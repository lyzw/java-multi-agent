package cn.studykid.multiagent.core.cap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 风险等级序比较与未知风险处置（§5.6 / §4.2 · P1-T4）。
 */
class CapabilityRiskLevelTest {

    @Test
    @DisplayName("✅ 序关系：READ < WRITE_PROFILE < WRITE_EXTERNAL（§5.6）")
    void riskOrderFollowsDesignContract() {
        assertThat(CapabilityRiskLevel.READ.rank())
                .isLessThan(CapabilityRiskLevel.WRITE_PROFILE.rank());
        assertThat(CapabilityRiskLevel.WRITE_PROFILE.rank())
                .isLessThan(CapabilityRiskLevel.WRITE_EXTERNAL.rank());
    }

    @Test
    @DisplayName("✅ 超出判定：只有严格更高才算超限")
    void exceedsOnlyWhenStrictlyHigher() {
        assertThat(CapabilityRiskLevel.WRITE_EXTERNAL.exceeds(CapabilityRiskLevel.READ)).isTrue();
        assertThat(CapabilityRiskLevel.WRITE_EXTERNAL.exceeds(CapabilityRiskLevel.WRITE_EXTERNAL))
                .isFalse();
        assertThat(CapabilityRiskLevel.READ.exceeds(CapabilityRiskLevel.WRITE_EXTERNAL)).isFalse();
        assertThat(CapabilityRiskLevel.READ.exceeds(CapabilityRiskLevel.READ)).isFalse();
    }

    @Test
    @DisplayName("⛔ 非法/缺失风险等级解析为 UNKNOWN，**不降级为 WRITE_EXTERNAL**")
    void unknownRiskIsNeverDowngradedToHighest() {
        // ⚠️ 这是 P1 退出门禁的明文要求：未知风险必须能单独成码
        //    （RISK_LEVEL_UNKNOWN），否则它会与 RISK_EXCEEDS_CEILING 混为一谈，
        //    「配置缺陷」会被误报成「策略边界」。
        assertThat(CapabilityRiskLevel.parse(null)).isEqualTo(CapabilityRiskLevel.UNKNOWN);
        assertThat(CapabilityRiskLevel.parse("")).isEqualTo(CapabilityRiskLevel.UNKNOWN);
        assertThat(CapabilityRiskLevel.parse("  ")).isEqualTo(CapabilityRiskLevel.UNKNOWN);
        assertThat(CapabilityRiskLevel.parse("SUPER_DANGEROUS"))
                .isEqualTo(CapabilityRiskLevel.UNKNOWN);
        assertThat(CapabilityRiskLevel.parse("read"))
                .isEqualTo(CapabilityRiskLevel.READ);
    }

    @Test
    @DisplayName("⛔ 未知风险不参与序比较（既不算超限，也不算未超限）")
    void unknownDoesNotCompare() {
        assertThat(CapabilityRiskLevel.UNKNOWN.exceeds(CapabilityRiskLevel.WRITE_EXTERNAL))
                .as("⛔ 未知风险被判为超限：会把「配置缺陷」误报成「风险超限」")
                .isFalse();
        assertThat(CapabilityRiskLevel.WRITE_EXTERNAL.exceeds(CapabilityRiskLevel.UNKNOWN))
                .as("⛔ 未知上限下放行：等于把「不可判定」当成「未超限」")
                .isFalse();
        assertThat(CapabilityRiskLevel.UNKNOWN.isUnknown()).isTrue();
    }

    @Test
    @DisplayName("✅ 取更严上限：覆盖只能收紧，不能放宽")
    void stricterNeverLoosens() {
        // ⚠️ 上限约束的是「允许的最大风险」，故**等级更低者更严格**
        assertThat(CapabilityRiskLevel.stricter(CapabilityRiskLevel.READ,
                CapabilityRiskLevel.WRITE_EXTERNAL))
                .isEqualTo(CapabilityRiskLevel.READ);
        assertThat(CapabilityRiskLevel.stricter(CapabilityRiskLevel.WRITE_EXTERNAL,
                CapabilityRiskLevel.READ))
                .as("⛔ 取了更宽松的上限：收紧语义失效")
                .isEqualTo(CapabilityRiskLevel.READ);
        assertThat(CapabilityRiskLevel.stricter(CapabilityRiskLevel.UNKNOWN,
                CapabilityRiskLevel.READ))
                .as("⛔ 未知上限被当成可忽略：上限不明确时不得按宽松处理")
                .isEqualTo(CapabilityRiskLevel.UNKNOWN);
    }
}
