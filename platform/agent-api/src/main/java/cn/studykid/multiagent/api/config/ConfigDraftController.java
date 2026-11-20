package cn.studykid.multiagent.api.config;

import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import cn.studykid.multiagent.api.governance.GovernanceSupport;
import cn.studykid.multiagent.api.run.RunController.CurrentPrincipal;
import cn.studykid.multiagent.application.cap.ConfigDraftService;
import cn.studykid.multiagent.core.cap.ConfigDraft;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.Page;
import cn.studykid.multiagent.ports.security.AuthorizationService;

/**
 * 变更草稿端点（/api/cap/drafts）。
 *
 * <p>承载"编辑 → 提交审核/发布"工作流，双轨独立状态标签里的
 * "变更工作流状态"即来自这里的 {@code draft_state}。
 */
@RestController
@RequestMapping("/api/cap/drafts")
public class ConfigDraftController {

    private final ConfigDraftService drafts;
    private final AuthorizationService authorization;
    private final CurrentPrincipal currentPrincipal;

    public ConfigDraftController(@NonNull ConfigDraftService drafts,
                                  @NonNull AuthorizationService authorization,
                                  @NonNull CurrentPrincipal currentPrincipal) {
        this.drafts = drafts;
        this.authorization = authorization;
        this.currentPrincipal = currentPrincipal;
    }

    // ── 写 ────────────────────────────────────────────────────────

    /** 创建新草稿。 */
    @PostMapping
    public ResponseEntity<DraftResponse> create(@RequestBody DraftCreateRequest request) {
        TenantContext ctx = currentPrincipal.context();
        authorization.requireAuthorized(ctx, AuthorizationService.Actions.CONFIG_PUBLISH, null);
        try {
            ConfigDraft draft = drafts.create(
                    scope(request.scope()), ctx.tenantId(),
                    request.resourceKind(), request.resourceId(), request.resourceName(),
                    request.baseVersion() == null ? 0 : request.baseVersion(),
                    request.draftConfig(), currentPrincipal.principalId()
            );
            return ResponseEntity.ok(DraftResponse.of(draft));
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /** 保存草稿内容（多次编辑递增 draft_version）。 */
    @PutMapping("/{draftId}")
    public ResponseEntity<DraftResponse> save(@PathVariable String draftId,
                                               @RequestBody DraftSaveRequest request) {
        TenantContext ctx = currentPrincipal.context();
        authorization.requireAuthorized(ctx, AuthorizationService.Actions.CONFIG_PUBLISH, null);
        try {
            ConfigDraft saved = drafts.save(
                    scope(request.scope()), ctx.tenantId(),
                    UUID.fromString(draftId), request.draftConfig(),
                    currentPrincipal.principalId()
            );
            return ResponseEntity.ok(DraftResponse.of(saved));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    /** 提交审核（策略 APPROVAL_* 模式）。 */
    @PostMapping("/{draftId}/submit-approval")
    public ResponseEntity<DraftResponse> submitApproval(@PathVariable String draftId,
                                                         @Nullable @RequestParam String reason) {
        TenantContext ctx = currentPrincipal.context();
        authorization.requireAuthorized(ctx, AuthorizationService.Actions.CONFIG_PUBLISH, null);
        try {
            ConfigDraft after = drafts.submitApproval(
                    "TENANT", ctx.tenantId(), UUID.fromString(draftId),
                    currentPrincipal.principalId(), reason);
            return ResponseEntity.ok(DraftResponse.of(after));
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    /** 审批通过（通常由外部审批工作流调用）。 */
    @PostMapping("/{draftId}/approve")
    public ResponseEntity<DraftResponse> approve(@PathVariable String draftId,
                                                   @Nullable @RequestParam String reason) {
        TenantContext ctx = currentPrincipal.context();
        authorization.requireAuthorized(ctx, AuthorizationService.Actions.CONFIG_PUBLISH, null);
        try {
            ConfigDraft after = drafts.approve(
                    "TENANT", ctx.tenantId(), UUID.fromString(draftId),
                    currentPrincipal.principalId(), reason);
            return ResponseEntity.ok(DraftResponse.of(after));
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    /** 审批驳回。 */
    @PostMapping("/{draftId}/reject")
    public ResponseEntity<DraftResponse> reject(@PathVariable String draftId,
                                                  @RequestParam String reason) {
        TenantContext ctx = currentPrincipal.context();
        authorization.requireAuthorized(ctx, AuthorizationService.Actions.CONFIG_PUBLISH, null);
        try {
            ConfigDraft after = drafts.reject(
                    "TENANT", ctx.tenantId(), UUID.fromString(draftId),
                    currentPrincipal.principalId(), reason);
            return ResponseEntity.ok(DraftResponse.of(after));
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    /** 手动发布（MANUAL / PENDING_PUBLISH / PUBLISH_FAILED 状态下）。 */
    @PostMapping("/{draftId}/publish")
    public ResponseEntity<DraftResponse> publish(@PathVariable String draftId) {
        TenantContext ctx = currentPrincipal.context();
        authorization.requireAuthorized(ctx, AuthorizationService.Actions.CONFIG_PUBLISH, null);
        try {
            ConfigDraft after = drafts.publish(
                    "TENANT", ctx.tenantId(), UUID.fromString(draftId),
                    currentPrincipal.principalId());
            return ResponseEntity.ok(DraftResponse.of(after));
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    /** 放弃草稿（终态 DISCARDED）。 */
    @PostMapping("/{draftId}/discard")
    public ResponseEntity<DraftResponse> discard(@PathVariable String draftId,
                                                  @Nullable @RequestParam String reason) {
        TenantContext ctx = currentPrincipal.context();
        authorization.requireAuthorized(ctx, AuthorizationService.Actions.CONFIG_PUBLISH, null);
        try {
            ConfigDraft after = drafts.discard(
                    "TENANT", ctx.tenantId(), UUID.fromString(draftId),
                    currentPrincipal.principalId(), reason);
            return ResponseEntity.ok(DraftResponse.of(after));
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    /** 发布失败后重发。 */
    @PostMapping("/{draftId}/retry")
    public ResponseEntity<DraftResponse> retry(@PathVariable String draftId) {
        TenantContext ctx = currentPrincipal.context();
        authorization.requireAuthorized(ctx, AuthorizationService.Actions.CONFIG_PUBLISH, null);
        try {
            ConfigDraft after = drafts.retryPublish(
                    "TENANT", ctx.tenantId(), UUID.fromString(draftId),
                    currentPrincipal.principalId());
            return ResponseEntity.ok(DraftResponse.of(after));
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    // ── 读 ────────────────────────────────────────────────────────

    /** 按资源查询当前活跃草稿。 */
    @GetMapping("/active")
    public ResponseEntity<DraftResponse> findActiveByResource(
            @RequestParam String resourceKind,
            @RequestParam String resourceId) {
        TenantContext ctx = currentPrincipal.context();
        authorization.requireAuthorized(ctx, AuthorizationService.Actions.CONFIG_PUBLISH, null);
        return drafts.findActiveByResource("TENANT", ctx.tenantId(), resourceKind, resourceId)
                .map(d -> ResponseEntity.ok(DraftResponse.of(d)))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "无活跃草稿"));
    }

    /** 草稿列表（可按 state 过滤，游标分页）。 */
    @GetMapping
    public ResponseEntity<GovernanceSupport.PageResponse<DraftResponse>> list(
            @Nullable @RequestParam String state,
            @Nullable @RequestParam String cursor,
            @Nullable @RequestParam Integer limit) {
        TenantContext ctx = currentPrincipal.context();
        authorization.requireAuthorized(ctx, AuthorizationService.Actions.CONFIG_PUBLISH, null);
        Page<ConfigDraft> page = drafts.list("TENANT", ctx.tenantId(), state, cursor,
                GovernanceSupport.limit(limit));
        return ResponseEntity.ok(GovernanceSupport.PageResponse.of(
                page.items().stream().map(DraftResponse::of).toList(), page.nextCursor()));
    }

    // ── 内部 ──────────────────────────────────────────────────────

    private static String scope(@Nullable String scope) {
        return (scope == null || scope.isBlank()) ? "TENANT" : scope.trim().toUpperCase(java.util.Locale.ROOT);
    }

    public record DraftCreateRequest(
            @Nullable String scope,
            @NonNull String resourceKind,
            @NonNull String resourceId,
            @Nullable String resourceName,
            @Nullable Integer baseVersion,
            @NonNull String draftConfig
    ) {}

    public record DraftSaveRequest(
            @Nullable String scope,
            @NonNull String draftConfig
    ) {}

    public record DraftResponse(
            String draftId, String scope, String tenantId,
            String resourceKind, String resourceId, String resourceName,
            int baseVersion, int draftVersion, String draftState,
            String failureStage, String failureDetails,
            String rejectionReason, String operatorId,
            String createdAt, String updatedAt
    ) {
        public static DraftResponse of(@NonNull ConfigDraft d) {
            return new DraftResponse(
                    d.draftId().toString(), d.scope(), d.tenantId(),
                    d.resourceKind(), d.resourceId(), d.resourceName(),
                    d.baseVersion(), d.draftVersion(), d.draftState(),
                    d.failureStage(), d.failureDetails(),
                    d.rejectionReason(), d.operatorId(),
                    d.createdAt().toString(), d.updatedAt().toString()
            );
        }
    }
}
