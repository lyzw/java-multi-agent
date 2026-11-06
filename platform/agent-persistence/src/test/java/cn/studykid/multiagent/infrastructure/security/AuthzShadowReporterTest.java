package cn.studykid.multiagent.infrastructure.security;

import cn.studykid.multiagent.core.security.ScopeFilter;
import cn.studykid.multiagent.infrastructure.persistence.scope.RowAttribution;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuthzShadowReporterTest {

    @Test
    void shadowMode_doesNotThrowAndCounts() {
        InMemoryAuthzMismatchMetrics m = new InMemoryAuthzMismatchMetrics();
        AuthzShadowReporter r = new AuthzShadowReporter(m, false);

        r.recordScopeEvaluation("t", new ScopeFilter.Unrestricted("ops"));
        r.reportMismatch("t", "detail");
        r.reportTaintViolation("t", new RowAttribution("t-b", false), new ScopeFilter.TenantOnly("t-a"));

        assertEquals(1, m.shadowSamples());
        assertEquals(1, m.unrestrictedUsages());
        assertEquals(1, m.mismatches());
        assertEquals(1, m.taintViolations());
    }

    @Test
    void blockMode_throwsOnMismatchAndTaint() {
        InMemoryAuthzMismatchMetrics m = new InMemoryAuthzMismatchMetrics();
        AuthzShadowReporter r = new AuthzShadowReporter(m, true);

        assertThrows(AccessDeniedException.class, () -> r.reportMismatch("t", "x"));
        assertThrows(AccessDeniedException.class,
                () -> r.reportTaintViolation("t", new RowAttribution("b", false),
                        new ScopeFilter.TenantOnly("a")));
        assertEquals(2, m.mismatches() + m.taintViolations());
    }
}
