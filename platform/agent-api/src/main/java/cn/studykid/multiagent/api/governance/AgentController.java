package cn.studykid.multiagent.api.governance;

import java.time.Instant;
import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * <p>⚠️ <b>端点共享 {@code GOVERNANCE_QUERY} 限流桶</b>（与审批/审计同桶，按租户限流）。
 *
 * <p>⚠️ <b>创建人只来自令牌</b>（{@code currentPrincipal.principalId()}），
 * 绝不取自请求体——否则任何人都能冒充创建人留痕。
 *
 * <p>⛔ <b>「发布新版本」写端点已下线（评审 P0-5 · 2026-10-27）</b>：
 * 原 {@code POST /api/agents/{id}/versions} 允许 {@code AGENT_MANAGE} 单权限直写
 * 自由 JSONB definition 并经 {@code DualWriteAgentManagePort} 进入权威表，
 * <b>无门禁无复核</b>——与平台对象（Tool/Skill/MCP）的「草稿→审批→发布」
 * 三步发布自相矛盾，构成本项目 SoD 纪律的唯旁路。该端点唯一的 UI 消费方
 * 是从未注册进路由的孤儿页（AgentDetailPage）。发布能力恢复前，任何 Agent
 * 版本变更必须走三步发布工作流；端口方法 {@code publishVersion} 保留
 * （基础设施契约 IT 与未来纳入门禁后的工作流复用），但生产 API 面不再暴露。
 *
 * <p>⚠️ <b>definition 是不可变快照</b>（FR-002）：不存在「编辑已发布版本」。
 *
 * <p>⚠️ <b>异常映射沿用审批台约定</b>：
 * 不存在 → 404（与「无权限」同码，避免存在性探测）；重名 → 409。
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
     *
     * <p>⚠️ <b>两条放行路径，返回面不同</b>（F1 · E2E 2026-11-14）：
     * <ul>
     *   <li><b>治理面</b>（持 {@code AGENT_MANAGE}）：完整视图，含
     *       {@code latestDefinition}（版本快照全文）与版本计数。</li>
     *   <li><b>受众面</b>（仅持 {@code AGENT_LIST}，即学生/家长/老师）：
     *       <b>裁剪视图</b>——只列**已发布**（{@code latestPublishedAt != null}）的 Agent，
     *       且<b>不下发</b> {@code latestDefinition}（系统提示词全文）与版本计数。
     *       「发起辅导」页的下拉框只需要 id / 名称 / 版本号 / 发布时间。</li>
     * </ul>
     *
     * <p>⚠️ <b>为什么受众面要单独一条路径</b>：此前本端点只认 {@code AGENT_MANAGE}，
     * 而受众角色按定义不持治理权限 → 接口 403 → 前端空列表 →
     * 页面误报「没有可用的 Agent」。那是<b>能力与依赖不匹配</b>：
     * 既然已允许其发起辅导，就必须让其看见「可发起什么」；
     * 但治理元数据（提示词全文）不该随受众面外泄。
     */
    @GetMapping
    public ResponseEntity<GovernanceSupport.PageResponse<AgentItemResponse>> listAgents(
            @RequestParam(required = false) @Nullable Integer limit,
            @RequestParam(required = false) @Nullable String cursor) {

        TenantContext context = currentPrincipal.context();
        // ⚠️ 治理面与受众面**不共用一个动作**：受众面不得因持有 AGENT_LIST
        //    而顺带拿到治理视图（提示词全文 / 未发布版本）。
        boolean governance = authorization.isAuthorized(
                context, AuthorizationService.Actions.AGENT_MANAGE, null);
        boolean audience = !governance && authorization.isAuthorized(
                context, AuthorizationService.Actions.AGENT_LIST, null);
        if (!governance && !audience) {
            // ⛔ fail-closed：两个动作都没有 → 403（与治理面同样的拒绝口径）
            authorization.requireAuthorized(context,
                    AuthorizationService.Actions.AGENT_MANAGE, null);
        }

        if (GovernanceSupport.rateLimited(rateLimiter, context,
                RateLimitPort.Buckets.GOVERNANCE_QUERY)) {
            return GovernanceSupport.tooManyRequests();
        }

        int pageSize = GovernanceSupport.limit(limit);
        var page = agents.listAgents(context.tenantId(), cursor, pageSize);
        // ⚠️ 受众面过滤未发布项：**未发布版本不是可发起对象**（前端亦按
        //    latestPublishedAt 过滤，两处口径一致）。过滤后本页条数可能少于
        //    pageSize —— 这是刻意的：受众只应看到可发起项，页大小不是契约。
        List<AgentItemResponse> items = governance
                ? page.items().stream().map(AgentItemResponse::of).toList()
                : page.items().stream()
                        .filter(item -> item.latestPublishedAt() != null)
                        .map(AgentItemResponse::ofAudience)
                        .toList();
        return ResponseEntity.ok(GovernanceSupport.PageResponse.of(
                items, page.nextCursor()));
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
     * 清理 Agent 的<b>未发布草稿版本历史</b>。
     *
     * <p>⚠️ <b>仅删 {@code published_at IS NULL} 的草稿行</b>：
     * 已发布版本是线上生效过的 —— 删除会让历史回放、审计追溯、Diff 对比
     * 变成不可解释。已发布版本一律保留，清理后列表重取即能看到效果。
     *
     * <p>⚠️ <b>没有草稿时返回 removed=0 而非报错</b>：让前端按数量给用户反馈
     * （"已清理 N 条" / "没有可清理的草稿"），而不是把无操作伪装成失败。
     */
    @DeleteMapping("/{agentId}/versions/unpublished")
    public ResponseEntity<VersionsCleanupResponse> cleanupUnpublishedVersions(
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
        int removed = agents.deleteUnpublishedVersions(context.tenantId(), agentId);
        return ResponseEntity.ok(new VersionsCleanupResponse(agentId, removed));
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
     * ⛔ 已下线（评审 P0-5 · 2026-10-27）：{@code POST /{agentId}/versions} 直发端点。
     *
     * <p>同路径的 {@code POST} 现在落回 Spring 默认的 405 Method Not Allowed
     * （GET 仍映射）——fail-closed：不存在「绕过三步发布的静默通道」。
     * 版本发布恢复时必须经三步发布工作流（草稿→审批→发布），不得直接复原本端点。
     */

    /**
     * 创建 Agent 请求体。
     *
     * <p>⚠️ <b>输入校验</b>：name 长度限制 + 格式校验，防止 SQL 注入字符进入 DB。
     *    虽然唯一约束兜底重名，但参数校验应在入库前完成。
     */
    public record CreateAgentRequest(@NonNull String name,
                                     @Nullable String description) {
        public CreateAgentRequest {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("name 不能为空");
            }
            if (name.length() > 128) {
                throw new IllegalArgumentException("name 长度不能超过 128 个字符");
            }
            // ⚠️ 防止 SQL 注入字符：仅允许中文、英文、数字、下划线、连字符
            if (!name.matches("[\\u4e00-\\u9fa5a-zA-Z0-9_\\-\\s]+")) {
                throw new IllegalArgumentException("name 只能包含中文、英文、数字、下划线和连字符");
            }
            if (description != null && description.length() > 1024) {
                throw new IllegalArgumentException("description 长度不能超过 1024 个字符");
            }
        }
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

        /**
         * 受众视图（只读 · 仅持 {@code AGENT_LIST} 的主体）。
         *
         * <p>⚠️ <b>裁掉的两项都不是「顺手带上」的</b>：
         * <ul>
         *   <li>{@code latestDefinition}：已发布版本的<b>定义快照全文</b>
         *       （含系统提示词 inlineInstructions）——治理面才可见；</li>
         *   <li>{@code versionCount}：治理面的版本存量信息，受众用不到。</li>
         * </ul>
         *
         * <p>保留 {@code agentId / name / description / latestVersionNumber /
         * latestPublishedAt}：这是「发起辅导」下拉框渲染与提交的最小必要集。
         */
        static AgentItemResponse ofAudience(AgentItem item) {
            return new AgentItemResponse(item.agentId(), item.name(), item.description(),
                    0, item.latestVersionNumber(),
                    null, item.latestPublishedAt());
        }
    }

    /** 清理草稿版本的响应。 */
    public record VersionsCleanupResponse(
            @NonNull String agentId,
            /** 实际删除的草稿版本行数；0 = 没有未发布草稿可清理。 */
            int removed) {
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
