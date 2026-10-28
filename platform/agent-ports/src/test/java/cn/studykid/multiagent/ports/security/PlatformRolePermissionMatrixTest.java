package cn.studykid.multiagent.ports.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.ports.security.Namespaces.Platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 平台角色权限矩阵自检（重规划 §3.1 / §4.1）。
 *
 * <p><b>为什么这些断言必须存在</b>：{@code verifyComplete()} 与
 * {@code verifyDescribed()} 写好了却<b>没有任何调用方</b>（既不在启动路径，也无测试）——
 * 于是「新增权限码忘了登记说明」「某动作全平台无人可执行」这两类缺口
 * 只会在真实调用时才暴露。把它们接进测试，自检才真的会跑。
 *
 * <p>同时冻结一条<b>不可回退</b>的口径：{@code platform.tenant.execution_ticket}
 * 只属于超级管理员。它等于「可扮演任意租户发起执行」，
 * 一旦哪天有人为了省事把它加进生命周期管理员或安全管理员，
 * 最小特权与职责分离就同时失效，而矩阵页面上看不出任何异常。
 */
class PlatformRolePermissionMatrixTest {

    @Test
    @DisplayName("启动期自检：矩阵无缺口、每个权限都有中文说明")
    void selfChecksPass() {
        PlatformRolePermissionMatrix.verifyComplete();
        PlatformRolePermissionMatrix.verifyDescribed();
    }

    @Test
    @DisplayName("⚠️ 说明表与权限全集双向对齐：多一条、少一条、空说明都算失败")
    void descriptionsAlignWithPermissionUniverse() {
        Map<String, String> described = PlatformRolePermissionMatrix.PERMISSION_DESCRIPTIONS;
        Set<String> universe = PlatformRolePermissionMatrix.allPlatformPermissions();

        assertEquals(universe, Set.copyOf(described.keySet()),
                "PERMISSION_DESCRIPTIONS 与 allPlatformPermissions() 不同源");
        assertTrue(described.values().stream().noneMatch(String::isBlank));
    }

    @Test
    @DisplayName("执行票证权限已登记，且只有超级管理员持有")
    void executionTicketIsSuperAdminOnly() {
        Set<String> universe = PlatformRolePermissionMatrix.allPlatformPermissions();
        assertTrue(universe.contains(Platform.TENANT_EXECUTION_TICKET),
                "新权限码没进全集 → 任何角色都拿不到它（默认拒绝）");
        assertTrue(PlatformRolePermissionMatrix.descriptionOf(
                Platform.TENANT_EXECUTION_TICKET).length() > 0);

        assertTrue(PlatformRolePermissionMatrix
                .permissionsOf(PlatformRoles.SUPER_ADMIN)
                .contains(Platform.TENANT_EXECUTION_TICKET));

        List<String> overGranting = new ArrayList<>();
        for (String role : PlatformRoles.ALL) {
            if (PlatformRoles.SUPER_ADMIN.equals(role)) {
                continue;
            }
            if (PlatformRolePermissionMatrix.permissionsOf(role)
                    .contains(Platform.TENANT_EXECUTION_TICKET)) {
                overGranting.add(role);
            }
        }
        assertTrue(overGranting.isEmpty(),
                "⚠️ 以下平台角色被授以「可扮演租户执行」的权限：" + overGranting);
    }

    @Test
    @DisplayName("角色权限只能是全集的子集（不得出现未登记的裸字符串）")
    void everyGrantedPermissionIsRegistered() {
        Set<String> universe = PlatformRolePermissionMatrix.allPlatformPermissions();
        List<String> strays = new ArrayList<>();
        for (String role : PlatformRoles.ALL) {
            for (String permission : PlatformRolePermissionMatrix.permissionsOf(role)) {
                if (!universe.contains(permission)) {
                    strays.add(role + " → " + permission);
                }
            }
        }
        assertTrue(strays.isEmpty(), "矩阵里出现了未登记的权限码：" + strays);
    }
}
