package cn.studykid.multiagent.infrastructure.persistence.scope;

import cn.studykid.multiagent.core.security.ScopeFilter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaintVerifierTest {

    private static final String SENTINEL = "shared-tenant";

    @Test
    void tenantOnly_acceptsMatchingRow() {
        TaintVerifier v = new TaintVerifier(new ScopeFilter.TenantOnly("t-a"), SENTINEL);
        assertTrue(v.accept(new RowAttribution("t-a", false)));
        assertEquals(0, v.violations());
        assertTrue(v.report().clean());
    }

    @Test
    void tenantOnly_rejectsOtherTenant() {
        TaintVerifier v = new TaintVerifier(new ScopeFilter.TenantOnly("t-a"), SENTINEL);
        assertFalse(v.accept(new RowAttribution("t-b", false)));
        assertEquals(1, v.violations());
    }

    @Test
    void none_rejectsAnyRow() {
        TaintVerifier v = new TaintVerifier(ScopeFilter.NONE, SENTINEL);
        assertFalse(v.accept(new RowAttribution("t-a", false)));
        assertFalse(v.report().clean());
    }

    @Test
    void unrestricted_acceptsAnyRow() {
        TaintVerifier v = new TaintVerifier(new ScopeFilter.Unrestricted("ops"), SENTINEL);
        assertTrue(v.accept(new RowAttribution("t-anything", false)));
        assertEquals(0, v.violations());
    }

    @Test
    void tenantWithShared_acceptsSubjectOrSharedSentinel() {
        TaintVerifier v = new TaintVerifier(new ScopeFilter.TenantWithShared("t-a"), SENTINEL);
        assertTrue(v.accept(new RowAttribution("t-a", false)));
        assertTrue(v.accept(new RowAttribution(SENTINEL, true)));
        assertFalse(v.accept(new RowAttribution("t-b", false)));
        assertEquals(1, v.violations());
    }

    @Test
    void report_aggregates() {
        TaintVerifier v = new TaintVerifier(new ScopeFilter.TenantOnly("t-a"), SENTINEL);
        v.accept(new RowAttribution("t-a", false));
        v.accept(new RowAttribution("t-b", false));
        v.accept(new RowAttribution("t-c", false));
        TaintReport r = v.report();
        assertEquals(3, r.rowsChecked());
        assertEquals(2, r.violations());
        assertFalse(r.clean());
    }
}
