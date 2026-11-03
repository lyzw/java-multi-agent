package cn.studykid.rag.management.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 抽检前置守卫与判定规则的测试（V1.0.2 S3-1 验收判据）。
 *
 * <p>核心反证：<b>未过抽检不得 VERIFIED</b>。
 * 这条如果失效，等于「所有新录入的语料都能绕过抽检直接放行」——
 * 而抽检守的正是「答案区漏判=0」这条泄题底线。
 */
class InspectionGuardTest {

    private static final String SOURCE = "src-1";

    @Nested
    @DisplayName("守卫：只有 PASSED 放行")
    class Guard {

        @Test
        @DisplayName("PASSED → 允许签发 VERIFIED")
        void passedAllows() {
            assertTrue(InspectionGuard.allowsVerified(InspectionGuard.InspectionStatus.PASSED));
            InspectionGuard.requireInspectionPassed(
                    InspectionGuard.InspectionStatus.PASSED, SOURCE); // 不抛
        }

        @Test
        @DisplayName("未抽检（null）→ 拒绝签发")
        void nullRejects() {
            assertFalse(InspectionGuard.allowsVerified(null));
            assertThrows(IllegalStateException.class,
                    () -> InspectionGuard.requireInspectionPassed(null, SOURCE));
        }

        @Test
        @DisplayName("NOT_REQUIRED（默认态）→ 拒绝签发")
        void notRequiredRejects() {
            // ⚠️ 这是本测试最关键的一条：NOT_REQUIRED 是新录入来源的默认值。
            //    若它被当作放行，等于「所有新录入的语料都自动跳过抽检」。
            assertFalse(InspectionGuard.allowsVerified(
                    InspectionGuard.InspectionStatus.NOT_REQUIRED));
            assertThrows(IllegalStateException.class,
                    () -> InspectionGuard.requireInspectionPassed(
                            InspectionGuard.InspectionStatus.NOT_REQUIRED, SOURCE));
        }

        @Test
        @DisplayName("FAILED → 拒绝签发")
        void failedRejects() {
            assertFalse(InspectionGuard.allowsVerified(InspectionGuard.InspectionStatus.FAILED));
        }
    }

    @Nested
    @DisplayName("判定：单点否决与准确率")
    class Rules {

        @Test
        @DisplayName("答案区漏判 1 条 → 不通过（即使准确率 95%+）")
        void answerLeakVetoes() {
            List<InspectionRecord> allPass = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                allPass.add(pass("c" + i));
            }
            // 20 条中 1 条漏判 → 准确率 95%，但漏判必须否决
            List<InspectionRecord> withLeak = new ArrayList<>(allPass.subList(1, 20));
            withLeak.add(fail("c0", InspectionRecord.ErrorType.ANSWER_LEAK));

            InspectionRules.Verdict v = InspectionRules.evaluate(withLeak);
            assertFalse(v.passed(), "漏判即泄题，必须单点否决，不能被 95% 的准确率摊平");
            assertEquals(1, v.answerLeaks());
            assertTrue(v.reason().contains("漏判"));
        }

        @Test
        @DisplayName("无漏判但准确率 < 95% → 不通过")
        void lowAccuracyFails() {
            List<InspectionRecord> records = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                records.add(i < 2
                        ? fail("c" + i, InspectionRecord.ErrorType.EXERCISE_NO_LOST)
                        : pass("c" + i));
            }
            InspectionRules.Verdict v = InspectionRules.evaluate(records);
            assertFalse(v.passed(), "准确率 80% 低于 95% 下限");
            assertTrue(v.reason().contains("准确率"));
        }

        @Test
        @DisplayName("全部正确 → 通过")
        void allPass() {
            List<InspectionRecord> records = List.of(pass("c1"), pass("c2"), pass("c3"));
            InspectionRules.Verdict v = InspectionRules.evaluate(records);
            assertTrue(v.passed());
            assertEquals(1.0, v.accuracy(), 1e-9);
            assertEquals(InspectionGuard.InspectionStatus.PASSED, InspectionRules.toStatus(v));
        }

        @Test
        @DisplayName("空抽检 → 不通过（不得空转放行）")
        void emptyFails() {
            InspectionRules.Verdict v = InspectionRules.evaluate(List.of());
            assertFalse(v.passed(), "没有抽检任何片段就签发「通过」是治理空转");
            assertEquals(InspectionGuard.InspectionStatus.FAILED, InspectionRules.toStatus(v));
        }
    }

    @Nested
    @DisplayName("记录自洽性")
    class RecordInvariants {

        @Test
        @DisplayName("FAIL 必带 errorType")
        void failRequiresErrorType() {
            assertThrows(IllegalArgumentException.class, () -> new InspectionRecord(
                    "r1", "t", "s", "c", "i", "EXPLANATION", "EXPLANATION",
                    null, InspectionRecord.Verdict.FAIL, "2026-01-01"));
        }

        @Test
        @DisplayName("PASS 不得带 errorType")
        void passForbidsErrorType() {
            assertThrows(IllegalArgumentException.class, () -> new InspectionRecord(
                    "r1", "t", "s", "c", "i", "EXPLANATION", "EXPLANATION",
                    InspectionRecord.ErrorType.OTHER, InspectionRecord.Verdict.PASS, "2026-01-01"));
        }

        @Test
        @DisplayName("是答案漏判的判定")
        void detectsAnswerLeak() {
            assertTrue(fail("c1", InspectionRecord.ErrorType.ANSWER_LEAK).isAnswerLeak());
            assertFalse(fail("c1", InspectionRecord.ErrorType.OTHER).isAnswerLeak());
            assertFalse(pass("c1").isAnswerLeak());
        }
    }

    private static InspectionRecord pass(String chunkId) {
        return new InspectionRecord("r-" + chunkId, "t", "s", chunkId, "i",
                "EXPLANATION", "EXPLANATION", null, InspectionRecord.Verdict.PASS, "2026-01-01");
    }

    private static InspectionRecord fail(String chunkId, InspectionRecord.ErrorType type) {
        return new InspectionRecord("r-" + chunkId, "t", "s", chunkId, "i",
                "EXERCISE_ANSWER", "EXPLANATION", type, InspectionRecord.Verdict.FAIL, "2026-01-01");
    }
}
