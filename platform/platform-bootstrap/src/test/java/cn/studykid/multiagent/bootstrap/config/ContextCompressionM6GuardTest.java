package cn.studykid.multiagent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.api.chat.AgentChatController;
import cn.studykid.multiagent.ports.agentchat.AgentChatEvent;
import cn.studykid.multiagent.ports.agentchat.AgentChatEvent.Candidate;
import cn.studykid.multiagent.ports.agentchat.AgentChatEvent.ContextCheckpoint;
import cn.studykid.multiagent.ports.agentchat.AgentChatEvent.Done;
import cn.studykid.multiagent.ports.agentchat.AgentChatEvent.Hit;
import cn.studykid.multiagent.ports.agentchat.AgentChatEvent.StepStatus;
import cn.studykid.multiagent.ports.agentchat.AgentChatRequest;

/**
 * 上下文压缩 <b>S2-M6 端口与事件契约</b>的跨模块源码守卫（ADR-013 D33–D35 / 开发计划 W4）。
 *
 * <p>⚠️ 本类钉死 M6 的<b>契约冻结面</b>（M7 签核后仍不得改变）：
 * <ol>
 *   <li>{@code agent-ports/ports/context} 只有四个中性端口，零算法层/框架/JDBC 反向依赖；</li>
 *   <li>SSE 协议 v1 → v1.1 <b>只追加不改旧帧</b>：14 事件、旧 13 帧字节冻结；
 *       {@code context.checkpoint} 自 M8-b-2 起仅在白名单内按
 *       {@code answer → usage → context.checkpoint? → done} 发射，关闭态不发射；</li>
 *   <li>M8-a 升级后，后端请求 DTO <b>只允许白名单文件</b>携带未验 {@code contextState}：
 *       全参构造、Controller 逐字透传、编排边界静默净化。S3-M13 二次升级后，两前端
 *       请求 DTO / 真实 body <b>白名单内必须携带 committed state</b>，仍只解析 v1.1 字段、
 *       不解析 / 不持久化 state，未知事件仍 {@code default: return null}；</li>
 *   <li>{@code ExecutionObserver.onContextCompacted} 是 default 空实现，链转发只做异常隔离。</li>
 * </ol>
 *
 * <p>⚠️ <b>M7-c 升级（G1 报告 A.5 台账，不删守卫）</b>：原「三条生产链路零 M6 运行时接线」
 * 已升级为「<b>只允许白名单装配边界接线</b>」——
 * L1 {@code RunExecutionLoop} 可按装配态发 {@code onContextCompacted}，
 * bootstrap adapter 可引用组合用 M6 类型；其余生产模块仍判红。
 * 关闭态必须继续走旧 {@code onContextTrimmed}。
 *
 * <p>⚠️ <b>S3-M9 二次升级（D52 / #64，不删守卫）</b>：L3 Console 从「零接线」升级为
 * 「只接中性 {@code ConsoleBudgetPort}」，其组合根 {@code ConsoleBudgetAdapter} /
 * {@code ConsoleBudgetConfiguration} 按「文件 → 允许令牌」精确登记到
 * {@link #M7_ALLOWED_WIRING}；{@code RequestAssemblyPort} 仍被禁止进入 L3，
 * 白名单外持有 M6 令牌的生产文件仍一律判红。
 *
 * <p>⚠️ 每条断言都问过「撤掉修复点它会红吗」，并配套 RV-65..RV-73 与 RV-79 撤点验证
 * （精确计数替换 + finally 复原 + md5 校验）。⚠️ 本类<b>不引用被测实现自己的常量</b>：
 * 线格式用字面量冻结，源码用语义锚点，避免「改常量 = 改测试」的自证循环（铁律 #6）。
 *
 * <p>⛔ 所有「不得出现某模式」断言均用 {@code Pattern.find()} 取反，
 * 不用 AssertJ {@code doesNotMatch}（它是<b>全串不匹配</b>语义，长函数里恒真，是假绿源）。
 *
 * <p>⛔ M13 mock 原「解析但不发送」已按 S3-M13 / D53 / #65 二次升级为
 * 「白名单内正面发送 committed state + 白名单外禁止 + 仍在解码器禁解析」；
 * M7 已按 G1 签核升级 RV-69，M8-a 已按 G1 A.5 台账把 RV-67 升级为
 * 「后端白名单内正面接入 + 白名单外禁止」。旧负面覆盖不删除：响应字段不得反向进请求、
 * 包装函数 / 解码器不得自己塞请求键。
 */
class ContextCompressionM6GuardTest {

    private static final String PORTS = "agent-ports/src/main/java/cn/studykid/multiagent/ports/";
    private static final String EVENT = PORTS + "agentchat/AgentChatEvent.java";
    private static final String OBSERVER = PORTS + "observer/ExecutionObserver.java";
    private static final String CHAIN = PORTS + "observer/ExecutionObserverChain.java";
    private static final String SPEC = PORTS + "context/ContextSpec.java";
    private static final String CONTROLLER =
            "agent-api/src/main/java/cn/studykid/multiagent/api/chat/AgentChatController.java";
    private static final String REQUEST = PORTS + "agentchat/AgentChatRequest.java";
    private static final String LOOP =
            "agent-worker/src/main/java/cn/studykid/multiagent/worker/execute/RunExecutionLoop.java";
    private static final String ORCHESTRATOR =
            "agent-worker/src/main/java/cn/studykid/multiagent/worker/agentchat/AgentChatOrchestrator.java";
    private static final List<String> FRONTEND_TRANSPORTS = List.of(
            "admin-console/src/api/agentChatApi.ts",
            "frontend/src/api/agentChatApi.ts");
    private static final List<String> FRONTEND_HOOKS = List.of(
            "admin-console/src/features/aiChat/useAgentChat.ts",
            "frontend/src/features/agentChat/useAgentChat.ts");

    private static final Pattern REQUEST_KEY = Pattern.compile("contextState\\s*:");

    private static final Set<String> FORBIDDEN_IMPORT_PREFIXES = Set.of(
            "org.springframework.", "java.sql.", "javax.sql.",
            "com.zaxxer.", "org.flywaydb.", "org.postgresql.",
            "cn.studykid.multiagent.context.");

    /** M6 契约一旦出现在生产模块（ports/算法层自身除外），就必须落在 M7 白名单边界内。 */
    private static final List<String> M6_WIRING_TOKENS = List.of(
            "ContextAssemblerPort", "ContextCheckpointPort", "SummaryGeneratorPort",
            "ContextSpec", "AssembledContext", "CheckpointDelta", "EffectiveContextPolicy",
            "ContextCheckpoint", "onContextCompacted", "CompactionPipeline",
            "BudgetAllocator", "WaterLevel", "RollingStateMerger", "StructuredStateCodec",
            "context.checkpoint");

    /**
     * G1 签核放行的<b>唯一</b> M6 令牌生产接线面（G1 报告 A.5 台账，M7 + M8-b 两批登记）。
     *
     * <p>⛔ 白名单按「文件 → 允许令牌」精确到令牌，不是整文件豁免：
     * adapter 多写一个 {@code ContextCheckpointPort} 仍会判红。
     * 新增条目必须同步 A.5 台账，并在对应里程碑做撤点反向验证。
     *
     * <p><b>M8-b-2 升级（#73）</b>：L2 SSE 轮末发射第 14 事件，新增 4 个文件持有
     * {@code ContextCheckpoint} / {@code CheckpointDelta} 令牌 —— 均按 G1 A.5
     * 「白名单精确到文件 → 令牌」登记，白名单外仍禁止。关闭态零发射、v1 兼容构造不变的
     * 负面语义由 {@link #contextCheckpointIsDefinedButNotEmitted()} 升级后的正面守卫承载。
     */
    private static final Map<String, Set<String>> M7_ALLOWED_WIRING = Map.of(
            "agent-worker/RunExecutionLoop.java", Set.of("onContextCompacted"),
            "platform-bootstrap/ContextRequestAssemblyAdapter.java",
            Set.of("AssembledContext", "ContextSpec", "CompactionPipeline"),
            // Thresholds 来自 agent-context 纯算法层 WaterLevel；组合根必须显式装配它，
            // 否则 adapter 会退化为内部 new 第二套内核（M7 RV-112）。精确登记到令牌，
            // 不允许整文件豁免；RV-74 会锁死这个令牌不得消失。
            "platform-bootstrap/ContextRequestAssemblyConfiguration.java",
            Set.of("CompactionPipeline", "WaterLevel"),
            // ── M8-b-2：L2 轮末 checkpoint 发射白名单（精确到令牌）──
            "agent-worker/AgentChatOrchestrator.java", Set.of("ContextCheckpoint"),
            "platform-bootstrap/ContextCheckpointEmitterAdapter.java",
            Set.of("CheckpointDelta", "ContextCheckpoint"),
            "platform-bootstrap/ContextCheckpointEmitterConfiguration.java",
            Set.of("ContextCheckpoint"),
            "platform-bootstrap/AgentChatConfiguration.java", Set.of("ContextCheckpoint"),
            // ── S3-M9：L3 Console 独立预算端口的组合根接线（D52 / #64）──
            // L3 不复用 RequestAssemblyPort，只在 bootstrap 持有中性 ConsoleBudgetPort；
            // 两个新组合根文件必须精确登记「文件 → 允许令牌」，白名单外仍禁止 M6 令牌。
            "platform-bootstrap/ConsoleBudgetAdapter.java",
            Set.of("ContextSpec", "CompactionPipeline"),
            "platform-bootstrap/ConsoleBudgetConfiguration.java",
            Set.of("CompactionPipeline"));

    /**
     * G1 A.5 S3-M8 台账登记的<b>请求侧 contextState 白名单</b>（精确到文件 → 令牌）。
     *
     * <p>⛔ 不是整模块豁免：新增生产文件携带 {@code contextState} 必须显式登记，
     * 否则下方的白名单外扫描立即红。当前只允许下列 12 个文件出现这三种令牌。
     *
     * <p><b>2026-11-18 对话审计插件登记（3 个文件）</b>：审计信封的
     * {@code RequestInfo.contextState} 是<b>只读事实快照</b>，不是压缩态生产者 ——
     * 插件只做「收到什么就逐字抄什么」，绝不解析 / 校验 / 改写 / 回填该值：
     * <ul>
     *   <li>{@code agent-ports/ChatAuditEnvelope.java} —— 信封契约声明该字段（类型
     *       {@code @Nullable String}，无语义），审计要能复现「用户到底带了什么 state 进来」；</li>
     *   <li>{@code agent-application/AuditingAgentChatOrchestrator.java} —— 编排边界
     *       {@code request.contextState()} 原样搬进审计请求快照，不 trim / 不解码；</li>
     *   <li>{@code agent-infrastructure/ChatAuditRecorder.java} —— 汇报时从既有请求
     *       基底同值搬运，不解析、不生成、不外发。</li>
     * </ul>
     * 三个文件都<b>不</b>持有 {@code contextStateVersion} / {@code contextStateVerifier}，
     * 也未新增请求侧读点：{@code AgentChatController} 的逐字透传断言（下方 ④）与
     * 「请求键恰好一处」断言不受影响，审计不构成第二个 state 生产者或消费者。
     * {@code Map.of} 上限 10 组，故此处改用 {@code Map.ofEntries}。
     */
    private static final Map<String, Set<String>> M8A_CONTEXT_STATE_ALLOWED = Map.ofEntries(
            Map.entry("agent-ports/AgentChatRequest.java", Set.of("contextState")),
            Map.entry("agent-ports/AgentChatEvent.java",
                    Set.of("contextState", "contextStateVersion")),
            Map.entry("agent-ports/ContextStateVerifier.java", Set.of("contextState")),
            Map.entry("agent-context/ContextStateEnvelope.java", Set.of("contextState")),
            Map.entry("agent-api/AgentChatController.java", Set.of("contextState")),
            Map.entry("agent-worker/AgentChatOrchestrator.java", Set.of("contextState")),
            Map.entry("platform-bootstrap/AgentChatConfiguration.java",
                    Set.of("contextStateVerifier")),
            Map.entry("platform-bootstrap/ContextStateVerifierAdapter.java",
                    Set.of("contextState")),
            Map.entry("platform-bootstrap/ContextStateVerifierConfiguration.java",
                    Set.of("contextState", "contextStateVerifier")),
            // ── 2026-11-18 对话审计插件：信封契约 + 搬运两处（只读事实，零语义）──
            Map.entry("agent-ports/ChatAuditEnvelope.java", Set.of("contextState")),
            Map.entry("agent-application/AuditingAgentChatOrchestrator.java",
                    Set.of("contextState")),
            Map.entry("agent-infrastructure/ChatAuditRecorder.java", Set.of("contextState")));

    /**
     * 必须真实出现的令牌；没有这些就是「白名单登记了但实现空转」。
     *
     * <p>对话审计三文件同样设为必需：审计信封一旦静默丢掉 {@code contextState}，
     * 事后就无法复现「用户当时带了什么 state 进来」，且守卫会随字段删除而假绿。
     */
    private static final Map<String, Set<String>> M8A_CONTEXT_STATE_REQUIRED = Map.of(
            "agent-ports/AgentChatRequest.java", Set.of("contextState"),
            "agent-ports/AgentChatEvent.java", Set.of("contextStateVersion"),
            "agent-api/AgentChatController.java", Set.of("contextState"),
            "agent-worker/AgentChatOrchestrator.java", Set.of("contextState"),
            "platform-bootstrap/AgentChatConfiguration.java", Set.of("contextStateVerifier"),
            "platform-bootstrap/ContextStateVerifierConfiguration.java",
            Set.of("contextStateVerifier"),
            // ── 2026-11-18 对话审计插件：信封声明 + 两处逐字搬运都不得消失 ──
            "agent-ports/ChatAuditEnvelope.java", Set.of("contextState"),
            "agent-application/AuditingAgentChatOrchestrator.java", Set.of("contextState"),
            "agent-infrastructure/ChatAuditRecorder.java", Set.of("contextState"));

    private static final List<String> M8A_CONTEXT_STATE_TOKENS =
            List.of("contextState", "contextStateVersion", "contextStateVerifier");

    private static final Pattern IMPORT = Pattern.compile(
            "^import\\s+(?:static\\s+)?([\\w.]+);", Pattern.MULTILINE);

    @Test
    @DisplayName("✅ RV-65：agent-ports 冻结四个中性端口，零算法层 / 框架 / JDBC 反向依赖")
    void contextPortsRemainNeutralAndFrameworkFree() {
        String pom = read(repoRoot().resolve("platform/agent-ports/pom.xml"));
        assertThat(pom)
                .as("⛔ agent-ports 反向依赖 agent-context：破坏 ADR-011 / C1，端口层将随算法漂移")
                .doesNotContain("<artifactId>agent-context</artifactId>");
        for (String forbidden : List.of("org.springframework", "java.sql", "javax.sql",
                "com.zaxxer", "org.flywaydb", "org.postgresql")) {
            assertThat(pom).as("⛔ agent-ports 声明了框架/持久层依赖：%s", forbidden)
                    .doesNotContain(forbidden);
        }

        Path dir = platformRoot().resolve("agent-ports/src/main/java/cn/studykid/multiagent/ports/context");
        assertThat(Files.isDirectory(dir)).as("context 端口目录必须存在").isTrue();
        List<String> violations = new ArrayList<>();
        List<String> interfaces = new ArrayList<>();
        try (Stream<Path> tree = Files.walk(dir)) {
            List<Path> files = tree.filter(p -> p.toString().endsWith(".java")).toList();
            for (Path file : files) {
                String code = stripped(file);
                Matcher imports = IMPORT.matcher(code);
                while (imports.find()) {
                    String imported = imports.group(1);
                    if (FORBIDDEN_IMPORT_PREFIXES.stream().anyMatch(imported::startsWith)) {
                        violations.add(file.getFileName() + " → " + imported);
                    }
                }
                Matcher declared = Pattern.compile("public\\s+interface\\s+(\\w+)").matcher(code);
                while (declared.find()) {
                    interfaces.add(declared.group(1));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("扫描 agent-ports/ports/context 失败", e);
        }

        assertThat(violations)
                .as("⛔ 端口层出现越界 import：M6 契约必须保持中性投影")
                .isEmpty();
        assertThat(interfaces)
                .as("⛔ context 端口数量被改变：四端口是 M6 冻结面")
                .containsExactlyInAnyOrder(
                        "TokenCounter", "ContextCheckpointPort", "SummaryGeneratorPort",
                        "ContextAssemblerPort");

        String summary = portsSrc("context/SummaryGeneratorPort.java");
        assertThat(summary)
                .as("⛔ 摘要端口必须消费不透明状态 JSON，不能把算法层 StructuredState 漏进端口层")
                .contains("Optional<String>")
                .doesNotContain("StructuredState")
                .doesNotContain("cn.studykid.multiagent.context.");
    }

    @Test
    @DisplayName("✅ RV-66：ContextSpec 是纯数据载体，不提供第二套渲染/投影口径")
    void contextSpecIsPureDataCarrier() {
        String spec = portsSrc("context/ContextSpec.java");
        assertThat(spec)
                .as("⛔ ContextSpec 自带渲染/投影：会与 ContextLayout 形成两套口径甚至伪造工具 Schema")
                .doesNotContain("toContextBlocks(")
                .doesNotContain("toolSchemaMap(")
                .doesNotContain("new ModelRequest(");
        assertThat(spec)
                .as("⛔ 身份/游标字段 fail-closed 被撤掉：空租户或负 fencing token 会静默串会话")
                .contains("tenantId == null")
                .contains("scopeKey == null")
                .contains("modelId == null")
                .contains("upToSeq < -1")
                .contains("fencingToken < 0")
                .contains("policy == null");
        assertThat(spec).contains("public ContextSpec {");
    }

    @Test
    @DisplayName("✅ RV-67（M8-a + S3-M13 二次升级）：state 仅在各层白名单内正面出现，白名单外禁止")
    void requestDtosCarryContextStateOnlyInWhitelist() {
        String controller = stripped(platformRoot().resolve(CONTROLLER));
        String request = portsSrc("agentchat/AgentChatRequest.java");
        String orchestrator = stripped(platformRoot().resolve(ORCHESTRATOR));

        // ① S3-M13：两前端请求 DTO / 真实 body 白名单内必须携带 committed state。
        //    保留的负面：响应字段 contextStateVersion 绝不反向进请求；请求键只能出现在 body 一处。
        for (String rel : FRONTEND_TRANSPORTS) {
            String raw = read(repoRoot().resolve(rel));
            String code = ContextCompressionS1GuardTest.stripCommentsKeepStrings(raw);
            String req = ContextCompressionS1GuardTest.stripCommentsKeepStrings(
                    regionBetween(raw, "export interface AgentChatRequest",
                            "export interface AgentChatSendContext"));
            assertThat(req)
                    .as("⛔ %s 请求接口缺少中性 state 字段：hook 注入的 committed state 无类型出口", rel)
                    .contains("contextState?: string")
                    .as("⛔ %s 请求接口混入响应字段 contextStateVersion：收发方向被污染", rel)
                    .doesNotContain("contextStateVersion");
            String body = ContextCompressionS1GuardTest.stripCommentsKeepStrings(
                    regionBetween(raw, "body: JSON.stringify({", "}),"));
            assertThat(REQUEST_KEY.matcher(body).find())
                    .as("⛔ %s 真实请求体未携带 committed state：S3-M13 前端空转（regex find）", rel)
                    .isTrue();
            assertThat(body)
                    .as("⛔ %s 请求体混入响应字段 contextStateVersion：收发方向被污染", rel)
                    .doesNotContain("contextStateVersion");
            assertThat(count(REQUEST_KEY, code))
                    .as("⛔ %s contextState 请求键必须恰好出现在 body 白名单一处，实际 %s 处",
                            rel, count(REQUEST_KEY, code))
                    .isEqualTo(1);
        }
        // ② 协议响应字段不进请求侧；M6 冻结的 context.checkpoint 仍只定义不发射。
        assertThat(controller)
                .as("⛔ 请求侧混入响应字段 contextStateVersion：收发方向被污染")
                .contains("record AgentChatStreamRequest(")
                .doesNotContain("contextStateVersion");
        assertThat(request)
                .as("⛔ 请求侧混入响应字段 contextStateVersion：收发方向被污染")
                .contains("record AgentChatRequest(")
                .doesNotContain("contextStateVersion");
        // G1 A.5 M8-b-2 升级（#73）：第 14 事件从「只定义不发射」改为「白名单内正面发射」。
        // 保留的负面：请求侧不得混入响应字段 —— 编排器只能在成功收口发射 checkpoint，
        // 绝不把 checkpoint 线名 / contextStateVersion 塞回请求 DTO（收发方向仍须单向）。
        assertThat(orchestrator)
                .as("⛔ SSE 链白名单内发射点消失：M8-b-2 的 context.checkpoint 被静默摘除")
                .contains("new AgentChatEvent.ContextCheckpoint(")
                .as("⛔ 成功收口 Done 未带版本：前端无法把 state 与 done 绑定")
                .contains("checkpoint == null ? null : checkpoint.version()")
                .as("⛔ 失败收口 v1 兼容构造丢失：旧客户端会收到带新字段的 done")
                .contains("new AgentChatEvent.Done(false)");

        // ③ 全参构造唯一：compat 构造器会静默清空后加字段（铁律 #10）。
        assertThat(java.util.Arrays.stream(AgentChatRequest.class.getRecordComponents())
                .map(RecordComponent::getName).toList())
                .as("⛔ 中性请求 DTO 字段顺序被改：编排入口/审计摘要可能漏带 contextState")
                .containsExactly("message", "history", "agentId", "topK",
                        "thinkingMode", "requestedTenantId", "scenario", "contextState");
        assertThat(AgentChatRequest.class.getDeclaredConstructors())
                .as("⛔ AgentChatRequest 出现兼容构造器：铁律 #10 逐字段带过会被绕过")
                .hasSize(1);
        assertThat(java.util.Arrays.stream(
                AgentChatController.AgentChatStreamRequest.class.getRecordComponents())
                .map(RecordComponent::getName).toList())
                .as("⛔ SSE 请求体字段顺序被改：前端 JSON 键与后端 DTO 会静默失配")
                .containsExactly("message", "history", "agentId", "topK",
                        "thinkingMode", "tenantId", "scenario", "contextState");
        assertThat(AgentChatController.AgentChatStreamRequest.class.getDeclaredConstructors())
                .as("⛔ AgentChatStreamRequest 出现兼容构造器：缺字段会被 Jackson 静默补空")
                .hasSize(1);

        // ④ 正面接入：Controller 必须逐字透传，不得 trim / 解码 / 当错误帧处理。
        assertThat(count(controller, "safe.contextState())"))
                .as("⛔ Controller 未把原始 state 逐字交给编排边界")
                .isEqualTo(1);
        assertThat(Pattern.compile("blankToNull\\s*\\(\\s*safe\\.contextState\\(\\)\\s*\\)")
                .matcher(controller).find())
                .as("⛔ Controller 对 state 做 blankToNull：超长/篡改会被静默改写成无状态")
                .isFalse();
        assertThat(Pattern.compile("safe\\.contextState\\(\\s*\\)\\.trim\\s*\\(")
                .matcher(controller).find())
                .as("⛔ Controller 对 state 做 trim：原始字节不再逐字下传")
                .isFalse();

        // ⑤ 白名单外禁止：任何新增生产文件携带 state 令牌都必须先登记 A.5。
        Map<String, Set<String>> found = m8aContextStateTokens();
        List<String> outside = new ArrayList<>();
        found.forEach((file, tokens) -> {
            if (!M8A_CONTEXT_STATE_ALLOWED.containsKey(file)) {
                outside.add(file + " → " + tokens);
            }
        });
        assertThat(outside).as("⛔ 白名单外生产文件出现 contextState 令牌：须先登记 A.5").isEmpty();
        found.forEach((file, tokens) -> assertThat(tokens)
                .as("⛔ 白名单文件 %s 出现未登记令牌", file)
                .isSubsetOf(M8A_CONTEXT_STATE_ALLOWED.getOrDefault(file, Set.of())));
        for (Map.Entry<String, Set<String>> required : M8A_CONTEXT_STATE_REQUIRED.entrySet()) {
            assertThat(found.getOrDefault(required.getKey(), Set.of()))
                    .as("⛔ 已登记白名单文件 %s 的 state 令牌消失（守卫会随文件退化而假绿）",
                            required.getKey())
                    .containsAll(required.getValue());
        }
    }

    @Test
    @DisplayName("✅ RV-68：SSE v1.1 只追加；14 事件、旧 13 帧字节与 Done 兼容构造冻结")
    void sseProtocolV11IsAdditiveAndFrozen() {
        String event = portsSrc("agentchat/AgentChatEvent.java");
        assertThat(count(event, "implements AgentChatEvent"))
                .as("⛔ permitted record 数量不是 14：协议 v1.1 的追加面被改变")
                .isEqualTo(14);
        assertThat(count(event, "Map.entry("))
                .as("⛔ WIRE_NAMES 不是 14 项：前端 switch 与后端线名会静默失配")
                .isEqualTo(14);
        assertThat(event)
                .contains("AgentChatEvent.ContextCheckpoint")
                .contains("record Done(boolean truncated, @Nullable String contextStateVersion)")
                .contains("public Done(boolean truncated) {")
                .as("⛔ v1 兼容构造未收敛到全参构造：铁律 #10 会被再次触发")
                .contains("this(truncated, null);")
                .contains("return \"context.checkpoint\";");

        assertThat(AgentChatEvent.WIRE_NAMES)
                .as("⛔ 线名表必须含第 14 事件且总数冻结")
                .hasSize(14)
                .containsEntry("contextCheckpoint", "context.checkpoint")
                .containsEntry("done", "done");

        assertThat(legacyEvents().stream().map(AgentChatEvent::data).toList())
                .as("⛔ 旧 13 帧 data 字节被改写：v1 客户端快照比对会静默失败")
                .containsExactlyElementsOf(legacyData());
        assertThat(new Done(false).data()).isEqualTo("{\"truncated\":false}");
        assertThat(new Done(false, "  ").data()).isEqualTo("{\"truncated\":false}");
        assertThat(new Done(false, "cp_v1").data())
                .isEqualTo("{\"truncated\":false,\"contextStateVersion\":\"cp_v1\"}");
        assertThat(new ContextCheckpoint("s", 1, "cp", 2, false, "sha256:d").data())
                .as("⛔ context.checkpoint 字段顺序冻结：前端镜像解析依赖顺序与字段名")
                .isEqualTo("{\"state\":\"s\",\"upToTurn\":1,\"policyVersion\":\"cp\","
                        + "\"tier\":2,\"fallback\":false,\"digest\":\"sha256:d\"}");
    }

    @Test
    @DisplayName("✅ RV-69（G1 A.5 / M8-b-2 升级）：第 14 事件仅在白名单内按冻结线序发射；"
            + "关闭态零发射、done 仍是唯一应用层终帧、L1 压缩观测按装配态分流")
    void contextCheckpointIsEmittedInAllowListWithFrozenWireOrder() {
        String orchestrator = stripped(platformRoot().resolve(ORCHESTRATOR));
        String loop = stripped(platformRoot().resolve(LOOP));

        // ── ① M8-b-2 正面接入：SSE 链在白名单文件内、成功收口处按顺序发射第 14 事件。
        //     旧负面「只定义不发射」按 G1 A.5 升级为「白名单内正面发射 + 白名单外禁止」。
        //     冻结线序：answer → usage → context.checkpoint? → done（旧 13 帧相对次序不变）。
        int answer = orchestrator.indexOf("new AgentChatEvent.Answer(");
        int usage = orchestrator.indexOf("new AgentChatEvent.Usage(");
        int checkpoint = orchestrator.indexOf("new AgentChatEvent.ContextCheckpoint(");
        int done = orchestrator.indexOf("new AgentChatEvent.Done(");
        assertThat(answer).as("⛔ answer 帧缺失：成功收口序列被改写").isGreaterThan(-1);
        assertThat(usage).as("⛔ usage 帧缺失：token 计数不再对用户可见").isGreaterThan(answer);
        assertThat(checkpoint)
                .as("⛔ L2 未发射 context.checkpoint：M8-b-2 契约空转").isGreaterThan(usage);
        assertThat(done).as("⛔ done 不再是应用层终帧").isGreaterThan(checkpoint);

        // ── ② 关闭态零发射的守卫（旧负面精华内化为「发射前置条件」）：
        //     未装配端口 / 非装配态 / policyVersion 裁决不到 / 封包返回 null / 异常 —— 一律 null → 不发。
        assertThat(orchestrator)
                .as("⛔ 发射前置条件被撤掉：关闭态会误发 checkpoint，逐位等价被破坏")
                .contains("checkpointEmitter == null || state.lastAssembly == null")
                .contains("!state.lastAssembly.assembled()")
                .contains("CheckpointEmission checkpoint = emitCheckpoint(")
                .as("⛔ 可选增强 fail-soft 被撤掉：封包异常会中断主流程")
                .contains("catch (RuntimeException e)")
                .as("⛔ 空 checkpoint 未走 v1 兼容构造：关闭态/无新增会泄漏空版本字段")
                .contains("checkpoint == null ? null : checkpoint.version()")
                // 失败收口仍用 v1 兼容构造；done 仍是唯一终帧（不得再加第二个终帧事件）。
                .contains("new AgentChatEvent.Done(false)");
        assertThat(count(orchestrator, "sink.accept(new AgentChatEvent.ContextCheckpoint("))
                .as("⛔ 第 14 事件发射点不是恰好 1 处：多发射会重复帧 / 少发射会空转")
                .isEqualTo(1);

        // ── ③ L1 仍只走观测链、不发射 SSE 第 14 事件（旧负面原样保留）。
        assertThat(loop)
                .as("⛔ L1 没接统一装配入口：压缩观测不会触发")
                .contains("RequestAssemblyResult assembly = assembleRequest(")
                .as("⛔ 开启态未发新事件：M6 契约空转")
                .contains("if (assembly.assembled())")
                .contains("observerChain.onContextCompacted(")
                .as("⛔ 关闭态仍发新事件：G1 要求 enabled=false 与旧行为逐位一致")
                .contains("} else if (trimmed.trimmed())")
                .contains("observerChain.onContextTrimmed(")
                .as("⛔ 观测未与唯一请求源同源：droppedTokens 可能算错")
                .contains("assembly.droppedTokens()");
        assertThat(loop)
                .as("⛔ L1 周期链发射 SSE 第 14 事件：done 不再是唯一应用层终帧")
                .doesNotContain("ContextCheckpoint")
                .doesNotContain("context.checkpoint");

        // ── ④ 白名单外禁止：仅 M7_ALLOWED_WIRING 登记的文件可持有 M6 令牌。
        List<String> violations = productionWiringViolations();
        assertThat(violations)
                .as("⛔ 生产模块出现白名单外的 M6 令牌：contract-only 边界被扩大")
                .isEmpty();
    }

    @Test
    @DisplayName("✅ RV-74：M7 接线面恰好等于 G1 登记白名单（不被改名/删除悄悄缩小）")
    void m7WiringBoundaryEqualsRegisteredAllowList() {
        Map<String, Set<String>> found = productionWiringTokens();

        assertThat(found.keySet())
                .as("⛔ 含 M6 令牌的生产文件集合与 A.5 登记不一致："
                        + "多出来的是越界接线，少的是白名单文件被改名/删除后守卫失效")
                .containsExactlyInAnyOrderElementsOf(M7_ALLOWED_WIRING.keySet());
        for (Map.Entry<String, Set<String>> entry : M7_ALLOWED_WIRING.entrySet()) {
            assertThat(found.get(entry.getKey()))
                    .as("⛔ 白名单文件 %s 的既有接线令牌消失（守卫会随文件退化而假绿）",
                            entry.getKey())
                    .containsAll(entry.getValue());
        }
    }

    @Test
    @DisplayName("✅ RV-70：Observer 新事件是 default 空实现，链转发只做异常隔离")
    void observerCompactionEventIsDefaultAndIsolated() {
        String observer = portsSrc("observer/ExecutionObserver.java");
        String chain = portsSrc("observer/ExecutionObserverChain.java");

        assertThat(observer)
                .contains("default void onContextCompacted(")
                .as("⛔ 旧 onContextTrimmed 必须保留且仍为抽象方法（[D11] 关闭态逐位等价）")
                .contains("void onContextTrimmed(")
                .doesNotContain("default void onContextTrimmed(");
        assertThat(methodBody(observer, "default void onContextCompacted(").trim())
                .as("⛔ default 方法出现副作用：既有 ExecutionObserver 实现者会被静默改变运行时行为")
                .isEmpty();

        String forward = methodBody(chain, "public void onContextCompacted(");
        assertThat(forward)
                .as("⛔ ObserverChain 未转发新事件：M7 起观测者收不到压缩事件")
                .contains("o.onContextCompacted(");
        assertThat(forward)
                .as("⛔ 单个观测者异常未被隔离：会打断主执行链，违反 Observer 契约")
                .contains("try {")
                .contains("catch (RuntimeException e)")
                .contains("logWarn(\"onContextCompacted\"");
    }

    @Test
    @DisplayName("✅ RV-71（S3-M13 升级）：未知事件忽略；只上送 committed state，解码器 / hook 不解析")
    void frontendProtocolV11SendsCommittedStateOnly() {
        for (String rel : FRONTEND_TRANSPORTS) {
            String raw = read(repoRoot().resolve(rel));
            String fn = ContextCompressionS1GuardTest.stripCommentsKeepStrings(
                    functionRegion(raw, "export function decodeAgentFrame"));
            String all = ContextCompressionS1GuardTest.stripCommentsKeepStrings(raw);

            assertThat(fn)
                    .as("⛔ %s 的未知事件兜底被改写：v1 客户端遇到未知帧会报错或污染状态", rel)
                    .contains("switch (name)")
                    .contains("case 'context.checkpoint':")
                    .contains("case 'done':")
                    .contains("contextStateVersion")
                    .containsPattern("default:\\s*return null");
            // ⛔ 解码器只读：Regex.find() 取反（doesNotMatch 是全串语义，长函数恒真）。
            assertThat(REQUEST_KEY.matcher(fn).find())
                    .as("⛔ %s 解码器出现 contextState 请求键：请求 / 响应方向被污染", rel)
                    .isFalse();
            assertThat(fn)
                    .as("⛔ %s 解码器出现请求键字面量或解析 state 正文", rel)
                    .doesNotContain("'contextState'")
                    .doesNotContain("\"contextState\"")
                    .doesNotContain("JSON.parse(state)")
                    .doesNotContain("atob(");

            String request = ContextCompressionS1GuardTest.stripCommentsKeepStrings(
                    regionBetween(raw, "export interface AgentChatRequest",
                            "export interface AgentChatSendContext"));
            assertThat(request)
                    .as("⛔ %s 请求接口缺少中性 state 字段：hook 注入的 committed state 无类型出口", rel)
                    .contains("contextState?: string")
                    .as("⛔ %s 请求接口混入响应字段 contextStateVersion：收发方向被污染", rel)
                    .doesNotContain("contextStateVersion");

            // 真实请求体在 liveAgentChatTransport 的 JSON.stringify 中，
            // 包装函数 / 接口不变不代表字节不变，必须一并冻结。
            String body = ContextCompressionS1GuardTest.stripCommentsKeepStrings(
                    regionBetween(raw, "body: JSON.stringify({", "}),"));
            assertThat(REQUEST_KEY.matcher(body).find())
                    .as("⛔ %s 真实请求体未携带 committed state：S3-M13 前端空转（regex find）", rel)
                    .isTrue();
            assertThat(body)
                    .as("⛔ %s 请求体混入响应字段 contextStateVersion：收发方向被污染", rel)
                    .doesNotContain("contextStateVersion");
            assertThat(count(REQUEST_KEY, all))
                    .as("⛔ %s contextState 请求键必须恰好出现在 body 白名单一处，实际 %s 处",
                            rel, count(REQUEST_KEY, all))
                    .isEqualTo(1);
        }

        // hook 是唯一注入口：页面参数不得传 state，STOP 不构造请求。
        for (String rel : FRONTEND_HOOKS) {
            String code = ContextCompressionS1GuardTest.stripCommentsKeepStrings(
                    read(repoRoot().resolve(rel)));
            assertThat(code)
                    .as("⛔ %s hook 未从 committed checkpoint 取 state：请求会空转或误送 pending", rel)
                    .contains("currentContextCheckpoint(stateRef.current)")
                    .as("⛔ %s hook 未把 state 放进请求 DTO", rel)
                    .contains("contextState: committed?.state")
                    .as("⛔ %s 页面参数仍可传 contextState：半截 / 伪造 state 会绕过提交闸门", rel)
                    .contains("Omit<AgentChatRequest, 'message' | 'history' | 'contextState'>");
            String stop = ContextCompressionS1GuardTest.stripCommentsKeepStrings(
                    regionBetween(code, "const stop = useCallback(", "const retry = useCallback("));
            assertThat(stop)
                    .as("⛔ %s STOP 构造了新请求：停止动作不得把半截上下文再送一次", rel)
                    .doesNotContain("sendAgentChat")
                    .as("⛔ %s STOP 未 abort 在途请求", rel)
                    .contains("controller.abort()");
        }
    }

    @Test
    @DisplayName("✅ RV-72（S3-M13 升级）：协议 v1.1 / Observer / G1 边界文档与 D33–D35、D53 同步冻结")
    void protocolDocsFreezeM6AndM13Boundary() {
        String adr = read(repoRoot().resolve(
                "docs/01_baseline/milestones/P1/ADR-013_上下文压缩统一入口与全窗口预算.md"));
        String protocol = read(repoRoot().resolve("docs/05_archive/上下文压缩SSE协议v1.1变更说明.md"));
        String observerGuide = read(repoRoot().resolve("docs/05_archive/OBSERVER-INJECTION-GUIDE.md"));

        assertThat(adr)
                .as("⛔ ADR 未登记 M6 三项决策：契约冻结没有架构依据")
                .contains("D33").contains("D34").contains("D35")
                .contains("四个中性端口")
                .contains("只追加不改旧帧")
                .contains("`default` 空实现")
                .as("⛔ 文档丢了 G1 门禁：M7 接线会被误启动")
                .contains("不得进入 M7 接线");
        assertThat(protocol)
                .as("⛔ 协议说明丢掉「只追加」硬边界")
                .contains("v1.1 只做追加，不改任何 v1 帧的一个字节")
                .contains("context.checkpoint")
                .contains("M6 不发射")
                .contains("旧客户端忽略");
        assertThat(observerGuide)
                .as("⛔ Observer 注入指南未同步 default 新方法")
                .contains("default void onContextCompacted(")
                .contains("M6 仅冻结契约");
        // G1 A.5 S3-M13 二次升级：旧 mock 边界不删，正式收发放行同时登记。
        assertThat(adr).as("⛔ ADR 未登记 D53 正式收发决策")
                .contains("D53").contains("S3-M13 正式收发");
        assertThat(protocol).as("⛔ 协议说明未登记两前端正式收发边界")
                .contains("S3-M13 正式收发").contains("contextState")
                .contains("不解析").contains("不持久化");
    }

    // ── 辅助 ──────────────────────────────────────────────────────

    private static List<AgentChatEvent> legacyEvents() {
        return List.of(
                new AgentChatEvent.RunStart("run-1"),
                new AgentChatEvent.Route("a-1", "数学助手", "显式指定",
                        List.of(new Candidate("a-1", "数学助手", null, null))),
                new AgentChatEvent.RagQuery("怎么解方程", 5),
                new AgentChatEvent.RagHits(List.of(new Hit(
                        "c-1", "先移项", 0.9, "第 10 页", "数学", "example", "语义命中")), true),
                new AgentChatEvent.Skill("skill-1", "解题", "load", StepStatus.PENDING,
                        "已装载", null, null),
                new AgentChatEvent.Tool("call-1", "get_hint", "call", StepStatus.PENDING,
                        "{}", null, null),
                new AgentChatEvent.Handoff("a-1", "委派至子 Agent"),
                new AgentChatEvent.Thinking("先移项"),
                new AgentChatEvent.Delta("你好"),
                new AgentChatEvent.Answer("你好，世界"),
                new AgentChatEvent.Usage(10, 20),
                new AgentChatEvent.Error("ROUTE_FAILED", "没有可执行 Agent"),
                new Done(false));
    }

    private static List<String> legacyData() {
        return List.of(
                "{\"runId\":\"run-1\"}",
                "{\"agentId\":\"a-1\",\"agentName\":\"数学助手\",\"reason\":\"显式指定\","
                        + "\"candidates\":[{\"agentId\":\"a-1\",\"name\":\"数学助手\"}]}",
                "{\"query\":\"怎么解方程\",\"topK\":5}",
                "{\"hits\":[{\"chunkId\":\"c-1\",\"text\":\"先移项\",\"score\":0.9,"
                        + "\"locator\":\"第 10 页\",\"edition\":\"数学\",\"role\":\"example\","
                        + "\"reason\":\"语义命中\"}],\"injected\":true}",
                "{\"skillId\":\"skill-1\",\"skillName\":\"解题\",\"phase\":\"load\","
                        + "\"status\":\"pending\",\"message\":\"已装载\"}",
                "{\"toolCallId\":\"call-1\",\"toolName\":\"get_hint\",\"phase\":\"call\","
                        + "\"status\":\"pending\",\"args\":\"{}\"}",
                "{\"from\":\"a-1\",\"note\":\"委派至子 Agent\"}",
                "先移项",
                "你好",
                "{\"text\":\"你好，世界\"}",
                "{\"inputTokens\":10,\"outputTokens\":20}",
                "{\"kind\":\"ROUTE_FAILED\",\"message\":\"没有可执行 Agent\"}",
                "{\"truncated\":false}");
    }

    /**
     * 扫描生产源码中精确出现的 M8-a state 令牌；返回「模块/文件 → 命中令牌」。
     *
     * <p>⛔ 用「前后非标识符字符」的边界而非裸 {@code String.contains}：
     * {@code contextStateVersion} 包含 {@code contextState} 前缀，裸匹配会把响应字段
     * 误算成请求字段，白名单随之失焦。
     */
    private static Map<String, Set<String>> m8aContextStateTokens() {
        Map<String, Set<String>> found = new java.util.LinkedHashMap<>();
        List<Pattern> exact = M8A_CONTEXT_STATE_TOKENS.stream()
                .map(token -> Pattern.compile("(?<![A-Za-z0-9_])"
                        + Pattern.quote(token) + "(?![A-Za-z0-9_])"))
                .toList();
        try (Stream<Path> modules = Files.list(platformRoot())) {
            for (Path module : modules.filter(Files::isDirectory).toList()) {
                Path src = module.resolve("src/main/java");
                if (!Files.isDirectory(src)) {
                    continue;
                }
                try (Stream<Path> tree = Files.walk(src)) {
                    for (Path file : tree.filter(p -> p.toString().endsWith(".java")).toList()) {
                        String code = ContextCompressionS1GuardTest.stripCommentsKeepStrings(
                                ContextCompressionS1GuardTest.read(file));
                        Set<String> hits = new LinkedHashSet<>();
                        for (int i = 0; i < M8A_CONTEXT_STATE_TOKENS.size(); i++) {
                            if (exact.get(i).matcher(code).find()) {
                                hits.add(M8A_CONTEXT_STATE_TOKENS.get(i));
                            }
                        }
                        if (!hits.isEmpty()) {
                            found.put(module.getFileName() + "/" + file.getFileName(),
                                    Set.copyOf(hits));
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("扫描生产源码的 contextState 令牌失败", e);
        }
        return found;
    }

    /** 扫描 M6 令牌在白名单外的生产接线；返回「文件 → 命中令牌」。 */
    private static Map<String, Set<String>> productionWiringTokens() {
        Map<String, Set<String>> found = new java.util.LinkedHashMap<>();
        Path platform = platformRoot();
        try (Stream<Path> modules = Files.list(platform)) {
            for (Path module : modules.filter(Files::isDirectory).toList()) {
                String name = module.getFileName().toString();
                if (name.equals("agent-ports") || name.equals("agent-context")) {
                    continue;   // 契约定义与纯算法内核本身当然持有这些名字
                }
                Path src = module.resolve("src/main/java");
                if (!Files.isDirectory(src)) {
                    continue;
                }
                try (Stream<Path> tree = Files.walk(src)) {
                    for (Path file : tree.filter(p -> p.toString().endsWith(".java")).toList()) {
                        String code = ContextCompressionS1GuardTest.stripCommentsKeepStrings(
                                ContextCompressionS1GuardTest.read(file));
                        Set<String> hits = new LinkedHashSet<>();
                        for (String token : M6_WIRING_TOKENS) {
                            if (code.contains(token)) {
                                hits.add(token);
                            }
                        }
                        if (!hits.isEmpty()) {
                            found.put(name + "/" + file.getFileName(), Set.copyOf(hits));
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("扫描 platform 生产源码的 M6 接线失败", e);
        }
        return found;
    }

    private static List<String> productionWiringViolations() {
        List<String> violations = new ArrayList<>();
        productionWiringTokens().forEach((file, tokens) -> {
            Set<String> allowed = M7_ALLOWED_WIRING.getOrDefault(file, Set.of());
            for (String token : tokens) {
                if (!allowed.contains(token)) {
                    violations.add(file + " → " + token
                            + "（允许: " + allowed + "）");
                }
            }
        });
        return violations;
    }

    private static String methodBody(String code, String signature) {
        int start = code.indexOf(signature);
        assertThat(start).as("⛔ 找不到方法签名: %s", signature).isGreaterThanOrEqualTo(0);
        int open = code.indexOf('{', start);
        assertThat(open).as("⛔ 找不到方法体开括号: %s", signature).isGreaterThan(start);

        // 必须用「配对括号」而不是首个 '}'：链转发的 catch 块会让首个 '}' 提前截断，
        // 守卫看到的 body 少了 catch，产生「异常隔离已撤掉却全绿」的假绿。
        int depth = 0;
        boolean inString = false;
        boolean inChar = false;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (inChar) {
                if (c == '\\') {
                    i++;
                } else if (c == '\'') {
                    inChar = false;
                }
                continue;
            }
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '\'') {
                inChar = true;
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return code.substring(open + 1, i);
                }
            }
        }
        throw new IllegalStateException("方法体括号不配对: " + signature);
    }

    private static String regionBetween(String src, String start, String end) {
        int from = src.indexOf(start);
        assertThat(from).as("⛔ 找不到区间起点: %s", start).isGreaterThanOrEqualTo(0);
        int to = src.indexOf(end, from);
        assertThat(to).as("⛔ 找不到区间终点: %s", end).isGreaterThan(from);
        return src.substring(from, to);
    }

    private static String functionRegion(String src, String signature) {
        int start = src.indexOf(signature);
        assertThat(start).as("⛔ 找不到前端函数: %s", signature).isGreaterThanOrEqualTo(0);
        int end = src.indexOf("\n}", start);
        assertThat(end).as("⛔ 找不到前端函数结束位置: %s", signature).isGreaterThan(start);
        return src.substring(start, end + 2);
    }

    private static String portsSrc(String rel) {
        return stripped(platformRoot().resolve(PORTS + rel));
    }

    private static String stripped(Path file) {
        return ContextCompressionS1GuardTest.stripCommentsKeepStrings(
                ContextCompressionS1GuardTest.read(file));
    }

    private static String read(Path file) {
        return ContextCompressionS1GuardTest.read(file);
    }

    private static int count(String text, String needle) {
        return ContextCompressionS1GuardTest.countOccurrences(text, needle);
    }

    /** Regex.find() 计数：⛔ 不用 doesNotMatch（全串语义，长函数恒真）。 */
    private static int count(Pattern pattern, String text) {
        int n = 0;
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            n++;
        }
        return n;
    }

    private static Path platformRoot() {
        return ContextCompressionS1GuardTest.platformRoot();
    }

    private static Path repoRoot() {
        return ContextCompressionS1GuardTest.repoRoot();
    }
}
