package cn.studykid.multiagent.application.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.application.config.ConfigTighteningPolicy.ConfigRelaxationException;
import cn.studykid.multiagent.application.config.ConfigTighteningPolicy.ValueType;
import cn.studykid.multiagent.ports.persistence.ConfigOverridePort;

/**
 * 配置收紧策略单元测试（P4-D15 / F6 · **C5 的核心安全语义**）。
 *
 * <p><b>⚠️ 为什么不只靠 IT</b>：收紧判定是<b>安全门禁</b>——
 * 一旦失效就是「安全参数可被静默放宽」。这类逻辑必须有**不依赖数据库**的
 * 单元层保护，否则容器一停就等于失去防护验证。
 *
 * <p><b>⚠️ 本类测的是「判断」，不是「执行」</b>：
 * 真正端到端的落库/审计路径由 {@code ConfigOverrideRepositoryIT} 与
 * {@code ConfigOverrideEndpointIT} 覆盖。
 */
@DisplayName("P4-D15 配置收紧策略（C5）")
class ConfigTighteningPolicyTest {

    private static final String REASON = "试点期收紧上限以控制成本";

    @Nested
    @DisplayName("白名单边界")
    class Whitelist {

        @Test
        @DisplayName("✅ 目录内键可写")
        void writableKeysAccepted() {
            assertThatCode(() -> ConfigTighteningPolicy.validate(
                    "agent.run.max-steps", "10", "20", REASON, ValueType.INTEGER))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("⛔ 白名单外键一律拒绝")
        void nonWritableKeysRejected() {
            String[] blocked = {
                    "agent.tool.risk-level-map",     // L1 锁定：工具风险级别是代码事实
                    "agent.approval.approver-scope", // L1 锁定：DEC-6 合规边界
                    "auth.jwt.private-key",          // 敏感：绝不暴露写入口
                    "auth.jwt.token-ttl-seconds",    // L1 只读：改=全员掉线风险
                    "totally.made.up"                // 未知键
            };
            for (String key : blocked) {
                assertThatThrownBy(() -> ConfigTighteningPolicy.validate(
                        key, "x", null, REASON, ValueType.INTEGER))
                        .as("⛔ 不可写键被放行: " + key)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("不可写");
            }
        }

        @Test
        @DisplayName("⛔ 白名单不含任何敏感键（结构性防护）")
        void whitelistHasNoSecrets() {
            assertThat(ConfigOverridePort.WRITABLE.keySet())
                    .as("⛔ 可写目录混入敏感键 = 一旦加错即暴露写入口")
                    .noneMatch(k -> k.contains("private") || k.contains("secret")
                            || k.contains("password") || k.contains("credential"));
        }

        @Test
        @DisplayName("✅ L1 锁定键确实不在可写目录")
        void lockedKeysAbsent() {
            assertThat(ConfigOverridePort.WRITABLE)
                    .as("⛔ L1 锁定键出现在可写目录")
                    .doesNotContainKeys("agent.tool.risk-level-map",
                            "agent.approval.approver-scope");
        }
    }

    @Nested
    @DisplayName("上限类：只能变小")
    class UpperBound {

        @Test
        @DisplayName("✅ 收紧（变小）放行")
        void tightenAllowed() {
            assertThatCode(() -> ConfigTighteningPolicy.validate(
                    "agent.run.max-cost-per-run", "1.50", "2.00", REASON, ValueType.DECIMAL))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("✅ 相同值放行（幂等重写）")
        void sameValueAllowed() {
            assertThatCode(() -> ConfigTighteningPolicy.validate(
                    "agent.run.max-steps", "20", "20", REASON, ValueType.INTEGER))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("⛔ 放宽（变大）拒绝")
        void loosenRejected() {
            assertThatThrownBy(() -> ConfigTighteningPolicy.validate(
                    "agent.run.max-cost-per-run", "10.00", "2.00", REASON, ValueType.DECIMAL))
                    .isInstanceOf(ConfigRelaxationException.class);
        }

        @Test
        @DisplayName("✅ 无当前值时任何正值合法（首次写入不受限）")
        void noCurrentValueAllowsAny() {
            assertThatCode(() -> ConfigTighteningPolicy.validate(
                    "agent.run.max-steps", "999", null, REASON, ValueType.INTEGER))
                    .as("⛔ 首次写入被拒 = 无法建立任何收紧基线")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("⛔ 非法数值拒绝（不放行、不取默认值兜底）")
        void malformedNumberRejected() {
            assertThatThrownBy(() -> ConfigTighteningPolicy.validate(
                    "agent.run.max-steps", "abc", "20", REASON, ValueType.INTEGER))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("⛔ 负数拒绝（≤ 当前值，不是放宽，但会卡死 Run）")
        void negativeRejected() {
            assertThatThrownBy(() -> ConfigTighteningPolicy.validate(
                    "agent.run.max-steps", "-1", "20", REASON, ValueType.INTEGER))
                    .isInstanceOf(ConfigRelaxationException.class);
        }
    }

    @Nested
    @DisplayName("审批矩阵：单向棘轮")
    class Ratchet {

        @Test
        @DisplayName("✅ 按等级收紧放行")
        void tightenAllowed() {
            assertThatCode(() -> ConfigTighteningPolicy.validate(
                    "agent.approval.required-by-risk",
                    "READ=REQUIRED,WRITE_EXTERNAL=REQUIRED",
                    "READ=NOT_REQUIRED,WRITE_EXTERNAL=REQUIRED", REASON, ValueType.ENUM))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("✅ 仅收紧其中一个等级放行（其余保持现状）")
        void partialTightenAllowed() {
            assertThatCode(() -> ConfigTighteningPolicy.validate(
                    "agent.approval.required-by-risk",
                    "READ=REQUIRED",
                    "READ=OPTIONAL,WRITE_EXTERNAL=REQUIRED", REASON, ValueType.ENUM))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("⛔ 放松任一等级即拒绝（整体拒绝，不部分放行）")
        void anyRelaxationRejected() {
            assertThatThrownBy(() -> ConfigTighteningPolicy.validate(
                    "agent.approval.required-by-risk",
                    "READ=REQUIRED,WRITE_EXTERNAL=NOT_REQUIRED",
                    "READ=REQUIRED,WRITE_EXTERNAL=REQUIRED", REASON, ValueType.ENUM))
                    .as("⛔ 收紧一个等级同时放松另一个被放行 = 净效果是放松")
                    .isInstanceOf(ConfigRelaxationException.class);
        }

        @Test
        @DisplayName("✅ 保持不变放行")
        void unchangedAllowed() {
            assertThatCode(() -> ConfigTighteningPolicy.validate(
                    "agent.approval.required-by-risk",
                    "READ=REQUIRED,WRITE_EXTERNAL=REQUIRED",
                    "READ=REQUIRED,WRITE_EXTERNAL=REQUIRED", REASON, ValueType.ENUM))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("⛔ 非法等级名拒绝")
        void badLevelRejected() {
            assertThatThrownBy(() -> ConfigTighteningPolicy.validate(
                    "agent.approval.required-by-risk",
                    "SUPER_READ=REQUIRED", null, REASON, ValueType.ENUM))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("⛔ 非法要求值拒绝")
        void badRequirementRejected() {
            assertThatThrownBy(() -> ConfigTighteningPolicy.validate(
                    "agent.approval.required-by-risk",
                    "READ=MAYBE", null, REASON, ValueType.ENUM))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("✅ yml map 花括号形态可解析（回归：实测踩过的坑）")
        void curlyBraceFormParsed() {
            // ⚠️ 回归：当前值来自 ConfigReadService.declaredValueOf()，
            //    对嵌套 yml map 返回 Java Map.toString() 形态。
            assertThatCode(() -> ConfigTighteningPolicy.validate(
                    "agent.approval.required-by-risk",
                    "READ=REQUIRED,WRITE_EXTERNAL=REQUIRED",
                    "{READ=NOT_REQUIRED, WRITE_EXTERNAL=REQUIRED}", REASON, ValueType.ENUM))
                    .as("⛔ 花括号形态未解析 = 审批矩阵完全不可写")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("⛔ 花括号形态下放松仍被拒（解析真实参与比较）")
        void curlyBraceFormStillEnforcesRatchet() {
            assertThatThrownBy(() -> ConfigTighteningPolicy.validate(
                    "agent.approval.required-by-risk",
                    "READ=NOT_REQUIRED,WRITE_EXTERNAL=REQUIRED",
                    "{READ=REQUIRED, WRITE_EXTERNAL=REQUIRED}", REASON, ValueType.ENUM))
                    .as("⛔ 解析成功但比较失效 = 棘轮被绕过")
                    .isInstanceOf(ConfigRelaxationException.class);
        }
    }

    @Nested
    @DisplayName("CHG 变更理由（C7）")
    class Reason {

        @Test
        @DisplayName("⛔ 空理由拒绝")
        void emptyRejected() {
            assertThatThrownBy(() -> ConfigTighteningPolicy.validate(
                    "agent.run.max-steps", "10", "20", "", ValueType.INTEGER))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("理由");
        }

        @Test
        @DisplayName("⛔ 理由过短（<10 字）拒绝")
        void tooShortRejected() {
            assertThatThrownBy(() -> ConfigTighteningPolicy.validate(
                    "agent.run.max-steps", "10", "20", "太贵了", ValueType.INTEGER))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("⛔ 纯空白理由等同空（不算长度）")
        void blankRejected() {
            assertThatThrownBy(() -> ConfigTighteningPolicy.validate(
                    "agent.run.max-steps", "10", "20", "           ", ValueType.INTEGER))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("⛔ null 理由拒绝")
        void nullRejected() {
            assertThatThrownBy(() -> ConfigTighteningPolicy.validate(
                    "agent.run.max-steps", "10", "20", null, ValueType.INTEGER))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("⛔ 理由检查先于收紧检查（错误码不泄露当前配置值）")
        void reasonCheckedBeforeRelaxation() {
            // ⚠️ 顺序很重要：若先判收紧，攻击者可借不同错误码反推当前配置值。
            assertThatThrownBy(() -> ConfigTighteningPolicy.validate(
                    "agent.run.max-steps", "999", "20", "短", ValueType.INTEGER))
                    .as("⛔ 错误码泄露当前配置值")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("理由");
        }
    }

    @Nested
    @DisplayName("时长型（TTL）")
    class Duration {

        @Test
        @DisplayName("✅ 缩短 TTL 放行")
        void shortenAllowed() {
            assertThatCode(() -> ConfigTighteningPolicy.validate(
                    "agent.approval.ttl", "5m", "15m", REASON, ValueType.DURATION))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("⛔ 延长 TTL 拒绝")
        void extendRejected() {
            assertThatThrownBy(() -> ConfigTighteningPolicy.validate(
                    "agent.approval.ttl", "30m", "15m", REASON, ValueType.DURATION))
                    .as("⛔ 延长审批有效期 = 审批失效风险上升")
                    .isInstanceOf(ConfigRelaxationException.class);
        }

        @Test
        @DisplayName("✅ 支持多种时长单位（2h > 15m 应识别为延长）")
        void supportsUnits() {
            assertThatThrownBy(() -> ConfigTighteningPolicy.validate(
                    "agent.approval.ttl", "2h", "15m", REASON, ValueType.DURATION))
                    .as("⛔ 单位换算错误 = 保护强度判断失真")
                    .isInstanceOf(ConfigRelaxationException.class);
        }

        @Test
        @DisplayName("⛔ 非法时长格式拒绝")
        void malformedRejected() {
            assertThatThrownBy(() -> ConfigTighteningPolicy.validate(
                    "agent.approval.ttl", "很久", "15m", REASON, ValueType.DURATION))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}