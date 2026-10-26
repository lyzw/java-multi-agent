package cn.studykid.multiagent.infrastructure.security;

import java.util.Set;

import cn.studykid.multiagent.infrastructure.security.PlatformAuthorizationServiceImpl;
import cn.studykid.multiagent.ports.security.Namespaces;
import cn.studykid.multiagent.ports.security.PlatformPrincipal;
import cn.studykid.multiagent.ports.security.PlatformRoles;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 平台授权矩阵测试（重规划 §3.1 / §4.3）。
 *
 * <p>核心断言：默认拒绝 + 最小特权 + 职责分离。
 */
class PlatformAuthorizationServiceImplTest {

    private final PlatformAuthorizationServiceImpl authz = new PlatformAuthorizationServiceImpl();

    private static PlatformPrincipal p(String role) {
        return new PlatformPrincipal("u1", Set.of(role), 1, false);
    }

    @Test
    void superAdminCanDoEverything() {
        PlatformPrincipal superAdmin = p(PlatformRoles.SUPER_ADMIN);
        assertTrue(authz.authorize(superAdmin, Namespaces.Platform.USER_MANAGE, null, null).allowed());
        assertTrue(authz.authorize(superAdmin, Namespaces.Platform.TENANT_DECOMMISSION, null, null).allowed());
        assertTrue(authz.authorize(superAdmin, Namespaces.Platform.AUDIT_EXPORT, null, null).allowed());
    }

    @Test
    void tenantLifecycleAdminCannotManageUsersOrResources() {
        PlatformPrincipal lc = p(PlatformRoles.TENANT_LIFECYCLE_ADMIN);
        assertTrue(authz.authorize(lc, Namespaces.Platform.TENANT_SUSPEND, null, null).allowed());
        assertTrue(authz.authorize(lc, Namespaces.Platform.TENANT_CAPABILITY_GRANT, null, null).allowed());
        // 不可管理平台用户 / 平台角色 / 平台资源
        assertFalse(authz.authorize(lc, Namespaces.Platform.USER_MANAGE, null, null).allowed());
        assertFalse(authz.authorize(lc, Namespaces.Platform.ROLE_ASSIGN, null, null).allowed());
        assertFalse(authz.authorize(lc, Namespaces.Platform.RESOURCE_MANAGE, null, null).allowed());
    }

    @Test
    void resourceAdminCannotManageTenants() {
        PlatformPrincipal ra = p(PlatformRoles.RESOURCE_ADMIN);
        assertTrue(authz.authorize(ra, Namespaces.Platform.RESOURCE_MANAGE, null, null).allowed());
        assertFalse(authz.authorize(ra, Namespaces.Platform.TENANT_CREATE, null, null).allowed());
        assertFalse(authz.authorize(ra, Namespaces.Platform.USER_READ, null, null).allowed());
    }

    @Test
    void auditorIsReadOnly() {
        PlatformPrincipal auditor = p(PlatformRoles.AUDITOR);
        assertTrue(authz.authorize(auditor, Namespaces.Platform.AUDIT_READ, null, null).allowed());
        assertTrue(authz.authorize(auditor, Namespaces.Platform.AUDIT_EXPORT, null, null).allowed());
        // 只读角色不得拥有任何写权限
        assertFalse(authz.authorize(auditor, Namespaces.Platform.USER_MANAGE, null, null).allowed());
        assertFalse(authz.authorize(auditor, Namespaces.Platform.TENANT_SUSPEND, null, null).allowed());
        assertFalse(authz.authorize(auditor, Namespaces.Platform.RESOURCE_MANAGE, null, null).allowed());
    }

    @Test
    void supportIsReadOnlyDiagnosticsOnly() {
        PlatformPrincipal support = p(PlatformRoles.SUPPORT);
        assertTrue(authz.authorize(support, Namespaces.Platform.SUPPORT_DIAGNOSTICS_READ, null, null).allowed());
        assertFalse(authz.authorize(support, Namespaces.Platform.AUDIT_READ, null, null).allowed());
        assertFalse(authz.authorize(support, Namespaces.Platform.USER_READ, null, null).allowed());
    }

    @Test
    void securityAdminCannotGrantSuperAdmin() {
        PlatformPrincipal sa = p(PlatformRoles.SECURITY_ADMIN);
        // SoD：安全管理员不能授予/撤销超级管理员
        assertFalse(authz.authorize(sa, Namespaces.Platform.ROLE_ASSIGN, "role",
                PlatformRoles.SUPER_ADMIN).allowed());
        // 但可发布策略
        assertTrue(authz.authorize(sa, Namespaces.Platform.POLICY_PUBLISH, null, null).allowed());
    }

    @Test
    void onlySuperAdminCanAssignSuperAdmin() {
        PlatformPrincipal superAdmin = p(PlatformRoles.SUPER_ADMIN);
        assertTrue(authz.authorize(superAdmin, Namespaces.Platform.ROLE_ASSIGN, "role",
                PlatformRoles.SUPER_ADMIN).allowed());
        assertFalse(authz.authorize(p(PlatformRoles.RESOURCE_ADMIN), Namespaces.Platform.ROLE_ASSIGN,
                "role", PlatformRoles.SUPER_ADMIN).allowed());
    }

    @Test
    void unknownActionIsDeniedByDefault() {
        PlatformPrincipal superAdmin = p(PlatformRoles.SUPER_ADMIN);
        assertFalse(authz.authorize(superAdmin, "platform.nonexistent.action", null, null).allowed());
    }
}
