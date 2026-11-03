package cn.studykid.multiagent.infrastructure.security;

import cn.studykid.multiagent.core.security.Action;
import cn.studykid.multiagent.core.security.ResourceRef;
import cn.studykid.multiagent.core.security.ScopeFilter;
import cn.studykid.multiagent.core.tenant.SubjectContext;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AuthzShadowHooksTest {

    private static final String TENANT = "tenant-A";
    private static final String SENTINEL = "shared-tenant";

    /** 一套「Gate→ScopeFilter 固定返回」的测试夹具（P1 影子期只观测不阻断）。 */
    private static final class Fixture {
        final InMemoryAuthzMismatchMetrics metrics = new InMemoryAuthzMismatchMetrics();
        final AuthzShadowReporter reporter;
        final ScopedDataAccess scoped;

        Fixture(ScopeFilter filter, boolean block) {
            this.reporter = new AuthzShadowReporter(metrics, block);
            AccessGate gate = new AccessGate() {
                @Override
                public ScopeFilter authorize(Action a, ResourceRef r) {
                    return filter;
                }

                @Override
                public ScopeFilter authorizeCollection(Action a, String t) {
                    return filter;
                }

                @Override
                public ScopeFilter authorizeCollection(SubjectContext s, Action a, String t) {
                    return filter;
                }
            };
            this.scoped = new ScopedDataAccess(gate);
        }
    }

    @Test
    void compare_matchingTenant_noMismatch() {
        Fixture f = new Fixture(new ScopeFilter.TenantOnly(TENANT), false);
        AuthzShadowHooks.compare(f.scoped, f.reporter, true, "agent",
                SubjectContext.forTenant(TENANT), TENANT, true);
        assertEquals(0, f.metrics.mismatches());
    }

    @Test
    void compare_mismatchedTenant_recordsMismatch() {
        Fixture f = new Fixture(new ScopeFilter.TenantOnly(TENANT), false);
        AuthzShadowHooks.compare(f.scoped, f.reporter, true, "agent",
                SubjectContext.forTenant("tenant-C"), "tenant-C", true);
        assertEquals(1, f.metrics.mismatches());
    }

    @Test
    void compare_gateNone_withPredicate_recordsMismatch() {
        Fixture f = new Fixture(ScopeFilter.NONE, false);
        AuthzShadowHooks.compare(f.scoped, f.reporter, true, "agent",
                SubjectContext.forTenant(TENANT), TENANT, true);
        assertEquals(1, f.metrics.mismatches());
    }

    @Test
    void compare_disabled_isNoOp() {
        Fixture f = new Fixture(new ScopeFilter.TenantOnly(TENANT), false);
        AuthzShadowHooks.compare(f.scoped, f.reporter, false, "agent",
                SubjectContext.forTenant("tenant-C"), "tenant-C", true);
        assertEquals(0, f.metrics.mismatches());
    }

    @Test
    void taint_inScopeRow_noViolation() {
        Fixture f = new Fixture(new ScopeFilter.TenantOnly(TENANT), false);
        AuthzShadowHooks.taint(f.scoped, f.reporter, true, "agent", SENTINEL,
                SubjectContext.forTenant(TENANT), TENANT, false);
        assertEquals(0, f.metrics.taintViolations());
    }

    @Test
    void taint_crossTenant_recordsViolation() {
        Fixture f = new Fixture(new ScopeFilter.TenantOnly(TENANT), false);
        AuthzShadowHooks.taint(f.scoped, f.reporter, true, "agent", SENTINEL,
                SubjectContext.forTenant(TENANT), "tenant-B", false);
        assertEquals(1, f.metrics.taintViolations());
    }

    @Test
    void taint_sentinelSharedRow_passesForTenantWithShared() {
        Fixture f = new Fixture(new ScopeFilter.TenantWithShared(TENANT), false);
        AuthzShadowHooks.taint(f.scoped, f.reporter, true, "agent", SENTINEL,
                SubjectContext.forTenant(TENANT), SENTINEL, true);
        assertEquals(0, f.metrics.taintViolations());
    }
}
