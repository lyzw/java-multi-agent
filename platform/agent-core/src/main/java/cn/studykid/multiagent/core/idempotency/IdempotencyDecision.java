package cn.studykid.multiagent.core.idempotency;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 幂等判定结果（ADR-003）。
 *
 * <p>三种结果必须<b>严格区分</b>，不可合并为「成功/失败」两态：
 * <ul>
 *   <li>{@link Kind#FIRST_EXECUTION} —— 首次执行，允许继续；</li>
 *   <li>{@link Kind#REPLAY} —— 重复且请求相同，<b>返回首次结果</b>，不得重复执行副作用；</li>
 *   <li>{@link Kind#CONFLICT} —— 键相同但请求不同，<b>必须报错</b>。</li>
 * </ul>
 *
 * <p>把 CONFLICT 当作 REPLAY 处理是危险的：用户换了参数却被告知「已成功」，
 * 会掩盖真实的参数错误；反过来把 REPLAY 当作 CONFLICT 则会让正常重试失败。
 */
public record IdempotencyDecision(@NonNull Kind kind, @Nullable String originalResourceId) {

    public enum Kind {
        /** 首次出现该键，可执行。 */
        FIRST_EXECUTION,
        /** 重复且请求内容相同，返回首次结果。 */
        REPLAY,
        /** 键相同但请求内容不同，冲突。 */
        CONFLICT
    }

    public IdempotencyDecision {
        if (kind == null) {
            throw new IllegalArgumentException("kind 不能为空");
        }
        if (kind != Kind.CONFLICT && originalResourceId == null) {
            throw new IllegalArgumentException(
                    kind + " 场景必须携带 originalResourceId");
        }
    }

    public static @NonNull IdempotencyDecision firstExecution(@NonNull String resourceId) {
        return new IdempotencyDecision(Kind.FIRST_EXECUTION, resourceId);
    }

    public static @NonNull IdempotencyDecision replay(@NonNull String resourceId) {
        return new IdempotencyDecision(Kind.REPLAY, resourceId);
    }

    public static @NonNull IdempotencyDecision conflict() {
        return new IdempotencyDecision(Kind.CONFLICT, null);
    }

    /** @return 是否应继续执行副作用。冲突时为 false。 */
    public boolean shouldExecute() {
        return kind == Kind.FIRST_EXECUTION;
    }

    /** @return 是否是把新请求误用了已占用的键。 */
    public boolean isConflict() {
        return kind == Kind.CONFLICT;
    }

    /**
     * @throws IdempotencyConflictException 键相同但请求不同
     */
    public void throwIfConflict() {
        if (isConflict()) {
            throw new IdempotencyConflictException();
        }
    }
}