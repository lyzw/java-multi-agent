package cn.studykid.multiagent.api.config;

import java.time.Instant;
import java.util.List;

import org.jspecify.annotations.NonNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import cn.studykid.multiagent.api.governance.GovernanceSupport;
import cn.studykid.multiagent.api.run.RunController.CurrentPrincipal;
import cn.studykid.multiagent.core.orchestration.CapAgentChildRef;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.CapAgentChildRefPort;
import cn.studykid.multiagent.ports.persistence.RateLimitPort;
import cn.studykid.multiagent.ports.security.AuthorizationService;

/**
 * 委派关系（父子 Agent 边）的只读端点（评审前置决策 4：{@code cap_agent_child_ref} <b>存储</b>）。
 *
 * <p>⚠️ <b>本端点读的是「发布关系投影」，不是权威定义</b>：
 * 权威定义是不可变 Agent release 里的 {@code childAgentRefs}；
 * 本表只是把「边」投影出来便于查询与分析，<b>可重建</b>。
 * 因此本端点<b>不提供写操作</b> —— 边的唯一来源是发布期投影，
 * 手工写一条边会让「投影」与「release」两个真相分叉。
 *
 * <p>⚠️ <b>为什么需要这组端点</b>：
 * 「谁依赖我」这个问题是<b>删除守卫</b>与<b>影响分析</b>的硬需求 ——
 * 没有它，运维只能靠全表扫边来回答「这个子 Agent 能不能被停用/删除」，
 * 而漏扫一次就等于放行了一次「被引用对象被抽走」的事故。
 * 出向查询（我依赖谁）回答不了这个问题。
 *
 * <p>⚠️ <b>租户边界</b>：三组查询都经租户连接上下文（RLS 强制），
 * 跨租户的边**不可见**（MA-T04）。
 */
@RestController
@RequestMapping("/api/cap/delegations")
public class DelegationRelationController {

    private final CapAgentChildRefPort childRefs;
    private final AuthorizationService authorization;
    private final RateLimitPort rateLimiter;
    private final CurrentPrincipal currentPrincipal;

    public DelegationRelationController(CapAgentChildRefPort childRefs,
                                        AuthorizationService authorization,
                                        RateLimitPort rateLimiter,
                                        CurrentPrincipal currentPrincipal) {
        this.childRefs = childRefs;
        this.authorization = authorization;
        this.rateLimiter = rateLimiter;
        this.currentPrincipal = currentPrincipal;
    }

    /**
     * 本租户的<b>全部</b>关系边（委派拓扑图用）。
     *
     * <p>⚠️ 一次取全量而不是「按父逐个查」：拓扑图要渲染的是**整张图**，
     * N+1 查询既慢又容易在并发下拼出不一致的快照（边来自不同时间点的查询）。
     */
    @GetMapping
    public ResponseEntity<List<DelegationEdgeResponse>> listAll() {
        authorize();
        return ResponseEntity.ok(childRefs.listByTenant(currentPrincipal.context().tenantId())
                .stream().map(DelegationEdgeResponse::of).toList());
    }

    /**
     * 某个父 Agent 的<b>全部</b>出向边（它声明了哪些子）。
     *
     * @param agentId 父 Agent 标识
     */
    @GetMapping("/parents/{agentId}/children")
    public ResponseEntity<List<DelegationEdgeResponse>> children(@PathVariable String agentId) {
        authorize();
        return ResponseEntity.ok(childRefs.listByParentAgent(
                        currentPrincipal.context().tenantId(), agentId)
                .stream().map(DelegationEdgeResponse::of).toList());
    }

    /**
     * 某个子 Agent 的<b>全部</b>入向边（<b>谁</b>声明了它）。
     *
     * <p>⚠️ <b>删除/停用守卫靠这个回答</b>：结果非空 = 有人依赖它。
     * ⚠️ <b>不限子版本</b>：被停用的是那个 <b>Agent</b>，不是某一版 ——
     * 任何父引用了它的任何一版，都构成「抽走它会让那个父悬空」的事实。
     */
    @GetMapping("/children/{agentId}/parents")
    public ResponseEntity<List<DelegationEdgeResponse>> parents(@PathVariable String agentId) {
        authorize();
        return ResponseEntity.ok(childRefs.listByChildAgent(
                        currentPrincipal.context().tenantId(), agentId)
                .stream().map(DelegationEdgeResponse::of).toList());
    }

    // ── 内部 ────────────────────────────────────────────────────────

    /**
     * 鉴权 + 限流（与 {@code CapabilityCatalogController} 同口径）。
     *
     * <p>⚠️ 委派拓扑揭示「谁把工作交给谁」，是攻击面枚举的素材，
     * 故读取同样需鉴权 + 限流。
     */
    private void authorize() {
        TenantContext context = currentPrincipal.context();
        authorization.requireAuthorized(context,
                AuthorizationService.Actions.AGENT_MANAGE, null);
        if (GovernanceSupport.rateLimited(rateLimiter, context,
                RateLimitPort.Buckets.GOVERNANCE_QUERY)) {
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁");
        }
    }

    // ── DTO ─────────────────────────────────────────────────────────

    /**
     * 一条委派边的视图。
     *
     * @param parentAgentId       父 Agent 标识
     * @param parentReleaseVersion 父 release 版本号
     * @param childAgentId        子 Agent 标识
     * @param childReleaseVersion 子 release 版本号
     * @param ordinal             父的声明次序（⚠️ 框架**不**按此顺序代为执行）
     * @param required            是否为必选子 Agent
     * @param createdAt           投影写入时间
     */
    public record DelegationEdgeResponse(
            @NonNull String parentAgentId,
            int parentReleaseVersion,
            @NonNull String childAgentId,
            int childReleaseVersion,
            int ordinal,
            boolean required,
            @NonNull Instant createdAt
    ) {
        static DelegationEdgeResponse of(CapAgentChildRef ref) {
            return new DelegationEdgeResponse(ref.parentAgentId(), ref.parentReleaseVersion(),
                    ref.childAgentId(), ref.childReleaseVersion(), ref.ordinal(),
                    ref.required(), ref.createdAt());
        }
    }
}
