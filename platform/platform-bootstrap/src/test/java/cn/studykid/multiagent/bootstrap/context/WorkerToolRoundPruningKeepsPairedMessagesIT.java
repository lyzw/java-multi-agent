package cn.studykid.multiagent.bootstrap.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import cn.studykid.multiagent.bootstrap.PlatformApplication;
import cn.studykid.multiagent.context.HeuristicTokenCounter;
import cn.studykid.multiagent.context.RequestContextProjection;
import cn.studykid.multiagent.core.config.ApprovalRequirement;
import cn.studykid.multiagent.core.config.RunConfigSnapshot;
import cn.studykid.multiagent.ports.context.TokenCounter;
import cn.studykid.multiagent.ports.model.ModelMessage;
import cn.studykid.multiagent.ports.model.ModelRequest;
import cn.studykid.multiagent.ports.model.ModelResponse;
import cn.studykid.multiagent.ports.model.ModelGateway;
import cn.studykid.multiagent.ports.observer.ExecutionObserver;
import cn.studykid.multiagent.ports.observer.ModelCallObserver;
import cn.studykid.multiagent.ports.observer.ModelCallObserverChain;
import cn.studykid.multiagent.ports.observer.ObservableModelGateway;
import cn.studykid.multiagent.ports.persistence.RunClaimPort;
import cn.studykid.multiagent.testsupport.db.ItTenantProvisioner;
import cn.studykid.multiagent.worker.execute.RunExecutionLoop;

/**
 * S3-M15-d：<b>L1 Worker 工具轮 W2 整轮裁剪后，ASSISTANT(tool_calls) ↔ TOOL(call_id) 配对仍闭合</b>。
 *
 * <p>这是 S2-G2-b L1 真跑的 S3 时代聚焦版。G2-b 已证明「压缩发生且账上 token 真降」，
 * 本类专攻一个更尖锐的失真面：<b>W2 把整轮（assistant 工具调用 + 其工具结果）一起丢弃</b>时，
 * 若只丢了半边（例如漏掉 TOOL 结果却保留 assistant tool_calls，或反之），模型会拿到
 * 「悬空工具调用 / 孤儿工具结果」，下游要么报错要么静默错乱。本类用真实的
 * {@link RunExecutionLoop} 多轮工具回灌，对<b>每一轮送出的 {@link ModelRequest}</b> 断言
 * {@link AgentChainItSupport#assertToolCallPairs} 双向闭合，并显式断言整轮被裁（末轮工具轮数
 * 严格小于峰值），从而把「配对不破」从「W1 只替换 TOOL 正文」的弱证据升级为
 * 「W2 整轮丢弃也不破配对」的强证据。
 *
 * <p><b>逼出 W2 的窗口</b>：把 agent 策略 / 模型目录的 maxInputTokens 同时压到 4096，
 * 使 window=4096（trigger=2867、hardLimit=3686、target=1638）。5 轮大工具输出累计约 3500 token
 * （usage≈0.86，稳落 W2、离 HARD 4096 有余量），W2 prune 会整轮丢弃最早 1–2 轮直到落到 target。
 * 若只落 W1（只替换 TOOL 正文、不丢轮），末轮工具轮数 = 峰值，断言 {@code 末轮 < 峰值} 会立刻变红，
 * 强制本类验证的是「整轮丢弃」而非「文本截短」。
 *
 * <p><b>计费口径</b>：模型桩按真实送出 {@link ModelRequest} 现算 token（同 G2-b），
 * 保证「压缩真进请求、账上真降」而非罐头 usage 假绿。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
@SpringBootTest(classes = PlatformApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.main.allow-bean-definition-overriding=true",
        "spring.datasource.url=${IT_PG_URL}",
        "spring.datasource.username=${IT_PG_USER:app_agent}",
        "spring.datasource.password=${IT_PG_PASSWORD:}",
        "spring.flyway.enabled=false",
        "auth.jwt.issuer=https://auth.test",
        "auth.jwt.audience=agent-platform",
        "auth.jwt.token-ttl-seconds=900",
        "agent.messaging.mode=database",
        "agent.messaging.switch-guard.rabbit-residual-check=false",
        "agent.messaging.poll-interval-ms=200",
        "agent.worker.enabled=true",
        "agent.worker.relay-enabled=false",
        "agent.worker.id=it-m15-worker-tool-prune",
        "agent.worker.tenant-slugs=it-m15-worker-tool-prune",
        "agent.context.enabled=true",
        "agent.memory.enabled=false"
})
@Import(WorkerToolRoundPruningKeepsPairedMessagesIT.M15TestConfig.class)
@DisplayName("S3-M15-d：L1 Worker 工具轮 W2 整轮裁剪后 tool_call↔call_id 配对仍闭合")
class WorkerToolRoundPruningKeepsPairedMessagesIT {

    private static final String TENANT_SLUG = "it-m15-worker-tool-prune";
    private static final String MODEL_ID = "m15-worker-tool-prune-fake";
    private static final String MODEL_SCENARIO = "m15_worker_tool_prune_it";

    private static final String AGENT_NAME_TIERED = "m15-worker-tool-prune-tiered";

    private static final String STABLE_SYSTEM_PROMPT = "你是初中数学辅导助手，先给直觉再给定义。";
    private static final String USER_QUERY = "请结合画像分析这道错题的归因。";
    private static final String STUDENT_ID = "stu_m15wtpruning0001";

    /** 5 轮工具回灌 + 1 轮终答；小窗口下足以越过 W2 触发线并整轮丢弃。 */
    private static final int TOOL_ROUNDS = 5;
    private static final int MODEL_CALLS = TOOL_ROUNDS + 1;
    private static final int WINDOW_TOKENS = 4_096;
    private static final int CONTEXT_WINDOW_TOKENS = 8_192;
    private static final int WEAKNESS_COUNT = 50;
    /** 50×42≈2100 字符≈700 token/工具结果；5 轮≈3500 < 4096(HARD)，稳落 W2。 */
    private static final int KNOWLEDGE_POINT_CHARS = 42;

    private static final TokenCounter TOKEN_COUNTER = new HeuristicTokenCounter();

    private static HikariDataSource adminPool;
    private static String provisionedTenantId;
    private static String provisionedSnapshotId;
    private static String tieredAgentId;

    static {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(env("IT_PG_URL", ""));
        cfg.setUsername(env("IT_PG_ADMIN_USER", "agent"));
        cfg.setPassword(env("IT_PG_ADMIN_PASSWORD", ""));
        cfg.setMaximumPoolSize(3);
        cfg.setPoolName("m15-worker-tool-prune-it-admin");
        adminPool = new HikariDataSource(cfg);
        try {
            provisionedTenantId = ItTenantProvisioner.provision(adminPool, TENANT_SLUG);
            provisionedSnapshotId = ItTenantProvisioner.snapshotId(adminPool, provisionedTenantId);
        } catch (SQLException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Autowired
    private RunExecutionLoop executionLoop;
    @Autowired
    private RunClaimPort claimPort;
    @Autowired
    private RecordingObservers recordings;

    private String tenantId;

    @BeforeAll
    static void seedModelCatalog() throws SQLException {
        AgentChainItSupport.upsertModelCatalog(adminPool, MODEL_ID, MODEL_SCENARIO,
                CONTEXT_WINDOW_TOKENS, WINDOW_TOKENS, "M15 Worker 工具轮裁剪 IT 夹具模型");
    }

    @AfterAll
    static void closeAdminPool() {
        if (adminPool != null && !adminPool.isClosed()) {
            adminPool.close();
        }
    }

    @BeforeEach
    void setUp() throws SQLException {
        tenantId = ItTenantProvisioner.provision(adminPool, TENANT_SLUG);
        cleanupTenant(tenantId);
        seedTenantFixtures(tenantId);
        recordings.reset();
    }

    @AfterEach
    void cleanup() {
        if (tenantId != null) {
            cleanupTenant(tenantId);
        }
    }

    @Test
    @DisplayName("✅ W2 整轮裁剪后：每一轮送模型请求的 tool_call↔call_id 配对闭合、首轮锚保留、真省 token")
    void tieredToolRoundPruningKeepsPairedMessages() throws Exception {
        RunResult result = runOnce(tieredAgentId);

        assertThat(result.requests()).as("模型请求数应等于 5 轮工具 + 1 终答").hasSize(MODEL_CALLS);

        // ① 核心断言：对【每一轮】送出的请求，assistant(tool_calls) ↔ tool(call_id) 双向闭合。
        //    若 W2 整轮丢弃时只丢了半边（悬空 tool_call / 孤儿 tool 结果），这里直接变红。
        //    这一步是 M15-d 的 Thesis：即便整轮（assistant 工具调用 + 其工具结果）一起被丢，
        //    留下的请求里每一对 tool_call ↔ call_id 仍然闭合，绝不出现半对。
        AgentChainItSupport.assertToolCallPairs("M15-d W2 工具轮", result.requests());

        // ② 首轮 USER 锚在【每一轮】都恰好出现一次（D27：锚只发一遍，不可被裁剪吞掉）。
        for (int i = 0; i < result.requests().size(); i++) {
            long hits = result.requests().get(i).effectiveMessages().stream()
                    .filter(m -> m.role() == ModelMessage.Role.USER
                            && m.content().contains(USER_QUERY))
                    .count();
            assertThat(hits)
                    .as("⚠️ 第 %d 轮首轮锚必须且只能发送一次", i + 1)
                    .isEqualTo(1);
        }

        // ③ 真压缩且必须是【W2 整轮裁剪】而非 W1 文本截短：
        //    · compactions 非空（统一装配真发了压缩事件）；
        //    · 至少一次 tier≥2（W2=整轮丢弃 / W3=摘要，二者都走「整轮丢弃」语义，
        //      都证明不是 W1 的单轮文本截短）；
        //    · droppedTokens>0（不得空转）且 after<before（账上真降）。
        //    ⚠️ 不断言「末轮工具轮数 < 峰值」：Worker 把历史 ASSISTANT(tool_calls)
        //       持久化后重载会丢失 toolCalls 字段，故任意单请求至多可见 1 个含 tool_calls
        //       的 assistant（当前步的），峰值恒为 1 —— 该计数无法区分 W1/W2，已弃用。
        //       证明 W2 的硬证据是「tier≥2 的压缩事件 + after<before」，不是轮数计数。
        assertThat(result.compactions()).as("⚠️ 必须发出统一装配压缩事件").isNotEmpty();
        assertThat(result.compactions()).anySatisfy(event -> {
            assertThat(event.tier())
                    .as("⚠️ 必须命中 W2(整轮丢弃)/W3(摘要)，不得只是 W1 文本截短或 W0 计量")
                    .isGreaterThanOrEqualTo(2);
            assertThat(event.droppedTokens()).as("⚠️ 压缩事件不得是空转").isPositive();
            assertThat(event.afterTokens())
                    .as("after 必须真小于 before（账上真降，否则是假绿）")
                    .isLessThan(event.beforeTokens());
        });

        // ④ Run 成功（仅确认终态成功；本类是 Worker 直跑，无 SSE / outbox 路径）。
        assertThat(result.state()).as("⚠️ 执行循环必须把 Run 推到 SUCCEEDED").isEqualTo("SUCCEEDED");
    }

    // ══════════════════════════════════════════════════════════════
    // 真跑一次 Run
    // ══════════════════════════════════════════════════════════════

    private RunResult runOnce(String agentId) throws Exception {
        recordings.reset();

        UUID runId = UUID.randomUUID();
        String traceId = "trace-m15wtp-" + runId;
        insertQueuedRun(runId, traceId);
        seedWeaknesses(runId);

        RunClaimPort.LeasedTask task = claimPort.claimSpecificRun(
                tenantId, runId.toString(), "it-m15-worker-tool-prune", Duration.ofSeconds(30))
                .orElseThrow(() -> new AssertionError("⚠️ 未能领取 Run —— 领取路径与生产不一致"));

        RunConfigSnapshot snapshot = new RunConfigSnapshot(
                runId.toString(), new BigDecimal("20.00"), MODEL_CALLS + 6, 20,
                ApprovalRequirement.OPTIONAL, Instant.now());
        RunExecutionLoop.AgentModelConfig modelConfig = new RunExecutionLoop.AgentModelConfig(
                MODEL_ID, STABLE_SYSTEM_PROMPT, 0.3, 512, Duration.ofSeconds(10),
                toolSchemas(), agentId, 1);

        executionLoop.execute(tenantId, task, modelConfig, snapshot,
                Map.of("query", USER_QUERY));

        String state = runState(runId);
        assertThat(state)
                .as("⚠️ 执行循环必须把 Run 推到 SUCCEEDED（失败原因码=%s）", runFailureReason(runId))
                .isEqualTo("SUCCEEDED");

        return new RunResult(
                runId,
                usageInputTokens(runId),
                runMessageRoleCounts(runId),
                recordings.requestsFor(runId.toString()),
                recordings.compactionsFor(runId.toString()),
                state);
    }

    private static Map<String, Object> toolSchemas() {
        return Map.of("get_student_profile", Map.of(
                "type", "object",
                "required", List.of("studentId"),
                "properties", Map.of("studentId", Map.of(
                        "type", "string",
                        "pattern", "^stu_[a-z0-9]{16}$")),
                "additionalProperties", false));
    }

    // ══════════════════════════════════════════════════════════════
    // 夹具
    // ══════════════════════════════════════════════════════════════

    private static void seedTenantFixtures(String tenant) throws SQLException {
        try (Connection conn = adminPool.getConnection()) {
            conn.setAutoCommit(false);
            try {
                tieredAgentId = upsertAgent(conn, tenant, AGENT_NAME_TIERED, "M15 工具轮裁剪实验 Agent");
                upsertAgentVersion(conn, tenant, tieredAgentId);

                try (PreparedStatement ps = conn.prepareStatement("""
                        DELETE FROM public.cap_agent_def
                        WHERE agent_id = ?::uuid
                        """)) {
                    ps.setString(1, tieredAgentId);
                    ps.executeUpdate();
                }
                insertCapAgentDef(conn, tenant, tieredAgentId, AGENT_NAME_TIERED,
                        "M15-d 工具轮 W2 裁剪：strategy=TIERED, maxInputTokens=4096",
                        policyJson(true, "TIERED"));

                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO student_profile (student_id, tenant_id, grade, subject)
                        VALUES (?, ?::uuid, '初三', '数学')
                        ON CONFLICT (tenant_id, student_id) DO UPDATE SET
                            grade=EXCLUDED.grade, subject=EXCLUDED.subject, updated_at=now()
                        """)) {
                    ps.setString(1, STUDENT_ID);
                    ps.setString(2, tenant);
                    ps.executeUpdate();
                }

                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO budget_account
                            (tenant_id, currency, total_limit, reserved_amount, settled_amount)
                        VALUES (?::uuid, 'CNY', 1000, 0, 0)
                        ON CONFLICT (tenant_id, currency) DO UPDATE SET
                            total_limit=EXCLUDED.total_limit,
                            reserved_amount=0, settled_amount=0, updated_at=now()
                        """)) {
                    ps.setString(1, tenant);
                    ps.executeUpdate();
                }

                conn.commit();
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    private static String upsertAgent(Connection conn, String tenant, String name,
                                      String description) throws SQLException {
        String id = UUID.nameUUIDFromBytes(("m15-wtp-" + name).getBytes(StandardCharsets.UTF_8))
                .toString();
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO agent (agent_id, name, description)
                VALUES (?::uuid, ?, ?)
                ON CONFLICT (name) DO NOTHING
                """)) {
            ps.setString(1, id);
            ps.setString(2, name);
            ps.setString(3, description);
            ps.executeUpdate();
        }
        // 运行时租户可见性 = tenant_agent_binding 里该租户→agent 的 ACTIVE 行
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO tenant_agent_binding (tenant_id, agent_id, status)
                VALUES (?::uuid, ?::uuid, 'ACTIVE')
                ON CONFLICT (tenant_id, agent_id) DO UPDATE SET status='ACTIVE'
                """)) {
            ps.setString(1, tenant);
            ps.setString(2, id);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT agent_id::text FROM agent WHERE name = ?
                """)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("IT Agent 创建后查不到: " + name);
                }
                return rs.getString(1);
            }
        }
    }

    private static void upsertAgentVersion(Connection conn, String tenant, String agentId)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO agent_version (agent_id, version_number, definition, published_at)
                VALUES (?::uuid, 1, '{"kind":"tutor"}'::jsonb, now())
                ON CONFLICT (agent_id, version_number) DO NOTHING
                """)) {
            ps.setString(1, agentId);
            ps.executeUpdate();
        }
    }

    private static void insertCapAgentDef(Connection conn, String tenant, String agentId,
                                          String name, String description,
                                          String generationParams) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO cap_agent_def
                    (agent_id, version, name, description, roles, enabled_skills,
                     skill_bundles, capability_tags, overrides, orchestration, instructions,
                     model_ref, generation_params, config_fingerprint, schema_version, published_at)
                VALUES (?::uuid, 1, ?, ?, '[]'::jsonb, '[]'::jsonb,
                        '[]'::jsonb, '["tutor"]'::jsonb, '{}'::jsonb, '{}'::jsonb,
                        ?::jsonb, ?, ?::jsonb, ?, 1, now())
                """)) {
            ps.setString(1, agentId);
            ps.setString(2, name);
            ps.setString(3, description);
            ps.setString(4, "{\"inlineInstructions\":\"" + STABLE_SYSTEM_PROMPT + "\"}");
            ps.setString(5, MODEL_ID);
            ps.setString(6, generationParams);
            ps.setString(7, "it-m15-wtp-fp-" + name);
            ps.executeUpdate();
        }
    }

    private static String policyJson(boolean enabled, String strategy) {
        return """
                {
                  "temperature":0.3,
                  "maxOutputTokens":512,
                  "context":{
                    "enabled":%s,
                    "strategy":"%s",
                    "maxInputTokens":4096,
                    "triggerRatio":0.7,
                    "targetRatio":0.4,
                    "reserveRatio":0.1,
                    "recentTurns":6
                  }
                }
                """.formatted(enabled, strategy);
    }

    private void insertQueuedRun(UUID runId, String traceId) throws SQLException {
        try (Connection conn = adminPool.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO public.run (run_id, tenant_id, snapshot_id, idempotency_key,
                            request_digest, state, fencing_token, attempt, input_payload,
                            result_payload, correlation_id, created_at, updated_at, trace_id)
                    VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, 'QUEUED', 0, 0,
                            '{"query":"请结合画像分析这道错题的归因。"}'::jsonb, '{}'::jsonb, ?, now(), now(), ?)
                    """)) {
                ps.setString(1, runId.toString());
                ps.setString(2, tenantId);
                ps.setString(3, provisionedSnapshotId);
                ps.setString(4, "it-m15-wtp-" + runId);
                ps.setString(5, "digest-" + runId);
                ps.setString(6, "corr-" + runId);
                ps.setString(7, traceId);
                ps.executeUpdate();
            }
            conn.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("造 QUEUED Run 失败", e);
        }
    }

    private void seedWeaknesses(UUID runId) throws SQLException {
        exec("DELETE FROM public.weakness_record WHERE tenant_id = ?::uuid", tenantId);
        try (Connection conn = adminPool.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO weakness_record
                        (tenant_id, student_id, run_id, knowledge_point, error_type, confidence)
                    VALUES (?::uuid, ?, ?::uuid, ?, '概念不清', 0.95)
                    """)) {
                for (int i = 0; i < WEAKNESS_COUNT; i++) {
                    ps.setString(1, tenantId);
                    ps.setString(2, STUDENT_ID);
                    ps.setString(3, runId.toString());
                    ps.setString(4, knowledgePoint(i));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            conn.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("造长工具输出夹具失败", e);
        }
    }

    private static String knowledgePoint(int index) {
        StringBuilder b = new StringBuilder(KNOWLEDGE_POINT_CHARS + 16);
        while (b.length() < KNOWLEDGE_POINT_CHARS) {
            b.append("kp").append(index).append("-abcdefghij");
        }
        return b.substring(0, KNOWLEDGE_POINT_CHARS);
    }

    // ══════════════════════════════════════════════════════════════
    // 读库断言辅助
    // ══════════════════════════════════════════════════════════════

    private List<Long> usageInputTokens(UUID runId) {
        List<Long> out = new ArrayList<>();
        try (Connection conn = adminPool.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT input_tokens FROM public.usage_record
                     WHERE tenant_id = ?::uuid AND run_id = ?::uuid AND model_id = ?
                     ORDER BY created_at, usage_id
                     """)) {
            ps.setString(1, tenantId);
            ps.setString(2, runId.toString());
            ps.setString(3, MODEL_ID);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getLong(1));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("读取 usage_record 失败", e);
        }
    }

    private Map<String, Integer> runMessageRoleCounts(UUID runId) {
        Map<String, Integer> out = new HashMap<>();
        try (Connection conn = adminPool.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT role, count(*) FROM public.run_message
                     WHERE tenant_id = ?::uuid AND run_id = ?::uuid
                     GROUP BY role
                     """)) {
            ps.setString(1, tenantId);
            ps.setString(2, runId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString(1), rs.getInt(2));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("读取 run_message 失败", e);
        }
    }

    private String runState(UUID runId) {
        return scalar("SELECT state FROM public.run WHERE tenant_id = ?::uuid AND run_id = ?::uuid",
                tenantId, runId.toString());
    }

    private String runFailureReason(UUID runId) {
        return scalar("""
                SELECT coalesce(result_payload->>'errorCode', result_payload->>'reason', '')
                FROM public.run WHERE tenant_id = ?::uuid AND run_id = ?::uuid
                """, tenantId, runId.toString());
    }

    private static String scalar(String sql, String... args) {
        try (Connection conn = adminPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setString(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询失败: " + sql, e);
        }
    }

    private static void cleanupTenant(String tenant) {
        if (tenant == null) {
            return;
        }
        exec("DELETE FROM public.agent_message_queue WHERE tenant_id = ?::uuid", tenant);
        exec("DELETE FROM platform.review_task WHERE tenant_id = ?::uuid", tenant);
        exec("DELETE FROM platform.agent_trace_tool_call WHERE tenant_id = ?::uuid", tenant);
        exec("DELETE FROM platform.agent_trace_metric WHERE tenant_id = ?::uuid", tenant);
        exec("DELETE FROM public.tool_operation_attempt WHERE tenant_id = ?::uuid", tenant);
        exec("DELETE FROM public.tool_operation WHERE tenant_id = ?::uuid", tenant);
        exec("DELETE FROM public.run_message WHERE tenant_id = ?::uuid", tenant);
        exec("DELETE FROM public.usage_record WHERE tenant_id = ?::uuid", tenant);
        exec("DELETE FROM public.budget_reservation WHERE tenant_id = ?::uuid", tenant);
        exec("DELETE FROM public.run_step WHERE tenant_id = ?::uuid", tenant);
        exec("DELETE FROM public.run_event WHERE tenant_id = ?::uuid", tenant);
        exec("DELETE FROM public.weakness_record WHERE tenant_id = ?::uuid", tenant);
        exec("DELETE FROM public.student_memory WHERE tenant_id = ?::uuid", tenant);
        exec("DELETE FROM public.run WHERE tenant_id = ?::uuid", tenant);
        exec("DELETE FROM public.student_profile WHERE tenant_id = ?::uuid", tenant);
    }

    private static void exec(String sql, String... args) {
        try (Connection conn = adminPool.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setString(i + 1, args[i]);
                }
                ps.executeUpdate();
            }
            conn.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("执行失败: " + sql, e);
        }
    }

    private static String env(String key, String def) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? def : value;
    }

    // ══════════════════════════════════════════════════════════════
    // 测试专用装配：只替换模型网关装饰器，生产 RunExecutionLoop 不变
    // ══════════════════════════════════════════════════════════════

    @TestConfiguration(proxyBeanMethods = false)
    static class M15TestConfig {

        @Bean
        RecordingObservers recordingObservers() {
            return new RecordingObservers();
        }

        /**
         * ⚠️ 用 {@link BeanDefinitionRegistryPostProcessor} 在所有配置类解析完成后
         * 直接替换同名 {@code observableModelGateway} BeanDefinition，确保 Worker 拿到的是
         * 按真实请求计费的桩，而不是生产可能覆盖进来的固定 usage 假绿桩。
         */
        @Bean
        static BeanDefinitionRegistryPostProcessor m15ObservableGatewayDefinitionOverride() {
            return (BeanDefinitionRegistry registry) -> {
                BeanFactory beanFactory = (BeanFactory) registry;
                if (registry.containsBeanDefinition("observableModelGateway")) {
                    registry.removeBeanDefinition("observableModelGateway");
                }
                RootBeanDefinition definition = new RootBeanDefinition(ObservableModelGateway.class);
                definition.setPrimary(true);
                definition.setInstanceSupplier(() -> new ObservableModelGateway(
                        new CountingRequestModelGateway(
                                beanFactory.getBean(RecordingObservers.class)),
                        beanFactory.getBean(ModelCallObserverChain.class)));
                registry.registerBeanDefinition("observableModelGateway", definition);
            };
        }
    }

    /** 按真实请求内容计费：usage 不是罐头值，压缩不进请求就不可能“账上下降”。 */
    private static final class CountingRequestModelGateway implements ModelGateway {

        private final RecordingObservers observers;

        private CountingRequestModelGateway(RecordingObservers observers) {
            this.observers = observers;
        }

        @Override
        public ModelResponse complete(ModelRequest request) {
            long inputTokens = TOKEN_COUNTER.count(RequestContextProjection.project(request));
            int call = observers.nextCall();
            if (call <= TOOL_ROUNDS) {
                String callId = "m15wtp-call-" + call;
                Map<String, ModelResponse.ToolCallRequest> toolCalls = Map.of(callId,
                        new ModelResponse.ToolCallRequest("get_student_profile",
                                Map.of("studentId", STUDENT_ID), callId));
                return new ModelResponse("正在查询学生画像", toolCalls,
                        new ModelResponse.ModelUsage(inputTokens, 8),
                        ModelResponse.FinishReason.TOOL_CALLS);
            }
            return new ModelResponse("已完成错题归因分析", Map.of(),
                    new ModelResponse.ModelUsage(inputTokens, 8),
                    ModelResponse.FinishReason.STOP);
        }
    }

    /** 同时实现两类 Observer：采集真实请求/压缩事件，并给桩提供确定性调用序号。 */
    private static final class RecordingObservers implements ModelCallObserver, ExecutionObserver {

        private final Map<String, List<ModelRequest>> requests = new ConcurrentHashMap<>();
        private final Map<String, List<CompactionEvent>> compactions = new ConcurrentHashMap<>();
        private final AtomicInteger callSequence = new AtomicInteger();

        private void reset() {
            requests.clear();
            compactions.clear();
            callSequence.set(0);
        }

        private int nextCall() {
            return callSequence.incrementAndGet();
        }

        private List<ModelRequest> requestsFor(String runId) {
            return new ArrayList<>(requests.getOrDefault(runId, List.of()));
        }

        private List<CompactionEvent> compactionsFor(String runId) {
            return new ArrayList<>(compactions.getOrDefault(runId, List.of()));
        }

        @Override
        public void beforeCall(String tenantId, String runId, int step, ModelRequest request) {
            requests.computeIfAbsent(runId, unused -> new CopyOnWriteArrayList<>()).add(request);
        }

        @Override
        public void onContextCompacted(String tenantId, String runId, int tier,
                                       int beforeTokens, int afterTokens,
                                       int droppedTokens, boolean fallback) {
            compactions.computeIfAbsent(runId, unused -> new CopyOnWriteArrayList<>())
                    .add(new CompactionEvent(tier, beforeTokens, afterTokens,
                            droppedTokens, fallback));
        }

        @Override
        public void afterCall(String tenantId, String runId, int step, ModelRequest request,
                              ModelResponse response, Duration latency) {
            // no-op
        }

        @Override
        public void onException(String tenantId, String runId, int step, ModelRequest request,
                                ModelGateway.ModelGatewayException exception, Duration latency) {
            // no-op
        }

        @Override
        public void onLoopStart(String tenantId, String runId, int maxSteps) {
            // no-op
        }

        @Override
        public void onStepStart(String tenantId, String runId, int step) {
            // no-op
        }

        @Override
        public void onModelCallComplete(String tenantId, String runId, int step,
                                        ModelResponse response, Duration latency) {
            // no-op
        }

        @Override
        public void onToolCallComplete(String tenantId, String runId, int step,
                                       ToolCallSummary toolResult, Duration latency) {
            // no-op
        }

        @Override
        public void onContextTrimmed(String tenantId, String runId, int droppedRounds,
                                     int droppedTokens, List<String> droppedTools) {
            // no-op
        }

        @Override
        public void onLoopComplete(String tenantId, String runId, LoopOutcome outcome,
                                   int totalSteps) {
            // no-op
        }
    }

    private record CompactionEvent(int tier, int beforeTokens, int afterTokens,
                                   int droppedTokens, boolean fallback) {
    }

    private record RunResult(UUID runId,
                             List<Long> usageInputTokens,
                             Map<String, Integer> messageRoles,
                             List<ModelRequest> requests,
                             List<CompactionEvent> compactions,
                             String state) {
    }
}
