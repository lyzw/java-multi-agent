package cn.studykid.multiagent.worker.execute;

import java.util.function.Supplier;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.ports.orchestration.ChildAgentRunner;

/**
 * 延迟解析的子 Agent 执行器（多级 Agent v1.1 · 装配破环）。
 *
 * <p>⚠️ <b>为什么需要它</b>：依赖链是
 * {@code 闸门 → 委派执行器 → 子执行器 → 闸门}（子执行器要用闸门跑子 Agent 的工具），
 * 直接构造会形成<b>构造期环</b>，Spring 无法解析。
 * 本类把「取真实实现」推迟到<b>第一次真正委派</b>，
 * 那时闸门已经就绪，环自然解开。
 *
 * <p>⚠️ <b>不是「可选依赖」</b>：真实实现取不到时直接抛异常，
 * 委派随之失败 —— 绝不静默降级成「没有子执行能力也照样返回成功」。
 */
public class LazyChildAgentRunner implements ChildAgentRunner {

    private final Supplier<ChildAgentRunner> delegate;

    public LazyChildAgentRunner(@NonNull Supplier<ChildAgentRunner> delegate) {
        this.delegate = delegate;
    }

    @Override
    public @NonNull ChildAgentResult run(@NonNull ChildAgentRequest request) {
        ChildAgentRunner runner = delegate.get();
        if (runner == null) {
            throw new IllegalStateException("子 Agent 执行器未装配");
        }
        return runner.run(request);
    }
}
