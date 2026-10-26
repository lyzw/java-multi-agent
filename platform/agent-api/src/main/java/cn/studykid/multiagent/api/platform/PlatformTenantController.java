package cn.studykid.multiagent.api.platform;

import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import cn.studykid.multiagent.ports.persistence.PlatformTenantPort.CapabilityGrantRow;
import cn.studykid.multiagent.ports.persistence.PlatformTenantPort.PlatformTenantRow;
import cn.studykid.multiagent.ports.persistence.PlatformTenantPort.QuotaRow;
import cn.studykid.multiagent.ports.security.IdentityDomains;
import cn.studykid.multiagent.ports.security.PlatformPrincipal;

/**
 * 平台租户管理端点（重规划 §6 / §8 / §9）。
 *
 * <p><b>独立命名空间</b> {@code /api/platform/v1/tenants}，与租户业务 API 物理隔离；
 * 仅接受 {@code aud=admin-console-api} 的平台令牌（令牌层隔离见 SecurityConfiguration）。
 *
 * <p>每个端点先经 {@link PlatformTenantService} 的执行入口授权（默认拒绝），
 * 再落地数据并写平台审计。平台管理权<b>不</b>等于租户业务执行权——
 * 本控制器不修改任何租户私有 Agent/Skill/Tool/MCP 配置或业务数据。
 */
@RestController
@RequestMapping("/api/platform/v1/tenants")
public class PlatformTenantController {

    /** slug 格式（与 V1__baseline_schema 的 CHECK 约束一致）。 */
    private static final Pattern SLUG_PATTERN =
            Pattern.compile("^[a-z0-9][a-z0-9-]{1,62}$");

    private final PlatformTenantService service;

    public PlatformTenantController(PlatformTenantService service) {
        this.service = service;
    }

    /** 列出租户（平台级，游标无关简单分页）。 */
    @GetMapping
    public ResponseEntity<List<TenantSummary>> list(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @RequestParam(required = false) @Nullable String status,
            @RequestParam(required = false) @Nullable Integer limit) {
        int lim = limit == null ? 50 : Math.max(1, Math.min(limit, 200));
        List<TenantSummary> rows = service.listTenants(principal, status, lim).stream()
                .map(TenantSummary::of).toList();
        return ResponseEntity.ok(rows);
    }

    /** 开通新租户（进入 PENDING_SETUP；不自动建租户管理员）。 */
    @PostMapping
    public ResponseEntity<TenantSummary> create(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @RequestBody CreateRequest request) {
        if (request == null || isBlank(request.slug()) || isBlank(request.name())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "slug 与 name 必填");
        }
        String slug = request.slug().trim();
        if (!SLUG_PATTERN.matcher(slug).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "slug 格式非法：须为 2-63 位小写字母数字或连字符，且不以连字符开头/结尾");
        }
        try {
            PlatformTenantRow row = service.createTenant(principal, slug,
                    request.name().trim(), request.plan());
            return ResponseEntity.status(HttpStatus.CREATED).body(TenantSummary.of(row));
        } catch (IllegalStateException e) {
            // ⚠️ 必须留痕：把真实失败原因吞进泛化 409 会让运维无从排查（实测教训）
            org.slf4j.LoggerFactory.getLogger(PlatformTenantController.class)
                    .warn("开通租户被拒（409）: slug={}, 原因={}", slug, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    /** 租户详情。 */
    @GetMapping("/{id}")
    public ResponseEntity<TenantSummary> detail(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @PathVariable String id) {
        return ResponseEntity.ok(TenantSummary.of(service.getTenant(principal, id)));
    }

    /** 读取配额。 */
    @GetMapping("/{id}/quota")
    public ResponseEntity<QuotaSummary> quota(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @PathVariable String id) {
        QuotaRow q = service.getQuota(principal, id);
        return ResponseEntity.ok(new QuotaSummary(q.agentLimit(), q.runConcurrencyLimit(), q.budgetLimit()));
    }

    /** 更新配额。 */
    @PutMapping("/{id}/quota")
    public ResponseEntity<QuotaSummary> updateQuota(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @PathVariable String id,
            @RequestBody QuotaRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体必填");
        }
        QuotaRow q = service.updateQuota(principal, id, request.agentLimit(),
                request.runConcurrencyLimit(), request.budgetLimit());
        return ResponseEntity.ok(new QuotaSummary(q.agentLimit(), q.runConcurrencyLimit(), q.budgetLimit()));
    }

    /** 暂停租户（拒绝新业务、处置进行中任务）。 */
    @PostMapping("/{id}/suspend")
    public ResponseEntity<TenantSummary> suspend(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @PathVariable String id) {
        return ResponseEntity.ok(TenantSummary.of(service.suspendTenant(principal, id)));
    }

    /** 恢复租户。 */
    @PostMapping("/{id}/resume")
    public ResponseEntity<TenantSummary> resume(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @PathVariable String id) {
        return ResponseEntity.ok(TenantSummary.of(service.resumeTenant(principal, id)));
    }

    /** 退役租户（进入 DECOMMISSIONING）。 */
    @PostMapping("/{id}/decommission")
    public ResponseEntity<TenantSummary> decommission(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @PathVariable String id) {
        return ResponseEntity.ok(TenantSummary.of(service.decommissionTenant(principal, id)));
    }

    /** 列出租户能力授权。 */
    @GetMapping("/{id}/capabilities")
    public ResponseEntity<List<CapabilitySummary>> capabilities(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @PathVariable String id) {
        return ResponseEntity.ok(service.listCapabilities(principal, id).stream()
                .map(CapabilitySummary::of).toList());
    }

    /** 授予租户能力（平台资源进入租户可用范围；不替代租户内调用授权）。 */
    @PostMapping("/{id}/capabilities")
    public ResponseEntity<CapabilitySummary> grant(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @PathVariable String id,
            @RequestBody CapabilityRequest request) {
        if (request == null || isBlank(request.resourceCode()) || isBlank(request.scope())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "resourceCode 与 scope 必填");
        }
        CapabilityGrantRow row = service.grantCapability(principal, id,
                request.resourceCode().trim(), request.scope().trim().toUpperCase(java.util.Locale.ROOT));
        return ResponseEntity.status(HttpStatus.CREATED).body(CapabilitySummary.of(row));
    }

    /** 撤销租户能力。 */
    @DeleteMapping("/{id}/capabilities/{code}")
    public ResponseEntity<Void> revoke(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @PathVariable String id,
            @PathVariable String code) {
        service.revokeCapability(principal, id, code.trim());
        return ResponseEntity.noContent().build();
    }

    // ── 响应 / 请求记录 ────────────────────────────────────

    public record TenantSummary(String tenantId, String slug, String name,
                               String lifecycleState, String plan,
                               long effectivePolicyVersion, long tenantAuthzVersion) {
        static TenantSummary of(PlatformTenantRow r) {
            return new TenantSummary(r.tenantId(), r.slug(), r.name(),
                    r.lifecycleState(), r.plan(), r.effectivePolicyVersion(),
                    r.tenantAuthzVersion());
        }
    }

    public record QuotaSummary(int agentLimit, int runConcurrencyLimit, @Nullable BigDecimal budgetLimit) {
    }

    public record CapabilitySummary(String id, String tenantId, String resourceCode,
                                   String scope, String status, long version) {
        static CapabilitySummary of(CapabilityGrantRow r) {
            return new CapabilitySummary(r.id(), r.tenantId(), r.resourceCode(),
                    r.scope(), r.status(), r.version());
        }
    }

    public record CreateRequest(@Nullable String slug, @Nullable String name, @Nullable String plan) {
    }

    public record QuotaRequest(int agentLimit, int runConcurrencyLimit, @Nullable BigDecimal budgetLimit) {
    }

    public record CapabilityRequest(@Nullable String resourceCode, @Nullable String scope) {
    }

    private static boolean isBlank(@Nullable String s) {
        return s == null || s.isBlank();
    }
}
