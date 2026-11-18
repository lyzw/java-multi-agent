package cn.studykid.multiagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

import cn.studykid.multiagent.application.agentchataudit.AuditingAgentChatOrchestrator;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.security.JwtTokenService;
import cn.studykid.multiagent.ports.agentchat.AgentChatEvent;
import cn.studykid.multiagent.ports.agentchat.AgentChatOrchestratorPort;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditConfig;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditConfigPort;

/**
 * 对话审计<b>端到端</b>集成测试：真实 HTTP → 真实过滤器链 → 真实审计装饰器 → 真实写入器
 * → 真实 PG（{@code chat_audit_record} / {@code chat_audit_unattributed}）。
 *
 * <p><b>为什么必须有这一层</b>：{@code ChatAuditWebFilterTest} 用 MockMvc 只证明「过滤器自己
 * 算对了」；{@code ChatAuditRecordIT} 只证明「仓储写对了」。两者都绿仍可能整条链断在中间——
 * 例如作用域没跨虚拟线程传下去（{@code InheritableThreadLocal} 失效）、装饰器没被装配、
 * 异步写入器队列被丢、{@code FORCE RLS} 下 tenant 绑定缺失写不进去。
 * 只有「一次真实请求 + 一次真实查库」能证伪这些。
 *
 * <p><b>唯一替身是编排器内核</b>（{@link ScriptedOrchestrator}）：外层仍是被测的
 * {@link AuditingAgentChatOrchestrator}，脚本帧由测试给，帧的写法与真实编排器一致。
 * 这样既不起模型 / RAG / 工具，又完整覆盖「逐帧 + 逐分片 + 终态收敛」。
 *
 * <p><b>反向验证锚点</b>：把 {@code ChatAuditConfiguration} 里的过滤器注册删掉 → 三个用例全红；
 * 把装饰器换回裸 {@code real}（走应急熔断）→ 成功用例的 envelope 查不到 request/result；
 * 把 {@code ChatAuditContextHolder} 从 {@code InheritableThreadLocal} 改成普通
 * {@code ThreadLocal} → 成功用例红（虚拟线程拿不到作用域 ⇒ 零记录）。
 *
 * <p>⚠️ 必须 {@code source scripts/it-env.sh} 后跑（连独立 {@code agent_it}）；
 * 无 {@code IT_PG_URL} 时整类跳过，避免 CI 假失败。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
@SpringBootTest(
        classes = PlatformApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@ActiveProfiles("test")
@Import(ChatAuditEndToEndIT.ScriptedOrchestrator.class)
@TestPropertySource(properties = {
        "spring.datasource.url=${IT_PG_URL}",
        "spring.datasource.username=${IT_PG_USER:app_agent}",
        "spring.datasource.password=${IT_PG_PASSWORD:}",
        "spring.flyway.enabled=false",
        "auth.jwt.issuer=https://auth.test",
        "auth.jwt.audience=agent-platform",
        "auth.jwt.token-ttl-seconds=900",
        "auth.platform-jwt.issuer=https://auth.test",
        "auth.platform-jwt.audience=admin-console-api",
        "agent.worker.enabled=false",
        "spring.rabbitmq.listener.simple.auto-startup=false"
})
@DisplayName("对话审计端到端：HTTP → SSE 全帧 → 库内原文可完整还原")
class ChatAuditEndToEndIT {

    private static final String TENANT_PATH = "/api/knowledge/agent/chat/stream";

    /** 本类写入的 traceId 统一前缀，便于清理与断言隔离。 */
    private static final String TRACE_PREFIX = "it-audit-";

    /** 用例脚本（每个用例自己设置；避免用可变实例字段破坏并行安全）。 */
    private static final AtomicReference<Scripted> SCRIPT =
            new AtomicReference<>(sink -> sink.accept(new AgentChatEvent.Done(false)));

    private static String tenantId;

    @Autowired
    private RestTestClient rest;

    @Autowired
    private JwtTokenService tokens;

    @Autowired
    private ChatAuditConfigPort auditConfig;

    /**
     * 把被测装饰器包在脚本编排器外面，替换掉生产装配的完整编排器。
     *
     * <p>⚠️ 用 {@code @Primary} 而不是 {@code @MockitoBean}：后者会把装饰器一起换掉，
     * 那样测的就不是「审计是否真的在链路上」了。
     */
    @TestConfiguration
    static class ScriptedOrchestrator {

        @Bean
        @Primary
        AgentChatOrchestratorPort auditedScriptedOrchestrator() {
            return new AuditingAgentChatOrchestrator(
                    (context, request, sink) -> SCRIPT.get().play(sink));
        }
    }

    @FunctionalInterface
    interface Scripted {
        void play(AgentChatOrchestratorPort.Sink sink);
    }

    @BeforeAll
    static void resolveTenantAndCleanup() {
        try (Connection conn = adminConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT tenant_id::text FROM tenant WHERE slug = 't-a' LIMIT 1");
                 ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("种子租户 t-a 必须存在（先跑 init-db.sh）").isTrue();
                tenantId = rs.getString(1);
            }
            // 只清本类产生的前缀，绝不整表 DELETE（会把并行跑的其它 IT 记录一并毁掉）
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM public.chat_audit_record WHERE trace_id LIKE ?")) {
                ps.setString(1, TRACE_PREFIX + "%");
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM public.chat_audit_unattributed WHERE trace_id LIKE ?")) {
                ps.setString(1, TRACE_PREFIX + "%");
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("准备 IT 租户与清理失败", e);
        }
    }

    @AfterAll
    static void clearConfig() {
        // 把配置恢复成默认（文件 sink 打开指回生产目录），避免影响同 JVM 里其它测试类
        try (Connection conn = adminConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM public.chat_audit_config")) {
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("恢复审计配置失败", e);
        }
    }

    @BeforeEach
    void disableFileSink() {
        // 文件 sink 默认指向 /data/agent/chat-audit：IT 里关掉，避免每个用例刷一条「目录不可写」WARN，
        // 同时明确「本类验证的是 DB 落库链路」
        ChatAuditConfig d = ChatAuditConfig.defaults();
        ChatAuditConfig.Sinks sinks = new ChatAuditConfig.Sinks(
                d.sinks().database(),
                new ChatAuditConfig.Sinks.FileSink(false, "/tmp/chat-audit-it", "day", false));
        auditConfig.save(new ChatAuditConfig(d.enabled(), d.scope(), sinks, d.capture(),
                d.redaction(), d.sampling(), d.writer(), d.retention(), d.limits()), "it");
    }

    // ════════════════════════════════════════════════════════════
    // 1. 成功链路：逐帧 + 逐分片 + 原文，全部可从 traceId 还原
    // ════════════════════════════════════════════════════════════

    @Test
    @DisplayName("✅ 成功流：库内一条记录即可还原问题原文 / 逐分片 / 最终回答 / 终态")
    void successfulStreamIsFullyReconstructable() {
        SCRIPT.set(sink -> {
            sink.accept(new AgentChatEvent.RunStart("run-audit-ok"));
            sink.accept(new AgentChatEvent.Thinking("先判断题型"));
            sink.accept(new AgentChatEvent.Delta("先移项"));
            sink.accept(new AgentChatEvent.Delta("再合并同类项"));
            sink.accept(new AgentChatEvent.Done(false));
        });

        String traceId = TRACE_PREFIX + "ok";
        Resp resp = post(TENANT_PATH, tenantToken(), traceId,
                "{\"message\":\"怎么移项\",\"thinkingMode\":true}");
        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.body()).as("HTTP 层必须照常流出 done 终帧（审计不得改变 SSE 线序）")
                .contains("event:done");

        Map<String, String> row = awaitRecord(traceId);
        assertThat(row)
                .as("⛔ 真实 HTTP 成功但库里没有审计记录：作用域没跨虚拟线程传播 / 写入门被误关")
                .isNotNull();
        assertThat(row.get("endpoint")).isEqualTo(TENANT_PATH);
        assertThat(row.get("domain")).isEqualTo("TENANT");
        assertThat(row.get("terminal_state")).isEqualTo("SUCCEEDED");
        assertThat(row.get("http_status")).isEqualTo("200");
        assertThat(row.get("tenant_id")).isEqualTo(tenantId);

        // 问题原文：结构化字段与原始 body 都要在（决策 1/2）
        assertThat(row.get("message")).isEqualTo("怎么移项");
        assertThat(row.get("raw_body"))
                .as("原始请求体没落库：审计只剩「结构化摘要」，无法还原客户端真实发送内容")
                .contains("怎么移项");

        // 逐分片（决策 4）：thinking 与两个 delta 都按到达顺序记录
        assertThat(row.get("chunk_count")).isEqualTo("3");
        assertThat(row.get("chunk_kinds")).isEqualTo("thinking,delta,delta");
        assertThat(row.get("chunk_text")).isEqualTo("先判断题型先移项再合并同类项");

        // 全帧（决策 3）：done 必须在帧序列里，帧数不为零
        assertThat(row.get("event_names")).contains("run.start").contains("thinking")
                .contains("delta").contains("done");
        assertThat(row.get("sse_frame_count")).isEqualTo("5");

        // 最终回答由 delta 累积而成
        assertThat(row.get("final_answer")).isEqualTo("先移项再合并同类项");
        assertThat(row.get("finish_reason")).isEqualTo("STOP");
        assertThat(row.get("duration_ms")).isNotNull();
    }

    // ════════════════════════════════════════════════════════════
    // 2. 失败链路：客户端仍拿到 error 帧，审计记 FAILED 且带错误码
    // ════════════════════════════════════════════════════════════

    @Test
    @DisplayName("⛔ 编排异常：业务侧收到 error 帧，审计记 FAILED（绝不记成成功）")
    void failedStreamRecordsFailure() {
        SCRIPT.set(sink -> {
            sink.accept(new AgentChatEvent.Delta("写了一半"));
            throw new IllegalStateException("模型网关不可用");
        });

        String traceId = TRACE_PREFIX + "fail";
        Resp resp = post(TENANT_PATH, tenantToken(), traceId,
                "{\"message\":\"怎么移项\",\"thinkingMode\":false}");
        assertThat(resp.body()).as("失败路径必须让客户端拿到 error 帧（不能让流静默截断）")
                .contains("event:error");

        Map<String, String> row = awaitRecord(traceId);
        assertThat(row).isNotNull();
        assertThat(row.get("terminal_state"))
                .as("⛔ 失败被记成 SUCCEEDED：审计会让「线上报错率」凭空消失")
                .isEqualTo("FAILED");
        assertThat(row.get("error_phase")).isEqualTo("orchestrator");
        assertThat(row.get("error_code")).isEqualTo("ORCHESTRATOR_ERROR");
        // 已推给客户端的半截回答仍要保留——客户端看到过什么，审计就得有什么
        assertThat(row.get("final_answer")).isEqualTo("写了一半");
    }

    // ════════════════════════════════════════════════════════════
    // 3. 建连前失败：只落元数据表，绝不落原文
    // ════════════════════════════════════════════════════════════

    @Test
    @DisplayName("⛔ 未认证 401：只写旁路元数据表，原文（问题）一个字都不落库")
    void unauthenticatedRequestIsRecordedAsMetadataOnly() {
        String traceId = TRACE_PREFIX + "anon";
        Resp resp = post(TENANT_PATH, null, traceId,
                "{\"message\":\"我不该出现在审计原文里\",\"thinkingMode\":false}");
        assertThat(resp.status())
                .as("无令牌必须是 401（不是 400/500）：否则「建连前拒绝」这条审计分支根本没被走到")
                .isEqualTo(401);

        Map<String, String> rejected = awaitRejected(traceId);
        assertThat(rejected)
                .as("建连前失败必须留痕；否则「有人拿过期令牌反复打」在审计里完全不可见")
                .isNotNull();
        assertThat(rejected.get("http_status")).isEqualTo("401");
        assertThat(rejected.get("endpoint")).isEqualTo(TENANT_PATH);
        assertThat(rejected.get("rejection_reason")).isEqualTo("未认证");
        assertThat(rejected.get("metadata"))
                .as("⛔ 未归属请求也存了原文：身份未成立时无法判断归属，落原文等于越权留存")
                .doesNotContain("我不该出现在审计原文里");

        assertThat(awaitRecord(traceId))
                .as("⛔ 未认证请求被写进了租户审计表：等于给匿名请求伪造租户归属")
                .isNull();
    }

    // ── 夹具 ────────────────────────────────────────────────────────

    private Resp post(String path, @Nullable String token, String traceId, String body) {
        var spec = rest.post().uri(path)
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
                .header("X-Trace-Id", traceId)
                .contentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        var result = spec.body(body).exchange().expectBody(String.class).returnResult();
        String responseTrace = result.getResponseHeaders().getFirst("X-Trace-Id");
        assertThat(responseTrace)
                .as("响应头必须回写同一个 traceId，否则排障时无法从一次请求定位到审计记录")
                .isEqualTo(traceId);
        int status = result.getStatus().value();
        return new Resp(status, result.getResponseBody() == null ? "" : result.getResponseBody());
    }

    /** 一次调用的最小结果（状态 + 响应体）。 */
    private record Resp(int status, String body) {
    }

    private String tenantToken() {
        return tokens.issue(new TenantContext(tenantId, "it-audit-principal", Set.of("STUDENT"), 1L),
                Instant.now());
    }

    /**
     * 等一条审计主表记录出现。
     *
     * <p>⚠️ 必须轮询而不是直接查：写入器默认异步，记录由容器线程在流收尾时提交，
     * 客户端拿到完整响应体时可能还差几毫秒。
     */
    private static @Nullable Map<String, String> awaitRecord(String traceId) {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            Map<String, String> row = queryRecord(traceId);
            if (row != null) {
                return row;
            }
            sleepBriefly();
        }
        return null;
    }

    private static @Nullable Map<String, String> awaitRejected(String traceId) {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            Map<String, String> row = queryRejected(traceId);
            if (row != null) {
                return row;
            }
            sleepBriefly();
        }
        return null;
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(100L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待审计记录时被中断", e);
        }
    }

    private static @Nullable Map<String, String> queryRecord(String traceId) {
        String sql = """
                SELECT endpoint, domain, terminal_state, http_status, tenant_id::text,
                       duration_ms::text,
                       envelope #>> '{request,message}',
                       envelope #>> '{request,rawBody}',
                       envelope #>> '{result,finalAnswer}',
                       envelope #>> '{result,finishReason}',
                       envelope #>> '{result,sseFrameCount}',
                       envelope #>> '{error,phase}',
                       envelope #>> '{error,code}',
                       jsonb_array_length(envelope -> 'chunks'),
                       (SELECT string_agg(c ->> 'kind', ',' ORDER BY (c ->> 'seq')::int)
                          FROM jsonb_array_elements(envelope -> 'chunks') AS c),
                       (SELECT string_agg(c ->> 'text', '' ORDER BY (c ->> 'seq')::int)
                          FROM jsonb_array_elements(envelope -> 'chunks') AS c),
                       (SELECT string_agg(e ->> 'name', ',' ORDER BY (e ->> 'seq')::int)
                          FROM jsonb_array_elements(envelope -> 'events') AS e)
                FROM public.chat_audit_record WHERE trace_id = ?
                """;
        try (Connection conn = adminConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, traceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                Map<String, String> row = new LinkedHashMap<>();
                row.put("endpoint", rs.getString(1));
                row.put("domain", rs.getString(2));
                row.put("terminal_state", rs.getString(3));
                row.put("http_status", String.valueOf(rs.getInt(4)));
                row.put("tenant_id", rs.getString(5));
                row.put("duration_ms", rs.getString(6));
                row.put("message", rs.getString(7));
                row.put("raw_body", rs.getString(8));
                row.put("final_answer", rs.getString(9));
                row.put("finish_reason", rs.getString(10));
                row.put("sse_frame_count", rs.getString(11));
                row.put("error_phase", rs.getString(12));
                row.put("error_code", rs.getString(13));
                row.put("chunk_count", rs.getString(14));
                row.put("chunk_kinds", rs.getString(15));
                row.put("chunk_text", rs.getString(16));
                row.put("event_names", rs.getString(17));
                return row;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询审计记录失败", e);
        }
    }

    private static @Nullable Map<String, String> queryRejected(String traceId) {
        String sql = """
                SELECT http_status, endpoint, rejection_reason, metadata::text
                FROM public.chat_audit_unattributed WHERE trace_id = ?
                """;
        try (Connection conn = adminConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, traceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                Map<String, String> row = new LinkedHashMap<>();
                row.put("http_status", String.valueOf(rs.getInt(1)));
                row.put("endpoint", rs.getString(2));
                row.put("rejection_reason", rs.getString(3));
                row.put("metadata", rs.getString(4));
                return row;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询未归属审计记录失败", e);
        }
    }

    /** 超管连接：跨租户读回（{@code app_agent} 受 FORCE RLS 限制，读不到全量）。 */
    private static Connection adminConnection() throws SQLException {
        return DriverManager.getConnection(
                System.getenv("IT_PG_URL"),
                System.getenv().getOrDefault("IT_PG_ADMIN_USER", "agent"),
                System.getenv().getOrDefault("IT_PG_ADMIN_PASSWORD", ""));
    }
}
