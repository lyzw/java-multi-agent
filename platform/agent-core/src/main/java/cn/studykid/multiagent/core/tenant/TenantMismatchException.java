package cn.studykid.multiagent.core.tenant;

import org.jspecify.annotations.NonNull;

/**
 * 租户不匹配 —— 归类为授权失败，对应 P0-D04 的 Sev-0
 * 「跨租户数据泄露」的前置拦截。
 *
 * <p>抛出此异常意味着：请求试图访问与当前认证上下文不一致的租户资源。
 * <b>绝不</b>降级为「返回空结果」或「忽略该字段」——那会掩盖攻击迹象。
 */
public class TenantMismatchException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient String actualTenantId;
    private final transient String expectedTenantId;

    public TenantMismatchException(@NonNull String actualTenantId, @NonNull String expectedTenantId) {
        super("租户不匹配：当前上下文 tenantId=" + actualTenantId
                + "，目标资源 tenantId=" + expectedTenantId + "。拒绝访问");
        this.actualTenantId = actualTenantId;
        this.expectedTenantId = expectedTenantId;
    }

    public @NonNull String actualTenantId() {
        return actualTenantId;
    }

    public @NonNull String expectedTenantId() {
        return expectedTenantId;
    }
}