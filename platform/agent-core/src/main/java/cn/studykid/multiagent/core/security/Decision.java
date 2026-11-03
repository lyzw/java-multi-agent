package cn.studykid.multiagent.core.security;

import org.jspecify.annotations.NonNull;

/**
 * 鉴权决策结果。
 *
 * <ul>
 *   <li>{@link Permit} —— 放行，携带生效的 {@link ScopeFilter}（可能被渲染为 SQL 谓词或向量库 filter）。</li>
 *   <li>{@link Deny} —— 拒绝，携带原因码与可读信息，由 {@code AccessGate} 转为异常抛出（fail-closed）。</li>
 * </ul>
 */
public sealed interface Decision
        permits Decision.Permit, Decision.Deny {

    /** 放行。 */
    record Permit(@NonNull ScopeFilter filter) implements Decision {}

    /** 拒绝。 */
    record Deny(@NonNull String reasonCode, @NonNull String message) implements Decision {}
}
