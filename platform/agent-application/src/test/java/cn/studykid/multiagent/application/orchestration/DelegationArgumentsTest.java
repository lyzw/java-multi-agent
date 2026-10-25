package cn.studykid.multiagent.application.orchestration;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 委派参数口径测试（多级 Agent v1.1 · P2-MA-02）。
 *
 * <p>⚠️ <b>参数口径全仓只有一处</b>（{@link DelegationArguments}）：
 * 闸门分支与未来的其他入口都走它，避免「两处各写一遍规则」导致语义漂移。
 */
class DelegationArgumentsTest {

    @Test
    @DisplayName("正常参数 → 解析出目标与输入")
    void parsesTargetAndInput() {
        var parsed = DelegationArguments.parse(Map.of(
                "targetAgentId", "child-agent",
                "input", Map.of("question", "q")));

        assertThat(parsed.targetAgentId()).isEqualTo("child-agent");
        assertThat(parsed.input()).containsEntry("question", "q");
    }

    @Test
    @DisplayName("省略 input → 输入为空（不得报错，委派可以不带上下文）")
    void missingInputYieldsEmptyMap() {
        var parsed = DelegationArguments.parse(Map.of("targetAgentId", "child-agent"));

        assertThat(parsed.input()).isEmpty();
    }

    @Test
    @DisplayName("缺 targetAgentId → 拒绝")
    void missingTargetIsRejected() {
        assertThatThrownBy(() -> DelegationArguments.parse(Map.of("input", Map.of("q", "1"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targetAgentId");
    }

    @Test
    @DisplayName("空白 targetAgentId → 拒绝")
    void blankTargetIsRejected() {
        assertThatThrownBy(() -> DelegationArguments.parse(Map.of("targetAgentId", "   ")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("多传字段 → 拒绝（越界输入不得被静默忽略）")
    void extraFieldIsRejected() {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("targetAgentId", "child-agent");
        arguments.put("targetAgentVersion", 2);

        assertThatThrownBy(() -> DelegationArguments.parse(arguments))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targetAgentVersion");
    }

    @Test
    @DisplayName("input 不是对象 → 拒绝")
    void nonObjectInputIsRejected() {
        assertThatThrownBy(() -> DelegationArguments.parse(Map.of(
                "targetAgentId", "child-agent", "input", "not-an-object")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("input");
    }
}
