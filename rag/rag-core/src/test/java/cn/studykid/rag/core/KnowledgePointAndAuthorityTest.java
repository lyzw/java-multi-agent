package cn.studykid.rag.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import cn.studykid.rag.core.knowledge.KnowledgePoint;
import cn.studykid.rag.core.source.SourceVerification;
import cn.studykid.rag.core.source.TrustTier;

/**
 * 知识点本体（碎片化）与数据源权威性（准入闸门）不变式。
 */
class KnowledgePointAndAuthorityTest {

    @Nested
    @DisplayName("知识点本体")
    class KnowledgePointOntology {

        private final KnowledgePoint kp = new KnowledgePoint(
                "kp-quadratic", "数学", "八年级", "二次函数顶点坐标",
                List.of("抛物线顶点", "顶点式", "y=a(x-h)^2+k 的顶点"),
                List.of("kp-quadratic-formula"),
                List.of(new KnowledgePoint.Facet("难度", "中等")));

        @Test
        @DisplayName("规范名与别称均可命中——别名缺失会造成系统性漏召回")
        void matchesCanonicalNameAndAliases() {
            assertThat(kp.matches("二次函数顶点坐标")).isTrue();
            assertThat(kp.matches("抛物线顶点")).isTrue();
            assertThat(kp.matches("顶点式")).isTrue();
        }

        @Test
        @DisplayName("⚠️ 判定方向：文本包含名称才命中（长文本里找短名称）")
        void matchesWhenTextContainsNameOrAlias() {
            // ⚠️ 这三条是**方向判别用例**：
            // 修复前（名称.contains(文本)）第 1、2 条为 false、第 3 条为 true；
            // 修复后（文本.contains(名称)）恰好相反。
            // 二者不可能同时成立 → 这组用例能**唯一锁定方向**。
            //
            // 修复前之所以漏检：原用例集全是**全等**样例
            // （"二次函数顶点坐标" / "抛物线顶点" / "顶点式"），
            // 两种方向在这些样例上**结果完全相同** → 方向写反了也全绿。
            assertThat(kp.matches("抛物线顶点的坐标是 (h,k)。"))
                    .as("片段正文包含别名「抛物线顶点」→ 应挂上知识点")
                    .isTrue();
            assertThat(kp.matches("抛物线顶点式"))
                    .as("类 Javadoc 自述的例子：「抛物线顶点式」必须命中别名「抛物线顶点」")
                    .isTrue();
            assertThat(kp.matches("抛物线顶"))
                    .as("反向部分匹配不成立：短文本「抛物线顶」不得命中「抛物线顶点」")
                    .isFalse();
        }

        @Test
        @DisplayName("归一化消除空格与大小写差异")
        void normalizationHandlesWhitespaceAndCase() {
            assertThat(KnowledgePoint.normalize("  二次函数 顶点坐标 ")).isEqualTo("二次函数顶点坐标");
            assertThat(kp.matches(KnowledgePoint.normalize(" 抛物线顶点 "))).isTrue();
        }

        @Test
        @DisplayName("无关查询不命中")
        void doesNotMatchUnrelated() {
            assertThat(kp.matches("一元一次方程")).isFalse();
            assertThat(kp.matches("")).isFalse();
        }

        @Test
        @DisplayName("先修关系被保留——「因知识缺口答错」的诊断依据")
        void keepsPrerequisites() {
            assertThat(kp.prerequisites()).containsExactly("kp-quadratic-formula");
        }
    }

    @Nested
    @DisplayName("数据源权威性（准入闸门）")
    class Authority {

        @Test
        @DisplayName("未校验来源不可进入索引（fail-closed）")
        void unverifiedIsNotIndexable() {
            assertThat(SourceVerification.unverified().indexable()).isFalse();
        }

        @Test
        @DisplayName("VERIFIED 必须携带校验依据与时间——「已校验」须可复核")
        void verifiedRequiresEvidence() {
            assertThatThrownBy(() -> new SourceVerification(
                    SourceVerification.VerificationStatus.VERIFIED,
                    SourceVerification.VerificationMethod.ISBN, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("evidenceRef");

            assertThatThrownBy(() -> new SourceVerification(
                    SourceVerification.VerificationStatus.VERIFIED,
                    SourceVerification.VerificationMethod.ISBN, "isbn:978-1", null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("verifiedAt");
        }

        @Test
        @DisplayName("校验通过的来源可索引")
        void verifiedIsIndexable() {
            SourceVerification verification = new SourceVerification(
                    SourceVerification.VerificationStatus.VERIFIED,
                    SourceVerification.VerificationMethod.ISBN, "isbn:978-7", Instant.now(), "qa-1");
            assertThat(verification.indexable()).isTrue();
        }

        @Test
        @DisplayName("校验结论过期后须重新校验")
        void expiredVerificationIsStale() {
            SourceVerification old = new SourceVerification(
                    SourceVerification.VerificationStatus.VERIFIED,
                    SourceVerification.VerificationMethod.ISBN, "isbn:978-7",
                    Instant.now().minus(java.time.Duration.ofDays(400)), "qa-1");
            assertThat(old.isStale(365)).isTrue();

            SourceVerification fresh = new SourceVerification(
                    SourceVerification.VerificationStatus.VERIFIED,
                    SourceVerification.VerificationMethod.ISBN, "isbn:978-7",
                    Instant.now().minus(java.time.Duration.ofDays(10)), "qa-1");
            assertThat(fresh.isStale(365)).isFalse();
        }

        @Test
        @DisplayName("仅官方教材与正式教辅可单独支撑教学结论")
        void standaloneSupportRestricted() {
            assertThat(TrustTier.OFFICIAL_PUBLISHER.standaloneSupportAllowed()).isTrue();
            assertThat(TrustTier.OFFICIAL_TUTORIAL.standaloneSupportAllowed()).isTrue();
            assertThat(TrustTier.PEER_REVIEWED_PAPER.standaloneSupportAllowed()).isFalse();
            assertThat(TrustTier.UNVERIFIED.standaloneSupportAllowed()).isFalse();
        }

        @Test
        @DisplayName("权威等级可比较，未校验等级最低")
        void trustTierOrdering() {
            assertThat(TrustTier.OFFICIAL_PUBLISHER.meets(TrustTier.OFFICIAL_TUTORIAL)).isTrue();
            assertThat(TrustTier.UNVERIFIED.meets(TrustTier.OFFICIAL_TUTORIAL)).isFalse();
            assertThat(TrustTier.UNVERIFIED.weight()).isZero();
        }
    }
}
