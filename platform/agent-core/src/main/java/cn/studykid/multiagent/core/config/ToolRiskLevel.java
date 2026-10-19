package cn.studykid.multiagent.core.config;

import java.util.Map;

import org.jspecify.annotations.NonNull;

/**
 * 工具风险级别（DEC-11 配置 {@code agent.tool.risk-level-map} 的取值域）。
 *
 * <p>首期两个工具的映射（P0-D00 §3.2）：
 * <pre>
 *   get_student_profile -> READ            （只读，无副作用）
 *   record_weakness     -> WRITE_PROFILE   （写学习画像，有副作用）
 * </pre>
 */
public enum ToolRiskLevel {

    /** 只读，无外部副作用。 */
    READ,

    /** 写入平台内部档案（如学习画像）。有副作用但不对外产生业务影响。 */
    WRITE_PROFILE,

    /** 对外部业务系统产生副作用。风险最高，首期强制 REQUIRED。 */
    WRITE_EXTERNAL;

    /**
     * 由工具名解析风险级别。
     *
     * <p><b>fail-closed</b>：未登记的工具名<b>不返回 null 放行</b>，而是按最严格的
     * {@link #WRITE_EXTERNAL} 处理，并要求调用方显式复核。新增工具若忘记配置
     * risk-level-map，会被自动当作最高风险对待——宁可多审批，不可漏审批。
     *
     * @param toolName 工具名
     * @param configured 已配置映射，未配置项以 {@link #WRITE_EXTERNAL} 兜底
     * @return 风险级别，永不为 null
     */
    public static @NonNull ToolRiskLevel resolve(@NonNull String toolName,
                                                 @NonNull Map<String, ToolRiskLevel> configured) {
        ToolRiskLevel level = configured.get(toolName);
        return level != null ? level : WRITE_EXTERNAL;
    }

    /** @return 该级别对应的默认审批要求（可被更严格配置覆盖，但不会被放松）。 */
    public @NonNull ApprovalRequirement defaultApproval() {
        return switch (this) {
            case READ -> ApprovalRequirement.NOT_REQUIRED;
            case WRITE_PROFILE -> ApprovalRequirement.OPTIONAL;
            case WRITE_EXTERNAL -> ApprovalRequirement.REQUIRED;
        };
    }

    /** @return 该级别是否必然产生副作用（决定是否需 ToolOperation 账本）。 */
    public boolean hasSideEffect() {
        return this != READ;
    }

    /** @return 需幂等键的工具级别。 */
    public boolean requiresIdempotencyKey() {
        return hasSideEffect();
    }
}
