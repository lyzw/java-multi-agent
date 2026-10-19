package cn.studykid.multiagent.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.compliance.DataClassification;
import cn.studykid.multiagent.core.compliance.ModelProviders;
import cn.studykid.multiagent.core.compliance.ZeroRetentionGate;
import cn.studykid.multiagent.core.compliance.ZeroRetentionRegistry;
import cn.studykid.multiagent.core.compliance.ZeroRetentionStatus;
import cn.studykid.multiagent.core.compliance.ZeroRetentionViolationException;

/**
 * 零保留闸门测试（DEC-10 GATE-1/2 / R-017）。
 *
 * <p><b>这是 Sev-0 级防护的测试</b>：闸门失效意味着未成年人数据
 * 出境或被用于训练，且不可撤回。除正向用例外必须包含<b>证伪尝试</b>
 * ——主动尝试绕过，确认绕过不了。
 *
 * <p><b>供应商维度</b>：确认状态按供应商分别持有。
 * 核心风险是「用 A 家的确认放行 B 家的调用」，故测试须覆盖该场景。
 */
class ZeroRetentionGateTest {

    private static final Instant NOW = Instant.parse("2026-10-19T02:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String EVIDENCE = "合同 SUP-2026-001 / 条款 8.3";

    // ── 闸门构造辅助 ────────────────────────────────────────────────

    /** 全空注册表：所有供应商均未确认（GATE-1 全面生效）。 */
    private static ZeroRetentionGate gateWithNothingConfirmed() {
        return new ZeroRetentionGate(ZeroRetentionRegistry.empty(), CLOCK);
    }

    /** 仅 deepseek 已确认，另两家未确认 —— 贴近当前实际情况。 */
    private static ZeroRetentionGate gateWithDeepseekConfirmed() {
        return new ZeroRetentionGate(ZeroRetentionRegistry.builder()
                .confirmed(ModelProviders.DEEPSEEK, EVIDENCE,
                        NOW.minus(Duration.ofDays(30)), Duration.ofDays(365))
                .pending(ModelProviders.MINIMAX)
                .pending(ModelProviders.WORKBUDDY)
                .build(), CLOCK);
    }

    /** 三家全部确认。 */
    private static ZeroRetentionGate gateWithAllConfirmed() {
        return new ZeroRetentionGate(ZeroRetentionRegistry.builder()
                .confirmed(ModelProviders.DEEPSEEK, EVIDENCE, NOW.minus(Duration.ofDays(30)),
                        Duration.ofDays(365))
                .confirmed(ModelProviders.MINIMAX, EVIDENCE, NOW.minus(Duration.ofDays(30)),
                        Duration.ofDays(365))
                .confirmed(ModelProviders.WORKBUDDY, EVIDENCE, NOW.minus(Duration.ofDays(30)),
                        Duration.ofDays(365))
                .build(), CLOCK);
    }

    /** 无有效期的确认（用于内容检测测试）。 */
    private static ZeroRetentionGate gateWithNoExpiry() {
        return new ZeroRetentionGate(ZeroRetentionRegistry.builder()
                .confirmedUntil(ModelProviders.DEEPSEEK, EVIDENCE, NOW, null)
                .build(), CLOCK);
    }

    // ── 测试 ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GATE-1：未确认时禁止真实学生数据")
    class Gate1Enforced {

        @Test
        @DisplayName("⚠️ 未确认 + 真实数据 → 拒绝")
        void realDataRejectedWhenPending() {
            var gate = gateWithNothingConfirmed();

            assertThat(catchThrowableOfType(
                    () -> gate.assertAllowed(ModelProviders.DEEPSEEK,
                            "解方程 x²-5x+6=0", DataClassification.REAL_STUDENT),
                    ZeroRetentionViolationException.class))
                    .isNotNull()
                    .satisfies(e -> assertThat(e.state())
                            .isEqualTo(ZeroRetentionStatus.State.PENDING));
        }

        @Test
        @DisplayName("✅ 未确认 + 合成数据 → 放行（P1 正常工作模式）")
        void syntheticDataAllowed() {
            assertThatCode(() -> gateWithNothingConfirmed().assertAllowed(
                    ModelProviders.DEEPSEEK, "解方程 x²-5x+6=0",
                    DataClassification.SYNTHETIC))
                    .as("P1 阶段必须能用合成数据开发")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("✅ 已确认 + 合成数据 → 放行（不受条款限制）")
        void syntheticDataAllowedEvenWhenConfirmed() {
            assertThatCode(() -> gateWithAllConfirmed().assertAllowed(
                    ModelProviders.DEEPSEEK, "任意内容", DataClassification.SYNTHETIC))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("⚠️ 供应商明确拒绝 → 永久拒绝")
        void rejectedProviderPermanentlyBlocked() {
            var gate = new ZeroRetentionGate(ZeroRetentionRegistry.builder()
                    .rejected(ModelProviders.MINIMAX)
                    .confirmed(ModelProviders.DEEPSEEK, EVIDENCE, NOW, Duration.ofDays(365))
                    .build(), CLOCK);

            assertThat(catchThrowableOfType(
                    () -> gate.assertAllowed(ModelProviders.MINIMAX, "题目",
                            DataClassification.REAL_STUDENT),
                    ZeroRetentionViolationException.class))
                    .isNotNull()
                    .satisfies(e -> assertThat(e.getMessage()).contains("拒绝"));
        }
    }

    @Nested
    @DisplayName("⚠️⚠️ 供应商隔离：不能用 A 家的确认放行 B 家的调用")
    class ProviderIsolation {

        @Test
        @DisplayName("⚠️ deepseek 已确认 → minimax 仍被拒")
        void confirmationDoesNotLeakAcrossProviders() {
            var gate = gateWithDeepseekConfirmed();

            assertThat(gate.allowsRealStudentData(ModelProviders.DEEPSEEK)).isTrue();

            assertThat(catchThrowableOfType(
                    () -> gate.assertAllowed(ModelProviders.MINIMAX, "题目内容",
                            DataClassification.REAL_STUDENT),
                    ZeroRetentionViolationException.class))
                    .as("⚠️ 零保留条款是按供应商签订的，跨供应商放行是合规漏洞")
                    .isNotNull();
        }

        @Test
        @DisplayName("⚠️ 未登记的供应商 → 拒绝（fail-closed）")
        void unregisteredProviderRejected() {
            var gate = gateWithAllConfirmed();

            assertThat(catchThrowableOfType(
                    () -> gate.assertAllowed("unknown-vendor", "题目",
                            DataClassification.REAL_STUDENT),
                    ZeroRetentionViolationException.class))
                    .as("新增供应商若未登记确认状态，必须自动拒绝而非放行")
                    .isNotNull();
        }

        @Test
        @DisplayName("✅ 三家全部确认 → 三家均可放行")
        void allConfirmedAllowAll() {
            var gate = gateWithAllConfirmed();
            for (String p : ModelProviders.REGISTERED) {
                assertThatCode(() -> gate.assertAllowed(p, "解方程 x²-5x+6=0",
                        DataClassification.REAL_STUDENT))
                        .as("供应商 %s 应放行", p)
                        .doesNotThrowAnyException();
            }
        }

        @Test
        @DisplayName("✅ 已登记供应商集合正确")
        void registeredProviders() {
            assertThat(ModelProviders.REGISTERED)
                    .containsExactlyInAnyOrder("deepseek", "minimax", "workbuddy");
            assertThat(ModelProviders.isRegistered("deepseek")).isTrue();
            assertThat(ModelProviders.isRegistered("openai")).isFalse();
        }
    }

    @Nested
    @DisplayName("GATE-2：条款过期自动失效")
    class ExpiryHandling {

        @Test
        @DisplayName("⚠️ 过期 → 拒绝")
        void expiredRejected() {
            var gate = new ZeroRetentionGate(ZeroRetentionRegistry.builder()
                    .confirmedUntil(ModelProviders.DEEPSEEK, EVIDENCE,
                            NOW.minus(Duration.ofDays(400)), NOW.minus(Duration.ofDays(1)))
                    .build(), CLOCK);

            assertThat(catchThrowableOfType(
                    () -> gate.assertAllowed(ModelProviders.DEEPSEEK, "题目",
                            DataClassification.REAL_STUDENT),
                    ZeroRetentionViolationException.class))
                    .isNotNull()
                    .satisfies(e -> assertThat(e.getMessage()).contains("过期"));
        }

        @Test
        @DisplayName("⚠️ 过期一纳秒即失效")
        void oneNanoPastExpiryInvalid() {
            Instant expiry = NOW;
            var status = ZeroRetentionStatus.pending()
                    .confirm("v", EVIDENCE, NOW.minus(Duration.ofDays(1)), expiry);
            assertThat(status.allowsRealStudentData(expiry)).isTrue();
            assertThat(status.allowsRealStudentData(expiry.plusNanos(1))).isFalse();
        }

        @Test
        @DisplayName("⚠️ 无证据出处不允许标记为已确认")
        void confirmationRequiresEvidence() {
            assertThat(org.assertj.core.api.Assertions.catchThrowableOfType(
                    () -> ZeroRetentionStatus.pending().confirm("vendor", "  ", NOW, null),
                    IllegalArgumentException.class))
                    .as("口头承诺不构成合规证据")
                    .isNotNull()
                    .satisfies(e -> assertThat(e.getMessage()).contains("证据"));
        }

        @Test
        @DisplayName("✅ 临期可被识别以便告警")
        void expiringSoonDetected() {
            var registry = ZeroRetentionRegistry.builder()
                    .confirmedUntil(ModelProviders.DEEPSEEK, EVIDENCE,
                            NOW, NOW.plus(Duration.ofDays(10)))
                    .confirmedUntil(ModelProviders.MINIMAX, EVIDENCE,
                            NOW, NOW.plus(Duration.ofDays(365)))
                    .build();

            assertThat(registry.expiredOrExpiring(NOW, 30))
                    .as("30 天内到期应触发告警，避免业务突然中断")
                    .containsExactly(ModelProviders.DEEPSEEK);
        }
    }

    @Nested
    @DisplayName("⚠️ 证伪：即便已确认，Prompt 仍不得含可识别信息")
    class BypassAttempts {

        @Test
        @DisplayName("⚠️ 身份证号 → 即使已确认也拒绝")
        void idCardBlockedEvenWhenConfirmed() {
            assertThat(catchThrowableOfType(
                    () -> gateWithNoExpiry().assertAllowed(ModelProviders.DEEPSEEK,
                            "学生 110101200501011234 的错题：解方程",
                            DataClassification.REAL_STUDENT),
                    ZeroRetentionViolationException.class))
                    .isNotNull()
                    .satisfies(e -> assertThat(e.getMessage()).contains("身份证"));
        }

        @Test
        @DisplayName("⚠️ 手机号 → 即使已确认也拒绝")
        void mobileBlockedEvenWhenConfirmed() {
            assertThat(catchThrowableOfType(
                    () -> gateWithNoExpiry().assertAllowed(ModelProviders.DEEPSEEK,
                            "联系 13812345678 答疑", DataClassification.REAL_STUDENT),
                    ZeroRetentionViolationException.class))
                    .isNotNull();
        }

        @Test
        @DisplayName("⚠️ 学号 → 即使已确认也拒绝")
        void studentIdBlockedEvenWhenConfirmed() {
            assertThat(catchThrowableOfType(
                    () -> gateWithNoExpiry().assertAllowed(ModelProviders.DEEPSEEK,
                            "学号 20260101 的题目", DataClassification.REAL_STUDENT),
                    ZeroRetentionViolationException.class))
                    .isNotNull();
        }

        @Test
        @DisplayName("✅ 纯题目内容 → 放行（无误伤）")
        void cleanContentNotBlocked() {
            assertThatCode(() -> gateWithNoExpiry().assertAllowed(ModelProviders.DEEPSEEK,
                    "解方程 x²-5x+6=0，并说明求根公式",
                    DataClassification.REAL_STUDENT))
                    .as("正常数学题目不应被误判")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("✅ 短数字不被当作学号（避免误伤公式）")
        void shortNumbersNotFlagged() {
            assertThatCode(() -> gateWithNoExpiry().assertAllowed(ModelProviders.DEEPSEEK,
                    "计算 5 × 6 = 30，答案是 30", DataClassification.REAL_STUDENT))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("异常与异常消息卫生")
    class ExceptionHygiene {

        @Test
        @DisplayName("✅ 异常消息不含被拦截的学生内容")
        void exceptionDoesNotLeakData() {
            var gate = gateWithNothingConfirmed();
            String secret = "学生小明 13812345678 的错题";

            var e = catchThrowableOfType(
                    () -> gate.assertAllowed(ModelProviders.DEEPSEEK, secret,
                            DataClassification.REAL_STUDENT),
                    ZeroRetentionViolationException.class);

            assertThat(e).isNotNull();
            assertThat(e.getMessage())
                    .as("异常消息会进日志，不得包含被拦截的学生数据")
                    .doesNotContain("小明")
                    .doesNotContain("13812345678");
        }

        @Test
        @DisplayName("✅ 异常携带供应商便于归因")
        void exceptionCarriesProvider() {
            var gate = gateWithDeepseekConfirmed();

            var e = catchThrowableOfType(
                    () -> gate.assertAllowed(ModelProviders.MINIMAX, "题目",
                            DataClassification.REAL_STUDENT),
                    ZeroRetentionViolationException.class);

            assertThat(e).isNotNull();
            assertThat(e.providerId()).isEqualTo(ModelProviders.MINIMAX);
            assertThat(e.classification()).isEqualTo(DataClassification.REAL_STUDENT);
        }
    }

    @Nested
    @DisplayName("注册表行为")
    class RegistryBehavior {

        @Test
        @DisplayName("✅ 空注册表 allAllow 为 false")
        void emptyRegistryAllowsNothing() {
            assertThat(ZeroRetentionRegistry.empty().allAllow(NOW))
                    .as("空注册表意味着全部未确认，不得因「没有例外」而放行")
                    .isFalse();
        }

        @Test
        @DisplayName("✅ 部分确认时 allAllow 为 false")
        void partialConfirmationNotAll() {
            var registry = ZeroRetentionRegistry.builder()
                    .confirmed(ModelProviders.DEEPSEEK, EVIDENCE, NOW, Duration.ofDays(365))
                    .pending(ModelProviders.MINIMAX)
                    .build();
            assertThat(registry.allAllow(NOW)).isFalse();
        }

        @Test
        @DisplayName("✅ 未登记供应商查询返回 pending 而非 null")
        void unregisteredReturnsPending() {
            var status = ZeroRetentionRegistry.empty().statusOf("nobody");
            assertThat(status.state()).isEqualTo(ZeroRetentionStatus.State.PENDING);
            assertThat(status.allowsRealStudentData(NOW)).isFalse();
        }

        @Test
        @DisplayName("✅ 全部确认后 allAllow 为 true")
        void allConfirmedAllowsAll() {
            var registry = ZeroRetentionRegistry.builder()
                    .confirmed(ModelProviders.DEEPSEEK, EVIDENCE, NOW, Duration.ofDays(365))
                    .confirmed(ModelProviders.MINIMAX, EVIDENCE, NOW, Duration.ofDays(365))
                    .confirmed(ModelProviders.WORKBUDDY, EVIDENCE, NOW, Duration.ofDays(365))
                    .build();
            assertThat(registry.allAllow(NOW)).isTrue();
        }
    }
}