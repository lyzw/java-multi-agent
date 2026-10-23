package cn.studykid.rag.management.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.rag.core.source.SourceVerification.VerificationMethod;
import cn.studykid.rag.core.source.SourceVerification.VerificationStatus;

/**
 * 载体级校验规则的单元测试（RAG-D03 §4 三层模型 / RAG-D07 §2.3.1 G-0）。
 *
 * <p><b>⚠️ 为什么这些规则必须有单测</b>：
 * 「载体不得高于来源」是本端点最容易被绕过的闸门——
 * 它一旦失效，库里就会出现「来源 PENDING、载体 VERIFIED」的记录，
 * 而只看载体标记的检索路径会召回未审核语料。
 * 把规则钉在 {@code core}（而非 Controller 的 if 判断里），
 * 才能让「越权放行」成为一条<b>可断言的反证</b>而不是一句口头约定。
 */
class DocumentVerificationTest {

    private static final String REVIEWER = "33333333-3333-3333-3333-333333333333";

    @Test
    @DisplayName("载体目标状态只能是认领/通过/拒绝/撤回，PENDING 不是合法目标")
    void assignableTargetsExcludePending() {
        assertTrue(DocumentVerification.ASSIGNABLE_TARGETS.contains(VerificationStatus.IN_REVIEW));
        assertTrue(DocumentVerification.ASSIGNABLE_TARGETS.contains(VerificationStatus.VERIFIED));
        assertTrue(DocumentVerification.ASSIGNABLE_TARGETS.contains(VerificationStatus.REJECTED));
        assertTrue(DocumentVerification.ASSIGNABLE_TARGETS.contains(VerificationStatus.REVOKED));
        assertFalse(DocumentVerification.ASSIGNABLE_TARGETS.contains(VerificationStatus.PENDING),
                "PENDING 是初始态，不是任何审核动作的目标");
    }

    @Test
    @DisplayName("指定非法目标状态被拒（→ 400）")
    void requireAssignableTargetRejectsPending() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> DocumentVerification.requireAssignableTarget(VerificationStatus.PENDING));
        assertTrue(ex.getMessage().contains("PENDING"));
    }

    @Test
    @DisplayName("来源未 VERIFIED 时，载体不得置 VERIFIED（→ 409）")
    void carrierCannotExceedSource() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> DocumentVerification.requireSourceAllowsVerified(
                        VerificationStatus.VERIFIED, VerificationStatus.PENDING));
        assertTrue(ex.getMessage().contains("来源"),
                "错误消息必须说清是来源未通过校验：" + ex.getMessage());
    }

    @Test
    @DisplayName("来源已 VERIFIED 时，载体可置 VERIFIED")
    void carrierMayFollowVerifiedSource() {
        DocumentVerification.requireSourceAllowsVerified(
                VerificationStatus.VERIFIED, VerificationStatus.VERIFIED);
    }

    @Test
    @DisplayName("非 VERIFIED 的载体目标不受「不得高于来源」约束（载体可低于来源）")
    void lowerCarrierStatesAreNotBlockedBySourceRule() {
        // ⚠️ 来源 REJECTED、载体也 REJECTED 是合法组合：
        //    三层模型的价值正是「同一版本下逐个载体处置」。
        DocumentVerification.requireSourceAllowsVerified(
                VerificationStatus.REJECTED, VerificationStatus.REJECTED);
        DocumentVerification.requireSourceAllowsVerified(
                VerificationStatus.REVOKED, VerificationStatus.REJECTED);
        DocumentVerification.requireSourceAllowsVerified(
                VerificationStatus.IN_REVIEW, VerificationStatus.PENDING);
    }

    @Test
    @DisplayName("载体级复用来源级转移表：REVOKED 是终态")
    void carrierReusesSourceTransitionTable() {
        assertThrows(IllegalStateException.class,
                () -> DocumentVerification.requireAllowed(
                        VerificationStatus.REVOKED, VerificationStatus.VERIFIED));
        assertThrows(IllegalStateException.class,
                () -> DocumentVerification.requireAllowed(
                        VerificationStatus.REJECTED, VerificationStatus.VERIFIED));
        // 合法路径：PENDING → IN_REVIEW → VERIFIED
        DocumentVerification.requireAllowed(VerificationStatus.PENDING, VerificationStatus.IN_REVIEW);
        DocumentVerification.requireAllowed(VerificationStatus.IN_REVIEW, VerificationStatus.VERIFIED);
    }

    @Test
    @DisplayName("只有 VERIFIED 的载体可检索（fail-closed）")
    void onlyVerifiedCarrierIsRetrievable() {
        assertTrue(DocumentVerification.retrievableAfter(VerificationStatus.VERIFIED));
        assertFalse(DocumentVerification.retrievableAfter(VerificationStatus.IN_REVIEW));
        assertFalse(DocumentVerification.retrievableAfter(VerificationStatus.REJECTED));
        assertFalse(DocumentVerification.retrievableAfter(VerificationStatus.REVOKED));
    }

    @Test
    @DisplayName("审计动作与来源级同形：ADMIT / REJECT / REVOKE / INGEST")
    void auditActionsMatchSourceLevel() {
        assertEquals("ADMIT", DocumentVerification.auditActionFor(VerificationStatus.VERIFIED));
        assertEquals("REJECT", DocumentVerification.auditActionFor(VerificationStatus.REJECTED));
        assertEquals("REVOKE", DocumentVerification.auditActionFor(VerificationStatus.REVOKED));
        assertEquals("INGEST", DocumentVerification.auditActionFor(VerificationStatus.IN_REVIEW));
    }

    @Test
    @DisplayName("载体级命令：证据与决定人缺一不可")
    void decisionRequiresEvidenceAndDecider() {
        assertThrows(IllegalArgumentException.class,
                () -> new DocumentVerificationDecision(VerificationStatus.VERIFIED, " ", REVIEWER,
                        VerificationMethod.ISBN, null, 365));
        assertThrows(IllegalArgumentException.class,
                () -> new DocumentVerificationDecision(VerificationStatus.VERIFIED, "ISBN:ref", " ",
                        VerificationMethod.ISBN, null, 365));
    }

    @Test
    @DisplayName("载体级命令：目标状态非法在构造期就被拒（不拖到落库才发现）")
    void decisionRejectsIllegalTargetAtConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new DocumentVerificationDecision(VerificationStatus.PENDING, "ISBN:ref", REVIEWER,
                        VerificationMethod.ISBN, null, 365));
    }

    @Test
    @DisplayName("撤回命令必须带依据（DB CHECK 亦强制）")
    void revokeRequiresEvidence() {
        assertThrows(IllegalArgumentException.class,
                () -> DocumentVerificationDecision.revoke("", REVIEWER));
        DocumentVerificationDecision decision =
                DocumentVerificationDecision.revoke("权利人通知", REVIEWER);
        assertEquals(VerificationStatus.REVOKED, decision.targetStatus());
        assertEquals("权利人通知", decision.evidenceRef());
    }
}
