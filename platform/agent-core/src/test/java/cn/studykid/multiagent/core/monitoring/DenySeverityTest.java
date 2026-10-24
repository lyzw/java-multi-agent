package cn.studykid.multiagent.core.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import cn.studykid.multiagent.core.cap.ReasonCode;

/**
 * 拒绝分档测试（P4 运行监控 · core 领域模型）。
 *
 * <p><b>本类验证什么</b>：
 * <ul>
 *   <li><b>穷尽性</b>：每个 {@link ReasonCode} 都有确定分档，无遗漏；</li>
 *   <li><b>分档语义</b>：安全类拒绝不得落 INFO（否则告警被静默吞掉）；</li>
 *   <li><b>记录不变量</b>：水位记录拒绝自相矛盾的计数组合。</li>
 * </ul>
 */
@DisplayName("P4 DenySeverity · 拒绝分档")
class DenySeverityTest {

    @ParameterizedTest
    @EnumSource(ReasonCode.class)
    @DisplayName("✅ 每个原因码都有确定分档（非 null）")
    void everyReasonCodeHasSeverity(ReasonCode code) {
        assertThat(DenySeverity.of(code)).isNotNull();
    }

    @Test
    @DisplayName("✅ 越权类拒绝 → CRITICAL（不得被降级为 INFO 静默吞掉）")
    void authzDeniesAreCritical() {
        assertThat(DenySeverity.of(ReasonCode.AUTHZ_STALE))
                .isEqualTo(DenySeverity.CRITICAL);
        assertThat(DenySeverity.of(ReasonCode.AUTHZ_ROLE_MISSING))
                .isEqualTo(DenySeverity.CRITICAL);
        assertThat(DenySeverity.of(ReasonCode.PARAM_POLICY_FAIL))
                .isEqualTo(DenySeverity.CRITICAL);
        assertThat(DenySeverity.of(ReasonCode.CONFIG_FINGERPRINT_STALE))
                .isEqualTo(DenySeverity.CRITICAL);
        assertThat(DenySeverity.of(ReasonCode.RISK_EXCEEDS_CEILING))
                .isEqualTo(DenySeverity.CRITICAL);
        assertThat(DenySeverity.of(ReasonCode.MCP_OP_NOT_ALLOWED))
                .isEqualTo(DenySeverity.CRITICAL);
    }

    @Test
    @DisplayName("✅ EXEC_UNKNOWN（副作用结果不确定，需人工对账）→ CRITICAL")
    void execUnknownIsCritical() {
        assertThat(DenySeverity.of(ReasonCode.EXEC_UNKNOWN))
                .isEqualTo(DenySeverity.CRITICAL);
    }

    @Test
    @DisplayName("✅ 声明/漂移类拒绝 → WARNING")
    void declarationDeniesAreWarning() {
        assertThat(DenySeverity.of(ReasonCode.SKILL_NOT_ENABLED))
                .isEqualTo(DenySeverity.WARNING);
        assertThat(DenySeverity.of(ReasonCode.TOOL_NOT_DECLARED))
                .isEqualTo(DenySeverity.WARNING);
        assertThat(DenySeverity.of(ReasonCode.RESOURCE_UNKNOWN))
                .isEqualTo(DenySeverity.WARNING);
    }

    @Test
    @DisplayName("✅ 业务节流类拒绝 → INFO")
    void businessDeniesAreInfo() {
        assertThat(DenySeverity.of(ReasonCode.BUDGET_EXCEEDED))
                .isEqualTo(DenySeverity.INFO);
        assertThat(DenySeverity.of(ReasonCode.APPROVAL_EXPIRED))
                .isEqualTo(DenySeverity.INFO);
        assertThat(DenySeverity.of(ReasonCode.IDEMPOTENCY_CONFLICT))
                .isEqualTo(DenySeverity.INFO);
    }

    @Test
    @DisplayName("✅ 分档数量守恒：所有原因码恰好落入三档之一")
    void severityBucketsAreConsistent() {
        long critical = 0;
        long warning = 0;
        long info = 0;
        for (ReasonCode code : ReasonCode.values()) {
            switch (DenySeverity.of(code)) {
                case CRITICAL -> critical++;
                case WARNING -> warning++;
                case INFO -> info++;
            }
        }
        assertThat(critical + warning + info).isEqualTo(ReasonCode.values().length);
        assertThat(critical).isGreaterThan(0);
        assertThat(warning).isGreaterThan(0);
        assertThat(info).isGreaterThan(0);
    }

    // ── AuthzWatermark 不变量 ──────────────────────────────────────

    @Test
    @DisplayName("✅ 水位：被撤权主体数不得超过持有主体数")
    void watermarkRejectsInconsistentCounts() {
        assertThatThrownBy(() -> new AuthzWatermark(
                "t-1", 3L, 5L, 2L, null, java.time.Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("revokedSubjects");
    }

    @Test
    @DisplayName("✅ 水位：无任何记录是合法初始状态（零值快照）")
    void watermarkAllowsEmptyTenant() {
        AuthzWatermark watermark = new AuthzWatermark(
                "t-1", 0L, 0L, 0L, null, java.time.Instant.now());
        assertThat(watermark.hasRevocations()).isFalse();
    }

    @Test
    @DisplayName("✅ 水位：发生过撤权则 hasRevocations 为真")
    void watermarkDetectsRevocations() {
        AuthzWatermark watermark = new AuthzWatermark(
                "t-1", 4L, 2L, 7L, java.time.Instant.now(), java.time.Instant.now());
        assertThat(watermark.hasRevocations()).isTrue();
    }

    // ── DenyAlert 不变量 ──────────────────────────────────────────

    @Test
    @DisplayName("⛔ 告警：invocationId 不得为空")
    void alertRejectsBlankInvocationId() {
        assertThatThrownBy(() -> new DenyAlert(
                DenySeverity.INFO, ReasonCode.SUCCESS, null, null, null, "  ",
                java.time.Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invocationId");
    }

    @Test
    @DisplayName("✅ 告警：isCritical 与严重级别一致")
    void alertCriticalFlag() {
        DenyAlert critical = new DenyAlert(
                DenySeverity.CRITICAL, ReasonCode.AUTHZ_STALE, "p-1", "t-1", "r-1",
                "inv-1", java.time.Instant.now());
        assertThat(critical.isCritical()).isTrue();
    }
}
