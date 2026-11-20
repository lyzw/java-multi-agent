package cn.studykid.multiagent.infrastructure.persistence.rls;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.core.tenant.TenantContext;

/**
 * 租户作用域上下文（W0 RLS 地基，对应主文档 §10 D2）。
 *
 * <p><b>为什么需要它</b>：MyBatis 的 Interceptor 拿不到方法参数里的 tenantId
 * （参数形态可能是 DO / Map / Wrapper，不可靠解析）。因此把「本次调用的主体租户」
 * 显式放进 ThreadLocal，由适配层在调用 Mapper <b>之前</b>从认证上下文写入、
 * finally 中无条件清理 —— <b>绝不从请求参数读取</b>。
 *
 * <p>⚠️ 线程池泄漏纪律（与 TransactionalConnectionHolder 同源）：
 * {@link #require()} 在值缺失时 <b>fail-closed 抛异常</b>——「无主体」的查询不允许静默发生；
 * 调用方必须在 finally 调 {@link #clear()}，否则下一个请求会串租户。
 */
public final class TenantScope {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    /**
     * 嵌套层数：适配层可能在一次外层 inTenantScope 内调用另一个适配层方法
     * （如 insert → findById 回读）。内层 clear() 必须恢复到外层主体，
     * 而不是直接清空，否则后续 Mapper 调用会 fail-closed 误报。
     */
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    /** 平台级共享行使用的哨兵租户（与 ConfigDraftPort 的 PLATFORM_SCOPE_TENANT 同值）。 */
    public static final String PLATFORM_SCOPE_TENANT = "00000000-0000-0000-0000-000000000000";

    private TenantScope() {
    }

    /** 进入租户作用域。tenantId 必须来自认证上下文，不得来自客户端参数。 */
    public static void bind(@NonNull String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId 不能为空");
        }
        if (CURRENT.get() != null) {
            // 嵌套作用域：必须是同一主体才允许复用，串租户仍然 fail-closed
            if (!tenantId.equals(CURRENT.get())) {
                throw new IllegalStateException("租户作用域嵌套串租户：外层=" + CURRENT.get()
                        + "，内层=" + tenantId);
            }
            DEPTH.set(DEPTH.get() + 1);
            return;
        }
        CURRENT.set(tenantId);
        DEPTH.set(1);
    }

    /**
     * 从 {@link TenantContext} 绑定：正常路径应传认证上下文解析出的 TenantContext。
     */
    public static void bind(@NonNull TenantContext context) {
        bind(context.tenantId());
    }

    /** 取当前主体租户；<b>缺失即抛异常</b>（fail-closed）。 */
    public static @NonNull String require() {
        String v = CURRENT.get();
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("无租户主体：Mapper 调用必须处于 TenantScope 内。"
                    + "禁止裸调用 SqlSession —— 这是 RLS 失效的最常见来源");
        }
        return v;
    }

    public static String peek() {
        return CURRENT.get();
    }

    /** 必须在 finally 中无条件调用；嵌套时逐层恢复，最外层才真正清空。 */
    public static void clear() {
        int depth = DEPTH.get();
        if (depth <= 1) {
            CURRENT.remove();
            DEPTH.remove();
            RlsTenantBindingInterceptor.onConnectionRelease();
        } else {
            DEPTH.set(depth - 1);
        }
    }
}
