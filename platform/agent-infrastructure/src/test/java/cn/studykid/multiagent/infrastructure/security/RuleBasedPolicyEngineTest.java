package cn.studykid.multiagent.infrastructure.security;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Set;

import cn.studykid.multiagent.core.security.Action;
import cn.studykid.multiagent.core.security.Decision;
import cn.studykid.multiagent.core.security.ResourceRef;
import cn.studykid.multiagent.core.security.Scope;
import cn.studykid.multiagent.core.security.ScopeFilter;
import cn.studykid.multiagent.core.tenant.SubjectContext;
import cn.studykid.multiagent.core.tenant.TenantContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("RuleBasedPolicyEngine —— 规则决策（含 R3 不变式）")
class RuleBasedPolicyEngineTest {

    private static final PolicyRule TENANT_READ = new PolicyRule(
            "rag-chunk-read", "rag_chunk", Action.RETRIEVE,
            ScopeRequirement.TENANT, ScopeEffect.TENANT_WITH_SHARED);
    private static final PolicyRule OPS_WRITE_SHARED = new PolicyRule(
            "rag-chunk-write-shared", "rag_chunk", Action.WRITE,
            ScopeRequirement.PLATFORM, ScopeEffect.UNRESTRICTED);

    private static SubjectContext tenant(String... roles) {
        return SubjectContext.from(new TenantContext("t-1", "p-1", Set.of(roles), 1L), Scope.TENANT);
    }

    private static SubjectContext ops() {
        return SubjectContext.from(new TenantContext("t-1", "p-1", Set.of("PLATFORM_ADMIN"), 1L),
                Scope.PLATFORM);
    }

    private static PolicyEngine engine(PolicyRule... rules) {
        return new RuleBasedPolicyEngine(new InMemoryPolicyStore(List.of(rules)));
    }

    private static ResourceRef ragChunkCollection() {
        return ResourceRef.collection("rag_chunk");
    }

    @Nested
    @DisplayName("租户态决策")
    class Tenant {

        @Test
        @DisplayName("匹配 TENANT 规则 → Permit(TenantWithShared)")
        void tenantReadPermitted() {
            Decision d = engine(TENANT_READ).decide(tenant(), Action.RETRIEVE, ragChunkCollection());
            Decision.Permit permit = assertInstanceOf(Decision.Permit.class, d);
            assertInstanceOf(ScopeFilter.TenantWithShared.class, permit.filter());
        }

        @Test
        @DisplayName("租户态请求 PLATFORM 规则 → Deny")
        void tenantCantUsePlatformRule() {
            Decision d = engine(OPS_WRITE_SHARED).decide(tenant(), Action.WRITE, ragChunkCollection());
            assertInstanceOf(Decision.Deny.class, d);
        }
    }

    @Nested
    @DisplayName("平台运维态决策（R3）")
    class PlatformOps {

        @Test
        @DisplayName("isPlatformOps + UNRESTRICTED 规则 → Permit(Unrestricted)")
        void opsUnrestrictedPermitted() {
            Decision d = engine(OPS_WRITE_SHARED).decide(ops(), Action.WRITE, ragChunkCollection());
            Decision.Permit permit = assertInstanceOf(Decision.Permit.class, d);
            assertInstanceOf(ScopeFilter.Unrestricted.class, permit.filter());
        }
    }

    @Nested
    @DisplayName("非法策略（R3 配置校验，拒绝启动）")
    class IllegalPolicy {

        @Test
        @DisplayName("UNRESTRICTED 配 TENANT 前置 → IllegalPolicyException")
        void unrestrictedWithoutPlatformRequirementRejected() {
            PolicyRule bad = new PolicyRule("bad", "x", Action.READ,
                    ScopeRequirement.TENANT, ScopeEffect.UNRESTRICTED);
            assertThrows(IllegalPolicyException.class, () -> engine(bad));
        }
    }

    @Nested
    @DisplayName("无规则 / 无主体（默认拒绝）")
    class Missing {

        @Test
        @DisplayName("无匹配规则 → Deny(NO_RULE)")
        void noRuleDenied() {
            Decision d = engine(TENANT_READ).decide(tenant(), Action.WRITE, ragChunkCollection());
            assertInstanceOf(Decision.Deny.class, d);
        }

        @Test
        @DisplayName("subject 为 null → Deny(NO_SUBJECT)")
        void nullSubjectDenied() {
            Decision d = engine(TENANT_READ).decide(null, Action.RETRIEVE, ragChunkCollection());
            assertInstanceOf(Decision.Deny.class, d);
        }
    }
}
