package cn.studykid.multiagent.core.cap;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 稳定原因码（权威表 §5.7）。
 *
 * <p><b>本枚举是 §5.7 的逐项落地，不是重新设计</b>：§5.7 标注「本表为对外稳定码权威源」，
 * 故此处<b>完整实现全表</b>（含 P2/P3 才会用到的 B 阶段与准入码），
 * 而不是只截取 Phase A 子集。理由：
 * <ul>
 *   <li>原因码是<b>对外稳定契约</b>，P2/P3 落地时若再补枚举，等于让已发布的行为码漂移；</li>
 *   <li>§5.7 要求「变更须版本化并保持兼容策略」——一次落地完整表，比分批追加更符合该要求；</li>
 *   <li>本枚举<b>不含任何判定逻辑</b>，多出来的常量不产生行为，只是不被引用。</li>
 * </ul>
 *
 * <p>⚠️ <b>不得重命名或复用已发布常量</b>：码一旦对外出现即为契约，
 * 废弃须新增常量并保留旧值（§5.7 兼容策略）。
 */
public enum ReasonCode {

    /** 成功。§9.1 规定成功路径固定为此码。 */
    SUCCESS("—"),

    /** 授权版本陈旧（Phase A）。主体上下文携带的版本落后于权威源。 */
    AUTHZ_STALE("A"),

    /** 角色不足（Phase A）。主体角色集不包含目标动作所需的任何角色。 */
    AUTHZ_ROLE_MISSING("A"),

    /** 版本服务不可用（Phase A / 准入）。读不到权威 authzVersion → fail-closed。 */
    AUTHZ_UNAVAILABLE("A/准入"),

    /** Skill 未启用（Phase A）。目标 Skill 不在 Agent 的启用集内。 */
    SKILL_NOT_ENABLED("A"),

    /** Tool 未声明（Phase A）。目标 Tool 不在 Skill 的声明集内。 */
    TOOL_NOT_DECLARED("A"),

    /** 风险超限（Phase A）。Tool 实际风险高于 Skill 的 riskCeiling。 */
    RISK_EXCEEDS_CEILING("A"),

    /** 风险未知/非法（Phase A）。Tool 风险等级缺失或不在取值域内。 */
    RISK_LEVEL_UNKNOWN("A"),

    /** 参数策略失败（Phase A）。违反强制安全 / Tool 策略 / Skill 策略。 */
    PARAM_POLICY_FAIL("A"),

    /** 闭包指纹失效（Phase A）。本 Run 依赖对象的闭包内容已变化。 */
    CONFIG_FINGERPRINT_STALE("A"),

    /** 未声明 MCP server（Phase A）。目标 MCP server 不在 Skill 的声明集内。 */
    MCP_NOT_BOUND("A"),

    /** 操作不在允许集（Phase A）。请求的 MCP 操作不在快照 AllowedOps 内。 */
    MCP_OP_NOT_ALLOWED("A"),

    /** 当前清单与快照不一致/下线（Phase A）。 */
    MCP_TOOL_DRIFTED("A"),

    /** 逻辑 id 解析到未绑定版本（Phase A）。 */
    RESOURCE_VERSION_MISMATCH("A"),

    /** 对象不存在（Phase A）。 */
    RESOURCE_UNKNOWN("A"),

    /** 结构校验失败（Phase B）。 */
    PARAM_SCHEMA_FAIL("B"),

    /** 审批不存在/无效（Phase B）。 */
    APPROVAL_NOT_FOUND("B"),

    /** 审批过期（Phase B）。 */
    APPROVAL_EXPIRED("B"),

    /** 参数不在审批范围/绑定不匹配（Phase B）。 */
    APPROVAL_SCOPE_MISMATCH("B"),

    /** 已被其他调用预占（Phase B）。 */
    APPROVAL_RESERVED_BY_OTHER("B"),

    /** 已消费后重放（Phase B）。 */
    APPROVAL_REPLAY_REJECTED("B"),

    /** 预算不足（Phase B）。 */
    BUDGET_EXCEEDED("B"),

    /** 同幂等键冲突（Phase B）。 */
    IDEMPOTENCY_CONFLICT("B"),

    /** 准入点版本/指纹失效（准入阶段）。 */
    EXEC_ADMISSION_STALE("准入"),

    /** 副作用结果不确定（执行后）。<b>唯一需要进对账的码</b>。 */
    EXEC_UNKNOWN("执行后");

    private final String phase;

    ReasonCode(@NonNull String phase) {
        this.phase = phase;
    }

    /**
     * @return 所属阶段标识（§5.7 表格第二列）：{@code A} / {@code B} / {@code 准入} / {@code 执行后} / {@code —}
     */
    public @NonNull String phase() {
        return phase;
    }

    /**
     * @return 是否为 Phase A（授权阶段）原因码
     */
    public boolean isPhaseA() {
        return "A".equals(phase) || "A/准入".equals(phase);
    }

    /**
     * 按码文本解析（大小写敏感，<b>不做容错</b>）。
     *
     * <p>⚠️ <b>解析失败返回 {@code null} 而非抛异常</b>：调用方据此走 fail-closed 分支
     * （拒绝而非放行）。若此处抛异常，异常会沿调用栈上抛，
     * 在没有统一决策封装的调用点上可能被误当成「系统错误」而<b>跳过拒绝逻辑</b>。
     *
     * @param code 码文本
     * @return 匹配的原因码；无匹配时为 {@code null}
     */
    public static @Nullable ReasonCode parse(@Nullable String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (ReasonCode candidate : values()) {
            if (candidate.name().equals(code)) {
                return candidate;
            }
        }
        return null;
    }
}
