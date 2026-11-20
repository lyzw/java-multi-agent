package cn.studykid.multiagent.infrastructure.persistence.rls;

import java.util.Objects;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.tenant.TenantContext;

/**
 * 租户作用域上下文（POC，对应主文档 §10 D2）。
 *
 * <p><b>为什么需要它</b>：MyBatis 的 Interceptor 在 SQL 执行前拿不到方法参数里的
 * tenantId（参数可能是 DO、Map、Wrapper 等多种形态，不可靠解析）。因此把「本次调用的主体租户」
 * 显式放进 ThreadLocal，由适配层（见 {@code MybatisConfigDraftRepository}）在调用 Mapper 前
 * <b>从认证上下文</b>写入、finally 中无条件清理——<b>绝不从请求参数读取</b>。
 *
 * <p>⚠️ 与 {@link TenantContext} 的口径一致：这里只保存 tenantId 字符串；
 * 平台共享行用零 UUID 哨兵（{@code ConfigDraftPort.PLATFORM_SCOPE_TENANT} 同值）。
 *
 * <p>⚠️ 线程池泄漏纪律（与 TransactionalConnectionHolder 同源）：
 * {@link #require()} 在值缺失时 <b>fail-closed 抛异常</b>——「无主体」的查询不允许静默发生；
 * 调用方必须在 finally 调 {@link #clear()}，否则下一个请求会串租户。
 */
public final class TenantScope {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    /** 平台级共享行使用的哨兵租户（与 cap_config_draft 的 scope 约束一致）。 */
    public static final String PLATFORM_SCOPE_TENANT = "00000000-0000-0000-0000-000000000000";

    private TenantScope() {
    }

    /** 进入租户作用域。tenantId 必须来自认证上下文，不得来自客户端参数。 */
    public static void bind(@NonNull String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId");
        if (CURRENT.get() != null) {
            throw new IllegalStateException("租户作用域重复绑定：疑似线程池残留未清理，"
                    + "串租户风险。检查调用方是否漏了 TenantScope.clear()");
        }
        CURRENT.set(tenantId);
    }

    /**
     * 取当前主体租户；<b>缺失即抛异常</b>（fail-closed）。
     *
     * <p>这是「代码保证 RLS」的关键：拦截器拿不到租户就拒绝执行 SQL，
     * 而不是放行一条无租户上下文的查询（那会读到 0 行或越权行）。
     */
    public static @NonNull String require() {
        String v = CURRENT.get();
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("无租户主体：Mapper 调用必须处于 TenantScope 内。"
                    + "禁止裸调用 SqlSession —— 这是 RLS 失效的最常见来源");
        }
        return v;
    }

    public static @Nullable String peek() {
        return CURRENT.get();
    }

    /** 必须在 finally 中无条件调用。 */
    public static void clear() {
        CURRENT.remove();
    }
}
