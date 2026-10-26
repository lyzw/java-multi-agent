package cn.studykid.multiagent.api.admin;

import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

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
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.core.tenant.TenantRegistry.PrincipalRow;
import cn.studykid.multiagent.core.tenant.TenantRegistry.RlsVerifyResult;
import cn.studykid.multiagent.core.tenant.TenantRegistry.TenantRow;
import cn.studykid.multiagent.ports.persistence.AdminTenantPort;
import cn.studykid.multiagent.ports.persistence.RateLimitPort;

/**
 * 租户管理端点（管理控制台 P0.5 · M0）。
 *
 * <p><b>两类端点，两种作用域</b>：
 * <ul>
 *   <li><b>租户内（/self）</b>：读取<b>当前租户</b>的概要/主体，任何已认证租户主体可用；
 *       数据经 RLS 严格限定在调用方所属租户，无越权面。</li>
 *   <li><b>平台级（/ 、/{id} 等）</b>：跨租户的租户注册表、开通、启停、主体治理、RLS 校验。
 *       <b>须经 PLATFORM_ADMIN 作用域闸门</b>——当前平台运维角色由带外通道授予，
 *       应用层租户主体默认不持有该角色，故这些端点<b>默认返回 403</b>（默认拒绝，非默认放行）。</li>
 * </ul>
 *
 * <p>⚠️ <b>tenant 表为全局表、无 RLS</b>：平台级写（开通/启停）在数据库层无需 BYPASSRLS，
 * 安全性完全由本控制器的 PLATFORM_ADMIN 闸门保证。闸门缺失即等于向任意租户管理员
 * 开放跨租户写入口——故闸门在所有平台级端点的最前面，任何业务逻辑之前先判角色。
 *
 * <p>⚠️ <b>端点默认受 {@code anyRequest().authenticated()} 保护</b>
 * （见 SecurityConfiguration）：漏配 PUBLIC_ENDPOINTS 时是拒绝而非放行。
 */
/**
 * ⚠️ <b>已弃用（P4）</b>：本控制器基于「租户令牌持 {@code PLATFORM_ADMIN} 角色」
 * 的单一身份模型，与重规划「平台身份 / 租户身份分离」冲突。
 * 平台级租户管理请迁移至 {@code /api/platform/v1/tenants}
 * （独立平台令牌 {@code aud=admin-console-api} + {@code PlatformSecurity} 授权闸门）。
 * 在本端点下线前仍保留可用，以避免影响既有 admin-console 前端，
 * 但<b>新功能不应再接入此路径</b>。
 *
 * @deprecated 迁移至 {@code /api/platform/v1/tenants}（平台身份域）。
 */
@Deprecated(since = "P4", forRemoval = false)
@RestController
@RequestMapping("/api/admin/tenants")
public class AdminTenantController {

    /** 租户 slug 格式（与 V1__baseline_schema 的 CHECK 约束一致）。 */
    private static final Pattern SLUG_PATTERN =
            Pattern.compile("^[a-z0-9][a-z0-9-]{1,62}$");

    /** 允许的租户生命周期状态。 */
    private static final java.util.Set<String> VALID_STATUS =
            java.util.Set.of("ACTIVE", "SUSPENDED", "DECOMMISSIONED");

    private final AdminTenantPort tenants;
    private final CurrentPrincipal currentPrincipal;
    private final RateLimitPort rateLimiter;

    public AdminTenantController(AdminTenantPort tenants,
                                 CurrentPrincipal currentPrincipal,
                                 RateLimitPort rateLimiter) {
        this.tenants = tenants;
        this.currentPrincipal = currentPrincipal;
        this.rateLimiter = rateLimiter;
    }

    // ── 租户内（当前租户，已认证即可） ──────────────────────

    /**
     * 当前租户概要（含主体/Agent/覆盖计数）。
     *
     * <p>⚠️ 计数与概要通过绑定<b>调用方所属租户</b>的连接读取，受 RLS 约束，
     * 不暴露其他租户数据。
     */
    @GetMapping("/self")
    public ResponseEntity<SelfSummary> selfSummary() {
        TenantContext context = currentPrincipal.context();
        rateLimit(context);
        TenantRow row = tenants.findTenant(context.tenantId());
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "租户不存在");
        }
        return ResponseEntity.ok(new SelfSummary(
                TenantResponse.of(row),
                tenants.countPrincipals(context.tenantId()),
                tenants.countAgentDefs(context.tenantId()),
                tenants.countOverrides(context.tenantId())));
    }

    /** 当前租户的主体及其角色。 */
    @GetMapping("/self/principals")
    public ResponseEntity<List<PrincipalResponse>> selfPrincipals() {
        TenantContext context = currentPrincipal.context();
        rateLimit(context);
        return ResponseEntity.ok(tenants.listPrincipals(context.tenantId()).stream()
                .map(PrincipalResponse::of).toList());
    }

    // ── 平台级（PLATFORM_ADMIN 闸门） ──────────────────────

    /** 租户注册表（全部租户，游标分页）。 */
    @GetMapping
    public ResponseEntity<GovernanceSupport.PageResponse<TenantResponse>> list(
            @RequestParam(required = false) @Nullable String status,
            @RequestParam(required = false) @Nullable Integer limit,
            @RequestParam(required = false) @Nullable String cursor) {
        requirePlatformAdmin();
        TenantContext context = currentPrincipal.context();
        rateLimit(context);
        int lim = limit == null ? 50 : Math.max(1, Math.min(limit, 200));
        List<TenantRow> rows = tenants.listTenants(status, lim);
        boolean hasMore = rows.size() > lim;
        if (hasMore) {
            rows = rows.subList(0, lim);
        }
        String nextCursor = hasMore && !rows.isEmpty()
                ? rows.get(rows.size() - 1).tenantId() : null;
        return ResponseEntity.ok(GovernanceSupport.PageResponse.of(
                rows.stream().map(TenantResponse::of).toList(), nextCursor));
    }

    /** 开通新租户。 */
    @PostMapping
    public ResponseEntity<TenantResponse> provision(@RequestBody ProvisionRequest request) {
        requirePlatformAdmin();
        TenantContext context = currentPrincipal.context();
        rateLimit(context);
        if (request == null || isBlank(request.slug()) || isBlank(request.name())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "slug 与 name 必填");
        }
        if (!SLUG_PATTERN.matcher(request.slug().trim()).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "slug 格式非法：须为 2-63 位小写字母数字或连字符，且不以连字符开头/结尾");
        }
        try {
            TenantRow row = tenants.provisionTenant(request.slug().trim(), request.name().trim());
            return ResponseEntity.status(HttpStatus.CREATED).body(TenantResponse.of(row));
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    /** 租户详情（含计数与主体列表）。 */
    @GetMapping("/{id}")
    public ResponseEntity<TenantDetailResponse> detail(@PathVariable String id) {
        requirePlatformAdmin();
        TenantContext context = currentPrincipal.context();
        rateLimit(context);
        TenantRow row = tenants.findTenant(id);
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "租户不存在");
        }
        return ResponseEntity.ok(new TenantDetailResponse(
                TenantResponse.of(row),
                tenants.countPrincipals(id),
                tenants.countAgentDefs(id),
                tenants.countOverrides(id),
                tenants.listPrincipals(id).stream().map(PrincipalResponse::of).toList()));
    }

    /** 租户生命周期：置状态。 */
    @PutMapping("/{id}/status")
    public ResponseEntity<TenantResponse> updateStatus(@PathVariable String id,
                                                      @RequestBody StatusRequest request) {
        requirePlatformAdmin();
        TenantContext context = currentPrincipal.context();
        rateLimit(context);
        String status = request == null || request.status() == null
                ? null : request.status().trim().toUpperCase(java.util.Locale.ROOT);
        if (status == null || !VALID_STATUS.contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "status 仅接受 ACTIVE / SUSPENDED / DECOMMISSIONED");
        }
        TenantRow row = tenants.updateStatus(id, status);
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "租户不存在");
        }
        return ResponseEntity.ok(TenantResponse.of(row));
    }

    /** 跨租户列出某租户的主体及其角色。 */
    @GetMapping("/{id}/principals")
    public ResponseEntity<List<PrincipalResponse>> principals(@PathVariable String id) {
        requirePlatformAdmin();
        TenantContext context = currentPrincipal.context();
        rateLimit(context);
        if (tenants.findTenant(id) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "租户不存在");
        }
        return ResponseEntity.ok(tenants.listPrincipals(id).stream()
                .map(PrincipalResponse::of).toList());
    }

    /**
     * RLS 隔离校验（P0.5 · 5.4）。
     *
     * <p>⚠️ 以目标租户身份（非特权 app_agent，NOBYPASSRLS）绑定连接后断言：
     * 会话上下文已绑定为目标租户，且无法读取其他租户的主体数据。
     * 若隔离被破坏（leakedRows &gt; 0），返回 ok=false，前端须显式告警。
     */
    @PostMapping("/{id}/rls-verify")
    public ResponseEntity<RlsVerifyResponse> rlsVerify(@PathVariable String id) {
        requirePlatformAdmin();
        TenantContext context = currentPrincipal.context();
        rateLimit(context);
        if (tenants.findTenant(id) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "租户不存在");
        }
        RlsVerifyResult result = tenants.verifyIsolation(id);
        boolean ok = result.contextBound() && result.isolated();
        return ResponseEntity.ok(new RlsVerifyResponse(
                result.contextBound(), result.isolated(), result.leakedRows(),
                result.boundTenantId(), ok));
    }

    // ── 内部 ───────────────────────────────────────────────

    /** 平台级作用域闸门：非 PLATFORM_ADMIN 一律 403（默认拒绝）。 */
    private void requirePlatformAdmin() {
        TenantContext context = currentPrincipal.context();
        if (!context.hasRole("PLATFORM_ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "平台级租户管理仅限 PLATFORM_ADMIN（须经平台运维通道授权）");
        }
    }

    private void rateLimit(TenantContext context) {
        if (GovernanceSupport.rateLimited(rateLimiter, context,
                RateLimitPort.Buckets.GOVERNANCE_QUERY)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "请求过于频繁，请稍后重试");
        }
    }

    private static boolean isBlank(@Nullable String s) {
        return s == null || s.isBlank();
    }

    /** 租户响应。 */
    public record TenantResponse(@NonNull String tenantId, @NonNull String slug,
                                 @NonNull String name, @NonNull String status,
                                 @Nullable String createdAt, @Nullable String updatedAt) {
        static TenantResponse of(TenantRow row) {
            return new TenantResponse(row.tenantId(), row.slug(), row.name(),
                    row.status(), iso(row.createdAt()), iso(row.updatedAt()));
        }
    }

    /** 租户详情响应。 */
    public record TenantDetailResponse(@NonNull TenantResponse tenant, long principalsCount,
                                       long agentsCount, long overridesCount,
                                       @NonNull List<PrincipalResponse> principals) {
    }

    /** 当前租户概要响应。 */
    public record SelfSummary(@NonNull TenantResponse tenant, long principalsCount,
                              long agentsCount, long overridesCount) {
    }

    /** 主体响应（不含任何凭据）。 */
    public record PrincipalResponse(@NonNull String principalId, @NonNull String loginName,
                                    @Nullable String displayName, @NonNull String status,
                                    long authzVersion, @NonNull List<String> roles) {
        static PrincipalResponse of(PrincipalRow row) {
            return new PrincipalResponse(row.principalId(), row.loginName(),
                    row.displayName(), row.status(), row.authzVersion(), row.roles());
        }
    }

    /** 开通请求。 */
    public record ProvisionRequest(@Nullable String slug, @Nullable String name) {
    }

    /** 状态变更请求。 */
    public record StatusRequest(@Nullable String status) {
    }

    /** RLS 校验响应。 */
    public record RlsVerifyResponse(boolean contextBound, boolean isolated,
                                    long leakedRows, @Nullable String boundTenantId,
                                    boolean ok) {
    }

    private static String iso(@Nullable Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
