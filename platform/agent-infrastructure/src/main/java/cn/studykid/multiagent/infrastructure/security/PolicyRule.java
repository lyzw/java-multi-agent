package cn.studykid.multiagent.infrastructure.security;

import cn.studykid.multiagent.core.security.Action;

import org.jspecify.annotations.NonNull;

/**
 * 一条声明式策略规则 —— 逐条对应现役 RLS 策略（12 文 §2.3.3）。
 *
 * <p>产出 {@code UNRESTRICTED} 的规则，其前置要求必须是 {@link ScopeRequirement#PLATFORM}，
 * 否则在 {@link PolicyStore} 加载期即抛 {@link IllegalPolicyException}（fail-closed，拒绝启动）。
 */
public record PolicyRule(
        @NonNull String id,
        @NonNull String resourceType,
        @NonNull Action action,
        @NonNull ScopeRequirement requirement,
        @NonNull ScopeEffect effect) {
}
