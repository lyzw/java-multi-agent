package cn.studykid.multiagent.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Set;

import cn.studykid.multiagent.core.security.Action;
import cn.studykid.multiagent.core.security.ResourceRef;
import cn.studykid.multiagent.core.security.Scope;
import cn.studykid.multiagent.core.security.ScopeFilter;
import cn.studykid.multiagent.core.tenant.SubjectContext;
import cn.studykid.multiagent.core.tenant.TenantContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DefaultAccessGate —— 门禁外观（fail-closed）")
class DefaultAccessGateTest {

    private static final PolicyRule RULE = new PolicyRule(
            "rag-chunk-read", "rag_chunk", Action.RETRIEVE,
            ScopeRequirement.TENANT, ScopeEffect.TENANT_WITH_SHARED);

    private static SubjectContext subject() {
        return SubjectContext.from(new TenantContext("t-1", "p-1", Set.of(), 1L), Scope.TENANT);
    }

    private static PolicyEngine engine() {
        return new RuleBasedPolicyEngine(new InMemoryPolicyStore(List.of(RULE)));
    }

    @Test
    @DisplayName("Permit → 返回 ScopeFilter")
    void permitReturnsFilter() {
        DefaultAccessGate gate = new DefaultAccessGate(() -> subject(), engine());
        ScopeFilter f = gate.authorizeCollection(Action.RETRIEVE, "rag_chunk");
        assertInstanceOf(ScopeFilter.TenantWithShared.class, f);
    }

    @Test
    @DisplayName("Deny → 抛 AccessDeniedException")
    void denyThrows() {
        // 写动作无匹配规则 → Deny → 异常
        DefaultAccessGate gate = new DefaultAccessGate(() -> subject(), engine());
        assertThrows(AccessDeniedException.class,
                () -> gate.authorize(Action.WRITE, ResourceRef.collection("rag_chunk")));
    }

    @Test
    @DisplayName("resolver 抛异常 → AccessDeniedException（fail-closed）")
    void resolverFailureDenied() {
        DefaultAccessGate gate = new DefaultAccessGate(() -> {
            throw new IllegalStateException("no auth context");
        }, engine());
        assertThrows(AccessDeniedException.class,
                () -> gate.authorizeCollection(Action.RETRIEVE, "rag_chunk"));
    }

    @Test
    @DisplayName("engine 抛异常 → AccessDeniedException（绝不回落放行，R5）")
    void engineFailureDenied() {
        PolicyEngine failing = (s, a, r) -> {
            throw new RuntimeException("boom");
        };
        DefaultAccessGate gate = new DefaultAccessGate(() -> subject(), failing);
        assertThrows(AccessDeniedException.class,
                () -> gate.authorizeCollection(Action.RETRIEVE, "rag_chunk"));
    }

    // ── 决策日志故障隔离（observability 不得改变判定）──────────────────────

    private static final AuthzDecisionRecorder BROKEN_RECORDER =
            (subject, action, resource, permitted, reasonCode) -> {
                throw new IllegalStateException("审计库不可用");
            };

    @Test
    @DisplayName("决策日志写失败：Permit 仍然放行（不得把放行变成 5xx）")
    void recorderFailure_keepsPermit() {
        DefaultAccessGate gate = new DefaultAccessGate(() -> subject(), engine(), BROKEN_RECORDER);
        ScopeFilter f = gate.authorizeCollection(Action.RETRIEVE, "rag_chunk");
        assertInstanceOf(ScopeFilter.TenantWithShared.class, f);
    }

    @Test
    @DisplayName("决策日志写失败：Deny 仍抛原始原因码（不得被内部异常覆盖）")
    void recorderFailure_keepsDenyReason() {
        DefaultAccessGate gate = new DefaultAccessGate(() -> subject(), engine(), BROKEN_RECORDER);
        AccessDeniedException ex = assertThrows(AccessDeniedException.class,
                () -> gate.authorize(Action.WRITE, ResourceRef.collection("rag_chunk")));
        // 原因码来自策略引擎（NO_RULE），不是日志层的 IllegalStateException
        assertThat(ex.reasonCode()).isNotBlank();
        assertThat(ex.getCause()).isNull();
    }
}
