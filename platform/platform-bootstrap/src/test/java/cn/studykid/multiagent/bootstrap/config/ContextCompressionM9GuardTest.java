package cn.studykid.multiagent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 上下文压缩 <b>S3-M9 L3 ConsoleChat 请求级 W1/W2 预算保护</b>的跨模块源码守卫
 * （ADR-013 D52 / G1 A.5「升级不删除」台账）。
 *
 * <p>⚠️ 本类钉死七条不可绕过边界：
 * <ol>
 *   <li>{@code ConsoleBudgetPort} / {@code ConsoleBudgetException} 是中性端口层，
 *       零 Spring / 零 JDBC / 零算法层反向依赖（ADR-011）；</li>
 *   <li>生产源码<b>恰好一个</b> {@code ConsoleBudgetAdapter} 实现、一个组合根装配入口；
 *       L3 只接这个中性端口，⛔ 不复用 Run 专用 {@code RequestAssemblyPort}；</li>
 *   <li>关闭态第一行短路并返回 legacy <b>同一引用</b>，零策略 / 零能力 / 零计数 / 零摘要；
 *       启用态强制 {@code TRIM_ONLY}，只跑 W1/W2，零 W3 / 零 LLM；</li>
 *   <li>无会话态：不读 checkpoint / carry-over / fencingToken / TenantContext /
 *       cap_agent_def，不接观测链、不落库、不发 MQ、不引入 Redis；</li>
 *   <li>Controller 建 SSE 连接前只把 {@code BudgetResult.request()} 送模型，
 *       策略 / 窗口拒绝 4xx、装配故障 5xx，⛔ 绝不回退 legacy；</li>
 *   <li>行为 / wiring 测试、ADR D52 与 G1 A.5 台账同步在线；六点撤点脚本具备
 *       中断保护 / 精确锚点 / 红绿判定 / md5 复原纪律。</li>
 * </ol>
 *
 * <p>⚠️ 每条断言都问过「撤掉修复点它会红吗」。本类<b>不引用被测实现自己的常量</b>：
 * 源码用语义锚点，线格式 / 稳定码用字面量，避免「改常量 = 改测试」自证循环（铁律 #6）。
 *
 * <p>⛔ 所有「不得出现某模式」断言均用 {@code Pattern.find()} 取反，
 * 不用 AssertJ {@code doesNotMatch}（它是<b>全串不匹配</b>语义，长文件里恒真，是假绿源）。
 */
class ContextCompressionM9GuardTest {

    private static final String PORT =
            "agent-ports/src/main/java/cn/studykid/multiagent/ports/agentchat/ConsoleBudgetPort.java";
    private static final String EXCEPTION =
            "agent-ports/src/main/java/cn/studykid/multiagent/ports/agentchat/ConsoleBudgetException.java";
    private static final String ADAPTER =
            "platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/config/ConsoleBudgetAdapter.java";
    private static final String CONFIG =
            "platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/config/ConsoleBudgetConfiguration.java";
    private static final String CONTROLLER =
            "agent-api/src/main/java/cn/studykid/multiagent/api/chat/ConsoleChatController.java";

    private static final String PORT_TEST =
            "agent-ports/src/test/java/cn/studykid/multiagent/ports/agentchat/ConsoleBudgetPortTest.java";
    private static final String ADAPTER_TEST =
            "platform-bootstrap/src/test/java/cn/studykid/multiagent/bootstrap/config/ConsoleBudgetAdapterTest.java";
    private static final String WIRING_TEST =
            "platform-bootstrap/src/test/java/cn/studykid/multiagent/bootstrap/config/ConsoleBudgetWiringTest.java";
    private static final String CONTROLLER_TEST =
            "agent-api/src/test/java/cn/studykid/multiagent/api/chat/ConsoleChatControllerTest.java";

    private static final String ADR =
            "docs/P0/P1/ADR-013_上下文压缩统一入口与全窗口预算.md";
    private static final String G1_LEDGER =
            "docs/上下文压缩影子对照零差异报告_v1.0.md";
    private static final String COUNTERPROOF =
            "scripts/m9-console-budget-counterproof.sh";

    /** worker / api 模型链路层允许持有中性预算令牌的文件（G1 A.5 白名单，精确到文件名）。 */
    private static final Set<String> CONSOLE_WIRING_ALLOWLIST =
            Set.of("ConsoleChatController.java");

    /** 模型链路层可出现的 L3 预算端口令牌；集合恰好等于登记表即防越界接线。 */
    private static final List<String> CONSOLE_WIRING_TOKENS =
            List.of("ConsoleBudgetPort", "BudgetInput", "BudgetResult");

    /**
     * worker / api 模型链路层禁止出现的<b>算法层</b>令牌。
     *
     * <p>⚠️ 不把 {@code RequestAssemblyPort} / {@code RagHitQuotaPort} 这类中性端口列入：
     * L1/L2/L4 / M10 本就合法持有它们，误列会让本守卫假红（M6/M7/M10 各自守卫负责白名单）。
     * 本类只钉死「L3 Console 一条链路零算法层泄漏」，Controller 另单独禁 Run 装配端口。
     */
    private static final List<String> ALGORITHM_LEAK_TOKENS = List.of(
            "cn.studykid.multiagent.context.",
            "ContextAssembler", "ContextSpec", "CompactionPipeline", "BudgetAllocator");

    /** L3 独立协议禁止泄漏的会话态 / 观测链 / 新中间件令牌（剥注释后扫描）。 */
    private static final List<String> L3_FORBIDDEN_STATE_TOKENS = List.of(
            "fencingToken", "TenantContext", "checkpoint", "carryOver",
            "cap_agent_def", "agentVersion", "ObservableModelGateway", "ModelCallObserver",
            "RabbitTemplate", "redis", "outbox_event", "agent_message_queue");

    @Test
    @DisplayName("✅ RV-M9-01：中性预算端口 / 稳定拒因零 Spring、零 JDBC、零算法层反向依赖")
    void consoleBudgetPortIsNeutralAndFailClosed() {
        String port = stripped(PORT);
        String exception = stripped(EXCEPTION);

        assertThat(port)
                .as("⛔ L3 预算端口契约消失：Console 链路又退回无门禁的 legacy 直发")
                .contains("interface ConsoleBudgetPort")
                .as("⛔ 中性 DTO 全参构造丢失：compat 构造器会静默清空后加字段（铁律 #10）")
                .contains("record BudgetInput(")
                .contains("record BudgetResult(")
                .as("⛔ 关闭等价桥不是构造级同一引用：[D11] 逐位等价失去保证")
                .contains("static @NonNull BudgetResult passthrough(")
                .as("⛔ 关闭态携带 token / tier 行为数据即多报数字，budgeted 态空转即功能失真")
                .contains("if (!budgeted && (tier != 0 || beforeTokens != 0 || afterTokens != 0))")
                .contains("if (budgeted && afterTokens <= 0)")
                .contains("if (budgeted && afterTokens > beforeTokens)")
                .as("⛔ L3 只跑 W1/W2：tier 必须在 0/1/2 内拒绝 W3/HARD")
                .contains("tier < 0 || tier > 2")
                .doesNotContain("org.springframework", "java.sql.", "javax.sql.",
                        "cn.studykid.multiagent.context.");

        assertThat(exception)
                .as("⛔ 稳定拒因缺项：Controller 无法区分 4xx 请求错与 5xx 装配故障")
                .contains("CONTEXT_POLICY_INVALID")
                .contains("CONTEXT_WINDOW_EXCEEDED")
                .contains("CONTEXT_RATIO_INVERTED")
                .contains("CONTEXT_BUDGET_EXCEEDED")
                .contains("CONTEXT_PINNED_OVERFLOW")
                .contains("CONSOLE_BUDGET_INPUT_INVALID")
                .contains("CONSOLE_BUDGET_FAILED")
                .doesNotContain("cn.studykid.multiagent.context.");
    }

    @Test
    @DisplayName("✅ RV-M9-02：唯一 adapter 关闭态零副作用、开启态强制 TRIM_ONLY、fail-closed 不回退")
    void soleAdapterShortCircuitsWhenDisabledAndFailsClosedWhenEnabled() {
        String adapter = stripped(ADAPTER);

        assertThat(adapter)
                .as("⛔ L3 预算端口没有唯一实现：多 adapter 会让 W1/W2 口径漂移")
                .contains("implements ConsoleBudgetPort")
                .as("⛔ 关闭态没有第一行零副作用短路：影子期会多计数 / 多改请求字节")
                .contains("if (!global.enabled())")
                .contains("return BudgetResult.passthrough(legacy);")
                .as("⛔ 输出上限合法性不能在关闭态裁决：[D11] 逐位等价会被多出的 400 破坏")
                .contains("if (input.maxOutputTokens() <= 0)")
                .contains("Reason.CONSOLE_BUDGET_INPUT_INVALID")
                .as("⛔ L3 必须强制 TRIM_ONLY：只跑 W1/W2、零 LLM / 零 W3")
                .contains("ContextPolicy.Strategy.TRIM_ONLY")
                .contains("effective.strategy() != ContextPolicy.Strategy.TRIM_ONLY")
                .as("⛔ HARD / W3 档位未拒绝：D49④ 的 L3 独立协议边界被扩大")
                .contains("out.tier() > 2")
                .as("⛔ 行为分流不是引用身份：关闭态可能悄悄改写请求")
                .contains("boolean budgeted = out.request() != legacy;")
                .contains("if (!budgeted)")
                .as("⛔ 唯一装配内核没有真正被调用：adapter 空转")
                .contains("new AssemblyInput(spec, legacy, summarizer, 0)")
                .contains("assembler.assemble(")
                .contains("new BudgetResult(")
                .contains("out.request(), true, out.beforeTokens(), "
                        + "out.afterTokens(), out.tier())")
                .as("⛔ 算法层拒因未逐码投影：L3 失败会被误判成普通 SERVER_ERROR")
                .contains("catch (ContextPolicyRejectedException e)")
                .contains("catch (ContextBudgetException e)")
                .contains("catch (RuntimeException e)")
                .contains("Reason.CONSOLE_BUDGET_FAILED")
                .as("⛔ 任何路径都不得静默回退 legacy 送模型")
                .doesNotContain("return legacy;")
                .doesNotContain("RequestAssemblyPort")
                .doesNotContain("RagHitQuota");

        for (String token : L3_FORBIDDEN_STATE_TOKENS) {
            assertNoPattern(adapter, Pattern.quote(token),
                    "⛔ L3 独立协议出现会话态 / 观测链 / 新中间件令牌 " + token);
        }

        // 每次请求从请求体全量历史独立投影：无 checkpoint、无增量游标、无 carry-over。
        String spec = regionAfter(adapter, "new ContextSpec(", 900);
        assertThat(spec)
                .as("⛔ 稳定前缀未逐字取自 legacy：systemPrompt 会被动态内容污染")
                .contains("legacy.systemPrompt()")
                .as("⛔ RAG / 记忆块未对 L3 恒空")
                .contains("List.of()")
                .contains("projected.history()")
                .contains("projected.currentInput()")
                .as("⛔ 跨请求状态被引入：upToSeq / carryOver / priorCompactions 必须为哨兵值")
                .contains("-1,")
                .contains("null,")
                .contains("0);");
        assertNoPattern(adapter, "legacy\\.systemPrompt\\(\\)\\s*\\+",
                "⛔ 在稳定前缀上追加内容：供应商 prompt cache 前缀失效 [D6]");
        assertNoPattern(adapter, "projected\\.currentInput\\(\\)\\s*\\+",
                "⛔ 当前输入被二次拼装：同一 USER 可能重复发送");
    }

    @Test
    @DisplayName("✅ RV-M9-03：组合根只装配一个中性端口 Bean，复用唯一算法内核，零第二套实现")
    void configurationWiresOnePortOnTheSingleKernel() {
        String config = stripped(CONFIG);

        assertThat(config)
                .as("⛔ L3 预算端口不是唯一可覆盖 Bean：多实例会让 W1/W2 口径漂移")
                .contains("@ConditionalOnMissingBean(ConsoleBudgetPort.class)")
                .contains("ConsoleBudgetPort consoleBudgetPort(")
                .as("⛔ 组合根没有构造真 adapter：Controller 注入 Optional.empty 后功能空转")
                .contains("new ConsoleBudgetAdapter(")
                .as("⛔ 未复用 S1/M7 唯一全局开关与算法内核：两套 enabled / 阈值口径")
                .contains("GlobalContextPolicy globalContextPolicy")
                .contains("ContextAssembler contextAssembler")
                .contains("Summarizer contextNoOpSummarizer")
                .as("⛔ 模型目录缺失时未声明可选注入：能力会静默借别的模型窗口估算")
                .contains("@Nullable ModelCatalogPort modelCatalogPort")
                .as("⛔ 组合根私造第二套 ContextAssembler：改 yml 阈值会有一套不生效")
                .doesNotContain("new ContextAssembler(")
                .doesNotContain("RequestAssemblyPort")
                .doesNotContain("RagHitQuota");

        for (String token : L3_FORBIDDEN_STATE_TOKENS) {
            assertNoPattern(config, Pattern.quote(token),
                    "⛔ L3 组合根出现会话态 / 观测链 / 新中间件令牌 " + token);
        }

        assertThat(countPlatformMain("implements ConsoleBudgetPort"))
                .as("⛔ 生产源码 L3 预算端口实现数不是 1：adapter 语义必然分叉")
                .isEqualTo(1);
        assertThat(countPlatformMain("ConsoleBudgetPort consoleBudgetPort("))
                .as("⛔ 生产源码 L3 预算端口装配入口数不是 1：组合根白名单外偷接")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("✅ RV-M9-04：Controller 建连前只送预算结果；4xx/5xx 明确拒绝，绝不回退 legacy")
    void controllerSendsOnlyBudgetedRequestAndNeverFallsBack() {
        String controller = stripped(CONTROLLER);

        assertThat(controller)
                .as("⛔ L3 仍未接中性预算端口：W1/W2 预算保护在生产链路上空转")
                .contains("Optional<ConsoleBudgetPort> consoleBudgetPort")
                .contains("consoleBudgetPort == null ? null : consoleBudgetPort.orElse(null)")
                .as("⛔ 控制器复制了 enabled / strategy 判定：关闭态逐位等价失去构造级保证")
                .contains("return BudgetResult.passthrough(legacyRequest).request();")
                .as("⛔ 送模型的不是端口裁决结果：legacy 基线可能绕过预算")
                .contains("consoleBudgetPort.protect(new ConsoleBudgetPort.BudgetInput(")
                .contains("return result.request();")
                .as("⛔ 预算异常未映射稳定码：请求错与服务端故障会混为一类")
                .contains("catch (ConsoleBudgetException e)")
                .contains("statusOf(e)")
                .contains("ConsoleBudgetException.Reason.CONSOLE_BUDGET_FAILED")
                .as("⛔ L3 不得复用 Run 专用装配入口或任何算法层类型（ADR-011）")
                .doesNotContain("RequestAssemblyPort")
                .doesNotContain("RagHitQuota")
                .doesNotContain("return legacyRequest;");

        for (String token : ALGORITHM_LEAK_TOKENS) {
            assertNoPattern(controller, Pattern.quote(token),
                    "⛔ ConsoleChatController 出现算法层 / Run 装配令牌 " + token);
        }

        int protect = controller.indexOf("ModelRequest request = protectRequest(");
        int emitter = controller.indexOf("SseEmitter emitter = emitterFactory.get();");
        int gateway = controller.indexOf("chatGateway.streamChat(request,");
        assertThat(protect).as("⛔ legacy 基线未经过预算保护入口").isGreaterThanOrEqualTo(0);
        assertThat(emitter).as("⛔ 预算拒绝前已建 SSE 连接，会发 error 帧而非明确 HTTP 错误")
                .isGreaterThan(protect);
        assertThat(gateway).as("⛔ 流式网关调用点消失：Console 对话空转").isGreaterThan(emitter);
        assertThat(count(controller, "chatGateway.streamChat("))
                .as("⛔ 送模型调用不是恰好 1 处：可能出现 legacy / 预算结果双发")
                .isEqualTo(1);
        assertThat(count(controller, "emitterFactory.get()"))
                .as("⛔ SSE 连接工厂调用不是恰好 1 处")
                .isEqualTo(1);
        assertThat(countPlatformMain("BudgetResult.passthrough(legacyRequest)"))
                .as("⛔ 生产源码关闭等价桥调用点不是 1：Controller 可能复制了判定")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("✅ RV-M9-05：worker/api 中 L3 预算令牌白名单恰好等于 ConsoleChatController，零算法层泄漏")
    void consoleBudgetWiringEqualsRegisteredFileAllowList() {
        assertThat(modelChainAlgorithmLeaks())
                .as("⛔ worker / api 模型链路出现算法层或 Run 装配令牌：ADR-011 零泄漏被破坏")
                .isEmpty();

        Map<String, Path> found = consoleBudgetWiringFiles();
        assertThat(found.keySet())
                .as("⛔ L3 预算接线文件集合与 G1 A.5 登记不一致："
                        + "多出=越界接线，少=白名单文件被改名/删除后守卫失效")
                .containsExactlyInAnyOrderElementsOf(CONSOLE_WIRING_ALLOWLIST);
        String controller = stripped(found.get("ConsoleChatController.java"));
        for (String token : CONSOLE_WIRING_TOKENS) {
            assertThat(controller).as("⛔ 白名单文件的预算令牌消失：%s", token).contains(token);
        }
    }

    @Test
    @DisplayName("✅ RV-M9-06：端口 / adapter / 组合根 / Controller 四层测试与 ADR D52、A.5 台账同步在线")
    void behaviorTestsAndGovernanceLedgerStayOnline() {
        assertThat(stripped(PORT_TEST))
                .as("⛔ 端口值对象契约测试被削弱")
                .contains("budgetInputDefersNumericValidationToEnabledAdapter")
                .contains("passthroughCannotCarryBehaviorData")
                .contains("budgetedResultRejectsEmptyOrInflatedOrW3");
        assertThat(stripped(ADAPTER_TEST))
                .as("⛔ adapter 三层行为证据被削弱")
                .contains("disabledShortCircuitsWithZeroInteractions")
                .contains("enabledW0KeepsStablePrefixAndCopiesNonContextFields")
                .contains("pressureRunsW1W2AndNeverW3")
                .contains("invalidOutputTokensFailsClosedOnlyWhenEnabled")
                .contains("assemblyFailureIsFailClosed")
                .contains("noCrossRequestState");
        assertThat(stripped(WIRING_TEST))
                .as("⛔ production-only 断链守卫被削弱：行为单测全绿也可能没接线")
                .contains("consoleBudgetPortIsWiredIntoController")
                .contains("adapterSharesTheSingleAssemblerBean");
        assertThat(stripped(CONTROLLER_TEST))
                .as("⛔ Controller 唯一送模型请求 / fail-closed / 关闭等价行为证据被削弱")
                .contains("budgetedRequestIsTheOnlyRequestSentToGateway")
                .contains("passthroughBudgetResultKeepsLegacyRequestIdentity")
                .contains("budgetPolicyRejectionReturns400BeforeEmitterAndNeverFallsBack")
                .contains("budgetInfrastructureFailureReturns500WithoutLegacyFallback")
                .contains("invalidBudgetInputReturns400WithoutLegacyFallback")
                .contains("rateLimitHappensBeforeBudgetProtection");

        assertThat(raw(ADR))
                .as("⛔ ADR 未登记 D52：L3 独立预算端口无架构依据")
                .contains("D52")
                .contains("ConsoleBudgetPort")
                .contains("TRIM_ONLY")
                .contains("console:direct");
        assertThat(raw(G1_LEDGER))
                .as("⛔ G1 A.5 台账未登记 S3-M9 守卫升级：先登记后升级纪律被破坏")
                .contains("S3-M9")
                .contains("ConsoleBudgetAdapter.java")
                .contains("ContextCompressionM9GuardTest");
    }

    @Test
    @DisplayName("✅ RV-M9-07：六点反证脚本带中断保护、精确锚点、红/绿判定与 md5 复原")
    void counterproofScriptKeepsDiscipline() {
        String script = raw(COUNTERPROOF);

        assertThat(script)
                .as("⛔ 撤点标识缺失：无法证明「撤掉修复点会红」")
                .contains("RV-M9-01")
                .contains("RV-M9-02")
                .contains("RV-M9-03")
                .contains("RV-M9-04")
                .contains("RV-M9-05")
                .contains("RV-M9-06")
                .as("⛔ 中断保护缺失：SIGTERM/SIGINT 会残留撤点源码造成静默失真")
                .contains("trap restore EXIT")
                .contains("INT TERM")
                .as("⛔ 红/绿判定缺失：编译失败无报告时会被当作有效反证")
                .contains("expect_red")
                .contains("expect_green")
                .contains("Tests run:")
                .contains("Skipped: 0")
                .as("⛔ 复原三件套缺失")
                .contains("anchor_count")
                .contains("marker_count")
                .contains("md5_all")
                .contains("git diff --check");
    }

    // ── 辅助 ────────────────────────────────────────────────────────────

    private static String raw(String rel) {
        return read(repoRoot().resolve(rel));
    }

    private static String stripped(String rel) {
        return stripped(platformRoot().resolve(rel));
    }

    private static String stripped(Path file) {
        return ContextCompressionS1GuardTest.stripCommentsKeepStrings(read(file));
    }

    private static String read(Path file) {
        return ContextCompressionS1GuardTest.read(file);
    }

    private static Path platformRoot() {
        return ContextCompressionS1GuardTest.platformRoot();
    }

    private static Path repoRoot() {
        return ContextCompressionS1GuardTest.repoRoot();
    }

    private static int count(String text, String needle) {
        return ContextCompressionS1GuardTest.countOccurrences(text, needle);
    }

    private static String rel(Path file) {
        return platformRoot().relativize(file).toString().replace('\\', '/');
    }

    /** 锚点出现即失败（Pattern.find 取反，规避 doesNotMatch 全串假绿）。 */
    private static void assertNoPattern(String text, String regex, String reason) {
        assertThat(Pattern.compile(regex, Pattern.DOTALL).matcher(text).find())
                .as(reason + "（Pattern.find 取反，非全串匹配）")
                .isFalse();
    }

    /** 从锚点起截取一段源码（只看这一次装配调用，不让它跨到下一个方法）。 */
    private static String regionAfter(String code, String marker, int maxChars) {
        int at = code.indexOf(marker);
        assertThat(at).as("⛔ 找不到源码锚点: %s", marker).isGreaterThanOrEqualTo(0);
        return code.substring(at, Math.min(code.length(), at + maxChars));
    }

    private static int countPlatformMain(String needle) {
        int total = 0;
        try (Stream<Path> tree = Files.walk(platformRoot())) {
            for (Path file : tree.filter(p -> p.toString().endsWith(".java")
                    && p.toString().contains("/src/main/java/")).toList()) {
                total += count(stripped(file), needle);
            }
        } catch (IOException e) {
            throw new IllegalStateException("扫描 platform 生产源码失败", e);
        }
        return total;
    }

    /** worker / api 模型链路层禁止出现算法层包前缀或 Run 装配令牌。 */
    private static List<String> modelChainAlgorithmLeaks() {
        List<String> leaks = new ArrayList<>();
        for (String module : List.of("agent-worker", "agent-api")) {
            Path root = platformRoot().resolve(module + "/src/main/java");
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> tree = Files.walk(root)) {
                for (Path file : tree.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String code = stripped(file);
                    for (String token : ALGORITHM_LEAK_TOKENS) {
                        if (code.contains(token)) {
                            leaks.add(rel(file) + " → " + token);
                        }
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("扫描模型链路算法层泄漏失败", e);
            }
        }
        return leaks;
    }

    /** 扫描 worker / api 模型链路生产源码，返回「文件名 → 路径」中含 L3 预算令牌的文件。 */
    private static Map<String, Path> consoleBudgetWiringFiles() {
        Map<String, Path> found = new LinkedHashMap<>();
        for (String module : List.of("agent-worker", "agent-api")) {
            Path src = platformRoot().resolve(module + "/src/main/java");
            if (!Files.isDirectory(src)) {
                continue;
            }
            try (Stream<Path> tree = Files.walk(src)) {
                for (Path file : tree.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String code = stripped(file);
                    boolean wired = CONSOLE_WIRING_TOKENS.stream().anyMatch(code::contains);
                    if (wired) {
                        found.put(file.getFileName().toString(), file);
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("扫描模型链路 L3 预算接线失败", e);
            }
        }
        return found;
    }
}
