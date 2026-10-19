package cn.studykid.multiagent.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.compliance.DataClassification;
import cn.studykid.multiagent.core.compliance.ZeroRetentionGate;
import cn.studykid.multiagent.core.compliance.ZeroRetentionStatus;
import cn.studykid.multiagent.core.compliance.ZeroRetentionViolationException;

/**
 * 零保留闸门测试（DEC-10 GATE-1 / R-017）。
 *
 * <p><b>这是 Sev-0 级防护的测试</b>：闸门失效意味着未成年人数据
 * 出境或被用于训练，且不可撤回。因此除正向用例外，
 * 必须包含<b>证伪尝试</b>——主动尝试绕过，确认绕过不了。
 */
class ZeroRetentionGateTest {

    private static final Instant NOW = Instant.parse("2026-10-19T02:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    /** 直接用内容字符串调用闸门（领域层不认识 ModelRequest）。 */
    private static String content(String userContent) {
        return userContent;
    }

    @Nested
    @DisplayName("GATE-1：未确认时禁止真实学生数据")
    class Gate1Enforced {

        @Test
        @DisplayName("⚠️ 未确认 + 真实数据 → 拒绝（GATE-1 生效中）")
        void realDataRejectedWhenPending() {
            var gate = new ZeroRetentionGate(ZeroRetentionStatus.pending(), CLOCK);

            assertThatThrownBy(() -> gate.assertAllowed(
                    content("解方程 x²-5x+6=0"), DataClassification.REAL_STUDENT))
                    .isInstanceOf(ZeroRetentionViolationException.class)
                    .hasMessageContaining("尚未确认");
        }

        @Test
        @DisplayName("✅ 未确认 + 合成数据 → 放行（P1 阶段正常工作模式）")
        void syntheticDataAllowed() {
            var gate = new ZeroRetentionGate(ZeroRetentionStatus.pending(), CLOCK);

            assertThatCode(() -> gate.assertAllowed(
                    content("解方程 x²-5x+6=0"), DataClassification.SYNTHETIC))
                    .as("P1 阶段必须能用合成数据开发")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("✅ gate1Active 在未确认时为 true")
        void gate1ActiveWhenPending() {
            assertThat(new ZeroRetentionGate(ZeroRetentionStatus.pending(), CLOCK).gate1Active())
                    .isTrue();
        }

        @Test
        @DisplayName("⚠️ 已拒绝的供应商 → 永久拒绝真实数据")
        void rejectedProviderPermanentlyBlocked() {
            var status = ZeroRetentionStatus.pending().reject("some-vendor");
            var gate = new ZeroRetentionGate(status, CLOCK);

            assertThatThrownBy(() -> gate.assertAllowed(
                    content("题目内容"), DataClassification.REAL_STUDENT))
                    .isInstanceOf(ZeroRetentionViolationException.class)
                    .hasMessageContaining("拒绝");

            // 即使后来被误传成 confirmed，也无法自行恢复
            assertThat(status.allowsRealStudentData(NOW)).isFalse();
        }
    }

    @Nested
    @DisplayName("GATE-2：已确认后放行真实数据")
    class Gate2Cleared {

        @Test
        @DisplayName("✅ 已确认 + 未过期 + 真实数据 → 放行")
        void confirmedAllowsRealData() {
            var status = ZeroRetentionStatus.pending().confirm(
                    "vendor-x", "合同条款 8.3 / 工单 SUP-12345",
                    NOW.minus(Duration.ofDays(1)),
                    NOW.plus(Duration.ofDays(365)));

            var gate = new ZeroRetentionGate(status, CLOCK);

            assertThatCode(() -> gate.assertAllowed(
                    content("解方程 x²-5x+6=0"), DataClassification.REAL_STUDENT))
                    .doesNotThrowAnyException();
            assertThat(gate.gate1Active()).isFalse();
        }

        @Test
        @DisplayName("⚠️ 确认已过期 → 拒绝（过期即失效）")
        void expiredConfirmationRejected() {
            var status = ZeroRetentionStatus.pending().confirm(
                    "vendor-x", "SUP-12345",
                    NOW.minus(Duration.ofDays(400)),
                    NOW.minus(Duration.ofDays(1)));

            var gate = new ZeroRetentionGate(status, CLOCK);

            assertThatThrownBy(() -> gate.assertAllowed(
                    content("题目"), DataClassification.REAL_STUDENT))
                    .isInstanceOf(ZeroRetentionViolationException.class)
                    .hasMessageContaining("过期");
        }

        @Test
        @DisplayName("⚠️ 无证据出处不允许标记为已确认")
        void confirmationRequiresEvidence() {
            assertThatThrownBy(() -> ZeroRetentionStatus.pending()
                    .confirm("vendor-x", "  ", NOW, null))
                    .as("口头承诺不构成合规证据，必须有可追溯出处")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("证据");
        }

        @Test
        @DisplayName("⚠️ 无供应商标识不允许标记为已确认")
        void confirmationRequiresProvider() {
            assertThatThrownBy(() -> ZeroRetentionStatus.pending()
                    .confirm("", "SUP-1", NOW, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("供应商标识");
        }

        @Test
        @DisplayName("✅ 无有效期的确认长期有效")
        void noExpiryMeansLongTerm() {
            var status = ZeroRetentionStatus.pending().confirm("vendor-x", "SUP-1", NOW, null);
            assertThat(status.allowsRealStudentData(NOW.plus(Duration.ofDays(3650)))).isTrue();
        }
    }

    @Nested
    @DisplayName("⚠️ 证伪尝试：即使已确认，Prompt 仍不得含可识别信息")
    class BypassAttempts {

        private ZeroRetentionGate confirmedGate() {
            return new ZeroRetentionGate(ZeroRetentionStatus.pending().confirm(
                    "vendor-x", "SUP-12345", NOW, null), CLOCK);
        }

        @Test
        @DisplayName("⚠️ 身份证号 → 即使已确认也拒绝")
        void idCardBlockedEvenWhenConfirmed() {
            var gate = confirmedGate();
            assertThatThrownBy(() -> gate.assertAllowed(
                    content("学生 110101200501011234 的错题：解方程"),
                    DataClassification.REAL_STUDENT))
                    .isInstanceOf(ZeroRetentionViolationException.class)
                    .hasMessageContaining("身份证");
        }

        @Test
        @DisplayName("⚠️ 手机号 → 即使已确认也拒绝")
        void mobileBlockedEvenWhenConfirmed() {
            var gate = confirmedGate();
            assertThatThrownBy(() -> gate.assertAllowed(
                    content("联系 13812345678 答疑"), DataClassification.REAL_STUDENT))
                    .isInstanceOf(ZeroRetentionViolationException.class)
                    .hasMessageContaining("手机号");
        }

        @Test
        @DisplayName("⚠️ 学号 → 即使已确认也拒绝")
        void studentIdBlockedEvenWhenConfirmed() {
            var gate = confirmedGate();
            assertThatThrownBy(() -> gate.assertAllowed(
                    content("学号 20260101 的题目"), DataClassification.REAL_STUDENT))
                    .isInstanceOf(ZeroRetentionViolationException.class)
                    .hasMessageContaining("学号");
        }

        @Test
        @DisplayName("✅ 纯题目内容 → 放行（无误伤）")
        void cleanContentNotBlocked() {
            var gate = confirmedGate();
            assertThatCode(() -> gate.assertAllowed(
                    content("解方程 x²-5x+6=0，并说明求根公式"),
                    DataClassification.REAL_STUDENT))
                    .as("正常的数学题目不应被误判为可识别信息")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("✅ 短数字不被当作学号（避免误伤公式）")
        void shortNumbersNotFlagged() {
            var gate = confirmedGate();
            assertThatCode(() -> gate.assertAllowed(
                    content("计算 5 × 6 = 30，答案是 30"), DataClassification.REAL_STUDENT))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("异常消息不含原始数据")
    class ExceptionHygiene {

        @Test
        @DisplayName("✅ 异常消息不含被拦截的学生内容")
        void exceptionDoesNotLeakData() {
            var gate = new ZeroRetentionGate(ZeroRetentionStatus.pending(), CLOCK);
            String secret = "学生小明 13812345678 的错题";

            var e = org.assertj.core.api.Assertions.catchThrowableOfType(
                    () -> gate.assertAllowed(content(secret), DataClassification.REAL_STUDENT),
                    ZeroRetentionViolationException.class);

            assertThat(e).isNotNull();
            assertThat(e.getMessage())
                    .as("异常消息会进日志，不得包含被拦截的学生数据")
                    .doesNotContain("小明")
                    .doesNotContain("13812345678");
        }

        @Test
        @DisplayName("✅ 异常携带状态便于归因")
        void exceptionCarriesState() {
            var gate = new ZeroRetentionGate(ZeroRetentionStatus.pending(), CLOCK);
            var e = org.assertj.core.api.Assertions.catchThrowableOfType(
                    () -> gate.assertAllowed(content("任意内容"), DataClassification.REAL_STUDENT),
                    ZeroRetentionViolationException.class);

            assertThat(e.state()).isEqualTo(ZeroRetentionStatus.State.PENDING);
            assertThat(e.classification()).isEqualTo(DataClassification.REAL_STUDENT);
        }
    }

    @Nested
    @DisplayName("状态判定边界")
    class StateBoundaries {

        @Test
        @DisplayName("✅ 恰好到期时刻仍视为有效")
        void exactlyAtExpiryStillValid() {
            var expiry = NOW;
            var status = ZeroRetentionStatus.pending().confirm("v", "SUP-1",
                    NOW.minus(Duration.ofDays(1)), expiry);
            assertThat(status.allowsRealStudentData(expiry)).isTrue();
        }

        @Test
        @DisplayName("⚠️ 过期一纳秒即失效")
        void oneNanoPastExpiryInvalid() {
            var expiry = NOW;
            var status = ZeroRetentionStatus.pending().confirm("v", "SUP-1",
                    NOW.minus(Duration.ofDays(1)), expiry);
            assertThat(status.allowsRealStudentData(expiry.plusNanos(1))).isFalse();
        }

        @Test
        @DisplayName("✅ 剩余天数计算")
        void daysUntilExpiry() {
            var status = ZeroRetentionStatus.pending().confirm("v", "SUP-1",
                    NOW, NOW.plus(Duration.ofDays(10)));
            assertThat(status.daysUntilExpiry(NOW)).contains(10L);
        }

        @Test
        @DisplayName("✅ 无有效期时剩余天数为空")
        void noExpiryNoDays() {
            var status = ZeroRetentionStatus.pending().confirm("v", "SUP-1", NOW, null);
            assertThat(status.daysUntilExpiry(NOW)).isEmpty();
        }

        @Test
        @DisplayName("⚠️ 放行决策不应携带拒绝原因")
        void allowedDecisionHasNoReason() {
            var status = ZeroRetentionStatus.pending().confirm("v", "SUP-1", NOW, null);
            assertThat(status.evaluate(NOW).allowed()).isTrue();
            assertThat(status.evaluate(NOW).reason()).isNull();
        }
    }
}