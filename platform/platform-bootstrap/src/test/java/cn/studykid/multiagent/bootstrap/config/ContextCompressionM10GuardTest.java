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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 上下文压缩 <b>S2-M10 RAG 命中配额</b>的跨模块源码守卫（ADR-013 D51 / 开发计划 W4）。
 *
 * <p>⚠️ 本类按 G1 A.5「升级不删除」纪律，从 M10-a 的「算法先行 / 零接线」阶段守卫
 * <b>升级</b>为 S3-M10 的「白名单内正面接入 + 白名单外禁止」守卫：
 * <ol>
 *   <li>{@code RagHitQuota} 仍是纯算法：零 Spring / 零 JDBC / 零 IO；</li>
 *   <li>生产源码中<b>恰好一个</b>算法层委托点（{@code RagHitQuotaAdapter}），
 *       worker / api 模型链路零算法层类型泄漏；</li>
 *   <li>{@code AgentChatOrchestrator} 的 legacy 固定口径仍原样保留（关闭态 / 未装配 /
 *       fail-soft 逐位等价），开启态只在白名单内正面接入中性 {@code RagHitQuotaPort}；</li>
 *   <li>三硬不变量、配额参数 yml 唯一真相、生产 TokenCounter 计数注入、反证脚本纪律在线。</li>
 * </ol>
 *
 * <p>⚠️ 每条断言都问过「撤掉修复点它会红吗」，配套 RV-M10-01..06 撤点验证
 * （精确计数替换 + 中断保护 + finally 复原 + md5 校验 + 编译通过 + skipped=0 判定）。
 * ⚠️ 本类<b>不引用被测实现自己的常量</b>：源码用语义锚点，避免「改常量 = 改测试」自证循环。
 *
 * <p>⛔ 所有「不得出现某模式」断言均用 {@code Pattern.find()} 取反，
 * 不用 AssertJ {@code doesNotMatch}（它是<b>全串不匹配</b>语义，长文件里恒真，是假绿源）。
 */
class ContextCompressionM10GuardTest {

    private static final String CTX = "agent-context/src/main/java/cn/studykid/multiagent/context/";
    private static final String QUOTA = CTX + "RagHitQuota.java";
    private static final String QUOTA_TEST = "agent-context/src/test/java/cn/studykid/multiagent/context/RagHitQuotaTest.java";
    private static final String PORT =
            "agent-ports/src/main/java/cn/studykid/multiagent/ports/agentchat/RagHitQuotaPort.java";
    private static final String ADAPTER =
            "platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/config/RagHitQuotaAdapter.java";
    private static final String CONFIG =
            "platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/config/RagHitQuotaConfiguration.java";
    private static final String PROPERTIES =
            "platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/config/AgentContextProperties.java";
    private static final String YAML =
            "platform-bootstrap/src/main/resources/application.yml";
    private static final String ENV = "platform.env.example";
    private static final String COUNTERPROOF = "scripts/m10-rag-quota-counterproof.sh";
    private static final String ORCHESTRATOR =
            "agent-worker/src/main/java/cn/studykid/multiagent/worker/agentchat/AgentChatOrchestrator.java";

    private static final Set<String> FORBIDDEN_IMPORT_PREFIXES = Set.of(
            "org.springframework.", "java.sql.", "javax.sql.",
            "com.zaxxer.", "org.flywaydb.", "org.postgresql.");

    private static final Pattern IMPORT = Pattern.compile(
            "^import\\s+(?:static\\s+)?([\\w.]+);", Pattern.MULTILINE);

    /** 模型链路层允许持有中性配额端口的文件（G1 A.5 白名单，精确到文件名）。 */
    private static final Set<String> QUOTA_WIRING_ALLOWLIST = Set.of("AgentChatOrchestrator.java");

    /** 模型链路层禁止出现的算法层令牌（中性端口 / DTO 不在此列）。 */
    private static final List<String> ALGORITHM_LEAK_TOKENS = List.of(
            "cn.studykid.multiagent.context.",
            "RagHitQuota.allocate(", "RagHitQuota.Candidate", "RagHitQuota.Options",
            "RagHitQuota.TextTokens", "RagHitQuota.RefTokens");

    private static String src(String rel) {
        return ContextCompressionS1GuardTest.read(platformRoot().resolve(rel));
    }

    private static String raw(String rel) {
        return ContextCompressionS1GuardTest.read(repoRoot().resolve(rel));
    }

    private static String stripped(String rel) {
        return ContextCompressionS1GuardTest.stripCommentsKeepStrings(src(rel));
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

    /** 只扫生产源码（src/main/java），测试里的同类型名不算「生产接线」。 */
    private static int countPlatformMain(String needle) {
        int total = 0;
        try (Stream<Path> tree = Files.walk(platformRoot())) {
            for (Path file : tree.filter(p -> p.toString().endsWith(".java")
                    && p.toString().contains("/src/main/java/")).toList()) {
                total += count(stripped(platformRoot().relativize(file).toString().replace('\\', '/')),
                        needle);
            }
        } catch (IOException e) {
            throw new IllegalStateException("扫描 platform 生产源码失败", e);
        }
        return total;
    }

    /** 锚点出现即失败（Pattern.find 取反，规避 doesNotMatch 全串假绿）。 */
    private static void assertNoPattern(String text, String regex, String reason) {
        assertThat(Pattern.compile(regex, Pattern.DOTALL).matcher(text).find())
                .as(reason + "（Pattern.find 取反，非全串匹配）")
                .isFalse();
    }

    @Test
    @DisplayName("✅ RV-101：RagHitQuota 是纯算法层，零 Spring / JDBC / IO import")
    void quotaKernelIsFrameworkFree() {
        String code = src(QUOTA);
        List<String> violations = new ArrayList<>();
        Matcher matcher = IMPORT.matcher(code);
        while (matcher.find()) {
            String imported = matcher.group(1);
            if (FORBIDDEN_IMPORT_PREFIXES.stream().anyMatch(imported::startsWith)) {
                violations.add(imported);
            }
        }
        assertThat(violations)
                .as("⛔ 纯算法层被框架/持久层污染：C1 / ADR-011 要求零 Spring/JDBC/IO")
                .isEmpty();
        for (String api : List.of("System.currentTimeMillis", "Instant.now", "System.getenv",
                "System.getProperty", "new Socket", "java.net.", "Files.")) {
            assertThat(code).as("⛔ 纯算法层出现非确定性/IO 调用：%s", api).doesNotContain(api);
        }
    }

    @Test
    @DisplayName("✅ RV-102（G1 A.5 升级）：配额内核生产源码恰好一个委托点；worker/api 零算法层泄漏")
    void quotaKernelHasExactlyOneProductionDelegate() {
        // 旧「零生产接线」升级为「唯一委托」：仍在白名单之外引用算法层即红。
        String adapter = stripped(ADAPTER);
        assertThat(adapter)
                .as("⛔ 唯一组合根委托点消失：配额算法变成空转代码")
                .contains("RagHitQuota.allocate(");
        assertThat(countPlatformMain("RagHitQuota.allocate("))
                .as("⛔ 生产源码出现第二个配额算法调用点：两套口径必然漂移")
                .isEqualTo(1);
        assertThat(countPlatformMain("import cn.studykid.multiagent.context.RagHitQuota;"))
                .as("⛔ 算法层顶层类型被调用方直接 import：ADR-011 被破坏")
                .isEqualTo(1);
        assertThat(countPlatformMain("RagHitQuotaPort ragHitQuotaPort("))
                .as("⛔ 生产源码出现第二个中性配额端口装配入口")
                .isEqualTo(1);

        // 剥注释再扫：Javadoc 提到类名不算接线，只有真实代码引用才算。
        // ⚠️ 只针对 RagHitQuota 这一类算法类型：M7/M8 组合根合法使用 agent-context 其它内核，
        //    不能把「算法层包前缀」一锅端扫描，否则守卫会假红（不是本批接线面）。
        List<String> violations = new ArrayList<>();
        try (Stream<Path> tree = Files.walk(platformRoot())) {
            for (Path file : tree.filter(p -> p.toString().endsWith(".java")
                    && p.toString().contains("/src/main/java/")).toList()) {
                String rel = platformRoot().relativize(file).toString().replace('\\', '/');
                if (rel.equals(QUOTA) || rel.equals(ADAPTER)) {
                    continue;
                }
                if (stripped(rel).contains("RagHitQuota.")) {
                    violations.add(rel + " → RagHitQuota.*");
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("扫描 platform 生产源码失败", e);
        }
        assertThat(violations)
                .as("⛔ RAG 配额算法类型 / 算法调用泄漏出唯一组合根适配器（ADR-011）")
                .isEmpty();
    }

    @Test
    @DisplayName("✅ RV-103（G1 A.5 升级）：L2 白名单内正面接入配额端口；legacy 固定口径保留；白名单外禁止")
    void orchestratorUsesQuotaPortInAllowListWithLegacyFallback() {
        String code = stripped(ORCHESTRATOR);

        // ① 旧负面覆盖升级为「legacy 固定口径只在回退路径保留」：删除任何一段都会破坏关闭态逐位等价。
        assertThat(code)
                .as("⛔ legacy 固定注入条数被删除：关闭态 / fail-soft 回退字节漂移")
                .contains("private static final int MAX_INJECT_HITS = 8;")
                .as("⛔ legacy 单块文本上限被删除：关闭态 / fail-soft 回退字节漂移")
                .contains("private static final int MAX_HIT_TEXT = 1200;")
                .as("⛔ legacy serializeHits 固定截断被替换：G1 逐位等价失去基线")
                .contains("hits.subList(0, Math.min(hits.size(), MAX_INJECT_HITS))")
                .contains("truncate(hit.text(), MAX_HIT_TEXT)");

        // ② 正面接入：worker 只碰中性端口 / DTO；围栏仍是唯一不可信出口。
        assertThat(code)
                .as("⛔ L2 没接 RAG 配额端口：开启态仍走固定条数，配额空转")
                .contains("RagHitQuotaPort")
                .contains("ragHitQuotaPort.applyQuota(")
                .contains("new RagHitQuotaPort.Input(")
                .as("⛔ 送模型载荷不是端口渲染结果：计量算 A、实际发 B")
                .contains("RagHitQuotaPort.payloadJson(allocation)")
                .as("⛔ 配额载荷绕过 PromptGuard 不可信围栏")
                .contains("RAG_GUARD.wrapToolResult(\"rag\", payload)")
                .as("⛔ 配额异常未 fail-soft：组件级增强会中断回答")
                .contains("catch (RuntimeException e)")
                .as("⛔ 全量丢弃时仍注入空围栏 / injected 语义未同步")
                .contains("if (!injected)")
                .as("⛔ 配额标量审计事实缺失：无法验证真省了多少 token")
                .contains("RAG_QUOTA");

        for (String token : ALGORITHM_LEAK_TOKENS) {
            assertNoPattern(code, Pattern.quote(token),
                    "⛔ AgentChatOrchestrator 出现算法层令牌 " + token + "：ADR-011 禁止 worker 直连算法层");
        }
        assertThat(modelChainAlgorithmLeaks())
                .as("⛔ worker / api 模型链路出现算法层包前缀或配额算法调用：ADR-011 零泄漏被破坏")
                .isEmpty();

        // ③ 白名单外禁止：worker / api 模型链路里持有中性配额令牌的文件集合必须恰好等于登记白名单。
        Map<String, Path> found = quotaWiringFiles();
        assertThat(found.keySet())
                .as("⛔ RAG 配额模型链路接线文件集合与 A.5 登记白名单不一致："
                        + "多出=越界接线，少=白名单文件被改名/删除后守卫失效")
                .containsExactlyInAnyOrderElementsOf(QUOTA_WIRING_ALLOWLIST);
        for (Map.Entry<String, Path> e : found.entrySet()) {
            assertThat(stripped(platformRoot().relativize(e.getValue()).toString().replace('\\', '/')))
                    .as("⛔ 白名单文件 %s 的必需配额令牌消失（守卫随实现退化而假绿）", e.getKey())
                    .contains("RagHitQuotaPort");
        }
    }

    @Test
    @DisplayName("✅ RV-104：after 硬顶校验 + 引用也扣剩余预算（撤掉即撑爆）")
    void hardBudgetEnforced() {
        String code = stripped(QUOTA);

        assertThat(code)
                .as("⛔ 缺少 after > tokenBudget 硬顶校验：引用/截断叠加会静默超窗")
                .contains("if (after > options.tokenBudget()) {")
                .as("⛔ 硬顶异常非 fail-closed 语义")
                .contains("throw new IllegalStateException(\"RagHitQuota 硬顶被突破");

        assertThat(count(code, "remaining -= full;"))
                .as("⛔ 全文路径未扣减 remaining")
                .isEqualTo(1);
        assertThat(count(code, "remaining -= cutTokens;"))
                .as("⛔ 截断路径未扣减 remaining")
                .isEqualTo(1);
        assertThat(count(code, "remaining -= refCost;"))
                .as("⛔ 引用路径未扣减 remaining：引用白占预算会撑爆上下文")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("✅ RV-105：引用比原文贵则丢弃 + 确定性全序 tie-break（撤掉即膨胀/漂移）")
    void refCannotExceedFullAndOrderIsTotal() {
        String code = stripped(QUOTA);

        assertThat(code)
                .as("⛔ 引用降级缺少「严格比原文便宜」闸门：短正文+长 locator 会静默膨胀")
                .contains("if (refCost < full && remaining >= refCost) {")
                .as("⛔ 引用不划算时未计入 droppedCount：净省统计会失真")
                .contains("dropped++;");

        assertThat(code)
                .as("⛔ 全序缺少 chunkId 末级 tie-break")
                .contains(".thenComparing(Candidate::chunkId)")
                .as("⛔ 全序缺少 text 末级 tie-break：去重保留项随输入顺序漂移")
                .contains(".thenComparing(Candidate::text)");
    }

    @Test
    @DisplayName("✅ RV-106：before 全量口径 + overhead 计入 + fail-closed（撤掉即净省失真）")
    void beforeCountsRawAndFailClosed() {
        String code = stripped(QUOTA);

        assertThat(code)
                .as("⛔ beforeTokens 未按原始全量统计：去重/降级的净省被掩盖")
                .contains("before += fullTokens(h, textTokens);")
                .as("⛔ fullTokens 未计入 overhead：元数据 token 被漏算，预算低估")
                .contains("return h.overheadTokens() + textTokens.count(h.text());");
        assertThat(count(code, "before += fullTokens(h, textTokens);"))
                .as("⛔ beforeTokens 累加点不唯一")
                .isEqualTo(1);

        assertThat(code)
                .as("⛔ chunkId 空未 fail-closed")
                .contains("Candidate.chunkId 不得为空")
                .as("⛔ overhead 负数未 fail-closed")
                .contains("Candidate.overheadTokens 不得为负")
                .as("⛔ NaN/Inf score 未 fail-closed")
                .contains("!Double.isFinite(score)")
                .as("⛔ allocate 参数 null 未 fail-closed")
                .contains("RagHitQuota.allocate 参数不得为 null")
                .as("⛔ null 元素未 fail-closed")
                .contains("hits 不得含 null 元素");

        assertThat(count(code, "throw new IllegalArgumentException"))
                .as("⛔ fail-closed 校验点不足：Options / Candidate / 参数校验被削弱")
                .isGreaterThanOrEqualTo(9);
    }

    @Test
    @DisplayName("✅ RV-107：行为测试覆盖 10 组撤点锚点 + ADR-013 D37 门禁登记")
    void behaviorTestsAndAdrRegistered() {
        String test = src(QUOTA_TEST);
        for (String rv : List.of("RV-91", "RV-92", "RV-93", "RV-94", "RV-95",
                "RV-96", "RV-97", "RV-98", "RV-99", "RV-100")) {
            assertThat(test).as("⛔ 算法行为测试缺少撤点锚点 %s", rv).contains(rv);
        }

        String adr = ContextCompressionS1GuardTest.read(
                repoRoot().resolve("docs/P0/P1/ADR-013_上下文压缩统一入口与全窗口预算.md"));
        assertThat(adr)
                .as("⛔ ADR-013 未登记 D37：M10 算法先行的决策无架构留痕")
                .contains("D37")
                .as("⛔ ADR 未写明 M10 算法先行 / 接线等签核边界")
                .contains("M10")
                .as("⛔ ADR 未登记 D51：G2 放行后的薄适配接线无架构留痕")
                .contains("D51");
    }

    @Test
    @DisplayName("✅ RV-108：中性端口 / 组合根适配器 / 唯一配置保持中性、fail-soft、yml 唯一真相")
    void portAdapterAndRootRemainNeutralFailSoftAndConfigDriven() {
        String port = stripped(PORT);
        String adapter = stripped(ADAPTER);
        String config = stripped(CONFIG);
        String props = stripped(PROPERTIES);
        String yml = src(YAML);
        String env = raw(ENV);

        // ① 中性端口：零框架 / JDBC / 算法层反向依赖；渲染口径与组合根计数同源。
        assertThat(port)
                .as("⛔ 配额端口契约不是「返回 nullable」：异常无法静默降级 legacy")
                .contains("@Nullable Allocation applyQuota(")
                .as("⛔ 中性 DTO 全参构造丢失：compat 构造器会静默清空后加字段（铁律 #10）")
                .contains("record Input(")
                .contains("record Hit(")
                .contains("record Allocation(")
                .as("⛔ 冻结渲染口径缺失：计量算 A、实际发 B 会静默超窗")
                .contains("static @NonNull String payloadJson(")
                .contains("static @NonNull String hitJson(")
                .contains("static @NonNull String refJson(")
                .contains("String RAG_KIND")
                .contains("String RAG_NOTE");
        assertNoPattern(port, "org\\.springframework|java\\.sql\\.|javax\\.sql\\.|"
                        + "cn\\.studykid\\.multiagent\\.context\\.",
                "⛔ 中性配额端口出现框架 / JDBC / 算法层反向依赖（ADR-011）");

        // ② 组合根 adapter：只做接线，五动作全部委托纯算法；计数来自生产 TokenCounter。
        assertThat(adapter)
                .as("⛔ adapter 没委托 RagHitQuota.allocate：组合根自造第二套配额算法")
                .contains("RagHitQuota.allocate(")
                .as("⛔ 关闭态没有零副作用短路：影子期会发生 token 计数 / 请求字节漂移")
                .contains("if (!global.enabled())")
                .as("⛔ 配额异常未 fail-soft：可选增强会中断 RAG / 回答 / 审计主流程")
                .contains("catch (RuntimeException e)")
                .as("⛔ 未注入生产 TokenCounter：计数口径与全窗口预算分叉")
                .contains("countSegment(")
                .as("⛔ overhead / ref 计数与 worker 渲染不同源：算 A、发 B")
                .contains("RagHitQuotaPort.hitJson(")
                .contains("RagHitQuotaPort.refJson(");
        assertNoPattern(adapter, "java\\.sql\\.|javax\\.sql\\.|outbox_event|agent_message_queue|"
                        + "RabbitTemplate|ObservableModelGateway|ModelCallObserver|ObjectMapper",
                "⛔ 配额适配器落库 / 发 MQ / 接观测链 / 自造第二套序列化");

        // ③ 唯一配置片：四项参数来自 yml / env，启动期 fail-fast；不引入第二套默认值。
        assertThat(config)
                .as("⛔ 配额端口不是唯一可覆盖 Bean：多实例会用两套预算口径")
                .contains("@ConditionalOnMissingBean(RagHitQuotaPort.class)")
                .contains("RagHitQuotaPort ragHitQuotaPort(")
                .as("⛔ 配额参数未来自 AgentContextProperties：改 env 永远无效")
                .contains("properties.getRagQuota()")
                .contains("getTokenBudget()")
                .contains("getMaxHits()")
                .contains("getMaxHitTokens()")
                .contains("getMinKeptTokens()")
                .as("⛔ 未共用全局开关 / 生产 TokenCounter：两套 enabled 与计量口径")
                .contains("GlobalContextPolicy globalContextPolicy")
                .contains("TokenCounter contextTokenCounter")
                .as("⛔ 非法配置被静默夹取")
                .contains("throw new IllegalArgumentException");
        assertNoPattern(config, Pattern.quote("RagHitQuota."),
                "⛔ 配置片直接持有算法层类型：算法层接线面不再是唯一 adapter");
        assertThat(countPlatformMain("RagHitQuotaPort ragHitQuotaPort("))
                .isEqualTo(1);

        assertThat(props)
                .as("⛔ RagQuota 配置形状缺失：yml 键无法绑定")
                .contains("class RagQuota")
                .contains("private int tokenBudget = 4_096;")
                .contains("private int maxHits = 8;")
                .contains("private int maxHitTokens = 1_200;")
                .contains("private int minKeptTokens = 64;");
        assertThat(yml)
                .as("⛔ 四项配额键缺失 / 未走 env：运维改值落不到生产")
                .contains("rag-quota:")
                .contains("AGENT_CONTEXT_RAG_QUOTA_TOKEN_BUDGET")
                .contains("AGENT_CONTEXT_RAG_QUOTA_MAX_HITS")
                .contains("AGENT_CONTEXT_RAG_QUOTA_MAX_HIT_TOKENS")
                .contains("AGENT_CONTEXT_RAG_QUOTA_MIN_KEPT_TOKENS");
        assertThat(env)
                .as("⛔ env 模板缺配额变量：灰度/运维无入口")
                .contains("AGENT_CONTEXT_RAG_QUOTA_TOKEN_BUDGET")
                .contains("AGENT_CONTEXT_RAG_QUOTA_MAX_HITS")
                .contains("AGENT_CONTEXT_RAG_QUOTA_MAX_HIT_TOKENS")
                .contains("AGENT_CONTEXT_RAG_QUOTA_MIN_KEPT_TOKENS");
    }

    @Test
    @DisplayName("✅ RV-109：M10 反证脚本带中断保护、精确锚点、红/绿判定与 md5 复原")
    void counterproofScriptKeepsDiscipline() {
        String script = raw(COUNTERPROOF);

        assertThat(script)
                .as("⛔ 撤点标识缺失：无法证明「撤掉修复点会红」")
                .contains("RV-M10-01")
                .contains("RV-M10-02")
                .contains("RV-M10-03")
                .contains("RV-M10-04")
                .contains("RV-M10-05")
                .contains("RV-M10-06")
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

    // ── 辅助 ──────────────────────────────────────────────────────

    /** worker / api 模型链路层禁止出现算法层包前缀或配额算法调用（组合根 adapter 不在扫描面）。 */
    private static List<String> modelChainAlgorithmLeaks() {
        List<String> leaks = new ArrayList<>();
        for (String module : List.of("agent-worker", "agent-api")) {
            Path root = platformRoot().resolve(module + "/src/main/java");
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> tree = Files.walk(root)) {
                for (Path file : tree.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String rel = platformRoot().relativize(file).toString().replace('\\', '/');
                    String code = stripped(rel);
                    for (String token : ALGORITHM_LEAK_TOKENS) {
                        if (code.contains(token)) {
                            leaks.add(rel + " → " + token);
                        }
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("扫描模型链路算法层泄漏失败", e);
            }
        }
        return leaks;
    }

    /** 扫描 worker / api 模型链路层生产源码，返回「文件名 → 路径」中含中性配额令牌的文件。 */
    private static Map<String, Path> quotaWiringFiles() {
        Map<String, Path> found = new LinkedHashMap<>();
        for (String module : List.of("agent-worker", "agent-api")) {
            Path src = platformRoot().resolve(module + "/src/main/java");
            if (!Files.isDirectory(src)) {
                continue;
            }
            try (Stream<Path> tree = Files.walk(src)) {
                for (Path file : tree.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String code = stripped(platformRoot().relativize(file).toString().replace('\\', '/'));
                    boolean wired = List.of("RagHitQuotaPort", "RagHitQuotaPort.Input", "Allocation")
                            .stream().anyMatch(code::contains);
                    if (wired) {
                        found.put(file.getFileName().toString(), file);
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("扫描模型链路 RAG 配额接线失败", e);
            }
        }
        return found;
    }
}
