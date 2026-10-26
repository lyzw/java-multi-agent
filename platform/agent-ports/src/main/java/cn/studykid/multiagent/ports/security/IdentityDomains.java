package cn.studykid.multiagent.ports.security;

/**
 * 身份域常量（重规划 §4 / §7）。
 *
 * <p>平台身份与租户身份是两套<b>独立安全主体</b>，其令牌通过不同的
 * {@code aud}（受众）与 {@code principal_type}（主体类型）在<b>令牌层</b>
 * 互相区分，而非仅靠角色名或前端路由。任一链收到对方 {@code aud} 的令牌
 * 必须直接拒绝。
 */
public final class IdentityDomains {

    private IdentityDomains() {
    }

    /** 租户业务 API 令牌受众（与现有 {@code auth.jwt.audience} 保持一致）。 */
    public static final String TENANT_AUDIENCE = "agent-platform";

    /** 平台管理 API 令牌受众（admin-console 专用，与租户受众严格不同）。 */
    public static final String PLATFORM_AUDIENCE = "admin-console-api";

    /** 租户主体类型声明值。 */
    public static final String PRINCIPAL_TYPE_TENANT = "TENANT_USER";

    /** 平台主体类型声明值。 */
    public static final String PRINCIPAL_TYPE_PLATFORM = "PLATFORM_USER";

    /**
     * 平台管理链写入的可信 {@link PlatformPrincipal} 请求属性名。
     *
     * <p>控制器只从该请求属性取平台主体，不自己解析令牌
     * （避免与过滤器链两处实现不一致导致语义分叉，对应租户侧
     * {@code SecurityConfiguration.TENANT_CONTEXT_ATTRIBUTE} 的纪律）。
     */
    public static final String PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE =
            "cn.studykid.multiagent.platformPrincipal";
}
