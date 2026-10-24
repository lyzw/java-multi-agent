package cn.studykid.multiagent.api.governance;

import java.time.Instant;
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

import cn.studykid.multiagent.api.run.RunController.CurrentPrincipal;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.AgentManagePort;
import cn.studykid.multiagent.ports.persistence.AgentQueryPort;
import cn.studykid.multiagent.ports.persistence.AgentQueryPort.AgentItem;
import cn.studykid.multiagent.ports.persistence.AgentQueryPort.AgentVersionItem;
import cn.studykid.multiagent.ports.persistence.RateLimitPort;
import cn.studykid.multiagent.ports.security.AuthorizationService;

/**
 * Agent 配置与发布端点（P4-D14 / G2 · FE-01 §P18）。
 *
 * <p>⚠️ <b>三个端点共享 {@code GOVERNANCE_QUERY} 限流桶</b>（与审批/审计同桶，按租户限流）。
 *
 * <p>⚠️ <b>创建人 / 发布人只来自令牌</b>（{@code currentPrincipal.principalId()}），
 * 绝不取自请求体——否则任何人都能冒充创建人留痕。
 *
 * <p>⚠️ <b>definition 是不可变快照</b>（FR-002）：本端点<b>不提供</b>「编辑已发布版本」，
 * 要改只能再发一个版本（{@code POST /api/agents/{id}/versions}）。
 *
 * <p>⚠️ <b>异常映射沿用审批台约定</b>：
 * 不存在 → 404（与「无权限」同码，避免存在性探测）；重名 / 版本竞态 → 409。
 */
@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private final AgentQueryPort agents;
    private final AgentManagePort agentManage;
    private final AuthorizationService authorization;
    private final RateLimitPort rateLimiter;
    private final CurrentPrincipal currentPrincipal;

    public AgentController(AgentQueryPort agents,
                           AgentManagePort agentManage,
                           AuthorizationService authorization,
                           RateLimitPort rateLimiter,
                           CurrentPrincipal currentPrincipal) {
        this.agents = agents;
        this.agentManage = agentManage;
        this.authorization = authorization;
        this.rateLimiter = rateLimiter;
        this.currentPrincipal = currentPrincipal;
    }

    /**
     * 列出本租户的 Agent（含最新版本摘要）。
     *
     * <p>游标分页：{@code cursor} 由服务端编码，前端原样回传。
     */
    @GetMapping
    public ResponseEntity<GovernanceSupport.PageResponse<AgentItemResponse>> listAgents(
            @RequestParam(required = false) @Nullable Integer limit,
            @RequestParam(required = false) @Nullable String cursor) {

        TenantContext context = currentPrincipal.context();
        authorization.requireAuthorized(context,
                AuthorizationService.Actions.AGENT_MANAGE, null);

        if (GovernanceSupport.rateLimited(rateLimiter, context,
                RateLimitPort.Buckets.GOVERNANCE_QUERY)) {
            return GovernanceSupport.tooManyRequests();
        }

        int pageSize = GovernanceSupport.limit(limit);
        var page = agents.listAgents(context.tenantId(), cursor, pageSize);
        return ResponseEntity.ok(GovernanceSupport.PageResponse.of(
                page.items().stream().map(AgentItemResponse::of).toList(),
                page.nextCursor()));
    }

    /**
     * 查询单个 Agent（含最新版本摘要）。
     *
     * <p>⚠️ 404 不区分「不存在」与「无权限」——区分即存在性探测通道，
     * 故先统一鉴权，再由 {@code findAgent} 的结果决定 404。
     */
    @GetMapping("/{agentId}")
    public ResponseEntity<AgentItemResponse> getAgent(@PathVariable String agentId) {
        TenantContext context = currentPrincipal.context();
        authorization.requireAuthorized(context,
                AuthorizationService.Actions.AGENT_MANAGE, null);
        if (GovernanceSupport.rateLimited(rateLimiter, context,
                RateLimitPort.Buckets.GOVERNANCE_QUERY)) {
            return GovernanceSupport.tooManyRequests();
        }
        AgentItem item = agents.findAgent(context.tenantId(), agentId);
        if (item == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "资源不存在");
        }
        return ResponseEntity.ok(AgentItemResponse.of(item));
    }

    /**
     * 查询单个 Agent 的版本历史（不可变快照，按版本号降序）。
     *
     * <p>⚠️ 404 不区分「不存在」与「无权限」——区分即存在性探测通道，
     * 故先统一鉴权，再校验 Agent 存在性后返回版本列表。
     */
    @GetMapping("/{agentId}/versions")
    public ResponseEntity<List<AgentVersionItemResponse>> listAgentVersions(
            @PathVariable String agentId) {
        TenantContext context = currentPrincipal.context();
        authorization.requireAuthorized(context,
                AuthorizationService.Actions.AGENT_MANAGE, null);
        if (GovernanceSupport.rateLimited(rateLimiter, context,
                RateLimitPort.Buckets.GOVERNANCE_QUERY)) {
            return GovernanceSupport.tooManyRequests();
        }
        if (agents.findAgent(context.tenantId(), agentId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "资源不存在");
        }
        var versions = agents.listVersions(context.tenantId(), agentId).stream()
                .map(AgentVersionItemResponse::of).toList();
        return ResponseEntity.ok(versions);
    }

    /**
     * 创建 Agent。
     *
     * <p>⚠️ 租户内重名经 {@code agent_name_per_tenant} 唯一约束兜底 → 409。
     */
    @PostMapping
    public ResponseEntity<AgentItemResponse> createAgent(
            @RequestBody CreateAgentRequest request) {

        TenantContext context = currentPrincipal.context();
        authorization.requireAuthorized(context,
                AuthorizationService.Actions.AGENT_MANAGE, null);

        if (GovernanceSupport.rateLimited(rateLimiter, context,
                RateLimitPort.Buckets.GOVERNANCE_QUERY)) {
            return GovernanceSupport.tooManyRequests();
        }

        try {
            var item = agentManage.createAgent(
                    context.tenantId(), request.name(), request.description(),
                    currentPrincipal.principalId());
            return ResponseEntity.ok(AgentItemResponse.of(item));
        } catch (AgentManagePort.DuplicateAgentNameException e) {
            // ⚠️ 重名 = 状态冲突（409），非请求体格式错（400）
            throw new ResponseStatusException(HttpStatus.CONFLICT, "资源与当前状态冲突");
        }
    }

    /**
     * 发布新版本（definition 为不可变快照）。
     *
     * <p>⚠️ 版本号 = 该 agent 当前最大版本号 + 1；并发发布竞态 → 409。
     */
    @PostMapping("/{agentId}/versions")
    public ResponseEntity<AgentVersionItemResponse> publishVersion(
            @PathVariable String agentId,
            @RequestBody PublishVersionRequest request) {

        TenantContext context = currentPrincipal.context();
        authorization.requireAuthorized(context,
                AuthorizationService.Actions.AGENT_MANAGE, agentId);

        if (GovernanceSupport.rateLimited(rateLimiter, context,
                RateLimitPort.Buckets.GOVERNANCE_QUERY)) {
            return GovernanceSupport.tooManyRequests();
        }

        try {
            var version = agentManage.publishVersion(
                    context.tenantId(), agentId, request.definition(),
                    currentPrincipal.principalId());
            return ResponseEntity.ok(AgentVersionItemResponse.of(version));
        } catch (AgentQueryPort.AgentNotFoundException e) {
            // ⚠️ 404 不区分「不存在」与「无权限」——区分即存在性探测通道
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "资源不存在");
        } catch (AgentManagePort.VersionConflictException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "资源与当前状态冲突");
        }
    }

    /** 创建 Agent 请求体。 */
    public record CreateAgentRequest(@NonNull String name,
                                     @Nullable String description) {
    }

    /** 发布版本请求体。 */
    public record PublishVersionRequest(@NonNull String definition) {
    }

    /** Agent 视图（与端口 {@code AgentItem} 同构）。 */
    public record AgentItemResponse(
            @NonNull String agentId,
            @NonNull String name,
            @Nullable String description,
            int versionCount,
            int latestVersionNumber,
            @Nullable String latestDefinition,
            @Nullable Instant latestPublishedAt
    ) {
        static AgentItemResponse of(AgentItem item) {
            return new AgentItemResponse(item.agentId(), item.name(), item.description(),
                    item.versionCount(), item.latestVersionNumber(),
                    item.latestDefinition(), item.latestPublishedAt());
        }
    }

    /** Agent 版本视图（与端口 {@code AgentVersionItem} 同构）。 */
    public record AgentVersionItemResponse(
            @NonNull String versionId,
            int versionNumber,
            @NonNull String definition,
            @Nullable Instant publishedAt,
            @NonNull Instant createdAt
    ) {
        static AgentVersionItemResponse of(AgentVersionItem item) {
            return new AgentVersionItemResponse(item.versionId(), item.versionNumber(),
                    item.definition(), item.publishedAt(), item.createdAt());
        }
    }
}
