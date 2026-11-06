package cn.studykid.multiagent.infrastructure.security;

import cn.studykid.multiagent.core.security.Action;
import cn.studykid.multiagent.core.security.ResourceRef;
import cn.studykid.multiagent.core.security.ScopeFilter;
import cn.studykid.multiagent.core.tenant.SubjectContext;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;

class ScopedDataAccessTest {

    @Test
    void scopeForCollection_delegatesToGate() {
        ScopeFilter expected = new ScopeFilter.TenantOnly("t-a");
        AccessGate gate = new AccessGate() {
            @Override
            public ScopeFilter authorize(Action action, ResourceRef resource) {
                return expected;
            }

            @Override
            public ScopeFilter authorizeCollection(Action action, String resourceType) {
                return expected;
            }

            @Override
            public ScopeFilter authorizeCollection(SubjectContext subject, Action action,
                                                   String resourceType) {
                return expected;
            }
        };
        ScopedDataAccess s = new ScopedDataAccess(gate);
        assertSame(expected, s.scopeForCollection(Action.RETRIEVE, "x"));
        assertSame(expected,
                s.scopeForCollection(SubjectContext.forTenant("t-a"), Action.RETRIEVE, "x"));
    }
}
