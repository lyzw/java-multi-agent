package cn.studykid.rag.management.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import cn.studykid.rag.core.source.SourceVerification.VerificationStatus;

/**
 * 校验状态机测试（RAG-D07 §2.4 转移表 T-1～T-12）。
 *
 * <p><b>⚠️ 本测试的价值在于「反证」</b>：对 12 条转移规则<b>逐条</b>给出
 * 允许/禁止的断言。RAG-D09 §6.4 记载过本项目的一个教训——
 * 「文档声称有守卫、实际没有守卫」。因此这里的每条规则都必须有对应断言，
 * 而不是「测几条正向路径就算过」。
 */
class VerificationTransitionTest {

    @Nested
    @DisplayName("允许的转移（T-1～T-9）")
    class AllowedTransitions {

        @ParameterizedTest(name = "T-{0}: {1} → {2} 应当允许")
        @CsvSource({
                "1, PENDING,   IN_REVIEW",
                "2, IN_REVIEW, VERIFIED",
                "3, IN_REVIEW, REJECTED",
                "4, REJECTED,  IN_REVIEW",
                "5, PENDING,   REVOKED",
                "6, IN_REVIEW, REVOKED",
                "7, VERIFIED,  REVOKED",
                "8, REJECTED,  REVOKED",
                "9, VERIFIED,  IN_REVIEW"
        })
        @DisplayName("RAG-D07 §2.4 允许的 9 条转移全部放行")
        void allowedTransitions(int ruleNo, VerificationStatus from, VerificationStatus to) {
            assertTrue(VerificationTransition.isAllowed(from, to),
                    "T-" + ruleNo + " 应当允许：" + from + " → " + to);
        }
    }

    @Nested
    @DisplayName("禁止的转移（T-10～T-12）")
    class ForbiddenTransitions {

        @Test
        @DisplayName("T-10：REVOKED 是终态，任何转移都被拒")
        void revokedIsTerminalState() {
            for (VerificationStatus to : VerificationStatus.values()) {
                assertFalse(VerificationTransition.isAllowed(VerificationStatus.REVOKED, to),
                        "REVOKED → " + to + " 必须被拒（T-10：REVOKED 是终态）");
            }
            assertTrue(VerificationTransition.allowedTargets(VerificationStatus.REVOKED).isEmpty(),
                    "REVOKED 的允许目标集合应为空");
        }

        @Test
        @DisplayName("T-11：不允许 VERIFIED → PENDING 降级回默认态")
        void verifiedCannotDowngradeToPending() {
            assertFalse(VerificationTransition.isAllowed(
                    VerificationStatus.VERIFIED, VerificationStatus.PENDING),
                    "T-11 禁止 VERIFIED → PENDING");
        }

        @Test
        @DisplayName("T-12：禁止 REJECTED → VERIFIED 直通（必须先回到 IN_REVIEW）")
        void rejectedCannotJumpToVerified() {
            assertFalse(VerificationTransition.isAllowed(
                    VerificationStatus.REJECTED, VerificationStatus.VERIFIED),
                    "T-12 禁止 REJECTED → VERIFIED");
        }

        @Test
        @DisplayName("同状态自转移不被允许（避免重复审计）")
        void selfTransitionNotAllowed() {
            for (VerificationStatus status : VerificationStatus.values()) {
                assertFalse(VerificationTransition.isAllowed(status, status),
                        status + " → " + status + " 不应被视为有效转移");
            }
        }
    }

    @Nested
    @DisplayName("requireAllowed 的失败行为")
    class RequireAllowedBehaviour {

        @Test
        @DisplayName("非法转移抛出 IllegalStateException 且消息含转移信息")
        void illegalTransitionThrows() {
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> VerificationTransition.requireAllowed(
                            VerificationStatus.REJECTED, VerificationStatus.VERIFIED));
            assertTrue(ex.getMessage().contains("REJECTED"),
                    "异常消息应包含原状态：" + ex.getMessage());
            assertTrue(ex.getMessage().contains("VERIFIED"),
                    "异常消息应包含目标状态：" + ex.getMessage());
        }

        @Test
        @DisplayName("合法转移不抛异常")
        void legalTransitionPasses() {
            VerificationTransition.requireAllowed(
                    VerificationStatus.IN_REVIEW, VerificationStatus.VERIFIED);
        }
    }

    @Nested
    @DisplayName("边界输入")
    class BoundaryInputs {

        @Test
        @DisplayName("null 状态一律不被允许（fail-closed）")
        void nullIsNeverAllowed() {
            assertFalse(VerificationTransition.isAllowed(null, VerificationStatus.VERIFIED));
            assertFalse(VerificationTransition.isAllowed(VerificationStatus.PENDING, null));
            assertFalse(VerificationTransition.isAllowed(null, null));
        }

        @Test
        @DisplayName("allowedTargets 对每个状态都返回不可变快照")
        void allowedTargetsAreSnapshots() {
            for (VerificationStatus status : VerificationStatus.values()) {
                Set<VerificationStatus> targets = VerificationTransition.allowedTargets(status);
                assertThrows(UnsupportedOperationException.class,
                        () -> targets.add(VerificationStatus.VERIFIED),
                        "allowedTargets 应返回不可变集合");
            }
        }
    }

    @Nested
    @DisplayName("允许目标集合的完整性")
    class AllowedTargetsCompleteness {

        @Test
        @DisplayName("PENDING 的允许目标恰为 {IN_REVIEW, REVOKED}")
        void pendingTargets() {
            assertEquals(Set.of(VerificationStatus.IN_REVIEW, VerificationStatus.REVOKED),
                    VerificationTransition.allowedTargets(VerificationStatus.PENDING));
        }

        @Test
        @DisplayName("IN_REVIEW 的允许目标恰为 {VERIFIED, REJECTED, REVOKED}")
        void inReviewTargets() {
            assertEquals(
                    Set.of(VerificationStatus.VERIFIED, VerificationStatus.REJECTED,
                            VerificationStatus.REVOKED),
                    VerificationTransition.allowedTargets(VerificationStatus.IN_REVIEW));
        }

        @Test
        @DisplayName("VERIFIED 的允许目标恰为 {IN_REVIEW, REVOKED}（不含 PENDING）")
        void verifiedTargets() {
            assertEquals(Set.of(VerificationStatus.IN_REVIEW, VerificationStatus.REVOKED),
                    VerificationTransition.allowedTargets(VerificationStatus.VERIFIED));
        }

        @Test
        @DisplayName("REJECTED 的允许目标恰为 {IN_REVIEW, REVOKED}（不含 VERIFIED）")
        void rejectedTargets() {
            assertEquals(Set.of(VerificationStatus.IN_REVIEW, VerificationStatus.REVOKED),
                    VerificationTransition.allowedTargets(VerificationStatus.REJECTED));
        }
    }
}
