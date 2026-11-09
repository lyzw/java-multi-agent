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

    @Test
    void rlsDependency_neverBlocksAndNeverCountsAsMismatch() {
        // ⚠️ 「靠 RLS 兜底」是迁移清单条目，不是违例：
        //    阻断模式下也必须不抛、也不进 mismatches（出口门禁指标语义不能被污染）。
        InMemoryAuthzMismatchMetrics m = new InMemoryAuthzMismatchMetrics();
        AuthzShadowReporter blocking = new AuthzShadowReporter(m, true);

        blocking.reportRlsDependent("t", "查询未带显式租户谓词");

        assertEquals(1, m.rlsDependent());
        assertEquals(0, m.mismatches());
        assertEquals(0, m.taintViolations());
    }
}
