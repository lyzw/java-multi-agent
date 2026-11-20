package cn.studykid.multiagent.api.config;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import cn.studykid.multiagent.application.cap.PublishPolicyService;
import cn.studykid.multiagent.core.cap.PublishPolicy;
import cn.studykid.multiagent.core.cap.PublishPolicy.PublishMode;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.security.AuthorizationService;

/**
 * 发布策略配置端点（/api/cap/publish-policies）。
 *
 * <p>CRUD + 优先级查询（resolve）：前端 Agent/Skill/Tool 编辑页在提交前可先
 * 查一下当前生效策略，决定要不要先建审批单、要不要直接"提交发布"。
 */
@RestController
@RequestMapping("/api/cap/publish-policies")
public class PublishPolicyController {

    private final PublishPolicyService service;
    private final AuthorizationService authorization;
    private final CurrentPrincipal currentPrincipal;

    public PublishPolicyController(@NonNull PublishPolicyService service,
                                    @NonNull AuthorizationService authorization,
                                    @NonNull CurrentPrincipal currentPrincipal) {
        this.service = service;
        this.authorization = authorization;
        this.currentPrincipal = currentPrincipal;
    }

    // ── 写 ────────────────────────────────────────────────────────

    @PostMapping
    public ResponseEntity<PolicyResponse> create(@RequestBody PolicyRequest request) {
        TenantContext ctx = currentPrincipal.context();
        authorization.requireAuthorized(ctx, AuthorizationService.Actions.CONFIG_PUBLISH, null);
        try {
            String resolvedScope = scope(request.scope());
            // ⚠️ 归一化 tenantId：PLATFORM scope 强制 null（core record 要求），
            //    TENANT scope 走 ctx.tenantId()。避免平台身份的哨兵租户泄漏进 UUID 解析链。
            String resolvedTenantId = normalizeTenantId(resolvedScope, ctx);
            PublishPolicy policy = new PublishPolicy(
                    UUID.randomUUID(),
                    resolvedScope,
                    resolvedTenantId,
                    request.policyLevel(),
                    request.resourceKind(),
                    request.resourceId(),
                    request.publishMode(),
                    request.enabled() == null || request.enabled(),
                    currentPrincipal.principalId(),
                    null, null
            );
            return ResponseEntity.ok(PolicyResponse.of(service.create(policy)));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PutMapping("/{policyId}")
    public ResponseEntity<PolicyResponse> update(@PathVariable String policyId,
                                                  @RequestBody PolicyRequest request) {
        TenantContext ctx = currentPrincipal.context();
        authorization.requireAuthorized(ctx, AuthorizationService.Actions.CONFIG_PUBLISH, null);
        try {
            UUID id = UUID.fromString(policyId);
            String resolvedScope = scope(request.scope());
            String resolvedTenantId = normalizeTenantId(resolvedScope, ctx);
            PublishPolicy existing = service.findById(resolvedScope, resolvedTenantId, id)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "策略不存在"));
            PublishPolicy updated = new PublishPolicy(
                    id, existing.scope(), existing.tenantId(),
                    existing.policyLevel(), existing.resourceKind(), existing.resourceId(),
                    request.publishMode(),
                    request.enabled() == null || request.enabled(),
                    currentPrincipal.principalId(),
                    existing.createdAt(), existing.updatedAt()
            );
            Optional<PublishPolicy> result = service.update(updated);
            return result.map(p -> ResponseEntity.ok(PolicyResponse.of(p)))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "策略不存在"));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @DeleteMapping("/{policyId}")
    public ResponseEntity<Void> delete(@PathVariable String policyId,
                                        @Nullable @RequestParam String scope) {
        TenantContext ctx = currentPrincipal.context();
        authorization.requireAuthorized(ctx, AuthorizationService.Actions.CONFIG_PUBLISH, null);
        UUID id = UUID.fromString(policyId);
        String resolvedScope = scope(scope);
        boolean deleted = service.delete(resolvedScope, normalizeTenantId(resolvedScope, ctx), id);
        if (!deleted) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "策略不存在");
        return ResponseEntity.noContent().build();
    }

    // ── 读 ────────────────────────────────────────────────────────

    @GetMapping("/{policyId}")
    public ResponseEntity<PolicyResponse> get(@PathVariable String policyId,
                                               @Nullable @RequestParam String scope) {
        TenantContext ctx = currentPrincipal.context();
        authorization.requireAuthorized(ctx, AuthorizationService.Actions.CONFIG_PUBLISH, null);
        UUID id = UUID.fromString(policyId);
        String resolvedScope = scope(scope);
        return service.findById(resolvedScope, normalizeTenantId(resolvedScope, ctx), id)
                .map(p -> ResponseEntity.ok(PolicyResponse.of(p)))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "策略不存在"));
    }

    @GetMapping
    public ResponseEntity<List<PolicyResponse>> list(
            @Nullable @RequestParam String scope) {
        TenantContext ctx = currentPrincipal.context();
        authorization.requireAuthorized(ctx, AuthorizationService.Actions.CONFIG_PUBLISH, null);
        String resolvedScope = scope(scope);
        List<PublishPolicy> rows = service.listEnabled(resolvedScope, normalizeTenantId(resolvedScope, ctx));
        return ResponseEntity.ok(rows.stream().map(PolicyResponse::of).toList());
    }

    /**
     * 便捷端点：给定 (resourceKind, resourceId)，返回当前生效的发布模式。
     * 前端编辑页在提交前先查一下该端点，决定按钮是"保存草稿"还是"直接提交发布"。
     */
    @GetMapping("/resolve")
    public ResponseEntity<java.util.Map<String, String>> resolve(
            @RequestParam String resourceKind,
            @Nullable @RequestParam String resourceId,
            @Nullable @RequestParam String scope) {
        TenantContext ctx = currentPrincipal.context();
        authorization.requireAuthorized(ctx, AuthorizationService.Actions.CONFIG_PUBLISH, null);
        String resolvedScope = scope(scope);
        PublishMode mode = service.resolveMode(resolvedScope, normalizeTenantId(resolvedScope, ctx), resourceKind, resourceId);
        return ResponseEntity.ok(java.util.Map.of("publishMode", mode.name()));
    }

    // ── 内部 ──────────────────────────────────────────────────────

    private static String scope(@Nullable String scope) {
        return (scope == null || scope.isBlank()) ? "TENANT" : scope.trim().toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * 按 scope 归一化 tenantId：PLATFORM scope 强制 null，TENANT scope 返回 ctx.tenantId()。
     *
     * <p>平台身份合成上下文的 tenantId 可能是哨兵值，此处统一按 scope 判定，
     * 避免哨兵泄漏进 UUID 解析链导致下游 Repository 崩溃。
     */
    @Nullable
    private static String normalizeTenantId(@NonNull String resolvedScope, @NonNull TenantContext ctx) {
        if ("PLATFORM".equals(resolvedScope)) return null;
        return ctx.tenantId();
    }

    /** API 请求体。 */
    public record PolicyRequest(
            @Nullable String scope,
            @NonNull String policyLevel,      // GLOBAL / RESOURCE_TYPE / INSTANCE
            @Nullable String resourceKind,
            @Nullable String resourceId,
            @NonNull String publishMode,      // DIRECT / MANUAL / APPROVAL_MANUAL / APPROVAL_AUTO
            @Nullable Boolean enabled
    ) {}

    /** API 响应体。 */
    public record PolicyResponse(
            String policyId, String scope, String tenantId,
            String policyLevel, String resourceKind, String resourceId,
            String publishMode, boolean enabled, String updatedBy,
            String createdAt, String updatedAt
    ) {
        public static PolicyResponse of(@NonNull PublishPolicy p) {
            return new PolicyResponse(
                    p.policyId().toString(), p.scope(), p.tenantId(),
                    p.policyLevel(), p.resourceKind(), p.resourceId(),
                    p.publishMode(), p.enabled(), p.updatedBy(),
                    p.createdAt().toString(), p.updatedAt().toString()
            );
        }
    }
}
