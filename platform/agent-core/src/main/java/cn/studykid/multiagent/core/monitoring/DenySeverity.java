package cn.studykid.multiagent.core.monitoring;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.core.cap.ReasonCode;

/**
 * 拒绝事件严重级别（管理面 P4 运行监控）。
 *
 * <p><b>本枚举回答的是「这条拒绝要不要现在处理」</b>，不是「这次调用成功了吗」。
 * 决策日志里的一次 DENY 可能有完全不同的运维含义：
 * <ul>
 *   <li>越权尝试（角色不足 / 策略失败）—— 有人在试探边界，必须立刻看；</li>
 *   <li>配置漂移（Skill 未启用 / Tool 未声明）—— 配置问题，走常规变更流程；</li>
 *   <li>预算超限 / 幂等冲突 —— 业务节流，属于预期内的常态拒绝。</li>
 * </ul>
 * 把三者混在一个「拒绝计数」里，等于让告警要么全红要么全绿，
 * <b>失去分诊能力</b>。
 *
 * <p>⚠️ <b>映射口径是「稳定契约」</b>：{@link #of(ReasonCode)} 的分档一旦对外
 * 展示即构成运维约定（告警规则会引用它），故只允许<b>新增原因码时显式定档</b>，
 * 不得把已定档的码改档 —— 改档会让历史告警的分级前后不可比。
 *
 * <p>⚠️ <b>未知码降级为 {@link #WARNING} 而非 {@link #INFO}</b>：
 * 原因码表会随 §5.7 的兼容策略持续追加。若新码默认落 INFO，
 * 运维侧的「仅 INFO 不告警」规则会把它静默吞掉 —— 那等于
 * 「没定档 = 不用管」，与 fail-closed 的整体取向相反。
 * 落 WARNING 意味着「需要人工判读一次」，代价是多看一眼，
 * 收益是不会漏掉。
 */
public enum DenySeverity {

    /**
     * 严重：安全边界被触碰或授权依据失效。
     *
     * <p>典型场景：版本陈旧仍放行、角色不足被拒、强制安全策略失败、
     * 闭包指纹失效、风险超限、MCP 操作越出允许集。
     *
     * <p>运维动作：<b>立刻排查是否有人在试探权限边界</b>。
     */
    CRITICAL,

    /**
     * 警告：配置/治理层面的拒绝，需在常规变更窗口内修正。
     *
     * <p>典型场景：Skill 未启用、Tool 未声明、MCP 未绑定、资源漂移、
     * 以及<b>尚未定档的新增原因码</b>。
     *
     * <p>运维动作：检查配置与声明是否对齐，必要时走发布流程。
     */
    WARNING,

    /**
     * 提示：业务节流或流程性拒绝，属预期内常态。
     *
     * <p>典型场景：预算超限、审批过期、幂等键冲突、参数结构校验失败。
     *
     * <p>运维动作：一般不告警，仅在趋势异常时回看。
     */
    INFO;

    /**
     * 按原因码定档。
     *
     * <p>⚠️ <b>本方法对 {@link ReasonCode} 是穷尽的</b>：
     * 采用 switch 表达式且<b>不设 default 分支</b>，
     * 目的是让「原因码表新增常量却忘了定档」在<b>编译期</b>暴露，
     * 而不是上线后悄悄落到某个兜底档。
     *
     * @param reasonCode 原因码（{@code null} 按 {@link #WARNING} 处理）
     * @return 严重级别
     */
    public static @NonNull DenySeverity of(@NonNull ReasonCode reasonCode) {
        return switch (reasonCode) {
            // ── 安全边界：越权 / 依据失效 ──────────────────────────
            case AUTHZ_STALE -> CRITICAL;
            case AUTHZ_ROLE_MISSING -> CRITICAL;
            case AUTHZ_UNAVAILABLE -> CRITICAL;
            case PARAM_POLICY_FAIL -> CRITICAL;
            case CONFIG_FINGERPRINT_STALE -> CRITICAL;
            case RISK_EXCEEDS_CEILING -> CRITICAL;
            case RISK_LEVEL_UNKNOWN -> CRITICAL;
            case MCP_NOT_BOUND -> CRITICAL;
            case MCP_OP_NOT_ALLOWED -> CRITICAL;
            case MCP_TOOL_DRIFTED -> CRITICAL;
            case RESOURCE_VERSION_MISMATCH -> CRITICAL;
            // ⚠️ 多级 Agent：委派目标未声明 = 越出父 Agent 声明的委派边界。
            //    这与「角色不足」同类 —— 有东西在试探边界，必须立刻看
            //    （典型来源：路由模型被注入后指向未声明的 Agent，MA-T05）。
            case DELEGATION_TARGET_NOT_DECLARED -> CRITICAL;
            // ⚠️ 路由候选越出**策略允许的子集**（第十四批）：与「越出父声明的委派拓扑」
            //    同类 —— 都是「有东西在试探边界」。典型来源同样是路由模型被注入后
            //    指向了一个存在但策略不允许的 Agent（MA-T05）。必须立刻看。
            case ROUTING_TARGET_NOT_ALLOWED -> CRITICAL;
            // ⚠️ 路由策略**不可用**（第十四批）：这是「路由依据失效」，
            //    与 AUTHZ_UNAVAILABLE 同类 —— 判据拿不到，就无法做路由决策。
            //    若落 INFO/WARNING，会被当成「偶发一次」，而实际后果是
            //    「引用它的每个 ROUTER Agent 的每次委派都在 fail-closed」。
            case ROUTING_POLICY_UNAVAILABLE -> CRITICAL;
            // ⚠️ EXEC_UNKNOWN 是「副作用结果不确定」——§7.4 唯一需要进人工对账的码。
            //    它不是「没成功」，而是「不知道成没成」：自动重试会造成重复副作用，
            //    只能由人工判定。故与越权同级，不能落 INFO。
            case EXEC_UNKNOWN -> CRITICAL;
            // ⚠️ 远程 HTTP 出站被拦截（第三十七批）：这是**安全边界**被触碰 ——
            //    目标解析后落在 loopback / 私网 / 链路本地 / 云元数据地址。
            //    与「配置期形态非法」（HTTP_ENDPOINT_INVALID → WARNING）必须分档：
            //    前者是「有人在请求内网」，后者是「配置填错了」，
            //    排查方向相反，混档会让安全事件被当成普通配置缺陷放过。
            case HTTP_EGRESS_BLOCKED -> CRITICAL;

            // ── 配置与治理：声明/漂移 ─────────────────────────────
            case SKILL_NOT_ENABLED -> WARNING;
            case TOOL_NOT_DECLARED -> WARNING;
            case RESOURCE_UNKNOWN -> WARNING;
            case EXEC_ADMISSION_STALE -> WARNING;
            case EXECUTION_PERSISTENCE_FAILED -> WARNING;
            // 多级 Agent：发布期配置缺陷（未发布引用 / 成环 / 编排声明非法）
            // 与「子 Agent 边界越界」——都需要走常规变更流程修正。
            case AGENT_REF_NOT_PUBLISHED -> WARNING;
            case AGENT_REF_CYCLE -> WARNING;
            case ORCHESTRATION_INVALID -> WARNING;
            // ⚠️ 输出契约引用未发布：与「子 Agent 引用未发布」同类 ——
            //    都是「引用了一个还没生效的固定版本」，走常规变更流程修正。
            case RESULT_CONTRACT_NOT_PUBLISHED -> WARNING;
            // ⚠️ 路由策略引用未发布（第十四批）：与「输出契约引用未发布」同类 ——
            //    都是「引用了一个还没生效的固定版本」，走常规变更流程修正。
            case ROUTING_POLICY_NOT_PUBLISHED -> WARNING;
            // ⚠️ 输出契约 / 路由策略**已停用**（第二十五批）：与「引用未发布」同类 ——
            //    都是「发布期的配置缺陷」，走常规变更流程修正。
            //    ⚠️ 与「未发布」必须分档到<b>不同原因码</b>（不是不同档位）：
            //    两者排查方向相反（查发布流程 vs 查停用记录），分档相同、码不同。
            case RESULT_CONTRACT_DEPRECATED -> WARNING;
            case ROUTING_POLICY_DEPRECATED -> WARNING;
            // ⚠️ HTTP 目标**形态**非法（第三十七批）：发布期配置缺陷 ——
            //    方法不在白名单 / 协议非法 / URL 内嵌凭据 / 超时越界。
            //    这是「配置填错了」，走常规变更流程修正，不惊动安全值班。
            //    ⚠️ 与「出站被拦截」（HTTP_EGRESS_BLOCKED → CRITICAL）分档，
            //    两者排查方向相反。
            case HTTP_ENDPOINT_INVALID -> WARNING;
            case DELEGATION_NOT_ALLOWED -> WARNING;
            case DELEGATION_DEPTH_EXCEEDED -> WARNING;
            case DELEGATION_TIMEOUT -> WARNING;
            case CHILD_RESULT_INVALID -> WARNING;

            // ── 业务节流与流程性拒绝：常态 ───────────────────────
            case SUCCESS -> INFO;
            case PARAM_SCHEMA_FAIL -> INFO;
            case APPROVAL_NOT_FOUND -> INFO;
            case APPROVAL_EXPIRED -> INFO;
            case APPROVAL_SCOPE_MISMATCH -> INFO;
            case APPROVAL_RESERVED_BY_OTHER -> INFO;
            case APPROVAL_REPLAY_REJECTED -> INFO;
            case BUDGET_EXCEEDED -> INFO;
            case IDEMPOTENCY_CONFLICT -> INFO;
            case IDEMPOTENCY_UNAVAILABLE -> INFO;
            case IDEMPOTENCY_IN_PROGRESS -> INFO;
            case RUN_CANCELLED -> INFO;
            case TOOL_EXECUTION_FAILED -> INFO;
            // 多级 Agent：子调用次数上限与发布 CAS 冲突都是<b>预期内</b>的节流/并发结果
            case CHILD_INVOCATION_LIMIT_EXCEEDED -> INFO;
            case AGENT_RELEASE_CONFLICT -> INFO;
        };
    }
}
