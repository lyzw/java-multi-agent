package cn.studykid.multiagent.application.agentchataudit;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 当前请求的审计作用域（跨线程传递）。
 *
 * <p><b>⚠️ 必须是 {@link InheritableThreadLocal}，不能是普通 ThreadLocal</b>：
 * 两个 SSE 控制器都用 {@code Executors.newVirtualThreadPerTaskExecutor()} 把编排/流式调用
 * 甩到虚拟线程里（{@code AgentChatController.java:171}、{@code ConsoleChatController.java:271}）。
 * 普通 ThreadLocal 在那里读到 {@code null}——这正是项目里
 * {@code RequestContextHolder} / MDC 不传播的同一个坑（控制器不得不把
 * {@code includeThinking} 在请求线程算好再带进 lambda）。
 * 虚拟线程在<b>创建时</b>继承 inheritable 值，因此「请求线程绑定 → 虚拟线程读取」成立。
 *
 * <p><b>⚠️ 必须 {@code remove()}</b>：容器工作线程会被复用，漏清会把上一个请求的
 * 会话带进下一个请求——那等于把 A 请求的原文记到 B 请求的审计里（串号）。
 * 过滤器在 {@code finally} 无条件 {@link #clear()}。
 *
 * <p><b>⚠️ 双保险</b>：装饰器自己也在入口 {@code current()} 取一次并持有引用，
 * 不依赖「取值那一刻线程仍是同一个」。
 */
public final class ChatAuditContextHolder {

    private static final InheritableThreadLocal<ChatAuditScope> CURRENT =
            new InheritableThreadLocal<>();

    private ChatAuditContextHolder() {
    }

    /** 绑定当前请求的审计作用域（请求线程调用）。 */
    public static void bind(@NonNull ChatAuditScope scope) {
        CURRENT.set(scope);
    }

    /** 当前作用域；未开启审计时返回 {@code null}（调用方必须容忍）。 */
    public static @Nullable ChatAuditScope current() {
        return CURRENT.get();
    }

    /** 清理（必须在 finally 中无条件调用）。 */
    public static void clear() {
        CURRENT.remove();
    }
}
