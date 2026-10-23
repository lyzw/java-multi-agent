package cn.studykid.multiagent.core.cap;

import org.jspecify.annotations.NonNull;

/**
 * 配置对象生命周期状态（§6.6 / 06 §3）。
 *
 * <p>```
 * DRAFT ──(validate)──▶ VALIDATED ──(publish)──▶ PUBLISHED ──(deactivate)──▶ DEPRECATED
 *   ▲                      │                          │                            │
 *   └────(reject/revise)───┘                          └────(new version/rollback)──┘
 * ```
 *
 * <p>⚠️ <b>与 {@code published_at} 并存，语义不同</b>：
 * <ul>
 *   <li>{@code published_at} 是<b>不可变触发器的依据</b>（V15
 *       {@code forbid_published_def_mutation}）：已发布行不可改写内容；</li>
 *   <li>{@code lifecycleState} 是<b>流程状态</b>：能表达 VALIDATED（已校验待发布）
 *       与 DEPRECATED（已停用、新发布不可再引用，06 §3.4）。</li>
 * </ul>
 * 两者由 DB 层的对齐触发器绑定：{@code published_at} 非空 ⇒ 状态为
 * {@link #PUBLISHED}（{@link #DEPRECATED} 除外，它是显式停用终态）。
 *
 * <p>⚠️ <b>为什么「校验失败保持可修订」必须在状态机里显式表达</b>：
 * 若状态机允许 {@code VALIDATED → PUBLISHED} 之外的隐式跃迁，
 * 「校验失败」就可能被实现成「静默进 PUBLISHED」——
 * 那正是 §6.6 明令禁止的（"校验失败保持可修订，不静默进 PUBLISHED"）。
 * 故本枚举<b>只</b>允许 {@code VALIDATED → DRAFT} 这条回退边，
 * 且 {@link #DEPRECATED} 是<b>终态</b>（不可迁出）——
 * 已停用的对象不能靠「改回去」重新变为可用，那会让停用形同虚设。
 */
public enum ConfigLifecycleState {

    /** 草稿：可编辑。校验失败时保持在此状态。 */
    DRAFT,

    /** 已校验：待发布。可回退到 {@link #DRAFT} 继续修订。 */
    VALIDATED,

    /** 已发布：当前发布可引用。 */
    PUBLISHED,

    /** 已停用：<b>新发布不可再引用</b>（06 §3.4）。终态。 */
    DEPRECATED;

    /**
     * 判断是否允许从本状态迁移到目标状态。
     *
     * <p>合法迁移（§6.6 / 06 §3）：
     * <ul>
     *   <li>{@code DRAFT → VALIDATED}：校验通过；</li>
     *   <li>{@code DRAFT → DRAFT} / {@code VALIDATED → VALIDATED}：修订（同态保持，
     *       允许「校验失败保持可修订」以内容修订的形式发生）；</li>
     *   <li>{@code VALIDATED → DRAFT}：校验被驳回，回退继续修订；</li>
     *   <li>{@code VALIDATED → PUBLISHED}：发布；</li>
     *   <li>{@code PUBLISHED → DEPRECATED}：停用。</li>
     * </ul>
     *
     * <p>⚠️ <b>刻意不允许</b>：{@code DRAFT → PUBLISHED}（跳过校验）、
     * {@code PUBLISHED → DRAFT}（把已发布对象退回草稿会让已生效的闭包突然失效）、
     * 以及任何自 {@link #DEPRECATED} 出发的迁移（终态）。
     *
     * @param target 目标状态
     * @return 允许迁移返回 {@code true}
     */
    public boolean canTransitionTo(@NonNull ConfigLifecycleState target) {
        if (target == null) {
            return false;
        }
        return switch (this) {
            case DRAFT -> target == DRAFT || target == VALIDATED;
            case VALIDATED -> target == VALIDATED || target == DRAFT || target == PUBLISHED;
            case PUBLISHED -> target == DEPRECATED;
            case DEPRECATED -> false;
        };
    }

    /**
     * @return 是否为「可被新发布引用」的状态。
     *         <b>只有 {@link #PUBLISHED} 是</b>：
     *         DRAFT/VALIDATED 未发布（§6.6「未发布不可被 Agent 启用」），
     *         DEPRECATED 已被停用（06 §3.4）。
     */
    public boolean isReferenceable() {
        return this == PUBLISHED;
    }

    /**
     * @return 是否为已发布（含已停用）。
     */
    public boolean isPublished() {
        return this == PUBLISHED || this == DEPRECATED;
    }
}
