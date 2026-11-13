package cn.studykid.multiagent.infrastructure.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import cn.studykid.multiagent.core.tenant.SubjectContext;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowBinding;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowHooks;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowReporter;
import cn.studykid.multiagent.infrastructure.security.ScopedDataAccess;
import cn.studykid.multiagent.ports.persistence.ExecutionSnapshotPort;

/**
 * {@link ExecutionSnapshotPort} 的 PostgreSQL 实现。
 *
 * <p><b>⚠️ 为什么必须走 {@link TransactionalConnectionHolder}</b>（P2-D05 铁律）：
 * 解析 SQL 查的是 {@code execution_snapshot}，该表
 * {@code ENABLE + FORCE ROW LEVEL SECURITY}，策略为
 * {@code tenant_id = current_tenant_id()}。未绑定 {@code app.tenant_id}
 * 时可见 <b>0 行</b>，症状是「Agent 明明有快照，却解析不出来」——
 * 与「Agent 真的没发布」<b>症状完全相同</b>，会把排查方向带偏。
 * ⚠️ RLS 退役期（改造计划 WP-M1-2）：GUC 绑定在 holder 中心统一执行，保留至
 * M4 退役批次；SQL 三表显式 {@code tenant_id} 等值谓词，RLS 退役后功能不变。
 *
 * <p><b>⚠️ 嵌套事务</b>：本类在 {@code RunCreationService} 的写入事务内被调用，
 * {@link TransactionalConnectionHolder#inTransaction} 检测到外层事务已激活，
 * 会<b>复用同一连接且不提交</b>，故解析与写入天然同事务——
 * 不存在「解析完再改版」的窗口。
 *
 * <p><b>影子态（WP-M1-2）</b>：入口 1:1 {@code compare("execution_snapshot")}；
 * SELECT 未取 {@code tenant_id} 列（仅 snapshot_id/version_number），无行租户可染色 ⇒
 * compare-only（与聚合面同一纪律）。
 */
public class JdbcExecutionSnapshotResolver implements ExecutionSnapshotPort {

    private static final Logger log = LoggerFactory.getLogger(JdbcExecutionSnapshotResolver.class);

    /**
     * 已发布版本的最新一条 → 其执行快照。
     *
     * <p>⚠️ <b>必须取 {@code es.snapshot_id} 而非 {@code es.version_id}</b>：
     * {@code execution_snapshot} <b>同时拥有</b> {@code snapshot_id}（自身主键）
     * 与 {@code version_id}（指向 {@code agent_version}）。而外键
     * {@code run_snapshot_fk} 声明为
     * {@code FOREIGN KEY (tenant_id, snapshot_id) REFERENCES execution_snapshot (tenant_id, snapshot_id)}
     * ——引的是<b>主键列</b>，不是 version_id。
     * 取错列的症状极具欺骗性：外键报错说
     * 「run 的 (tenant_id, snapshot_id) 不在 execution_snapshot 里」，
     * 而那条行**确实存在**，只是主键值对不上；
     * 排查方向会被带向「租户隔离有问题」或「数据没写进去」，
     * 而真因只是选错了列。
     *
     * <p>⚠️ {@code published_at IS NOT NULL} 是<b>硬过滤</b>：
     * 草稿版本不该被 Run 使用（草稿可能随时被改或删）。
     *
     * <p>⚠️ 三表都按 {@code tenant_id} 显式等值连接：即便 RLS 将来被误关，
     * 也不会跨租户取到快照。
     *
     * <p>⚠️ 内连接 {@code execution_snapshot}：版本已发布但尚未生成快照，
     * 同样属「不可用」，必须当 empty 返回（否则又回到外键报错）。
     *
     * <p>⚠️ {@code v.version_number} 与快照<b>同一条语句读出</b>（G-3）：
     * 拆成两次读会让「快照来自 v3、版本号来自 v4」的错配写进能力快照，
     * 而能力快照一旦落库就不可变 —— 失真无法事后修正。
     */
    private static final String SQL_RESOLVE = """
            SELECT es.snapshot_id::text, v.version_number
            FROM agent a
            JOIN agent_version v
              ON v.agent_id = a.agent_id AND v.tenant_id = a.tenant_id
            JOIN execution_snapshot es
              ON es.version_id = v.version_id AND es.tenant_id = v.tenant_id
            WHERE a.tenant_id = ?::uuid
              AND a.agent_id = ?::uuid
              AND v.published_at IS NOT NULL
            ORDER BY v.version_number DESC
            LIMIT 1
            """;

    private final DataSource dataSource;

    // ── 影子态接入（WP-M1-2，默认不启用零行为变更）────────────────────
    private @Nullable ScopedDataAccess scoped;
    private @Nullable AuthzShadowReporter reporter;
    private volatile boolean shadowEnabled;
    // ⚠️ 装配守卫按反射统一读取四字段（compare-only 仓也为保持契约一致而声明）
    private @NonNull String sentinelTenantId = "";

    public JdbcExecutionSnapshotResolver(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 影子态装配入口：容器里存在 {@link AuthzShadowBinding} 即开启（P1 影子期），
     * 不存在即完全关闭（零行为变更）。
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

    @Override
    @NonNull
    public Optional<SnapshotRef> resolveSnapshot(@NonNull String tenantId,
                                                  @NonNull String agentId) {
        // ⚠️ 非法 UUID 在 PostgreSQL 里会报 PSQLException（22P02）。
        //    那是**客户端**给的坏值，应落到 4xx；在此提前拦下，
        //    绝不能让一个明显的 400 冒泡成 500。
        if (!isUuid(tenantId) || !isUuid(agentId)) {
            log.debug("快照解析跳过：agentId 非 UUID");
            return Optional.empty();
        }
        // ⚠️ 影子比对（P1 只记日志）：Gate 算出的 ScopeFilter vs 本次绑定租户。
        shadowCompare(tenantId);
        return TransactionalConnectionHolder.inTransaction(
                dataSource,
                new TenantContext(tenantId, "system", Set.of(), 0L),
                conn -> doResolve(conn, tenantId, agentId));
    }

    private Optional<SnapshotRef> doResolve(Connection conn, String tenantId, String agentId)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SQL_RESOLVE)) {
            ps.setString(1, tenantId);
            ps.setString(2, agentId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new SnapshotRef(rs.getString(1), rs.getInt(2)));
            }
        }
    }

    private static boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // ── 影子辅助（守卫要求：声明与调用分离，入口真调用）────────────

    /**
     * 影子比对：Gate 算出的 ScopeFilter vs 本次绑定租户（只记日志）。
     * SELECT 未取 {@code tenant_id} 列，无行租户可染色 ⇒ compare-only。
     */
    void shadowCompare(@NonNull String boundTenantId) {
        AuthzShadowHooks.compare(scoped, reporter, shadowEnabled, "execution_snapshot",
                SubjectContext.forTenant(boundTenantId), boundTenantId, true);
    }
}