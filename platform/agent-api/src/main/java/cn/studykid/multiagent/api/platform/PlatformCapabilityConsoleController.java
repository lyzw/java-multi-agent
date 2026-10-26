package cn.studykid.multiagent.api.platform;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import cn.studykid.multiagent.api.security.PlatformSecurity;
import cn.studykid.multiagent.application.cap.ConfigPublishPayload;
import cn.studykid.multiagent.application.cap.ConfigPublishRejectedException;
import cn.studykid.multiagent.application.cap.PlatformCapabilityPublishService;
import cn.studykid.multiagent.core.cap.CapHttpToolConfig;
import cn.studykid.multiagent.core.cap.CapMcpServerDef;
import cn.studykid.multiagent.core.cap.CapSkillDef;
import cn.studykid.multiagent.core.cap.CapToolDef;
import cn.studykid.multiagent.core.cap.ConfigPointer;
import cn.studykid.multiagent.core.cap.ConfigLifecycleState;
import cn.studykid.multiagent.core.cap.ConfigPublishRequest;
import cn.studykid.multiagent.application.cap.ConfigLifecycleService;
import cn.studykid.multiagent.ports.audit.PlatformAuditPort;
import cn.studykid.multiagent.ports.audit.PlatformAuditPort.PlatformAuditEvent;
import cn.studykid.multiagent.ports.persistence.Page;
import cn.studykid.multiagent.ports.persistence.PlatformOpsSessionPort;
import cn.studykid.multiagent.ports.persistence.PlatformOpsUnavailableException;
import cn.studykid.multiagent.ports.persistence.cap.PlatformAgentDirectoryPort;
import cn.studykid.multiagent.ports.persistence.cap.PlatformAgentDirectoryPort.PlatformAgentRow;
import cn.studykid.multiagent.ports.persistence.CapMcpServerDefPort;
import cn.studykid.multiagent.ports.persistence.CapSkillDefPort;
import cn.studykid.multiagent.ports.persistence.CapToolDefPort;
import cn.studykid.multiagent.ports.persistence.ConfigPublishRequestPort;
import cn.studykid.multiagent.core.cap.ConfigPublishRequest;
import cn.studykid.multiagent.ports.security.IdentityDomains;
import cn.studykid.multiagent.ports.security.Namespaces;
import cn.studykid.multiagent.ports.security.PlatformPrincipal;

/**
 * 平台控制台 · 能力目录端点（/api/platform/v1/capabilities · 第三十九批）。
 *
 * <p>ntent：admin-console（平台令牌）直接管理 Tool / Skill / MCP Server，
 * 并<b>只读盘点</b>各租户 Agent 注册表。
 *
 * <p><b>三类对象，三套语义</b>：
 * <ol>
 *   <li><b>Tool / Skill / MCP Server</b> —— platform 级对象
 *       （{@code cap_tool_def} 等不启用 RLS，无租户维度），本端点提供
 *       全字段只读目录（与 {@code /api/cap/catalog/*} 同形状）+ 平台级
 *       「申请 → 审批 → 执行」三步写通道（复用
 *       {@link PlatformCapabilityPublishService}，一行不重复实现）。</li>
 *   <li><b>Agent</b> —— 租户私有（RLS 锁定）。平台控制台只提供跨租户
 *       <b>注册表盘点</b>（管理元数据；业务指令正文永不回传，重规划 §10 禁区），
 *       读经平台运维角色（BYPASSRLS + 会话窗，fail-closed）。</li>
 * </ol>
 *
 * <p><b>授权</b>：读需 {@code platform.resource.read}；三步写各步需
 * {@code platform.resource.manage}（矩阵驱动、默认拒绝），审批的双人复核
 * 由 {@code approverId != requesterId} 强制（服务端口内校验）。
 */
@RestController
@RequestMapping("/api/platform/v1/capabilities")
public class PlatformCapabilityConsoleController {

    private final CapToolDefPort toolDefs;
    private final CapSkillDefPort skillDefs;
    private final CapMcpServerDefPort serverDefs;
    private final ConfigLifecycleService lifecycle;
    private final PlatformSecurity security;
    private final PlatformCapabilityPublishService publishService;
    private final PlatformAgentDirectoryPort agentDirectory;
    private final PlatformOpsSessionPort platformOps;
    private final PlatformAuditPort audit;

    public PlatformCapabilityConsoleController(CapToolDefPort toolDefs,
                                               CapSkillDefPort skillDefs,
                                               CapMcpServerDefPort serverDefs,
                                               ConfigLifecycleService lifecycle,
                                               PlatformSecurity security,
                                               PlatformCapabilityPublishService publishService,
                                               PlatformAgentDirectoryPort agentDirectory,
                                               PlatformOpsSessionPort platformOps,
                                               PlatformAuditPort audit) {
        this.toolDefs = toolDefs;
        this.skillDefs = skillDefs;
        this.serverDefs = serverDefs;
        this.lifecycle = lifecycle;
        this.security = security;
        this.publishService = publishService;
        this.agentDirectory = agentDirectory;
        this.platformOps = platformOps;
        this.audit = audit;
    }

    // ── 目录读（platform 级对象 · 同 /api/cap/catalog 形状）─────────

    /** 列出全部 Tool 定义（每个逻辑 id 的最高版本；需 platform.resource.read）。 */
    @GetMapping("/tools")
    public ResponseEntity<List<ToolDto>> listTools(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal) {
        security.require(principal, Namespaces.Platform.RESOURCE_READ, "capability", null);
        return ResponseEntity.ok(toolDefs.listAll().stream()
                .map(def -> ToolDto.of(def, lifecycleStateOf("tool", def.id(), def.version())))
                .toList());
    }

    /** 单个 Tool 定义（最高版本）。 */
    @GetMapping("/tools/{toolId}")
    public ResponseEntity<ToolDto> getTool(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal, @PathVariable String toolId) {
        security.require(principal, Namespaces.Platform.RESOURCE_READ, "capability", toolId);
        CapToolDef def = findTool(toolId);
        return ResponseEntity.ok(ToolDto.of(def,
                lifecycleStateOf("tool", def.id(), def.version())));
    }

    /** Tool 的关系：绑定的 MCP Server（native = null，合法状态）。 */
    @GetMapping("/tools/{toolId}/relations")
    public ResponseEntity<ToolRelationsDto> toolRelations(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal, @PathVariable String toolId) {
        security.require(principal, Namespaces.Platform.RESOURCE_READ, "capability", toolId);
        CapToolDef def = findTool(toolId);
        McpSummaryDto server = null;
        if (def.mcpServerId() != null) {
            server = serverDefs.findLatestPublished(def.mcpServerId())
                    .or(() -> serverDefs.find(def.mcpServerId(), Integer.MAX_VALUE))
                    .map(McpSummaryDto::of)
                    .orElse(null);
        }
        return ResponseEntity.ok(new ToolRelationsDto(toolId, server));
    }

    /** 列出全部 Skill 定义（最高版本）。 */
    @GetMapping("/skills")
    public ResponseEntity<List<SkillDto>> listSkills(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal) {
        security.require(principal, Namespaces.Platform.RESOURCE_READ, "capability", null);
        return ResponseEntity.ok(skillDefs.listAll().stream()
                .map(def -> SkillDto.of(def,
                        lifecycleStateOf("skill", def.id(), def.version())))
                .toList());
    }

    /** 单个 Skill 定义（最高版本）。 */
    @GetMapping("/skills/{skillId}")
    public ResponseEntity<SkillDto> getSkill(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal, @PathVariable String skillId) {
        security.require(principal, Namespaces.Platform.RESOURCE_READ, "capability", skillId);
        CapSkillDef def = findSkill(skillId);
        return ResponseEntity.ok(SkillDto.of(def,
                lifecycleStateOf("skill", def.id(), def.version())));
    }

    /** Skill 的关系：声明的 Tool / MCP + 缺失项（如实呈现悬空引用）。 */
    @GetMapping("/skills/{skillId}/relations")
    public ResponseEntity<SkillRelationsDto> skillRelations(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal, @PathVariable String skillId) {
        security.require(principal, Namespaces.Platform.RESOURCE_READ, "capability", skillId);
        CapSkillDef def = findSkill(skillId);
        List<ToolSummaryDto> tools = new ArrayList<>();
        List<String> missingTools = new ArrayList<>();
        for (String toolId : def.declaredTools()) {
            toolDefs.findLatestPublished(toolId)
                    .or(() -> toolDefs.find(toolId, Integer.MAX_VALUE))
                    .map(t -> tools.add(ToolSummaryDto.of(t)))
                    .orElseGet(() -> missingTools.add(toolId));
        }
        List<McpSummaryDto> servers = new ArrayList<>();
        List<String> missingServers = new ArrayList<>();
        for (var declaration : def.declaredMcp()) {
            serverDefs.findLatestPublished(declaration.serverId())
                    .or(() -> serverDefs.find(declaration.serverId(), Integer.MAX_VALUE))
                    .map(s -> servers.add(McpSummaryDto.of(s)))
                    .orElseGet(() -> missingServers.add(declaration.serverId()));
        }
        return ResponseEntity.ok(new SkillRelationsDto(
                skillId, tools, missingTools, servers, missingServers));
    }

    /** 列出全部 MCP Server 定义（最高版本）。 */
    @GetMapping("/mcps")
    public ResponseEntity<List<McpDto>> listMcps(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal) {
        security.require(principal, Namespaces.Platform.RESOURCE_READ, "capability", null);
        return ResponseEntity.ok(serverDefs.listAll().stream()
                .map(def -> McpDto.of(def,
                        lifecycleStateOf("mcp_server", def.id(), def.version())))
                .toList());
    }

    /** 单个 MCP Server 定义（最高版本）。 */
    @GetMapping("/mcps/{serverId}")
    public ResponseEntity<McpDto> getMcp(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal, @PathVariable String serverId) {
        security.require(principal, Namespaces.Platform.RESOURCE_READ, "capability", serverId);
        CapMcpServerDef def = findMcp(serverId);
        return ResponseEntity.ok(McpDto.of(def,
                lifecycleStateOf("mcp_server", def.id(), def.version())));
    }

    /** MCP Server 的关系：允许集（null≠[]）与实际登记的 Tool 分列。 */
    @GetMapping("/mcps/{serverId}/relations")
    public ResponseEntity<McpRelationsDto> mcpRelations(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal, @PathVariable String serverId) {
        security.require(principal, Namespaces.Platform.RESOURCE_READ, "capability", serverId);
        CapMcpServerDef def = findMcp(serverId);
        List<ToolSummaryDto> tools = toolDefs.listByMcpServer(serverId).stream()
                .map(ToolSummaryDto::of)
                .toList();
        return ResponseEntity.ok(new McpRelationsDto(serverId, def.exposedToolAllowlist(), tools));
    }

    // ── Agent 注册表（跨租户 · 只读盘点）──────────────────────────

    /**
     * 跨租户 Agent 注册表（需 platform.resource.read；运维通道未接入时 503 fail-closed）。
     *
     * <p>⚠️ 只回管理元数据（id/名称/版本/发布/角色/启用技能/能力标签/有无覆盖），
     * definition 正文、运行输出、会话内容不出现在任何字段（禁区红线）。
     */
    @GetMapping("/agents")
    public ResponseEntity<List<AgentRowDto>> listAgents(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @RequestParam @Nullable String tenantId) {
        security.require(principal, Namespaces.Platform.RESOURCE_READ, "agent_registry", null);
        if (tenantId != null && !tenantId.isBlank()
                && !isUuid(tenantId.trim())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tenantId 须为 UUID");
        }
        List<PlatformAgentRow> rows = platformOps.inPlatformScopeSession(
                () -> agentDirectory.listAgents(tenantId == null ? null : tenantId.trim()));
        // ⚠️ PlatformOpsUnavailableException（运维未接入）不在此包装成 5xx
        //    ResponseStatusException：ApiExceptionHandler 会把「控制器声明的 5xx」
        //    一律吞成 500（P4-D09 防误吞护栏）。运维未接入由专用异常处理器按 503 呈现
        //    （fail-closed）；真实 SQL 故障落入兜底 500（语义正确）。
        return ResponseEntity.ok(rows.stream().map(AgentRowDto::of).toList());
    }

    private static boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // ── 三步写通道（申请 → 审批 → 执行；复用平台发布服务）─────────

    /** 提交平台级发布申请（不写配置表；需 platform.resource.manage）。 */
    @PostMapping("/publish-requests")
    public ResponseEntity<RequestDto> requestPublish(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @RequestBody @Nullable PlatformCapabilityRequestView request) {
        security.require(principal, Namespaces.Platform.RESOURCE_MANAGE, "capability", null);
        if (request == null || isBlank(request.reason())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "变更理由不能为空");
        }
        try {
            ConfigPublishRequest created = publishService.requestPublish(
                    PlatformCapabilityRequestView.toToolSpecs(request.tools()),
                    PlatformCapabilityRequestView.toSkillSpecs(request.skills()),
                    PlatformCapabilityRequestView.toMcpSpecs(request.mcpServers()),
                    request.expectedPublishSeq(), request.reason().trim(), principal.userId());
            recordAudit(principal, Namespaces.Platform.RESOURCE_MANAGE, "capability_publish_request",
                    created.requestId(), "platform.capability.publish.request");
            return ResponseEntity.ok(RequestDto.of(created));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (ConfigPublishRejectedException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    e.reasonCode() + ": " + e.getMessage());
        }
    }

    /** 提交平台级生命周期迁移申请（停用；需 platform.resource.manage）。 */
    @PostMapping("/lifecycle-requests")
    public ResponseEntity<RequestDto> requestLifecycle(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @RequestBody @Nullable LifecycleRequestView request) {
        security.require(principal, Namespaces.Platform.RESOURCE_MANAGE, "capability", null);
        if (request == null || isBlank(request.reason())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "变更理由不能为空");
        }
        try {
            ConfigPublishRequest created = publishService.requestLifecycle(
                    new ConfigPublishPayload.LifecycleSpec(
                            request.type(), request.objectId(), request.version(),
                            request.expectedFrom(), request.to()),
                    request.reason().trim(), principal.userId());
            recordAudit(principal, Namespaces.Platform.RESOURCE_MANAGE, "capability_lifecycle_request",
                    created.requestId(), "platform.capability.lifecycle.request");
            return ResponseEntity.ok(RequestDto.of(created));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        // ⚠️ cn.studykid...ports.persistence.PlatformOpsUnavailableException（运维未接入）直接上抛：由专用异常处理器呈现 503（fail-closed）
        //    ——包装成 ResponseStatusException(503) 会被 P4-D09 护栏吞成 500。
    }

    /** 平台级变更申请列表（游标分页）。 */
    @GetMapping("/publish-requests")
    public ResponseEntity<List<RequestDto>> listRequests(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @RequestParam @Nullable String state,
            @RequestParam @Nullable String cursor,
            @RequestParam @Nullable Integer limit) {
        security.require(principal, Namespaces.Platform.RESOURCE_READ, "capability", null);
        ConfigPublishRequest.@Nullable State parsed = parseState(state);
        int pageSize = limit == null ? 50 : Math.max(1, Math.min(limit, 200));
        Page<ConfigPublishRequest> page = publishService.list(parsed, cursor, pageSize);
        return ResponseEntity.ok(page.items().stream().map(RequestDto::of).toList());
    }

    /** 单条平台级变更申请（详情深链）。 */
    @GetMapping("/publish-requests/{requestId}")
    public ResponseEntity<RequestDto> getRequest(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal, @PathVariable String requestId) {
        security.require(principal, Namespaces.Platform.RESOURCE_READ, "capability", null);
        return publishService.findRequest(requestId)
                .map(r -> ResponseEntity.ok(RequestDto.of(r)))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "申请不存在"));
    }

    /** 审批（APPROVE / REJECT；⚠️ 审批人不得等于申请人，服务端口内强制）。 */
    @PostMapping("/publish-requests/{requestId}/decision")
    public ResponseEntity<RequestDto> decide(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal, @PathVariable String requestId,
            @RequestBody @Nullable DecisionView request) {
        security.require(principal, Namespaces.Platform.RESOURCE_MANAGE, "capability", requestId);
        if (request == null || request.decision() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "decision 必填");
        }
        ConfigPublishRequestPort.@Nullable Decision decision =
                "APPROVE".equalsIgnoreCase(request.decision())
                        ? ConfigPublishRequestPort.Decision.APPROVE
                        : "REJECT".equalsIgnoreCase(request.decision())
                                ? ConfigPublishRequestPort.Decision.REJECT : null;
        if (decision == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "decision 仅支持 APPROVE / REJECT");
        }
        try {
            ConfigPublishRequest updated = publishService.decide(
                    requestId, decision, request.note(), principal.userId());
            recordAudit(principal, Namespaces.Platform.RESOURCE_MANAGE,
                    "capability_publish_request", requestId,
                    "platform.capability.publish.decision:" + request.decision());
            return ResponseEntity.ok(RequestDto.of(updated));
        } catch (IllegalArgumentException | ConfigPublishRequestPort.NotFoundException
                 | ConfigPublishRequestPort.StateConflictException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /** 执行已批准的变更（此步才真正写入平台配置）。 */
    @PostMapping("/publish-requests/{requestId}/apply")
    public ResponseEntity<RequestDto> apply(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal, @PathVariable String requestId) {
        security.require(principal, Namespaces.Platform.RESOURCE_MANAGE, "capability", requestId);
        try {
            ConfigPublishRequest updated = publishService.apply(requestId, principal.userId());
            recordAudit(principal, Namespaces.Platform.RESOURCE_MANAGE,
                    "capability_publish_request", requestId, "platform.capability.publish.apply");
            return ResponseEntity.ok(RequestDto.of(updated));
        } catch (ConfigPublishRequestPort.NotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "申请不存在");
        } catch (ConfigPublishRequestPort.StateConflictException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (IllegalArgumentException | ConfigPublishRejectedException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /** 当前平台发布指针（CAS 基线来源）。 */
    @GetMapping("/pointer")
    public ResponseEntity<PointerDto> pointer(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal) {
        security.require(principal, Namespaces.Platform.RESOURCE_READ, "capability", null);
        ConfigPointer p = publishService.currentPointer().orElse(null);
        return ResponseEntity.ok(PointerDto.of(p));
    }

    // ── 内部 ───────────────────────────────────────────────

    /** 生命周期状态读取失败返回 null（前端禁用停用按钮），不让整页挂掉。 */
    private @Nullable String lifecycleStateOf(String type, String objectId, int version) {
        try {
            ConfigLifecycleState state = lifecycle.stateOf(type, null, objectId, version)
                    .orElse(null);
            return state == null ? null : state.name();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private CapToolDef findTool(String toolId) {
        return toolDefs.findLatestPublished(toolId)
                .or(() -> toolDefs.find(toolId, Integer.MAX_VALUE))
                .orElseThrow(() -> notFound());
    }

    private CapSkillDef findSkill(String skillId) {
        return skillDefs.findLatestPublished(skillId)
                .or(() -> skillDefs.find(skillId, Integer.MAX_VALUE))
                .orElseThrow(() -> notFound());
    }

    private CapMcpServerDef findMcp(String serverId) {
        return serverDefs.findLatestPublished(serverId)
                .or(() -> serverDefs.find(serverId, Integer.MAX_VALUE))
                .orElseThrow(() -> notFound());
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "资源不存在");
    }

    private static ConfigPublishRequest.@Nullable State parseState(@Nullable String state) {
        if (state == null || state.isBlank()) {
            return null;
        }
        try {
            return ConfigPublishRequest.State.valueOf(state.trim());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知状态: " + state);
        }
    }

    private static boolean isBlank(@Nullable String s) {
        return s == null || s.isBlank();
    }

    private void recordAudit(@NonNull PlatformPrincipal actor, @NonNull String action,
                             @NonNull String targetType, @Nullable String targetId,
                             @Nullable String detail) {
        audit.record(new PlatformAuditEvent(UUID.randomUUID(), actor.userId(),
                "PLATFORM_USER", detail != null ? detail : action,
                targetType, targetId, "ALLOW", detail, null, null, Instant.now()));
    }

    // ── DTO ───────────────────────────────────────────────
    // 形状与 admin-console 的 ToolCatalogItem / SkillCatalogItem / McpCatalogItem
    // 类型契约保持一致（camelCase 命名、lifecycleState/published 并存）。

    public record ToolDto(@NonNull String toolId, @Nullable String name, @NonNull String kind,
                          @NonNull String riskLevel, @Nullable String mcpServerId,
                          @Nullable String mcpToolName, @Nullable String httpMethod,
                          @Nullable String httpUrl, @Nullable Integer httpTimeoutMs,
                          @NonNull String inputSchema, @NonNull String paramPolicies,
                          int version, int schemaVersion, boolean published,
                          @Nullable Instant publishedAt, @Nullable String lifecycleState,
                          @NonNull String configFingerprint, @NonNull Instant createdAt) {
        static ToolDto of(CapToolDef def, @Nullable String lifecycleState) {
            CapHttpToolConfig http = def.httpConfig();
            return new ToolDto(def.id(), def.name(), def.kind().name(), def.riskLevel().name(),
                    def.mcpServerId(), def.mcpToolName(),
                    http == null ? null : http.method(), http == null ? null : http.url(),
                    http == null ? null : http.timeoutMs(),
                    def.inputSchema(), def.paramPolicies(), def.version(), def.schemaVersion(),
                    def.isPublished(), def.publishedAt(), lifecycleState,
                    def.configFingerprint(), def.createdAt());
        }
    }

    public record SkillDto(@NonNull String skillId, @Nullable String name,
                           @Nullable String description, @NonNull List<String> declaredTools,
                           @NonNull List<McpDeclarationDto> declaredMcp,
                           @NonNull String riskCeiling, @NonNull String paramPolicies,
                           @NonNull List<String> requiredCaps, int version, int schemaVersion,
                           boolean published, @Nullable Instant publishedAt,
                           @Nullable String lifecycleState) {
        static SkillDto of(CapSkillDef def, @Nullable String lifecycleState) {
            return new SkillDto(def.id(), def.name(), def.description(), def.declaredTools(),
                    def.declaredMcp().stream().map(McpDeclarationDto::of).toList(),
                    def.riskCeiling().name(), def.paramPolicies(), def.requiredCaps(),
                    def.version(), def.schemaVersion(), def.isPublished(), def.publishedAt(),
                    lifecycleState);
        }
    }

    public record McpDeclarationDto(@NonNull String serverId, @Nullable List<String> toolAllowlist) {
        static McpDeclarationDto of(CapSkillDef.McpDeclaration d) {
            return new McpDeclarationDto(d.serverId(), d.toolAllowlist());
        }
    }

    public record McpDto(@NonNull String serverId, int version, @NonNull String transport,
                         @Nullable String endpoint, @NonNull String authKind,
                         @NonNull String trustTier, @Nullable List<String> exposedToolAllowlist,
                         @NonNull String connectionRisk, boolean dynamicDiscovery,
                         @NonNull String configFingerprint, int schemaVersion,
                         @Nullable Instant createdAt, @Nullable Instant publishedAt,
                         boolean published, boolean hasCredentialRef,
                         @Nullable String lifecycleState) {
        static McpDto of(CapMcpServerDef def, @Nullable String lifecycleState) {
            return new McpDto(def.id(), def.version(), def.transport().name(), def.endpoint(),
                    def.authKind().name(), def.trustTier().name(), def.exposedToolAllowlist(),
                    def.connectionRisk().name(), def.dynamicDiscovery(), def.configFingerprint(),
                    def.schemaVersion(), def.createdAt(), def.publishedAt(), def.isPublished(),
                    def.credentialRef() != null, lifecycleState);
        }
    }

    public record ToolSummaryDto(@NonNull String toolId, @Nullable String name,
                                 @NonNull String kind, @NonNull String riskLevel,
                                 int version, boolean published) {
        static ToolSummaryDto of(CapToolDef def) {
            return new ToolSummaryDto(def.id(), def.name(), def.kind().name(),
                    def.riskLevel().name(), def.version(), def.isPublished());
        }
    }

    public record McpSummaryDto(@NonNull String serverId, @NonNull String transport,
                                @NonNull String trustTier, @NonNull String connectionRisk,
                                int version, boolean published) {
        static McpSummaryDto of(CapMcpServerDef def) {
            return new McpSummaryDto(def.id(), def.transport().name(), def.trustTier().name(),
                    def.connectionRisk().name(), def.version(), def.isPublished());
        }
    }

    public record ToolRelationsDto(@NonNull String toolId, @Nullable McpSummaryDto mcpServer) {
    }

    public record SkillRelationsDto(@NonNull String skillId, @NonNull List<ToolSummaryDto> tools,
                                    @NonNull List<String> missingTools,
                                    @NonNull List<McpSummaryDto> mcpServers,
                                    @NonNull List<String> missingMcpServers) {
    }

    public record McpRelationsDto(@NonNull String serverId,
                                  @Nullable List<String> exposedToolAllowlist,
                                  @NonNull List<ToolSummaryDto> tools) {
    }

    /** Agent 注册表条目视图（只含管理元数据）。 */
    public record AgentRowDto(@NonNull String tenantId, @NonNull String agentId,
                              @Nullable String name, @Nullable String description,
                              int versionCount, @Nullable Integer latestVersionNumber,
                              @Nullable Instant latestPublishedAt, @Nullable Integer defVersion,
                              @Nullable String lifecycleState, @NonNull List<String> roles,
                              @NonNull List<String> enabledSkills,
                              @NonNull List<String> capabilityTags, boolean hasOverrides) {
        static AgentRowDto of(PlatformAgentRow r) {
            return new AgentRowDto(r.tenantId(), r.agentId(), r.name(), r.description(),
                    r.versionCount(), r.latestVersionNumber(), r.latestPublishedAt(),
                    r.defVersion(), r.lifecycleState(), r.roles(), r.enabledSkills(),
                    r.capabilityTags(), r.hasOverrides());
        }
    }

    /** 变更申请视图（与租户链 /cap/platform/* 同形状）。 */
    public record RequestDto(@NonNull String requestId, @NonNull String scope,
                             @NonNull String operation, @NonNull String state,
                             @NonNull String requesterId, @Nullable String approverId,
                             @Nullable String decisionNote, @Nullable String failureReason,
                             @Nullable Instant requestedAt, @Nullable Instant decidedAt,
                             @Nullable Instant appliedAt) {
        static RequestDto of(ConfigPublishRequest r) {
            return new RequestDto(r.requestId(), r.scope() == null ? "PLATFORM" : r.scope().name(),
                    r.operation().name(), r.state().name(), r.requesterId(),
                    r.approverId(), r.decisionNote(),
                    r.failureReason(), r.requestedAt(), r.decidedAt(), r.appliedAt());
        }
    }

    public record PointerDto(@Nullable Long currentReleaseId, @Nullable Long currentPublishSeq,
                             @Nullable Instant updatedAt) {
        static PointerDto of(@Nullable ConfigPointer p) {
            return p == null ? new PointerDto(null, null, null)
                    : new PointerDto(p.currentReleaseId(), p.currentPublishSeq(), p.updatedAt());
        }
    }

    public record DecisionView(@Nullable String decision, @Nullable String note) {
    }

    public record LifecycleRequestView(@Nullable String type, @Nullable String objectId,
                                       @Nullable Integer version, @Nullable String expectedFrom,
                                       @Nullable String to, @Nullable String reason) {
    }

    /** 发布申请视图（草稿；版本号/指纹由服务端决定，客户端不采信）。 */
    public record PlatformCapabilityRequestView(
            @Nullable List<ToolSpecView> tools,
            @Nullable List<SkillSpecView> skills,
            @Nullable List<McpSpecView> mcpServers,
            @Nullable Long expectedPublishSeq,
            @Nullable String reason) {

        static List<ConfigPublishPayload.ToolSpec> toToolSpecs(
                @Nullable List<ToolSpecView> views) {
            if (views == null) return List.of();
            return views.stream().map(v -> new ConfigPublishPayload.ToolSpec(
                    v.toolId(), v.name(), v.kind(), v.riskLevel(), v.mcpServerId(),
                    v.mcpToolName(), v.httpMethod(), v.httpUrl(), v.httpTimeoutMs(),
                    v.inputSchema(), v.paramPolicies())).toList();
        }

        static List<ConfigPublishPayload.SkillSpec> toSkillSpecs(
                @Nullable List<SkillSpecView> views) {
            if (views == null) return List.of();
            return views.stream().map(v -> new ConfigPublishPayload.SkillSpec(
                    v.skillId(), v.name(), v.description(), v.declaredTools(),
                    declaredMcpOf(v.declaredMcp()), v.riskCeiling(), v.paramPolicies(),
                    v.requiredCaps())).toList();
        }

        private static List<ConfigPublishPayload.McpDeclarationSpec> declaredMcpOf(
                @Nullable List<McpDeclarationSpecView> views) {
            if (views == null) return List.of();
            return views.stream().map(d -> new ConfigPublishPayload.McpDeclarationSpec(
                    d.serverId(), d.toolAllowlist())).toList();
        }

        static List<ConfigPublishPayload.McpServerSpec> toMcpSpecs(
                @Nullable List<McpSpecView> views) {
            if (views == null) return List.of();
            return views.stream().map(v -> new ConfigPublishPayload.McpServerSpec(
                    v.serverId(), v.transport(), v.endpoint(), v.command(), v.authKind(),
                    v.trustTier(), v.exposedToolAllowlist(), v.connectionRisk(),
                    v.credentialRef())).toList();
        }
    }

    public record ToolSpecView(@Nullable String toolId, @Nullable String name,
                               @Nullable String kind, @Nullable String riskLevel,
                               @Nullable String mcpServerId, @Nullable String mcpToolName,
                               @Nullable String httpMethod, @Nullable String httpUrl,
                               @Nullable Integer httpTimeoutMs, @Nullable String inputSchema,
                               @Nullable String paramPolicies) {
    }

    public record SkillSpecView(@Nullable String skillId, @Nullable String name,
                                @Nullable String description, @Nullable List<String> declaredTools,
                                @Nullable List<McpDeclarationSpecView> declaredMcp,
                                @Nullable String riskCeiling, @Nullable String paramPolicies,
                                @Nullable List<String> requiredCaps) {
    }

    public record McpDeclarationSpecView(@Nullable String serverId,
                                         @Nullable List<String> toolAllowlist) {
    }

    public record McpSpecView(@Nullable String serverId, @Nullable String transport,
                              @Nullable String endpoint, @Nullable String command,
                              @Nullable String authKind, @Nullable String trustTier,
                              @Nullable List<String> exposedToolAllowlist,
                              @Nullable String connectionRisk,
                              @Nullable String credentialRef) {
    }
}
