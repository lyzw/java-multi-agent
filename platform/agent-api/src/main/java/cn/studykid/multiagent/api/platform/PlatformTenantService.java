package cn.studykid.multiagent.api.platform;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import cn.studykid.multiagent.api.security.PlatformSecurity;
import cn.studykid.multiagent.ports.audit.PlatformAuditPort;
import cn.studykid.multiagent.ports.audit.PlatformAuditPort.PlatformAuditEvent;
import cn.studykid.multiagent.ports.persistence.PlatformTenantPort;
import cn.studykid.multiagent.ports.persistence.PlatformTenantPort.CapabilityGrantRow;
import cn.studykid.multiagent.ports.persistence.PlatformTenantPort.PlatformTenantRow;
import cn.studykid.multiagent.ports.persistence.PlatformTenantPort.QuotaRow;
import cn.studykid.multiagent.ports.security.Namespaces;
import cn.studykid.multiagent.ports.security.PlatformPrincipal;
import cn.studykid.multiagent.ports.tenant.TenantLifecycle;

/**
 * 平台租户管理服务（重规划 §6 / §8 / §9）。
 *
 * <p><b>授权在执行入口强制</b>：每个写操作先经 {@link PlatformSecurity#require}
 * （默认拒绝、矩阵驱动），再调仓储，最后写 {@code platform_audit_event}。
 * 控制台有权限不代表绕过本服务的授权闸门。
 *
 * <p>⚠️ <b>创建租户不自动建租户管理员</b>；生命周期迁移须通过状态机校验，
 * 非法迁移返回 409（重规划 §6.1 / §6.3）。
 */
@Component
public class PlatformTenantService {

    private final PlatformTenantPort tenants;
    private final PlatformSecurity security;
    private final PlatformAuditPort audit;

    public PlatformTenantService(PlatformTenantPort tenants,
                                 PlatformSecurity security,
                                 PlatformAuditPort audit) {
        this.tenants = tenants;
        this.security = security;
        this.audit = audit;
    }

    // ── 读 ─────────────────────────────────────────────────

    public List<PlatformTenantRow> listTenants(@NonNull PlatformPrincipal principal,
                                               @Nullable String statusFilter, int limit) {
        security.require(principal, Namespaces.Platform.TENANT_READ, null, null);
        return tenants.listTenants(statusFilter, limit);
    }

    public PlatformTenantRow getTenant(@NonNull PlatformPrincipal principal, @NonNull String id) {
        security.require(principal, Namespaces.Platform.TENANT_READ, "tenant", id);
        PlatformTenantRow row = tenants.findTenant(id);
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "租户不存在");
        }
        return row;
    }

    public QuotaRow getQuota(@NonNull PlatformPrincipal principal, @NonNull String id) {
        security.require(principal, Namespaces.Platform.TENANT_QUOTA_READ, "tenant", id);
        requireTenant(id);
        QuotaRow quota = tenants.findQuota(id);
        if (quota == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "租户配额不存在");
        }
        return quota;
    }

    public List<CapabilityGrantRow> listCapabilities(@NonNull PlatformPrincipal principal,
                                                     @NonNull String id) {
        security.require(principal, Namespaces.Platform.TENANT_CAPABILITY_READ, "tenant", id);
        requireTenant(id);
        return tenants.listCapabilities(id);
    }

    // ── 写 ─────────────────────────────────────────────────

    public PlatformTenantRow createTenant(@NonNull PlatformPrincipal principal,
                                          @NonNull String slug, @NonNull String name,
                                          @Nullable String plan) {
        security.require(principal, Namespaces.Platform.TENANT_CREATE, "tenant", null);
        PlatformTenantRow row = tenants.provisionTenant(slug, name, plan);
        record(principal, Namespaces.Platform.TENANT_CREATE, "tenant", row.tenantId(), "ALLOW", null);
        return row;
    }

    public PlatformTenantRow suspendTenant(@NonNull PlatformPrincipal principal, @NonNull String id) {
        security.require(principal, Namespaces.Platform.TENANT_SUSPEND, "tenant", id);
        PlatformTenantRow row = transition(principal, id, TenantLifecycle.SUSPENDED,
                Namespaces.Platform.TENANT_SUSPEND);
        record(principal, Namespaces.Platform.TENANT_SUSPEND, "tenant", id, "ALLOW", null);
        return row;
    }

    public PlatformTenantRow resumeTenant(@NonNull PlatformPrincipal principal, @NonNull String id) {
        security.require(principal, Namespaces.Platform.TENANT_RESUME, "tenant", id);
        PlatformTenantRow row = transition(principal, id, TenantLifecycle.ACTIVE,
                Namespaces.Platform.TENANT_RESUME);
        record(principal, Namespaces.Platform.TENANT_RESUME, "tenant", id, "ALLOW", null);
        return row;
    }

    public PlatformTenantRow decommissionTenant(@NonNull PlatformPrincipal principal,
                                               @NonNull String id) {
        security.require(principal, Namespaces.Platform.TENANT_DECOMMISSION, "tenant", id);
        PlatformTenantRow row = transition(principal, id, TenantLifecycle.DECOMMISSIONING,
                Namespaces.Platform.TENANT_DECOMMISSION);
        record(principal, Namespaces.Platform.TENANT_DECOMMISSION, "tenant", id, "ALLOW", null);
        return row;
    }

    public QuotaRow updateQuota(@NonNull PlatformPrincipal principal, @NonNull String id,
                               int agentLimit, int runConcurrencyLimit,
                               @Nullable BigDecimal budgetLimit) {
        security.require(principal, Namespaces.Platform.TENANT_QUOTA_UPDATE, "tenant", id);
        requireTenant(id);
        QuotaRow row = tenants.updateQuota(id,
                new QuotaRow(agentLimit, runConcurrencyLimit, budgetLimit));
        record(principal, Namespaces.Platform.TENANT_QUOTA_UPDATE, "tenant", id, "ALLOW", null);
        return row;
    }

    public CapabilityGrantRow grantCapability(@NonNull PlatformPrincipal principal,
                                             @NonNull String id, @NonNull String resourceCode,
                                             @NonNull String scope) {
        security.require(principal, Namespaces.Platform.TENANT_CAPABILITY_GRANT, "tenant", id);
        requireTenant(id);
        CapabilityGrantRow row = tenants.grantCapability(id, resourceCode, scope);
        record(principal, Namespaces.Platform.TENANT_CAPABILITY_GRANT, "tenant", id, "ALLOW", null);
        return row;
    }

    public void revokeCapability(@NonNull PlatformPrincipal principal, @NonNull String id,
                                @NonNull String resourceCode) {
        security.require(principal, Namespaces.Platform.TENANT_CAPABILITY_REVOKE, "tenant", id);
        requireTenant(id);
        boolean ok = tenants.revokeCapability(id, resourceCode);
        if (!ok) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "能力授权不存在或已撤销");
        }
        record(principal, Namespaces.Platform.TENANT_CAPABILITY_REVOKE, "tenant", id, "ALLOW", null);
    }

    // ── 内部 ───────────────────────────────────────────────

    private PlatformTenantRow transition(PlatformPrincipal principal, String id, String target,
                                        String action) {
        PlatformTenantRow current = tenants.findTenant(id);
        if (current == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "租户不存在");
        }
        if (!TenantLifecycle.canTransition(current.lifecycleState(), target)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "非法状态迁移：" + current.lifecycleState() + " → " + target);
        }
        PlatformTenantRow row = tenants.transitionLifecycle(id, target);
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "租户不存在");
        }
        return row;
    }

    private void requireTenant(@NonNull String id) {
        if (tenants.findTenant(id) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "租户不存在");
        }
    }

    private void record(PlatformPrincipal principal, String action, String targetType,
                       String targetId, String decision, String reason) {
        audit.record(new PlatformAuditEvent(
                UUID.randomUUID(), principal.userId(), "PLATFORM_USER",
                action, targetType, targetId, decision, reason, null, null, Instant.now()));
    }
}
