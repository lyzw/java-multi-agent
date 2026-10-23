package cn.studykid.multiagent.application.cap;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.core.cap.ConfigLifecycleState;

/**
 * 配置生命周期状态机（§6.6 / 06 §3 · W-PERM-P5）。
 *
 * <p>⚠️ <b>本类只管「状态迁移是否合法」，不管「何时迁移」</b>：
 * 迁移的时机由用例决定（发布服务在发布时把对象抬到 PUBLISHED，
 * 管理端在停用时把它推到 DEPRECATED）。
 * 把两者混在一起会让状态机变成一个隐含副作用的 god object。
 *
 * <p>⚠️ <b>「校验失败保持可修订」靠回退边实现</b>：
 * {@code VALIDATED → DRAFT} 是唯一允许的回退边。
 * 没有它，「校验失败」就只能停在 VALIDATED（看起来像已校验通过），
 * 或者被实现成「静默进 PUBLISHED」—— 后者正是 §6.6 明令禁止的。
 *
 * <p>⚠️ <b>「未发布不可被 Agent 启用」由本状态机 + 发布指针共同保证</b>：
 * 只有 {@link ConfigLifecycleState#PUBLISHED} 的对象才在当前发布的
 * {@code object_versions} 里；DRAFT / VALIDATED 的对象根本不在发布头中，
 * 因此闭包解析根本找不到它们 —— 启用链路上根本不会看到它们。
 */
public class ConfigLifecycleStateMachine {

    /**
     * 判断迁移是否合法。
     *
     * @param from 源状态
     * @param to   目标状态
     * @return 合法返回 {@code true}
     */
    public boolean canTransition(@NonNull ConfigLifecycleState from,
                                 @NonNull ConfigLifecycleState to) {
        if (from == null || to == null) {
            return false;
        }
        return from.canTransitionTo(to);
    }

    /**
     * 执行迁移，非法时抛异常。
     *
     * @param from 源状态
     * @param to   目标状态
     * @return 目标状态（合法时）
     * @throws IllegalStateException 迁移非法
     */
    @NonNull
    public ConfigLifecycleState transition(@NonNull ConfigLifecycleState from,
                                           @NonNull ConfigLifecycleState to) {
        if (!canTransition(from, to)) {
            throw new IllegalStateException(
                    "非法的生命周期迁移: " + from + " → " + to
                            + "（允许的迁移见 §6.6：DRAFT→VALIDATED→PUBLISHED→DEPRECATED，"
                            + "VALIDATED→DRAFT 为校验失败回退边）");
        }
        return to;
    }

    /**
     * 断言某状态可被新发布引用（§6.6「未发布不可被 Agent 启用」）。
     *
     * @param state 状态
     * @param objectId 对象标识（用于报错定位）
     * @throws ConfigPublishRejectedException 状态不可引用
     */
    public void assertReferenceable(@NonNull ConfigLifecycleState state,
                                    @NonNull String objectId) {
        if (!state.isReferenceable()) {
            throw new ConfigPublishRejectedException(
                    cn.studykid.multiagent.core.cap.ReasonCode.RESOURCE_UNKNOWN,
                    "lifecycleState",
                    "对象 " + objectId + " 当前状态为 " + state
                            + "，仅 PUBLISHED 状态可被新发布引用（§6.6：未发布不可被 Agent 启用；"
                            + "DEPRECATED 已被停用，06 §3.4）");
        }
    }
}
