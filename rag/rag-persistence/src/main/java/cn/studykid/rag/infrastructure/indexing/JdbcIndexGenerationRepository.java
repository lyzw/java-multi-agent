package cn.studykid.rag.infrastructure.indexing;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import cn.studykid.rag.core.indexing.IndexGeneration;
import cn.studykid.rag.ports.IndexGenerationPort;

/**
 * 索引代际端口的 JDBC 实现（RAG-D13 §3.5.2）。
 *
 * <p><b>⚠️ 所有方法都在「已建立作用域会话」的事务内执行</b>：
 * {@code rag_index_generation} 与其它租户表一样受 RLS FORCE 约束，
 * 不设会话租户就去查，只会得到空结果（甚至写入被静默拒绝）。
 *
 * <p><b>⚠️ 「至多一个 ACTIVE」由本类保证</b>：
 * 激活动作在同一事务里「先把原 ACTIVE 置 RETIRED，再把目标置 ACTIVE」。
 * 若拆成两次独立提交，中间态会出现零个 ACTIVE——此时并发检索会查不到任何向量，
 * 表现为「切代际的瞬间全站搜不到东西」。
 */
public class JdbcIndexGenerationRepository implements IndexGenerationPort {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public JdbcIndexGenerationRepository(@NonNull JdbcTemplate jdbcTemplate,
                                         @NonNull TransactionTemplate transactionTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public @Nullable Integer activeGeneration(@NonNull String tenantId) {
        // ⚠️ 这是**跨模块共享的读方法**（检索侧也会调），故用调用方给的租户
        //    而非会话变量——检索侧在不同事务里跑，会话租户不一定已设置。
        //    RLS 仍会兜底：不一致的租户查不到行。
        List<Integer> rows = transactionTemplate.execute(status -> {
            bindTenant(tenantId);
            return jdbcTemplate.query(
                    "SELECT generation FROM rag_index_generation"
                            + " WHERE tenant_id = ?::uuid AND status = 'ACTIVE' LIMIT 1",
                    (rs, i) -> rs.getInt(1),
                    tenantId);
        });
        return rows == null || rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public @NonNull List<IndexGeneration> listGenerations(@NonNull String tenantId) {
        // ⚠️⚠️ **必须显式设置会话租户**（2026-11-04 真实库实测踩到）：
        //    RLS 读策略是 `tenant_id IN (rag_current_tenant(), rag_global_tenant_id())`。
        //    不设会话变量时 `rag_current_tenant()` 返回 NULL，于是**本租户的行被整行过滤**——
        //    接口返回空数组，而库里明明有数据
        //    （表现为"新建代际后列表还是空的"，极易被误判成"没建成功"）。
        //    这与 `JdbcCorpusManagementRepository` 用 `RagSessionScope.apply` 的理由完全一致。
        //
        // ⚠️ 用 transactionTemplate 而非裸 query：`set_config(..., true)` 是**事务局部**的，
        //    在 autocommit 下当场失效。
        //
        // ⚠️ 全局哨兵**不能** set_config（会触发 `rag_current_tenant()` 的 RAISE），
        //    故全局桶走「不设会话」分支——此时策略只剩全局行一支，正是想要的。
        List<IndexGeneration> rows = transactionTemplate.execute(status -> {
            bindTenant(tenantId);
            return jdbcTemplate.query("""
                    SELECT generation, embed_model, dimension, status,
                           indexed_chunks, total_chunks, note,
                           activated_at::text, retired_at::text, created_at::text, build_cursor
                      FROM rag_index_generation
                     WHERE tenant_id = ?::uuid
                     ORDER BY generation DESC
                    """, (rs, i) -> mapRow(rs), tenantId);
        });
        return rows == null ? List.<IndexGeneration>of() : rows;
    }

    @Override
    public @NonNull IndexGeneration createGeneration(@NonNull String tenantId,
                                                     @NonNull String embedModel,
                                                     int dimension,
                                                     long totalChunks) {
        return transactionTemplate.execute(status -> {
            // ⚠️ 不绑定会话租户会触发 RLS 写策略拒绝（500，实测）
            bindTenant(tenantId);
            // ⚠️ 代际号 = 当前最大 + 1（含已退役的，避免复用旧号——
            //    复用会让「回滚到代际 3」指向一个已经是新模型的代际）。
            Integer max = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(MAX(generation), 0) FROM rag_index_generation"
                            + " WHERE tenant_id = ?::uuid",
                    Integer.class, tenantId);
            int next = (max == null ? 0 : max) + 1;
            jdbcTemplate.update("""
                    INSERT INTO rag_index_generation
                        (tenant_id, generation, embed_model, dimension, status,
                         indexed_chunks, total_chunks, scope)
                    VALUES (?::uuid, ?, ?, ?, 'BUILDING', 0, ?, ?)
                    """,
                    tenantId, next, embedModel, dimension, Math.max(0, totalChunks),
                    isGlobal(tenantId) ? "PLATFORM" : "TENANT");
            return findGeneration(tenantId, next);
        });
    }

    @Override
    public @NonNull IndexGeneration activateGeneration(@NonNull String tenantId, int generation) {
        return transactionTemplate.execute(status -> {
            // ⚠️ 不绑定会话租户会触发 RLS 写策略拒绝（500，实测）
            bindTenant(tenantId);
            IndexGeneration target = findGeneration(tenantId, generation);
            // ⚠️ BUILDING 不可激活：只建了一半的代际上线，等于让检索去查
            //    一半是空向量的索引——表现为「有些片段永远召回不到」，且无任何报错。
            if (!target.isActivatable()) {
                throw new IllegalArgumentException(
                        "代际 " + generation + " 处于 " + target.status()
                                + "，构建中的代际不可激活（请等构建完成）");
            }
            if (target.status() == IndexGeneration.Status.ACTIVE) {
                // 已是当前代际：幂等返回，不产生无意义的写与状态抖动。
                return target;
            }
            // ⚠️ 两条 UPDATE 必须在同一事务：拆开会出现「零个 ACTIVE」的中间态，
            //    并发检索在那一瞬查不到任何向量（全站搜不到东西）。
            jdbcTemplate.update("""
                    UPDATE rag_index_generation
                       SET status = 'RETIRED', retired_at = now()
                     WHERE tenant_id = ?::uuid AND status = 'ACTIVE'
                    """, tenantId);
            jdbcTemplate.update("""
                    UPDATE rag_index_generation
                       SET status = 'ACTIVE', activated_at = now(), retired_at = NULL
                     WHERE tenant_id = ?::uuid AND generation = ?
                    """, tenantId, generation);
            return findGeneration(tenantId, generation);
        });
    }

    @Override
    public void updateBuildState(@NonNull String tenantId, int generation,
                                 long indexedChunks, long totalChunks,
                                 @Nullable String buildCursor) {
        // ⚠️ 进度与游标一次写完：拆开会在崩溃时留下矛盾状态，
        //    续跑时重复嵌入或跳过片段。
        // ⚠️ 必须在事务内绑定会话租户：裸 update 既无会话租户（RLS 拒绝），
        //    也无法让 set_config 生效（它是事务局部的）。
        transactionTemplate.executeWithoutResult(status -> {
            bindTenant(tenantId);
            jdbcTemplate.update("""
                    UPDATE rag_index_generation
                       SET indexed_chunks = ?, total_chunks = ?, build_cursor = ?
                     WHERE tenant_id = ?::uuid AND generation = ?
                    """,
                    Math.max(0, indexedChunks), Math.max(0, totalChunks),
                    buildCursor, tenantId, generation);
        });
    }

    @Override
    public void updateProgress(@NonNull String tenantId, int generation, long indexedChunks) {
        transactionTemplate.executeWithoutResult(status -> {
            bindTenant(tenantId);
            jdbcTemplate.update("""
                    UPDATE rag_index_generation
                       SET indexed_chunks = ?
                     WHERE tenant_id = ?::uuid AND generation = ?
                    """, Math.max(0, indexedChunks), tenantId, generation);
        });
    }

    @Override
    public @Nullable IndexGeneration findPendingBuild(@NonNull String tenantId) {
        // ⚠️⚠️ 排除已标记完成的行（note 含完成标记）：
        //    否则一个建完的代际会被 worker 每 2 秒捡起一次，反复扫空集刷日志。
        // ⚠️ 「待构建」条件写成 `indexed < total OR total = 0`：
        //    · indexed < total —— 建到一半
        //    · total = 0       —— 刚创建、还没开跑（0 < 0 为假，必须单独放进来，
        //                        否则新建的代际永远不会被 worker 捡起）
        //    取 generation 最小的一个：多代际并存时不饿死先建的那个。
        List<IndexGeneration> rows = transactionTemplate.execute(status -> {
            bindTenant(tenantId);
            return jdbcTemplate.query("""
                    SELECT generation, embed_model, dimension, status,
                           indexed_chunks, total_chunks, note,
                           activated_at::text, retired_at::text, created_at::text, build_cursor
                      FROM rag_index_generation
                     WHERE tenant_id = ?::uuid
                       AND status = 'BUILDING'
                       AND COALESCE(note, '') NOT LIKE '%已完成%'
                       -- ⚠️ **「未标记完成」是唯一条件**：
                       --    不能用 `indexed < total` 判断——计数相等只说明"片段都处理过了"，
                       --    还需再扫一遍确认没有新增片段，才能标记完成；
                       --    若按计数判断，代际会永远停在「5/5 但未完成」，无法切换。
                       --    用「是否已标记」判断既覆盖"建到一半"、也覆盖"待收尾"、
                       --    也覆盖"刚创建未开跑"，一条条件替代三个。
                     ORDER BY generation ASC
                     LIMIT 1
                    """, (rs, i) -> mapRow(rs), tenantId);
        });
        return rows == null || rows.isEmpty() ? null : rows.get(0);
    }

    // ------------------------------------------------------------------

    private IndexGeneration findGeneration(@NonNull String tenantId, int generation) {
        // ⚠️ 调用方（create/activate）已在事务内并绑定过会话租户，这里不再重复绑定。
        List<IndexGeneration> rows = jdbcTemplate.query("""
                SELECT generation, embed_model, dimension, status,
                       indexed_chunks, total_chunks, note,
                       activated_at::text, retired_at::text, created_at::text, build_cursor
                  FROM rag_index_generation
                 WHERE tenant_id = ?::uuid AND generation = ?
                """, (rs, i) -> mapRow(rs), tenantId, generation);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("代际不存在：" + generation);
        }
        return rows.get(0);
    }

    private static IndexGeneration mapRow(ResultSet rs) throws SQLException {
        return new IndexGeneration(
                rs.getInt("generation"),
                rs.getString("embed_model"),
                rs.getInt("dimension"),
                IndexGeneration.Status.valueOf(rs.getString("status")),
                rs.getLong("indexed_chunks"),
                rs.getLong("total_chunks"),
                rs.getString("note"),
                rs.getString("activated_at"),
                rs.getString("retired_at"),
                rs.getString("created_at"),
                rs.getString("build_cursor"));
    }

    private static boolean isGlobal(@NonNull String tenantId) {
        return cn.studykid.rag.core.scope.RagScope.GLOBAL_TENANT_ID.equalsIgnoreCase(tenantId);
    }

    @Override
    public void markBuildFinished(@NonNull String tenantId, int generation) {
        transactionTemplate.executeWithoutResult(status -> {
            bindTenant(tenantId);
            // ⚠️ 收尾三件事一起做（同一事务）：
            //    ① total 对齐为实际处理数（进度显示 100%，不再随扫描漂移）
            //    ② 写入完成标记（findPendingBuild 据此不再捡起）
            //    ③ 保留 status=BUILDING —— 构建完成 ≠ 自动上线，是否切换由人决定
            jdbcTemplate.update("""
                    UPDATE rag_index_generation
                       SET total_chunks = indexed_chunks,
                           note = CASE WHEN COALESCE(note,'') LIKE '%已完成%' THEN note
                                       ELSE COALESCE(note || ' · ', '') || '构建已完成' END
                     WHERE tenant_id = ?::uuid AND generation = ?
                    """, tenantId, generation);
        });
    }

    // ------------------------------------------------------------------
    // ⚠️⚠️ RLS 会话租户（2026-11-04 真实库实测：漏了会整片失效）
    //
    // `rag_index_generation` 是 FORCE RLS 表，读写策略都以
    // `rag_current_tenant()` 为准；该函数在**未设会话变量时返回 NULL**，
    // 于是：读 → 行被整行过滤（接口返回空数组，像"没建成功"）；
    //       写 → 直接被策略拒绝（500）。
    //
    // 单测用 mock 的 JdbcTemplate 完全覆盖不到这一点——只有真实库能暴露。
    // 故**所有**访问该表的方法都必须在事务内先调 bindTenant()。
    // ------------------------------------------------------------------

    /**
     * 在**当前事务内**绑定会话租户。
     *
     * @param tenantId 租户 ID；全局哨兵时**不绑定**（填哨兵会让
     *                 {@code rag_current_tenant()} 直接 RAISE，见 V8 封堵）
     */
    private void bindTenant(@NonNull String tenantId) {
        if (isGlobal(tenantId)) {
            return;
        }
        // ⚠️ set_config 是 SELECT（返回一行），必须用 queryForObject；
        //    用 update() 会抛「A result was returned when none was expected」。
        jdbcTemplate.queryForObject(
                "SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId);
    }
}
