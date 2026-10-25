package cn.studykid.multiagent.application.orchestration;

import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.cap.AuthorizationDecision;
import cn.studykid.multiagent.core.cap.CapAgentDef;
import cn.studykid.multiagent.core.cap.ReasonCode;
import cn.studykid.multiagent.core.orchestration.AgentOrchestration;
import cn.studykid.multiagent.core.orchestration.ChildAgentRef;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.AuthzVersionPort;
import cn.studykid.multiagent.ports.persistence.CapAgentDefPort;
import cn.studykid.multiagent.ports.security.AuthorizationService;

/**
 * 委派守卫（多级 Agent v1.1 · FR-024 / FR-025 · 07 变更补充「委派时序」）。
 *
 * <p>⚠️ <b>委派不授予权限</b>：本类只判定「这次委派<b>允许发生</b>吗」，
 * <b>不</b>把子 Agent 的能力并入父 Agent，也不允许父继承子的一切。
 * 子 Agent 内部的工具调用仍走原 T3 门禁（{@code ToolInvocationGate}）。
 *
 * <p>⚠️ <b>逐跳重新授权</b>（FR-025）：每一跳都用自己的主体上下文与<b>当前</b>
 * {@code authzVersion} 重新判定，绝不继承父 Agent 的授权结论。
 * 上下文版本落后于权威源 → {@code AUTHZ_STALE}；读不到权威版本 →
 * {@code AUTHZ_UNAVAILABLE}（fail-closed）。
 *
 * <p>⚠️ <b>路由模型输出只是候选</b>（NFR-013 / MA-T05）：目标必须落在父 Agent
 * 声明的 {@code childAgentRefs} 内且版本一致，否则一律拒绝且<b>不触发子执行</b>。
 *
 * <p>⚠️ <b>共享根 Run 的上限</b>（NFR-012 / MA-T17）：深度、子调用次数、超时与预算
 * 是<b>整棵调用树共享</b>的，不能通过嵌套 Agent 重置额度。
 *
 * <p>⚠️ <b>时间预算是「封顶」而非「否决」</b>：有效预算 =
 * {@code min(根 Run 剩余时间, 本级 timeoutMs)}。
 * 「根 Run 剩余时间比本级 timeout 长」是常态（子预算本就是子集），
 * 若据此拒绝，所有合法委派都会被判超时 —— 本级 timeout 仍作为上界生效。
 */
public class DelegationGuard {

    /**
     * 预算探针（Run 级剩余额度）。
     *
     * <p>⚠️ <b>不提供默认实现</b>：构造期必须显式注入。
     * 缺省「认为还有预算」等于让嵌套 Agent 绕过预算上限（MA-T17），
     * 缺省「认为没预算」则让未接入预算的链路全量拒绝 —— 前者更危险，故必须显式接线。
     */
    @FunctionalInterface
    public interface BudgetProbe {
        /**
         * @param tenantId 租户标识
         * @param runId    根 Run 标识
         * @return 该 Run 是否还有剩余预算
         */
        boolean hasRemaining(@NonNull String tenantId, @NonNull String runId);
    }

    /**
     * 一次委派的判定请求。
     *
     * @param context              可信主体上下文（<b>不得来自客户端输入</b>）
     * @param runId                根 Run 标识（预算/超时共享域）
     * @param parentAgentId        父 Agent 标识
     * @param parentAgentVersion   父 Agent 的固定版本号
     * @param targetAgentId        目标 Agent 标识（候选）
     * @param targetAgentVersion   目标 Agent 的版本号（必须与声明的固定版本一致）
     * @param depth                委派<b>后</b>的深度（根 Agent = 1，直接子调用 = 2）
     * @param childInvocationsSoFar 本父 Agent 已发起的子调用次数
     * @param remainingMillis      根 Run 剩余可用时间（毫秒）；
     *                             有效时间预算 = min(本值, 本级声明的 timeoutMs) ——
     *                             本级 timeout 永远是上界
     */
    public record DelegationRequest(
            @NonNull TenantContext context,
            @NonNull String runId,
            @NonNull String parentAgentId,
            int parentAgentVersion,
            @NonNull String targetAgentId,
            int targetAgentVersion,
            int depth,
            int childInvocationsSoFar,
            long remainingMillis) {
    }

    private final CapAgentDefPort agentDefs;
    private final AuthorizationService authorization;
    private final AuthzVersionPort authzVersions;
    private final BudgetProbe budget;

    public DelegationGuard(@NonNull CapAgentDefPort agentDefs,
                           @NonNull AuthorizationService authorization,
                           @NonNull AuthzVersionPort authzVersions,
                           @NonNull BudgetProbe budget) {
        this.agentDefs = agentDefs;
        this.authorization = authorization;
        this.authzVersions = authzVersions;
        this.budget = budget;
    }

    /**
     * 判定一次委派是否允许。
     *
     * <p>⚠️ <b>判定顺序是契约</b>（与 Phase A「主体授权最先」一致）：
     * <ol>
     *   <li>授权版本新鲜度（fail-closed）；</li>
     *   <li>父 Agent 是否声明委派能力；</li>
     *   <li>目标是否在声明内且版本固定；</li>
     *   <li>深度 / 次数 / 超时 / 预算边界；</li>
     *   <li>目标资源的逐跳授权。</li>
     * </ol>
     * 短路顺序保证<b>原因码最贴近根因</b>：例如「目标未声明」不会被报成「无权」。
     *
     * @param request 委派请求
     * @return 决策结果（允许为 ALLOW，否则 DENY + NOT_STARTED + 稳定原因码）
     */
    public @NonNull AuthorizationDecision evaluate(@NonNull DelegationRequest request) {
        return evaluateWithBudget(request).decision();
    }

    /**
     * 判定一次委派是否允许，并给出<b>有效时间预算</b>。
     *
     * <p>⚠️ <b>为什么要把预算一并返回</b>：有效预算 = {@code min(根 Run 剩余时间,
     * 本级 timeoutMs)}。若只返回「允许/拒绝」，调用方就只能拿
     * <b>本级声明值</b>去下传 —— 那样根 Run 的剩余时间只是「参与了一次判定」，
     * 却<b>没有任何强制力</b>：子 Agent 仍能跑满自己的 timeoutMs，
     * 而根 Run 可能早就该结束了。这正是「全调用树共享超时上限」被架空的形式。
     *
     * <p>⚠️ <b>拒绝时有效预算为 0</b>：此时根本没有子执行发生，
     * 预算值无意义 —— 给出非零值会让调用方误以为「可以按这个预算继续」。
     *
     * @param request 委派请求
     * @return 判定结果 + 有效时间预算（毫秒）
     */
    public @NonNull Verdict evaluateWithBudget(@NonNull DelegationRequest request) {
        // ── 1. 授权版本新鲜度（fail-closed）──────────────────────────
        AuthorizationDecision stale = checkAuthzFreshness(request.context());
        if (stale != null) {
            return new Verdict(stale, 0L);
        }

        // ── 2. 父 Agent 的编排声明 ─────────────────────────────────
        AgentOrchestration orchestration;
        try {
            Optional<CapAgentDef> parentDef = agentDefs.find(request.context().tenantId(),
                    request.parentAgentId(), request.parentAgentVersion());
            if (parentDef.isEmpty()) {
                return new Verdict(AuthorizationDecision.deny(ReasonCode.RESOURCE_UNKNOWN), 0L);
            }
            orchestration = AgentOrchestrationParser.parse(parentDef.get().orchestration());
        } catch (OrchestrationConfigException e) {
            // ⚠️ 配置缺陷不得降级放行：那等于「父 Agent 的编排声明坏了 → 随便委派」。
            return new Verdict(AuthorizationDecision.deny(ReasonCode.ORCHESTRATION_INVALID), 0L);
        }
        if (orchestration.isLeaf() || !orchestration.hasChildren()) {
            // ⚠️ 缺省 LEAF = 不允许委派（MA-T01），不是「不限制」。
            return new Verdict(AuthorizationDecision.deny(ReasonCode.DELEGATION_NOT_ALLOWED), 0L);
        }

        // ── 3. 目标声明与版本固定 ───────────────────────────────────
        Optional<ChildAgentRef> declared =
                orchestration.child(request.targetAgentId());
        if (declared.isEmpty()) {
            return new Verdict(
                    AuthorizationDecision.deny(ReasonCode.DELEGATION_TARGET_NOT_DECLARED), 0L);
        }
        if (declared.get().releaseVersion() != request.targetAgentVersion()) {
            // ⚠️ 目标版本与声明的固定版本不一致 = 试图漂移到别的 release。
            return new Verdict(
                    AuthorizationDecision.deny(ReasonCode.RESOURCE_VERSION_MISMATCH), 0L);
        }

        // ── 4. 边界：深度 / 次数 / 超时 / 预算 ──────────────────────
        if (request.depth() > orchestration.maxDelegationDepth()) {
            return new Verdict(
                    AuthorizationDecision.deny(ReasonCode.DELEGATION_DEPTH_EXCEEDED), 0L);
        }
        if (request.childInvocationsSoFar() + 1 > orchestration.maxChildInvocations()) {
            return new Verdict(
                    AuthorizationDecision.deny(ReasonCode.CHILD_INVOCATION_LIMIT_EXCEEDED), 0L);
        }
        if (request.remainingMillis() <= 0) {
            // ⚠️ 根 Run 的剩余时间已耗尽 → **在新增子调用前**拒绝（MA-T09）。
            //    注意这里判的是「根剩余」，不是「本级剩余」——
            //    本级剩余比根剩余长是常态，据此拒绝会让所有合法委派被判超时。
            return new Verdict(AuthorizationDecision.deny(ReasonCode.DELEGATION_TIMEOUT), 0L);
        }
        // ⚠️ 有效时间预算 = min(根 Run 剩余时间, 本级声明的 timeoutMs)：
        //    本级 timeout 永远是**上界**（配置里的 timeout 不得形同虚设）；
        //    而「根 Run 剩余时间比本级预算长」是常态（子预算本就是子集），
        //    不能因此拒绝 —— 那会让所有合法委派都被判超时。
        //    （timeoutMs 在 AgentOrchestration 构造期已保证为正数。）
        long effectiveTimeoutMs = Math.min(request.remainingMillis(), orchestration.timeoutMs());
        if (!budget.hasRemaining(request.context().tenantId(), request.runId())) {
            return new Verdict(AuthorizationDecision.deny(ReasonCode.BUDGET_EXCEEDED), 0L);
        }

        // ── 5. 目标资源的逐跳授权 ───────────────────────────────────
        boolean permitted;
        try {
            permitted = authorization.isAuthorized(request.context(),
                    AuthorizationService.Actions.AGENT_DELEGATE, request.targetAgentId());
        } catch (RuntimeException e) {
            // ⚠️ 授权服务不可用 → fail-closed（不得当作「有权限」）。
            return new Verdict(AuthorizationDecision.deny(ReasonCode.AUTHZ_UNAVAILABLE), 0L);
        }
        if (!permitted) {
            return new Verdict(AuthorizationDecision.deny(ReasonCode.AUTHZ_ROLE_MISSING), 0L);
        }

        return new Verdict(AuthorizationDecision.allow(), effectiveTimeoutMs);
    }

    /**
     * 判定结果 + 有效时间预算。
     *
     * @param decision           四字段决策结论
     * @param effectiveTimeoutMs 有效时间预算（毫秒）；<b>拒绝时为 0</b>
     */
    public record Verdict(@NonNull AuthorizationDecision decision, long effectiveTimeoutMs) {

        public Verdict {
            if (decision == null) {
                throw new IllegalArgumentException("decision 不能为空");
            }
            if (effectiveTimeoutMs < 0) {
                throw new IllegalArgumentException(
                        "有效时间预算不能为负数，收到: " + effectiveTimeoutMs);
            }
        }

        /** @return 是否允许委派（授权通过且未在 Phase B 被拒）。 */
        public boolean allowed() {
            return decision.authorization() == AuthorizationDecision.Authorization.ALLOW
                    && decision.status() == AuthorizationDecision.Status.SUCCEEDED;
        }
    }

    /**
     * 授权版本新鲜度校验。
     *
     * <p>⚠️ <b>版本先于其它一切</b>：上下文版本落后于权威源说明该上下文产生于撤权之前，
     * 此时上下文里的角色集同样不可信 —— 先报 {@code AUTHZ_STALE} 才能指向真正的根因。
     *
     * @return 失败时返回拒绝决策；通过时返回 {@code null}
     */
    private @Nullable AuthorizationDecision checkAuthzFreshness(@NonNull TenantContext context) {
        long current;
        try {
            current = authzVersions.require(context.tenantId(), context.principalId())
                    .authzVersion();
        } catch (RuntimeException e) {
            // ⚠️ 读不到权威版本 = 版本服务不可用 → fail-closed。
            return AuthorizationDecision.deny(ReasonCode.AUTHZ_UNAVAILABLE);
        }
        if (context.authzVersion() < current) {
            return AuthorizationDecision.deny(ReasonCode.AUTHZ_STALE);
        }
        return null;
    }
}
