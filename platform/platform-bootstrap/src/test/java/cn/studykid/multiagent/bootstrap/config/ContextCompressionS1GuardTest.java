package cn.studykid.multiagent.bootstrap.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上下文压缩 S1 影子期的架构守卫（ADR-013 / 开发计划 §0.4、§7.4）。
 *
 * <p>⚠️ <b>每条断言都能「撤掉修复点变红」</b>：把白名单登记删掉、
 * 把门禁调用删掉、把 {@code enabled} 默认改成 true、把独立 HMAC 键删掉、
 * 或给纯算法层加一个 Spring/JDBC import，本类对应用例立即失败。
 *
 * <p>⚠️ 本类是<b>阶段守卫</b>：S2 接线完成后按计划升级为
 * {@code NoDirectTrimCallOutsideAssemblerGuard} 等运行期守卫，
 * 届时可退役的断言须在评审记录里显式登记，不得直接删除了事。
 */
class ContextCompressionS1GuardTest {

    private static final Set<String> FORBIDDEN_IMPORT_PREFIXES = Set.of(
            "org.springframework.", "java.sql.", "javax.sql.",
            "com.zaxxer.", "org.flywaydb.", "org.postgresql.");

    private static final Pattern IMPORT = Pattern.compile(
            "^import\\s+(?:static\\s+)?([\\w.]+);", Pattern.MULTILINE);

    /**
     * G1 A.5 S3-M8-b 台账登记的<b>模型链路统一装配白名单</b>（精确文件 → 必需令牌，#72 从
     * 「L2/L3 一律禁止 RequestAssemblyPort」升级而来，旧负面覆盖不删除）。
     *
     * <p>⛔ keySet 必须与扫描结果完全一致：L3 Console 或任何新文件接线即红；
     * 白名单文件被删 / 改名 / 必需令牌消失也红（防守卫随实现退化而假绿）。
     * 组合根装配边界由 M6/M7 守卫单独钉死，此处只管 worker / api 模型链路层。
     */
    private static final Map<String, Set<String>> REQUEST_ASSEMBLY_WIRING = Map.of(
            "RunExecutionLoop.java", Set.of("RequestAssemblyPort", "assembly.request()"),
            "WorkerChildAgentRunner.java", Set.of("RequestAssemblyPort", "assembly.request()"),
            "AgentChatOrchestrator.java", Set.of("RequestAssemblyPort",
                    "assembly.request()", "RequestAssemblyResult.passthrough(",
                    "CONTEXT_ASSEMBLED", "assembly.reason().name()",
                    "state.carryOver.stateJson()"));

    /** S3-M8-b-1（#72）撤点反证脚本，钉死「升级后的白名单守卫」不是空断言。 */
    private static final String COUNTERPROOF = "scripts/m8b-l2-assembly-counterproof.sh";
    private static final String COUNTERPROOF_M8B2 = "scripts/m8b2-checkpoint-emission-counterproof.sh";

    @Test
    @DisplayName("✅ agent-context 是纯算法层：零 Spring / 零 JDBC / 零驱动 import")
    void contextModuleIsFrameworkFree() {
        Path src = platformRoot().resolve("agent-context/src/main/java");
        assertThat(Files.isDirectory(src)).as("agent-context 模块必须存在（[D1]）").isTrue();

        List<String> violations = new ArrayList<>();
        try (Stream<Path> tree = Files.walk(src)) {
            for (Path file : tree.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher matcher = IMPORT.matcher(read(file));
                while (matcher.find()) {
                    String imported = matcher.group(1);
                    if (FORBIDDEN_IMPORT_PREFIXES.stream().anyMatch(imported::startsWith)) {
                        violations.add(file.getFileName() + " → " + imported);
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("扫描 agent-context 源码失败", e);
        }

        assertThat(violations)
                .as("⛔ 纯算法层被框架/持久层污染：C1 要求 DB 写入只能在 agent-persistence")
                .isEmpty();
    }

    @Test
    @DisplayName("✅ 发布期门禁调用同一份压缩策略校验器，不另写范围常量")
    void validationGateUsesSharedValidator() {
        // ⚠️ 必须剥离注释再扫：Javadoc 里解释「为什么」的表达式不是实现，
        //    不剥离会把「文档写得清楚」误判成「另写了一套校验」（假红）。
        String code = stripCommentsKeepStrings(read(platformRoot().resolve(
                "agent-application/src/main/java/cn/studykid/multiagent/application/cap"
                        + "/ConfigValidationGate.java")));

        assertThat(code)
                .as("⛔ validate() 未调用压缩策略门禁：非法策略会活到配置生效之后")
                .contains("validateGenerationParams(command)");
        assertThat(code)
                .as("⛔ 未复用 agent-context 的唯一校验实现：发布期与执行期必然漂移")
                .contains("ContextPolicyValidator.validate(");
        assertThat(code)
                .as("⛔ 门禁里出现硬编码比例常量：两套范围常量迟早不一致")
                .doesNotContain("0.70")
                .doesNotContain("targetRatio >= triggerRatio");
        assertThat(code)
                .as("⛔ 发布期未复用运行期同一份 JSON 归一化：合法/非法判定会漂移")
                .contains("ContextPolicyReader.contextNode(")
                .contains("ContextPolicyReader.toPlainMap(")
                .doesNotContain("MAPPER.readTree(agent.generationParams())");
    }

    @Test
    @DisplayName("✅ 依赖方向守卫已登记 agent-context（撤掉登记即红）")
    void dependencyDirectionWhitelistIsRegistered() {
        String guard = read(platformRoot().resolve(
                "platform-bootstrap/src/test/java/cn/studykid/multiagent/bootstrap/config"
                        + "/ModuleDependencyDirectionTest.java"));

        assertThat(guard)
                .as("⛔ 应用层依赖纯算法层却未登记白名单：ModuleDependencyDirectionTest 会红")
                .contains("\"agent-context\"");
    }

    @Test
    @DisplayName("✅ S1 影子期默认关闭，且 HMAC 密钥为独立键")
    void shadowPhaseIsDisabledByDefault() {
        String yml = read(platformRoot().resolve(
                "platform-bootstrap/src/main/resources/application.yml"));

        assertThat(yml)
                .as("⛔ 影子期默认开启会改变模型请求字节，违反 G1「影子对照零差异」")
                .contains("AGENT_CONTEXT_ENABLED:false");
        assertThat(yml)
                .as("⛔ 缺少独立 HMAC 键：contextState 验签会退化成复用 JWT 私钥")
                .contains("AGENT_CONTEXT_STATE_HMAC_KEY");
    }

    @Test
    @DisplayName("✅ env 模板声明独立密钥占位，且不含真实值")
    void envTemplateDeclaresIndependentKey() {
        String env = read(repoRoot().resolve("platform.env.example"));

        assertThat(env).contains("AGENT_CONTEXT_STATE_HMAC_KEY=<MUST-FILL>");
        assertThat(env)
                .as("⛔ 密钥必须独立于两套 JWT 私钥")
                .contains("绝不复用 JWT 私钥");
    }

    @Test
    @DisplayName("✅ M8-b 关闭等价守卫（M7 守卫按 G1 A.5 台账升级）：L1/L2/L4 统一装配白名单精确到文件→令牌；L3 Console 仍零接线；算法层零泄漏")
    void closedEquivalenceHasNoAlgorithmLayerLeak() {
        // ⚠️ G1 报告 A.5 台账：本方法由「S1 零接线」→「M7 L1/L4 正面接入」两次升级而来，不删除。
        //    本批（M8-b-1 / #72）第三次升级：L2 SSE 接入统一装配入口；
        //    旧禁止面（算法层类型不得进任何模型链路）完整保留；
        //    L1/L4 正面断言保留；新增 L2 精确白名单 + 白名单外禁止 + 关闭态同一引用。
        List<String> chains = List.of(
                "agent-worker/src/main/java/cn/studykid/multiagent/worker/execute/RunExecutionLoop.java",
                "agent-worker/src/main/java/cn/studykid/multiagent/worker/execute/WorkerChildAgentRunner.java",
                "agent-worker/src/main/java/cn/studykid/multiagent/worker/agentchat/AgentChatOrchestrator.java",
                "agent-api/src/main/java/cn/studykid/multiagent/api/chat/ConsoleChatController.java");
        List<String> forbidden = List.of(
                "ContextAssembler", "ContextPolicyResolver", "BudgetAllocator",
                "CompactionPipeline", "cn.studykid.multiagent.context.");

        // ── ① 旧负面覆盖（原样保留）：算法层类型不得泄漏进任何模型链路 ──
        List<String> violations = new ArrayList<>();
        for (String rel : chains) {
            Path file = platformRoot().resolve(rel);
            if (!Files.exists(file)) {
                continue;   // 文件被重构/移位时由 CH 契约单处理，此处不假红
            }
            String code = stripCommentsKeepStrings(read(file));
            for (String token : forbidden) {
                if (code.contains(token)) {
                    violations.add(rel + " → " + token);
                }
            }
        }
        assertThat(violations)
                .as("⛔ 算法层类型泄漏进模型链路：worker 只能认识 ports/contextwiring 中性端口（ADR-011）")
                .isEmpty();

        // ── ② M7 既有正面断言（原样保留）：L1/L4 只经统一装配入口 ──
        String loop = stripCommentsKeepStrings(read(platformRoot().resolve(chains.get(0))));
        String child = stripCommentsKeepStrings(read(platformRoot().resolve(chains.get(1))));
        assertThat(loop)
                .as("⛔ L1 没接统一请求装配入口：关闭态逐位等价失去构造级保证")
                .contains("RequestAssemblyPort")
                .contains("RequestAssemblyResult assembly = assembleRequest(")
                .contains("if (!assembly.assembled())")
                .contains("ModelRequest request = assembly.request();");
        assertThat(child)
                .as("⛔ L4 没接统一请求装配入口：父子两套裁剪语义")
                .contains("RequestAssemblyPort")
                .contains("RequestAssemblyResult assembly = assembleRequest(")
                .contains("assembly.request()");

        // ── ③ M8-b 升级：L2 SSE 正面接入（白名单精确到文件→令牌）──
        String orchestrator = stripCommentsKeepStrings(read(platformRoot().resolve(chains.get(2))));
        assertThat(orchestrator)
                .as("⛔ L2 没接统一请求装配入口：送模型的还是 legacy 请求，装配层空转")
                .contains("RequestAssemblyPort")
                .contains("RequestAssemblyResult assembly = assembleRequest(")
                .contains("ModelRequest modelRequest = assembly.request();")
                .as("⛔ L2 关闭态没有回退 legacy 同一引用：逐位等价失去构造级保证")
                .contains("if (requestAssemblyPort == null)")
                .contains("RequestAssemblyResult.passthrough(legacyRequest)")
                .as("⛔ L2 装配拒绝未 fail-closed：错误码会被吞成 SERVER_ERROR")
                .contains("assembly.reason().name()")
                .as("⛔ L2 carry-over 未只取已验载荷，或装配态未记真实事实")
                .contains("state.carryOver.stateJson()")
                .contains("CONTEXT_ASSEMBLED");

        // ── ④ 白名单外禁止：worker / api 模型链路层的接线文件集合必须恰好等于登记白名单 ──
        //    防两条静默失真：(a) L3 Console 被扩大接线；(b) 新文件偷接装配入口绕过守卫。
        Map<String, Path> found = requestAssemblyWiringFiles();
        assertThat(found.keySet())
                .as("⛔ 模型链路统一装配接线文件集合与 G1 A.5 登记白名单不一致："
                        + "多出=越界接线，少=白名单文件被改名/删除后守卫失效")
                .containsExactlyInAnyOrderElementsOf(REQUEST_ASSEMBLY_WIRING.keySet());
        for (Map.Entry<String, Set<String>> e : REQUEST_ASSEMBLY_WIRING.entrySet()) {
            assertThat(stripCommentsKeepStrings(read(found.get(e.getKey()))))
                    .as("⛔ 白名单文件 %s 的必需装配令牌消失（守卫随实现退化而假绿）", e.getKey())
                    .contains(e.getValue().toArray(new String[0]));
        }
        // L3 Console 结构证据边界在此次升级中仍不扩大（原负面语义显式保留）。
        String console = stripCommentsKeepStrings(read(platformRoot().resolve(chains.get(3))));
        assertThat(console)
                .as("⛔ L3 Console 结构证据边界被扩大：Console 仍 contract-only，不得接统一装配入口")
                .doesNotContain("RequestAssemblyPort");
    }

    /**
     * 扫描 worker / api 模型链路层生产源码，返回「文件名 → 路径」中含统一装配令牌的文件。
     *
     * <p>⛔ 只扫模型链路两层（不扫 bootstrap 组合根——那里的装配边界由 M6/M7 守卫钉死），
     * 否则 adapter / config 持有端口会被误判为越界接线。
     */
    private static Map<String, Path> requestAssemblyWiringFiles() {
        Map<String, Path> found = new LinkedHashMap<>();
        for (String module : List.of("agent-worker", "agent-api")) {
            Path src = platformRoot().resolve(module + "/src/main/java");
            if (!Files.isDirectory(src)) {
                continue;
            }
            try (Stream<Path> tree = Files.walk(src)) {
                for (Path file : tree.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String code = stripCommentsKeepStrings(read(file));
                    boolean wired = List.of("RequestAssemblyPort", "RequestAssemblyResult",
                            "RequestAssemblyInput", "RequestAssemblyException").stream()
                            .anyMatch(code::contains);
                    if (wired) {
                        found.put(file.getFileName().toString(), file);
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("扫描模型链路统一装配接线失败", e);
            }
        }
        return found;
    }

    @Test
    @DisplayName("✅ M8-b 反证脚本带中断保护、精确锚点、红/绿判定与 md5 复原（升级守卫不是空断言）")
    void m8bCounterproofScriptKeepsDiscipline() {
        String script = read(repoRoot().resolve(COUNTERPROOF));

        assertThat(script)
                .as("⛔ 撤点标识缺失：无法证明「撤掉修复点会红」")
                .contains("RV-M8B-01")
                .contains("RV-M8B-02")
                .contains("RV-M8B-03")
                .contains("RV-M8B-04")
                .contains("RV-M8B-05")
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

    @Test
    @DisplayName("✅ M8-b-2 反证脚本带中断保护、精确锚点、红/绿判定与 md5 复原（第 14 事件发射不是空断言）")
    void m8b2CounterproofScriptKeepsDiscipline() {
        String script = read(repoRoot().resolve(COUNTERPROOF_M8B2));

        assertThat(script)
                .as("⛔ 撤点标识缺失：无法证明「撤掉修复点会红」")
                .contains("RV-M8B2-01")
                .contains("RV-M8B2-02")
                .contains("RV-M8B2-03")
                .contains("RV-M8B2-04")
                .contains("RV-M8B2-05")
                .contains("RV-M8B2-06")
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

    @Test
    @DisplayName("⚠️ R10：能力未知时的 reserve 配置键必须存在且注入 GlobalContextPolicy（缺键=静默失效）")
    void unknownCapabilityReserveIsWiredFromYml() {
        String yml = read(platformRoot().resolve(
                "platform-bootstrap/src/main/resources/application.yml"));
        assertThat(yml)
                .as("⛔ 缺少 agent.context.unknown-capability-reserve-ratio："
                        + "能力未知时不抬安全余量，估算偏小 → 静默超窗（R10）")
                .contains("unknown-capability-reserve-ratio:")
                .contains("AGENT_CONTEXT_UNKNOWN_RESERVE");

        String config = stripCommentsKeepStrings(read(platformRoot().resolve(
                "platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/config"
                        + "/AgentContextConfiguration.java")));
        assertThat(config)
                .as("⛔ GlobalContextPolicy 第 4 参数被写死或漏传：运维改 env 永远无效")
                .contains("properties.getUnknownCapabilityReserveRatio()");
        assertThat(config)
                .as("⛔ 启动期未校验 reserve 上界：配成 1.0 会让预算全被余量吃光却不报错")
                .contains("getUnknownCapabilityReserveRatio() > 0")
                .contains("<= 0.25");

        // 校准 alpha 必须也有 yml 键，否则与 0.20 默认值漂移。
        assertThat(yml).as("⛔ 缺 calibration.alpha 键").contains("alpha:")
                .contains("AGENT_CONTEXT_CALIBRATION_ALPHA");
    }

    @Test
    @DisplayName("✅ 校准桥是只读 ModelCallObserver：不构造/替换 ModelRequest，只投影计数")
    void calibrationBridgeIsReadOnlyObserver() {
        String observer = stripCommentsKeepStrings(read(platformRoot().resolve(
                "agent-infrastructure/src/main/java/cn/studykid/multiagent/infrastructure"
                        + "/observer/metrics/ContextCalibrationObserver.java")));
        assertThat(observer)
                .as("⛔ 校准桥必须实现 ModelCallObserver 才能挂进观测链")
                .contains("implements ModelCallObserver");
        assertThat(observer)
                .as("⛔ 校准桥构造了新 ModelRequest：G1「请求字节零差异」被破坏")
                .doesNotContain("new ModelRequest(");
        assertThat(observer)
                .as("⛔ 校准桥退化为「只看消息序列」估算：全窗口口径丢失，bias 会把超窗悄悄放大")
                .contains("RequestContextProjection.project(request)")
                .contains("counter.count(");
        assertThat(observer)
                .as("⛔ afterCall 未用 usage 真值喂校准器：bias 恒 1.0，S2 拿不到可信系数")
                .contains("calibrator.addSample(")
                .contains("response.usage().inputTokens()");
        assertThat(observer)
                .as("⛔ ThreadLocal 未在 finally 清理：线程池复用会串味到下一次请求")
                .contains("finally")
                .contains("clearQuietly()")
                .contains("pendingEstimate.remove()");

        // 投影本身也必须只读、确定性（Map 递归排序）。
        String projection = stripCommentsKeepStrings(read(platformRoot().resolve(
                "agent-context/src/main/java/cn/studykid/multiagent/context"
                        + "/RequestContextProjection.java")));
        assertThat(projection)
                .as("⛔ 投影构造了 ModelRequest：它会参与请求构造，违反影子期只读")
                .doesNotContain("new ModelRequest(");
        assertThat(projection)
                .as("⛔ Map 未递归按 key 排序：Map.copyOf 迭代序不定 → 同请求两次估算不同，"
                        + "校准样本变噪声且静默")
                .contains("new TreeMap<>()")
                .contains("sorted.put(String.valueOf(e.getKey())");
    }

    @Test
    @DisplayName("✅ 校准桥按 @Order(40) 排在观测链末尾，且由 @ConditionalOnProperty 开关控制")
    void calibrationBridgeIsLastAndFeatureGated() {
        String observer = read(platformRoot().resolve(
                "agent-infrastructure/src/main/java/cn/studykid/multiagent/infrastructure"
                        + "/observer/metrics/ContextCalibrationObserver.java"));
        assertThat(observer).contains("@Order(40)")
                .as("⛔ 观测者顺序未钉死：若插在事件桥接之前，自身失败会影响后续观测者");

        String wiring = read(platformRoot().resolve(
                "platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/config"
                        + "/ObserverConfiguration.java"));
        assertThat(wiring)
                .as("⛔ 校准桥装配未加启用开关：影子期无法一键关停该观测者")
                .contains("@ConditionalOnProperty(prefix = \"agent.context.calibration\"")
                .contains("matchIfMissing = false");
        assertThat(wiring)
                .as("⛔ 装配参数顺序错（MeterRegistry 必须是第一个）")
                .contains("new ContextCalibrationObserver(\n                registry, contextTokenCounter");
    }

    @Test
    @DisplayName("⚠️ 校准桥装配闭环：Bean → List<ModelCallObserver> → Chain → ObservableModelGateway → Worker 两链路")
    void calibrationBridgeIsActuallyInTheCallPath() {
        String wiring = stripCommentsKeepStrings(read(platformRoot().resolve(
                "platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/config"
                        + "/ObserverConfiguration.java")));

        // 链必须由「容器注入的全部观测者」构造，不能写死 List.of()（否则校准桥 Bean 存在却空转）。
        assertThat(wiring)
                .as("⛔ ModelCallObserverChain 未从注入的观测者列表构造："
                        + "校准桥 Bean 会被创建但永远不进调用链（功能空转）")
                .contains("modelCallObserverChain(")
                .contains("List<ModelCallObserver> observers")
                .contains("new ModelCallObserverChain(observers)");
        // 可观测网关必须真的把链包进 delegate。
        assertThat(wiring)
                .as("⛔ observableModelGateway 未注入观测者链：观测者不会被触发")
                .contains("ObservableModelGateway(rawModelGateway, observerChain)");

        // Worker 两条链路必须注入「可观测」网关并调用带上下文的 4 参 complete。
        for (String rel : List.of(
                "agent-worker/src/main/java/cn/studykid/multiagent/worker/execute/RunExecutionLoop.java",
                "agent-worker/src/main/java/cn/studykid/multiagent/worker/execute/WorkerChildAgentRunner.java")) {
            String code = stripCommentsKeepStrings(read(platformRoot().resolve(rel)));
            assertThat(code)
                    .as("⛔ %s 未注入 ObservableModelGateway：校准桥不在调用路径上，"
                            + "G1 行为覆盖证据失效", rel)
                    .contains("ObservableModelGateway");
            assertThat(code)
                    .as("⛔ %s 未调用带上下文的 4 参 complete：观测链不会被触发", rel)
                    .contains(".complete(");
        }
    }

    @Test
    @DisplayName("✅ G1 覆盖边界：SSE/Console 链路不经过 ModelCallObserver（报告须诚实标注）")
    void sseAndConsoleBypassObserverChainByDesign() {
        // ⚠️ 这不是「缺陷守卫」而是「诚实边界守卫」：SSE/Console 走 ChatStreamGateway，
        //    不进 ObservableModelGateway。若将来有人给它们接上观测链，本用例会红，
        //    强制更新 G1 报告的行为覆盖口径，避免报告谎称「所有链路都过了校准桥」。
        List<String> chatChains = List.of(
                "agent-worker/src/main/java/cn/studykid/multiagent/worker/agentchat/AgentChatOrchestrator.java",
                "agent-api/src/main/java/cn/studykid/multiagent/api/chat/ConsoleChatController.java");
        for (String rel : chatChains) {
            Path file = platformRoot().resolve(rel);
            if (!Files.exists(file)) {
                continue;
            }
            String code = stripCommentsKeepStrings(read(file));
            assertThat(code)
                    .as("⛔ %s 接线了 ModelCallObserver：G1 报告的行为覆盖口径需随之升级", rel)
                    .doesNotContain("ModelCallObserver")
                    .doesNotContain("ObservableModelGateway");
        }
    }

    @Test
    @DisplayName("✅ 关闭等价 [D11]：enabled=false 时零压缩行为指标；影子期仅 4 个只读估算指标，且独立于总开关")
    void disabledCompressionIsBitIdenticalForRequestsAndMetricsScoped() {
        // [D11] 逐位等价的核心是「不改请求字节 / 不多发事件 / 不多扣 token」。
        // 影子校准指标（tokens.before / tokens.actual / estimation_error_ratio / bias）
        // 是 G1 完成标准 4「估算误差基线值记录并可回放」的必需观测，且由独立开关
        // agent.context.calibration.enabled 控制——不随压缩总开关 enabled 注册行为指标。
        // 本守卫钉死：S1 生产代码里的 context.* 指标名只能是这 4 个只读估算指标。
        List<String> allowedShadowMetrics = List.of(
                "context.tokens.before", "context.tokens.actual",
                "context.estimation_error_ratio", "context.bias");
        List<String> forbiddenBehaviorMetrics = List.of(
                "context.tokens.after", "context.compaction_total",
                "context.dropped_tokens_total", "context.checkpoint.",
                "context.budget_exceeded_total", "context.summarizer.",
                "context.prompt_cache.prefix_hash");

        List<String> found = new ArrayList<>();
        List<String> behaviorLeak = new ArrayList<>();
        try (java.util.stream.Stream<Path> tree = java.util.stream.Stream.concat(
                Files.walk(platformRoot().resolve("agent-context/src/main/java")),
                Files.walk(platformRoot().resolve("agent-infrastructure/src/main/java")))) {
            for (Path f : tree.filter(p -> p.toString().endsWith(".java")).toList()) {
                String code = stripCommentsKeepStrings(read(f));
                java.util.regex.Matcher m = Pattern.compile("\"(context\\.[a-z_.]+)\"").matcher(code);
                while (m.find()) {
                    String metric = m.group(1);
                    if (!allowedShadowMetrics.contains(metric)) {
                        found.add(f.getFileName() + " → " + metric);
                    }
                    for (String forbidden : forbiddenBehaviorMetrics) {
                        if (metric.startsWith(forbidden) || metric.equals(forbidden)) {
                            behaviorLeak.add(f.getFileName() + " → " + metric);
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("扫描 context.* 指标失败", e);
        }

        assertThat(found)
                .as("⛔ S1 影子期不允许出现压缩行为指标（compaction/checkpoint/budget/summarizer/after）："
                        + "enabled=false 必须与「无压缩功能」逐位一致 [D11]")
                .isEmpty();
        assertThat(behaviorLeak).isEmpty();

        // 校准桥必须由「独立开关」控制，且默认开（采集基线），绝不挂在压缩总开关下。
        String wiring = stripCommentsKeepStrings(read(platformRoot().resolve(
                "platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/config"
                        + "/ObserverConfiguration.java")));
        assertThat(wiring)
                .as("⛔ 校准桥若挂在压缩总开关 agent.context.enabled 下："
                        + "enabled=false 时收不到估算样本，G1 完成标准 4「基线可回放」失效")
                .contains("@ConditionalOnProperty(prefix = \"agent.context.calibration\"")
                .doesNotContain("prefix = \"agent.context.enabled\"");

        String yml = read(platformRoot().resolve(
                "platform-bootstrap/src/main/resources/application.yml"));
        assertThat(yml)
                .as("⛔ 影子期总开关必须默认关，校准开关必须默认开（独立）")
                .contains("enabled: \"${AGENT_CONTEXT_ENABLED:false}\"")
                .contains("enabled: \"${AGENT_CONTEXT_CALIBRATION_ENABLED:true}\"");
    }

    @Test
    @DisplayName("✅ ContextPolicy 只有唯一全参拷贝点，禁止新增旁路的兼容构造/投影")
    void contextPolicyHasSingleCopyPoint() {
        String src = stripCommentsKeepStrings(read(platformRoot().resolve(
                "agent-context/src/main/java/cn/studykid/multiagent/context/ContextPolicy.java")));

        int constructions = countOccurrences(src, "new ContextPolicy(");
        assertThat(constructions)
                .as("⛔ 全参构造必须是 defaults() / off() / copy() 三处；"
                        + "新增旁路会让「加字段漏带」绕过编译期强制")
                .isEqualTo(3);
        assertThat(src)
                .as("⛔ 唯一拷贝点 copy() 消失，withXxx 将各自散写全参构造")
                .contains("private @NonNull ContextPolicy copy(");
        assertThat(src)
                .as("⛔ 出现了兼容构造器（铁律 #11）")
                .doesNotContain("public ContextPolicy(");
    }

    static int countOccurrences(String text, String needle) {
        int n = 0;
        int i = 0;
        while ((i = text.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }

    /**
     * 剥离 Java 注释但<b>保留字符串字面量</b>（项目既有源码守卫纪律）。
     * 不剥离会把 Javadoc 里的说明文字当成实现，产生结构性假红。
     */
    static String stripCommentsKeepStrings(String src) {
        StringBuilder out = new StringBuilder();
        boolean inString = false;
        boolean inChar = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            char n = i + 1 < src.length() ? src.charAt(i + 1) : '\0';
            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false;
                    out.append(c);
                }
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && n == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }
            // ⚠️ 字符字面量（如 '"' / '\\' / '/'）必须先于字符串处理：
            //    否则 '"' 会被误判为字符串起点，吞掉后续注释，守卫拿到含注释的源码 → 假绿。
            if (inChar) {
                out.append(c);
                if (c == '\\' && i + 1 < src.length()) {
                    out.append(src.charAt(++i));
                } else if (c == '\'') {
                    inChar = false;
                }
                continue;
            }
            if (inString) {
                out.append(c);
                if (c == '\\' && i + 1 < src.length()) {
                    out.append(src.charAt(++i));
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '\'') {
                inChar = true;
                out.append(c);
            } else if (c == '"') {
                inString = true;
                out.append(c);
            } else if (c == '/' && n == '/') {
                inLineComment = true;
                i++;
            } else if (c == '/' && n == '*') {
                inBlockComment = true;
                i++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    // ── 辅助 ──────────────────────────────────────────────────────

    static Path platformRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("agent-core")) && Files.exists(dir.resolve("pom.xml"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("未定位到 platform 源码根目录");
    }

    static Path repoRoot() {
        return platformRoot().getParent();
    }

    static String read(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取失败: " + file, e);
        }
    }
}
