package cn.studykid.multiagent.bootstrap.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上下文压缩 <b>S2-M5-b 纯算法内核</b>的结构守卫（ADR-013 / 开发计划 W4）。
 *
 * <p>⚠️ 与 {@code ContextCompressionS1GuardTest} 的分工：S1 守卫钉死「影子期零接线」，
 * 本类钉死「M5-b 纯算法内核的 7 个修复点」。每条断言都问过「撤掉修复点它会红吗」，
 * 并配套 {@code RV-21..27} 撤点脚本（精确计数替换 + finally 复原 + md5 校验）。
 *
 * <p>⚠️ 本类<b>不引用被测实现自己的常量</b>：一律用字符串锚点 + 行为语义断言，
 * 避免「改常量 = 改测试」的自证循环（项目铁律 #6）。
 */
class ContextCompressionM5bGuardTest {

    private static final String CTX = "agent-context/src/main/java/cn/studykid/multiagent/context/";

    @Test
    @DisplayName("✅ 估算与渲染同源：CompactionPipeline.estimate 必须走 ContextLayout.countRendered（RV-23）")
    void estimateIsSourcedFromRender() {
        String pipe = ContextCompressionS1GuardTest.stripCommentsKeepStrings(
                ContextCompressionS1GuardTest.read(
                        ContextCompressionS1GuardTest.platformRoot().resolve(CTX + "CompactionPipeline.java")));

        assertThat(pipe)
                .as("⛔ estimate 不再从 render 取口径：布局每加一块（首轮锚/围栏/当前输入）估算就会漏算，"
                        + "预算静默低估（RV-23 撤点必红）")
                .contains("ContextLayout.countRendered(");

        String layout = ContextCompressionS1GuardTest.stripCommentsKeepStrings(
                ContextCompressionS1GuardTest.read(
                        ContextCompressionS1GuardTest.platformRoot().resolve(CTX + "ContextLayout.java")));
        assertThat(layout)
                .as("⛔ countRendered 必须先 render 再数：否则估算与真实请求字节脱钩")
                .contains("RenderedContext rendered = render(blocks)")
                .contains("return counter.count(cb)");
    }

    @Test
    @DisplayName("✅ W1 归档净省 token + 短结果跳过：不能把 TOOL 越压越长（RV-22）")
    void w1ArchivalMustBeNetSaving() {
        String pipe = ContextCompressionS1GuardTest.stripCommentsKeepStrings(
                ContextCompressionS1GuardTest.read(
                        ContextCompressionS1GuardTest.platformRoot().resolve(CTX + "CompactionPipeline.java")));

        // countContent 必须存在且参与「归档行 vs 原文」的比较，而不是摆设。
        assertThat(pipe)
                .as("⛔ W1 缺少逐条 token 比较：短 TOOL 结果被换成更长的归档行 = 净增 token（RV-22）")
                .contains("countContent(counter, archived.content()) >= countContent(counter, m.content())");
        assertThat(pipe)
                .as("⛔ countContent 必须走真实 TokenCounter，不能用 content.length() 近似（CJK 口径不同）")
                .contains("counter.count(new ContextBlocks(");
    }

    @Test
    @DisplayName("✅ W2 裁剪目标用 targetRatio（非 hardLimit）：杜绝「水位触发却不裁」功能空转（RV-21）")
    void w2TargetUsesTargetRatio() {
        String pipe = ContextCompressionS1GuardTest.stripCommentsKeepStrings(
                ContextCompressionS1GuardTest.read(
                        ContextCompressionS1GuardTest.platformRoot().resolve(CTX + "CompactionPipeline.java")));

        assertThat(pipe)
                .as("⛔ W2 裁剪目标被改成 hardLimit：usage=0.70 时 before 本就 <0.90×window，"
                        + "整轮裁剪永不触发（功能空转，RV-21 撤点必红）")
                .contains("return (int) (budget.window() * policy.targetRatio());")
                .doesNotContain("return budget.hardLimit();");
        assertThat(pipe)
                .as("⛔ W1 与 W2 动作未分离：W1 水位不该做整轮裁剪")
                .contains("boolean wantsPrune = tier.ordinal() >= WaterLevel.Tier.W2.ordinal();");
    }

    @Test
    @DisplayName("✅ 摘要失败不伪造 + 增量游标单调：empty 回落 W2，只吃 index>upToSeq（RV-24/RV-25）")
    void summaryFailClosedAndDeltaCursor() {
        String pipe = ContextCompressionS1GuardTest.stripCommentsKeepStrings(
                ContextCompressionS1GuardTest.read(
                        ContextCompressionS1GuardTest.platformRoot().resolve(CTX + "CompactionPipeline.java")));

        assertThat(pipe)
                .as("⛔ 摘要失败未回落 W2 / 未标记 fallback：会伪造摘要（RV-24）")
                .contains("fallback = true;")
                .contains("fallbackReason = FALLBACK_SUMMARIZER_FAILED;")
                .as("⛔ 摘要成功分支必须要求非空，empty 不是成功")
                .contains("merged.isPresent() && !merged.get().isEmpty()");
        assertThat(pipe)
                .as("⛔ 摘要 delta 未用单调游标：递归重摘要会重复计费（RV-25）")
                .contains("if (i > upToSeq) {");
    }

    @Test
    @DisplayName("✅ 不可信块围栏 + 稳定前缀逐字透传：system/tools 无动态内容（RV-26/RV-27）")
    void untrustedFenceAndStablePrefix() {
        String layout = ContextCompressionS1GuardTest.stripCommentsKeepStrings(
                ContextCompressionS1GuardTest.read(
                        ContextCompressionS1GuardTest.platformRoot().resolve(CTX + "ContextLayout.java")));

        // ⚠️ 不能只断言常量「存在」——把 wrapUntrusted 的 content 改成裸 text 时
        //    常量还在，守卫会假绿（RVG-6 实证）。必须断言围栏常量真的参与 content 拼装。
        assertThat(layout)
                .as("⛔ 围栏标记缺失：RAG/摘要/裁剪说明会裸奔进消息（RV-26 撤点必红）")
                .contains("String content = FENCE_OPEN")
                .contains("FENCE_DISCLAIMER")
                .contains("+ text +")
                .contains("FENCE_CLOSE;")
                .contains("compacted_context")
                .contains("untrusted");
        assertThat(layout)
                .as("⛔ 稳定前缀被追加动态内容：systemPrompt 逐字透传是 prompt cache 的前提（RV-27）")
                .contains("String system = blocks.systemPrompt();")
                .contains("String tools = blocks.toolSchemas();")
                .doesNotContain("String system = blocks.systemPrompt() +");
        // 四类不可信块必须全部走围栏，绝不直拼 system/tools。
        for (String label : List.of("trim_notice", "summary", "rag[", "memory")) {
            assertThat(layout)
                    .as("⛔ 不可信块 %s 未走 wrapUntrusted 围栏", label)
                    .contains("wrapUntrusted(\"" + label);
        }
    }

    @Test
    @DisplayName("✅ M5-b 结果 record 全参构造唯一：CompactedBlocks/CompactionResult 零拷贝旁路（铁律 #11）")
    void resultRecordsHaveNoCopyBypass() {
        for (String name : List.of("CompactedBlocks", "CompactionResult", "RenderedContext", "ContextBudget")) {
            String src = ContextCompressionS1GuardTest.stripCommentsKeepStrings(
                    ContextCompressionS1GuardTest.read(
                            ContextCompressionS1GuardTest.platformRoot().resolve(CTX + name + ".java")));
            assertThat(src)
                    .as("⛔ %s 出现兼容构造器：加字段时会静默清空后加的字段（铁律 #11）", name)
                    .doesNotContain("public " + name + "(");
            assertThat(src)
                    .as("⛔ %s 缺少紧凑构造器做 null→空归一化", name)
                    .contains("public " + name + " {");
        }
    }

    @Test
    @DisplayName("✅ M5-b 零接线 / 零 IO：纯算法层不碰网关、不读时钟、不写文件")
    void m5bKernelIsPureAndUnwired() {
        Path src = ContextCompressionS1GuardTest.platformRoot().resolve(CTX);
        List<String> forbidden = List.of(
                "ModelGateway", "ChatStreamGateway", "chatStream(",
                "System.currentTimeMillis", "Instant.now", "LocalDateTime.now",
                "java.io.File", "Files.write", "Files.read", "java.net.http");
        List<String> violations = new ArrayList<>();
        try (var tree = Files.walk(src)) {
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
            throw new IllegalStateException("扫描 M5-b 内核源码失败", e);
        }
        assertThat(violations)
                .as("⛔ 纯算法层出现 LLM 网关 / IO / 时钟：W3 摘要必须由外部注入 Summarizer，"
                        + "本层只负责喂增量、拿结果、失败回落")
                .isEmpty();
    }
}
