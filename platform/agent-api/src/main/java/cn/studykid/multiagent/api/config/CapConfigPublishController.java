package cn.studykid.multiagent.api.config;

import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import cn.studykid.multiagent.api.governance.GovernanceSupport;
import cn.studykid.multiagent.api.run.RunController.CurrentPrincipal;
import cn.studykid.multiagent.application.cap.ConfigPublishPayload;
import cn.studykid.multiagent.application.cap.ConfigPublishWorkflowService;
import cn.studykid.multiagent.application.cap.ConfigPublishWorkflowService.ConfigPublishBaselineConflictException;
import cn.studykid.multiagent.application.cap.ConfigPublishWorkflowService.ConfigPublishScopeNotAllowedException;
import cn.studykid.multiagent.core.cap.ConfigPointer;
import cn.studykid.multiagent.core.cap.ConfigPublishRequest;
import cn.studykid.multiagent.core.cap.ConfigRelease;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.ConfigPublishRequestPort;
import cn.studykid.multiagent.ports.persistence.RateLimitPort;
import cn.studykid.multiagent.ports.security.AuthorizationService;

/**
 * 权威配置对象的变更管理端点（P6R4 · 消解 P6 登记的限制 7 / 限制 8）。
 *
 * <p>⚠️ <b>三步流程刻意分开，不可合并</b>：
 * <pre>
 *   POST /api/cap/config/publishes                    → PENDING   （不写配置表）
 *   POST /api/cap/config/publishes/{id}/decision       → APPROVED  （仍不写配置表）
 *   POST /api/cap/config/publishes/{id}/apply          → APPLIED   （此步才真正写入）
 * </pre>
 * 合并「审批」与「生效」会让审批人无法区分「我批了」与「配置真的变了」。
 *
 * <p>⚠️ <b>双人复核靠身份比对，不靠权限拆分</b>：
 * 申请人与审批人持有的是<b>同一个</b>动作（{@code config:publish}），
 * 「不得自批」由 {@code approver_id <> requester_id} 强制
 * （应用层 + DB CHECK 双保险）。与平台既有配置变更流程同款。
 *
 * <p>⚠️ <b>平台级发布一律拒绝</b>（403）：
 * 平台级对象（Tool / Skill / MCP Server）无 {@code tenant_id}，
 * 走平台运维通道（BYPASSRLS 凭据 + 带外运维工具）。
 * 平台身份表 {@code principal} 是<b>租户内</b>的，没有跨租户主体 ——
 * 若把平台级发布挂到租户身份上，任一租户管理员自授角色即可改全平台配置。
 * 故本端点**显式拒绝**，而不是「尝试一下被 RLS 挡掉」——
 * 后者会把「设计意图」变成「偶然的副作用」。
 *
 * <p>⚠️ <b>端点默认受 {@code anyRequest().authenticated()} 保护</b>
 * （见 {@code SecurityConfiguration}）：漏配 PUBLIC_ENDPOINTS 时是拒绝而非放行。
 */
@RestController
@RequestMapping("/api/cap/config")
public class CapConfigPublishController {

    private final ConfigPublishWorkflowService workflow;
    private final AuthorizationService authorization;
    private final RateLimitPort rateLimiter;
    private final CurrentPrincipal currentPrincipal;

    public CapConfigPublishController(ConfigPublishWorkflowService workflow,
                                      AuthorizationService authorization,
                                      RateLimitPort rateLimiter,
                                      CurrentPrincipal currentPrincipal) {
        this.workflow = workflow;
        this.authorization = authorization;
        this.rateLimiter = rateLimiter;
        this.currentPrincipal = currentPrincipal;
    }

    // ── 第一步：申请 ───────────────────────────────────────────────

    /** 提交变更申请（<b>不写入配置表</b>）。 */
    @PostMapping("/publishes")
    public ResponseEntity<RequestResponse> request(@RequestBody PublishRequest request) {
        TenantContext context = currentPrincipal.context();
        authorization.requireAuthorized(context,
                AuthorizationService.Actions.CONFIG_PUBLISH, null);
        if (rateLimited(context)) {
            return GovernanceSupport.tooManyRequests();
        }

        // ⚠️ 作用域闸门在**最前面**：平台级发布不经租户身份暴露。
        //    必须在做任何事之前拒绝 —— 包括「先落一条申请单再说」。
        ConfigRelease.Scope scope = parseScope(request.scope());

        String reason = request.reason() == null ? "" : request.reason().trim();
        if (reason.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "变更理由不能为空");
        }

        try {
            ConfigPublishRequest.Operation operation = parseOperation(request.operation());
            List<ConfigPublishPayload.AgentSpec> agentSpecs = agentSpecs(request.agents());
            List<ConfigPublishPayload.ResultContractSpec> contractSpecs =
                    resultContractSpecs(request.resultContracts());
            if (agentSpecs.isEmpty() && contractSpecs.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "PUBLISH 必须携带 agents 或 resultContracts");
            }
            ConfigPublishRequest created = switch (operation) {
                case PUBLISH -> workflow.requestPublish(context.tenantId(), scope,
                        agentSpecs, contractSpecs, request.expectedPublishSeq(),
                        reason, currentPrincipal.principalId());
                case ROLLBACK -> workflow.requestRollback(context.tenantId(), scope,
                        request.targetReleaseId() == null ? 0L : request.targetReleaseId(),
                        request.expectedPublishSeq(), reason, currentPrincipal.principalId());
                case LIFECYCLE_TRANSITION -> workflow.requestLifecycle(context.tenantId(), scope,
                        lifecycleSpec(request.lifecycle()), reason,
                        currentPrincipal.principalId());
            };
            return ResponseEntity.ok(RequestResponse.of(created));
        } catch (ConfigPublishScopeNotAllowedException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    // ── 第二步：审批 ───────────────────────────────────────────────

    /** 审批决定（批准或拒绝；<b>不写入配置表</b>）。 */
    @PostMapping("/publishes/{requestId}/decision")
    public ResponseEntity<RequestResponse> decide(@PathVariable String requestId,
                                                  @RequestBody DecisionRequest request) {
        TenantContext context = currentPrincipal.context();
        authorization.requireAuthorized(context,
                AuthorizationService.Actions.CONFIG_PUBLISH, null);
        if (rateLimited(context)) {
            return GovernanceSupport.tooManyRequests();
        }

        ConfigPublishRequestPort.Decision decision;
        try {
            decision = ConfigPublishRequestPort.Decision.valueOf(
                    String.valueOf(request.decision()).trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "decision 仅接受 APPROVE / REJECT");
        }

        try {
            ConfigPublishRequest decided = workflow.decide(context.tenantId(), requestId,
                    decision, request.note(), currentPrincipal.principalId());
            return ResponseEntity.ok(RequestResponse.of(decided));
        } catch (ConfigPublishRequestPort.SelfApprovalException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "不得审批自己提交的变更申请");
        } catch (ConfigPublishRequestPort.StateConflictException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "资源与当前状态冲突");
        } catch (ConfigPublishRequestPort.NotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "资源不存在");
        }
    }

    // ── 第三步：执行 ───────────────────────────────────────────────

    /** 执行已批准的变更（<b>此步才真正写入配置表</b>）。 */
    @PostMapping("/publishes/{requestId}/apply")
    public ResponseEntity<RequestResponse> apply(@PathVariable String requestId) {
        TenantContext context = currentPrincipal.context();
        authorization.requireAuthorized(context,
                AuthorizationService.Actions.CONFIG_PUBLISH, null);
        if (rateLimited(context)) {
            return GovernanceSupport.tooManyRequests();
        }

        try {
            ConfigPublishRequest applied = workflow.apply(context.tenantId(), requestId,
                    currentPrincipal.principalId());
            return ResponseEntity.ok(RequestResponse.of(applied));
        } catch (ConfigPublishScopeNotAllowedException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (ConfigPublishBaselineConflictException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (ConfigPublishRequestPort.StateConflictException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "资源与当前状态冲突");
        } catch (ConfigPublishRequestPort.NotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "资源不存在");
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    // ── 读 ─────────────────────────────────────────────────────────

    /** 列出本租户的变更申请（游标分页）。 */
    @GetMapping("/publishes")
    public ResponseEntity<GovernanceSupport.PageResponse<RequestResponse>> list(
            @RequestParam(required = false) @Nullable String state,
            @RequestParam(required = false) @Nullable Integer limit,
            @RequestParam(required = false) @Nullable String cursor) {
        TenantContext context = currentPrincipal.context();
        authorization.requireAuthorized(context,
                AuthorizationService.Actions.CONFIG_PUBLISH, null);
        if (rateLimited(context)) {
            return GovernanceSupport.tooManyRequests();
        }

        ConfigPublishRequest.State parsed = null;
        if (state != null && !state.isBlank()) {
            try {
                parsed = ConfigPublishRequest.State.valueOf(
                        state.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "state 取值非法: " + state);
            }
        }
        var page = workflow.list(context.tenantId(), parsed, cursor,
                GovernanceSupport.limit(limit));
        return ResponseEntity.ok(GovernanceSupport.PageResponse.of(
                page.items().stream().map(RequestResponse::of).toList(),
                page.nextCursor()));
    }

    /**
     * 读取本租户当前的发布指针（CAS 基线的来源）。
     *
     * <p>⚠️ 只读、零副作用；返回的 {@code currentPublishSeq} 就是客户端申请时
     * 应携带的 {@code expectedPublishSeq}。
     */
    @GetMapping("/pointer")
    public ResponseEntity<PointerResponse> pointer() {
        TenantContext context = currentPrincipal.context();
        authorization.requireAuthorized(context,
                AuthorizationService.Actions.CONFIG_PUBLISH, null);
        if (rateLimited(context)) {
            return GovernanceSupport.tooManyRequests();
        }
        ConfigPointer pointer = workflow.currentPointer(context.tenantId()).orElse(null);
        return ResponseEntity.ok(PointerResponse.of(pointer));
    }

    /**
     * 读取单条变更申请（详情深链用）。
     *
     * <p>⚠️ 与 {@link #list} 同口径：只读本租户申请单；
     * 跨租户 id 与不存在的 id 同样返回 404（不泄露存在性）。
     */
    @GetMapping("/publishes/{requestId}")
    public ResponseEntity<RequestResponse> getRequest(@PathVariable String requestId) {
        TenantContext context = currentPrincipal.context();
        authorization.requireAuthorized(context,
                AuthorizationService.Actions.CONFIG_PUBLISH, null);
        if (rateLimited(context)) {
            return GovernanceSupport.tooManyRequests();
        }
        ConfigPublishRequest item = workflow.findRequest(context.tenantId(), requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "变更申请不存在"));
        return ResponseEntity.ok(RequestResponse.of(item));
    }

    /**
     * 本租户发布时间线（游标分页，含 ROLLED_BACK）。
     *
     * <p>⚠️ <b>含已回滚发布</b>：时间线的意义是「发生过什么」；
     * ROLLED_BACK 行不参与当前版本解析，但既是历史事实，
     * 也是再次回滚的目标来源（{@code targetReleaseId} 即取于此）。
     *
     * <p>⚠️ <b>只列 TENANT 作用域</b>：平台级发布头由运维通道管理，
     * 不属于租户时间线（与工作流服务同口径）。
     */
    @GetMapping("/releases")
    public ResponseEntity<GovernanceSupport.PageResponse<ReleaseResponse>> releases(
            @RequestParam(required = false) @Nullable Integer limit,
            @RequestParam(required = false) @Nullable String cursor) {
        TenantContext context = currentPrincipal.context();
        authorization.requireAuthorized(context,
                AuthorizationService.Actions.CONFIG_PUBLISH, null);
        if (rateLimited(context)) {
            return GovernanceSupport.tooManyRequests();
        }
        var page = workflow.listReleases(context.tenantId(), cursor,
                GovernanceSupport.limit(limit));
        return ResponseEntity.ok(GovernanceSupport.PageResponse.of(
                page.items().stream().map(ReleaseResponse::of).toList(),
                page.nextCursor()));
    }

    // ── 内部 ───────────────────────────────────────────────────────

    /**
     * 作用域解析。
     *
     * <p>⚠️ 未显式给出 scope 时默认 {@code TENANT}（本端点只服务租户级对象）。
     * PLATFORM 不在此处拒绝，而是交给<b>应用层</b>的作用域闸门 ——
     * 闸门只有一处实现，避免「API 层拒了、应用层放行」的分裂。
     */
    private static ConfigRelease.Scope parseScope(@Nullable String scope) {
        if (scope == null || scope.isBlank()) {
            return ConfigRelease.Scope.TENANT;
        }
        try {
            return ConfigRelease.Scope.valueOf(scope.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "scope 取值非法: " + scope);
        }
    }

    private static ConfigPublishRequest.Operation parseOperation(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "operation 必填：PUBLISH / ROLLBACK / LIFECYCLE_TRANSITION");
        }
        try {
            return ConfigPublishRequest.Operation.valueOf(
                    raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "operation 取值非法: " + raw);
        }
    }

    private static List<ConfigPublishPayload.AgentSpec> agentSpecs(
            @Nullable List<AgentRequest> agents) {
        if (agents == null || agents.isEmpty()) {
            return List.of();
        }
        return agents.stream().map(a -> new ConfigPublishPayload.AgentSpec(
                a.agentId(), a.name(), a.description(),
                a.roles() == null ? List.of() : a.roles(),
                a.enabledSkills() == null ? List.of() : a.enabledSkills(),
                a.skillBundles() == null ? List.of() : a.skillBundles(),
                a.capabilityTags() == null ? List.of() : a.capabilityTags(),
                a.overrides() == null ? "{}" : a.overrides(),
                // ⚠️ 编排声明必须由客户端提交（多级 Agent v1.1）：
                //    缺省 "{}" = LEAF，与「显式声明 LEAF」不可区分 ——
                //    治理通道若不暴露这个字段，多级 Agent 根本无法发布。
                a.orchestration() == null ? "{}" : a.orchestration())).toList();
    }

    /**
     * 输出契约草稿映射。
     *
     * <p>⚠️ 空列表 = 本次发布不登记契约（≠「契约随意」）——
     * 没有契约可登记时，Agent 的 {@code resultContractRef}
     * 只能指向<b>此前已发布</b>的契约。
     */
    private static List<ConfigPublishPayload.ResultContractSpec> resultContractSpecs(
            @Nullable List<ResultContractRequest> contracts) {
        if (contracts == null || contracts.isEmpty()) {
            return List.of();
        }
        return contracts.stream().map(c -> new ConfigPublishPayload.ResultContractSpec(
                c.contractId(), c.schemaJson())).toList();
    }

    private static ConfigPublishPayload.LifecycleSpec lifecycleSpec(
            @Nullable LifecycleRequest lifecycle) {
        if (lifecycle == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "LIFECYCLE_TRANSITION 必须携带 lifecycle");
        }
        try {
            return new ConfigPublishPayload.LifecycleSpec(lifecycle.type(),
                    lifecycle.objectId(), lifecycle.version(),
                    lifecycle.expectedFrom(), lifecycle.to());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private boolean rateLimited(TenantContext context) {
        return GovernanceSupport.rateLimited(rateLimiter, context,
                RateLimitPort.Buckets.GOVERNANCE_QUERY);
    }

    // ── DTO ────────────────────────────────────────────────────────

    /** 变更申请请求体。 */
    public record PublishRequest(
            @Nullable String operation,
            @Nullable String scope,
            @Nullable Long expectedPublishSeq,
            @Nullable List<AgentRequest> agents,
            @Nullable Long targetReleaseId,
            @Nullable LifecycleRequest lifecycle,
            @Nullable List<ResultContractRequest> resultContracts,
            @Nullable String reason) {

        /**
         * 兼容构造器：<b>无输出契约</b>的发布申请（多级 Agent 之前的形态）。
         */
        public PublishRequest(@Nullable String operation,
                              @Nullable String scope,
                              @Nullable Long expectedPublishSeq,
                              @Nullable List<AgentRequest> agents,
                              @Nullable Long targetReleaseId,
                              @Nullable LifecycleRequest lifecycle,
                              @Nullable String reason) {
            this(operation, scope, expectedPublishSeq, agents, targetReleaseId, lifecycle, null,
                    reason);
        }
    }

    /** Agent 定义草稿（<b>不含版本号与内容指纹</b> —— 二者由服务端决定）。 */
    public record AgentRequest(
            @Nullable String agentId,
            @Nullable String name,
            @Nullable String description,
            @Nullable List<String> roles,
            @Nullable List<String> enabledSkills,
            @Nullable List<String> skillBundles,
            @Nullable List<String> capabilityTags,
            @Nullable String overrides,
            @Nullable String orchestration) {

        /**
         * 兼容构造器：<b>无编排声明</b>的 Agent 草稿（多级 Agent 之前的提交形态）。
         *
         * <p>⚠️ 缺省 {@code "{}"} = LEAF（MA-T01）：不是「不限制」，而是「不允许委派」。
         */
        public AgentRequest(@Nullable String agentId,
                             @Nullable String name,
                             @Nullable String description,
                             @Nullable List<String> roles,
                             @Nullable List<String> enabledSkills,
                             @Nullable List<String> skillBundles,
                             @Nullable List<String> capabilityTags,
                             @Nullable String overrides) {
            this(agentId, name, description, roles, enabledSkills, skillBundles, capabilityTags,
                    overrides, null);
        }
    }

    /**
     * 输出契约草稿（<b>不含版本号与内容指纹</b> —— 二者由服务端决定）。
     *
     * <p>⚠️ 与 Agent 草稿同口径：客户端若能指定版本号或内容指纹，
     * 就等于能指定「内容没变」这个证明。
     */
    public record ResultContractRequest(
            @Nullable String contractId,
            @Nullable String schemaJson) {
    }

    /** 生命周期迁移参数。 */
    public record LifecycleRequest(
            @Nullable String type,
            @Nullable String objectId,
            int version,
            @Nullable String expectedFrom,
            @Nullable String to) {
    }

    /** 审批决定请求体。 */
    public record DecisionRequest(@Nullable String decision, @Nullable String note) {
    }

    /** 变更申请响应。 */
    public record RequestResponse(
            @NonNull String requestId,
            @NonNull String operation,
            @NonNull String state,
            @NonNull String requesterId,
            @Nullable String approverId,
            @Nullable String decisionNote,
            @NonNull String requestedAt,
            @Nullable String decidedAt,
            @Nullable String appliedAt,
            @Nullable String failureReason) {

        public static RequestResponse of(ConfigPublishRequest item) {
            return new RequestResponse(
                    item.requestId(), item.operation().name(), item.state().name(),
                    item.requesterId(), item.approverId(), item.decisionNote(),
                    item.requestedAt().toString(),
                    item.decidedAt() == null ? null : item.decidedAt().toString(),
                    item.appliedAt() == null ? null : item.appliedAt().toString(),
                    item.failureReason());
        }
    }

    /** 当前发布指针响应。 */
    public record PointerResponse(
            @Nullable Long currentReleaseId,
            @Nullable Long currentPublishSeq,
            @Nullable String updatedAt) {

        public static PointerResponse of(@Nullable ConfigPointer pointer) {
            return pointer == null
                    ? new PointerResponse(null, null, null)
                    : new PointerResponse(pointer.currentReleaseId(),
                            pointer.currentPublishSeq(), pointer.updatedAt().toString());
        }
    }

    /**
     * 发布历史条目响应。
     *
     * <p>⚠️ {@code releaseId} / {@code configPublishSeq} 以<b>字符串</b>透出：
     * 前端回传 {@code targetReleaseId}（回滚目标）时按字符串解析，
     * 避免 JS number 精度问题落在审计字段上。
     *
     * <p>⚠️ {@code objectVersions} 是<b>聚合 JSON</b>（该发布包含的全部对象版本），
     * 只作审计展示，不在此处展开。
     */
    public record ReleaseResponse(
            @NonNull String releaseId,
            @NonNull String configPublishSeq,
            @NonNull String scope,
            @Nullable String tenantId,
            @NonNull String objectVersions,
            @Nullable String aggregateFingerprint,
            @NonNull String status,
            @Nullable String requesterId,
            @Nullable String approverId,
            @Nullable String effectiveAt,
            @NonNull String createdAt) {

        public static ReleaseResponse of(ConfigRelease release) {
            return new ReleaseResponse(
                    String.valueOf(release.releaseId()),
                    String.valueOf(release.configPublishSeq()),
                    release.scope().name(),
                    release.tenantId(),
                    release.objectVersions(),
                    release.aggregateFingerprint(),
                    release.status().name(),
                    release.requesterId(),
                    release.approverId(),
                    release.effectiveAt() == null ? null : release.effectiveAt().toString(),
                    release.createdAt().toString());
        }
    }
}
