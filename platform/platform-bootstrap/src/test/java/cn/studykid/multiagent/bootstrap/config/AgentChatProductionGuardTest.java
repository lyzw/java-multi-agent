package cn.studykid.multiagent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 请求级通用 Agent 对话「真正接入生产」的跨文件架构守卫。
 *
 * <p><b>为什么需要源码级守卫</b>：本轮接入横跨 5 个模块 + Security 配置 +
 * application.yml，有若干约束是<b>任何单点行为测试都测不到</b>的——它们是
 * 「路径 / 配置键 / SQL 形状 / 装配结构 / 跨端点身份域」，一旦回归，表现不是报错，
 * 而是 <b>401 全线、Worker 重复执行、审计静默丢失、RAG 空转</b>这类静默失真。
 *
 * <p><b>与行为测试的分工</b>：13 事件协议 / RAG 组件级降级 / thinking 闸门 / 工具闸门
 * 由 {@code AgentChatOrchestratorTest}（18 例）与 {@code AgentChatControllerTest}（12 例）
 * 在真实行为层覆盖；本类只锁<b>跨文件一致性与结构性红线</b>，不做脆弱的字符串重载。
 *
 * <p><b>反向验证原则</b>（每条都问过「撤掉修复点它会红吗」）：
 * <ul>
 *   <li>删 {@code AGENT_CHAT_PREFIX} matcher / 放宽成 {@code /api/} → matcher 守卫红；</li>
 *   <li>删任一处 ASYNC dispatch 放行 → SSE 挂起守卫红；</li>
 *   <li>把 {@code MATERIALIZED} CTE 写回 {@code IN (SELECT … LIMIT)} → RLS 超发守卫红；</li>
 *   <li>给审计仓储加 {@code outbox_event} 写入 → 无 Outbox 守卫红；</li>
 *   <li>组合根任一 Bean 退回桩 / 审计仓储挂 {@code @Repository} → 装配守卫红。</li>
 * </ul>
 *
 * <p>⚠️ 读源码断言前<b>剥离注释但保留字符串字面量</b>：否则「注释里提到某类名 / 某 SQL」
 * 会被当成真实引用，守卫迅速退化成假绿或假红。yml 含 {@code http://} 不能用 Java 扫描器剥离，
 * 单独用原文读取。
 */
class AgentChatProductionGuardTest {

    // ── 路径（相对 platform reactor 根）──────────────────────────────
    private static final String SECURITY =
            "platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/config/SecurityConfiguration.java";
    private static final String WIRING =
            "platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/config/AgentChatConfiguration.java";
    private static final String CONTROLLER =
            "agent-api/src/main/java/cn/studykid/multiagent/api/chat/AgentChatController.java";
    private static final String AUDIT_REPO =
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/JdbcAgentChatAuditRepository.java";
    private static final String CLAIM_REPO =
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/JdbcRunClaimRepository.java";
    private static final String ORCHESTRATOR =
            "agent-worker/src/main/java/cn/studykid/multiagent/worker/agentchat/AgentChatOrchestrator.java";
    private static final String RAG_GATEWAY =
            "agent-infrastructure/src/main/java/cn/studykid/multiagent/infrastructure/rag/HttpRagRetrievalGateway.java";
    private static final String RUN_CLAIM_PORT =
            "agent-ports/src/main/java/cn/studykid/multiagent/ports/persistence/RunClaimPort.java";
    private static final String APP_YML =
            "platform-bootstrap/src/main/resources/application.yml";
    /** 真编排 + 真 HTTP + 真审计闭环 IT：必须与 AgentChatEndpointIT 的「桩编排」分工，不得混同。 */
    private static final String ORCHESTRATION_IT =
            "platform-bootstrap/src/test/java/cn/studykid/multiagent/bootstrap/AgentChatOrchestrationIT.java";

    // ── 身份域隔离 ────────────────────────────────────────────────────

    @Test
    @DisplayName("⛔ 平台链 matcher 精确覆盖 /api/agent/，租户链端点与流端点都不匿名放行")
    void platformMatcherIsExactAndStreamEndpointsStayProtected() {
        String code = code(SECURITY);
        // 前缀必须精确：放宽成 /api/ 会把租户链所有端点吸进平台链 → 全线 401。
        assertThat(code).contains("AGENT_CHAT_PREFIX = \"/api/agent/\"");
        String platformChain = between(code, "platformSecurityFilterChain", "return http.build();");
        assertThat(platformChain)
                .as("平台链 securityMatcher 必须显式包含 AGENT_CHAT_PREFIX，"
                        + "否则平台令牌端点被租户链 aud 校验拒绝")
                .contains("AGENT_CHAT_PREFIX + \"**\"");
        assertThat(platformChain).doesNotContain("knowledge");
        String publicEndpoints = between(code, "PUBLIC_ENDPOINTS = {", "};");
        assertThat(publicEndpoints)
                .as("两个 agent/chat 流端点一旦进入 PUBLIC_ENDPOINTS，直连即匿名执行 RAG / 落库")
                .doesNotContain("agent/chat")
                .doesNotContain("knowledge");
    }

    @Test
    @DisplayName("⛔ 两条安全链都放行 ASYNC dispatch，SseEmitter.complete() 不挂起")
    void bothChainsPermitAsyncDispatch() {
        long count = countOf(code(SECURITY),
                "dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()");
        assertThat(count)
                .as("平台链与租户链各需一处 ASYNC dispatch 放行；"
                        + "缺任一处 -> 对应 SSE 端点 complete() 后浏览器永远等不到终止块")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("⛔ 双端点常量路径与两前端逐字对齐，且平台 ≠ 租户")
    void bothEndpointConstantsAreFrozen() {
        String code = code(CONTROLLER);
        assertThat(code)
                .contains("PLATFORM_STREAM_PATH = \"/api/agent/chat/stream\"")
                .contains("TENANT_STREAM_PATH = \"/api/knowledge/agent/chat/stream\"");
        assertThat("/api/agent/chat/stream")
                .as("平台链路径不得含 knowledge（那是租户链专属）")
                .doesNotContain("knowledge");
        assertThat("/api/knowledge/agent/chat/stream").contains("knowledge");
        assertThat("/api/agent/chat/stream").isNotEqualTo("/api/knowledge/agent/chat/stream");
    }

    // ── 不派发 Worker（两条独立防线）─────────────────────────────────

    @Test
    @DisplayName("⛔ 审计仓储只写 run/run_event/run_step，绝不写 outbox_event / 幂等记录")
    void auditRepositoryNeverWritesOutbox() {
        String code = code(AUDIT_REPO);
        assertThat(code)
                .as("任何 outbox_event 写入都会唤醒 MQ Worker 重复执行本请求级流")
                .doesNotContain("outbox_event")
                .doesNotContain("idempotency_record");
        // 正向锚点：确实写了三段式审计（避免空转的 doesNotContain）。
        assertThat(code).contains("INSERT INTO run")
                .contains("INSERT INTO run_event")
                .contains("INSERT INTO run_step")
                .contains("UPDATE run");
    }

    @Test
    @DisplayName("⛔ claimTasks 用 MATERIALIZED CTE + SSE owner 排除，堵住 RLS 超发与重复执行")
    void claimTasksIsRlsSafeAndExcludesSseRuns() {
        String code = code(CLAIM_REPO);
        // 2026-11-10 实测：app_agent + FORCE RLS 下，IN (SELECT … LIMIT ? FOR UPDATE SKIP LOCKED)
        // 会退化成逐行 SubPlan，LIMIT 1 实际领走全部候选；MATERIALIZED 强制只求值一次。
        assertThat(code).contains("WITH candidates AS MATERIALIZED")
                .as("绝不能写回 UPDATE…WHERE run_id IN (SELECT … LIMIT …) —— RLS security-barrier "
                        + "会让 LIMIT 失效并超发 Worker 配额");
        assertThat(code).doesNotContain("run_id IN (SELECT");
        // 请求级 SSE 的 RUNNING 占位行必须被排除，否则 RecoverySweeper 在租约过期后接管重跑。
        assertThat(code).contains("lease_owner IS DISTINCT FROM '%s'")
                .contains("SSE_DIRECT_LEASE_OWNER");
        // 占位符顺序：CTE 内 tenant → limit，UPDATE owner → lease 秒数（错位会静默写错列）。
        assertThat(code).contains("ps.setString(1, tenantId)")
                .contains("ps.setInt(2, limit)")
                .contains("ps.setString(3, workerId)")
                .contains("ps.setInt(4, (int) leaseDuration.toSeconds())");
    }

    @Test
    @DisplayName("⛔ SSE 专属 lease_owner 常量存在且被审计仓储 + 领取仓储共用")
    void sseLeaseOwnerConstantIsShared() {
        assertThat(code(RUN_CLAIM_PORT)).contains("SSE_DIRECT_LEASE_OWNER = \"agent-chat-sse\"");
        assertThat(code(AUDIT_REPO)).contains("RunClaimPort.SSE_DIRECT_LEASE_OWNER");
        assertThat(code(CLAIM_REPO)).contains("RunClaimPort.SSE_DIRECT_LEASE_OWNER");
    }

    // ── 组合根真实装配 ────────────────────────────────────────────────

    @Test
    @DisplayName("⛔ 组合根五个 Bean 全部接真实实现，零 Fake/NoOp/Stub")
    void compositionRootWiresOnlyRealImplementations() {
        String code = code(WIRING);
        assertThat(code)
                .contains("new HttpRagRetrievalGateway(")
                .contains("new JdbcAgentChatAuditRepository(")
                .contains("new ModelAgentRouter(")
                .contains("new RequestAgentChatIdentityResolver(")
                .contains("new AgentChatOrchestrator(")
                .contains("new AgentModelConfigAssembler(");
        assertThat(code)
                .as("组合根出现桩实现 = 页面流式正常但 RAG/工具/审计其实空转")
                .doesNotContain("NoOp")
                .doesNotContain("Fake")
                .doesNotContain("Stub");
    }

    @Test
    @DisplayName("⛔ 审计仓储刻意不挂 @Repository（防组件扫描 + @Bean 双注册）")
    void auditRepositoryIsNotComponentScanned() {
        String code = code(AUDIT_REPO);
        assertThat(code).doesNotContain("@Repository");
        // 正向锚点：类本身存在且实现端口（否则 doesNotContain 空转）。
        assertThat(code).contains("class JdbcAgentChatAuditRepository")
                .contains("implements AgentChatAuditPort");
    }

    // ── RAG 真实接入 ──────────────────────────────────────────────────

    @Test
    @DisplayName("⛔ RAG base-url 配置键在 yml 与组合根一致，默认指向独立 rag 进程 8081")
    void ragBaseUrlKeyIsConsistentBetweenYamlAndWiring() {
        String wiring = code(WIRING);
        String yml = raw(APP_YML); // yml 含 http:// ，不能用 Java 词法扫描器剥离
        assertThat(wiring).contains("agent.rag.base-url")
                .contains("http://127.0.0.1:8081");
        assertThat(yml).contains("base-url: \"${RAG_BASE_URL:http://127.0.0.1:8081}\"");
        // @Value 占位符的环境变量名必须与 yml 一致，否则环境变量覆盖失效、
        // 生产仍连本地 8081（表现为 RAG 组件级失败 + 零命中）。
        assertThat(countOf(yml, "RAG_BASE_URL")).isEqualTo(1);
        assertThat(yml).contains("agent:").contains("rag:");
    }

    @Test
    @DisplayName("⛔ RAG 网关走真实 /api/v1/retrieval + 鉴权头 + 租户头 + 七字段白名单")
    void ragGatewayUsesRealEndpointHeadersAndWhitelistFields() {
        String code = code(RAG_GATEWAY);
        assertThat(code).contains("\"/api/v1/retrieval\"")
                .as("错用 /retrieve 等不存在的路径会 404，被误读成『知识库为空』")
                .contains(".header(\"Authorization\"")
                .contains(".header(\"X-Tenant-Id\"")
                .contains("\"CROSS_EDITION\"")
                .contains("\"EXPLAIN\"");
        // 七字段白名单：只取这七个，不透传 rag 的 channel / funnel / prompt 等额外字段。
        assertThat(code).contains("text(n, \"chunkId\")")
                .contains("text(n, \"text\")")
                .contains("\"score\"")
                .contains("text(n, \"locator\")")
                .contains("text(n, \"edition\")")
                .contains("text(n, \"role\")")
                .contains("text(n, \"reason\")");
    }

    // ── 编排器 fail-closed 语义（源码存在性，行为由 OrchestratorTest 覆盖）──

    @Test
    @DisplayName("⛔ RAG 命中经 PromptGuard 围栏后以 user 消息注入，不伪造 / 不静默吞")
    void ragContextIsGuardedAndInjectedAsUserMessage() {
        String code = code(ORCHESTRATOR);
        assertThat(code).contains("RAG_GUARD.wrapToolResult(\"rag\"")
                .as("RAG 是外部不可信数据，必须经围栏；"
                        + "否则命中里的『忽略之前指令』可劫持模型")
                .contains("ModelMessage.user(ragContext)");
        assertThat(code).contains("catch (RagRetrievalPort.RagUnavailableException e)")
                .contains("\"error\"")
                .contains("new AgentChatEvent.RagHits(List.of(), false)")
                .as("RAG 失败必须是组件级：如实 error + 空命中，不整轮 fail、不伪造命中");
    }

    @Test
    @DisplayName("⛔ 租户通道推理草稿不可见：thinking 由 context.thinkingAllowed() 控源")
    void tenantThinkingIsSuppressedAtSource() {
        String code = code(ORCHESTRATOR);
        assertThat(code).contains("if (!context.thinkingAllowed() || token.isEmpty())")
                .as("推理草稿只允许平台管理员可见；租户通道必须从源头丢弃")
                .contains("new AgentChatEvent.Thinking(token)");
    }

    @Test
    @DisplayName("⛔ 工具执行唯一闸门：编排器只走 ToolInvocationGate，不另造旁路")
    void toolExecutionUsesSingleGate() {
        String code = code(ORCHESTRATOR);
        assertThat(code).contains("toolGate.invokeAll(")
                .contains("ToolOutcomeRenderer.renderRound(")
                .contains("new ExecutionScope(")
                .as("工具审批/预算/闭包/取消全在 Gate 内；对话链绕过它就等于绕过全部治理");
    }

    @Test
    @DisplayName("⛔ 闭环 IT 必须用真编排 + 真审计落库，只替换两个网络出口，退化成脚本桩即红")
    void orchestrationItMustKeepRealOrchestratorAndAudit() {
        String it = code(ORCHESTRATION_IT); // 已剥离注释但保留字符串，故注释里的类名不算
        assertThat(it).as("闭环 IT 源码不能为空").isNotEmpty();
        // ① 两个网络出口可以（且只能）替换：具名模型网关 + RAG 独立服务。
        assertThat(it)
                .contains("@MockitoBean(name = \"chatStreamGateway\")")
                .contains("private RagRetrievalPort rag");
        // ② ⛔ 真编排器与真审计绝不可被替换：一旦 @MockitoBean 掉它们，
        //    「页面流式正常、run 从不落库」的静默失真就回来了——那正是本 IT 要证伪的对象。
        //    ⚠️ 用「类型名零出现」而非「@MockitoBean + 简单名」的组合匹配：
        //       后者会被全限定名（cn.studykid...AgentChatOrchestratorPort）绕过 → 假绿。
        //       本 IT 通过真容器拿这两个 Bean，源码无需、也不得声明这两个端口类型。
        assertThat(it)
                .as("闭环 IT 不得出现 AgentChatOrchestratorPort 类型（无论 @Autowired / @MockitoBean、"
                        + "简单名或全限定名）：出现即意味着把真编排器换成了桩或绕过了真容器，"
                        + "runId 将不再来自真库落库行")
                .doesNotContain("AgentChatOrchestratorPort");
        assertThat(it)
                .as("闭环 IT 不得出现 AgentChatAuditPort 类型：真库 run/run_event/run_step 断言"
                        + "必须打在真实仓储上，换成桩会让全部落库断言失去意义")
                .doesNotContain("AgentChatAuditPort");
        // ③ 闭环断言锚点必须在：从 SSE runId 反查真库，验证终态 / 租约释放 / 无 Outbox。
        assertThat(it)
                .contains("FROM run WHERE tenant_id = ?::uuid AND run_id = ?::uuid")
                .contains("FROM run_event ")
                .contains("FROM run_step ")
                .contains("FROM outbox_event ")
                .contains("\"SUCCEEDED\"")
                .contains("\"FAILED\"")
                .as("闭环断言必须落到真库：终态、run_event、run_step 与「绝不写 Outbox」"
                        + "缺一不可；只有「有一行」不算数");
    }

    // ── 辅助 ────────────────────────────────────────────────────────

    /** 读 Java 源码：剥离注释但保留字符串 / text block 内容。 */
    private static String code(String relativePath) {
        return stripCommentsKeepStrings(raw(root().resolve(relativePath)));
    }

    /** 原文读取（用于 yml 等非 Java 文件）。 */
    private static String raw(String relativePath) {
        return raw(root().resolve(relativePath));
    }

    private static String raw(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取源码失败: " + file, e);
        }
    }

    private static Path root() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("agent-core")) && Files.exists(dir.resolve("pom.xml"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("未定位到 platform 源码根目录（user.dir="
                + System.getProperty("user.dir") + "）");
    }

    /** 取 from 之后、to 之前的片段（用于锁定某方法 / 注解块的局部文本）。 */
    private static String between(String src, String from, String to) {
        int start = src.indexOf(from);
        if (start < 0) {
            return "";
        }
        int end = src.indexOf(to, start + from.length());
        return end < 0 ? src.substring(start) : src.substring(start, end);
    }

    private static long countOf(String src, String needle) {
        long count = 0;
        int from = 0;
        while ((from = src.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }

    /**
     * 剥离行注释与块注释，<b>保留</b>字符串字面量与 text block 的内容。
     *
     * <p>⚠️ 与仓库里其他守卫的 {@code stripCommentsAndStrings} 不同：那个版本连字符串一起清空，
     * 会误伤本类要锁的 SQL 形状、URL、路径与 @Value 键。这里在词法扫描进入字符串 / text block /
     * 字符字面量后，其内部出现的 {@code //} 与 {@code /*} 一律当普通字符处理。
     */
    private static String stripCommentsKeepStrings(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(i + 2, n);
                out.append(' ');
                continue;
            }
            if (c == '"' && i + 2 < n && src.charAt(i + 1) == '"' && src.charAt(i + 2) == '"') {
                int close = src.indexOf("\"\"\"", i + 3);
                if (close < 0) {
                    out.append(src, i, n);
                    break;
                }
                out.append(src, i, close + 3);
                i = close + 3;
                continue;
            }
            if (c == '"' || c == '\'') {
                char quote = c;
                int start = i++;
                while (i < n && src.charAt(i) != quote) {
                    if (src.charAt(i) == '\\') {
                        i++;
                    }
                    i++;
                }
                i = Math.min(i + 1, n);
                out.append(src, start, i);
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }
}
