package cn.studykid.multiagent.infrastructure.security;

import cn.studykid.multiagent.core.security.Action;
import cn.studykid.multiagent.core.security.ResourceRef;
import cn.studykid.multiagent.core.tenant.SubjectContext;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 授权决策记录器（12 文 §4.5 / D6）。
 *
 * <p>{@link DefaultAccessGate} 每次判定（Permit / Deny / 异常）后调用本接口，
 * 把「谁、对什么、是否放行、原因码」落到可观测存储。<b>这是「退役后只剩应用层」
 * 纵深下降的三层补偿之一</b>（类型系统 + 染色 + 决策日志）—— 没有它，
 * 应用层越权在事后不可审计。
 *
 * <p>默认实现 {@link NoOpAuthzDecisionRecorder} 无副作用；P2 翻转为
 * {@link JdbcAuthzDecisionLog} 持久化到 {@code platform.authz_decision_log}
 * （DDL 见该类 Javadoc，P2 落地）。
 */
public interface AuthzDecisionRecorder {

    /**
     * 记录一次判定。
     *
     * @param subject    判定主体（已脱敏，仅含 tenantId/principalId/scope）
     * @param action     动作
     * @param resource   资源（集合路径为 {@code ResourceRef.collection(type)}）
     * @param permitted  true=Permit；false=Deny 或决策异常
     * @param reasonCode 拒绝原因码（Permit 时为 {@code null}）
     */
    void record(@NonNull SubjectContext subject,
                @NonNull Action action,
                @NonNull ResourceRef resource,
                boolean permitted,
                @Nullable String reasonCode);
}
