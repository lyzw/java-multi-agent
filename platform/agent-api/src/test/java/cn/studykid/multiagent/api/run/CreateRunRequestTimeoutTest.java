package cn.studykid.multiagent.api.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Run 创建请求的<b>时间预算</b>校验测试（多级 Agent v1.1 · FR-024 · MA-T09）。
 *
 * <p><b>⚠️ 本类存在的唯一理由</b>：{@code timeoutMs} 决定「根 Run 最多跑多久」，
 * 进而决定整棵委派调用树的时间上界。校验一旦松掉：
 * <ul>
 *   <li>{@code 0 / 负数} → Run 一创建就已超时，是个必然失败的僵尸 Run；</li>
 *   <li><b>无上限</b> → 客户端可以要一个「几乎无限」的 Run ——
 *       那与「不声明预算」没有区别，却多了一个「看起来有约束」的假象。</li>
 * </ul>
 */
class CreateRunRequestTimeoutTest {

    private static Map<String, Object> input() {
        return Map.of("question", "q");
    }

    @Test
    @DisplayName("✅ 未声明 timeoutMs → 不限时（不假装有上限）")
    void absentTimeoutMeansUnlimited() {
        var request = new CreateRunRequest("idem-1", "agent-1", input());

        assertThat(request.timeoutMs()).isNull();
    }

    @Test
    @DisplayName("✅ 合法 timeoutMs → 原样保留")
    void validTimeoutIsKept() {
        var request = new CreateRunRequest("idem-1", "agent-1", input(), null, 30_000L);

        assertThat(request.timeoutMs()).isEqualTo(30_000L);
    }

    @Test
    @DisplayName("⛔ timeoutMs = 0 → 拒绝（一创建就已超时）")
    void zeroTimeoutIsRejected() {
        assertThatThrownBy(() ->
                new CreateRunRequest("idem-1", "agent-1", input(), null, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeoutMs");
    }

    @Test
    @DisplayName("⛔ timeoutMs 为负 → 拒绝")
    void negativeTimeoutIsRejected() {
        assertThatThrownBy(() ->
                new CreateRunRequest("idem-1", "agent-1", input(), null, -1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeoutMs");
    }

    @Test
    @DisplayName("⛔ timeoutMs 超过硬上限 → 拒绝（不得出现「几乎无限」的 Run）")
    void oversizedTimeoutIsRejected() {
        assertThatThrownBy(() ->
                new CreateRunRequest("idem-1", "agent-1", input(), null,
                        CreateRunRequest.MAX_TIMEOUT_MS + 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeoutMs");
    }

    @Test
    @DisplayName("✅ timeoutMs 恰为硬上限 → 允许")
    void exactlyAtLimitIsAllowed() {
        var request = new CreateRunRequest("idem-1", "agent-1", input(), null,
                CreateRunRequest.MAX_TIMEOUT_MS);

        assertThat(request.timeoutMs()).isEqualTo(CreateRunRequest.MAX_TIMEOUT_MS);
    }
}
