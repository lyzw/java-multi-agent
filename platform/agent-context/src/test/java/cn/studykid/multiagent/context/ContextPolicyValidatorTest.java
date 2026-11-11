package cn.studykid.multiagent.context;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 压缩策略门禁（发布期 + 执行期共用实现 / 《方案》§3.2-B、§7.4）。
 *
 * <p>⚠️ 断言用<b>字面量</b>写期望值，绝不引用被测实现自己的常量
 * （项目铁律 #6：守卫不得引用被测实现的常量，否则两边一起改就永远绿）。
 */
class ContextPolicyValidatorTest {

    private static List<String> fields(Map<String, Object> context) {
        return ContextPolicyValidator.validate(context).stream()
                .map(ContextPolicyValidator.Violation::field).toList();
    }

    @Test
    @DisplayName("✅ 未声明 context / 空对象 = 走全局默认，旧 Agent 版本不受影响")
    void absentContextIsAllowed() {
        assertThat(ContextPolicyValidator.validate(null)).isEmpty();
        assertThat(ContextPolicyValidator.validate(Map.of())).isEmpty();
    }

    @Test
    @DisplayName("✅ 完整合法策略通过")
    void fullValidPolicyPasses() {
        Map<String, Object> valid = new HashMap<>();
        valid.put("enabled", true);
        valid.put("maxInputTokens", 16_000);
        valid.put("triggerRatio", 0.70);
        valid.put("targetRatio", 0.40);
        valid.put("reserveRatio", 0.10);
        valid.put("recentTurns", 6);
        valid.put("pinFirstUserTurn", true);
        valid.put("ragRatio", 0.30);
        valid.put("memoryRatio", 0.15);
        valid.put("summaryRatio", 0.25);
        valid.put("strategy", "TIERED");
        valid.put("summarizerModel", null);
        valid.put("summaryMaxTokens", 1_200);
        valid.put("summaryTimeoutMs", 8_000);
        valid.put("maxCompactionsPerRun", 3);
        valid.put("carryOver", "CLIENT");
        valid.put("checkpointTtlSeconds", 1_800);

        assertThat(ContextPolicyValidator.validate(valid)).isEmpty();
    }

    @Test
    @DisplayName("✅ 非法值六组 + 绕过前端直调的参数必须被拒")
    void illegalValuesAreRejected() {
        assertThat(fields(Map.of("triggerRatio", 1.2))).contains("triggerRatio");
        assertThat(fields(Map.of("reserveRatio", 0.5))).contains("reserveRatio");
        assertThat(fields(Map.of("recentTurns", 1))).contains("recentTurns");
        assertThat(fields(Map.of("strategy", "FULL"))).contains("strategy");
        assertThat(fields(Map.of("maxInputTokens", 3.5))).contains("maxInputTokens");
        assertThat(fields(Map.of("compressRatio", 0.7))).contains("compressRatio");
    }

    @Test
    @DisplayName("✅ 弹性池三项之和不得超过 0.9（单项均合法也要拒）")
    void elasticRatioSumIsBounded() {
        Map<String, Object> c = Map.of(
                "ragRatio", 0.6, "memoryRatio", 0.2, "summaryRatio", 0.2);

        assertThat(fields(c)).contains("ragRatio/memoryRatio/summaryRatio");
    }

    @Test
    @DisplayName("✅ targetRatio 必须严格小于 triggerRatio（RV-5：撤掉门禁即红）")
    void targetMustBeBelowTrigger() {
        assertThat(fields(Map.of("triggerRatio", 0.60, "targetRatio", 0.60)))
                .contains("targetRatio");
        assertThat(fields(Map.of("triggerRatio", 0.60, "targetRatio", 0.70)))
                .contains("targetRatio");
        assertThat(fields(Map.of("triggerRatio", 0.70, "targetRatio", 0.40)))
                .doesNotContain("targetRatio");
    }

    @Test
    @DisplayName("✅ 类型错误 fail-closed，不静默回落默认值")
    void wrongTypesAreRejected() {
        assertThat(fields(Map.of("enabled", "true"))).contains("enabled");
        assertThat(fields(Map.of("carryOver", "SERVER_FALLBACK"))).contains("carryOver");
        assertThat(fields(Map.of("summarizerModel", 42))).contains("summarizerModel");
    }
}
