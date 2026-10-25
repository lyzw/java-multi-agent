package cn.studykid.multiagent.api.config;

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

import cn.studykid.multiagent.api.governance.GovernanceSupport;
import cn.studykid.multiagent.api.run.RunController.CurrentPrincipal;
import cn.studykid.multiagent.application.cap.ConfigPublishPayload;
import cn.studykid.multiagent.application.cap.PlatformCapabilityPublishService;
import cn.studykid.multiagent.core.cap.ConfigPublishRequest;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.ConfigPublishRequestPort;
import cn.studykid.multiagent.ports.persistence.Page;
import cn.studykid.multiagent.ports.persistence.PlatformOpsUnavailableException;
import cn.studykid.multiagent.ports.persistence.RateLimitPort;

/**
 * 平台级能力对象（Tool / Skill / MCP Server）的写入口（第三十四批 · C34-2）。
 *
 * <p>⚠️ <b>为什么平台级写入口现在才接上</b>：
 * 此前这些对象**没有可达的写入口** —— 管理台的 Tool / Skill / MCP 页是
 * <b>只读目录</b>，变更只能靠手写 SQL 或带外运维工具。
 * P6 登记的限制 7（「发布 / 回滚 / 生命周期迁移没有 HTTP 管理端点」）
 * 在本端点落地后，对**平台级对象**这一支不再成立。
 *
 * <p>⚠️ <b>两道闸门，缺一不可</b>：
 * <ol>
 *   <li><b>身份闸门</b>：{@code PLATFORM_ADMIN} 角色（403 默认拒绝）。
 *       平台身份表 {@code principal} 是<b>租户内</b>的，没有跨租户主体 ——
 *       若没有这道闸门，任一租户管理员自授角色即可改全平台配置。</li>
 *   <li><b>通道闸门</b>：写入走<b>平台运维通道</b>（BYPASSRLS 凭据）。
 *       PLATFORM 作用域的发布头（{@code cap_config_release}）与当前指针
 *       （{@code cap_config_pointer}）的写策略只放行本租户的 TENANT 行，
 *       没有运维通道就<b>写不进去</b>。</li>
 * </ol>
 * ⚠️ **运维角色未接入时明确失败（503），绝不回落到应用角色** ——
 * 回落后 RLS 会拒写，报错点落在 RLS 策略上，离「这个环境没配运维凭据」这一真因极远。
 *
 * <p>⚠️ <b>仍是「申请 → 审批 → 执行」三步</b>：
 * 把申请人与审批人两个 id 放进一次请求由调用方填写，
 * 双人复核就退化成「调用方自己声明另一个人同意了」（V18 迁移注释的原话）。
 * 平台级对象影响<b>全平台</b>，比租户级更需要复核。
 *
 * <p>⚠️ <b>「删除」语义</b>：物理删除<b>不做</b> ——
 * 已发布行不可变（DB 层触发器），正确语义是<b>停用</b>（{@code DEPRECATED}，单向终态）
 * 与「删除未发布草稿」。管理台的「删除」按钮因此映射到停用通道。
 */
@RestController
@RequestMapping("/api/cap/platform")
public class PlatformCapabilityController {

    private final PlatformCapabilityPublishService platform;
    private final RateLimitPort rateLimiter;
    private final CurrentPrincipal currentPrincipal;

    public PlatformCapabilityController(PlatformCapabilityPublishService platform,
                                        RateLimitPort rateLimiter,
                                        CurrentPrincipal currentPrincipal) {
        this.platform = platform;
        this.rateLimiter = rateLimiter;
        this.currentPrincipal = currentPrincipal;
    }

    // ── 第一步：申请 ───────────────────────────────────────────────

    /**
     * 提交平台级发布申请（<b>不写入配置表</b>）。
     *
     * <p>⚠️ 一个请求里可以同时带 Tool / Skill / MCP 三类对象 ——
     * 它们属于<b>同一个</b> PLATFORM 作用域发布，共享同一个发布头与指针 CAS。
     * 分开发等于「先发 Tool、再发引用它的 Skill」，中间会出现
     * 「Skill 已生效但它声明的 Tool 还没生效」的窗口。
     */
    @PostMapping("/publish-requests")
    public ResponseEntity<RequestResponse> requestPublish(
            @RequestBody PlatformPublishRequest request) {
        requirePlatformAdmin();
        String reason = request.reason() == null ? "" : request.reason().trim();
        if (reason.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "变更理由不能为空");
        }
        try {
            ConfigPublishRequest created = platform.requestPublish(
                    toolSpecs(request.tools()),
                    skillSpecs(request.skills()),
                    mcpServerSpecs(request.mcpServers()),
                    request.expectedPublishSeq(),
                    reason,
                    currentPrincipal.principalId());
            return ResponseEntity.ok(RequestResponse.of(created));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (PlatformOpsUnavailableException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        }
    }

    /** 提交平台级生命周期迁移申请（停用 / 回退）。 */
    @PostMapping("/lifecycle-requests")
    public ResponseEntity<RequestResponse> requestLifecycle(
            @RequestBody PlatformLifecycleRequest request) {
        requirePlatformAdmin();
        String reason = request.reason() == null ? "" : request.reason().trim();
        if (reason.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "变更理由不能为空");
        }
        try {
            ConfigPublishPayload.LifecycleSpec spec = new ConfigPublishPayload.LifecycleSpec(
                    request.type(), request.objectId(), request.version(),
                    request.expectedFrom(), request.to());
            ConfigPublishRequest created = platform.requestLifecycle(
                    spec, reason, currentPrincipal.principalId());
            return ResponseEntity.ok(RequestResponse.of(created));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (PlatformOpsUnavailableException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        }
    }

    // ── 第二步：审批 ───────────────────────────────────────────────

    /** 审批决定（批准或拒绝；<b>不写入配置表</b>）。 */
    @PostMapping("/publish-requests/{requestId}/decision")
    public ResponseEntity<RequestResponse> decide(@PathVariable String requestId,
                                                  @RequestBody PlatformDecisionRequest request) {
        requirePlatformAdmin();
        ConfigPublishRequestPort.Decision decision;
        try {
            decision = ConfigPublishRequestPort.Decision.valueOf(
                    String.valueOf(request.decision()).trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "decision 仅接受 APPROVE / REJECT");
        }
        try {
            return ResponseEntity.ok(RequestResponse.of(
                    platform.decide(requestId, decision, request.note(),
                            currentPrincipal.principalId())));
        } catch (ConfigPublishRequestPort.SelfApprovalException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "不得审批自己提交的变更申请");
        } catch (ConfigPublishRequestPort.StateConflictException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "资源与当前状态冲突");
        } catch (ConfigPublishRequestPort.NotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "资源不存在");
        } catch (PlatformOpsUnavailableException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        }
    }

    // ── 第三步：执行 ───────────────────────────────────────────────

    /** 执行已批准的变更（<b>此步才真正写入平台配置</b>）。 */
    @PostMapping("/publish-requests/{requestId}/apply")
    public ResponseEntity<RequestResponse> apply(@PathVariable String requestId) {
        requirePlatformAdmin();
        try {
            return ResponseEntity.ok(RequestResponse.of(
                    platform.apply(requestId, currentPrincipal.principalId())));
        } catch (ConfigPublishRequestPort.StateConflictException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "资源与当前状态冲突");
        } catch (ConfigPublishRequestPort.NotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "资源不存在");
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (PlatformOpsUnavailableException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        }
    }

    // ── 读 ─────────────────────────────────────────────────────────

    /** 列出平台级变更申请。 */
    @GetMapping("/publish-requests")
    public ResponseEntity<PageResponse<RequestResponse>> listRequests(
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        requirePlatformAdmin();
        ConfigPublishRequest.State filter = null;
        if (state != null && !state.isBlank()) {
            try {
                filter = ConfigPublishRequest.State.valueOf(
                        state.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "state 仅接受 PENDING / APPROVED / REJECTED / APPLIED / FAILED");
            }
        }
        try {
            Page<ConfigPublishRequest> page = platform.list(filter, cursor, limit);
            return ResponseEntity.ok(PageResponse.of(page, RequestResponse::of));
        } catch (PlatformOpsUnavailableException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        }
    }

    /** 读取单条平台级变更申请。 */
    @GetMapping("/publish-requests/{requestId}")
    public ResponseEntity<RequestResponse> getRequest(@PathVariable String requestId) {
        requirePlatformAdmin();
        try {
            return platform.findRequest(requestId)
                    .map(RequestResponse::of)
                    .map(ResponseEntity::ok)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND, "资源不存在"));
        } catch (PlatformOpsUnavailableException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        }
    }

    // ── 内部 ───────────────────────────────────────────────────────

    /**
     * 平台级作用域闸门：非 {@code PLATFORM_ADMIN} 一律 403（默认拒绝）。
     *
     * <p>⚠️ 闸门在**每个端点的最前面** —— 任何业务逻辑之前先判角色。
     */
    private void requirePlatformAdmin() {
        TenantContext context = currentPrincipal.context();
        if (!context.hasRole("PLATFORM_ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "平台级能力配置仅限 PLATFORM_ADMIN（须经平台运维通道授权）");
        }
        if (GovernanceSupport.rateLimited(rateLimiter, context,
                RateLimitPort.Buckets.GOVERNANCE_QUERY)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁");
        }
    }

    private static List<ConfigPublishPayload.ToolSpec> toolSpecs(
            List<PlatformToolSpec> specs) {
        if (specs == null) {
            return List.of();
        }
        return specs.stream().map(spec -> new ConfigPublishPayload.ToolSpec(
                spec.toolId(), spec.name(), spec.kind(), spec.riskLevel(),
                spec.mcpServerId(), spec.mcpToolName(),
                spec.inputSchema() == null ? "{}" : spec.inputSchema(),
                spec.paramPolicies() == null ? "{}" : spec.paramPolicies())).toList();
    }

    private static List<ConfigPublishPayload.SkillSpec> skillSpecs(
            List<PlatformSkillSpec> specs) {
        if (specs == null) {
            return List.of();
        }
        return specs.stream().map(spec -> {
            List<ConfigPublishPayload.McpDeclarationSpec> declarations = spec.declaredMcp() == null
                    ? List.of()
                    : spec.declaredMcp().stream()
                            .map(d -> new ConfigPublishPayload.McpDeclarationSpec(
                                    d.serverId(), d.toolAllowlist()))
                            .toList();
            return new ConfigPublishPayload.SkillSpec(
                    spec.skillId(), spec.name(), spec.description(),
                    spec.declaredTools() == null ? List.of() : spec.declaredTools(),
                    declarations,
                    spec.riskCeiling(),
                    spec.paramPolicies() == null ? "{}" : spec.paramPolicies(),
                    spec.requiredCaps() == null ? List.of() : spec.requiredCaps());
        }).toList();
    }

    private static List<ConfigPublishPayload.McpServerSpec> mcpServerSpecs(
            List<PlatformMcpServerSpec> specs) {
        if (specs == null) {
            return List.of();
        }
        return specs.stream().map(spec -> new ConfigPublishPayload.McpServerSpec(
                spec.serverId(), spec.transport(), spec.endpoint(), spec.command(),
                spec.authKind(), spec.trustTier(), spec.exposedToolAllowlist(),
                spec.connectionRisk(), spec.credentialRef())).toList();
    }

    // ── DTO ─────────────────────────────────────────────────────────

    /** 平台级发布申请体。 */
    public record PlatformPublishRequest(
            @Nullable List<PlatformToolSpec> tools,
            @Nullable List<PlatformSkillSpec> skills,
            @Nullable List<PlatformMcpServerSpec> mcpServers,
            @Nullable Long expectedPublishSeq,
            @Nullable String reason) {
    }

    /** Tool 定义草稿。 */
    public record PlatformToolSpec(
            @NonNull String toolId,
            @Nullable String name,
            @NonNull String kind,
            @NonNull String riskLevel,
            @Nullable String mcpServerId,
            @Nullable String mcpToolName,
            @Nullable String inputSchema,
            @Nullable String paramPolicies) {
    }

    /** Skill 定义草稿。 */
    public record PlatformSkillSpec(
            @NonNull String skillId,
            @Nullable String name,
            @Nullable String description,
            @Nullable List<String> declaredTools,
            @Nullable List<PlatformMcpDeclarationSpec> declaredMcp,
            @NonNull String riskCeiling,
            @Nullable String paramPolicies,
            @Nullable List<String> requiredCaps) {
    }

    /** Skill 对 MCP server 的声明草稿。 */
    public record PlatformMcpDeclarationSpec(
            @NonNull String serverId,
            @Nullable List<String> toolAllowlist) {
    }

    /** MCP Server 定义草稿。 */
    public record PlatformMcpServerSpec(
            @NonNull String serverId,
            @NonNull String transport,
            @Nullable String endpoint,
            @Nullable String command,
            @NonNull String authKind,
            @NonNull String trustTier,
            @Nullable List<String> exposedToolAllowlist,
            @NonNull String connectionRisk,
            @Nullable String credentialRef) {
    }

    /** 平台级生命周期迁移申请体。 */
    public record PlatformLifecycleRequest(
            @NonNull String type,
            @NonNull String objectId,
            int version,
            @NonNull String expectedFrom,
            @NonNull String to,
            @Nullable String reason) {
    }

    /** 审批决定体。 */
    public record PlatformDecisionRequest(
            @Nullable String decision,
            @Nullable String note) {
    }

    /** 平台级变更申请视图。 */
    public record RequestResponse(
            @NonNull String requestId,
            @NonNull String scope,
            @NonNull String operation,
            @NonNull String state,
            @NonNull String requesterId,
            @Nullable String approverId,
            @Nullable String decisionNote,
            @Nullable String failureReason,
            @NonNull Instant requestedAt,
            @Nullable Instant decidedAt,
            @Nullable Instant appliedAt) {
        static RequestResponse of(ConfigPublishRequest request) {
            return new RequestResponse(
                    request.requestId(), request.scope().name(), request.operation().name(),
                    request.state().name(), request.requesterId(), request.approverId(),
                    request.decisionNote(), request.failureReason(),
                    request.requestedAt(), request.decidedAt(), request.appliedAt());
        }
    }

    /** 通用分页视图。 */
    public record PageResponse<T>(
            @NonNull List<T> items,
            @Nullable String nextCursor) {
        static <S, T> PageResponse<T> of(Page<S> page, java.util.function.Function<S, T> mapper) {
            return new PageResponse<>(page.items().stream().map(mapper).toList(), page.nextCursor());
        }
    }
}
