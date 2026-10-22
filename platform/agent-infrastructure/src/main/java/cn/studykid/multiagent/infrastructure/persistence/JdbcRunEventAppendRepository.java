package cn.studykid.multiagent.infrastructure.persistence;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.RunEventPort;

/**
 * Run 事件追加实现（P4-D12 / G4）。
 *
 * <p>⚠️ <b>序号分配必须串行</b>：{@code run_event.sequence_no} 在
 * {@code (tenant_id, run_id)} 内唯一（{@code run_event_seq_unique}），
 * 且是 SSE 续传依据（必须单调递增）。
 * 故本实现<b>先锁 run 行</b>再取 {@code MAX+1}——
 * 锁 run 行天然按 run 串行（一个 Run 只有一个 Worker 在执行），
 * 且 run 行本就是热点（租约续期也在写它），不引入新的争用点。
 *
 * <p>⚠️ <b>不选 {@code INSERT ... MAX+1}（无锁）</b>：并发下会撞唯一约束。
 * <p>⚠️ <b>不选 {@code pg_advisory_xact_lock}</b>：引入 PG 专有依赖，
 * 且与本项目「按 run 串行」的既有模型不直观。
 */
@Repository
public class JdbcRunEventAppendRepository implements RunEventPort {

    /**
     * 锁 run 行并取下一个序号，随后插入事件。
     *
     * <p>⚠️ <b>三步必须同一事务</b>：锁、算序号、插入。
     * 分开做等于没有锁。
     */
    private static final String SQL_APPEND = """
            INSERT INTO run_event (tenant_id, run_id, sequence_no, event_type, payload)
            VALUES (?::uuid, ?::uuid, ?, ?, ?::jsonb)
            """;

    private static final String SQL_NEXT_SEQUENCE = """
            SELECT COALESCE(MAX(sequence_no), 0) + 1
            FROM run_event
            WHERE tenant_id = ?::uuid AND run_id = ?::uuid
            """;

    /** 锁 run 行——串行化同一 Run 的事件追加。 */
    private static final String SQL_LOCK_RUN = """
            SELECT run_id FROM run
            WHERE tenant_id = ?::uuid AND run_id = ?::uuid
            FOR UPDATE
            """;

    private final DataSource dataSource;

    public JdbcRunEventAppendRepository(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void append(@NonNull String tenantId,
                       @NonNull String runId,
                       @NonNull String eventType,
                       @NonNull Map<String, Object> payload) {
        try (TenantConnectionLease lease = tenantConnection(tenantId)) {
            // ⚠️ 自持连接已由 openTenantConnection 置 autoCommit=false；
            //    此处不再经租约做额外回滚——事务边界由下面的 commit/rollback 收口。
            lease.connection().setAutoCommit(false);
            try {
                long next = nextSequence(lease.connection(), tenantId, runId);
                insert(lease.connection(), tenantId, runId, next, eventType, payload);
                lease.connection().commit();
            } catch (SQLException | RuntimeException e) {
                lease.connection().rollback();
                throw e;
            } finally {
                // ⚠️ 还原 autoCommit，避免租约 close() 在归还连接前再次显式回滚
                //    （autoCommit 有效时回滚会抛 WARN —— 这正是日志风暴的根因）。
                lease.connection().setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("追加 Run 事件失败", e);
        }
    }

    private long nextSequence(java.sql.Connection conn, String tenantId, String runId)
            throws SQLException {
        // ⚠️ 先锁 run 行：没有它，同一 Run 的并发追加会算出相同序号。
        try (PreparedStatement ps = conn.prepareStatement(SQL_LOCK_RUN)) {
            ps.setString(1, tenantId);
            ps.setString(2, runId);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) {
                    // ⚠️ Run 不存在（已被清理？）→ 不写事件。
                    //    写入会撞外键，且事件对已消失的 Run 无意义。
                    throw new IllegalStateException("Run 不存在，拒绝追加事件: " + runId);
                }
            }
        }
        try (PreparedStatement ps = conn.prepareStatement(SQL_NEXT_SEQUENCE)) {
            ps.setString(1, tenantId);
            ps.setString(2, runId);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 1L;
            }
        }
    }

    private void insert(java.sql.Connection conn, String tenantId, String runId,
                        long sequenceNo, String eventType, Map<String, Object> payload)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SQL_APPEND)) {
            ps.setString(1, tenantId);
            ps.setString(2, runId);
            ps.setLong(3, sequenceNo);
            ps.setString(4, eventType);
            ps.setString(5, payloadJson(payload));
            ps.executeUpdate();
        }
    }

    /**
     * 载荷转 JSON。
     *
     * <p>⚠️ <b>只允许标量</b>：与 SSE 白名单一致（P3-D10 §5.2 S-6）。
     * 非标量值（Map/List）<b>直接拒绝</b>而不是静默丢弃——
     * 静默丢弃会让「我明明写了却没看到」这类问题极难排查。
     */
    private static String payloadJson(Map<String, Object> payload) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : payload.entrySet()) {
            Object v = e.getValue();
            if (v == null) {
                continue;
            }
            if (!(v instanceof String || v instanceof Number || v instanceof Boolean)) {
                throw new IllegalArgumentException(
                        "事件载荷只允许标量（String/Number/Boolean），实际: " + e.getKey()
                                + "=" + v.getClass().getSimpleName());
            }
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(escape(e.getKey())).append("\":");
            if (v instanceof String str) {
                sb.append('"').append(escape(str)).append('"');
            } else {
                sb.append(v);
            }
        }
        return sb.append('}').toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    /**
     * 取得已绑定租户的连接。
     *
     * <p>⚠️ 与 {@code JdbcRunQueryRepository} 同源铁律：
     * {@code set_config(is_local=true)} <b>必须配合事务</b>，自动提交下立即失效。
     */
    private TenantConnectionLease tenantConnection(String tenantId) throws SQLException {
        java.sql.Connection outer = TransactionalConnectionHolder.current();
        if (outer != null) {
            return TenantConnectionLease.borrowed(outer);
        }
        return TransactionalConnectionHolder.openTenantConnection(
                dataSource, systemContext(tenantId));
    }

    private static TenantContext systemContext(String tenantId) {
        return new TenantContext(tenantId, "system", java.util.Set.of(), 0L);
    }
}
