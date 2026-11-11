package cn.studykid.multiagent.ports.contextwiring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.ports.model.ModelMessage;
import cn.studykid.multiagent.ports.model.ModelRequest;

/**
 * M7-b 中性端口 DTO 契约（ADR-013 D38）。
 *
 * <p>⚠️ 本类锁死两个最容易被「看着对、其实错」的性质：
 * <ol>
 *   <li>关闭态 `passthrough` 必须返回传入 {@code ModelRequest} 的<b>同一引用</b>，
 *       且不得携带任何压缩行为数据（否则 enabled=false 会多报指标 / 换成本口径）；</li>
 *   <li>冻结身份（agentId / agentVersion / maxOutputTokens / fencingToken）缺一即拒，
 *       ⛔ 绝不退化成默认值——那是 [D4] 漂移与静默超窗的入口。</li>
 * </ol>
 */
class RequestAssemblyContractTest {

    private static ModelRequest legacy() {
        return new ModelRequest("model-x", "稳定前缀\n[裁剪说明]", "首轮输入", 0.3,
                512, Duration.ofSeconds(5), Map.of(), List.of(ModelMessage.user("首轮输入")));
    }

    private static RequestAssemblyInput input() {
        return new RequestAssemblyInput(
                "11111111-1111-1111-1111-111111111111", "run:42", "agent-x", 3,
                "model-x", "稳定前缀", Map.of("t", Map.of("type", "object")),
                "首轮输入", "", List.of(), List.of(), "", 512, 77L, -1L, null, 0,
                legacy());
    }

    @Test
    @DisplayName("✅ passthrough 返回 legacy 同一引用，且零行为数据（关闭等价构造级保证）")
    void passthroughIsIdentityAndBehaviorFree() {
        ModelRequest legacy = legacy();
        RequestAssemblyResult result = RequestAssemblyResult.passthrough(legacy);

        assertThat(result.request()).isSameAs(legacy);
        assertThat(result.assembled()).isFalse();
        assertThat(result.tier()).isZero();
        assertThat(result.fallback()).isFalse();
        assertThat(result.beforeTokens()).isZero();
        assertThat(result.afterTokens()).isZero();
        assertThat(result.compactionCount()).isZero();
        assertThat(result.checkpointDelta()).isNull();
        assertThat(result.droppedTokens()).isZero();
    }

    @Test
    @DisplayName("⛔ 关闭态携带任何压缩行为数据即拒（防止 enabled=false 多报指标）")
    void passthroughCannotCarryBehaviorData() {
        assertThatThrownBy(() -> new RequestAssemblyResult(
                legacy(), false, 2, false, 100, 50, 1, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RequestAssemblyResult(
                legacy(), false, 0, true, 100, 50, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("⛔ 装配态 afterTokens 为 0 即拒（预算口径不得空转）")
    void assembledResultRequiresPositiveAfterTokens() {
        assertThatThrownBy(() -> new RequestAssemblyResult(
                legacy(), true, 0, false, 0, 0, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("✅ droppedTokens 由 before-after 推导并 clamp 到 ≥0（W1 外置可能净增）")
    void droppedTokensIsClamped() {
        RequestAssemblyResult shrunk = new RequestAssemblyResult(
                legacy(), true, 2, false, 900, 300, 1, null);
        assertThat(shrunk.droppedTokens()).isEqualTo(600);

        RequestAssemblyResult grew = new RequestAssemblyResult(
                legacy(), true, 1, false, 100, 120, 1, null);
        assertThat(grew.droppedTokens()).isZero();
    }

    @Test
    @DisplayName("⛔ 冻结身份 / legacy 基线缺失即拒：不猜 agentVersion，不回退默认模型")
    void frozenIdentityFailsClosed() {
        assertThatThrownBy(() -> new RequestAssemblyInput(
                "", "run:42", "agent-x", 3, "model-x", "", Map.of(), "", "",
                List.of(), List.of(), "", 512, 77L, -1L, null, 0, legacy()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RequestAssemblyInput(
                "tenant", "run:42", "agent-x", 0, "model-x", "", Map.of(), "", "",
                List.of(), List.of(), "", 512, 77L, -1L, null, 0, legacy()))
                .as("agentVersion=0 不是合法固定 release")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RequestAssemblyInput(
                "tenant", "run:42", "agent-x", 1, "model-x", "", Map.of(), "", "",
                List.of(), List.of(), "", 0, 77L, -1L, null, 0, legacy()))
                .as("maxOutputTokens 缺失会让总窗口判定静默超窗")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RequestAssemblyInput(
                "tenant", "run:42", "agent-x", 1, "model-x", "", Map.of(), "", "",
                List.of(), List.of(), "", 512, -1L, -1L, null, 0, legacy()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RequestAssemblyInput(
                "tenant", "run:42", "agent-x", 1, "model-x", "", Map.of(), "", "",
                List.of(), List.of(), "", 512, 1L, -1L, null, 0, null))
                .as("缺关闭等价基线时绝不允许构造输入")
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("✅ 装配请求只消费 request()，不暴露任何可回写 legacy 的入口")
    void resultIsImmutableValue() {
        RequestAssemblyResult result = new RequestAssemblyResult(
                legacy(), true, 1, false, 100, 80, 1, null);
        assertThat(result.request().systemPrompt()).contains("稳定前缀");
        assertThat(result.request().systemPrompt()).doesNotContain("改写");
        assertThat(result.checkpointDelta()).isNull();
    }
}
