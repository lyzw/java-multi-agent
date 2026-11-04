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

    // ------------------------------------------------------------------
    // 版本五轴（RAG-D00 RD-03「缺一轴不可入库」）
    //
    // ⚠️ 守三件事：
    //   1) 五轴齐全 → hasFullEdition 为真、出版社缺省回落来源出版社；
    //   2) **部分填写** → partiallyFilledEdition 报出缺失轴（不能静默退化）；
    //   3) 册次必须在白名单内（与 rag_edition.term 的 CHECK 同口径）。
    // ------------------------------------------------------------------

    /** 构造一条带完整五轴的登记命令。 */
    private static SourceRegistration withEdition(String subject, String grade, String term,
                                                  String revisionCode, String editionPublisher) {
        return new SourceRegistration(
                "教材", "数学 七年级上册", "人民教育出版社", "9787100000000", null, null, null,
                subject, grade, term, revisionCode, editionPublisher);
    }

    @Test
    @DisplayName("五轴齐全时 hasFullEdition 为真")
    void fullEditionDetected() {
        SourceRegistration reg = withEdition("数学", "七年级", "上册", "2024修订", "人教版");
        assertEquals(true, reg.hasFullEdition());
        assertNull(reg.partiallyFilledEdition(), "齐全时不应报缺失轴");
    }

    @Test
    @DisplayName("五轴全空时 hasFullEdition 为假（存量/批量路径不受影响）")
    void emptyEditionIsNotFull() {
        SourceRegistration reg = new SourceRegistration(
                "教材", "标题", "出版社", "9787100000000", null, null, null);
        assertEquals(false, reg.hasFullEdition());
        assertNull(reg.partiallyFilledEdition(), "全空不算部分填写，不应报错");
    }

    @Test
    @DisplayName("部分填写时报出缺失的轴（防止静默退化成默认坐标）")
    void partialEditionReportsMissingAxes() {
        SourceRegistration reg = withEdition("数学", null, "上册", null, "人教版");
        assertEquals(false, reg.hasFullEdition());
        String missing = reg.partiallyFilledEdition();
        assertNotNull(missing);
        assertEquals(true, missing.contains("年级"), "应报出缺失的年级轴：" + missing);
        assertEquals(true, missing.contains("版次"), "应报出缺失的版次轴：" + missing);
    }

    @Test
    @DisplayName("出版社轴缺省回落到来源出版社（登记页只有一个出版社输入）")
    void editionPublisherFallsBackToSourcePublisher() {
        SourceRegistration reg = withEdition("数学", "七年级", "上册", "2024修订", null);
        assertEquals("人民教育出版社", reg.effectiveEditionPublisher());
        assertEquals(true, reg.hasFullEdition(), "出版社回落后期望五轴齐全");
    }

    @Test
    @DisplayName("册次非法值被拦下（与 rag_edition.term 的 CHECK 同口径）")
    void illegalTermRejected() {
        IllegalArgumentException cause = assertThrows(IllegalArgumentException.class,
                () -> withEdition("数学", "七年级", "第一卷", "2024修订", "人教版"));
        assertEquals(true, cause.getMessage().contains("册次"));
    }

    @Test
    @DisplayName("五轴先归一再去校验（带空白的合法值不被误判）")
    void editionAxesAreTrimmedBeforeValidation() {
        SourceRegistration reg = withEdition(" 数学 ", " 七年级 ", " 上册 ", " 2024修订 ", " 人教版 ");
        assertEquals("数学", reg.subject());
        assertEquals("上册", reg.term());
        assertEquals(true, reg.hasFullEdition(), "归一后应判定为齐全");
    }

    // ------------------------------------------------------------------
    // 枚举归一会（防止"一条登记把检索打挂"）
    //
    // ⚠️ 检索期 subjectFromDb/gradeFromDb/publisherFromDb 用 displayName 比对，
    //    比对不上**直接抛异常** → 该校材任何检索 500。
    //    而这三列是 text，写入不做校验。故登记时必须归一。
    // ------------------------------------------------------------------

    @Test
    @DisplayName("版本体系合法值原样保留（人教版/数学/七年级 都在枚举内）")
    void legalEnumValuesKept() {
        SourceRegistration reg = withEdition("数学", "七年级", "上册", "2024修订", "人教版");
        assertEquals("数学", reg.normalizedEditionSubject());
        assertEquals("七年级", reg.normalizedEditionGrade());
        assertEquals("人教版", reg.normalizedEditionPublisher());
    }

    @Test
    @DisplayName("出版机构名归一为「其他」，不会让检索 500")
    void institutionNameFallsBackToOther() {
        // 「人民教育出版社」是出版机构，不是版本体系（人教版）；直接写入会让
        // publisherFromDb 抛 IllegalStateException，整条检索链路挂掉。
        SourceRegistration reg = withEdition("数学", "七年级", "上册", "2024修订", null);
        assertEquals("人民教育出版社", reg.effectiveEditionPublisher(), "原始值仍保留（可审计）");
        assertEquals("其他", reg.normalizedEditionPublisher(), "落库值必须归一");
    }

    @Test
    @DisplayName("非法学科/年级归一为兜底值（未指定 / 全学段）")
    void illegalSubjectAndGradeFallBack() {
        SourceRegistration reg = withEdition("体育", "十年级", "上册", "2024修订", "人教版");
        assertEquals("未指定", reg.normalizedEditionSubject());
        assertEquals("全学段", reg.normalizedEditionGrade());
    }

    @Test
    @DisplayName("全部五轴归一路径可被检索期安全反查（不抛异常）")
    void normalizedValuesAreAllResolvable() {
        SourceRegistration reg = withEdition("未知学科", "未知年级", "上册", "v1", "某出版社");
        // 三个归一值必须能在枚举中找到（这正是检索期反查做的事）
        assertEquals(true, java.util.Arrays.stream(
                cn.studykid.rag.core.version.EditionCoordinate.Subject.values())
                .anyMatch(v -> v.displayName().equals(reg.normalizedEditionSubject())));
        assertEquals(true, java.util.Arrays.stream(
                cn.studykid.rag.core.version.EditionCoordinate.GradeBand.values())
                .anyMatch(v -> v.displayName().equals(reg.normalizedEditionGrade())));
        assertEquals(true, java.util.Arrays.stream(
                cn.studykid.rag.core.version.EditionCoordinate.Publisher.values())
                .anyMatch(v -> v.displayName().equals(reg.normalizedEditionPublisher())));
    }
}
