package cn.studykid.multiagent.infrastructure.security;

/**
 * 策略规则的前置主体要求（12 文 §2.3.3）。
 *
 * <ul>
 *   <li>{@code TENANT} —— 任意租户态主体满足。</li>
 *   <li>{@code PLATFORM} —— 仅 {@code SubjectContext.isPlatformOps()} 主体满足。</li>
 * </ul>
 */
public enum ScopeRequirement {
    TENANT,
    PLATFORM
}
