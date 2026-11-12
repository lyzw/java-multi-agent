package cn.studykid.multiagent.ports.agentchat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.ports.agentchat.ConsoleBudgetPort.BudgetInput;
import cn.studykid.multiagent.ports.agentchat.ConsoleBudgetPort.BudgetResult;
import cn.studykid.multiagent.ports.model.ModelMessage;
import cn.studykid.multiagent.ports.model.ModelRequest;

/**
 * S3-M9 L3 ConsoleChat 预算中性端口的值对象契约（ADR-013 D52）。
 *
 * <p>⚠️ 本类只测「端口自己的不可变性」：关闭等价同一引用、行为数据不得混入关闭态、
 * 输入合法性不阻断开关闭态逐位等价。真正的 W1/W2 行为在 bootstrap adapter 行为测试里。
 */
class ConsoleBudgetPortTest {

    @Test
    @DisplayName("✅ passthrough 是 legacy 同一引用，且零 budgeted / token / tier 行为数据")
    void passthroughIsIdentityAndBehaviorFree() {
        ModelRequest legacy = legacy();

        BudgetResult result = BudgetResult.passthrough(legacy);

        assertThat(result.request()).as("⛔ 关闭态换了引用，逐位等价失去构造级保证")
                .isSameAs(legacy);
        assertThat(result.budgeted()).isFalse();
        assertThat(result.beforeTokens()).isZero();
        assertThat(result.afterTokens()).isZero();
        assertThat(result.tier()).as("⛔ L3 关闭态竟然报出 W1/W2 档位").isZero();
    }

    @Test
    @DisplayName("✅ BudgetInput 只 fail-closed 基线引用；数值合法性留给启用态 adapter")
    void budgetInputDefersNumericValidationToEnabledAdapter() {
        ModelRequest legacy = legacy();

        // ⚠️ 关闭态连 maxOutputTokens<=0 都必须原样透传，否则 enabled=false 时
        //    多出一个 400，与「没有压缩功能」不再逐位等价（[D11]）。
        BudgetInput zero = new BudgetInput(legacy, "  ", 0);
        BudgetInput negative = new BudgetInput(legacy, "console:test", -7);

        assertThat(zero.legacyRequest()).isSameAs(legacy);
        assertThat(zero.scopeKey()).isEqualTo(ConsoleBudgetPort.DEFAULT_SCOPE);
        assertThat(zero.maxOutputTokens()).isZero();
        assertThat(negative.scopeKey()).isEqualTo("console:test");
        assertThat(negative.maxOutputTokens()).isEqualTo(-7);

        assertThatThrownBy(() -> new BudgetInput(null, "scope", 512))
                .as("⛔ 缺 legacy 基线时允许构造，关闭态将空转 / NPE")
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("⛔ 关闭等价态携带任何行为数据即拒（多报 token / tier 属失真）")
    void passthroughCannotCarryBehaviorData() {
        assertThatThrownBy(() -> new BudgetResult(legacy(), false, 2, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BudgetResult(legacy(), false, 0, 2, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BudgetResult(legacy(), false, 0, 0, 1))
                .as("⛔ L3 关闭态出现 tier=1：违反 D49④ 只跑 W1/W2 的边界")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("⛔ budgeted 结果零计数 / 反向膨胀 / W3 档位一律拒绝（不空转、不越界）")
    void budgetedResultRejectsEmptyOrInflatedOrW3() {
        assertThatThrownBy(() -> new BudgetResult(rewritten(), true, 0, 0, 0))
                .as("⛔ budgeted=true 却 afterTokens=0：预算口径空转")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BudgetResult(rewritten(), true, 100, 120, 2))
                .as("⛔ 预算保护后反而变多，属静默失真")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BudgetResult(rewritten(), true, 100, 80, 3))
                .as("⛔ L3 出现 W3/HARD 档位：D49④ 只允许 W1/W2")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BudgetResult(rewritten(), true, -1, 80, 1))
                .as("⛔ token 计数为负")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("✅ budgeted 结果只冻结 request 与真实收缩数字，非上下文字段由调用方真源携带")
    void budgetedResultCarriesShrunkNumbers() {
        ModelRequest rewritten = rewritten();
        BudgetResult result = new BudgetResult(rewritten, true, 120, 80, 2);

        assertThat(result.request()).isSameAs(rewritten);
        assertThat(result.budgeted()).isTrue();
        assertThat(result.beforeTokens()).isEqualTo(120);
        assertThat(result.afterTokens()).isEqualTo(80);
        assertThat(result.tier()).isEqualTo(2);
    }

    private static ModelRequest legacy() {
        return new ModelRequest("model-m9-port", "稳定前缀", "首轮输入", 0.31,
                512, Duration.ofSeconds(7), Map.of("lookup", Map.of("type", "object")),
                List.of(ModelMessage.user("首轮输入")));
    }

    private static ModelRequest rewritten() {
        return new ModelRequest(legacy().modelId(), legacy().systemPrompt(),
                legacy().userContent(), legacy().temperature(), legacy().maxTokens(),
                legacy().timeout(), legacy().toolSchemas(),
                List.of(ModelMessage.user("预算后当前输入")));
    }
}
