package cn.studykid.multiagent.infrastructure.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.core.page.PageCursor;
import cn.studykid.multiagent.core.reference.TutoringVocabulary;
import cn.studykid.multiagent.core.tenant.SubjectContext;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowBinding;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowHooks;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowReporter;
import cn.studykid.multiagent.infrastructure.security.ScopedDataAccess;
import cn.studykid.multiagent.ports.persistence.Page;
import cn.studykid.multiagent.ports.persistence.RunListQueryPort;

/**
 * Run 列表查询实现（P4-D13 / G7）。
 *
 * <p>⚠️ <b>租户隔离是双层的</b>（与 {@code JdbcRunQueryRepository} 同源铁律）：
 * SQL 显式携带 {@code tenant_id = ?}（第一道）+ 表上强制 RLS（第二道）。
 * 任一层被误改，另一层仍能挡住。
 *
 * <p>⚠️ <b>排序必须是全序</b>：{@code ORDER BY created_at DESC, run_id DESC}。
 * 只按 {@code created_at} 排时同微秒多行顺序不确定，
 * 游标翻页会出现重复或漏行（反证 A-2）。
 */
@Repository
@ConditionalOnProperty(name = "persistence.mybatis.enabled", havingValue = "false", matchIfMissing = true)
public class JdbcRunListQueryRepository implements RunListQueryPort {

    /**
     * ⚠️ <b>{@code tenant_id} 条件不可删除</b>（反证 A-1）。
     *
     * <p>多取 {@code limit + 1} 行用于判断「是否还有下一页」——
     * 比多发一次 {@code COUNT} 查询便宜得多，且不会在并发下与 COUNT 结果不一致。
     *
     * <p>⚠️ <b>只取两个 JSONB 标量字段，不取整个 {@code input_payload}</b>：
     * {@code input_payload} 里除了学科/年级，还有学生提交的题干与解答原文。
     * 用 {@code ->>} 精确取两个键，而不是把整列拉进内存再解析——
     * 后者会让「不小心多读一个字段」变成一次静默的隐私事故。
     */
    private static final String SQL_BASE = """
            SELECT tenant_id::text AS tenant_id, run_id, state, created_at, completed_at, attempt, error_code,
                   input_payload->>'subject' AS subject,
                   input_payload->>'grade'   AS grade
            FROM run
            WHERE tenant_id = ?::uuid
            """;

    private final DataSource dataSource;

    // ── P1 影子态接入（12 文 §4.5 / 13 文 §5.2）──────────────────────────────
    // 默认全部为 null / false ⇒ 现有行为 100% 不变；仅当显式注入且 shadowEnabled=true 时生效。
    // ⚠️ 非 final：由 Spring 启动期通过 {@link #bindAuthzShadow} 一次性绑定，之后只读。
    //    容器里没有 AuthzShadowBinding 时保持构造期默认值（null / false）⇒ 零行为变更。
    private @Nullable ScopedDataAccess scoped;
    private @Nullable AuthzShadowReporter reporter;
    private boolean shadowEnabled;
    private @NonNull String sentinelTenantId;

    /** Spring 装配入口（保持既有单参构造，确保 @Repository 装配不变）。 */
    @Autowired
    public JdbcRunListQueryRepository(@NonNull DataSource dataSource) {
        this(dataSource, null, null, false, "");
    }

    /**
     * 影子态接线构造（P1 由配置在开启染色时调用；测试用）。
     *
     * @param shadowEnabled 是否启用影子比对 + 染色（P1 默认 false；P2 翻转为 true 进入阻断模式）
     * @param sentinelTenantId 哨兵（共享）租户标识，与 {@code SqlScopeTranslator} 共用配置项
     */
    JdbcRunListQueryRepository(@NonNull DataSource dataSource,
                               @Nullable ScopedDataAccess scoped,
                               @Nullable AuthzShadowReporter reporter,
                               boolean shadowEnabled,
                               @NonNull String sentinelTenantId) {
        this.dataSource = dataSource;
        this.scoped = scoped;
        this.reporter = reporter;
        this.shadowEnabled = shadowEnabled;
        this.sentinelTenantId = sentinelTenantId;
    }

    @Override
    public @NonNull Page<RunListItem> list(@NonNull String tenantId,
                                           @Nullable String requesterId,
                                           @Nullable String state,
                                           @Nullable String subject,
                                           @Nullable Instant from,
                                           @Nullable Instant to,
                                           @Nullable String cursor,
                                           int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须为正数: " + limit);
        }
        PageCursor after = cursor == null || cursor.isBlank()
                ? null : PageCursor.decode(cursor);

        StringBuilder sql = new StringBuilder(SQL_BASE);
        List<Object> params = new ArrayList<>();
        params.add(tenantId);

        if (requesterId != null) {
            // ⚠️ 受众隔离（F1）：只返回本人创建的 Run。
            //    `requester_id IS NULL` 的历史行 / 运维代操作行对受众不可见——
            //    「没有归属证明的数据」宁可不展示，也不能当作「本人数据」放行。
            sql.append(" AND requester_id = ?::uuid");
            params.add(requesterId);
        }
        if (state != null) {
            sql.append(" AND state = ?::run_state");
            params.add(state);
        }
        if (subject != null) {
            // ⚠️ 用 btrim 比较：存的值可能带首尾空白（客户端可控），
            //    而展示侧（TutoringVocabulary）也只做 trim——
            //    两边口径必须一致，否则会出现「列表里显示数学，却筛不出数学」。
            sql.append(" AND btrim(input_payload->>'subject') = ?");
            params.add(subject.trim());
        }
        if (from != null) {
            sql.append(" AND created_at >= ?");
            params.add(Timestamp.from(from));
        }
        if (to != null) {
            sql.append(" AND created_at <= ?");
            params.add(Timestamp.from(to));
        }
        if (after != null) {
            // ⚠️ 全序比较：时间「小于」**或**时间相等而 id「小于」。
            //    写成 (created_at, run_id) < (?, ?) 需要 PG 的行值比较，
            //    这里展开成显式 OR 以免因类型推断踩坑（V4 曾栽在 createArrayOf 上）。
            sql.append(" AND (created_at < ? OR (created_at = ? AND run_id < ?::uuid))");
            params.add(Timestamp.from(after.createdAt()));
            params.add(Timestamp.from(after.createdAt()));
            params.add(after.id());
        }

        sql.append(" ORDER BY created_at DESC, run_id DESC LIMIT ?");
        params.add(limit + 1);

        // ⚠️ P1 影子比对：本次查询绑定租户 vs Gate 算出的 ScopeFilter（只记日志）。
        shadowCompare("run", tenantId, true);
        try (TenantConnectionLease lease = tenantConnection(tenantId)) {
            List<RunListItem> rows = new ArrayList<>(limit);
            try (PreparedStatement ps = lease.connection().prepareStatement(sql.toString())) {
                bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        // ⚠️ 主体 = 本次查询的租户；行归属 = 结果行真实 tenant_id。
                        //    两者必须分开传：用行租户当主体是同义反复（恒不违例）。
                        shadowTaint("run", tenantId, rs.getString("tenant_id"));
                        rows.add(read(rs));
                    }
                }
            }
            // ⚠️ 多取的那一行**只用于判定**，不返回——
            //    返回它会让本页实际条数超过调用方请求的 limit。
            String next = null;
            if (rows.size() > limit) {
                rows.remove(rows.size() - 1);
                RunListItem last = rows.get(rows.size() - 1);
                next = new PageCursor(last.createdAt(), last.runId()).encode();
            }
            return Page.of(rows, next);
        } catch (SQLException e) {
            throw new IllegalStateException("查询 Run 列表失败", e);
        }
    }

    private static RunListItem read(ResultSet rs) throws SQLException {
        Timestamp completed = rs.getTimestamp("completed_at");
        return new RunListItem(
                rs.getString("run_id"),
                rs.getString("state"),
                rs.getTimestamp("created_at").toInstant(),
                completed == null ? null : completed.toInstant(),
                rs.getInt("attempt"),
                rs.getString("error_code"),
                // ⚠️⛔ **fail-closed**：客户端可控的学科/年级，只有命中 DEC-6 词表
                //    才允许回显；不认识的一律当「没有」（null）。
                //    原样回显等于把请求体里的任意文本直接送到前端渲染。
                TutoringVocabulary.subject(rs.getString("subject")),
                TutoringVocabulary.grade(rs.getString("grade")));
    }

    private static void bind(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            ps.setObject(i + 1, params.get(i));
        }
    }

    // ══════════════════════════════════════════════════════════════
    // P1 影子态：比对 + 染色（默认不启用，不影响现有行为）
    // ══════════════════════════════════════════════════════════════

    /**
     * 影子态装配入口（P1 / 13 文 §5.2）：容器里<b>存在</b> {@code AuthzShadowBinding} 即开启，
     * 不存在即完全关闭（不存在「Bean 在、开关关」的半开状态）。
     *
     * <p>⚠️ 用 setter 而不是可选构造参数：本仓既有构造签名被 {@code @Repository} 装配依赖，
     * 改成可选构造参数会把「影子态」渗进每一个调用点。setter 只在启动期被调用一次。
     */
    @Autowired(required = false)
    public void bindAuthzShadow(@Nullable AuthzShadowBinding binding) {
        if (binding == null) {
            return;
        }
        this.scoped = binding.scoped();
        this.reporter = binding.reporter();
        this.shadowEnabled = true;
        this.sentinelTenantId = binding.sentinelTenantId();
    }

    /** 影子比对：Gate 算出的 ScopeFilter vs 本次查询的绑定租户（只记日志）。 */
    void shadowCompare(@NonNull String resourceType,
                       @NonNull String boundTenantId,
                       boolean hasTenantPredicate) {
        AuthzShadowHooks.compare(scoped, reporter, shadowEnabled, resourceType,
                SubjectContext.forTenant(boundTenantId), boundTenantId, hasTenantPredicate);
    }

    /**
     * 逐行染色：结果行的真实 {@code tenant_id} 与 Gate 算出的 ScopeFilter 比对。
     *
     * <p>⚠️ <b>主体与行归属必须分开</b>（12 文 §4.5 · D6/D13）：
     * 主体是<b>发起本次查询的租户</b>，行归属从<b>结果行</b>读出的 {@code tenant_id}。
     * 若把行租户当作主体（{@code SubjectContext.forTenant(rowTenantId)}），
     * 策略引擎必然算出 {@code TenantOnly(行租户)}，逐行比对恒通过 ——
     * 染色会退化成「永远 0 违例」的装饰。
     *
     * @param subjectTenantId 发起查询的租户（主体）
     * @param rowTenantId 结果行的真实租户（不可用查询绑定租户代替，否则是同义反复）
     */
    void shadowTaint(@NonNull String resourceType,
                     @Nullable String subjectTenantId,
                     @Nullable String rowTenantId) {
        if (rowTenantId == null) {
            return;
        }
        // 哨兵行可由「行租户 == 哨兵租户」直接推断，无需调用方另行声明。
        boolean shared = !sentinelTenantId.isBlank() && sentinelTenantId.equals(rowTenantId);
        AuthzShadowHooks.taint(scoped, reporter, shadowEnabled, resourceType, sentinelTenantId,
                subjectTenantId == null ? null : SubjectContext.forTenant(subjectTenantId),
                rowTenantId, shared);
    }

    private TenantConnectionLease tenantConnection(String tenantId) throws SQLException {
        Connection outer = TransactionalConnectionHolder.current();
        if (outer != null) {
            return TenantConnectionLease.borrowed(outer);
        }
        return TransactionalConnectionHolder.openTenantConnection(
                dataSource, new TenantContext(tenantId, "system", java.util.Set.of(), 0L));
    }
}