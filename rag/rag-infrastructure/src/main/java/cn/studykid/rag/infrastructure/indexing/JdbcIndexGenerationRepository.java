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
        List<Integer> rows = jdbcTemplate.query(
                "SELECT generation FROM rag_index_generation"
                        + " WHERE tenant_id = ?::uuid AND status = 'ACTIVE' LIMIT 1",
                (rs, i) -> rs.getInt(1),
                tenantId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public @NonNull List<IndexGeneration> listGenerations(@NonNull String tenantId) {
        // ⚠️ RLS 读策略允许「本租户 ∪ 全局」，故这里用调用方给的租户即可；
        //    不设会话变量是因为本方法可能被检索侧在独立事务中调用。
        List<IndexGeneration> rows = jdbcTemplate.query("""
                SELECT generation, embed_model, dimension, status,
                       indexed_chunks, total_chunks, note,
                       activated_at::text, retired_at::text, created_at::text, build_cursor
                  FROM rag_index_generation
                 WHERE tenant_id = ?::uuid
                 ORDER BY generation DESC
                """, (rs, i) -> mapRow(rs), tenantId);
        return rows == null ? List.<IndexGeneration>of() : rows;
    }

    @Override
    public @NonNull IndexGeneration createGeneration(@NonNull String tenantId,
                                                     @NonNull String embedModel,
                                                     int dimension,
                                                     long totalChunks) {
        return transactionTemplate.execute(status -> {
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
        jdbcTemplate.update("""
                UPDATE rag_index_generation
                   SET indexed_chunks = ?, total_chunks = ?, build_cursor = ?
                 WHERE tenant_id = ?::uuid AND generation = ?
                """,
                Math.max(0, indexedChunks), Math.max(0, totalChunks),
                buildCursor, tenantId, generation);
    }

    @Override
    public void updateProgress(@NonNull String tenantId, int generation, long indexedChunks) {
        jdbcTemplate.update("""
                UPDATE rag_index_generation
                   SET indexed_chunks = ?
                 WHERE tenant_id = ?::uuid AND generation = ?
                """, Math.max(0, indexedChunks), tenantId, generation);
    }

    @Override
    public @Nullable IndexGeneration findPendingBuild(@NonNull String tenantId) {
        // ⚠️ 「待构建」条件写成 `indexed < total OR total = 0`：
        //    · indexed < total —— 建到一半
        //    · total = 0       —— 刚创建、还没开跑（0 < 0 为假，必须单独放进来，
        //                        否则新建的代际永远不会被 worker 捡起）
        //    取 generation 最小的一个：多代际并存时不饿死先建的那个。
        List<IndexGeneration> rows = jdbcTemplate.query("""
                SELECT generation, embed_model, dimension, status,
                       indexed_chunks, total_chunks, note,
                       activated_at::text, retired_at::text, created_at::text, build_cursor
                  FROM rag_index_generation
                 WHERE tenant_id = ?::uuid
                   AND status = 'BUILDING'
                   AND (indexed_chunks < total_chunks OR total_chunks = 0)
                 ORDER BY generation ASC
                 LIMIT 1
                """, (rs, i) -> mapRow(rs), tenantId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ------------------------------------------------------------------

    private IndexGeneration findGeneration(@NonNull String tenantId, int generation) {
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
}
