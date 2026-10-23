package cn.studykid.rag.management.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.rag.core.source.SourceVerification.VerificationMethod;
import cn.studykid.rag.core.source.SourceVerification.VerificationStatus;
import cn.studykid.rag.core.source.TrustTier;

/**
 * 来源准入判定测试（RAG-D07 §2.3.1 G-1/G-2/G-3/G-4 + §3.4 过期语义）。
 */
class CorpusSourceTest {

    private static final Instant NOW = Instant.parse("2026-10-22T00:00:00Z");

    private static CorpusSource source(VerificationStatus status, TrustTier tier,
                                       String license, Instant verifiedAt, int validDays) {
        return new CorpusSource(
                "src-1", "教材", "人教版八年级上册数学", "人民教育出版社",
                "9787100000000", null, tier, status, VerificationMethod.ISBN,
                "ISBN:ref@2026-01-01", verifiedAt, "reviewer-1", validDays,
                license, "obj://book.pdf", "a".repeat(64),
                NOW.minus(100, ChronoUnit.DAYS), NOW);
    }

    @Test
    @DisplayName("VERIFIED + 未过期 + 达门槛 + 版权放行 → 准入")
    void admissibleWhenAllGatesPass() {
        CorpusSource s = source(VerificationStatus.VERIFIED, TrustTier.OFFICIAL_PUBLISHER,
                "LICENSED", NOW.minus(10, ChronoUnit.DAYS), 365);
        assertFalse(s.stale(NOW));
        assertTrue(s.admissible(TrustTier.OFFICIAL_TUTORIAL, NOW));
    }

    @Test
    @DisplayName("G-1：非 VERIFIED 一律不准入")
    void notVerifiedIsNotAdmissible() {
        for (VerificationStatus status : VerificationStatus.values()) {
            if (status == VerificationStatus.VERIFIED) {
                continue;
            }
            CorpusSource s = source(status, TrustTier.OFFICIAL_PUBLISHER,
                    "LICENSED", NOW.minus(10, ChronoUnit.DAYS), 365);
            assertFalse(s.admissible(TrustTier.UNVERIFIED, NOW),
                    status + " 状态不应准入（fail-closed）");
        }
    }

    @Test
    @DisplayName("G-2：校验结论过期即不准入（过期是计算属性，不是状态位）")
    void staleIsNotAdmissible() {
        CorpusSource expired = source(VerificationStatus.VERIFIED, TrustTier.OFFICIAL_PUBLISHER,
                "LICENSED", NOW.minus(400, ChronoUnit.DAYS), 365);
        assertTrue(expired.stale(NOW), "应判定为过期");
        assertFalse(expired.admissible(TrustTier.UNVERIFIED, NOW),
                "过期来源不得准入");
        // ⚠️ 关键：过期时状态位仍是 VERIFIED —— 证明「过期」确实是计算属性。
        assertEquals(VerificationStatus.VERIFIED, expired.verificationStatus());
    }

    @Test
    @DisplayName("G-3：权威等级未达租户门槛即不准入")
    void trustTierBelowTenantGateIsNotAdmissible() {
        CorpusSource s = source(VerificationStatus.VERIFIED, TrustTier.PREPRINT,
                "LICENSED", NOW.minus(10, ChronoUnit.DAYS), 365);
        assertFalse(s.admissible(TrustTier.OFFICIAL_TUTORIAL, NOW),
                "预印本不应通过官方教辅门槛");
        assertTrue(s.admissible(TrustTier.PREPRINT, NOW),
                "预印本应通过预印本门槛");
    }

    @Test
    @DisplayName("G-4：版权状态 PENDING / RESTRICTED 均不放行")
    void licenseMustPermitRetrieval() {
        for (String license : List.of("LICENSED", "FAIR_USE_QUOTATION")) {
            CorpusSource s = source(VerificationStatus.VERIFIED, TrustTier.OFFICIAL_PUBLISHER,
                    license, NOW.minus(10, ChronoUnit.DAYS), 365);
            assertTrue(s.licensePermitsRetrieval(), license + " 应放行");
        }
        for (String license : List.of("PENDING", "RESTRICTED")) {
            CorpusSource s = source(VerificationStatus.VERIFIED, TrustTier.OFFICIAL_PUBLISHER,
                    license, NOW.minus(10, ChronoUnit.DAYS), 365);
            assertFalse(s.licensePermitsRetrieval(), license + " 不应放行");
            assertFalse(s.admissible(TrustTier.UNVERIFIED, NOW),
                    license + " 版权状态不应准入");
        }
    }

    @Test
    @DisplayName("非 VERIFIED 状态一律视为「不可用」（stale 语义为不可信）")
    void nonVerifiedAlwaysStale() {
        for (VerificationStatus status : VerificationStatus.values()) {
            if (status == VerificationStatus.VERIFIED) {
                continue;
            }
            CorpusSource s = source(status, TrustTier.OFFICIAL_PUBLISHER,
                    "LICENSED", NOW.minus(10, ChronoUnit.DAYS), 365);
            assertTrue(s.stale(NOW), status + " 应视为不可用");
        }
    }

    @Test
    @DisplayName("VERIFIED 但缺 verifiedAt 视为过期（数据不完整即不可信）")
    void missingVerifiedAtIsStale() {
        CorpusSource s = source(VerificationStatus.VERIFIED, TrustTier.OFFICIAL_PUBLISHER,
                "LICENSED", null, 365);
        assertTrue(s.stale(NOW));
    }

    @Test
    @DisplayName("构造参数非法时立即失败（fail-fast）")
    void constructorRejectsInvalidInput() {
        assertThrows(IllegalArgumentException.class, () -> new CorpusSource(
                "", "教材", "标题", "出版社", null, null, TrustTier.UNVERIFIED,
                VerificationStatus.PENDING, VerificationMethod.NONE, null, null, null,
                365, "PENDING", null, null, NOW, NOW));
        assertThrows(IllegalArgumentException.class, () -> new CorpusSource(
                "src-1", "教材", "标题", "出版社", null, null, TrustTier.UNVERIFIED,
                VerificationStatus.PENDING, VerificationMethod.NONE, null, null, null,
                0, "PENDING", null, null, NOW, NOW),
                "validDays 必须 > 0（与 V1 的 CHECK 约束一致）");
    }
}
