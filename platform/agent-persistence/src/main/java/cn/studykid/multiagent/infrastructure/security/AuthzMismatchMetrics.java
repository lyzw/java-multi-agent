package cn.studykid.multiagent.infrastructure.security;

import cn.studykid.multiagent.core.security.ScopeFilter;
import cn.studykid.multiagent.infrastructure.persistence.scope.RowAttribution;

import org.jspecify.annotations.NonNull;

/**
 * 权限影子/染色观测指标（12 文 §4.5 / D6）。
 *
 * <p>迁移期「双写取交集」阶段，应用层过滤与 RLS 同时生效；任何两者不一致都必须被量化，
 * 否则「退役后只剩应用层」的纵深下降无法被证明已收敛。本接口定义五项核心计数：
 * <ul>
 *   <li>{@code shadowSamples} —— 影子比对样本数（每个被比对的查询）；</li>
 *   <li>{@code unrestrictedUsages} —— {@code Unrestricted}（平台全域）使用次数；</li>
 *   <li>{@code mismatches} —— Gate 计算谓词与实际查询绑定不一致的计数；</li>
 *   <li>{@code taintViolations} —— 染色校验捕获的越权行计数；</li>
 *   <li>{@code rlsDependent} —— <b>仍依赖 RLS 兜底的查询数</b>（Gate 要求按租户收口，
 *       但该查询没有显式租户谓词）。</li>
 * </ul>
 *
 * <p>生产出口门禁（13 文 §5.2）：≥2 周 {@code AUTHZ_MISMATCH = 0} 方可放行 P2。
 *
 * <p>⚠️ <b>这个出口门禁本身是「结构性为 0」的，必须与 {@code rlsDependent} 一起读</b>
 * （2026-11-09 实测推导）：
 * <ul>
 *   <li><b>染色违例</b>要成立，必须有一条<b>行归属越出 Gate 范围</b>的行。
 *       但 RLS 仍在时，带 RLS 的表<b>只可能返回本租户的行</b> ⇒ 染色违例恒为 0；
 *       也就是说它<b>只在无 RLS 的缺口表上才有信号</b>。</li>
 *   <li><b>比对不一致</b>要成立，必须 Gate 租户 ≠ 查询绑定租户；而仓库传入的绑定租户
 *       与 Gate 用的主体租户<b>通常是同一个</b> ⇒ 该分支也恒不成立。</li>
 *   <li>于是「2 周 AUTHZ_MISMATCH=0」在 RLS 仍在时<b>无论隔离做得好不好都会达成</b>，
 *       它<b>不是</b>「应用层过滤已正确」的证据。</li>
 * </ul>
 * <b>唯一能在 P1 期间产出非平凡信号、且直接对应「退役 RLS 后是否还会漏」的量，
 * 就是 {@code rlsDependent}</b>：它把「哪些查询还在靠 RLS 兜底」变成可量化、可清零的清单。
 */
public interface AuthzMismatchMetrics {

    void recordShadowSample(@NonNull String resourceType);

    void recordUnrestricted(@NonNull String resourceType);

    void recordMismatch(@NonNull String resourceType, @NonNull String detail);

    void recordTaintViolation(@NonNull String resourceType,
                              @NonNull RowAttribution row,
                              @NonNull ScopeFilter expected);

    /**
     * 记录一次「仍依赖 RLS 兜底」的查询（Gate 要求按租户收口，但查询未带显式租户谓词）。
     *
     * <p>⚠️ <b>这不是违例</b>，因此<b>不计入 {@link #mismatches()}、也不触发阻断</b>：
     * 它是「退役 RLS 的迁移清单」条目。把两件事混在一个计数器里，
     * 会让出口门禁要么永远不达标、要么被迫调松阈值 —— 两种都是把防线拆掉。
     */
    void recordRlsDependent(@NonNull String resourceType);

    long shadowSamples();

    long unrestrictedUsages();

    long mismatches();

    long taintViolations();

    /** 仍依赖 RLS 兜底的查询数（P2 退役清单的当前规模）。 */
    long rlsDependent();
}
