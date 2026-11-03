package cn.studykid.multiagent.infrastructure.security;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import cn.studykid.multiagent.core.security.Action;
import cn.studykid.multiagent.core.security.ResourceRef;
import cn.studykid.multiagent.core.tenant.SubjectContext;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 内存态决策记录器 —— 供单测与本地验证使用（P1 观测窗口、无 DB 依赖）。
 *
 * <p>仅保留最近 {@code maxEntries} 条，避免长时间运行 OOM；生产环境应使用
 * {@link JdbcAuthzDecisionLog}。
 */
public final class InMemoryAuthzDecisionRecorder implements AuthzDecisionRecorder {

    private final List<Entry> entries = new CopyOnWriteArrayList<>();
    private final int maxEntries;

    public InMemoryAuthzDecisionRecorder() {
        this(10_000);
    }

    public InMemoryAuthzDecisionRecorder(int maxEntries) {
        this.maxEntries = Math.max(1, maxEntries);
    }

    @Override
    public void record(@NonNull SubjectContext subject,
                       @NonNull Action action,
                       @NonNull ResourceRef resource,
                       boolean permitted,
                       @Nullable String reasonCode) {
        entries.add(new Entry(subject.auditString(), action.name(),
                resource.type(), permitted, reasonCode));
        if (entries.size() > maxEntries) {
            entries.subList(0, entries.size() - maxEntries).clear();
        }
    }

    public @NonNull List<Entry> entries() {
        return List.copyOf(entries);
    }

    public int size() {
        return entries.size();
    }

    /** 一条决策记录（不可变）。 */
    public record Entry(@NonNull String subject,
                        @NonNull String action,
                        @NonNull String resourceType,
                        boolean permitted,
                        @Nullable String reasonCode) {
    }
}
