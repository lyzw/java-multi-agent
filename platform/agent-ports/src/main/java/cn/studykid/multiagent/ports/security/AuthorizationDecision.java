package cn.studykid.multiagent.ports.security;

import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 授权判定结果（重规划 §4.3 / §7.2）。
 *
 * <p>明确返回是否允许、拒绝原因码、策略版本与决策标识，
 * 供审计追踪与执行入口拒绝对齐。
 */
public record AuthorizationDecision(
        boolean allowed,
        @Nullable String reasonCode,
        long policyVersion,
        @NonNull UUID decisionId
) {

    public static @NonNull AuthorizationDecision allow(long policyVersion) {
        return new AuthorizationDecision(true, null, policyVersion, UUID.randomUUID());
    }

    public static @NonNull AuthorizationDecision deny(@NonNull String reasonCode,
                                                      long policyVersion) {
        return new AuthorizationDecision(false, reasonCode, policyVersion, UUID.randomUUID());
    }
}
