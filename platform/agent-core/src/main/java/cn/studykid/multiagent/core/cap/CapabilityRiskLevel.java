package cn.studykid.multiagent.core.cap;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 能力风险等级（§5.6 / §4.2）。
 *
 * <p>§5.6 规定序关系：{@code READ < WRITE_PROFILE < WRITE_EXTERNAL}，未知 → 拒绝。
 *
 * <p>⚠️ <b>本枚举与既有 {@code core.config.ToolRiskLevel} 是两套并存模型，不可混用</b>：
 * <ul>
 *   <li>{@code ToolRiskLevel} 是<b>旧平台</b>的工具风险登记表取值域，其
 *       {@code resolve()} 对未登记工具<b>降级为最严格的 WRITE_EXTERNAL</b>
 *       （fail-closed 的「宁可多审批」策略），并服务于旧审批策略；</li>
 *   <li>本枚举服务于<b>统一配置与权限管控体系</b>的 Phase A 判定，
 *       §5.7 为「风险未知」单列了 {@link ReasonCode#RISK_LEVEL_UNKNOWN} 码，
 *       且 §4.2 明确「未知/非法 → 运行期 RISK_LEVEL_UNKNOWN」。</li>
 * </ul>
 * 若把「未知」也降级成 {@code WRITE_EXTERNAL}，则
 * {@code RISK_LEVEL_UNKNOWN} 与 {@code RISK_EXCEEDS_CEILING} 永远无法区分 ——
 * 而二者的处置不同（前者是<b>配置缺陷</b>，须修配置；后者是<b>策略边界</b>，属正常拒绝）。
 * P1 的退出门禁明确要求「未知风险拒绝且<b>不降级</b>为 WRITE_EXTERNAL」。
 */
public enum CapabilityRiskLevel {

    /** 只读，无外部副作用。 */
    READ(0),

    /** 写入平台内部档案（如学习画像）。 */
    WRITE_PROFILE(1),

    /** 对外部业务系统产生副作用，风险最高。 */
    WRITE_EXTERNAL(2),

    /**
     * 未知或非法风险等级。
     *
     * <p>⚠️ <b>不是「最严格」而是「不可判定」</b>：未知风险一律拒绝，
     * 但拒绝原因是 {@link ReasonCode#RISK_LEVEL_UNKNOWN}，
     * <b>不得</b>与「超出 ceiling」混为一谈。
     */
    UNKNOWN(-1);

    private final int rank;

    CapabilityRiskLevel(int rank) {
        this.rank = rank;
    }

    /**
     * @return 风险序值（数值越大风险越高）；{@link #UNKNOWN} 为 -1，表示<b>不可比较</b>
     */
    public int rank() {
        return rank;
    }

    /**
     * 解析持久化的风险等级文本。
     *
     * <p>⚠️ <b>非法值一律返回 {@link #UNKNOWN}，绝不降级为 WRITE_EXTERNAL</b>（见类注释）。
     *
     * @param raw 持久化文本（{@code READ} / {@code WRITE_PROFILE} / {@code WRITE_EXTERNAL}）
     * @return 解析结果；文本为空或不在取值域内时为 {@link #UNKNOWN}
     */
    public static @NonNull CapabilityRiskLevel parse(@Nullable String raw) {
        if (raw == null) {
            return UNKNOWN;
        }
        String normalized = raw.trim().toUpperCase(java.util.Locale.ROOT);
        for (CapabilityRiskLevel candidate : values()) {
            if (candidate != UNKNOWN && candidate.name().equals(normalized)) {
                return candidate;
            }
        }
        return UNKNOWN;
    }

    /**
     * 判断本级别是否<b>超出</b>给定上限。
     *
     * <p>⚠️ <b>未知风险不参与比较</b>：{@link #UNKNOWN} 与任何 ceiling 比较都返回 {@code false}
     * —— 因为「不可判定」既不能被断言为「未超限」，也不应被误报为「超限」。
     * 调用方<b>必须先</b>用 {@link #isUnknown()} 分流未知情形，
     * 否则未知风险会被放行（把 fail-closed 变成 fail-open）。
     *
     * @param ceiling 允许的风险上限
     * @return 本级别高于 ceiling 时为 {@code true}
     */
    public boolean exceeds(@NonNull CapabilityRiskLevel ceiling) {
        if (this == UNKNOWN || ceiling == UNKNOWN) {
            return false;
        }
        return this.rank > ceiling.rank;
    }

    /**
     * @return 是否为未知/非法风险
     */
    public boolean isUnknown() {
        return this == UNKNOWN;
    }

    /**
     * 取两个上限中<b>更严格</b>者（§4.2 overrides 仅收紧）。
     *
     * <p>⚠️ <b>「更严格」= 等级更低</b>：上限约束的是「允许的最大风险」，
     * 故 {@code READ} 比 {@code WRITE_EXTERNAL} 更严格。
     *
     * <p>⚠️ 任一为 {@link #UNKNOWN} 时返回 {@link #UNKNOWN}：
     * 未知上限不能被当成「无限制」，也不能被当成「最宽松」。
     *
     * @param a 上限 A
     * @param b 上限 B
     * @return 更严格的上限
     */
    public static @NonNull CapabilityRiskLevel stricter(@NonNull CapabilityRiskLevel a,
                                                         @NonNull CapabilityRiskLevel b) {
        if (a == UNKNOWN || b == UNKNOWN) {
            return UNKNOWN;
        }
        return a.rank <= b.rank ? a : b;
    }
}
