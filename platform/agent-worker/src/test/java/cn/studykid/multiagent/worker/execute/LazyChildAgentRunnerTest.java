package cn.studykid.multiagent.worker.execute;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.cap.ExecutionScope;
import cn.studykid.multiagent.core.orchestration.AgentInvocationStatus;
import cn.studykid.multiagent.ports.orchestration.ChildAgentRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 延迟解析的子执行器测试（多级 Agent v1.1 · 装配破环）。
 *
 * <p>⚠️ <b>为什么有破环这回事</b>：依赖链是
 * {@code 闸门 → 委派执行器 → 子执行器 → 闸门}（子执行器要用闸门跑子 Agent 的工具）。
 * 直接构造会形成<b>构造期环</b>，Spring 无法解析；
 * 本类把「取真实实现」推迟到第一次真正委派。
 *
 * <p>⚠️ <b>不是「可选依赖」</b>：真实实现取不到时必须抛异常，
 * 委派随之失败 —— 绝不静默降级成「没有子执行能力也照样返回成功」。
 */
class LazyChildAgentRunnerTest {

    private static ChildAgentRunner.ChildAgentRequest request() {
        return new ChildAgentRunner.ChildAgentRequest(
                "t-a", "run-1", "inv-1", "trace-1", "parent-agent", 1,
                "child-agent", 1, Map.<String, Object>of("question", "q"), 2,
                new ExecutionScope("child-agent", 1, "cap_fp_v1:child"), 30_000L, 1L, 1);
    }

    @Test
    @DisplayName("构造期不得解析真实实现（否则构造期环无法解开）")
    void constructionDoesNotResolveDelegate() {
        AtomicInteger resolutions = new AtomicInteger();

        new LazyChildAgentRunner(() -> {
            resolutions.incrementAndGet();
            return request -> ChildAgentRunner.ChildAgentResult.succeeded("ok");
        });

        assertThat(resolutions.get())
                .as("构造期解析会把「闸门 → 委派执行器 → 子执行器 → 闸门」的环提前引爆")
                .isZero();
    }

    @Test
    @DisplayName("真正委派时才解析并执行")
    void resolvesOnFirstDelegation() {
        AtomicInteger resolutions = new AtomicInteger();
        LazyChildAgentRunner runner = new LazyChildAgentRunner(() -> {
            resolutions.incrementAndGet();
            return request -> ChildAgentRunner.ChildAgentResult.succeeded("ok");
        });

        var result = runner.run(request());

        assertThat(resolutions.get()).isEqualTo(1);
        assertThat(result.status()).isEqualTo(AgentInvocationStatus.SUCCEEDED);
        assertThat(result.output()).isEqualTo("ok");
    }

    @Test
    @DisplayName("真实实现未装配 → 抛异常（不得静默返回成功）")
    void missingDelegateFailsLoudly() {
        LazyChildAgentRunner runner = new LazyChildAgentRunner(() -> null);

        assertThatThrownBy(() -> runner.run(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未装配");
    }

    @Test
    @DisplayName("真实实现抛出的异常不得被吞掉")
    void delegateFailurePropagates() {
        LazyChildAgentRunner runner = new LazyChildAgentRunner(() -> request -> {
            throw new IllegalStateException("子执行炸了");
        });

        assertThatThrownBy(() -> runner.run(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("子执行炸了");
    }
}
