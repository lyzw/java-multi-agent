package cn.studykid.multiagent.core.config;

import org.jspecify.annotations.NonNull;

/**
 * 审批要求级别（DEC-11 配置 {@code agent.approval.required-by-risk} 的取值域）。
 *
 * <p><b>单调性约束</b>：审批策略<b>只能收紧、不能放松</b>。任何将
 * {@link #REQUIRED} 降为非 REQUIRED 的变更属<b>安全门禁降级</b>，必须走
 * P0-D03 §6 范围变更模板 + ISO-5 外部抽检，不得作为普通配置调整处理
 * （P0-D03 §4 红线 15 / R-020）。
 *
 * <p>排序值用于表达「收紧程度」，数值越大保护越强，便于校验单调性。
 */
public enum ApprovalRequirement {

    /** 不需要审批。仅允许只读工具。 */
    NOT_REQUIRED(0),

    /** 可选审批：默认不阻塞，但满足条件时仍可触发。 */
    OPTIONAL(1),

    /** 必须审批：消费前检查状态与取消，时效期内有效。 */
    REQUIRED(2);

    private final int strictness;

    ApprovalRequirement(int strictness) {
        this.strictness = strictness;
    }

    /** @return 收紧程度，数值越大保护越强。 */
    public int strictness() {
        return strictness;
    }

    /**
     * 判断从当前要求到目标要求的变更是否为<b>放松</b>。
     *
     * @param target 目标要求
     * @return true 表示保护被削弱，属门禁降级，须走变更流程
     */
    public boolean isRelaxationTo(@NonNull ApprovalRequirement target) {
        return target.strictness < this.strictness;
    }

    /**
     * 合并两个要求，取更严格者。
     * <p>用于「工具风险级别映射」与「全局策略」同时生效的场景，
     * 保证结果不因配置组合顺序而变松。
     */
    public @NonNull ApprovalRequirement maxWith(@NonNull ApprovalRequirement other) {
        return other.strictness > this.strictness ? other : this;
    }
}
