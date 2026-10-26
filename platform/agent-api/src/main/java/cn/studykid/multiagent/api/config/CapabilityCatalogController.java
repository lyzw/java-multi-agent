package cn.studykid.multiagent.api.config;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import cn.studykid.multiagent.api.governance.GovernanceSupport;
import cn.studykid.multiagent.api.run.RunController.CurrentPrincipal;
import cn.studykid.multiagent.application.cap.ConfigLifecycleService;
import cn.studykid.multiagent.core.cap.CapHttpToolConfig;
import cn.studykid.multiagent.core.cap.CapMcpServerDef;
import cn.studykid.multiagent.core.cap.CapSkillDef;
import cn.studykid.multiagent.core.cap.CapSkillDef.McpDeclaration;
import cn.studykid.multiagent.core.cap.CapToolDef;
import cn.studykid.multiagent.core.cap.ConfigLifecycleState;
import cn.studykid.multiagent.core.cap.SkillDescription;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.CapMcpServerDefPort;
import cn.studykid.multiagent.ports.persistence.CapSkillDefPort;
import cn.studykid.multiagent.ports.persistence.CapToolDefPort;
import cn.studykid.multiagent.ports.persistence.RateLimitPort;
import cn.studykid.multiagent.ports.security.AuthorizationService;

/**
 * 能力目录只读端点（Tool / Skill 配置台的读取面）。
 *
 * <p>⚠️ <b>为什么只有 GET</b>：Tool / Skill 定义是 <b>platform 级对象</b>
 * （无 {@code tenant_id}，不启用 RLS），其变更走<b>带外运维通道</b>——
 * 平台身份表 {@code principal} 是租户内的，没有跨租户主体；
 * 若把平台级写操作挂到租户身份上，任一租户管理员自授角色即可改全平台配置。
 * 这正是 {@link CapConfigPublishController} 对 PLATFORM 作用域显式返回 403 的原因。
 *
 * <p>因此本端点<b>刻意不提供</b> POST/PUT/DELETE：把「目录可读」与
 * 「定义可写」分成两个信任域，比在同一个端点上加权限判断更不容易出错。
 * 前端据此把 Tool / Skill 页做成<b>只读目录</b>，并明示变更入口在运维通道。
 *
 * <p>⚠️ <b>第三十四批起：写入口挪到平台通道，读入口留在本类</b> ——
 * 写走 {@link PlatformCapabilityController}（PLATFORM_ADMIN 闸门 + 运维通道），
 * 读仍是普通的 {@code AGENT_MANAGE} 鉴权（平台对象无租户维度，
 * 任何已授权主体都可读目录）。
 * 本类同时补上<b>关系查询</b>端点（第三十四批）——
 * 管理台要维护「Skill ↔ Tool / MCP」「MCP ↔ Tool」这层关系，
 * 就必须能<b>反查</b>「这个对象关联了哪些对象」，
 * 否则前端只能逐个试，没有可枚举的清单。
 *
 * <p>⚠️ <b>读取同样要鉴权</b>：能力目录揭示「平台上有哪些工具 / 技能、
 * 各自的风险等级与 MCP 绑定」，是攻击面枚举的素材，
 * 故与 {@code /api/agents} 同用 {@code AGENT_MANAGE} 动作 + 同一限流桶。
 */
@RestController
@RequestMapping("/api/cap/catalog")
public class CapabilityCatalogController {

    private final CapToolDefPort toolDefs;
    private final CapSkillDefPort skillDefs;
    private final CapMcpServerDefPort serverDefs;
    private final ConfigLifecycleService lifecycle;
    private final AuthorizationService authorization;
    private final RateLimitPort rateLimiter;
    private final CurrentPrincipal currentPrincipal;

    public CapabilityCatalogController(CapToolDefPort toolDefs,
                                       CapSkillDefPort skillDefs,
                                       CapMcpServerDefPort serverDefs,
                                       ConfigLifecycleService lifecycle,
                                       AuthorizationService authorization,
                                       RateLimitPort rateLimiter,
                                       CurrentPrincipal currentPrincipal) {
        this.toolDefs = toolDefs;
        this.skillDefs = skillDefs;
        this.serverDefs = serverDefs;
        this.lifecycle = lifecycle;
        this.authorization = authorization;
        this.rateLimiter = rateLimiter;
        this.currentPrincipal = currentPrincipal;
    }

    /**
     * 读取平台对象的生命周期状态。
     *
     * <p>⚠️ <b>为什么目录视图要带它</b>：停用（{@code PUBLISHED → DEPRECATED}）
     * 是<strong>CAS 迁移</strong> —— 它要求「期望的原状态」与实际一致。
     * 前端若硬编码 {@code expectedFrom=PUBLISHED}，那么对一个**已停用**的对象
     * 再点停用，会得到「原状态不符」这种**并发竞争**式的报错，
     * 读起来像是「有人跟我抢」—— 实际是「它早就停了」。
     * 带上真实状态后，前端能区分「真被并发改动」与「本来就是这个状态」。
     *
     * <p>⚠️ <b>取不到时返回 {@code null}</b>（不抛异常）：
     * 生命周期状态读不出来**不该**让整个目录页挂掉；
     * 前端据此<strong>禁用</strong>停用按钮即可（fail-closed 在 UI 层）。
     */
    private @Nullable String lifecycleStateOf(String type, String objectId, int version) {
        try {
            ConfigLifecycleState state = lifecycle.stateOf(type, null, objectId, version)
                    .orElse(null);
            return state == null ? null : state.name();
        } catch (RuntimeException e) {
            // ⚠️ 生命周期状态读不出来 → 返回 null（前端禁用停用按钮），
            //    **不**把它升级成整页失败 —— 目录仍可读。
            return null;
        }
    }

    // ── Tool ────────────────────────────────────────────────────────

    /** 列出全部 Tool 定义（每个逻辑 id 的最高版本）。 */
    @GetMapping("/tools")
    public ResponseEntity<List<ToolResponse>> listTools() {
        authorize();
        return ResponseEntity.ok(toolDefs.listAll().stream()
                .map(def -> ToolResponse.of(def, lifecycleStateOf("tool", def.id(), def.version())))
                .toList());
    }

    /** 读取单个 Tool 定义（最高版本）。 */
    @GetMapping("/tools/{toolId}")
    public ResponseEntity<ToolResponse> getTool(@PathVariable String toolId) {
        authorize();
        CapToolDef def = toolDefs.findLatestPublished(toolId)
                .or(() -> toolDefs.find(toolId, Integer.MAX_VALUE))
                .orElseThrow(() -> new ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "资源不存在"));
        return ResponseEntity.ok(ToolResponse.of(def,
                lifecycleStateOf("tool", def.id(), def.version())));
    }

    // ── Skill ───────────────────────────────────────────────────────

    /** 列出全部 Skill 定义（每个逻辑 id 的最高版本）。 */
    @GetMapping("/skills")
    public ResponseEntity<List<SkillResponse>> listSkills() {
        authorize();
        return ResponseEntity.ok(skillDefs.listAll().stream()
                .map(def -> SkillResponse.of(def,
                        lifecycleStateOf("skill", def.id(), def.version())))
                .toList());
    }

    /** 读取单个 Skill 定义（最高版本）。 */
    @GetMapping("/skills/{skillId}")
    public ResponseEntity<SkillResponse> getSkill(@PathVariable String skillId) {
        authorize();
        CapSkillDef def = skillDefs.findLatestPublished(skillId)
                .or(() -> skillDefs.find(skillId, Integer.MAX_VALUE))
                .orElseThrow(() -> new ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "资源不存在"));
        return ResponseEntity.ok(SkillResponse.of(def,
                lifecycleStateOf("skill", def.id(), def.version())));
    }

    // ── MCP Server ─────────────────────────────────────────────────

    /** 列出全部 MCP Server 定义（每个逻辑 id 的最高版本）。 */
    @GetMapping("/mcps")
    public ResponseEntity<List<McpResponse>> listMcps() {
        authorize();
        return ResponseEntity.ok(serverDefs.listAll().stream()
                .map(def -> McpResponse.of(def,
                        lifecycleStateOf("mcp_server", def.id(), def.version())))
                .toList());
    }

    /** 读取单个 MCP Server 定义（最高版本）。 */
    @GetMapping("/mcps/{serverId}")
    public ResponseEntity<McpResponse> getMcp(@PathVariable String serverId) {
        authorize();
        CapMcpServerDef def = serverDefs.findLatestPublished(serverId)
                .or(() -> serverDefs.find(serverId, Integer.MAX_VALUE))
                .orElseThrow(() -> new ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "资源不存在"));
        return ResponseEntity.ok(McpResponse.of(def,
                lifecycleStateOf("mcp_server", def.id(), def.version())));
    }

    // ── 关系（第三十四批）───────────────────────────────────────────

    /**
     * Tool 的关系：它绑定在哪个 MCP Server 上。
     *
     * <p>⚠️ <b>「没有绑定」是合法状态</b>：{@code kind=native} 的 Tool 不属于任何
     * MCP Server（DB 层有 CHECK 兜底）。此时返回 {@code mcpServer=null}，
     * 而不是 404 —— 404 会把「这是合法的原生工具」说成「资源不存在」。
     */
    @GetMapping("/tools/{toolId}/relations")
    public ResponseEntity<ToolRelationsResponse> toolRelations(@PathVariable String toolId) {
        authorize();
        CapToolDef def = toolDefs.findLatestPublished(toolId)
                .or(() -> toolDefs.find(toolId, Integer.MAX_VALUE))
                .orElseThrow(() -> new ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "资源不存在"));
        McpSummary server = null;
        if (def.mcpServerId() != null) {
            server = serverDefs.findLatestPublished(def.mcpServerId())
                    .or(() -> serverDefs.find(def.mcpServerId(), Integer.MAX_VALUE))
                    .map(McpSummary::of)
                    .orElse(null);
        }
        return ResponseEntity.ok(new ToolRelationsResponse(toolId, server));
    }

    /**
     * Skill 的关系：它声明了哪些 Tool、哪些 MCP Server。
     *
     * <p>⚠️ <b>「声明了但对象不存在」必须如实呈现</b>：
     * {@code declaredTools} 里可以出现尚未登记的 id（草稿态 Skill 的正常情形）。
     * 这里把找不到的对象**显式列为 {@code missing}**，
     * 而不是悄悄从清单里消失 —— 悄悄消失会让运维以为「没有这个依赖」。
     */
    @GetMapping("/skills/{skillId}/relations")
    public ResponseEntity<SkillRelationsResponse> skillRelations(@PathVariable String skillId) {
        authorize();
        CapSkillDef def = skillDefs.findLatestPublished(skillId)
                .or(() -> skillDefs.find(skillId, Integer.MAX_VALUE))
                .orElseThrow(() -> new ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "资源不存在"));

        List<ToolSummary> tools = new ArrayList<>();
        List<String> missingTools = new ArrayList<>();
        for (String toolId : def.declaredTools()) {
            toolDefs.findLatestPublished(toolId)
                    .or(() -> toolDefs.find(toolId, Integer.MAX_VALUE))
                    .map(t -> tools.add(ToolSummary.of(t)))
                    .orElseGet(() -> missingTools.add(toolId));
        }

        List<McpSummary> servers = new ArrayList<>();
        List<String> missingServers = new ArrayList<>();
        for (McpDeclaration declaration : def.declaredMcp()) {
            serverDefs.findLatestPublished(declaration.serverId())
                    .or(() -> serverDefs.find(declaration.serverId(), Integer.MAX_VALUE))
                    .map(s -> servers.add(McpSummary.of(s)))
                    .orElseGet(() -> missingServers.add(declaration.serverId()));
        }

        return ResponseEntity.ok(new SkillRelationsResponse(
                skillId, tools, missingTools, servers, missingServers));
    }

    /**
     * MCP Server 的关系：它下面挂了哪些 Tool。
     *
     * <p>⚠️ <b>与 {@code exposedToolAllowlist} 是两回事</b>：
     * 前者是「这个 Server <b>允许</b>哪些远端工具」，这里是「实际登记在它上面的
     * Tool <b>定义</b>」。两者可以不一致（允许集更宽、登记更窄），
     * 刻意分开呈现，避免运维把「没登记」误读成「不允许」。
     */
    @GetMapping("/mcps/{serverId}/relations")
    public ResponseEntity<McpRelationsResponse> mcpRelations(@PathVariable String serverId) {
        authorize();
        CapMcpServerDef def = serverDefs.findLatestPublished(serverId)
                .or(() -> serverDefs.find(serverId, Integer.MAX_VALUE))
                .orElseThrow(() -> new ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "资源不存在"));
        List<ToolSummary> tools = toolDefs.listByMcpServer(serverId).stream()
                .map(ToolSummary::of)
                .toList();
        return ResponseEntity.ok(new McpRelationsResponse(serverId, def.exposedToolAllowlist(),
                tools));
    }

    // ── 内部 ────────────────────────────────────────────────────────

    /**
     * 鉴权 + 限流（与 {@code AgentController} 同口径）。
     *
     * <p>⚠️ 404 不区分「不存在」与「无权限」——区分即存在性探测通道，
     * 故这里先统一鉴权，再由 find 的结果决定 404。
     */
    private void authorize() {
        TenantContext context = currentPrincipal.context();
        authorization.requireAuthorized(context,
                AuthorizationService.Actions.AGENT_MANAGE, null);
        if (GovernanceSupport.rateLimited(rateLimiter, context,
                RateLimitPort.Buckets.GOVERNANCE_QUERY)) {
            // ⚠️ 与 RunEventController 同口径：429 用 ResponseStatusException 表达，
            //    不用「返回 ResponseEntity」——那要求调用方是控制器方法本身。
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁");
        }
    }

    // ── DTO ─────────────────────────────────────────────────────────

    /**
     * Tool 目录视图。
     *
     * <p>⚠️ <b>刻意给全 Tool 定义行的全部字段</b>（含
     * {@code configFingerprint} / {@code createdAt}）：管理台的
     * 「登记 / 修改 / 详情」三处都要求**如实呈现全部 Tool 信息** ——
     * 少回显一个字段，运维就只能靠猜（例如「这份 Tool 到底改过没有」
     * 就只能靠比对配置指纹）。字段缺失不得由前端**臆造**默认值。
     */
    public record ToolResponse(
            @NonNull String toolId,
            @Nullable String name,
            @NonNull String kind,
            @NonNull String riskLevel,
            @Nullable String mcpServerId,
            @Nullable String mcpToolName,
            /** 远程 HTTP 请求方法（仅 kind=http 时非空） */
            @Nullable String httpMethod,
            /** 远程 HTTP 请求目标 URL（仅 kind=http 时非空） */
            @Nullable String httpUrl,
            /** 远程 HTTP 请求超时（毫秒；仅 kind=http 时非空） */
            @Nullable Integer httpTimeoutMs,
            @NonNull String inputSchema,
            @NonNull String paramPolicies,
            int version,
            int schemaVersion,
            boolean published,
            @Nullable Instant publishedAt,
            @Nullable String lifecycleState,
            /** 配置指纹（cap_tool_def.config_fingerprint）—— 该版本定义内容的指纹 */
            @NonNull String configFingerprint,
            /** 版本行创建时间（cap_tool_def.created_at） */
            @NonNull Instant createdAt
    ) {
        static ToolResponse of(CapToolDef def, @Nullable String lifecycleState) {
            CapHttpToolConfig http = def.httpConfig();
            return new ToolResponse(
                    def.id(), def.name(), def.kind().name(), def.riskLevel().name(),
                    def.mcpServerId(), def.mcpToolName(),
                    http == null ? null : http.method(),
                    http == null ? null : http.url(),
                    http == null ? null : http.timeoutMs(),
                    def.inputSchema(), def.paramPolicies(),
                    def.version(), def.schemaVersion(),
                    def.isPublished(), def.publishedAt(), lifecycleState,
                    def.configFingerprint(), def.createdAt());
        }
    }

    /** Skill 目录视图。 */
    public record SkillResponse(
            @NonNull String skillId,
            @Nullable String name,
            /** 两级描述（1级 purpose + 2级 usage）—— 描述不是授权。 */
            @Nullable SkillDescription description,
            @NonNull List<String> declaredTools,
            @NonNull List<McpDeclarationResponse> declaredMcp,
            @NonNull String riskCeiling,
            @NonNull String paramPolicies,
            @NonNull List<String> requiredCaps,
            int version,
            int schemaVersion,
            boolean published,
            @Nullable Instant publishedAt,
            @Nullable String lifecycleState
    ) {
        static SkillResponse of(CapSkillDef def, @Nullable String lifecycleState) {
            return new SkillResponse(
                    def.id(), def.name(), def.description(),
                    def.declaredTools(),
                    def.declaredMcp().stream().map(McpDeclarationResponse::of).toList(),
                    def.riskCeiling().name(), def.paramPolicies(), def.requiredCaps(),
                    def.version(), def.schemaVersion(),
                    def.isPublished(), def.publishedAt(), lifecycleState);
        }
    }

    /**
     * Skill 的 MCP 声明视图。
     *
     * <p>⚠️ <b>{@code toolAllowlist} 可空且空数组不等价于 null</b>（§4.4）：
     * {@code null} = 未配置（取 ServerAllowedOps 全集），
     * {@code []} = 显式不允许任何操作。目录页必须如实呈现，不可归一化。
     */
    public record McpDeclarationResponse(
            @NonNull String serverId,
            @Nullable List<String> toolAllowlist
    ) {
        static McpDeclarationResponse of(McpDeclaration d) {
            return new McpDeclarationResponse(d.serverId(), d.toolAllowlist());
        }
    }

    /** Tool 紧凑摘要（关系视图用）。 */
    public record ToolSummary(
            @NonNull String toolId,
            @Nullable String name,
            @NonNull String kind,
            @NonNull String riskLevel,
            int version,
            boolean published
    ) {
        static ToolSummary of(CapToolDef def) {
            return new ToolSummary(def.id(), def.name(), def.kind().name(),
                    def.riskLevel().name(), def.version(), def.isPublished());
        }
    }

    /** MCP Server 紧凑摘要（关系视图用）。 */
    public record McpSummary(
            @NonNull String serverId,
            @NonNull String transport,
            @NonNull String trustTier,
            @NonNull String connectionRisk,
            int version,
            boolean published
    ) {
        static McpSummary of(CapMcpServerDef def) {
            return new McpSummary(def.id(), def.transport().name(), def.trustTier().name(),
                    def.connectionRisk().name(), def.version(), def.isPublished());
        }
    }

    /** Tool 的关系视图。 */
    public record ToolRelationsResponse(
            @NonNull String toolId,
            @Nullable McpSummary mcpServer
    ) {
    }

    /** Skill 的关系视图。 */
    public record SkillRelationsResponse(
            @NonNull String skillId,
            @NonNull List<ToolSummary> tools,
            @NonNull List<String> missingTools,
            @NonNull List<McpSummary> mcpServers,
            @NonNull List<String> missingMcpServers
    ) {
    }

    /** MCP Server 的关系视图。 */
    public record McpRelationsResponse(
            @NonNull String serverId,
            @Nullable List<String> exposedToolAllowlist,
            @NonNull List<ToolSummary> tools
    ) {
    }

    /**
     * MCP Server 目录视图。
     *
     * <p>⚠️ <b>刻意不包含 {@code credentialRef} 取值</b>：该字段只是凭据库定位符，
     * 凭据材料永不入响应（§4.2 / §6.4）；目录页只呈现连接形态与风险画像。
     * 仅暴露 {@code hasCredentialRef} 布尔，告知「是否存在凭据引用」而不回显任何定位符。
     */
    /**
     * MCP Server 目录视图。
     *
     * <p>⚠️ {@code published} 显式回显（Tool / Skill 侧都有）：管理台的目录页要
     * 把「**发布状态**」与「**生命周期状态**」**合并成一列**呈现 ——
     * 停用是**单向终态**，已发布过的对象停用后 {@code published} 仍为 true，
     * 只看这一个布尔会把「已停用」显示成「已发布」。
     */
    public record McpResponse(
            @NonNull String serverId,
            int version,
            @NonNull String transport,
            @Nullable String endpoint,
            @NonNull String authKind,
            @NonNull String trustTier,
            @Nullable List<String> exposedToolAllowlist,
            @NonNull String connectionRisk,
            boolean dynamicDiscovery,
            @NonNull String configFingerprint,
            int schemaVersion,
            @Nullable Instant createdAt,
            @Nullable Instant publishedAt,
            boolean published,
            boolean hasCredentialRef,
            @Nullable String lifecycleState
    ) {
        static McpResponse of(CapMcpServerDef def, @Nullable String lifecycleState) {
            return new McpResponse(
                    def.id(), def.version(), def.transport().name(), def.endpoint(),
                    def.authKind().name(), def.trustTier().name(), def.exposedToolAllowlist(),
                    def.connectionRisk().name(), def.dynamicDiscovery(), def.configFingerprint(),
                    def.schemaVersion(), def.createdAt(), def.publishedAt(), def.isPublished(),
                    def.credentialRef() != null, lifecycleState);
        }
    }
}
