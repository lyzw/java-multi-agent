package cn.studykid.multiagent.bootstrap.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上下文压缩 <b>S2-M5-c 增量状态合并内核</b>的结构守卫（ADR-013 / 开发计划 W4）。
 *
 * <p>⚠️ 与 S1 / M5-b 守卫的分工：本类钉死 M5-c 的 9 个修复点，每条都问过
 * 「撤掉修复点它会红吗」，并配套 RV-28..34 撤点脚本（精确计数替换 + finally 复原 + md5 校验）。
 *
 * <p>⚠️ 本类<b>不引用被测实现自己的常量</b>：一律用字符串锚点 + 行为语义断言，
 * 避免「改常量 = 改测试」的自证循环（项目铁律 #6）。
 */
class ContextCompressionM5cGuardTest {

    private static final String CTX = "agent-context/src/main/java/cn/studykid/multiagent/context/";

    private static String src(String name) {
        return ContextCompressionS1GuardTest.stripCommentsKeepStrings(
                ContextCompressionS1GuardTest.read(
                        ContextCompressionS1GuardTest.platformRoot().resolve(CTX + name + ".java")));
    }

    private static int count(String text, String needle) {
        return ContextCompressionS1GuardTest.countOccurrences(text, needle);
    }

    @Test
    @DisplayName("✅ RV-28/RV-37：只吃 seq>upToSeq 增量，无增量早返回在抽取器调用之前（NoRecursiveResummary）")
    void deltaCursorIsStrictAndExtractionIsSingleShot() {
        String code = src("RollingStateMerger");

        assertThat(code)
                .as("⛔ 缺少严格 seq 闸门：把旧轮次重喂抽取器 = 递归重摘要 + 重复计费")
                .contains("if (sm.seq() > oldState.upToSeq()) {")
                .as("⛔ 缺少空增量早返回：没有新轮次也会调用模型（零成本保证失效）")
                .contains("if (newMessages.isEmpty()) {");
        assertThat(code.indexOf("if (newMessages.isEmpty()) {"))
                .as("⛔ 早返回出现在抽取器调用之后：仍会白调一次模型")
                .isLessThan(code.indexOf("extractor.extract("));
        assertThat(count(code, "extractor.extract("))
                .as("⛔ 抽取器调用点不唯一：存在旁路可绕过增量闸门")
                .isEqualTo(1);
        assertThat(code)
                .as("⛔ 游标未取 max 单调前移")
                .contains("Math.max(oldState.upToSeq(), maxSeq)");
    }

    @Test
    @DisplayName("✅ RV-33：关键 span 逐字校验不可绕过，改写/跨消息拼接一律整批回落")
    void verbatimSpansAreMandatory() {
        String code = src("RollingStateMerger");

        assertThat(code)
                .as("⛔ 合并前未做逐字校验：抽取器改写的 UUID / 精确值会写进 checkpoint")
                .contains("violations = verifyVerbatim(fragment, newMessages);")
                .contains("if (!violations.isEmpty()) {")
                .contains("FALLBACK_VERBATIM_VIOLATION");
        assertThat(code)
                .as("⛔ 逐字校验范围不全：约束 / 用户纠正 / 精确事实必须逐字出现在增量原文")
                .contains("spans.addAll(fragment.constraints());")
                .contains("spans.addAll(fragment.userCorrections());")
                .contains("spans.add(f.value());")
                .contains("if (m.content().contains(span)) {");
    }

    @Test
    @DisplayName("✅ RV-30：抽取 empty / 异常 / null 一律回落旧状态，绝不伪造 checkpoint")
    void extractorFailureNeverFabricatesState() {
        String code = src("RollingStateMerger");

        assertThat(code)
                .as("⛔ 抽取器抛异常未被捕获：一次模型超时会打断整条编排链")
                .contains("catch (RuntimeException e) {")
                .as("⛔ Optional.empty / null 未被当作失败：会伪造空状态并推进游标")
                .contains("if (extracted == null || extracted.isEmpty()) {")
                .contains("FALLBACK_EXTRACTOR_FAILED");
        assertThat(count(code, "return new MergeResult(oldState, 0, skipped, false, true,"))
                .as("⛔ 失败分支没有原样回落旧状态引用（state 被改写 / 游标被推进）")
                .isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("✅ RV-34：策略版本不符丢弃 checkpoint，不检视增量、不复用旧策略摘要")
    void policyVersionMismatchDiscardsCheckpoint() {
        String code = src("RollingStateMerger");

        assertThat(code)
                .as("⛔ 未比对策略指纹：换策略后旧摘要被当成新策略真值续写")
                .contains("!oldState.policyVersion().equals(policyVersion)")
                .contains("FALLBACK_POLICY_VERSION_MISMATCH, true")
                .as("⛔ 版本不符时仍报告 skipped=delta.size()：掩盖「根本没检视增量」的事实")
                .contains("return new MergeResult(oldState, 0, 0, false, true,");
        assertThat(code)
                .as("⛔ 片段的 policyVersion 被写进合并结果：策略漂移会被固化")
                .doesNotContain("policyVersion = ");
    }

    @Test
    @DisplayName("✅ RV-32：fromSeq 取 min 单调不后退，摘要链不丢头")
    void fromSeqNeverRegresses() {
        String code = src("RollingStateMerger");
        assertThat(code)
                .as("⛔ fromSeq 被直接改成新增量起点：旧摘要覆盖范围被悄悄抹掉")
                .contains("Math.min(oldState.fromSeq(), firstSeq(delta, oldState.upToSeq()))");
    }

    @Test
    @DisplayName("✅ RV-29/RV-35/RV-36：规范 JSON 拒重复键 / 缺字段 / 多字段 / 非规范整数，facts 按 key 排序")
    void canonicalJsonRejectsDuplicateFieldsAndNonCanonicalIntegers() {
        String code = src("StructuredStateCodec");

        assertThat(code)
                .as("⛔ 重复字段被 LinkedHashMap 静默覆盖：同一 checkpoint 可有两种真值")
                .contains("if (map.containsKey(key)) {")
                .as("⛔ 缺字段 / 多字段未拒：脏状态被宽松解析后续写")
                .contains("if (obj.size() != keys.length) {")
                .contains("if (!obj.containsKey(k)) {")
                .as("⛔ facts 未按 key 排序：同状态产生不同 digest，幂等键失效")
                .contains("sortedFacts.sort((x, y) -> x.key().compareTo(y.key()));")
                .as("⛔ 非规范整数（空数字 / 前导零 / -0）被接受：文本表示不唯一")
                .contains("if (digits == 0) {")
                .contains("if (digits > 1 && s.charAt(digitsStart) == '0') {")
                .contains("if (negative && digits == 1 && s.charAt(digitsStart) == '0') {");
        assertThat(code)
                .as("⛔ 纯算法层引入 JSON 库：迭代顺序 / 浮点格式会破坏确定性")
                .doesNotContain("com.fasterxml")
                .doesNotContain("com.google.gson")
                .doesNotContain("ObjectMapper");
    }

    @Test
    @DisplayName("✅ RV-31：checkpoint 游标结构自洽才解析，digest 取规范 JSON 的 SHA-256")
    void cursorInvariantsAreValidated() {
        String code = src("StructuredStateCodec");
        assertThat(code)
                .as("⛔ 非法游标被当真值：越界 upToSeq 会让后续所有增量被静默跳过（功能空转）")
                .contains("if (fromSeq < 0 || upToSeq < -1 || (upToSeq >= 0 && fromSeq > upToSeq)) {")
                .as("⛔ digest 未取规范 JSON 的 SHA-256：checkpoint 幂等键会漂移")
                .contains("MessageDigest.getInstance(\"SHA-256\").digest(bytes)")
                .contains("toJson(state).getBytes(StandardCharsets.UTF_8)");
        for (String method : List.of("public static @NonNull String toJson(",
                "public static @NonNull String digest(",
                "public static @NonNull StructuredState fromJson(")) {
            assertThat(code).as("⛔ 编解码入口缺失: %s", method).contains(method);
        }
    }

    @Test
    @DisplayName("✅ StructuredState 全参构造唯一 + 零原始对话正文（铁律 #10 / §5.4）")
    void structuredStateHasNoRawConversationAndNoCopyBypass() {
        String code = src("StructuredState");
        for (String name : List.of("StructuredState", "Artifact", "Fact", "ToolCall")) {
            assertThat(code)
                    .as("⛔ %s 出现兼容构造器：加字段时会静默清空后加的字段", name)
                    .doesNotContain("public " + name + "(");
            assertThat(code)
                    .as("⛔ %s 缺少紧凑构造器做 null→空归一化", name)
                    .contains("public " + name + " {");
        }
        assertThat(code)
                .as("⛔ 结构化状态夹带原始对话正文：checkpoint 会退化成「换皮全文」")
                .doesNotContain("List<ModelMessage>")
                .doesNotContain("conversation")
                .doesNotContain("history");
    }

    @Test
    @DisplayName("✅ M5-c 零接线 / 零 IO / 零框架依赖：抽取能力只由接口注入")
    void m5cKernelIsPureAndUnwired() {
        Path sourceDir = ContextCompressionS1GuardTest.platformRoot().resolve(CTX);
        List<String> forbidden = List.of(
                "ModelGateway", "ChatStreamGateway", "chatStream(",
                "System.currentTimeMillis", "Instant.now", "LocalDateTime.now",
                "java.io.File", "Files.write", "Files.read", "java.net.http",
                "ObjectMapper", "Gson", "com.fasterxml", "java.sql", "javax.sql",
                "DataSource", "PreparedStatement", "org.springframework");
        List<String> violations = new ArrayList<>();
        try (var tree = Files.walk(sourceDir)) {
            for (Path file : tree.filter(p -> p.toString().endsWith(".java")).toList()) {
                String code = ContextCompressionS1GuardTest.stripCommentsKeepStrings(
                        ContextCompressionS1GuardTest.read(file));
                for (String token : forbidden) {
                    if (code.contains(token)) {
                        violations.add(file.getFileName() + " → " + token);
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("扫描 M5-c 内核源码失败", e);
        }
        assertThat(violations)
                .as("⛔ 纯算法层出现 LLM / IO / JDBC / 框架依赖：M7 之前不得接线")
                .isEmpty();

        String pom = ContextCompressionS1GuardTest.read(
                ContextCompressionS1GuardTest.platformRoot().resolve("agent-context/pom.xml"));
        assertThat(count(pom, "<dependency>"))
                .as("⛔ agent-context 依赖面扩大：只允许 agent-ports + jspecify（C1 / ADR-011）")
                .isEqualTo(2);
        assertThat(pom).contains("agent-ports").contains("jspecify");
    }
}
