package cn.studykid.rag.management.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 审核职责分离测试（V1.0.2 S3-2 / §5 R4 / §6.1 硬规则）。
 *
 * <p><b>⚠️ 这两条规则是治理从「制度」变「机制」的分界线</b>：
 * <ul>
 *   <li><b>审核员≠采集员</b>：自审意味着「同一人既采集又签发」，
 *       出了问题无法追责；</li>
 *   <li><b>双人复核</b>：VERIFIED 是语料可被学生检索的开关，
 *       单人可签发等于没有把关。</li>
 * </ul>
 * 本类逐条给出<b>反证</b>——每条规则都必须有一个「违反即拒绝」的用例。
 */
class SegregationOfDutiesTest {

    private static final String ALICE = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    private static final String BOB = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";

    private SegregationOfDuties.Actors actors(String actor, String registeredBy, String verifiedBy) {
        return new SegregationOfDuties.Actors(actor,
                SegregationOfDuties.PrincipalType.TENANT_USER, registeredBy, verifiedBy);
    }

    @Nested
    @DisplayName("① 审核员 ≠ 采集员")
    class NoSelfReview {

        @Test
        @DisplayName("反证：同一人既采集又签发 → 拒绝")
        void selfReviewRejected() {
            var d = SegregationOfDuties.check(actors(ALICE, ALICE, BOB), true);
            assertFalse(d.allowed(), "自审必须被拒绝");
            assertTrue(d.reason().contains("不得审核自己采集"));
        }

        @Test
        @DisplayName("正向：不同人采集+审核+复核 → 允许")
        void differentPeopleAllowed() {
            var d = SegregationOfDuties.check(actors(BOB, ALICE, "cccccccc-cccc-4ccc-8ccc-cccccccccccc"), true);
            assertTrue(d.allowed(), "拒绝原因：" + d.reason());
        }

        @Test
        @DisplayName("历史数据：来源无登记人记录时不阻断（但要留审计）")
        void noRegisteredByAllowed() {
            var d = SegregationOfDuties.check(actors(ALICE, null, BOB), true);
            assertTrue(d.allowed(), "无登记人记录是历史数据，不应阻断：" + d.reason());
        }
    }

    @Nested
    @DisplayName("② 双人复核")
    class DualReview {

        @Test
        @DisplayName("反证：缺少复核人 → 拒绝")
        void missingReviewerRejected() {
            var d = SegregationOfDuties.check(actors(ALICE, null, null), true);
            assertFalse(d.allowed(), "VERIFIED 必须双人复核");
            assertTrue(d.reason().contains("双人复核"));
        }

        @Test
        @DisplayName("反证：复核人 = 执行人 → 拒绝")
        void sameReviewerRejected() {
            var d = SegregationOfDuties.check(actors(ALICE, null, ALICE), true);
            assertFalse(d.allowed(), "复核人不能是执行人本人");
            assertTrue(d.reason().contains("同一人"));
        }

        @Test
        @DisplayName("正向：两个不同主体 → 允许")
        void twoDistinctAllowed() {
            var d = SegregationOfDuties.check(actors(ALICE, null, BOB), true);
            assertTrue(d.allowed(), "拒绝原因：" + d.reason());
        }

        @Test
        @DisplayName("非 VERIFIED 的状态不要求双人复核")
        void nonVerifiedNoDualReview() {
            var d = SegregationOfDuties.check(actors(ALICE, null, null), false);
            assertTrue(d.allowed(), "IN_REVIEW/REJECTED 无需双人");
        }
    }

    @Nested
    @DisplayName("③ 身份完整性")
    class Identity {

        @Test
        @DisplayName("反证：缺主体 → 拒绝（不得匿名签发）")
        void anonymousRejected() {
            var d = SegregationOfDuties.check(
                    new SegregationOfDuties.Actors("", SegregationOfDuties.PrincipalType.TENANT_USER,
                            null, null), false);
            assertFalse(d.allowed());
            assertTrue(d.reason().contains("匿名"));
        }

        @Test
        @DisplayName("反证：主体类型不可识别 → 拒绝")
        void unknownTypeRejected() {
            var d = SegregationOfDuties.check(
                    new SegregationOfDuties.Actors(ALICE, SegregationOfDuties.PrincipalType.UNKNOWN,
                            null, null), false);
            assertFalse(d.allowed());
            assertTrue(d.reason().contains("principal_type"));
        }

        @Test
        @DisplayName("principal_type 解析")
        void parseType() {
            assertEquals(SegregationOfDuties.PrincipalType.PLATFORM_USER,
                    SegregationOfDuties.parsePrincipalType("PLATFORM_USER"));
            assertEquals(SegregationOfDuties.PrincipalType.TENANT_USER,
                    SegregationOfDuties.parsePrincipalType("tenant"));
            assertEquals(SegregationOfDuties.PrincipalType.UNKNOWN,
                    SegregationOfDuties.parsePrincipalType(null));
        }
    }

    @Nested
    @DisplayName("④ 角色授权")
    class Roles {

        @Test
        @DisplayName("反证：采集员角色 → 不得签发 VERIFIED")
        void collectorCannotVerify() {
            var d = SegregationOfDuties.checkRole(Set.of("TENANT_COLLECTOR"), true);
            assertFalse(d.allowed(), "采集员不得签发审核结论");
            assertTrue(d.reason().contains("无权签发"));
        }

        @Test
        @DisplayName("正向：审核员角色 → 允许")
        void reviewerCanVerify() {
            assertTrue(SegregationOfDuties.checkRole(Set.of("TENANT_REVIEWER"), true).allowed());
            assertTrue(SegregationOfDuties.checkRole(Set.of("PLATFORM_AUDITOR"), true).allowed());
        }

        @Test
        @DisplayName("非 VERIFIED 不看角色")
        void nonVerifiedAnyRole() {
            assertTrue(SegregationOfDuties.checkRole(Set.of("TENANT_COLLECTOR"), false).allowed());
        }

        @Test
        @DisplayName("正向：治理票据专用角色 → 允许（否则平台全局语料永远签不出 VERIFIED）")
        void governanceTicketRoleCanVerify() {
            // ⚠️ 回归钉：票据按设计不带持有者真实角色，只带这一格专用角色。
            //    若这条不成立，会出现「能拿票据的人没角色、有角色的人拿不到票据」的死锁。
            assertTrue(SegregationOfDuties.checkRole(
                    Set.of(SegregationOfDuties.RAG_GOVERNANCE_ROLE), true).allowed(),
                    "治理票据专用角色必须能签发 VERIFIED");
            // 契约名与平台侧同名同义（两处各存一份，改一处不改另一处会静默失效）
            assertEquals("RAG_CORPUS_GOVERNANCE", SegregationOfDuties.RAG_GOVERNANCE_ROLE);
        }

        @Test
        @DisplayName("反证：真实平台角色不复位成「万能」——票据只带这一格，不带 PLATFORM_*")
        void ticketRoleIsNotAPlatformRole() {
            // 只持有治理票据角色的令牌，不应当被当作审核员以外的任何身份；
            // 这里钉住「它单独存在就足够签字」而「不含平台角色名也可行」。
            assertTrue(SegregationOfDuties.checkRole(
                    Set.of("RAG_CORPUS_GOVERNANCE"), true).allowed());
            assertFalse(SegregationOfDuties.checkRole(Set.of("PLATFORM_SUPPORT"), true).allowed(),
                    "支持角色仍然不得签发审核结论");
        }
    }
}
