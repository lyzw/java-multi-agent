package cn.studykid.multiagent.application.cap;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.cap.AuthorizationDecision;
import cn.studykid.multiagent.core.cap.CapabilityRiskLevel;
import cn.studykid.multiagent.core.cap.CapabilitySnapshot;
import cn.studykid.multiagent.core.cap.EffectiveClosure;
import cn.studykid.multiagent.core.cap.ReasonCode;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.mcp.McpManifestPort;
import cn.studykid.multiagent.ports.persistence.AuthzVersionPort;
import cn.studykid.multiagent.ports.persistence.CapabilitySnapshotPort;
import cn.studykid.multiagent.ports.security.AuthorizationService;
import cn.studykid.multiagent.ports.security.RolePermissionPort;

/**
 * Phase A 授权校验（§7.1 {@code InvocationValidator} · §5.2 授权公式 · §5.3 步骤 2–7）。
 *
 * <p>§5.2：{@code Allow_auth = PrincipalAuthorized ∧ AgentSkillAllowed ∧ SkillToolAllowed
 * ∧ RiskWithinCeiling ∧ MCPBindingAllowed ∧ ParamPolicyPassed}，
 * 外加 §5.3 步骤 7 的 {@code ConfigFingerprintValid}。
 *
 * <p>⚠️ <b>顺序是契约，不是实现细节</b>（§5.3「本小节为全文档唯一顺序与语义」）：
 * <ol>
 *   <li><b>主体授权最先</b>（步骤 2）。主体无权时，<b>不解析闭包</b> ——
 *       既避免向无权主体暴露能力面，也避免为无权调用做无用功。</li>
 *   <li>闭包解析在主体授权通过之后（步骤 3）。</li>
 *   <li>声明关系 → 风险上限 → MCP 绑定 → 参数策略 → 指纹，逐因子短路。</li>
 * </ol>
 * 短路顺序保证了<b>原因码最贴近根因</b>：例如「Skill 未启用」不会被报成「Tool 未声明」。
 *
 * <p>⚠️ <b>全链路 fail-closed</b>：读不到授权版本、闭包解析不出、指纹对不上 ——
 * 一律拒绝并给出 §5.7 的稳定原因码，<b>绝不降级放行</b>。
 *
 * <p>⚠️ <b>本类只做判定，不做副作用</b>：不写库、不发消息、不调用 Tool。
 * 「写决策日志」属 §5.3 步骤 7 的后续动作（P2/P5 的审计链路），本阶段不实现。
 */
public class InvocationValidator {

    private final AuthzVersionPort authzVersions;
    private final RolePermissionPort rolePermissions;
    private final ClosureResolver resolver;
    private final CapabilitySnapshotPort snapshots;
    private final McpManifestPort manifest;

    public InvocationValidator(@NonNull AuthzVersionPort authzVersions,
                               @NonNull RolePermissionPort rolePermissions,
                               @NonNull ClosureResolver resolver,
                               @NonNull CapabilitySnapshotPort snapshots,
                               @NonNull McpManifestPort manifest) {
        this.authzVersions = authzVersions;
        this.rolePermissions = rolePermissions;
        this.resolver = resolver;
        this.snapshots = snapshots;
        this.manifest = manifest;
    }

    /**
     * MCP 操作目标（§4.4 意义下的「op」）。
     *
     * @param serverId MCP Server 标识
     * @param op       MCP 远端操作名
     */
    public record McpTarget(@NonNull String serverId, @NonNull String op) {
    }

    /**
     * 一次工具调用的授权校验请求。
     *
     * @param context   可信上下文（身份/租户/角色/授权版本，<b>不得来自客户端输入</b>，§5.3 步骤 1）
     * @param agentId   目标 Agent
     * @param skillId   目标 Skill（MCP 工具同样归属于某个 Skill）
     * @param toolId    目标 Tool
     * @param arguments 实际入参（供参数策略求值）
     * @param runId     所属 Run（用于读取闭包快照；<b>为空则无法校验指纹 → 拒绝</b>）
     * @param mcpTarget MCP 操作目标；原生工具传 {@code null}
     * @param expectedFingerprint <b>期望的闭包指纹</b>；为 {@code null} 时按 {@code runId}
     *                             读 Run 快照比对（单 Agent 路径），
     *                             非空时直接与之比对（<b>子 Agent 路径</b>，
     *                             见 {@link cn.studykid.multiagent.core.cap.ExecutionScope}）
     */
    public record InvocationRequest(
            @NonNull TenantContext context,
            @NonNull String agentId,
            @NonNull String skillId,
            @NonNull String toolId,
            @NonNull Map<String, Object> arguments,
            @Nullable String runId,
            @Nullable McpTarget mcpTarget,
            @Nullable String expectedFingerprint) {

        /**
         * 兼容构造器：单 Agent 路径（指纹按 Run 快照比对）。
         */
        public InvocationRequest(@NonNull TenantContext context,
                                 @NonNull String agentId,
                                 @NonNull String skillId,
                                 @NonNull String toolId,
                                 @NonNull Map<String, Object> arguments,
                                 @Nullable String runId,
                                 @Nullable McpTarget mcpTarget) {
            this(context, agentId, skillId, toolId, arguments, runId, mcpTarget, null);
        }
    }

    /**
     * 执行 Phase A 全因子校验。
     *
     * @param request 校验请求
     * @return 决策结果（授权通过为 ALLOW，否则 DENY + NOT_EVALUATED + NOT_STARTED + 原因码）
     */
    public @NonNull AuthorizationDecision validate(@NonNull InvocationRequest request) {
        // ── 步骤 2：PrincipalAuthorized（主体授权最先）────────────────
        AuthorizationDecision principalFailure = checkPrincipal(request.context());
        if (principalFailure != null) {
            return principalFailure;
        }

        // ── 步骤 3：PermissionResolver → EffectiveClosure ─────────────
        EffectiveClosure closure;
        try {
            closure = resolver.resolveCurrent(request.context().tenantId(), request.agentId());
        } catch (ClosureResolutionException e) {
            return AuthorizationDecision.deny(e.reasonCode());
        }

        // ── 步骤 4：AgentSkillAllowed → SkillToolAllowed → RiskWithinCeiling ──
        Optional<EffectiveClosure.@NonNull SkillBinding> skill = closure.skill(request.skillId());
        if (skill.isEmpty()) {
            // S2 未启用 Skill / S4a Skill 从 enabled 移除
            return AuthorizationDecision.deny(ReasonCode.SKILL_NOT_ENABLED);
        }
        EffectiveClosure.SkillBinding skillBinding = skill.get();

        Optional<EffectiveClosure.@NonNull ToolBinding> tool = closure.tool(request.toolId());
        boolean mcpDeclared = tool.map(EffectiveClosure.ToolBinding::isMcp).orElse(false);
        if (tool.isEmpty() && request.mcpTarget() == null) {
            // 目标工具不在任何<b>已启用</b> Skill 的声明集内
            return AuthorizationDecision.deny(ReasonCode.TOOL_NOT_DECLARED);
        }

        // MCP 目标：优先取自闭包内的工具绑定；工具不在闭包内时用请求携带的目标
        McpTarget target = null;
        if (mcpDeclared) {
            EffectiveClosure.ToolBinding binding = tool.orElseThrow();
            target = new McpTarget(binding.mcpServerId(), binding.mcpToolName());
            if (request.mcpTarget() != null
                    && (!request.mcpTarget().serverId().equals(target.serverId())
                        || !request.mcpTarget().op().equals(target.op()))) {
                // 请求声明的 MCP 操作与闭包绑定的操作不一致 → 拒绝（fail-closed）
                return AuthorizationDecision.deny(ReasonCode.RESOURCE_VERSION_MISMATCH);
            }
        } else if (request.mcpTarget() != null) {
            target = request.mcpTarget();
        }

        // 风险上限（对原生工具与 MCP 工具同源：Tool 的 riskLevel vs Skill 的 riskCeiling）
        if (tool.isPresent()) {
            CapabilityRiskLevel risk = tool.get().riskLevel();
            if (risk.isUnknown()) {
                // ⚠️ 未知风险必须单独成码，**不得降级为 WRITE_EXTERNAL**（§5.6 / P1 退出门禁）
                return AuthorizationDecision.deny(ReasonCode.RISK_LEVEL_UNKNOWN);
            }
            if (risk.exceeds(skillBinding.riskCeiling())) {
                return AuthorizationDecision.deny(ReasonCode.RISK_EXCEEDS_CEILING);
            }
        }

        // ── 步骤 5：MCPBindingAllowed（§5.8 顺序）────────────────────
        if (target != null) {
            AuthorizationDecision mcpFailure =
                    checkMcpBinding(request, closure, skillBinding, target, tool.orElse(null));
            if (mcpFailure != null) {
                return mcpFailure;
            }
        }

        // ── 步骤 6：ParamPolicyPassed ────────────────────────────────
        AuthorizationDecision paramFailure = checkParamPolicies(skillBinding, tool.orElse(null),
                request.arguments());
        if (paramFailure != null) {
            return paramFailure;
        }

        // ── 步骤 7：ConfigFingerprintValid ───────────────────────────
        AuthorizationDecision fingerprintFailure = checkFingerprint(request, closure);
        if (fingerprintFailure != null) {
            return fingerprintFailure;
        }

        return AuthorizationDecision.allow();
    }

    // ── 步骤 2：主体授权 ───────────────────────────────────────────

    /**
     * 主体授权判定。
     *
     * <p>⚠️ <b>版本先于角色</b>：上下文携带的版本落后于权威源时，
     * 说明该上下文<b>产生于撤权之前</b> —— 此时上下文里的角色集同样不可信，
     * 先报 {@code AUTHZ_STALE} 才能指向真正的根因（撤权），而不是「角色不足」。
     *
     * @return 失败时返回拒绝决策；通过时返回 {@code null}
     */
    private @Nullable AuthorizationDecision checkPrincipal(@NonNull TenantContext context) {
        long current;
        try {
            current = authzVersions.require(context.tenantId(), context.principalId()).authzVersion();
        } catch (RuntimeException e) {
            // ⚠️ 读不到权威版本 = 版本服务不可用 → fail-closed（§5.5）
            return AuthorizationDecision.deny(ReasonCode.AUTHZ_UNAVAILABLE);
        }
        if (context.authzVersion() < current) {
            // 上下文版本陈旧 → 授权已被撤销（§5.4 / S3 / S3a）
            return AuthorizationDecision.deny(ReasonCode.AUTHZ_STALE);
        }
        if (!rolePermissions.permits(context.roles(), AuthorizationService.Actions.TOOL_INVOKE)) {
            return AuthorizationDecision.deny(ReasonCode.AUTHZ_ROLE_MISSING);
        }
        return null;
    }

    // ── 步骤 5：MCP 绑定（§5.8）────────────────────────────────────

    /**
     * MCP 绑定校验（§5.8 四步顺序）。
     *
     * <p>顺序<b>不可调换</b>：§5.8 明确规定「同一类变化不得两种码」，
     * 因此清单漂移必须<b>先</b>由指纹新鲜度捕获（步骤 1），
     * 只有指纹未涵盖的变化才落到 {@code MCP_TOOL_DRIFTED}（步骤 3）。
     */
    private @Nullable AuthorizationDecision checkMcpBinding(
            @NonNull InvocationRequest request,
            @NonNull EffectiveClosure closure,
            EffectiveClosure.@NonNull SkillBinding skill,
            @NonNull McpTarget target,
            EffectiveClosure.@Nullable ToolBinding tool) {

        // §5.8 步骤 1：闭包指纹新鲜度
        AuthorizationDecision stale = checkFingerprint(request, closure);
        if (stale != null) {
            return stale;
        }

        // §5.8 步骤 2：请求 op ∈ 快照 AllowedOps / ClosureOps
        Optional<EffectiveClosure.McpBinding> edge =
                closure.mcpBinding(skill.skillId(), target.serverId());
        if (edge.isEmpty()) {
            // Skill 未声明该 MCP server
            return AuthorizationDecision.deny(ReasonCode.MCP_NOT_BOUND);
        }
        if (!edge.get().allowedOps().contains(target.op())) {
            return AuthorizationDecision.deny(ReasonCode.MCP_OP_NOT_ALLOWED);
        }

        // §5.8 步骤 3：请求 op 仍 ∈ 当前 ManifestOps
        // ⚠️ 清单「不可用」与「不含该 op」给同一个码（MCP_TOOL_DRIFTED）：
        //    两者在授权语义上是同一件事 ——「无法确认该 op 仍在当前清单内」。
        //    区分「宕机」与「下线」靠日志，不靠原因码（原因码是对外稳定契约，不能随意分化）。
        McpManifestPort.ManifestSnapshot live = manifest.manifest(target.serverId());
        if (!live.available() || !live.ops().contains(target.op())) {
            return AuthorizationDecision.deny(ReasonCode.MCP_TOOL_DRIFTED);
        }

        // §5.8 步骤 4：逻辑 id 映射到未绑定新版本
        if (tool == null) {
            // 闭包内找不到该操作绑定的工具 → 逻辑 id 解析到了未绑定的版本
            return AuthorizationDecision.deny(ReasonCode.RESOURCE_VERSION_MISMATCH);
        }
        if (!target.serverId().equals(tool.mcpServerId()) || !target.op().equals(tool.mcpToolName())) {
            return AuthorizationDecision.deny(ReasonCode.RESOURCE_VERSION_MISMATCH);
        }
        return null;
    }

    // ── 步骤 6：参数策略 ───────────────────────────────────────────

    /**
     * 参数策略判定（§4.3）。
     *
     * <p>三层合并后求值：强制安全 ∧ Tool 策略 ∧ Skill 策略。
     * Skill 层与 Tool 层用同一套合并逻辑，这正是「Skill 只能收紧」的机制。
     */
    private @Nullable AuthorizationDecision checkParamPolicies(
            EffectiveClosure.@NonNull SkillBinding skill,
            EffectiveClosure.@Nullable ToolBinding tool,
            @NonNull Map<String, Object> arguments) {
        if (tool == null) {
            // 没有工具定义就没有可求值的策略（MCP 目标此时已被步骤 5 拒绝）
            return null;
        }
        try {
            ParamPolicyEngine.Evaluation evaluation = ParamPolicyEngine.evaluate(
                    ParamPolicyEngine.parse(tool.paramPolicies()),
                    ParamPolicyEngine.parse(skill.paramPolicies()),
                    arguments);
            if (!evaluation.passed()) {
                return AuthorizationDecision.deny(ReasonCode.PARAM_POLICY_FAIL);
            }
        } catch (ParamPolicyConfigException e) {
            // ⚠️ 策略本身非法（未知 op 等）→ 配置缺陷 → 同样拒绝（fail-closed）
            return AuthorizationDecision.deny(ReasonCode.PARAM_POLICY_FAIL);
        }
        return null;
    }

    // ── 步骤 7：闭包指纹 ───────────────────────────────────────────

    /**
     * 闭包指纹新鲜度校验（§5.4 / §6.4）。
     *
     * <p>口径：把「当前解析出的闭包」的指纹与「Run 快照里固化的指纹」比较。
     * 不一致 → 本 Run 依赖的对象内容已变 → 拒绝。
     *
     * <p>⚠️ <b>无关对象变更不触发失效</b>：指纹只覆盖闭包内的对象，
     * 且<b>不含</b>全局发布序号（§7.3）。
     */
    private @Nullable AuthorizationDecision checkFingerprint(@NonNull InvocationRequest request,
                                                            @NonNull EffectiveClosure closure) {
        String current = ClosureFingerprint.of(closure);
        // ⚠️ 子 Agent 路径：期望指纹由**委派时算出的子 Agent 闭包**给出，
        //    直接比对，不读 Run 快照（Run 快照是根 Agent 的）。
        if (request.expectedFingerprint() != null) {
            if (!current.equals(request.expectedFingerprint())) {
                return AuthorizationDecision.deny(ReasonCode.CONFIG_FINGERPRINT_STALE);
            }
            return null;
        }
        if (request.runId() == null || request.runId().isBlank()) {
            // 没有 Run 就没有可校验的闭包快照 → 无法证明指纹有效 → 拒绝
            return AuthorizationDecision.deny(ReasonCode.CONFIG_FINGERPRINT_STALE);
        }
        Optional<CapabilitySnapshot> snapshot =
                snapshots.find(request.context().tenantId(), request.runId());
        if (snapshot.isEmpty()) {
            return AuthorizationDecision.deny(ReasonCode.RESOURCE_UNKNOWN);
        }
        if (!current.equals(snapshot.get().configFingerprint())) {
            return AuthorizationDecision.deny(ReasonCode.CONFIG_FINGERPRINT_STALE);
        }
        return null;
    }
}
