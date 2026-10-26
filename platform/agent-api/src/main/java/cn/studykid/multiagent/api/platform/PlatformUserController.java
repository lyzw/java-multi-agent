package cn.studykid.multiagent.api.platform;

import java.util.List;
import java.util.Set;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import cn.studykid.multiagent.ports.security.IdentityDomains;
import cn.studykid.multiagent.ports.security.PlatformPrincipal;
import cn.studykid.multiagent.ports.security.PlatformPrincipalRepository.PlatformUserRecord;

/**
 * 平台用户管理端点（重规划 §8 / §9 · P2 用户/角色/权限管理）。
 *
 * <p>仅暴露平台用户的管理元数据（id/用户名/状态/认证源/角色集合），
 * <b>绝不返回 password_hash</b>。平台账号与租户账号不混用、不互查。
 *
 * <p>写端点（第三十八批起）：
 * <ul>
 *   <li>{@code POST   /users} · 创建（platform.user.manage）</li>
 *   <li>{@code PUT    /users/{id}} · 变更/启停（platform.user.manage）</li>
 *   <li>{@code PUT    /users/{id}/password} · 重置密码（platform.user.manage）</li>
 *   <li>{@code POST   /users/{id}/roles} · 赋角色（platform.role.assign，SoD）</li>
 *   <li>{@code DELETE /users/{id}/roles/{role}} · 撤角色（platform.role.revoke，SoD）</li>
 * </ul>
 * 授权在服务层 {@link PlatformUserManagementService} 执行入口强制；
 * 本类只做请求解析与响应封装。
 */
@RestController
@RequestMapping("/api/platform/v1/users")
public class PlatformUserController {

    private final PlatformUserManagementService service;

    public PlatformUserController(PlatformUserManagementService service) {
        this.service = service;
    }

    /**
     * 列出全部平台用户（需 {@code platform.user.read}），含各用户角色集合。
     */
    @GetMapping
    public List<UserDetailResponse> list(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal) {
        return service.listUsersWithRoles(principal).stream()
                .map(UserDetailResponse::of)
                .toList();
    }

    /** 用户详情（含角色集合）。 */
    @GetMapping("/{id}")
    public ResponseEntity<UserDetailResponse> get(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @PathVariable String id) {
        return ResponseEntity.ok(UserDetailResponse.of(service.getUser(principal, id)));
    }

    /** 创建平台账号（LOCAL 认证源）。 */
    @PostMapping
    public ResponseEntity<UserDetailResponse> create(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @RequestBody @Nullable CreateUserRequest request) {
        if (request == null || isBlank(request.username()) || isBlank(request.password())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "username 与 password 必填");
        }
        if (request.password().length() < PlatformUserManagementService.MIN_PASSWORD_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "密码长度不得少于 " + PlatformUserManagementService.MIN_PASSWORD_LENGTH + " 位");
        }
        PlatformUserManagementService.UserDetail detail = service.createUser(
                principal, request.username(), request.email(), request.displayName(),
                request.password(), request.mfaRequired() != null && request.mfaRequired(),
                request.roles());
        return ResponseEntity.status(HttpStatus.CREATED).body(UserDetailResponse.of(detail));
    }

    /** 更新用户（displayName / email / status / mfaRequired；null 字段不动）。 */
    @PutMapping("/{id}")
    public ResponseEntity<UserDetailResponse> update(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @PathVariable String id,
            @RequestBody @Nullable UpdateUserRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
        }
        return ResponseEntity.ok(UserDetailResponse.of(service.updateUser(
                principal, id, request.displayName(), request.email(),
                request.status(), request.mfaRequired())));
    }

    /** 重置密码（管理员置新密码）。 */
    @PutMapping("/{id}/password")
    public ResponseEntity<Void> resetPassword(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @PathVariable String id,
            @RequestBody @Nullable PasswordRequest request) {
        if (request == null || isBlank(request.newPassword())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "newPassword 必填");
        }
        service.resetPassword(principal, id, request.newPassword());
        return ResponseEntity.noContent().build();
    }

    /** 给用户授予一个角色（SoD：授予超管须超管本人操作）。 */
    @PostMapping("/{id}/roles")
    public ResponseEntity<UserRolesResponse> assignRole(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @PathVariable String id,
            @RequestBody @Nullable RoleRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(
                new UserRolesResponse(service.assignRole(principal, id, request.role())));
    }

    /** 撤销用户的一个角色（SoD：撤销超管须超管本人操作）。 */
    @DeleteMapping("/{id}/roles/{role}")
    public ResponseEntity<UserRolesResponse> revokeRole(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @PathVariable String id,
            @PathVariable String role) {
        return ResponseEntity.ok(new UserRolesResponse(
                service.revokeRole(principal, id, role)));
    }

    // ── DTO ───────────────────────────────────────────────

    /** 平台用户摘要（无敏感字段；含角色集合供列表直显）。 */
    public record UserSummary(@NonNull String id, @NonNull String username,
                              @Nullable String displayName, @NonNull String status,
                              @NonNull String authSource, boolean mfaRequired,
                              long authzVersion, @Nullable String lastLoginAt,
                              @NonNull Set<String> roles) {
        static UserSummary of(PlatformUserRecord r, Set<String> roles) {
            return new UserSummary(r.id(), r.username(), r.displayName(), r.status(),
                    r.authSource(), r.mfaRequired(), r.authzVersion(),
                    r.lastLoginAt() == null ? null : r.lastLoginAt().toString(), roles);
        }
    }

    /** 用户详情响应（摘要 + 邮箱 + 审计元信息）。 */
    public record UserDetailResponse(
            @NonNull String id, @NonNull String username,
            @Nullable String displayName, @Nullable String email,
            @NonNull String status, @NonNull String authSource,
            boolean mfaRequired, long authzVersion,
            @Nullable String lastLoginAt, @Nullable String createdAt,
            @Nullable String updatedAt, @NonNull Set<String> roles) {
        static UserDetailResponse of(PlatformUserManagementService.UserDetail d) {
            PlatformUserRecord r = d.user();
            return new UserDetailResponse(r.id(), r.username(), r.displayName(), r.email(),
                    r.status(), r.authSource(), r.mfaRequired(), r.authzVersion(),
                    r.lastLoginAt() == null ? null : r.lastLoginAt().toString(),
                    r.createdAt() == null ? null : r.createdAt().toString(),
                    r.updatedAt() == null ? null : r.updatedAt().toString(), d.roles());
        }
    }

    public record UserRolesResponse(@NonNull Set<String> roles) {
    }

    public record CreateUserRequest(@Nullable String username, @Nullable String password,
                                    @Nullable String displayName, @Nullable String email,
                                    @Nullable Boolean mfaRequired,
                                    @Nullable Set<String> roles) {
    }

    public record UpdateUserRequest(@Nullable String displayName, @Nullable String email,
                                    @Nullable String status, @Nullable Boolean mfaRequired) {
    }

    public record PasswordRequest(@Nullable String newPassword) {
    }

    public record RoleRequest(@Nullable String role) {
    }

    private static boolean isBlank(@Nullable String s) {
        return s == null || s.isBlank();
    }
}
