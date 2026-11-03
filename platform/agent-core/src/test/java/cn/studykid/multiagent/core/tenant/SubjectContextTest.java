package cn.studykid.multiagent.core.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.security.Scope;

@DisplayName("SubjectContext —— 主体上下文（含 scope 维度）")
class SubjectContextTest {

    private static TenantContext tenantCtx(String... roles) {
        return new TenantContext("t-1", "p-1", Set.of(roles), 1L);
    }

    @Nested
    @DisplayName("isPlatformOps 不变式（R3 核心防线）")
    class PlatformOpsInvariant {

        @Test
        @DisplayName("租户态 + 任意角色 → false")
        void tenantScopeIsNeverPlatformOps() {
            SubjectContext subject = SubjectContext.from(tenantCtx("PLATFORM_ADMIN"), Scope.TENANT);
            assertFalse(subject.isPlatformOps());
        }

        @Test
        @DisplayName("PLATFORM 态 + 含 PLATFORM_ADMIN → true")
        void platformScopeWithAdminRoleIsPlatformOps() {
            SubjectContext subject = SubjectContext.from(tenantCtx("PLATFORM_ADMIN"), Scope.PLATFORM);
            assertTrue(subject.isPlatformOps());
        }

        @Test
        @DisplayName("PLATFORM 态但不含 PLATFORM_ADMIN → false（防越权放大）")
        void platformScopeWithoutAdminRoleIsNotPlatformOps() {
            SubjectContext subject = SubjectContext.from(tenantCtx("TENANT_USER"), Scope.PLATFORM);
            assertFalse(subject.isPlatformOps());
        }

        @Test
        @DisplayName("默认租户工厂 → 非平台运维")
        void tenantFactoryIsNotPlatformOps() {
            assertFalse(SubjectContext.tenant(tenantCtx()).isPlatformOps());
        }
    }

    @Nested
    @DisplayName("构造约束与不可变性")
    class Construction {

        @Test
        @DisplayName("tenantId 为空 → 抛异常")
        void blankTenantIdRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> new SubjectContext("", "p", Set.of(), Scope.TENANT, 1L));
        }

        @Test
        @DisplayName("authzVersion 为负 → 抛异常")
        void negativeVersionRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> new SubjectContext("t", "p", Set.of(), Scope.TENANT, -1L));
        }

        @Test
        @DisplayName("auditString 含 scope 维度")
        void auditStringIncludesScope() {
            SubjectContext subject = SubjectContext.from(tenantCtx("PLATFORM_ADMIN"), Scope.PLATFORM);
            assertTrue(subject.auditString().contains("scope=PLATFORM"));
        }

        @Test
        @DisplayName("forTenant 工厂 → 租户态、非平台运维、principal=repo")
        void forTenantFactory() {
            SubjectContext subject = SubjectContext.forTenant("tenant-X");
            assertEquals("tenant-X", subject.tenantId());
            assertEquals(Scope.TENANT, subject.scope());
            assertEquals("repo", subject.principalId());
            assertFalse(subject.isPlatformOps());
        }
    }
}
