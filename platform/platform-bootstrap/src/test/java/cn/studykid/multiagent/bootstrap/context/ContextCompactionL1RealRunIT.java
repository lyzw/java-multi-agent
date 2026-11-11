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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
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
 * S2-G2-b：<b>L1 Worker 真跑压缩闭环</b>。
 *
 * <p>本类要堵的不是「算法单测绿了」，而是「算法绿、Worker 真跑时
 * {@code usage_record.input_tokens} 没变」这类 Sev-0 假绿。故：
 * <ol>
 *   <li>用<b>生产 {@link RunExecutionLoop} Bean</b> 真跑两次 Run；</li>
 *   <li>同一个 {@code agent.context.enabled=true} 的 Spring 上下文里，
 *       两个冻结 Agent 分别用 {@code strategy=OFF} 与 {@code strategy=TIERED}；</li>
 *   <li>模型桩<b>按真实送出的 {@link ModelRequest} 内容计费</b>，
 *       绝不返回固定 usage —— 固定 usage 下「压缩发生但账上不降」也会全绿；</li>
 *   <li>用只读工具 {@code get_student_profile} 连续制造 5 轮大输出回灌，
 *       逼出 legacy 预算 16000 以内、压缩窗口 0.70×16000 以上的挤出压力区间；</li>
 *   <li>同时断言账本 token 真降、回放消息完整、工具轮次配对不破、稳定前缀跨轮一致。</li>
 * </ol>
 *
 * <p><b>边界</b>：本类只验 L1 根 Agent。L4 子 Agent 不继承父 checkpoint 的语义由
 * M7 单测锁死；M11/S4 才落 checkpoint 表，故此处不验「压缩态重复落库」。
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
        "agent.worker.id=it-context-worker",
        // ⚠️ 启动期按 slug 解析 tenantId；租户必须已由静态块 provision。
        "agent.worker.tenant-slugs=it-context-compaction-l1",
        // ⚠️ 本类唯一的生产开关变更：打开统一装配入口。
        "agent.context.enabled=true",
        // 记忆会额外改系统提示/写 student_memory；本类只验压缩，关掉以隔离变量。
        "agent.memory.enabled=false"
})
@Import(ContextCompactionL1RealRunIT.G2TestConfig.class)
@DisplayName("S2-G2-b：L1 真跑压缩闭环（token 真下降 + 回放完整 + 工具轮次配对）")
class ContextCompactionL1RealRunIT {

    private static final String TENANT_SLUG = "it-context-compaction-l1";
    private static final String MODEL_ID = "g2-compact-fake";
    private static final String MODEL_SCENARIO = "g2_compaction_it";
    private static final String FIXED_MODEL_ROW_ID =
            UUID.nameUUIDFromBytes(MODEL_ID.getBytes(StandardCharsets.UTF_8)).toString();

    private static final String AGENT_NAME_OFF = "g2-compaction-off";
    private static final String AGENT_NAME_TIERED = "g2-compaction-tiered";

    private static final String STABLE_SYSTEM_PROMPT = "你是初中数学辅导助手，先给直觉再给定义。";
    private static final String USER_QUERY = "请结合画像分析这道错题的归因。";
    private static final String STUDENT_ID = "stu_g2compaction0001";

    /** 5 轮工具回灌 + 1 轮终答；足以越过 0.70×16000 触发线，又低于 legacy 16000 裁剪线。 */
    private static final int TOOL_ROUNDS = 5;
    private static final int MODEL_CALLS = TOOL_ROUNDS + 1;
    private static final int WINDOW_TOKENS = 16_000;
    private static final int CONTEXT_WINDOW_TOKENS = 32_768;
    private static final int WEAKNESS_COUNT = 50;
    private static final int KNOWLEDGE_POINT_CHARS = 100;

    private static final TokenCounter TOKEN_COUNTER = new HeuristicTokenCounter();

    private static HikariDataSource adminPool;
    private static String provisionedTenantId;
    private static String provisionedSnapshotId;
    private static String offAgentId;
    private static String tieredAgentId;

    static {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(env("IT_PG_URL", ""));
        cfg.setUsername(env("IT_PG_ADMIN_USER", "agent"));
        cfg.setPassword(env("IT_PG_ADMIN_PASSWORD", ""));
        cfg.setMaximumPoolSize(3);
        cfg.setPoolName("context-compaction-l1-it-admin");
        adminPool = new HikariDataSource(cfg);
        try {
            // ⚠️ 必须早于 Spring 上下文创建：MessagingChannels 启动期按 slug 解析 tenantId。
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
        upsertModelCatalog();
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
        // 上轮 fail-fast 可能留下半截数据；每个用例从干净租户态开始。
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
    @DisplayName("⚠️ 开启态真压缩：usage_record 输入 token 下降，run_message 与工具轮次配对不破")
    void enabledTieredRunReallyDropsInputTokensAndKeepsReplayAndToolPairs() throws Exception {
        // ── 对照组：同一 Spring 上下文、同一模型桩、同一工具压力，只有策略冻结为 OFF ──
        RunResult off = runOnce(offAgentId);

        // ── 实验组：冻结策略 TIERED ──
        RunResult tiered = runOnce(tieredAgentId);

        // ① 两次 Run 都真跑完；模型调用/工具轮次/落库消息都齐全。
        assertThat(off.requests()).as("OFF 对照组模型请求数").hasSize(MODEL_CALLS);
        assertThat(tiered.requests()).as("TIERED 实验组模型请求数").hasSize(MODEL_CALLS);
        assertThat(off.usageInputTokens()).as("OFF usage_record 行数").hasSize(MODEL_CALLS);
        assertThat(tiered.usageInputTokens()).as("TIERED usage_record 行数").hasSize(MODEL_CALLS);
        assertThat(off.messageRoles())
                .as("OFF run_message 必须完整保留回放底座")
                .containsEntry("USER", 1).containsEntry("ASSISTANT", MODEL_CALLS)
                .containsEntry("TOOL", TOOL_ROUNDS);
        assertThat(tiered.messageRoles())
                .as("⚠️ 压缩不得删除 run_message 回放底座")
                .containsEntry("USER", 1).containsEntry("ASSISTANT", MODEL_CALLS)
                .containsEntry("TOOL", TOOL_ROUNDS);
        assertThat(off.toolOutputMinLength()).as("OFF 工具输出应已落库").isGreaterThan(1_000);
        assertThat(tiered.toolOutputMinLength())
                .as("⚠️ 压缩后 TOOL 消息正文不得被抹空")
                .isEqualTo(off.toolOutputMinLength());

        // ② usage_record 必须来自真实送出请求，而不是桩的固定 usage。
        assertUsageMatchesRequests("OFF", off);
        assertUsageMatchesRequests("TIERED", tiered);

        // ③ 行为分流：OFF 零压缩事件；TIERED 至少一次净省 token 的真实压缩。
        assertThat(off.compactions()).as("OFF 策略不得发压缩事件").isEmpty();
        assertThat(tiered.compactions()).as("TIERED 必须发出统一装配事件").isNotEmpty();
        assertThat(tiered.compactions()).anySatisfy(event -> {
            assertThat(event.tier()).as("至少命中 W1/W2/W3，不能只是 W0 计量").isGreaterThanOrEqualTo(1);
            assertThat(event.droppedTokens()).as("⚠️ 压缩事件不得是空转").isPositive();
            assertThat(event.afterTokens()).as("after 必须真小于 before").isLessThan(event.beforeTokens());
        });

        // ④ 核心断言：账上输入 token 真下降（末轮 + 全 Run 累计都要降）。
        long offLast = off.usageInputTokens().get(MODEL_CALLS - 1);
        long tieredLast = tiered.usageInputTokens().get(MODEL_CALLS - 1);
        long offTotal = off.usageInputTokens().stream().mapToLong(Long::longValue).sum();
        long tieredTotal = tiered.usageInputTokens().stream().mapToLong(Long::longValue).sum();
        assertThat(tieredLast)
                .as("⚠️⚠️ 末轮 usage_record.input_tokens 必须真下降；"
                        + "若相等，说明压缩没进真实请求或桩用了固定 usage（假绿）")
                .isLessThan(offLast);
        assertThat(tieredTotal)
                .as("⚠️ 全 Run 累计输入 token 必须真下降")
                .isLessThan(offTotal);

        // ⑤ 稳定前缀 + 非上下文字段跨轮不变（D6 / M7-b 硬约束）。
        assertStableRequestEnvelope("OFF", off.requests());
        assertStableRequestEnvelope("TIERED", tiered.requests());
        ModelRequest offFirst = off.requests().get(0);
        ModelRequest tieredFirst = tiered.requests().get(0);
        assertThat(tieredFirst.systemPrompt()).as("稳定 systemPrompt 前缀跨策略一致")
                .isEqualTo(offFirst.systemPrompt());
        assertThat(tieredFirst.toolSchemas()).as("工具 Schema 逐字保留")
                .isEqualTo(offFirst.toolSchemas());
        assertThat(tieredFirst.modelId()).isEqualTo(offFirst.modelId());
        assertThat(tieredFirst.temperature()).isEqualTo(offFirst.temperature());
        assertThat(tieredFirst.maxTokens()).isEqualTo(offFirst.maxTokens());
        assertThat(tieredFirst.timeout()).isEqualTo(offFirst.timeout());
        assertThat(tieredFirst.userContent()).isEqualTo(offFirst.userContent());

        // ⑥ 首轮锚只发一遍；工具 assistant/tool 配对在压缩后仍闭合。
        assertFirstUserAnchorOnce("OFF", off.requests());
        assertFirstUserAnchorOnce("TIERED", tiered.requests());
        assertToolCallPairs("OFF", off.requests());
        assertToolCallPairs("TIERED", tiered.requests());
    }

    // ══════════════════════════════════════════════════════════════
    // 真跑一次 Run
    // ══════════════════════════════════════════════════════════════

    private RunResult runOnce(String agentId) throws Exception {
        recordings.reset();

        UUID runId = UUID.randomUUID();
        String traceId = "trace-g2-" + runId;
        insertQueuedRun(runId, traceId);
        // weakness_record.run_id 有外键，必须在 Run 行存在后再造大输出夹具。
        seedWeaknesses(runId);

        RunClaimPort.LeasedTask task = claimPort.claimSpecificRun(
                tenantId, runId.toString(), "it-context-worker", Duration.ofSeconds(30))
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
                traceId,
                usageInputTokens(runId),
                runMessageRoleCounts(runId),
                toolOutputMinLength(runId),
                recordings.requestsFor(runId.toString()),
                recordings.compactionsFor(runId.toString()));
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

    private static void upsertModelCatalog() throws SQLException {
        String sql = """
                INSERT INTO model_catalog (
                    id, provider, scenario, model_name, display_name, base_url,
                    is_default, supports_tools, supports_streaming, supports_vision,
                    dimension, is_enabled, sort_order, description,
                    context_window_tokens, max_input_tokens, tokenizer_id,
                    supports_prompt_caching, supports_tokenize_api, updated_at)
                VALUES (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (scenario, model_name) DO UPDATE SET
                    provider=EXCLUDED.provider, scenario=EXCLUDED.scenario,
                    model_name=EXCLUDED.model_name, display_name=EXCLUDED.display_name,
                    base_url=EXCLUDED.base_url, is_default=EXCLUDED.is_default,
                    supports_tools=EXCLUDED.supports_tools,
                    supports_streaming=EXCLUDED.supports_streaming,
                    supports_vision=EXCLUDED.supports_vision,
                    dimension=EXCLUDED.dimension, is_enabled=EXCLUDED.is_enabled,
                    sort_order=EXCLUDED.sort_order, description=EXCLUDED.description,
                    context_window_tokens=EXCLUDED.context_window_tokens,
                    max_input_tokens=EXCLUDED.max_input_tokens,
                    tokenizer_id=EXCLUDED.tokenizer_id,
                    supports_prompt_caching=EXCLUDED.supports_prompt_caching,
                    supports_tokenize_api=EXCLUDED.supports_tokenize_api,
                    updated_at=now()
                """;
        try (Connection conn = adminPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            conn.setAutoCommit(false);
            try {
                ps.setString(1, FIXED_MODEL_ROW_ID);
                ps.setString(2, "fake");
                ps.setString(3, MODEL_SCENARIO);
                ps.setString(4, MODEL_ID);
                ps.setString(5, "G2 L1 真跑压缩夹具模型");
                ps.setString(6, "http://model.invalid");
                ps.setBoolean(7, false);
                ps.setBoolean(8, true);
                ps.setBoolean(9, false);
                ps.setBoolean(10, false);
                ps.setNull(11, java.sql.Types.INTEGER);
                ps.setBoolean(12, true);
                ps.setInt(13, 9_999);
                ps.setString(14, "仅用于 G2-b L1 真跑 IT：窗口 32768 / 输入硬顶 16000");
                ps.setInt(15, CONTEXT_WINDOW_TOKENS);
                ps.setInt(16, WINDOW_TOKENS);
                ps.setString(17, "heuristic");
                ps.setBoolean(18, false);
                ps.setBoolean(19, false);
                ps.executeUpdate();
                conn.commit();
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    private static void seedTenantFixtures(String tenant) throws SQLException {
        try (Connection conn = adminPool.getConnection()) {
            conn.setAutoCommit(false);
            try {
                offAgentId = upsertAgent(conn, tenant, AGENT_NAME_OFF, "G2 关闭等价对照 Agent");
                tieredAgentId = upsertAgent(conn, tenant, AGENT_NAME_TIERED, "G2 真压缩实验 Agent");
                upsertAgentVersion(conn, tenant, offAgentId);
                upsertAgentVersion(conn, tenant, tieredAgentId);

                // cap_agent_def 是已发布不可变行：IT 夹具需确定性重建，故先按租户谓词删除自有行。
                try (PreparedStatement ps = conn.prepareStatement("""
                        DELETE FROM public.cap_agent_def
                        WHERE tenant_id = ?::uuid AND agent_id IN (?::uuid, ?::uuid)
                        """)) {
                    ps.setString(1, tenant);
                    ps.setString(2, offAgentId);
                    ps.setString(3, tieredAgentId);
                    ps.executeUpdate();
                }
                insertCapAgentDef(conn, tenant, offAgentId, AGENT_NAME_OFF,
                        "G2-B 关闭等价对照：strategy=OFF",
                        policyJson(false, "OFF"));
                insertCapAgentDef(conn, tenant, tieredAgentId, AGENT_NAME_TIERED,
                        "G2-B 真压缩实验：strategy=TIERED",
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

                // 工具闸门每次调用预留 ¥0.50；5 轮 × 2 Run 需要独立且充足的账户额度。
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
        String id = UUID.nameUUIDFromBytes(("g2-context-" + name).getBytes(StandardCharsets.UTF_8))
                .toString();
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO agent (agent_id, tenant_id, name, description)
                VALUES (?::uuid, ?::uuid, ?, ?)
                ON CONFLICT (tenant_id, name) DO NOTHING
                """)) {
            ps.setString(1, id);
            ps.setString(2, tenant);
            ps.setString(3, name);
            ps.setString(4, description);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT agent_id::text FROM agent WHERE tenant_id = ?::uuid AND name = ?
                """)) {
            ps.setString(1, tenant);
            ps.setString(2, name);
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
                INSERT INTO agent_version (tenant_id, agent_id, version_number, definition, published_at)
                SELECT ?::uuid, ?::uuid, 1, '{"kind":"tutor"}'::jsonb, now()
                ON CONFLICT (tenant_id, agent_id, version_number) DO NOTHING
                """)) {
            ps.setString(1, tenant);
            ps.setString(2, agentId);
            ps.executeUpdate();
        }
    }

    private static void insertCapAgentDef(Connection conn, String tenant, String agentId,
                                          String name, String description,
                                          String generationParams) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO cap_agent_def
                    (tenant_id, agent_id, version, name, description, roles, enabled_skills,
                     skill_bundles, capability_tags, overrides, orchestration, instructions,
                     model_ref, generation_params, config_fingerprint, schema_version, published_at)
                VALUES (?::uuid, ?::uuid, 1, ?, ?, '[]'::jsonb, '[]'::jsonb,
                        '[]'::jsonb, '["tutor"]'::jsonb, '{}'::jsonb, '{}'::jsonb,
                        ?::jsonb, ?, ?::jsonb, ?, 1, now())
                """)) {
            ps.setString(1, tenant);
            ps.setString(2, agentId);
            ps.setString(3, name);
            ps.setString(4, description);
            ps.setString(5, "{\"inlineInstructions\":\"" + STABLE_SYSTEM_PROMPT + "\"}");
            ps.setString(6, MODEL_ID);
            ps.setString(7, generationParams);
            ps.setString(8, "it-g2-compaction-fp-" + name);
            ps.executeUpdate();
        }
    }

    /**
     * 冻结策略 JSON。键名逐字对齐 {@code ContextPolicyValidator.ALLOWED_KEYS}；
     * 未知键会被 fail-closed 拒绝，不能用拼错的「看起来像配了」制造假绿。
     */
    private static String policyJson(boolean enabled, String strategy) {
        return """
                {
                  "temperature":0.3,
                  "maxOutputTokens":512,
                  "context":{
                    "enabled":%s,
                    "strategy":"%s",
                    "maxInputTokens":16000,
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
                ps.setString(4, "it-g2-compaction-" + runId);
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

    /** 100 个 ASCII 字符：启发式计数稳定且不会因汉字按 1 token 而提前冲破 legacy 16000。 */
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

    private int toolOutputMinLength(UUID runId) {
        try (Connection conn = adminPool.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT min(length(coalesce(content->>'output', '')))
                     FROM public.run_message
                     WHERE tenant_id = ?::uuid AND run_id = ?::uuid AND role = 'TOOL'
                     """)) {
            ps.setString(1, tenantId);
            ps.setString(2, runId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取 TOOL 消息正文失败", e);
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

    // ══════════════════════════════════════════════════════════════
    // 请求级断言
    // ══════════════════════════════════════════════════════════════

    private static void assertUsageMatchesRequests(String label, RunResult result) {
        List<Long> expected = result.requests().stream()
                .map(request -> (long) TOKEN_COUNTER.count(
                        RequestContextProjection.project(request)))
                .toList();
        assertThat(result.usageInputTokens())
                .as("⚠️ %s 的 usage_record.input_tokens 必须逐轮等于真实送出请求的全窗口估算", label)
                .isEqualTo(expected);
    }

    private static void assertStableRequestEnvelope(String label, List<ModelRequest> requests) {
        ModelRequest first = requests.get(0);
        for (int i = 1; i < requests.size(); i++) {
            ModelRequest current = requests.get(i);
            assertThat(current.systemPrompt())
                    .as("%s 第 %d 轮 systemPrompt 前缀漂移", label, i + 1)
                    .isEqualTo(first.systemPrompt());
            assertThat(current.toolSchemas())
                    .as("%s 第 %d 轮 toolSchemas 漂移", label, i + 1)
                    .isEqualTo(first.toolSchemas());
            assertThat(current.modelId()).isEqualTo(first.modelId());
            assertThat(current.temperature()).isEqualTo(first.temperature());
            assertThat(current.maxTokens()).isEqualTo(first.maxTokens());
            assertThat(current.timeout()).isEqualTo(first.timeout());
            assertThat(current.userContent()).isEqualTo(first.userContent());
        }
    }

    private static void assertFirstUserAnchorOnce(String label, List<ModelRequest> requests) {
        for (int i = 0; i < requests.size(); i++) {
            long hits = requests.get(i).effectiveMessages().stream()
                    .filter(m -> m.role() == ModelMessage.Role.USER
                            && m.content().contains(USER_QUERY))
                    .count();
            assertThat(hits)
                    .as("⚠️ %s 第 %d 轮首轮锚必须且只能发送一次（D27）", label, i + 1)
                    .isEqualTo(1);
        }
    }

    /**
     * 逐条请求检查 assistant(tool_calls) → tool(call_id) 配对。
     * W1 只替换 TOOL 正文、W2 只整轮丢弃，都不允许把配对拆开。
     */
    private static void assertToolCallPairs(String label, List<ModelRequest> requests) {
        for (int i = 0; i < requests.size(); i++) {
            ModelRequest request = requests.get(i);
            Set<String> pending = new LinkedHashSet<>();
            for (ModelMessage message : request.effectiveMessages()) {
                if (message.role() == ModelMessage.Role.ASSISTANT
                        && !message.toolCalls().isEmpty()) {
                    for (ModelMessage.ToolCallEcho echo : message.toolCalls()) {
                        pending.add(echo.callId());
                    }
                } else if (message.role() == ModelMessage.Role.TOOL) {
                    assertThat(message.toolCallId())
                            .as("%s 第 %d 轮 TOOL 消息缺 callId", label, i + 1)
                            .isNotNull();
                    assertThat(pending)
                            .as("⚠️ %s 第 %d 轮 tool_call_id=%s 找不到对应 assistant tool_calls",
                                    label, i + 1, message.toolCallId())
                            .contains(message.toolCallId());
                    pending.remove(message.toolCallId());
                }
            }
            assertThat(pending)
                    .as("⚠️ %s 第 %d 轮 assistant tool_calls 没有对应 TOOL 回灌：%s",
                            label, i + 1, pending)
                    .isEmpty();
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 清理
    // ══════════════════════════════════════════════════════════════

    private static void cleanupTenant(String tenant) {
        if (tenant == null) {
            return;
        }
        // ⚠️ FORCE RLS 下不绑租户的 DELETE 静默 0 行；所有删除必须带 tenant_id 谓词。
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
    static class G2TestConfig {

        /**
         * ⚠️ 只注册<b>一个</b> {@link RecordingObservers} Bean。
         *
         * <p>它同时实现 {@link ModelCallObserver} 与 {@link ExecutionObserver}，
         * 因此会被生产 {@code modelCallObserverChain} / {@code executionObserverChain}
         * 各自收进链中；不能再额外注册「返回接口类型但实际返回同一实例」的适配 Bean，
         * 否则按 {@code RecordingObservers} 注入时会出现两个候选，Spring 上下文直接起不来。
         */
        @Bean
        RecordingObservers recordingObservers() {
            return new RecordingObservers();
        }

        /**
         * ⚠️⚠️ 不能只靠「@Bean(name = "observableModelGateway") + @Primary」来覆盖。
         *
         * <p>{@code @Import} 的测试配置与组件扫描的先后并不保证测试配置最后注册；
         * 实测生产 {@code ObserverConfiguration#observableModelGateway} 反而覆盖了本类 Bean，
         * 结果 Worker 拿到的是 {@code FakeModelGateway} 固定 usage 10:20 —— 压缩发生也看不出
         * 账上 token 下降，正是本 IT 要堵的 Sev-0 假绿。
         *
         * <p>故用 {@link BeanDefinitionRegistryPostProcessor} 在<b>所有配置类解析完成后</b>
         * 直接替换同名 BeanDefinition。仍然保留生产的 {@link ModelCallObserverChain}，
         * 所以模型请求前缀与压缩事件采集走的是同一条生产观测链。
         * 执行循环、工具闸门、预算、统一装配 adapter 全是生产 Bean。
         */
        @Bean
        static BeanDefinitionRegistryPostProcessor g2ObservableGatewayDefinitionOverride() {
            return registry -> {
                BeanFactory beanFactory = (BeanFactory) registry;
                if (registry.containsBeanDefinition("observableModelGateway")) {
                    registry.removeBeanDefinition("observableModelGateway");
                }
                RootBeanDefinition definition =
                        new RootBeanDefinition(ObservableModelGateway.class);
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
                String callId = "g2-call-" + call;
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

    /** 同时实现两类 Observer：一个采集真实请求/压缩事件，一个给桩提供确定性调用序号。 */
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
                             String traceId,
                             List<Long> usageInputTokens,
                             Map<String, Integer> messageRoles,
                             int toolOutputMinLength,
                             List<ModelRequest> requests,
                             List<CompactionEvent> compactions) {
    }
}
