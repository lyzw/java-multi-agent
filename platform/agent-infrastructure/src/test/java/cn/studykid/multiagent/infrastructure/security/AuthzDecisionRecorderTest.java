package cn.studykid.multiagent.infrastructure.security;

import cn.studykid.multiagent.core.security.Action;
import cn.studykid.multiagent.core.security.ResourceRef;
import cn.studykid.multiagent.core.tenant.SubjectContext;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthzDecisionRecorderTest {

    private static final SubjectContext SUBJECT = SubjectContext.forTenant("tenant-A");
    private static final ResourceRef RES = ResourceRef.collection("agent");

    @Test
    void noOp_neverThrowsAndHasNoState() {
        NoOpAuthzDecisionRecorder.INSTANCE.record(SUBJECT, Action.RETRIEVE, RES, true, null);
        NoOpAuthzDecisionRecorder.INSTANCE.record(SUBJECT, Action.RETRIEVE, RES, false, "DENY");
    }

    @Test
    void inMemory_recordsPermitAndDeny() {
        InMemoryAuthzDecisionRecorder r = new InMemoryAuthzDecisionRecorder();
        r.record(SUBJECT, Action.RETRIEVE, RES, true, null);
        r.record(SUBJECT, Action.WRITE, RES, false, "FORBIDDEN");

        assertEquals(2, r.size());
        InMemoryAuthzDecisionRecorder.Entry permit = r.entries().get(0);
        assertTrue(permit.permitted());
        assertEquals(null, permit.reasonCode());
        InMemoryAuthzDecisionRecorder.Entry deny = r.entries().get(1);
        assertFalse(deny.permitted());
        assertEquals("FORBIDDEN", deny.reasonCode());
    }

    @Test
    void inMemory_evictsBeyondCapacity() {
        InMemoryAuthzDecisionRecorder r = new InMemoryAuthzDecisionRecorder(3);
        for (int i = 0; i < 10; i++) {
            r.record(SUBJECT, Action.RETRIEVE, RES, true, null);
        }
        assertEquals(3, r.size());
    }

    @Test
    void inMemory_rejectsNullSubject() {
        InMemoryAuthzDecisionRecorder r = new InMemoryAuthzDecisionRecorder();
        assertThrows(NullPointerException.class,
                () -> r.record(null, Action.RETRIEVE, RES, true, null));
    }
}
