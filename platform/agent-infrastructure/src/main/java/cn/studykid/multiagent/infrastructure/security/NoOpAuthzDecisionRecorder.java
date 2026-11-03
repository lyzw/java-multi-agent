package cn.studykid.multiagent.infrastructure.security;

import cn.studykid.multiagent.core.security.Action;
import cn.studykid.multiagent.core.security.ResourceRef;
import cn.studykid.multiagent.core.tenant.SubjectContext;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 无副作用的决策记录器（默认）。
 *
 * <p>P1 影子期与未启用决策日志时启用；P2 翻转为 {@link JdbcAuthzDecisionLog}。
 */
public final class NoOpAuthzDecisionRecorder implements AuthzDecisionRecorder {

    public static final NoOpAuthzDecisionRecorder INSTANCE = new NoOpAuthzDecisionRecorder();

    private NoOpAuthzDecisionRecorder() {
    }

    @Override
    public void record(@NonNull SubjectContext subject,
                       @NonNull Action action,
                       @NonNull ResourceRef resource,
                       boolean permitted,
                       @Nullable String reasonCode) {
        // 无操作
    }
}
