package cn.studykid.multiagent.infrastructure.security;

import java.util.Set;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.infrastructure.security.RoleBasedAuthorizationService;
import cn.studykid.multiagent.ports.security.RolePermissionPort;

/**
 * {@link RolePermissionPort} 的实现：复用既有角色→动作权限矩阵。
 *
 * <p>⚠️ <b>只做角色判定，不做版本判定</b>：版本维度由
 * {@code InvocationValidator} 直接读 {@code AuthzVersionPort}（DB 强一致）完成。
 * 若改用 {@code AuthorizationService.isAuthorized}，其内部的版本判定读的是
 * <b>进程内内存 Map</b>（缺省 0L），在「DB 已撤权但内存未同步」的实例上会
 * <b>静默忽略撤权</b> —— 详见 {@link RolePermissionPort} 的类注释。
 *
 * <p>权限矩阵本身<b>不重复定义</b>：直接取 {@code RoleBasedAuthorizationService.permissionsOf}，
 * 保证「Phase A 判定」与「既有闸门」用的是同一份角色权限定义，
 * 不会出现两套矩阵各说各话。
 */
public class RolePermissionAdapter implements RolePermissionPort {

    @Override
    public boolean permits(@NonNull Set<String> roles, @NonNull String action) {
        // ⚠️ 未登记的角色 permissionsOf 返回空集 → anyMatch 为 false → 默认拒绝
        return roles.stream()
                .map(RoleBasedAuthorizationService::permissionsOf)
                .anyMatch(actions -> actions.contains(action));
    }
}
