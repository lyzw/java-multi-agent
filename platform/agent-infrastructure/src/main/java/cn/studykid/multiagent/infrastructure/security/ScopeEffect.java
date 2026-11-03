package cn.studykid.multiagent.infrastructure.security;

/**
 * 策略规则命中后产出的 {@link cn.studykid.multiagent.core.security.ScopeFilter} 形态（12 文 §2.3.3）。
 */
public enum ScopeEffect {
    /** 仅本租户 → {@code ScopeFilter.TenantOnly}。 */
    TENANT_ONLY,

    /** 本租户 + 平台哨兵 → {@code ScopeFilter.TenantWithShared}。 */
    TENANT_WITH_SHARED,

    /** 平台全域 → {@code ScopeFilter.Unrestricted}（仅限 isPlatformOps 主体，R3）。 */
    UNRESTRICTED
}
