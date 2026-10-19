package cn.studykid.multiagent.infrastructure.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.core.idempotency.IdempotencyDecision;
import cn.studykid.multiagent.core.idempotency.IdempotencyKey;
import cn.studykid.multiagent.core.idempotency.RequestDigest;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.OutboxMessage;
import cn.studykid.multiagent.ports.persistence.RunCreationPort;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;

/**
 * {@link RunCreationPort} 的 JDBC 实现。
 *
 * <p><b>原子性由数据库事务保证</b>（红线 3）：四条 INSERT 在同一连接、
 * 同一事务内执行，任一失败则整体回滚，不留半成品数据。
 *
 * <p>⚠️ <b>本类不自行 commit/rollback</b>——事务边界由
 * {@code RunCreationService.TransactionRunner} 控制。这是有意为之：
 * 若实现内部提交，异常路径上的回滚就会被绕过。
 */
@Repository
public class JdbcRunCreationRepository implements RunCreationPort {

    private static final Logger log = LoggerFactory.getLogger(JdbcRunCreationRepository.class);

    private final DataSource dataSource;

    public JdbcRunCreationRepository(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public @NonNull IdempotencyDecision findExistingResource(@NonNull String tenantId,
                                                              @NonNull IdempotencyKey key,
                                                              @NonNull RequestDigest digest) {
        String sql = """
                SELECT request_digest, resource_id
                FROM idempotency_record
                WHERE tenant_id = ?::uuid AND key_namespace = ? AND idempotency_key = ?
                """;
        // ⚠️ P2-D05：租约区分「自开」与「复用外层」——
        //    复用时不得 rollback/close，否则摧毁外层事务。
        try (TenantConnectionLease lease = tenantConnection(tenantId)) {
            Connection conn = lease.connection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, tenantId);
                ps.setString(2, key.namespace());
                ps.setString(3, key.keyValue());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        lease.rollbackQuietly();
                        return IdempotencyDecision.firstExecution("pending");
                    }
                    String storedDigest = rs.getString("request_digest");
                    String resourceId = rs.getString("resource_id");
                    lease.rollbackQuietly();
                    if (digest.value().equals(storedDigest)) {
                        return IdempotencyDecision.replay(resourceId);
                    }
                    log.warn("幂等冲突: tenant={}, namespace={}", tenantId, key.namespace());
                    return IdempotencyDecision.conflict();
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询幂等记录失败", e);
        }
    }

    @Override
    public @NonNull String insertRunAtomically(@NonNull String tenantId,
                                                @NonNull String runId,
                                                @NonNull String snapshotId,
                                                @NonNull IdempotencyKey key,
                                                @NonNull RequestDigest digest,
                                                @NonNull Map<String, Object> inputPayload,
                                                @NonNull String correlationId,
                                                @NonNull OutboxMessage agentPayload) {
        // 消息体安全校验：即便调用方误传敏感字段，也在入库前拦下
        OutboxMessage.assertPayloadSafe(agentPayload.toPayload());

        // ⚠️ P2-D05 §2 D-1：优先复用外层事务连接。
        //    此前此处无条件 dataSource.getConnection() 并自行 commit，
        //    导致应用层 TransactionRunner 的事务语义形同虚设——
        //    外层即使开了事务，本方法的写入也不在其中，回滚会漏掉它。
        //    现改为：外层有连接则复用（提交权归外层），否则自开自提交。
        if (TransactionalConnectionHolder.isActive()) {
            Connection outer = TransactionalConnectionHolder.current();
            try {
                insertAll(outer, tenantId, runId, snapshotId, key, digest,
                        inputPayload, correlationId, agentPayload);
                // ⚠️ 不在此处 commit —— 外层事务尚未结束，
                //    提前提交会让外层后续失败时无法回滚本次写入。
                return runId;
            } catch (SQLException | RuntimeException e) {
                log.warn("Run 原子写入失败（外层事务将回滚）: runId={}, 原因={}",
                        runId, e.getMessage());
                throw e instanceof RuntimeException re
                        ? re : new IllegalStateException("Run 原子写入失败", e);
            }
        }

        // 无外层事务 → 自开连接，完整管理提交与回滚
        try (Connection conn = dataSource.getConnection()) {
            boolean prevAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                // 绑定租户上下文（RLS 前置条件）——必须在任何业务 SQL 之前
                TenantSessionBinder.bind(conn, systemContext(tenantId));

                insertAll(conn, tenantId, runId, snapshotId, key, digest,
                        inputPayload, correlationId, agentPayload);

                conn.commit();
                if (log.isDebugEnabled()) {
                    log.debug("Run 原子提交完成: runId={}", runId);
                }
                return runId;
            } catch (SQLException | RuntimeException e) {
                // 任一失败 → 全部回滚，不留半成品
                try {
                    conn.rollback();
                    log.warn("Run 原子提交失败已回滚: runId={}, 原因={}", runId, e.getMessage());
                } catch (SQLException rollbackError) {
                    log.error("回滚失败", rollbackError);
                }
                throw e instanceof RuntimeException re ? re : new IllegalStateException("Run 原子提交失败", e);
            } finally {
                try {
                    conn.setAutoCommit(prevAutoCommit);
                } catch (SQLException ignored) {
                    // 连接即将归还池中，忽略
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("获取数据库连接失败", e);
        }
    }

    /**
     * 四者同写入（红线 3）。
     *
     * <p>⚠️ <b>不自行提交</b>：提交与回滚由调用方（事务边界）决定。
     * 若在此处 commit，异常路径上的回滚就会被绕过，
     * 留下「Run 已写但 Outbox 未写」的半成品状态——
     * 表现为 Run 永远不会被 Worker 领取，任务静默卡死。
     */
    private void insertAll(Connection conn, String tenantId, String runId, String snapshotId,
                           IdempotencyKey key, RequestDigest digest,
                           Map<String, Object> inputPayload, String correlationId,
                           OutboxMessage agentPayload) throws SQLException {
        // 1) Run
        insertRun(conn, tenantId, runId, snapshotId, key, digest, inputPayload, correlationId);
        // 2) 初始事件（SSE 续传起点，sequence_no 从 1 开始）
        insertInitialEvent(conn, tenantId, runId, correlationId);
        // 3) 幂等记录
        insertIdempotencyRecord(conn, tenantId, key, digest, runId);
        // 4) Outbox（唤醒信号）
        insertOutbox(conn, tenantId, runId, agentPayload);
    }

    private void insertRun(Connection conn, String tenantId, String runId, String snapshotId,
                           IdempotencyKey key, RequestDigest digest,
                           Map<String, Object> inputPayload, String correlationId)
            throws SQLException {
        // ⚠️ run_id / snapshot_id / tenant_id 均为 uuid 列，必须显式 ::uuid 转换，
        //    否则 setString 传入的 varchar 会被 PG 拒绝（类型不匹配）。
        String sql = """
                INSERT INTO run (run_id, tenant_id, snapshot_id, idempotency_key, request_digest,
                                 state, attempt, fencing_token, input_payload, correlation_id)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, 'QUEUED', 0, 0, ?::jsonb, ?)
                ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, runId);
            ps.setString(2, tenantId);
            ps.setString(3, snapshotId);
            ps.setString(4, key.keyValue());
            ps.setString(5, digest.value());
            ps.setString(6, toJson(inputPayload));
            ps.setString(7, correlationId);
            ps.executeUpdate();
        }
    }

    private void insertInitialEvent(Connection conn, String tenantId, String runId,
                                    String correlationId) throws SQLException {
        String sql = """
                INSERT INTO run_event (tenant_id, run_id, sequence_no, event_type, payload)
                VALUES (?::uuid, ?::uuid, 1, 'RUN_CREATED', jsonb_build_object('correlationId', ?::text))
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, runId);
            ps.setString(3, correlationId);
            ps.executeUpdate();
        }
        // ⚠️ P4-D02 D-1：在**同一事务内**发 NOTIFY。
        //    事务回滚则通知不发出 —— 不推送不存在的事件（原子性）。
        //    ⚠️ 载荷只含「坐标」（tenantId/runId/sequenceNo），
        //    **不含 payload** —— 否则事件内容绕过 SSE 载荷白名单（D-2）。
        notifyRunEvent(conn, tenantId, runId, 1L);
    }

    /**
     * 发出事件通知（P4-D02 / D-1）。
     *
     * <p>⚠️ <b>为何用 {@code SELECT ... INTO + pg_notify} 而非 {@code PERFORM}</b>：
     * {@code PERFORM pg_notify(...)} 在 JDBC {@code PreparedStatement} 下同样可用，
     * 但 {@code SELECT} 形式能拿到影响行数，便于断言「通知确实发出」。
     *
     * <p>⚠️ <b>失败不得影响业务</b>：通知是优化手段，不是正确性依赖——
     * 事件已落库，订阅者还有轮询兜底（D-3）。故此处吞掉异常并记 WARN。
     */
    private void notifyRunEvent(Connection conn, String tenantId, String runId, long sequenceNo) {
        String sql = """
                SELECT pg_notify('run_event', json_build_object(
                        'tenantId', ?::text, 'runId', ?::text, 'sequenceNo', ?::text)::text)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, runId);
            ps.setString(3, String.valueOf(sequenceNo));
            ps.executeQuery();
        } catch (SQLException e) {
            // ⚠️ 吞掉：事件已落库，订阅者靠轮询兜底（D-3）。
            //    让通知失败拖垮 Run 创建是本末倒置。
            log.warn("事件通知发送失败（已落库，订阅者将由轮询兜底）: runId={}, seq={}",
                    runId, sequenceNo, e);
        }
    }

    private void insertIdempotencyRecord(Connection conn, String tenantId, IdempotencyKey key,
                                         RequestDigest digest, String resourceId) throws SQLException {
        String sql = """
                INSERT INTO idempotency_record (tenant_id, key_namespace, idempotency_key,
                                                request_digest, resource_type, resource_id, expires_at)
                VALUES (?::uuid, ?, ?, ?, 'RUN', ?::uuid, now() + interval '24 hours')
                ON CONFLICT (tenant_id, key_namespace, idempotency_key) DO NOTHING
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, key.namespace());
            ps.setString(3, key.keyValue());
            ps.setString(4, digest.value());
            ps.setString(5, resourceId);
            ps.executeUpdate();
        }
    }

    private void insertOutbox(Connection conn, String tenantId, String runId,
                              OutboxMessage message) throws SQLException {
        String sql = """
                INSERT INTO outbox_event (tenant_id, run_id, aggregate_type, aggregate_id,
                                          event_type, payload, state, attempts)
                VALUES (?::uuid, ?::uuid, 'RUN', ?::uuid, 'RUN_DISPATCH', ?::jsonb, 'PENDING', 0)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, runId);
            ps.setString(3, runId);
            ps.setString(4, toJson(message.toPayload()));
            ps.executeUpdate();
        }
    }

    @Override
    public @Nullable RunRecord findRun(@NonNull String tenantId, @NonNull String runId) {
        String sql = """
                SELECT run_id, state::text, attempt, fencing_token,
                       lease_owner, lease_expires_at, created_at
                FROM run WHERE tenant_id = ? AND run_id = ?::uuid
                """;
        try (TenantConnectionLease lease = tenantConnection(tenantId)) {
            Connection conn = lease.connection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, tenantId);
                ps.setString(2, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        lease.rollbackQuietly();
                        return null;
                    }
                    Timestamp leaseExp = rs.getTimestamp("lease_expires_at");
                    RunRecord record = new RunRecord(
                            rs.getString("run_id"),
                            rs.getString("state"),
                            rs.getInt("attempt"),
                            rs.getLong("fencing_token"),
                            rs.getString("lease_owner"),
                            leaseExp == null ? null : leaseExp.toInstant(),
                            rs.getTimestamp("created_at").toInstant());
                    lease.rollbackQuietly();
                    return record;
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询 Run 失败", e);
        }
    }

    @Override
    public @NonNull OutboxBatch loadPendingOutbox(@NonNull String tenantId, int limit) {
        String sql = """
                SELECT outbox_id, run_id, event_type, payload, attempts
                FROM outbox_event
                WHERE tenant_id = ?::uuid AND state = 'PENDING'
                ORDER BY created_at
                LIMIT ?
                """;
        List<PendingOutbox> events = new ArrayList<>();
        try (TenantConnectionLease lease = tenantConnection(tenantId)) {
            Connection conn = lease.connection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, tenantId);
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        events.add(new PendingOutbox(
                                rs.getString("outbox_id"),
                                rs.getString("run_id"),
                                rs.getString("event_type"),
                                parseJsonObject(rs.getString("payload")),
                                rs.getInt("attempts")));
                    }
                }
            }
            lease.rollbackQuietly();
        } catch (SQLException e) {
            throw new IllegalStateException("查询待发布 Outbox 失败", e);
        }
        return new OutboxBatch(events);
    }

    @Override
    public void markOutboxPublished(@NonNull String tenantId, @NonNull String outboxId,
                                    @NonNull Instant publishedAt) {
        String sql = """
                UPDATE outbox_event
                SET state = 'PUBLISHED', published_at = ?
                WHERE tenant_id = ?::uuid AND outbox_id = ?::uuid
                """;
        try (TenantConnectionLease lease = tenantConnection(tenantId)) {
            Connection conn = lease.connection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setTimestamp(1, Timestamp.from(publishedAt));
                ps.setString(2, tenantId);
                ps.setString(3, outboxId);
                ps.executeUpdate();
                lease.commitQuietly();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("标记 Outbox 已发布失败", e);
        }
    }

    @Override
    public void markOutboxFailed(@NonNull String tenantId, @NonNull String outboxId,
                                 @NonNull String error) {
        String sql = """
                UPDATE outbox_event
                SET state = 'FAILED', attempts = attempts + 1, last_error = ?
                WHERE tenant_id = ?::uuid AND outbox_id = ?::uuid
                """;
        try (TenantConnectionLease lease = tenantConnection(tenantId)) {
            Connection conn = lease.connection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, error == null ? "unknown" : error.substring(0, Math.min(500, error.length())));
                ps.setString(2, tenantId);
                ps.setString(3, outboxId);
                ps.executeUpdate();
                lease.commitQuietly();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("标记 Outbox 失败状态失败", e);
        }
    }


    /**
     * 取得<b>已开启事务且已绑定租户上下文</b>的连接。
     *
     * <p>⚠️ 两处都不可省略，否则租户隔离会失效：
     * <ol>
     *   <li><b>必须关闭自动提交</b>——{@code set_config(..., is_local=true)}
     *       采用事务局部语义，在自动提交模式下每条语句独立事务，
     *       绑定会随语句结束立即失效（实测：自动提交可见 0 行，事务内可见 114 行）。</li>
     *   <li><b>必须绑定 app.tenant_id</b>——RLS 策略以它为依据，
     *       未绑定时所有行不可见（默认拒绝）。</li>
     * </ol>
     *
     * <p>调用方负责 commit/rollback；本方法不改变原连接的自动提交设置语义，
     * 由 {@link #closeAndRestore} 统一还原。
     */
    /**
     * 取租户连接：<b>优先复用外层事务连接</b>（P2-D05 §2 D-1）。
     *
     * <p>复用而非另开的原因：若外层事务中另开连接，
     * 本方法读到的可能是<b>外层未提交前的旧数据</b>，
     * 造成「自己刚写完却查不到」的假象。
     *
     * <p>⚠️ 复用时<b>不重复绑定租户</b>：外层连接已绑定，
     * 重复 {@code set_config} 无害但会掩盖「绑定被谁做的」这一事实。
     *
     * @return 连接；调用方负责 commit/rollback 与关闭
     */
    private TenantConnectionLease tenantConnection(String tenantId) throws SQLException {
        // ⚠️ RLS 铁律：外层已有连接时复用，绝不另开。
        //    另开会导致「写入在事务 A、读取在事务 B」，原子性失效。
        Connection outer = TransactionalConnectionHolder.current();
        if (outer != null) {
            return TenantConnectionLease.borrowed(outer);
        }
        Connection conn = dataSource.getConnection();
        conn.setAutoCommit(false);
        TenantSessionBinder.bind(conn, systemContext(tenantId));
        return TenantConnectionLease.open(conn);
    }

    private static TenantContext systemContext(String tenantId) {
        return new TenantContext(tenantId, "system", java.util.Set.of(), 0L);
    }

    /** 最小化 JSON 序列化（避免引入完整 JSON 库到基础设施层）。 */
    private static String toJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(e.getKey()).append("\":");
            Object v = e.getValue();
            if (v instanceof Number || v instanceof Boolean) {
                sb.append(v);
            } else if (v == null) {
                sb.append("null");
            } else {
                String s = String.valueOf(v)
                        .replace("\\", "\\\\").replace("\"", "\\\"");
                sb.append('"').append(s).append('"');
            }
        }
        return sb.append('}').toString();
    }

    private static Map<String, Object> parseJsonObject(String json) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        String body = json.trim();
        if (body.startsWith("{")) {
            body = body.substring(1);
        }
        if (body.endsWith("}")) {
            body = body.substring(0, body.length() - 1);
        }
        for (String pair : body.split(",")) {
            int idx = pair.indexOf(':');
            if (idx <= 0) {
                continue;
            }
            String k = pair.substring(0, idx).trim().replace("\"", "");
            String v = pair.substring(idx + 1).trim();
            if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
                v = v.substring(1, v.length() - 1);
            }
            out.put(k, v);
        }
        return out;
    }
}