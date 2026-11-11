package cn.studykid.multiagent.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.zaxxer.hikari.HikariDataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.agentchat.AgentChatAuditPort.AuditEvent;
import cn.studykid.multiagent.ports.agentchat.AgentChatAuditPort.AuditHandle;
import cn.studykid.multiagent.ports.agentchat.AgentChatAuditPort.AuditStep;
import cn.studykid.multiagent.ports.agentchat.AgentChatAuditPort.BeginRecord;
import cn.studykid.multiagent.ports.agentchat.AgentChatAuditPort.FinishRecord;
import cn.studykid.multiagent.ports.persistence.RunClaimPort;

/**
 * 请求级 Agent 对话<b>两段式审计</b>集成测试（真库、非特权角色）。
 *
 * <p><b>为什么必须真实数据库</b>：本仓储的全部语义都藏在 SQL 与触发器里——
 * {@code RUNNING} 占位、{@code lease_owner} 排除、{@code run_event} 序号唯一约束、
 * {@code run_result_consistent} CHECK、RLS 行归属。mock 掉 DataSource 只能证明
 * 「拼了 SQL 字符串」，证明不了「工具调用前 run 真的可见」或「终态真的收敛」。
 *
 * <p><b>两级防线同时验证</b>：
 * <ol>
 *   <li>begin 写 {@code RUNNING} + {@code lease_owner='agent-chat-sse'}，
 *       {@code claimTasks} 显式跳过（由 SseDirectRunClaimIT 验证）；</li>
 *   <li>全程<b>不写 outbox_event / idempotency_record</b>，MQ 通道没有唤醒信号。</li>
 * </ol>
 *
 * <p><b>反向验证锚点</b>：把 finish 的「按实际写入序号推进」改回用原始
 * {@code event.sequenceNo()}（自动编号为 0）→ {@code run_event_seq_positive} CHECK
 * 直接拒绝；把「影响行数 != 1 抛异常」改成静默返回 →
 * {@link #staleFencingTokenCannotSilentlyFinishAudit()} 立即红。
 *
 * <p>⚠️ 用 {@code app_agent}（NOBYPASSRLS）+ {@code it-} 专用租户；
 * 所有查询必须在事务内先 {@code set_config('app.tenant_id', …)}，否则 FORCE RLS 下静默 0 行。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
class AgentChatAuditIT {

    private static final String SLUG_A = "it-audit-a";
    private static final String SLUG_B = "it-audit-b";
    private static final String AGENT_ID = "agent-1";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static HikariDataSource appPool;
    private static HikariDataSource adminPool;
    private static JdbcAgentChatAuditRepository repo;
    private static String tenantA;
    private static String tenantB;
    private static String snapshotA;
    private static String snapshotB;

    @BeforeAll
    static void setUp() throws Exception {
        appPool = FaultInjectionSupport.appPool(4);
        adminPool = FaultInjectionSupport.adminPool();
        FaultInjectionSupport.requireNonPrivileged(appPool);
        repo = new JdbcAgentChatAuditRepository(appPool);
        tenantA = FaultInjectionSupport.itTenant(adminPool, SLUG_A);
        snapshotA = FaultInjectionSupport.itSnapshot(adminPool, tenantA);
        tenantB = FaultInjectionSupport.itTenant(adminPool, SLUG_B);
        snapshotB = FaultInjectionSupport.itSnapshot(adminPool, tenantB);
        purgeBoth();
    }

    @AfterAll
    static void closePools() {
        if (appPool != null) {
            appPool.close();
        }
        if (adminPool != null) {
            adminPool.close();
        }
    }

    @BeforeEach
    void reset() throws Exception {
        purgeBoth();
    }

    @AfterEach
    void clearHolder() {
        // 残留 ThreadLocal 会让后续测试复用上一个租户的连接，造成跨租户串数据。
        TransactionalConnectionHolder.clear();
    }

    @Test
    @DisplayName("⛔ begin 写 RUNNING 占位 + SSE 专属 owner + 初始事件，且不写 Outbox/幂等记录")
    void beginCreatesRunningPlaceholderWithoutOutbox() throws Exception {
        String runId = UUID.randomUUID().toString();

        AuditHandle handle = repo.begin(beginRecord(runId, tenantA, snapshotA));

        assertThat(handle.runId()).isEqualTo(runId);
        assertThat(handle.tenantId()).isEqualTo(tenantA);
        assertThat(handle.fencingToken()).isZero();

        RunRow row = runRow(tenantA, runId);
        assertThat(row.state()).as("工具账本外键要求 run 先存在，故必须是 RUNNING 占位").isEqualTo("RUNNING");
        assertThat(row.leaseOwner())
                .as("专用 owner 让 claimTasks 跳过，是防 Worker 重复执行的第二道防线")
                .isEqualTo(RunClaimPort.SSE_DIRECT_LEASE_OWNER);
        assertThat(row.fencingToken()).isZero();
        assertThat(row.attempt()).isZero();
        assertThat(row.completedAt()).as("RUNNING 不得提前写完成时刻").isNull();
        assertThat(row.resultPayload()).isNull();
        assertThat(row.correlationId()).isEqualTo("corr-" + runId);
        assertThat(row.leaseExpiresAt()).isAfter(Instant.now());
        // ⚠️ jsonb 会把文本规范化为 {"key": value}（键后带空格），不能用字符串 contains。
        JsonNode input = JSON.readTree(row.inputPayload());
        assertThat(input.get("channel").asString()).isEqualTo("agent-chat");
        assertThat(input.get("topK").asInt()).isEqualTo(7);
        assertThat(input.get("domain").asString()).isEqualTo("PLATFORM");

        List<EventRow> events = eventRows(tenantA, runId);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).sequenceNo()).isEqualTo(1L);
        assertThat(events.get(0).eventType()).isEqualTo("AGENT_CHAT_STARTED");
        assertThat(JSON.readTree(events.get(0).payload()).get("channel").asString())
                .isEqualTo("agent-chat");

        assertThat(countOutbox(tenantA, runId)).as("没有唤醒信号，MQ 通道不会被触发").isZero();
        assertThat(countIdempotency(tenantA, runId))
                .as("幂等记录属于 Outbox/MQ 投递去重链，本链路不写").isZero();
        assertThat(stepRows(tenantA, runId)).isEmpty();
    }

    @Test
    @DisplayName("⛔ finish 收敛终态、自动序号从 2 起不撞唯一约束、步骤逐行落库")
    void finishConvergesTerminalStateAndAutoSequencedEvents() throws Exception {
        String runId = UUID.randomUUID().toString();
        AuditHandle handle = repo.begin(beginRecord(runId, tenantA, snapshotA));
        Instant completed = Instant.now().plusMillis(1);
        String resultPayload = "{\"channel\":\"agent-chat\",\"answerChars\":12,"
                + "\"finishReason\":\"STOP\",\"ragHits\":1,\"toolRounds\":1}";

        repo.finish(handle, new FinishRecord("SUCCEEDED", null, resultPayload,
                List.of(
                        new AuditEvent(0, "ROUTE", Map.of("agentId", AGENT_ID)),
                        new AuditEvent(0, "RAG_HITS", Map.of("hits", 1, "injected", true)),
                        new AuditEvent(0, "DONE", Map.of("truncated", false)),
                        // 显式序号与自动序号混用：推进必须按「实际写入的序号」。
                        new AuditEvent(7, "CUSTOM_MARKER", Map.of("step", 7))),
                List.of(
                        new AuditStep(1, "RAG", completed.minusSeconds(3), completed.minusSeconds(2)),
                        new AuditStep(2, "MODEL_ROUND_1", completed.minusSeconds(2), completed)),
                completed));

        RunRow row = runRow(tenantA, runId);
        assertThat(row.state()).isEqualTo("SUCCEEDED");
        assertThat(row.completedAt()).isNotNull();
        assertThat(row.leaseOwner()).as("终态必须释放租约").isNull();
        assertThat(row.leaseExpiresAt()).isNull();
        assertThat(row.errorCode()).isNull();
        JsonNode result = JSON.readTree(row.resultPayload());
        assertThat(result.get("answerChars").asInt()).as("数字必须算对，不只是有一行").isEqualTo(12);
        assertThat(result.get("ragHits").asInt()).isEqualTo(1);
        assertThat(result.get("finishReason").asString()).isEqualTo("STOP");

        List<EventRow> events = eventRows(tenantA, runId);
        assertThat(events).extracting(EventRow::sequenceNo)
                .as("begin 写 1，finish 从 2 自动推进；显式 7 不得被覆盖")
                .containsExactly(1L, 2L, 3L, 4L, 7L);
        assertThat(events).extracting(EventRow::eventType)
                .containsExactly("AGENT_CHAT_STARTED", "ROUTE", "RAG_HITS", "DONE", "CUSTOM_MARKER");
        assertThat(JSON.readTree(events.get(2).payload()).get("injected").asBoolean()).isTrue();

        List<StepRow> steps = stepRows(tenantA, runId);
        assertThat(steps).extracting(StepRow::stepNumber).containsExactly(1, 2);
        assertThat(steps).extracting(StepRow::stepType).containsExactly("RAG", "MODEL_ROUND_1");
        assertThat(steps).allSatisfy(s -> {
            assertThat(s.state()).as("run_step 用 RUNNING 表达「该步骤已发生」").isEqualTo("RUNNING");
            assertThat(s.fencingToken()).isZero();
        });
        assertThat(countOutbox(tenantA, runId)).isZero();
        assertThat(countIdempotency(tenantA, runId)).isZero();
    }

    @Test
    @DisplayName("⛔ 过期 fencing 世代收敛必须抛异常，绝不静默当成功（Run 永不终态）")
    void staleFencingTokenCannotSilentlyFinishAudit() throws Exception {
        String runId = UUID.randomUUID().toString();
        AuditHandle real = repo.begin(beginRecord(runId, tenantA, snapshotA));
        AuditHandle stale = new AuditHandle(runId, tenantA, real.fencingToken() + 1);
        FinishRecord finish = new FinishRecord("SUCCEEDED", null, "{\"answerChars\":0}",
                List.of(), List.of(), Instant.now().plusMillis(1));

        assertThatThrownBy(() -> repo.finish(stale, finish))
                .as("影响 0 行 = 已被收敛 / 被取消 / 世代过期；必须显式失败")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("影响 0 行");
        assertThat(runRow(tenantA, runId).state()).as("过期世代不得改动真实 Run").isEqualTo("RUNNING");
        assertThat(eventRows(tenantA, runId)).hasSize(1);

        // 正确世代仍可收敛（上一条失败不得毒化连接 / 泄漏 ThreadLocal）
        repo.finish(real, finish);
        assertThat(runRow(tenantA, runId).state()).isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName("⛔ FAILED 也收敛终态并写结构化错误码，不留 RUNNING 悬挂")
    void failedFinishConvergesWithStructuredErrorCode() throws Exception {
        String runId = UUID.randomUUID().toString();
        AuditHandle handle = repo.begin(beginRecord(runId, tenantA, snapshotA));

        repo.finish(handle, new FinishRecord("FAILED", "ROUTE_FAILED",
                "{\"channel\":\"agent-chat\",\"finishReason\":\"FAILED\"}",
                List.of(new AuditEvent(0, "ERROR", Map.of("kind", "ROUTE_FAILED"))),
                List.of(), Instant.now().plusMillis(1)));

        RunRow row = runRow(tenantA, runId);
        assertThat(row.state()).isEqualTo("FAILED");
        assertThat(row.completedAt()).isNotNull();
        assertThat(row.errorCode()).isEqualTo("ROUTE_FAILED");
        assertThat(row.errorMessage()).as("只存归一化错误码，不存供应商异常原文").isEqualTo("ROUTE_FAILED");
        assertThat(eventRows(tenantA, runId).get(1).eventType()).isEqualTo("ERROR");
        assertThat(countOutbox(tenantA, runId)).isZero();
    }

    @Test
    @DisplayName("⛔ SUCCEEDED 缺 result_payload 在 SQL 前就拒（避免 aborted 事务静默回滚）")
    void succeededWithoutResultPayloadRejectedBeforeSql() throws Exception {
        String runId = UUID.randomUUID().toString();
        AuditHandle handle = repo.begin(beginRecord(runId, tenantA, snapshotA));

        assertThatThrownBy(() -> repo.finish(handle,
                new FinishRecord("SUCCEEDED", null, null, List.of(), List.of(),
                        Instant.now().plusMillis(1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("result_payload");
        assertThat(runRow(tenantA, runId).state()).as("提前拦下，不应留下 aborted 事务").isEqualTo("RUNNING");
        assertThat(eventRows(tenantA, runId)).hasSize(1);
    }

    @Test
    @DisplayName("⛔ 审计载荷只收标量：嵌套对象在 SQL 前就拒，整笔回滚无幽灵 Run")
    void nonScalarPayloadRejectedAndRolledBack() throws Exception {
        String runId = UUID.randomUUID().toString();
        BeginRecord bad = new BeginRecord(runId, tenantA, AGENT_ID, 1, snapshotA,
                "d".repeat(64), null,
                Map.of("channel", "agent-chat", "nested", Map.of("k", "v")),
                "corr-" + runId, 0L, Instant.now(), Instant.now().plusSeconds(60));

        assertThatThrownBy(() -> repo.begin(bad))
                .as("嵌套对象会把对话正文夹带进 jsonb，必须在序列化前拒绝")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("标量");
        assertThat(runCount(tenantA, runId)).as("事务必须回滚，不能留幽灵 Run").isZero();
        assertThat(eventRows(tenantA, runId)).isEmpty();
    }

    @Test
    @DisplayName("⛔ FORCE RLS：另一 it- 租户看不到本租户的审计 Run（非假绿）")
    void auditRowsInvisibleAcrossTenants() throws Exception {
        String runId = UUID.randomUUID().toString();
        repo.begin(beginRecord(runId, tenantA, snapshotA));

        long visibleFromB = inTenant(appPool, tenantB, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT count(*) FROM run WHERE run_id = ?::uuid")) {
                ps.setString(1, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
        assertThat(visibleFromB).as("app_agent 必须受 FORCE RLS 约束，否则整个 IT 是假绿").isZero();
        assertThat(runRow(tenantA, runId).state()).isEqualTo("RUNNING");
    }

    // ── 夹具 ────────────────────────────────────────────────────────

    // ⚠️「对话正文不落审计」的守卫在编排入口 AgentChatOrchestratorTest（从 run() 进），
    //    本 IT 只守仓储层的标量白名单与非特权 RLS，避免重复的空转断言。

    private static BeginRecord beginRecord(String runId, String tenant, String snapshot) {
        Instant now = Instant.now();
        return new BeginRecord(runId, tenant, AGENT_ID, 1, snapshot,
                "a".repeat(64), "algebra",
                Map.of("channel", "agent-chat", "domain", "PLATFORM", "topK", 7,
                        "messageDigest", "a".repeat(64)),
                "corr-" + runId, 0L, now, now.plus(Duration.ofMinutes(15)));
    }

    private record RunRow(String state, String leaseOwner, long fencingToken, int attempt,
                          Instant completedAt, Instant leaseExpiresAt, String resultPayload,
                          String errorCode, String errorMessage, String correlationId,
                          String inputPayload) {
    }

    private record EventRow(long sequenceNo, String eventType, String payload) {
    }

    private record StepRow(int stepNumber, String state, String stepType, long fencingToken) {
    }

    private static RunRow runRow(String tenant, String runId) throws Exception {
        return inTenant(appPool, tenant, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT state, lease_owner, fencing_token, attempt, completed_at, "
                  + "lease_expires_at, result_payload, error_code, error_message, "
                  + "correlation_id, input_payload "
                  + "FROM run WHERE tenant_id = ?::uuid AND run_id = ?::uuid")) {
                ps.setString(1, tenant);
                ps.setString(2, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).as("夹具 Run 必须存在").isTrue();
                    return new RunRow(rs.getString("state"), rs.getString("lease_owner"),
                            rs.getLong("fencing_token"), rs.getInt("attempt"),
                            instantOf(rs, "completed_at"), instantOf(rs, "lease_expires_at"),
                            rs.getString("result_payload"), rs.getString("error_code"),
                            rs.getString("error_message"), rs.getString("correlation_id"),
                            rs.getString("input_payload"));
                }
            }
        });
    }

    private static List<EventRow> eventRows(String tenant, String runId) throws Exception {
        return inTenant(appPool, tenant, conn -> {
            List<EventRow> rows = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT sequence_no, event_type, payload FROM run_event "
                  + "WHERE tenant_id = ?::uuid AND run_id = ?::uuid ORDER BY sequence_no")) {
                ps.setString(1, tenant);
                ps.setString(2, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new EventRow(rs.getLong("sequence_no"),
                                rs.getString("event_type"), rs.getString("payload")));
                    }
                }
            }
            return rows;
        });
    }

    private static List<StepRow> stepRows(String tenant, String runId) throws Exception {
        return inTenant(appPool, tenant, conn -> {
            List<StepRow> rows = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT step_number, state, step_type, fencing_token FROM run_step "
                  + "WHERE tenant_id = ?::uuid AND run_id = ?::uuid ORDER BY step_number")) {
                ps.setString(1, tenant);
                ps.setString(2, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new StepRow(rs.getInt("step_number"), rs.getString("state"),
                                rs.getString("step_type"), rs.getLong("fencing_token")));
                    }
                }
            }
            return rows;
        });
    }

    private static long countOutbox(String tenant, String runId) throws Exception {
        return count(tenant, "SELECT count(*) FROM outbox_event "
                + "WHERE tenant_id = ?::uuid AND run_id = ?::uuid", runId);
    }

    private static long countIdempotency(String tenant, String runId) throws Exception {
        return count(tenant, "SELECT count(*) FROM idempotency_record "
                + "WHERE tenant_id = ?::uuid AND idempotency_key = ?", "agent-chat:" + runId);
    }

    private static long runCount(String tenant, String runId) throws Exception {
        return count(tenant, "SELECT count(*) FROM run "
                + "WHERE tenant_id = ?::uuid AND run_id = ?::uuid", runId);
    }

    private static long count(String tenant, String sql, String arg) throws Exception {
        return inTenant(appPool, tenant, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, tenant);
                ps.setString(2, arg);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    private static Instant instantOf(ResultSet rs, String column) throws Exception {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private static void purgeBoth() throws Exception {
        FaultInjectionSupport.purgeItTenantRuns(adminPool, SLUG_A);
        FaultInjectionSupport.purgeItTenantRuns(adminPool, SLUG_B);
    }

    /** 必须在事务内、任何业务 SQL 前绑定租户；自动提交会让 set_config(is_local) 立即失效。 */
    private static <T> T inTenant(javax.sql.DataSource pool, String tenantId, TenantQuery<T> body)
            throws Exception {
        try (Connection conn = pool.getConnection()) {
            conn.setAutoCommit(false);
            try {
                TenantSessionBinder.bind(conn,
                        new TenantContext(tenantId, "it", Set.of(), 0L));
                T result = body.apply(conn);
                conn.rollback(); // 只读断言：回滚归还，避免测试自身留下提交
                return result;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    @FunctionalInterface
    private interface TenantQuery<T> {
        T apply(Connection conn) throws Exception;
    }
}
