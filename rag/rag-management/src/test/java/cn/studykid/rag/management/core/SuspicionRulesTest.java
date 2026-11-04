package cn.studykid.rag.management.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 抽检疑点判据（交互文档 §4.6「看红灯」）。
 *
 * <p>⚠️ 守的核心是<b>不漏报</b>：漏标一条「其实是被判成正文的答案区」，
 * 就意味着某个标准答案会被送进讲解模式的回答里。
 * 多标一条的代价只是多看一眼，所以判据宁宽勿严。
 *
 * <p>⚠️ 同时守<b>不误报到不可用</b>：若把普通正文全标成疑点，
 * 人就又回到了「逐条看」——那等于没优化。
 */
class SuspicionRulesTest {

    private static SuspicionRules.ChunkFacts fact(String role, String text,
                                                  String exerciseNo, Integer page) {
        return new SuspicionRules.ChunkFacts(
                "c-1", "d-1", role, text, page, "Unit 1", exerciseNo);
    }

    private static List<InspectionSuspicion.Reason> reasonsOf(SuspicionRules.ChunkFacts f) {
        return SuspicionRules.inspect(f).stream().map(InspectionSuspicion::reason).toList();
    }

    @Test
    @DisplayName("答案特征词出现在正文里 → 标为疑似漏判（HIGH）")
    void flagsAnswerMarkerInExplanation() {
        var findings = SuspicionRules.inspect(
                fact("EXPLANATION", "参考答案：1-5 B A C D A", null, 160));

        assertEquals(List.of(InspectionSuspicion.Reason.SUSPECT_ANSWER_MISSED),
                findings.stream().map(InspectionSuspicion::reason).toList());
        assertEquals(InspectionSuspicion.Severity.HIGH, findings.get(0).severity());
    }

    @Test
    @DisplayName("题号-选项序列出现在正文里 → 标为疑似漏判")
    void flagsAnswerSequenceInExplanation() {
        assertTrue(reasonsOf(fact("EXPLANATION", "1-5: B A C D A  6-10: C D A B C", null, 88))
                .contains(InspectionSuspicion.Reason.SUSPECT_ANSWER_MISSED));
    }

    @Test
    @DisplayName("答案已经是答案类角色 → 不重复标「漏判」")
    void doesNotFlagAnswerRoleAsMissed() {
        var reasons = reasonsOf(fact("EXERCISE_ANSWER", "参考答案：B A C", "1-5", 160));
        assertFalse(reasons.contains(InspectionSuspicion.Reason.SUSPECT_ANSWER_MISSED));
    }

    @Test
    @DisplayName("答案类片段缺题号 → 标出（无法定位到题目）")
    void flagsAnswerWithoutExerciseNo() {
        assertTrue(reasonsOf(fact("EXERCISE_ANSWER", "B A C D", null, 160))
                .contains(InspectionSuspicion.Reason.ANSWER_WITHOUT_EXERCISE_NO));
    }

    @Test
    @DisplayName("习题片段缺题号 → 标出（题号保留率要求 100%）")
    void flagsExerciseWithoutNo() {
        assertTrue(reasonsOf(fact("EXERCISE", "选择正确答案。", null, 48))
                .contains(InspectionSuspicion.Reason.EXERCISE_WITHOUT_NO));
    }

    @Test
    @DisplayName("超长片段 → 标出（切分边界可能有误）")
    void flagsOversizedChunk() {
        String longText = "很长的讲解内容。".repeat(400);
        assertTrue(reasonsOf(fact("EXPLANATION", longText, null, 10))
                .contains(InspectionSuspicion.Reason.OVERSIZED_CHUNK));
    }

    @Test
    @DisplayName("完全空的片段 → 标出（解析残渣）")
    void flagsEmptyChunk() {
        assertTrue(reasonsOf(fact("EXPLANATION", "   \n  ", null, 10))
                .contains(InspectionSuspicion.Reason.NEARLY_EMPTY_CHUNK));
    }

    @Test
    @DisplayName("⛔ 短但有字的片段**不**标疑点（实测修正：803/1463 条短片段会淹没真疑点）")
    void doesNotFlagShortButMeaningfulChunk() {
        // 真实教材里的页眉/图注就是这种：短，但是有效内容
        for (String shortText : new String[] {"[图]", "六年级 上册", "义务教育教科书"}) {
            var findings = SuspicionRules.inspect(
                    fact("EXPLANATION", shortText, null, 1));
            assertTrue(findings.stream().noneMatch(
                            f -> f.reason() == InspectionSuspicion.Reason.NEARLY_EMPTY_CHUNK),
                    "短但有字的片段不应判为残渣：" + shortText);
        }
    }

    @Test
    @DisplayName("答案类缺页码 → 标出（无法回取原文）")
    void flagsAnswerWithoutPage() {
        assertTrue(reasonsOf(fact("EXAMPLE_SOLUTION", "解题过程……", "例 3", null))
                .contains(InspectionSuspicion.Reason.NO_LOCATOR));
    }

    @Test
    @DisplayName("⛔ 正常正文不产生任何疑点（否则人又回到逐条看）")
    void cleanExplanationProducesNoFinding() {
        var findings = SuspicionRules.inspect(fact(
                "EXPLANATION",
                "I usually go to school by bus. She walks to school every day.",
                null, 12));

        assertTrue(findings.isEmpty(),
                "普通正文不应被标为疑点，实际：" + findings);
    }

    @Test
    @DisplayName("正常习题（有题号、长度正常）不产生疑点")
    void cleanExerciseProducesNoFinding() {
        var findings = SuspicionRules.inspect(fact(
                "EXERCISE",
                "1. 选择正确答案。(  ) A. by bus  B. by car",
                "1", 48));

        assertTrue(findings.isEmpty(), "实际：" + findings);
    }

    @Test
    @DisplayName("多条成因可同时命中，且按严重度排序（HIGH 在前）")
    void multipleFindingsSortedBySeverity() {
        String longAnswer = ("参考答案：B A C D ".repeat(200));
        var findings = SuspicionRules.inspect(fact("EXPLANATION", longAnswer, null, null));

        // 至少命中「疑似漏判」(HIGH) 与「超长」(MEDIUM)
        assertTrue(findings.size() >= 2, "实际：" + findings);
        assertEquals(InspectionSuspicion.Severity.HIGH, findings.get(0).severity());
        // 排序必须是单调的
        for (int i = 1; i < findings.size(); i += 1) {
            assertTrue(findings.get(i - 1).severity().order() <= findings.get(i).severity().order());
        }
    }

    @Test
    @DisplayName("正文超长时截断展示（避免单条撑爆界面）")
    void truncatesLongTextPreview() {
        // ⚠️ 用真实中文长文本而非 "AAAA" 重复：
        //    纯字母重复会被「题号-选项序列」正则命中，从而额外多出一个 HIGH 疑点，
        //    测试就测不到「超长截断」这一条了（本用例第一版正是这样挂的）。
        // ⚠️ 必须真的超过 OVERSIZED_THRESHOLD（2000 字符）才会命中——
        //    单句 26 字 × 40 只有 1040，第一版就是长度算错导致本用例没测到目标规则。
        String sentence = "这是一段很长的正文讲解，用于验证超长片段的截断展示。";
        String huge = sentence.repeat(
                (SuspicionRules.OVERSIZED_THRESHOLD / sentence.length()) + 2);
        var findings = SuspicionRules.inspect(fact("EXPLANATION", huge, null, 1));

        assertFalse(findings.isEmpty());
        assertTrue(findings.get(0).text().length() <= InspectionSuspicion.TEXT_PREVIEW_LIMIT + 1);
        assertTrue(findings.get(0).text().endsWith("…"));
    }

    @Test
    @DisplayName("答案类口径与 ChunkRole.isAnswerBearing 一致")
    void answerBearingMatchesChunkRole() {
        assertTrue(SuspicionRules.isAnswerBearing("EXERCISE_ANSWER"));
        assertTrue(SuspicionRules.isAnswerBearing("EXAMPLE_SOLUTION"));
        assertFalse(SuspicionRules.isAnswerBearing("EXAMPLE"));
        assertFalse(SuspicionRules.isAnswerBearing("EXERCISE_HINT"));
        assertFalse(SuspicionRules.isAnswerBearing("EXPLANATION"));
    }

    @Test
    @DisplayName("英文答案标记也能识别（Answers: / Answer key）")
    void recognizesEnglishAnswerMarkers() {
        assertTrue(SuspicionRules.looksLikeAnswerZone("Answers: 1. B 2. A"));
        assertTrue(SuspicionRules.looksLikeAnswerZone("Answer key"));
    }

    @Test
    @DisplayName("null 正文不抛异常，判为疑似残渣")
    void handlesNullText() {
        var findings = SuspicionRules.inspect(fact("EXPLANATION", null, null, 10));
        assertTrue(findings.stream()
                .anyMatch(f -> f.reason() == InspectionSuspicion.Reason.NEARLY_EMPTY_CHUNK));
    }
}
