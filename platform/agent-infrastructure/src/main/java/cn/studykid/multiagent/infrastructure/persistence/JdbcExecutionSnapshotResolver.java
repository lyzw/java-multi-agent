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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.core.tenant.TenantContext;
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
 *
 * <p><b>⚠️ 嵌套事务</b>：本类在 {@code RunCreationService} 的写入事务内被调用，
 * {@link TransactionalConnectionHolder#inTransaction} 检测到外层事务已激活，
 * 会<b>复用同一连接且不提交</b>，故解析与写入天然同事务——
 * 不存在「解析完再改版」的窗口。
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
     */
    private static final String SQL_RESOLVE = """
            SELECT es.snapshot_id::text
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

    public JdbcExecutionSnapshotResolver(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    @NonNull
    public Optional<String> resolveSnapshotId(@NonNull String tenantId, @NonNull String agentId) {
        // ⚠️ 非法 UUID 在 PostgreSQL 里会报 PSQLException（22P02）。
        //    那是**客户端**给的坏值，应落到 4xx；在此提前拦下，
        //    绝不能让一个明显的 400 冒泡成 500。
        if (!isUuid(tenantId) || !isUuid(agentId)) {
            log.debug("快照解析跳过：agentId 非 UUID");
            return Optional.empty();
        }
        return TransactionalConnectionHolder.inTransaction(
                dataSource,
                new TenantContext(tenantId, "system", Set.of(), 0L),
                conn -> doResolve(conn, tenantId, agentId));
    }

    private Optional<String> doResolve(Connection conn, String tenantId, String agentId)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SQL_RESOLVE)) {
            ps.setString(1, tenantId);
            ps.setString(2, agentId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
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
}