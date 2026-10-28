package cn.studykid.multiagent.core.orchestration;

import org.jspecify.annotations.NonNull;

/**
 * 路由策略的模式（10 号设计 §4.1）：<b>差别在于「谁选目标」</b>。
 *
 * <table>
 *   <caption>三种模式的语义边界</caption>
 *   <tr><th>模式</th><th>谁选目标</th><th>模型参与度</th></tr>
 *   <tr><td>{@link #RULES_ONLY}</td><td>规则引擎</td><td><b>零</b>（模型只产出 {@code input}）</td></tr>
 *   <tr><td>{@link #RULE_FIRST}</td><td>规则优先，无命中再兜底/语义</td><td>有限</td></tr>
 *   <tr><td>{@link #SEMANTIC}</td><td>模型（看得到 §3 契约）</td><td>完全</td></tr>
 * </table>
 *
 * <p>⚠️ <b>{@link #RULES_ONLY} 的下发 Schema 不含 {@code candidates}</b>：
 * 该模式下模型没有目标选择权，把候选列给它就是「名义上让它选、实际上不听它的」，
 * 而模型会按那个许诺来设计自己的行为。这条约束在 {@code DelegationToolSchema} 侧把守，
 * 不在本枚举里 —— 本枚举只负责把「谁选」这件事写成三行代码说不清的<b>类型</b>。
 *
 * <p>⚠️ 历史教训（§4.1）：{@code ROUTER} 曾「名不副实」、{@code SEQUENTIAL} 曾被承诺聚合而实现没有。
 * <b>名字许诺了做不到的事，配置方就会按那个许诺来设计。</b>
 * 因此每个模式都必须有可执行的行为来兑现名字，做不到就不加这个枚举值。
 */
public enum RoutingMode {

    /** 只按规则转发；模型不参与目标选择（<b>推荐默认</b>，确定性最强）。 */
    RULES_ONLY,

    /** 规则优先；{@code ②=NO_MATCH} 时才启用语义引擎，候选来自模型。 */
    RULE_FIRST,

    /** 语义路由；目标由模型按 §3 契约选定。 */
    SEMANTIC;

    /**
     * 该模式是否必须带非空规则列表（DDL 的 {@code eval_ck} 与守卫 V1 同口径）。
     *
     * <p>⚠️ {@link #SEMANTIC} 允许带规则（此时规则不参与判定，只作观测），
     * 但 {@code RULES_ONLY}/{@link #RULE_FIRST} 带空规则等于「每次都走兜底」——
     * 那是配置缺陷，必须拒，而不是让它看起来在跑规则。
     */
    public boolean requiresNonEmptyRules() {
        return this != SEMANTIC;
    }

    /**
     * 该模式下模型<b>有没有</b>目标选择权。
     *
     * <p>Schema 装配（要不要下发 {@code candidates}）与决策快照（{@code candidateFromModel}
     * 是否可能非空）都读这一个判据 —— 两处各判一次就会出现
     * 「Schema 给了候选但引擎不听」这类名分落差。
     */
    public boolean modelChoosesTarget() {
        return this != RULES_ONLY;
    }

    /**
     * 按策略文本解析模式。
     *
     * @param wireName 策略文本里的写法
     * @return 枚举
     * @throws IllegalArgumentException 词汇表之外（<b>没有</b>「未知按默认」这条路）
     */
    public static @NonNull RoutingMode from(@NonNull String wireName) {
        for (RoutingMode mode : values()) {
            if (mode.name().equals(wireName)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("未知的路由模式: " + wireName + "（只有 "
                + java.util.Arrays.stream(values()).map(Enum::name).toList()
                + "；EXPLICIT_TARGETS / ORDERED_FALLBACK 已随未上线的旧模型一并删除）");
    }
}
