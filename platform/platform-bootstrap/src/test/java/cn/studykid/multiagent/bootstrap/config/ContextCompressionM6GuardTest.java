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
 *   <li>SSE 协议 v1 → v1.1 <b>只追加不改旧帧</b>：14 事件、旧 13 帧字节冻结、
 *       {@code context.checkpoint} 只定义不发射；</li>
 *   <li>M8-a 升级后，后端请求 DTO <b>只允许白名单文件</b>携带未验 {@code contextState}：
 *       全参构造、Controller 逐字透传、编排边界静默净化；两前端请求体仍不带该字段，
 *       只解析 v1.1 字段（{@code context.checkpoint} / {@code done.contextStateVersion}），
 *       未知事件仍 {@code default: return null}；正式同批发属 M13；</li>
 *   <li>{@code ExecutionObserver.onContextCompacted} 是 default 空实现，链转发只做异常隔离。</li>
 * </ol>
 *
 * <p>⚠️ <b>M7-c 升级（G1 报告 A.5 台账，不删守卫）</b>：原「三条生产链路零 M6 运行时接线」
 * 已升级为「<b>只允许白名单装配边界接线</b>」——
 * L1 {@code RunExecutionLoop} 可按装配态发 {@code onContextCompacted}，
 * bootstrap adapter 可引用组合用 M6 类型；其余生产模块仍判红。
 * 关闭态必须继续走旧 {@code onContextTrimmed}，SSE/Console 仍零接线。
 *
 * <p>⚠️ 每条断言都问过「撤掉修复点它会红吗」，并配套 RV-65..RV-73 与 RV-79 撤点验证
 * （精确计数替换 + finally 复原 + md5 校验）。⚠️ 本类<b>不引用被测实现自己的常量</b>：
 * 线格式用字面量冻结，源码用语义锚点，避免「改常量 = 改测试」的自证循环（铁律 #6）。
 *
 * <p>⛔ 所有「不得出现某模式」断言均用 {@code Pattern.find()} 取反，
 * 不用 AssertJ {@code doesNotMatch}（它是<b>全串不匹配</b>语义，长函数里恒真，是假绿源）。
 *
 * <p>⛔ M13 mock 已显式升级 RV-71 为「解析但不发送」；M7 已按 G1 签核升级 RV-69。
 * M8-a 已按 G1 A.5 台账把 RV-67 升级为「白名单内正面接入 + 白名单外禁止」，
 * M13 前端正式同批发时仍须显式二次升级，不得直接删守卫了事。
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
     * G1 签核放行的<b>唯一</b> M7 接线面（G1 报告 A.5 台账）。
     *
     * <p>⛔ 白名单按「文件 → 允许令牌」精确到令牌，不是整文件豁免：
     * adapter 多写一个 {@code ContextCheckpointPort} 仍会判红。
     * 新增条目必须同步 A.5 台账，并在 #45 做撤点反向验证。
     */
    private static final Map<String, Set<String>> M7_ALLOWED_WIRING = Map.of(
            "agent-worker/RunExecutionLoop.java", Set.of("onContextCompacted"),
            "platform-bootstrap/ContextRequestAssemblyAdapter.java",
            Set.of("AssembledContext", "ContextSpec", "CompactionPipeline"),
            // Thresholds 来自 agent-context 纯算法层 WaterLevel；组合根必须显式装配它，
            // 否则 adapter 会退化为内部 new 第二套内核（M7 RV-112）。精确登记到令牌，
            // 不允许整文件豁免；RV-74 会锁死这个令牌不得消失。
            "platform-bootstrap/ContextRequestAssemblyConfiguration.java",
            Set.of("CompactionPipeline", "WaterLevel"));

    /**
     * G1 A.5 S3-M8 台账登记的<b>请求侧 contextState 白名单</b>（精确到文件 → 令牌）。
     *
     * <p>⛔ 不是整模块豁免：新增生产文件携带 {@code contextState} 必须显式登记，
     * 否则下方的白名单外扫描立即红。当前 M8-a 只允许 8 个文件出现以下三种令牌。
     */
    private static final Map<String, Set<String>> M8A_CONTEXT_STATE_ALLOWED = Map.of(
            "agent-ports/AgentChatRequest.java", Set.of("contextState"),
            "agent-ports/AgentChatEvent.java", Set.of("contextState", "contextStateVersion"),
            "agent-ports/ContextStateVerifier.java", Set.of("contextState"),
            "agent-context/ContextStateEnvelope.java", Set.of("contextState"),
            "agent-api/AgentChatController.java", Set.of("contextState"),
            "agent-worker/AgentChatOrchestrator.java", Set.of("contextState"),
            "platform-bootstrap/AgentChatConfiguration.java", Set.of("contextStateVerifier"),
            "platform-bootstrap/ContextStateVerifierAdapter.java", Set.of("contextState"),
            "platform-bootstrap/ContextStateVerifierConfiguration.java",
            Set.of("contextState", "contextStateVerifier"));

    /** 必须真实出现的令牌；没有这些就是「白名单登记了但实现空转」。 */
    private static final Map<String, Set<String>> M8A_CONTEXT_STATE_REQUIRED = Map.of(
            "agent-ports/AgentChatRequest.java", Set.of("contextState"),
            "agent-ports/AgentChatEvent.java", Set.of("contextStateVersion"),
            "agent-api/AgentChatController.java", Set.of("contextState"),
            "agent-worker/AgentChatOrchestrator.java", Set.of("contextState"),
            "platform-bootstrap/AgentChatConfiguration.java", Set.of("contextStateVerifier"),
            "platform-bootstrap/ContextStateVerifierConfiguration.java",
            Set.of("contextStateVerifier"));

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
    @DisplayName("✅ RV-67（M8-a 升级）：后端 DTO 仅在白名单携带原始 contextState，前端请求字节冻结")
    void requestDtosDoNotCarryCheckpointState() {
        String controller = stripped(platformRoot().resolve(CONTROLLER));
        String request = portsSrc("agentchat/AgentChatRequest.java");
        String orchestrator = stripped(platformRoot().resolve(ORCHESTRATOR));

        // ① 旧负面覆盖保留：两前端请求接口与真实 JSON.stringify 请求体仍禁带 state。
        for (String rel : List.of(
                "admin-console/src/api/agentChatApi.ts",
                "frontend/src/api/agentChatApi.ts")) {
            String raw = read(repoRoot().resolve(rel));
            String body = ContextCompressionS1GuardTest.stripCommentsKeepStrings(
                    regionBetween(raw, "body: JSON.stringify({", "}),"));
            assertThat(body)
                    .as("⛔ %s 请求体提前上送 contextState：M13 才允许正式同批发", rel)
                    .doesNotContain("contextState")
                    .doesNotContain("contextStateVersion");
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
        assertThat(orchestrator)
                .as("⛔ SSE 链提前发射第 14 事件：M8-a 只做接收侧净化")
                .doesNotContain("new AgentChatEvent.ContextCheckpoint(")
                .doesNotContain("context.checkpoint")
                .doesNotContain("new AgentChatEvent.Done(false,")
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
    @DisplayName("✅ RV-69（M7-c 升级）：context.checkpoint 仍只定义不发射；压缩观测按装配态分流")
    void contextCheckpointIsDefinedButNotEmitted() {
        String orchestrator = stripped(platformRoot().resolve(ORCHESTRATOR));
        String loop = stripped(platformRoot().resolve(LOOP));

        assertThat(orchestrator)
                .as("⛔ SSE 链提前发射 context.checkpoint：旧客户端与审计口径均未升级")
                .doesNotContain("ContextCheckpoint")
                .doesNotContain("context.checkpoint")
                .as("⛔ done 仍是唯一应用层终帧")
                .contains("new AgentChatEvent.Done(false)");

        // ⚠️ G1 签核后 L1 已接线：新事件由「装配态」唯一推导，关闭态仍走旧事件。
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
                .as("⛔ context.checkpoint 提前发射：done 不再是唯一应用层终帧")
                .doesNotContain("ContextCheckpoint")
                .doesNotContain("context.checkpoint");

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
    @DisplayName("✅ RV-71：两前端未知事件忽略；M13 mock 只解析 v1.1 字段，请求接口/真实请求体均不上送 contextState")
    void frontendProtocolV11MockIsParseOnly() {
        for (String rel : List.of(
                "admin-console/src/api/agentChatApi.ts",
                "frontend/src/api/agentChatApi.ts")) {
            String raw = read(repoRoot().resolve(rel));
            String fn = functionRegion(raw, "export function decodeAgentFrame");
            String code = ContextCompressionS1GuardTest.stripCommentsKeepStrings(fn);

            assertThat(code)
                    .as("⛔ %s 的未知事件兜底被改写：v1 客户端遇到未知帧会报错或污染状态", rel)
                    .contains("switch (name)")
                    .contains("case 'context.checkpoint':")
                    .contains("case 'done':")
                    .contains("contextStateVersion")
                    .containsPattern("default:\\s*return null");

            String request = ContextCompressionS1GuardTest.stripCommentsKeepStrings(
                    regionBetween(raw, "export interface AgentChatRequest",
                            "export interface AgentChatSendContext"));
            assertThat(request)
                    .as("⛔ %s 请求接口提前加入 contextState：M13 mock 必须只解析不上送（M8 + G1 签核）", rel)
                    .doesNotContain("contextState")
                    .doesNotContain("contextStateVersion");
            // ⛔ AssertJ doesNotMatch 是「全串不匹配」语义，长函数里永远为真；
            //    必须用 Pattern.find() 取反，否则撤掉这个修复点守卫仍绿。
            assertThat(Pattern.compile("contextState\\s*:").matcher(code).find())
                    .as("⛔ %s 解码器出现 contextState 请求键：请求字节零变更被破坏", rel)
                    .isFalse();
            assertThat(code)
                    .as("⛔ %s 解码器出现 contextState 请求键字面量", rel)
                    .doesNotContain("'contextState'")
                    .doesNotContain("\"contextState\"");

            // 真实请求体在 liveAgentChatTransport 的 JSON.stringify 中，
            // 包装函数 / 接口不变不代表字节不变，必须一并冻结。
            String body = ContextCompressionS1GuardTest.stripCommentsKeepStrings(
                    regionBetween(raw, "body: JSON.stringify({", "}),"));
            assertThat(body)
                    .as("⛔ %s 真实请求体提前加入 contextState：M13 mock 只解析不上送", rel)
                    .doesNotContain("contextState")
                    .doesNotContain("contextStateVersion");
            assertThat(Pattern.compile("contextState\\s*:").matcher(body).find())
                    .as("⛔ %s 真实请求体出现 contextState: 键（regex find，非全串匹配）", rel)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("✅ RV-72：协议 v1.1 / Observer / G1 边界文档与 ADR D33–D35 同步冻结")
    void protocolDocsFreezeM6Boundary() {
        String adr = read(repoRoot().resolve(
                "docs/P0/P1/ADR-013_上下文压缩统一入口与全窗口预算.md"));
        String protocol = read(repoRoot().resolve("docs/上下文压缩SSE协议v1.1变更说明.md"));
        String observerGuide = read(repoRoot().resolve("docs/OBSERVER-INJECTION-GUIDE.md"));

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

    private static Path platformRoot() {
        return ContextCompressionS1GuardTest.platformRoot();
    }

    private static Path repoRoot() {
        return ContextCompressionS1GuardTest.repoRoot();
    }
}
