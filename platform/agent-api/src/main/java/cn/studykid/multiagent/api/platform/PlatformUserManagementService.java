package cn.studykid.multiagent.api.platform;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import cn.studykid.multiagent.api.security.PlatformSecurity;
import cn.studykid.multiagent.infrastructure.security.PrincipalRepository.PasswordHasher;
import cn.studykid.multiagent.ports.audit.PlatformAuditPort;
import cn.studykid.multiagent.ports.audit.PlatformAuditPort.PlatformAuditEvent;
import cn.studykid.multiagent.ports.security.Namespaces;
import cn.studykid.multiagent.ports.security.PlatformPrincipal;
import cn.studykid.multiagent.ports.security.PlatformPrincipalRepository;
import cn.studykid.multiagent.ports.security.PlatformPrincipalRepository.PlatformUserRecord;
import cn.studykid.multiagent.ports.security.PlatformPrincipalRepository.DuplicateUserException;
import cn.studykid.multiagent.ports.security.PlatformRoles;

/**
 * 平台用户 / 角色 / 权限管理服务（重规划 §8 · P2 核心功能）。
 *
 * <p><b>授权在执行入口强制</b>：每个写操作先经 {@link PlatformSecurity#require}
 * （默认拒绝、矩阵驱动），再调仓储，最后写 {@code platform_audit_event} ——
 * 与 {@link PlatformTenantService} 同一套纪律。
 *
 * <p>⚠️ <b>以前「角色赋权走带外运维通道」是暂缓实现（P0 契约未落）</b>，
 * 本服务补上后，{@code platform.user.manage} / {@code platform.role.assign} /
 * {@code platform.role.revoke} 三个矩阵预留权限终于有了可达路径。
 * 自举回路防线不变：角色动作走 SoD（授予/撤销超管须超管本人，
 * {@code PlatformAuthorizationServiceImpl} 强制），并叠加自身保护。
 *
 * <p><b>三条纪律</b>：
 * <ol>
 *   <li><b>响应最小化</b> —— 密码哈希绝不回传；摘要回传角色集合便于列表页直显。</li>
 *   <li><b>自身保护</b> —— 禁止对当前登录主体本人执行 状态变更 / 重置密码 /
 *       角色增删（自授权位被改掉会让操作者瞬间失去继续操作的能力，
 *       也让「误把自己降权」无从发现）。查看自身信息不受限。</li>
 *   <li><b>authz_version 递增</b> —— 角色、状态变化都递增被操作者的授权版本，
 *       使在途缓存/上下文按版本失效（撤权传播）。</li>
 * </ol>
 */
@Component
public class PlatformUserManagementService {

    /** 密码最小长度（平台账号手工建号）。 */
    static final int MIN_PASSWORD_LENGTH = 10;

    private final PlatformPrincipalRepository users;
    private final PlatformSecurity security;
    private final PlatformAuditPort audit;

    public PlatformUserManagementService(PlatformPrincipalRepository users,
                                         PlatformSecurity security,
                                         PlatformAuditPort audit) {
        this.users = users;
        this.security = security;
        this.audit = audit;
    }

    // ── 读 ─────────────────────────────────────────────────

    /**
     * 列出全部平台用户并带角色集合（一次批量 join，不做 N+1）。
     *
     * <p>需 {@code platform.user.read}。
     */
    public List<UserDetail> listUsersWithRoles(@NonNull PlatformPrincipal principal) {
        security.require(principal, Namespaces.Platform.USER_READ, null, null);
        Map<String, Set<String>> rolesByUser = users.rolesByUser();
        List<UserDetail> result = new ArrayList<>();
        for (PlatformUserRecord r : users.listUsers()) {
            result.add(new UserDetail(r, rolesByUser.getOrDefault(r.id(), Set.of())));
        }
        return result;
    }

    /** 单个平台用户详情（含角色；需 {@code platform.user.read}）。 */
    public UserDetail getUser(@NonNull PlatformPrincipal principal, @NonNull String id) {
        security.require(principal, Namespaces.Platform.USER_READ, "platform_user", id);
        PlatformUserRecord record = requireUser(id);
        return new UserDetail(record, users.findRoles(id));
    }

    // ── 用户写 ─────────────────────────────────────────────

    /**
     * 创建平台账号（LOCAL 认证源；密码哈希入库，绝不回传）。
     *
     * <p>需 {@code platform.user.manage}；如同时指定初始角色，
     * 每个角色另按赋权动作鉴权（含 SoD）。
     */
    public UserDetail createUser(@NonNull PlatformPrincipal actor,
                                 @NonNull String username,
                                 @Nullable String email,
                                 @Nullable String displayName,
                                 @NonNull String password,
                                 boolean mfaRequired,
                                 @Nullable Set<String> initialRoles) {
        security.require(actor, Namespaces.Platform.USER_MANAGE, "platform_user", null);
        requirePasswordPolicy(password);
        Set<String> roles = validateRoles(initialRoles);
        PlatformUserRecord created;
        try {
            created = users.insertUser(username.trim(), email, displayName,
                    PasswordHasher.hash(password), "LOCAL", mfaRequired);
        } catch (DuplicateUserException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        recordAudit(actor, Namespaces.Platform.USER_MANAGE, "platform_user",
                created.id(), "platform.user.create");
        for (String role : roles) {
            grantRoleInternal(actor, created.id(), role);
        }
        return new UserDetail(created, roles);
    }

    /**
     * 更新用户管理元数据（displayName / email / status / mfaRequired；null = 不变）。
     *
     * <p>需 {@code platform.user.manage}；状态置 DISABLED 递增授权版本，
     * 撤权传播在能力侧生效（平台令牌为无状态 JWT，变更至多在一个令牌周期内生效，
     * 下次登录立即生效）。
     */
    public UserDetail updateUser(@NonNull PlatformPrincipal actor, @NonNull String id,
                                 @Nullable String displayName,
                                 @Nullable String email,
                                 @Nullable String status,
                                 @Nullable Boolean mfaRequired) {
        requireNotSelf(actor, id, "用户变更");
        security.require(actor, Namespaces.Platform.USER_MANAGE, "platform_user", id);
        PlatformUserRecord current = requireUser(id);
        if (status != null && !Set.of("ACTIVE", "DISABLED").contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "status 仅支持 ACTIVE / DISABLED（LOCKED 由安全机制置位）");
        }
        boolean disable = "DISABLED".equals(status);
        PlatformUserRecord updated = users.updateProfile(id, email, displayName, mfaRequired);
        if (disable && "ACTIVE".equals(current.status())) {
            users.setStatus(id, "DISABLED");
        }
        if ("ACTIVE".equals(status) && "DISABLED".equals(current.status())) {
            users.setStatus(id, "ACTIVE");
        }
        recordAudit(actor, Namespaces.Platform.USER_MANAGE, "platform_user", id,
                "platform.user.update");
        return new UserDetail(updated, users.findRoles(id));
    }

    /**
     * 重置密码（新 PBKDF2 哈希入库；需 {@code platform.user.manage}）。
     *
     * <p>⚠️ 不做「旧密码校验」：管理操作语义是管理员置新密码，
     * 不是用户自助改密（后者无端点，重置即覆盖）。
     */
    public void resetPassword(@NonNull PlatformPrincipal actor, @NonNull String id,
                              @NonNull String newPassword) {
        requireNotSelf(actor, id, "重置密码");
        security.require(actor, Namespaces.Platform.USER_MANAGE, "platform_user", id);
        requireUser(id);
        requirePasswordPolicy(newPassword);
        users.updatePasswordHash(id, PasswordHasher.hash(newPassword));
        recordAudit(actor, Namespaces.Platform.USER_MANAGE, "platform_user", id,
                "platform.user.password.reset");
    }

    // ── 角色写 ─────────────────────────────────────────────

    /** 给平台用户授予一个已登记角色（需 {@code platform.role.assign}；超管受 SoD）。 */
    public Set<String> assignRole(@NonNull PlatformPrincipal actor, @NonNull String userId,
                                  @NonNull String roleName) {
        requireNotSelf(actor, userId, "角色授予");
        grantRoleInternal(actor, userId, roleName);
        return users.findRoles(userId);
    }

    /** 撤销平台用户的一个角色（需 {@code platform.role.revoke}；超管受 SoD）。 */
    public Set<String> revokeRole(@NonNull PlatformPrincipal actor, @NonNull String userId,
                                  @NonNull String roleName) {
        requireNotSelf(actor, userId, "角色撤销");
        String role = validRoleName(roleName);
        security.require(actor, Namespaces.Platform.ROLE_REVOKE, "role", role);
        PlatformUserRecord target = requireUser(userId);
        boolean removed = users.revokeRole(userId, role);
        if (removed) {
            // ⚠️ 撤销超管最后一个超管角色 = 平台失去可操作的赋权主体 —— 明确拒绝
            if (PlatformRoles.SUPER_ADMIN.equals(role) && countSuperAdmins() == 0) {
                users.assignRole(userId, role, actor.userId());
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "不能撤销最后一个平台超管的角色（平台将失去赋权能力）");
            }
            users.bumpAuthzVersion(userId);
        }
        recordAudit(actor, Namespaces.Platform.ROLE_REVOKE, "platform_user", target.id(),
                "platform.role.revoke:" + role);
        return users.findRoles(userId);
    }

    // ── 内部 ───────────────────────────────────────────────

    private void grantRoleInternal(@NonNull PlatformPrincipal actor, @NonNull String userId,
                                   @NonNull String roleName) {
        String role = validRoleName(roleName);
        security.require(actor, Namespaces.Platform.ROLE_ASSIGN, "role", role);
        requireUser(userId);
        users.assignRole(userId, role, actor.userId());
        users.bumpAuthzVersion(userId);
        recordAudit(actor, Namespaces.Platform.ROLE_ASSIGN, "platform_user", userId,
                "platform.role.assign:" + role);
    }

    private String validRoleName(@Nullable String roleName) {
        if (roleName == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "role 必填");
        }
        String name = roleName.trim();
        if (!PlatformRoles.ALL.contains(name)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "未知平台角色: " + name);
        }
        return name;
    }

    private Set<String> validateRoles(@Nullable Set<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return Set.of();
        }
        Set<String> validated = new LinkedHashSet<>();
        for (String role : roles) {
            validated.add(validRoleName(role));
        }
        return validated;
    }

    private PlatformUserRecord requireUser(@NonNull String id) {
        return users.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "平台用户不存在"));
    }

    /** 自身保护：管理动作不得作用于操作者本人账号。 */
    private void requireNotSelf(@NonNull PlatformPrincipal actor, @Nullable String targetId,
                                @NonNull String what) {
        if (targetId != null && targetId.equals(actor.userId())) {
            recordAudit(actor, Namespaces.Platform.USER_MANAGE, "platform_user", targetId,
                    "platform.user.self_operation_denied:" + what);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "不能对当前登录账号自身执行" + what + "（自身保护）");
        }
    }

    /** 平台超管数量（撤销最后一员防护用）。 */
    private long countSuperAdmins() {
        return users.rolesByUser().values().stream()
                .filter(roles -> roles.contains(PlatformRoles.SUPER_ADMIN))
                .count();
    }

    private void requirePasswordPolicy(@Nullable String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "密码长度不得少于 " + MIN_PASSWORD_LENGTH + " 位");
        }
    }

    private void recordAudit(@NonNull PlatformPrincipal actor, @NonNull String action,
                             @NonNull String targetType, @Nullable String targetId,
                             @Nullable String detail) {
        audit.record(new PlatformAuditEvent(
                UUID.randomUUID(), actor.userId(), "PLATFORM_USER",
                detail != null ? detail : action,
                targetType, targetId, "ALLOW", detail, null, null, Instant.now()));
    }

    /** 用户详情（含角色集合；无敏感字段）。 */
    public record UserDetail(@NonNull PlatformUserRecord user,
                             @NonNull Set<String> roles) {
    }
}
