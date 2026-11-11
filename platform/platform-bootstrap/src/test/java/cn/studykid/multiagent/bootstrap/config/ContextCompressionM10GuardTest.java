package cn.studykid.multiagent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 上下文压缩 <b>S2-M10 RAG 命中配额内核</b>的跨模块源码守卫（ADR-013 D37 / 开发计划 W4）。
 *
 * <p>⚠️ 本类是<b>阶段守卫</b>，钉死 M10-a 的「算法先行、接线等签核」边界：
 * <ol>
 *   <li>{@code RagHitQuota} 是纯算法：零 Spring / 零 JDBC / 零 IO，且<b>不被任何生产模块引用</b>；</li>
 *   <li>{@code AgentChatOrchestrator} 的 RAG 注入请求字节原样保留（{@code MAX_INJECT_HITS} /
 *       {@code MAX_HIT_TEXT} / {@code serializeHits} 未被配额内核替换）——G1 签核前不得接线；</li>
 *   <li>三条硬不变量在源码层存在：after 硬顶校验、引用也扣剩余预算、
 *       引用比原文贵则丢弃、全序末级 tie-break、before 按含重复全量统计、overhead 计入。</li>
 * </ol>
 *
 * <p>⚠️ 每条断言都问过「撤掉修复点它会红吗」，配套 RV-201..RV-210 撤点验证
 * （精确计数替换 + finally 复原 + md5 校验 + 编译通过 + skipped=0 判定）。
 * ⚠️ 本类<b>不引用被测实现自己的常量</b>：
 * 源码用语义锚点，避免「改常量 = 改测试」的自证循环（铁律 #6）。
 *
 * <p>⛔ 所有「不得出现某模式」断言均用 {@code Pattern.find()} 取反，
 * 不用 AssertJ {@code doesNotMatch}（它是<b>全串不匹配</b>语义，长文件里恒真，是假绿源）。
 *
 * <p>⛔ M7 / M10 正式接线、或 G1 签核放行时，必须按评审记录<b>显式升级或退役</b>
 * 「零接线 / 请求字节冻结」断言，不得直接删守卫了事。
 */
class ContextCompressionM10GuardTest {

    private static final String CTX = "agent-context/src/main/java/cn/studykid/multiagent/context/";
    private static final String QUOTA = CTX + "RagHitQuota.java";
    private static final String QUOTA_TEST = "agent-context/src/test/java/cn/studykid/multiagent/context/RagHitQuotaTest.java";
    private static final String ORCHESTRATOR =
            "agent-worker/src/main/java/cn/studykid/multiagent/worker/agentchat/AgentChatOrchestrator.java";

    private static final Set<String> FORBIDDEN_IMPORT_PREFIXES = Set.of(
            "org.springframework.", "java.sql.", "javax.sql.",
            "com.zaxxer.", "org.flywaydb.", "org.postgresql.");

    private static final Pattern IMPORT = Pattern.compile(
            "^import\\s+(?:static\\s+)?([\\w.]+);", Pattern.MULTILINE);

    private static String src(String rel) {
        return ContextCompressionS1GuardTest.read(platformRoot().resolve(rel));
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
        // 不读时钟、不读环境、不发网络：这些 API 一旦出现即非确定性纯函数。
        for (String api : List.of("System.currentTimeMillis", "Instant.now", "System.getenv",
                "System.getProperty", "new Socket", "java.net.", "Files.")) {
            assertThat(code).as("⛔ 纯算法层出现非确定性/IO 调用：%s", api).doesNotContain(api);
        }
    }

    @Test
    @DisplayName("✅ RV-102：RagHitQuota 不被任何生产模块引用（零接线，G1 签核前）")
    void quotaKernelHasNoProductionCaller() {
        Path platform = platformRoot();
        try (Stream<Path> tree = Files.walk(platform)) {
            for (Path file : tree.filter(p -> p.toString().endsWith(".java")).toList()) {
                String rel = platform.relativize(file).toString();
                // 算法层自身、它的测试、本守卫文件允许出现类名。
                if (rel.equals(QUOTA) || rel.equals(QUOTA_TEST)
                        || rel.endsWith("ContextCompressionM10GuardTest.java")) {
                    continue;
                }
                // 剥注释再扫：Javadoc 提到类名不算接线，只有真实代码引用才算。
                String code = ContextCompressionS1GuardTest.stripCommentsKeepStrings(src(rel));
                assertNoPattern(code, "RagHitQuota",
                        "⛔ 纯算法层被生产/其它模块引用，M10 在 G1 签核前已接线: " + rel);
            }
        } catch (IOException e) {
            throw new IllegalStateException("扫描 platform 源码失败", e);
        }
    }

    @Test
    @DisplayName("✅ RV-103：AgentChatOrchestrator RAG 注入请求字节原样（MAX_* / serializeHits 未接线）")
    void orchestratorRequestBytesFrozen() {
        String code = stripped(ORCHESTRATOR);

        // 现有请求字节常量与序列化逻辑必须仍在；M10 接线前不得替换成配额内核。
        assertThat(code)
                .as("⛔ 固定注入条数上限被删除/改写：G1 影子期请求字节已变化")
                .contains("private static final int MAX_INJECT_HITS = 8;")
                .as("⛔ 单块文本上限被删除/改写：G1 影子期请求字节已变化")
                .contains("private static final int MAX_HIT_TEXT = 1200;")
                .as("⛔ serializeHits 的固定截断逻辑被替换：RAG 注入字节已变化")
                .contains("hits.subList(0, Math.min(hits.size(), MAX_INJECT_HITS))")
                .contains("truncate(hit.text(), MAX_HIT_TEXT)");

        // 不得在编排器里出现配额内核的接线痕迹。
        for (String token : List.of("RagHitQuota", "allocate(", "KeptHit", "RefHit")) {
            assertNoPattern(code, Pattern.quote(token),
                    "⛔ AgentChatOrchestrator 出现 M10 接线痕迹 " + token + "：G1 签核前禁止改请求字节");
        }
    }

    @Test
    @DisplayName("✅ RV-104：after 硬顶校验 + 引用也扣剩余预算（撤掉即撑爆）")
    void hardBudgetEnforced() {
        String code = stripped(QUOTA);

        // 硬顶：分配结束按累计 token 校验，超预算直接抛异常（fail-closed）。
        assertThat(code)
                .as("⛔ 缺少 after > tokenBudget 硬顶校验：引用/截断叠加会静默超窗")
                .contains("if (after > options.tokenBudget()) {")
                .as("⛔ 硬顶异常非 fail-closed 语义")
                .contains("throw new IllegalStateException(\"RagHitQuota 硬顶被突破");

        // 全文 / 截断 / 引用三条路径都必须扣 remaining，否则后续块会重复用同一预算。
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

        // 引用严格更便宜才保留，否则丢弃——杜绝「压缩后反而更长」。
        assertThat(code)
                .as("⛔ 引用降级缺少「严格比原文便宜」闸门：短正文+长 locator 会静默膨胀")
                .contains("if (refCost < full && remaining >= refCost) {")
                .as("⛔ 引用不划算时未计入 droppedCount：净省统计会失真")
                .contains("dropped++;");

        // 全序比较必须落到 chunkId / text / overhead，否则同分同 locator 时依赖输入顺序。
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

        // beforeTokens 必须遍历原始 hits（含重复），否则去重不体现为净省。
        assertThat(code)
                .as("⛔ beforeTokens 未按原始全量统计：去重/降级的净省被掩盖")
                .contains("before += fullTokens(h, textTokens);")
                .as("⛔ fullTokens 未计入 overhead：元数据 token 被漏算，预算低估")
                .contains("return h.overheadTokens() + textTokens.count(h.text());");
        assertThat(count(code, "before += fullTokens(h, textTokens);"))
                .as("⛔ beforeTokens 累加点不唯一")
                .isEqualTo(1);

        // 身份 / 数值 / 参数 fail-closed 锚点。
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

        // Options 四项范围闸门。
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
            assertThat(test).as("⛔ 行为测试缺少撤点锚点 %s", rv).contains(rv);
        }

        // ADR 在仓库根 docs 下，用 repoRoot 读。
        String adr = ContextCompressionS1GuardTest.read(
                repoRoot().resolve("docs/P0/P1/ADR-013_上下文压缩统一入口与全窗口预算.md"));
        assertThat(adr)
                .as("⛔ ADR-013 未登记 D37：M10 算法先行的决策无架构留痕")
                .contains("D37")
                .as("⛔ ADR 未写明 M10 算法先行 / 接线等签核边界")
                .contains("M10");
    }
}
