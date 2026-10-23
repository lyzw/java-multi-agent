package cn.studykid.rag.management.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.rag.core.source.SourceVerification.VerificationMethod;
import cn.studykid.rag.core.source.SourceVerification.VerificationStatus;
import cn.studykid.rag.core.source.TrustTier;

/**
 * 登记与校验决定命令的校验测试（RAG-D07 §2.2 角色约束 / §2.5 留痕要求）。
 */
class SourceRegistrationTest {

    @Test
    @DisplayName("ISBN 与 DOI 都缺失时拒绝登记（无法进入校验流程）")
    void registrationRequiresAtLeastOneIdentifier() {
        assertThrows(IllegalArgumentException.class, () -> new SourceRegistration(
                "教材", "人教版八年级上册数学", "人民教育出版社", null, null, null, null));
    }

    @Test
    @DisplayName("只有空白字符串的标识视为缺失")
    void blankIdentifierIsTreatedAsMissing() {
        assertThrows(IllegalArgumentException.class, () -> new SourceRegistration(
                "教材", "标题", "出版社", "   ", "  ", null, null));
    }

    @Test
    @DisplayName("登记态恒为 PENDING / NONE（采集员不得自行提升）")
    void registrationStateIsFixed() {
        SourceRegistration reg = new SourceRegistration(
                "教材", "标题", "出版社", "9787100000000", null, null, null);
        assertEquals(VerificationStatus.PENDING, reg.registrationStatus());
        assertEquals(VerificationMethod.NONE, reg.registrationMethod());
    }

    @Test
    @DisplayName("教材等级需 ISBN，论文等级需 DOI（与 V1 CHECK 约束同构）")
    void identifierConsistencyMatchesDatabaseConstraint() {
        SourceRegistration isbnOnly = new SourceRegistration(
                "教材", "标题", "出版社", "9787100000000", null, null, null);
        isbnOnly.requireIdentifierConsistent(TrustTier.OFFICIAL_PUBLISHER);
        assertThrows(IllegalArgumentException.class,
                () -> isbnOnly.requireIdentifierConsistent(TrustTier.PEER_REVIEWED_PAPER),
                "只有 ISBN 时不应通过论文等级校验");

        SourceRegistration doiOnly = new SourceRegistration(
                "论文", "标题", "出版社", null, "10.1000/xyz", null, null);
        doiOnly.requireIdentifierConsistent(TrustTier.PEER_REVIEWED_PAPER);
        assertThrows(IllegalArgumentException.class,
                () -> doiOnly.requireIdentifierConsistent(TrustTier.OFFICIAL_TUTORIAL),
                "只有 DOI 时不应通过教材等级校验");
    }

    @Test
    @DisplayName("UNVERIFIED 等级对标识无要求")
    void unverifiedTierToleratesMissingIdentifier() {
        SourceRegistration reg = new SourceRegistration(
                "教材", "标题", "出版社", "9787100000000", null, null, null);
        reg.requireIdentifierConsistent(TrustTier.UNVERIFIED);
    }

    @Test
    @DisplayName("校验决定必须带证据与决定人（RAG-D07 §2.5 留痕要求）")
    void decisionRequiresEvidenceAndDecider() {
        assertThrows(IllegalArgumentException.class, () -> new VerificationDecision(
                VerificationStatus.VERIFIED, "", "reviewer-1",
                VerificationMethod.ISBN, TrustTier.OFFICIAL_PUBLISHER, 365),
                "证据为空必须被拒");
        assertThrows(IllegalArgumentException.class, () -> new VerificationDecision(
                VerificationStatus.VERIFIED, "ISBN:ref@2026-01-01", "",
                VerificationMethod.ISBN, TrustTier.OFFICIAL_PUBLISHER, 365),
                "决定人为空必须被拒");
    }

    @Test
    @DisplayName("判定 VERIFIED 必须同时给出权威等级（否则审核形同虚设）")
    void approveRequiresTrustTier() {
        assertThrows(IllegalArgumentException.class, () -> new VerificationDecision(
                VerificationStatus.VERIFIED, "ISBN:ref@2026-01-01", "reviewer-1",
                VerificationMethod.ISBN, null, 365));
        VerificationDecision d = VerificationDecision.approve(
                TrustTier.OFFICIAL_PUBLISHER, VerificationMethod.ISBN,
                "ISBN:ref@2026-01-01", "reviewer-1", 365);
        assertEquals(TrustTier.OFFICIAL_PUBLISHER, d.trustTier());
    }

    @Test
    @DisplayName("拒绝与撤回不需要权威等级")
    void rejectAndRevokeDoNotNeedTrustTier() {
        assertNull(VerificationDecision.reject("疑似盗版", "reviewer-1").trustTier());
        assertNull(VerificationDecision.revoke("权利人通知", "legal-1").trustTier());
    }

    @Test
    @DisplayName("有效天数必须为正")
    void validDaysMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new VerificationDecision(
                VerificationStatus.IN_REVIEW, "evidence", "reviewer-1",
                VerificationMethod.NONE, null, 0));
    }

    @Test
    @DisplayName("查询条件对 limit / offset 的边界校验")
    void queryValidatesBounds() {
        assertThrows(IllegalArgumentException.class,
                () -> new SourceQuery(null, null, null, false, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new SourceQuery(null, null, null, false, 10, -1));
        assertThrows(IllegalArgumentException.class,
                () -> new SourceQuery(null, null, null, false, 501, 0),
                "limit 超过 500 必须被拒（防止全表拉取）");
        assertNotNull(SourceQuery.of(VerificationStatus.PENDING));
    }
}
