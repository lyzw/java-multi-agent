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
 *
 * <p>⚠️ <b>P2 追加了三个常量</b>（{@link #IDEMPOTENCY_UNAVAILABLE}、
 * {@link #RUN_CANCELLED}、{@link #TOOL_EXECUTION_FAILED}）。
 * §5.7 原表未覆盖这三类情形，而 §5.3 步骤 8 与 §9.1 S3f 又明确要求
 * 「幂等存储不可用 → fail-closed」与「确定失败 → 稳定失败码」。
 * 按 §5.7「变更须版本化并保持兼容策略」，此处采取<b>只追加、不改写</b>的方式补齐：
 * 已发布常量一律不改名、不复用、不改语义。三个新增码的缺口分析见各常量注释。
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
    EXEC_UNKNOWN("执行后"),

    // ── P2 追加（§5.7 兼容策略下的向后兼容新增）────────────────────
    // ⚠️ 以下三个码是 P2 落地 §5.3 时**发现 §5.7 原表未覆盖**的场景，
    //    按 §5.7「变更须版本化并保持兼容策略」以**追加**方式补齐：
    //    已发布常量一律不改名、不复用、不改语义。

    /**
     * 幂等存储不可用（Phase B）。
     *
     * <p>§5.3 步骤 8 明确「幂等存储不可用 → fail-closed」，但 §5.7 原表
     * 只有「同键冲突」（{@link #IDEMPOTENCY_CONFLICT}）——
     * 后者语义是「键相同但参数不同」，与「存储读不到」完全不同。
     * 若把存储不可用也报成 {@code IDEMPOTENCY_CONFLICT}，
     * 运维会去排查「参数被改」，而真实原因是「幂等存储挂了」——
     * 排查方向完全错误。故追加本码。
     */
    IDEMPOTENCY_UNAVAILABLE("B"),

    /**
     * 同一逻辑操作<b>正在执行中</b>（Phase B）。
     *
     * <p>§5.3 步骤 8 明确要求区分「已完成 → 返回首次结果」与
     * 「进行中 → 按契约阻塞或返回进行中」两种情形，
     * 但 §5.7 原表只有「同键冲突」（{@link #IDEMPOTENCY_CONFLICT}）——
     * 后者语义是「键相同但参数不同」，与「同一操作仍在跑」是完全不同的两件事。
     * 二者混用会让运维把「重复投递」误判成「参数被篡改」。
     */
    IDEMPOTENCY_IN_PROGRESS("B"),

    /**
     * 副作用<b>之前</b>的持久化失败（Phase B）。
     *
     * <p>§5.3 步骤 13a 要求「执行意图必须先于外部调用持久化」。
     * 意图写不进去时<b>绝不能</b>照常执行 ——
     * 那样一旦崩溃，恢复方无从判断副作用是否已经发生，
     * 只能按 UNKNOWN 人工处置，等于把本可自动恢复的场景变成人工工单。
     * 故此处拒绝执行并给出独立原因码：
     * 它与「幂等存储不可用」分属不同存储、不同后果，运维处置方向不同。
     */
    EXECUTION_PERSISTENCE_FAILED("B"),

    /**
     * Run 在准入过程中被取消（Phase B）。
     *
     * <p>取消是 Run 的<b>状态</b>而非授权结论，但它同样导致「不启动副作用」，
     * 需要一个可区分的原因码：审批端口的 {@code RUN_CANCELLED} 结局
     * 与「审批已拒绝」在语义上完全不同（前者是主动中止，后者是策略结论）。
     */
    RUN_CANCELLED("B"),

    /**
     * 工具<b>明确</b>失败（结果确定，可重试语义由调用方按原因码决定）。
     *
     * <p>§9.1 S3f 要求确定失败时给出「稳定失败码」，但 §5.7 原表
     * 只覆盖到执行前的准入类码与执行后的 {@link #EXEC_UNKNOWN}，
     * <b>没有</b>「执行了但明确失败」的码。
     * 复用 {@link #SUCCESS} 会把失败说成成功，复用 {@link #EXEC_UNKNOWN}
     * 会把「确定失败」误报成「结果不确定」并凭空造出待对账的幽灵操作
     * （§5.1 明令 UNKNOWN 仅表示副作用结果不确定）。
     */
    TOOL_EXECUTION_FAILED("执行后");

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
