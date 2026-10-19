package cn.studykid.multiagent.core.run;

import java.util.Set;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** 非法状态迁移。归类为一致性缺陷，对应 P0-D04 缺陷分级中的 Sev-1。 */
public class IllegalStateTransitionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient RunState from;
    private final transient RunState to;

    public IllegalStateTransitionException(@NonNull RunState from, @NonNull RunState to) {
        super("非法状态迁移: Run " + from.name() + " -> " + to.name()
                + "（允许目标: " + from.legalTargets() + "）");
        this.from = from;
        this.to = to;
    }

    public IllegalStateTransitionException(@NonNull String entity,
                                           @NonNull String from,
                                           @NonNull String to,
                                           @NonNull Set<?> allowed) {
        super("非法状态迁移: " + entity + " " + from + " -> " + to + "（允许目标: " + allowed + "）");
        this.from = null;
        this.to = null;
    }

    /** @return 迁移来源；仅 Run 场景非 null。 */
    public @Nullable RunState from() {
        return from;
    }

    /** @return 迁移目标；仅 Run 场景非 null。 */
    public @Nullable RunState to() {
        return to;
    }
}
