package cn.studykid.rag.management.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.rag.management.core.InspectionSuspicion.Reason;
import cn.studykid.rag.management.core.SuspicionRules.ChunkFacts;

/**
 * 零疑点来源的确认性抽检样本守卫。
 *
 * <p><b>为什么必须有这组测试</b>：{@code listSuspects} 零疑点返回空会让抽检台无法提交，
 * {@code inspection_status} 停在 NOT_REQUIRED，VERIFIED 永远被 409 拒绝——
 * 这是「质量越好的来源越签不出来」的产品死锁。守卫必须在纯函数层钉死
 * 「零疑点也能产出可裁决的样本」，同时确保它<b>不是</b>自动放行。
 */
class ConfirmationSamplingTest {

    private static ChunkFacts chunk(int n, String role, Integer page) {
        return new ChunkFacts("chunk-" + n, "doc-1", role, "正文片段 " + n,
                page, "第1章", null);
    }

    private static List<ChunkFacts> plainFacts(int count) {
        List<ChunkFacts> facts = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            facts.add(chunk(i, "EXPLANATION", i + 1));
        }
        return facts;
    }

    @Test
    @DisplayName("零疑点来源产出非空确认样本（不再让 VERIFIED 死锁）")
    void zeroSuspicionProducesSamples() {
        List<InspectionSuspicion> samples = ConfirmationSampling.sample(plainFacts(100), 20);
        assertTrue(!samples.isEmpty(), "无自动疑点时也必须给出可裁决样本，否则抽检台无法提交");
        assertEquals(20, samples.size());
    }

    @Test
    @DisplayName("样本统一标记 ROUTINE_CONFIRM 且为 LOW（明确不是泄题疑点，不构成自动放行）")
    void samplesAreRoutineConfirmAndLow() {
        List<InspectionSuspicion> samples = ConfirmationSampling.sample(plainFacts(100), 20);
        for (InspectionSuspicion s : samples) {
            assertEquals(Reason.ROUTINE_CONFIRM, s.reason());
            assertEquals(InspectionSuspicion.Severity.LOW, s.severity());
            assertNotNull(s.text());
            assertTrue(!s.text().isBlank());
        }
    }

    @Test
    @DisplayName("答案类片段优先入样（兜住 D04 答案区漏判底线）")
    void answerBearingFragmentsArePrioritized() {
        List<ChunkFacts> facts = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            facts.add(chunk(i, "EXPLANATION", i + 1));
        }
        // 末尾放 3 条答案类：若不优先，均匀抽样很可能抽不到它们。
        facts.add(chunk(100, "EXAMPLE_SOLUTION", 101));
        facts.add(chunk(101, "EXERCISE_ANSWER", 102));
        facts.add(chunk(102, "EXAMPLE_SOLUTION", 103));

        List<InspectionSuspicion> samples = ConfirmationSampling.sample(facts, 10);
        long answerCount = samples.stream()
                .filter(s -> SuspicionRules.isAnswerBearing(s.role()))
                .count();
        assertEquals(3, answerCount, "3 条答案类片段必须全部入样，供人核对是否被误判");
    }

    @Test
    @DisplayName("正文按页均匀抽样，覆盖首尾而非只给封面")
    void samplesAreEvenlySpaced() {
        List<InspectionSuspicion> samples = ConfirmationSampling.sample(plainFacts(100), 5);
        // 100 条 = chunk-0..chunk-99；等距取 [0, 25, 50, 75, 99]，覆盖首尾而非只给封面。
        assertEquals("chunk-0", samples.get(0).chunkId());
        assertEquals("chunk-99", samples.get(samples.size() - 1).chunkId());
        assertEquals(5, samples.size());
    }

    @Test
    @DisplayName("n==1 取中段而非封面")
    void singleSamplePicksMiddle() {
        List<InspectionSuspicion> samples = ConfirmationSampling.sample(plainFacts(100), 1);
        assertEquals(1, samples.size());
        assertEquals("chunk-50", samples.get(0).chunkId());
    }

    @Test
    @DisplayName("空输入返回空（如实呈现，绝不伪造样本）")
    void emptyInputReturnsEmpty() {
        assertTrue(ConfirmationSampling.sample(List.of(), 20).isEmpty());
    }

    @Test
    @DisplayName("样本数不超过片段总数（不重复造数据）")
    void neverExceedsAvailableFragments() {
        assertEquals(3, ConfirmationSampling.sample(plainFacts(3), 20).size());
    }

    @Test
    @DisplayName("反向验证锚：样本不带 PASS/FAIL 结论字段，仍需人工逐条裁决")
    void samplesAreNotVerdicts() {
        // InspectionSuspicion 本身无 verdict 字段；确认样本只是「待看材料」。
        // 这条断言钉死：本功能不得偷渡成「系统替人判 PASS」。
        List<InspectionSuspicion> samples = ConfirmationSampling.sample(plainFacts(10), 5);
        for (InspectionSuspicion s : samples) {
            assertTrue(s.reason() == Reason.ROUTINE_CONFIRM,
                    "确认样本只能标记成因，不能携带任何结论语义");
        }
    }
}
