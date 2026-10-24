package cn.studykid.multiagent.api.admin;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import cn.studykid.multiagent.api.governance.GovernanceSupport;
import cn.studykid.multiagent.api.run.RunController.CurrentPrincipal;
import cn.studykid.multiagent.application.cap.DependencyGraphService;
import cn.studykid.multiagent.core.cap.DependencyEntityType;
import cn.studykid.multiagent.core.cap.DependencyGraph;
import cn.studykid.multiagent.core.cap.ImpactReport;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.RateLimitPort;
import cn.studykid.multiagent.ports.security.AuthorizationService;

/**
 * 依赖关系只读端点（管理面 M7 · 能力闭包图谱 + 影响分析）。
 *
 * <p>⚠️ <b>为什么只有 GET</b>：与 {@code CapabilityCatalogController} 同一哲学 ——
 * 图谱/影响是<b>决策素材</b>，不是变更通道。删除 / 停用 / 改版本一律走各自
 * 既有入口（Agent 发新版本、平台对象走带外运维），本端点不提供任何写操作，
 * 从结构上排除「顺手加一个删除按钮」的风险。
 *
 * <p>⚠️ <b>租户作用域</b>：{@code graph} / {@code impact} 都按<b>调用方租户</b>
 * 计算 —— Agent 侧经 {@code CapAgentDefPort}（RLS 绑定），平台侧目录无 RLS。
 * 影响链路中的 {@code affectedAgentIds} 仅含本租户 Agent：其他租户命中了什么，
 * 对本租户不可见（也没有理由可见）。
 *
 * <p>⚠️ <b>鉴权口径与能力目录一致</b>（{@code AGENT_MANAGE} + 同一限流桶）：
 * 图谱揭示「平台有哪些对象、本租户怎么引用」，是攻击面枚举素材，
 * 与 {@code /api/cap/catalog}、{@code /api/agents} 同信任级别。
 */
@RestController
@RequestMapping("/api/admin/dependencies")
public class AdminDependencyController {

    private final DependencyGraphService dependencies;
    private final AuthorizationService authorization;
    private final RateLimitPort rateLimiter;
    private final CurrentPrincipal currentPrincipal;

    public AdminDependencyController(@NonNull DependencyGraphService dependencies,
                                     @NonNull AuthorizationService authorization,
                                     @NonNull RateLimitPort rateLimiter,
                                     @NonNull CurrentPrincipal currentPrincipal) {
        this.dependencies = dependencies;
        this.authorization = authorization;
        this.rateLimiter = rateLimiter;
        this.currentPrincipal = currentPrincipal;
    }

    /**
     * 能力依赖图谱（节点 + 边）。
     *
     * <p>节点 = Tenant + 本租户 Agent（最高版本行）+ 全平台 Tool/Skill/MCP 目录；
     * 边 = 引用关系（含 overrides 负向边与悬空幽灵节点）。
     * 超限时 {@code truncated=true}（展示降级，前端明示）。
     */
    @GetMapping("/graph")
    public ResponseEntity<DependencyGraph> graph() {
        TenantContext context = authorize();
        return ResponseEntity.ok(dependencies.graph(context.tenantId()));
    }

    /**
     * 影响分析：目标实体「引用了谁」+「改/删它会命中谁」。
     *
     * @param type {@code agent} / {@code tool} / {@code skill} / {@code mcp_server}（兼容 {@code mcp}）
     * @param id   实体业务 id
     * @throws ResponseStatusException 400 类型/参数非法；404 实体不存在（不区分存在性细节）
     */
    @GetMapping("/impact")
    public ResponseEntity<ImpactReport> impact(@RequestParam @NonNull String type,
                                               @RequestParam @NonNull String id) {
        TenantContext context = authorize();
        DependencyEntityType entityType = DependencyEntityType.fromCode(type);
        if (entityType == null || id.isBlank()) {
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "参数非法: type/id");
        }
        ImpactReport report = dependencies.impact(context.tenantId(), entityType, id);
        if (!report.entityExists()) {
            // ⚠️ 与能力目录同口径：先鉴权再 404，不泄露「是否存在」之外的细节
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "资源不存在");
        }
        return ResponseEntity.ok(report);
    }

    // ── 内部 ────────────────────────────────────────────────────────

    /**
     * 鉴权 + 限流（与 {@code CapabilityCatalogController#authorize} 同口径）。
     */
    private @NonNull TenantContext authorize() {
        TenantContext context = currentPrincipal.context();
        authorization.requireAuthorized(context,
                AuthorizationService.Actions.AGENT_MANAGE, null);
        if (GovernanceSupport.rateLimited(rateLimiter, context,
                RateLimitPort.Buckets.GOVERNANCE_QUERY)) {
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁");
        }
        return context;
    }
}
