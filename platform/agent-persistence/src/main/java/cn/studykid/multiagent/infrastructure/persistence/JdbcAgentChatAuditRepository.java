package cn.studykid.multiagent.infrastructure.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;

import tools.jackson.databind.ObjectMapper;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.agentchat.AgentChatAuditPort;
import cn.studykid.multiagent.ports.persistence.RunClaimPort;

/**
 * 请求级 Agent 对话审计的两段式 JDBC 实现。
 *
 * <p><b>两段式而非一次写终态</b>：真实工具闸门写 {@code tool_operation} 时要求
 * {@code run(tenant_id, run_id)} 已存在（外键），故 {@link #begin} 先落 {@code RUNNING}
 * 占位；{@link #finish} 收敛终态。两步各自短事务，不跨模型调用持有数据库连接。
 *
 * <p><b>绝不写 Outbox</b>：本类只 INSERT / UPDATE
 * {@code run} / {@code run_event} / {@code run_step}，没有任何 outbox_event 写入。
 * 且 {@code run.lease_owner} 固定为 {@link RunClaimPort#SSE_DIRECT_LEASE_OWNER}，
 * {@code claimTasks} 显式跳过 —— 两条独立防线保证 Worker 不会重复执行本请求。
 *
 * <p><b>RLS 铁律</b>：全部 SQL 走 {@link TransactionalConnectionHolder#inTransaction}，
 * 事务内、业务 SQL 前由 {@link TenantSessionBinder} 绑定 {@code app.tenant_id}；
 * 用 app_agent（NOBYPASSRLS）。所有语句带 {@code tenant_id} 条件。
 *
 * <p><b>事务毒化</b>：任何 SQLException 都不在本类吞掉；
 * {@code TransactionalConnectionHolder} 会拒绝提交被毒化的事务，
 * 避免「PG aborted 事务上 COMMIT 静默回滚 → POST 假成功」。
 *
 * <p><b>不存正文</b>：只序列化调用方给的标量事实对象，不落用户问题 / 模型回答 / thinking。
 *
 * <p><b>装配归 bootstrap</b>：刻意不挂 {@code @Repository}，由
 * {@code AgentChatConfiguration#agentChatAuditPort} 显式 {@code @Bean} 注册，
 * 与 executionSnapshotPort / runClaimPort 同一纪律，避免测试切片误拾取。
 */
public class JdbcAgentChatAuditRepository implements AgentChatAuditPort {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String INSERT_RUN = """
            INSERT INTO run
                (run_id, tenant_id, snapshot_id, idempotency_key, request_digest,
                 state, fencing_token, lease_owner, lease_expires_at, attempt,
                 input_payload, correlation_id, created_at, updated_at)
            VALUES
                (?::uuid, ?::uuid, ?::uuid, ?, ?,
                 'RUNNING'::run_state, ?, ?, ?, 0,
                 ?::jsonb, ?, now(), now())
            """;

    /** 初始事件固定 seq=1；finish 的事件从 2 起，避免跨事务读 MAX+1 的竞态。 */
    private static final String INSERT_EVENT = """
            INSERT INTO run_event (tenant_id, run_id, sequence_no, event_type, payload)
            VALUES (?::uuid, ?::uuid, ?, ?, ?::jsonb)
            """;

    private static final String UPDATE_TERMINAL = """
            UPDATE run
            SET state = ?::run_state,
                completed_at = ?,
                result_payload = ?::jsonb,
                error_code = ?,
                error_message = ?,
                lease_owner = NULL,
                lease_expires_at = NULL,
                updated_at = now()
            WHERE tenant_id = ?::uuid
              AND run_id = ?::uuid
              AND state = 'RUNNING'::run_state
              AND fencing_token = ?
            """;

    private static final String INSERT_STEP = """
            INSERT INTO run_step
                (tenant_id, run_id, step_number, state, step_type,
                 fencing_token, started_at, completed_at)
            VALUES
                (?::uuid, ?::uuid, ?, 'RUNNING'::run_state, ?,
                 ?, ?, ?)
            """;

    private final DataSource dataSource;

    public JdbcAgentChatAuditRepository(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public @NonNull AuditHandle begin(@NonNull BeginRecord record) {
        TransactionalConnectionHolder.inTransaction(
                dataSource, systemContext(record.tenantId()),
                conn -> {
                    insertRun(conn, record);
                    insertEvent(conn, record.tenantId(), record.runId(), 1,
                            "AGENT_CHAT_STARTED",
                            java.util.Map.of("channel", "agent-chat",
                                    "agentId", record.agentId()));
                    return null;
                });
        return new AuditHandle(record.runId(), record.tenantId(), record.fencingToken());
    }

    @Override
    public void finish(@NonNull AuditHandle handle, @NonNull FinishRecord record) {
        TransactionalConnectionHolder.inTransaction(
                dataSource, systemContext(handle.tenantId()),
                conn -> {
                    updateTerminal(conn, handle, record);
                    int sequence = 2;
                    for (AuditEvent event : record.events()) {
                        int seq = event.sequenceNo() > 0 ? event.sequenceNo() : sequence;
                        insertEvent(conn, handle.tenantId(), handle.runId(),
                                seq, event.eventType(), event.payload());
                        // ⚠️ 必须按「实际写入的序号」推进；用原始 sequenceNo() 会让
                        //    自动编号（0）的下一条仍拿到同一个号 → 撞唯一约束。
                        sequence = Math.max(sequence, seq + 1);
                    }
                    for (AuditStep step : record.steps()) {
                        insertStep(conn, handle, step);
                    }
                    return null;
                });
    }

    private static void insertRun(Connection conn, BeginRecord record) throws SQLException {
        // idempotency_key 只保证本表唯一（run_id 本身即唯一来源），
        // 刻意不写 idempotency_record —— 那条链属于 Outbox/MQ 投递去重，本链路不发消息。
        String idempotencyKey = "agent-chat:" + record.runId();
        try (PreparedStatement ps = conn.prepareStatement(INSERT_RUN)) {
            int i = 1;
            ps.setString(i++, record.runId());
            ps.setString(i++, record.tenantId());
            ps.setString(i++, record.snapshotId());
            ps.setString(i++, idempotencyKey);
            ps.setString(i++, record.inputDigest());
            ps.setLong(i++, record.fencingToken());
            ps.setString(i++, RunClaimPort.SSE_DIRECT_LEASE_OWNER);
            ps.setTimestamp(i++, Timestamp.from(record.leaseExpiresAt()));
            ps.setString(i++, json(record.inputPayload()));
            ps.setString(i++, record.correlationId());
            if (ps.executeUpdate() != 1) {
                throw new IllegalStateException("Agent 对话审计 Run 写入影响 0 行: "
                        + record.runId());
            }
        }
    }

    private static void updateTerminal(Connection conn, AuditHandle handle,
                                       FinishRecord record) throws SQLException {
        if ("SUCCEEDED".equals(record.state())
                && (record.resultPayload() == null || record.resultPayload().isBlank())) {
            // 提前拦下，避免 DB CHECK run_result_consistent 报错后留下 aborted 事务。
            throw new IllegalArgumentException("SUCCEEDED 的审计记录必须带 result_payload");
        }
        try (PreparedStatement ps = conn.prepareStatement(UPDATE_TERMINAL)) {
            int i = 1;
            ps.setString(i++, record.state());
            ps.setTimestamp(i++, Timestamp.from(record.completedAt()));
            ps.setString(i++, record.resultPayload() == null ? null : jsonLiteral(record.resultPayload()));
            ps.setString(i++, record.errorCode());
            ps.setString(i++, errorLiteral(record));
            ps.setString(i++, handle.tenantId());
            ps.setString(i++, handle.runId());
            ps.setLong(i++, handle.fencingToken());
            int affected = ps.executeUpdate();
            if (affected != 1) {
                // 0 行 = 已被收敛 / 被取消 / 世代过期；>1 不可能（run_id 主键）。
                // 绝不静默当成功：审计终态丢失就是「Run 永远 RUNNING」的静默失效。
                throw new IllegalStateException(
                        "Agent 对话审计终态收敛失败（影响 " + affected + " 行）: "
                                + handle.runId());
            }
        }
    }

    /**
     * error_message 只放结构化错误码与归一化原因，<b>不放模型异常原文</b>
     * （可能含供应商报文片段）。调用方已把 message 归一化；这里再做长度封顶。
     */
    private static String errorLiteral(FinishRecord record) {
        if (record.errorCode() == null) {
            return null;
        }
        String code = record.errorCode();
        return code.length() > 128 ? code.substring(0, 128) : code;
    }

    private static void insertEvent(Connection conn, String tenantId, String runId,
                                    int sequenceNo, String eventType,
                                    java.util.Map<String, Object> payload)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(INSERT_EVENT)) {
            ps.setString(1, tenantId);
            ps.setString(2, runId);
            ps.setInt(3, sequenceNo);
            ps.setString(4, eventType);
            ps.setString(5, json(payload));
            ps.executeUpdate();
        }
    }

    private static void insertStep(Connection conn, AuditHandle handle, AuditStep step)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(INSERT_STEP)) {
            int i = 1;
            ps.setString(i++, handle.tenantId());
            ps.setString(i++, handle.runId());
            ps.setInt(i++, step.stepNumber());
            ps.setString(i++, step.stepType());
            ps.setLong(i++, handle.fencingToken());
            ps.setTimestamp(i++, Timestamp.from(step.startedAt()));
            ps.setTimestamp(i++, Timestamp.from(step.completedAt()));
            ps.executeUpdate();
        }
    }

    /** 序列化标量 Map；非标量直接拒绝，避免「写进 jsonb 却没人看得懂」。 */
    private static String json(java.util.Map<String, Object> payload) {
        tools.jackson.databind.node.ObjectNode root = JSON.createObjectNode();
        payload.forEach((key, value) -> {
            if (value == null) {
                return;
            }
            if (value instanceof String str) {
                root.put(key, str);
            } else if (value instanceof Boolean b) {
                root.put(key, b);
            } else if (value instanceof Number n) {
                root.set(key, JSON.valueToTree(n));
            } else {
                throw new IllegalArgumentException(
                        "审计载荷只允许标量（String/Number/Boolean）: " + key + "=" + value);
            }
        });
        return JSON.writeValueAsString(root);
    }

    /** result_payload 是编排器已序列化的 JSON 文本（标量对象），原样作为 jsonb 参数。 */
    private static String jsonLiteral(String payload) {
        return payload;
    }

    private static TenantContext systemContext(String tenantId) {
        // UUID 合法性由控制器在入口校验；这里再守一道，避免非法值进 PG 变成 22P02。
        if (!isUuid(tenantId)) {
            throw new IllegalArgumentException("审计租户标识不是合法 UUID: " + tenantId);
        }
        return new TenantContext(tenantId, "agent-chat-audit", Set.of(), 0L);
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
