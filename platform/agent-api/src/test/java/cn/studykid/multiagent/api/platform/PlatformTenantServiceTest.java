package cn.studykid.multiagent.api.platform;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import cn.studykid.multiagent.api.security.PlatformSecurity;
import cn.studykid.multiagent.infrastructure.security.PlatformAuthorizationServiceImpl;
import cn.studykid.multiagent.ports.audit.PlatformAuditPort;
import cn.studykid.multiagent.ports.audit.PlatformAuditPort.PlatformAuditEvent;
import cn.studykid.multiagent.ports.persistence.PlatformTenantPort;
import cn.studykid.multiagent.ports.persistence.PlatformTenantPort.CapabilityGrantRow;
import cn.studykid.multiagent.ports.persistence.PlatformTenantPort.PlatformTenantRow;
import cn.studykid.multiagent.ports.persistence.PlatformTenantPort.QuotaRow;
import cn.studykid.multiagent.ports.security.AccessDeniedException;
import cn.studykid.multiagent.ports.security.PlatformPrincipal;
import cn.studykid.multiagent.ports.security.PlatformRoles;
import cn.studykid.multiagent.ports.tenant.TenantLifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 平台租户管理服务单元测试（重规划 §6 / §8 / §9）。
 *
 * <p>用内存假实现驱动：验证<b>授权在执行入口强制</b>、<b>生命周期状态机</b>与
 * <b>审计落盘</b>三件事，不依赖数据库。
 */
class PlatformTenantServiceTest {

    private static PlatformPrincipal superAdmin() {
        return new PlatformPrincipal("u-super", Set.of(PlatformRoles.SUPER_ADMIN), 1L, true);
    }

    private static PlatformPrincipal support() {
        return new PlatformPrincipal("u-support", Set.of(PlatformRoles.SUPPORT), 1L, true);
    }

    @Test
    void superAdminCanProvisionAndDriveLifecycle() {
        FakeTenantPort tenants = new FakeTenantPort();
        FakeAuditPort audit = new FakeAuditPort();
        PlatformTenantService svc =
                new PlatformTenantService(tenants, new PlatformSecurity(new PlatformAuthorizationServiceImpl()), audit);

        PlatformTenantRow created = svc.createTenant(superAdmin(), "acme", "Acme Inc", "PRO");
        assertEquals(TenantLifecycle.PENDING_SETUP, created.lifecycleState());
        assertTrue(audit.has("platform.tenant.create", created.tenantId()));

        // PENDING_SETUP -> ACTIVE
        PlatformTenantRow active = svc.resumeTenant(superAdmin(), created.tenantId());
        assertEquals(TenantLifecycle.ACTIVE, active.lifecycleState());

        // ACTIVE -> SUSPENDED
        PlatformTenantRow suspended = svc.suspendTenant(superAdmin(), created.tenantId());
        assertEquals(TenantLifecycle.SUSPENDED, suspended.lifecycleState());

        // SUSPENDED -> ACTIVE
        assertEquals(TenantLifecycle.ACTIVE, svc.resumeTenant(superAdmin(), created.tenantId()).lifecycleState());

        // ACTIVE -> DECOMMISSIONING
        assertEquals(TenantLifecycle.DECOMMISSIONING,
                svc.decommissionTenant(superAdmin(), created.tenantId()).lifecycleState());
        assertEquals(5, audit.events.size());
    }

    @Test
    void illegalTransitionIsRejectedWithConflict() {
        FakeTenantPort tenants = new FakeTenantPort();
        PlatformTenantService svc = new PlatformTenantService(tenants,
                new PlatformSecurity(new PlatformAuthorizationServiceImpl()), new FakeAuditPort());

        String id = svc.createTenant(superAdmin(), "acme", "Acme", "PRO").tenantId();
        // PENDING_SETUP -> SUSPENDED 非法（须先 ACTIVE）
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> svc.suspendTenant(superAdmin(), id));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void supportCannotCreateTenant() {
        PlatformTenantService svc = new PlatformTenantService(new FakeTenantPort(),
                new PlatformSecurity(new PlatformAuthorizationServiceImpl()), new FakeAuditPort());
        AccessDeniedException ex = assertThrows(AccessDeniedException.class,
                () -> svc.createTenant(support(), "acme", "Acme", "PRO"));
        assertTrue(ex.getMessage().contains("platform.tenant.create"));
    }

    @Test
    void capabilityGrantAndRevoke() {
        FakeTenantPort tenants = new FakeTenantPort();
        PlatformTenantService svc = new PlatformTenantService(tenants,
                new PlatformSecurity(new PlatformAuthorizationServiceImpl()), new FakeAuditPort());
        String id = svc.createTenant(superAdmin(), "acme", "Acme", "PRO").tenantId();

        CapabilityGrantRow grant = svc.grantCapability(superAdmin(), id, "tool:weather", "TENANT");
        assertEquals("ACTIVE", grant.status());
        assertEquals(1, svc.listCapabilities(superAdmin(), id).size());

        svc.revokeCapability(superAdmin(), id, "tool:weather");
        assertEquals(0, tenants.listCapabilities(id).stream()
                .filter(c -> "ACTIVE".equals(c.status())).count());
    }

    @Test
    void quotaUpdate() {
        FakeTenantPort tenants = new FakeTenantPort();
        PlatformTenantService svc = new PlatformTenantService(tenants,
                new PlatformSecurity(new PlatformAuthorizationServiceImpl()), new FakeAuditPort());
        String id = svc.createTenant(superAdmin(), "acme", "Acme", "PRO").tenantId();
        QuotaRow q = svc.updateQuota(superAdmin(), id, 200, 40, new BigDecimal("9999.00"));
        assertEquals(200, q.agentLimit());
        assertEquals(0, new BigDecimal("9999.00").compareTo(q.budgetLimit()));
    }

    // ── 内存假实现 ────────────────────────────────────────

    static final class FakeTenantPort implements PlatformTenantPort {
        final Map<String, PlatformTenantRow> map = new HashMap<>();
        final Map<String, CapabilityGrantRow> caps = new HashMap<>();

        @Override
        public @NonNull List<PlatformTenantRow> listTenants(@Nullable String statusFilter, int limit) {
            return new ArrayList<>(map.values());
        }

        @Override
        public @Nullable PlatformTenantRow findTenant(@NonNull String tenantId) {
            return map.get(tenantId);
        }

        @Override
        public @Nullable QuotaRow findQuota(@NonNull String tenantId) {
            return new QuotaRow(50, 10, null);
        }

        @Override
        public @NonNull PlatformTenantRow provisionTenant(@NonNull String slug, @NonNull String name,
                                                         @Nullable String plan) {
            String id = UUID.randomUUID().toString();
            PlatformTenantRow row = new PlatformTenantRow(id, slug, name,
                    TenantLifecycle.PENDING_SETUP, plan, 1L, 1L, Instant.now(), Instant.now());
            map.put(id, row);
            return row;
        }

        @Override
        public @Nullable PlatformTenantRow transitionLifecycle(@NonNull String tenantId,
                                                              @NonNull String newState) {
            PlatformTenantRow old = map.get(tenantId);
            if (old == null) {
                return null;
            }
            PlatformTenantRow updated = new PlatformTenantRow(old.tenantId(), old.slug(), old.name(),
                    newState, old.plan(), old.effectivePolicyVersion(), old.tenantAuthzVersion(),
                    old.createdAt(), Instant.now());
            map.put(tenantId, updated);
            return updated;
        }

        @Override
        public @Nullable QuotaRow updateQuota(@NonNull String tenantId, @NonNull QuotaRow quota) {
            return quota;
        }

        @Override
        public @NonNull CapabilityGrantRow grantCapability(@NonNull String tenantId,
                                                          @NonNull String resourceCode,
                                                          @NonNull String scope) {
            CapabilityGrantRow row = new CapabilityGrantRow(
                    UUID.randomUUID().toString(), tenantId, resourceCode, scope, "ACTIVE", 1L);
            caps.put(tenantId + "|" + resourceCode, row);
            return row;
        }

        @Override
        public boolean revokeCapability(@NonNull String tenantId, @NonNull String resourceCode) {
            String key = tenantId + "|" + resourceCode;
            CapabilityGrantRow old = caps.get(key);
            if (old == null) {
                return false;
            }
            caps.put(key, new CapabilityGrantRow(old.id(), old.tenantId(), old.resourceCode(),
                    old.scope(), "REVOKED", old.version() + 1));
            return true;
        }

        @Override
        public @NonNull List<CapabilityGrantRow> listCapabilities(@NonNull String tenantId) {
            List<CapabilityGrantRow> out = new ArrayList<>();
            for (CapabilityGrantRow c : caps.values()) {
                if (c.tenantId().equals(tenantId)) {
                    out.add(c);
                }
            }
            return out;
        }
    }

    static final class FakeAuditPort implements PlatformAuditPort {
        final List<PlatformAuditEvent> events = new ArrayList<>();

        @Override
        public void record(@NonNull PlatformAuditEvent event) {
            events.add(event);
        }

        @Override
        public @NonNull List<PlatformAuditEvent> query(@NonNull PlatformAuditQuery query) {
            return events;
        }

        boolean has(String action, String targetId) {
            return events.stream().anyMatch(e ->
                    action.equals(e.action()) && targetId.equals(e.targetId()));
        }
    }
}
