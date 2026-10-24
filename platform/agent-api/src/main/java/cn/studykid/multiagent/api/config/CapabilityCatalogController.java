package cn.studykid.multiagent.api.config;

import java.time.Instant;
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
import cn.studykid.multiagent.core.cap.CapMcpServerDef;
import cn.studykid.multiagent.core.cap.CapSkillDef;
import cn.studykid.multiagent.core.cap.CapSkillDef.McpDeclaration;
import cn.studykid.multiagent.core.cap.CapToolDef;
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
    private final AuthorizationService authorization;
    private final RateLimitPort rateLimiter;
    private final CurrentPrincipal currentPrincipal;

    public CapabilityCatalogController(CapToolDefPort toolDefs,
                                       CapSkillDefPort skillDefs,
                                       CapMcpServerDefPort serverDefs,
                                       AuthorizationService authorization,
                                       RateLimitPort rateLimiter,
                                       CurrentPrincipal currentPrincipal) {
        this.toolDefs = toolDefs;
        this.skillDefs = skillDefs;
        this.serverDefs = serverDefs;
        this.authorization = authorization;
        this.rateLimiter = rateLimiter;
        this.currentPrincipal = currentPrincipal;
    }

    // ── Tool ────────────────────────────────────────────────────────

    /** 列出全部 Tool 定义（每个逻辑 id 的最高版本）。 */
    @GetMapping("/tools")
    public ResponseEntity<List<ToolResponse>> listTools() {
        authorize();
        return ResponseEntity.ok(toolDefs.listAll().stream().map(ToolResponse::of).toList());
    }

    /** 读取单个 Tool 定义（最高版本）。 */
    @GetMapping("/tools/{toolId}")
    public ResponseEntity<ToolResponse> getTool(@PathVariable String toolId) {
        authorize();
        CapToolDef def = toolDefs.findLatestPublished(toolId)
                .or(() -> toolDefs.find(toolId, Integer.MAX_VALUE))
                .orElseThrow(() -> new ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "资源不存在"));
        return ResponseEntity.ok(ToolResponse.of(def));
    }

    // ── Skill ───────────────────────────────────────────────────────

    /** 列出全部 Skill 定义（每个逻辑 id 的最高版本）。 */
    @GetMapping("/skills")
    public ResponseEntity<List<SkillResponse>> listSkills() {
        authorize();
        return ResponseEntity.ok(skillDefs.listAll().stream().map(SkillResponse::of).toList());
    }

    /** 读取单个 Skill 定义（最高版本）。 */
    @GetMapping("/skills/{skillId}")
    public ResponseEntity<SkillResponse> getSkill(@PathVariable String skillId) {
        authorize();
        CapSkillDef def = skillDefs.findLatestPublished(skillId)
                .or(() -> skillDefs.find(skillId, Integer.MAX_VALUE))
                .orElseThrow(() -> new ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "资源不存在"));
        return ResponseEntity.ok(SkillResponse.of(def));
    }

    // ── MCP Server ─────────────────────────────────────────────────

    /** 列出全部 MCP Server 定义（每个逻辑 id 的最高版本）。 */
    @GetMapping("/mcps")
    public ResponseEntity<List<McpResponse>> listMcps() {
        authorize();
        return ResponseEntity.ok(serverDefs.listAll().stream().map(McpResponse::of).toList());
    }

    /** 读取单个 MCP Server 定义（最高版本）。 */
    @GetMapping("/mcps/{serverId}")
    public ResponseEntity<McpResponse> getMcp(@PathVariable String serverId) {
        authorize();
        CapMcpServerDef def = serverDefs.findLatestPublished(serverId)
                .or(() -> serverDefs.find(serverId, Integer.MAX_VALUE))
                .orElseThrow(() -> new ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "资源不存在"));
        return ResponseEntity.ok(McpResponse.of(def));
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

    /** Tool 目录视图。 */
    public record ToolResponse(
            @NonNull String toolId,
            @Nullable String name,
            @NonNull String kind,
            @NonNull String riskLevel,
            @Nullable String mcpServerId,
            @Nullable String mcpToolName,
            @NonNull String inputSchema,
            @NonNull String paramPolicies,
            int version,
            int schemaVersion,
            boolean published,
            @Nullable Instant publishedAt
    ) {
        static ToolResponse of(CapToolDef def) {
            return new ToolResponse(
                    def.id(), def.name(), def.kind().name(), def.riskLevel().name(),
                    def.mcpServerId(), def.mcpToolName(),
                    def.inputSchema(), def.paramPolicies(),
                    def.version(), def.schemaVersion(),
                    def.isPublished(), def.publishedAt());
        }
    }

    /** Skill 目录视图。 */
    public record SkillResponse(
            @NonNull String skillId,
            @Nullable String name,
            @Nullable String description,
            @NonNull List<String> declaredTools,
            @NonNull List<McpDeclarationResponse> declaredMcp,
            @NonNull String riskCeiling,
            @NonNull String paramPolicies,
            @NonNull List<String> requiredCaps,
            int version,
            int schemaVersion,
            boolean published,
            @Nullable Instant publishedAt
    ) {
        static SkillResponse of(CapSkillDef def) {
            return new SkillResponse(
                    def.id(), def.name(), def.description(),
                    def.declaredTools(),
                    def.declaredMcp().stream().map(McpDeclarationResponse::of).toList(),
                    def.riskCeiling().name(), def.paramPolicies(), def.requiredCaps(),
                    def.version(), def.schemaVersion(),
                    def.isPublished(), def.publishedAt());
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

    /**
     * MCP Server 目录视图。
     *
     * <p>⚠️ <b>刻意不包含 {@code credentialRef} 取值</b>：该字段只是凭据库定位符，
     * 凭据材料永不入响应（§4.2 / §6.4）；目录页只呈现连接形态与风险画像。
     * 仅暴露 {@code hasCredentialRef} 布尔，告知「是否存在凭据引用」而不回显任何定位符。
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
            boolean hasCredentialRef
    ) {
        static McpResponse of(CapMcpServerDef def) {
            return new McpResponse(
                    def.id(), def.version(), def.transport().name(), def.endpoint(),
                    def.authKind().name(), def.trustTier().name(), def.exposedToolAllowlist(),
                    def.connectionRisk().name(), def.dynamicDiscovery(), def.configFingerprint(),
                    def.schemaVersion(), def.createdAt(), def.publishedAt(),
                    def.credentialRef() != null);
        }
    }
}
