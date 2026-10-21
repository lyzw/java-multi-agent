package cn.studykid.multiagent.infrastructure.security;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.security.AuthorizationService;

/**
 * 基于角色的授权实现（P1-04 / FR-008 / FR-010）。
 *
 * <p><b>设计要点</b>：
 * <ol>
 *   <li><b>角色到动作的映射是数据而非代码分支</b>——新增动作只需扩充映射表，
 *       不会遗漏某条 if 判断。</li>
 *   <li><b>authzVersion 校验</b>：授权被撤销时版本号递增，持有旧版本的在途
 *       上下文会被拒绝（P0-D03 §4 红线 9：旧 Run 恢复时须重新验证当前授权）。</li>
 *   <li><b>默认拒绝</b>：未在映射表中登记的动作<b>一律拒绝</b>，
 *       不会因「忘记配置」而放行。</li>
 * </ol>
 *
 * <p><b>本类是 P1 的内存实现</b>，用于跑通闭环。生产实现须替换为
 * IAM/OIDC + 数据库授权表（D-001），接口保持不变。
 */
public class RoleBasedAuthorizationService implements AuthorizationService {

    /** 角色 → 允许的动作集合。未登记的动作对所有角色默认拒绝。 */
    private static final Map<String, Set<String>> ROLE_PERMISSIONS = Map.of(
            Roles.TENANT_ADMIN, Set.of(
                    Actions.RUN_CREATE, Actions.RUN_READ, Actions.RUN_CANCEL,
                    Actions.EVENT_SUBSCRIBE, Actions.RESULT_READ,
                    Actions.TOOL_INVOKE, Actions.APPROVAL_CONSUME,
                    Actions.UNKNOWN_DISPOSE, Actions.AUDIT_READ, Actions.AGENT_MANAGE,
                    // ⚠️ 画像读取（P4-D09 / G6）：租户管理员是画像数据的管理方
                    Actions.PROFILE_READ,
                    // ⚠️ **配置审批（P4-D16 / C7）**：
                    //    TENANT_ADMIN 同时具备申请与审批两个动作——这是**刻意的**：
                    //    「管理员什么都能做」是既有不变式，而双人复核靠的是
                    //    **身份比对**（approver_id <> requester_id，应用层 + DB CHECK 双保险），
                    //    不是靠权限拆分。
                    //    若把审批权从管理员手里拿走，反而会导致「无人可批」：
                    //    试点期往往只有一个管理员。
                    Actions.CONFIG_REQUEST, Actions.CONFIG_APPROVE),
            Roles.AGENT_AUTHOR, Set.of(
                    Actions.RUN_CREATE, Actions.RUN_READ, Actions.RUN_CANCEL,
                    Actions.EVENT_SUBSCRIBE, Actions.RESULT_READ,
                    Actions.TOOL_INVOKE, Actions.AGENT_MANAGE,
                    // ⚠️ **只能申请、不能审批**（P4-D16 / C7）：
                    //    作者负责准备变更，审批权在管理员。
                    //    这使双人复核在**权限层面**就有落点，
                    //    而不只是靠同一管理员的自律。
                    Actions.CONFIG_REQUEST),
            Roles.APPROVER, Set.of(
                    Actions.RUN_READ, Actions.APPROVAL_CONSUME,
                    Actions.UNKNOWN_DISPOSE, Actions.EVENT_SUBSCRIBE),
            Roles.VIEWER, Set.of(
                    Actions.RUN_READ, Actions.RESULT_READ, Actions.EVENT_SUBSCRIBE,
                    // ⚠️ 画像读取（P4-D09 / G6）：只读角色读只读数据，语义一致
                    Actions.PROFILE_READ),
            // Worker 服务账号：需要执行与处置能力，但不需人工审批
            // ⚠️ **刻意不含 PROFILE_READ**（P4-D09 / G6）：
            //    Worker 是执行侧服务账号，读学生画像不属于其职责；
            //    且画像是学生维度数据，给服务账号开这个口子会扩大数据可见面。
            Roles.WORKER, Set.of(
                    Actions.RUN_READ, Actions.RUN_CANCEL, Actions.TOOL_INVOKE,
                    Actions.EVENT_SUBSCRIBE, Actions.RESULT_READ)
    );

    /** 租户+主体 → 当前授权版本。撤销时递增。 */
    private final Map<String, Long> authzVersions = new ConcurrentHashMap<>();

    @Override
    public boolean isAuthorized(@NonNull TenantContext context,
                                @NonNull String action,
                                @Nullable String resourceId) {
        // 1) 授权版本校验：拒绝持有陈旧版本的上下文
        long current = currentAuthzVersion(context.tenantId(), context.principalId());
        if (context.authzVersion() < current) {
            // 授权已在该上下文创建后被撤销
            return false;
        }
        // 2) 角色授权判断：未登记动作默认拒绝
        return context.roles().stream()
                .map(ROLE_PERMISSIONS::get)
                .filter(java.util.Objects::nonNull)
                .anyMatch(actions -> actions.contains(action));
    }

    @Override
    public long currentAuthzVersion(@NonNull String tenantId, @NonNull String principalId) {
        return authzVersions.getOrDefault(tenantId + "/" + principalId, 0L);
    }

    @Override
    public @NonNull Set<String> rolesOf(@NonNull TenantContext context) {
        return context.roles();
    }

    /**
     * 撤销主体在租户内的全部授权。
     *
     * <p>递增版本号后，所有持有旧 {@code authzVersion} 的在途上下文在下一次
     * 授权检查时将被拒绝——这是「执行时重新授权」的技术基础。
     */
    public void revoke(@NonNull TenantContext context) {
        authzVersions.merge(context.tenantId() + "/" + context.principalId(), 1L, Long::sum);
    }

    /** @return 某角色被允许的动作集合（只读）。 */
    public static @NonNull Set<String> permissionsOf(@NonNull String role) {
        return ROLE_PERMISSIONS.getOrDefault(role, Set.of());
    }

    /**
     * 启动期自检：确认所有受控动作至少被一个角色允许。
     *
     * <p>若某个动作没有任何角色可执行，说明权限矩阵存在缺口。
     * 该检查在应用启动时执行，失败则拒绝启动——避免上线后才发现动作无人可执行。
     */
    public static void verifyPermissionMatrixComplete() {
        Set<String> granted = ROLE_PERMISSIONS.values().stream()
                .flatMap(Set::stream)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> missing = Actions.all().stream()
                .filter(a -> !granted.contains(a))
                .collect(java.util.stream.Collectors.toSet());
        if (!missing.isEmpty()) {
            throw new IllegalStateException("权限矩阵存在缺口，以下动作无任何角色可执行: " + missing);
        }
    }
}