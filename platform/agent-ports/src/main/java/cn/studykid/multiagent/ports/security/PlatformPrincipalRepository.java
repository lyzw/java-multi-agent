package cn.studykid.multiagent.ports.security;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 平台身份仓储（重规划 §2.1 / §5.2）。
 *
 * <p>只访问 {@code platform} schema 的平台身份表（<b>无 RLS、无 tenant_id</b>），
 * 与租户侧 {@code PrincipalRepository}（访问带 RLS 的租户表）物理隔离。
 * 平台账号与租户账号不依靠邮箱唯一性建立自动关联。
 *
 * <p>⚠️ 密码哈希<b>绝不可</b>出现在任何返回路径（含日志与 API 响应）。
 * {@link PlatformUserRecord#passwordHash()} 仅用于本层内部校验。
 */
public interface PlatformPrincipalRepository {

    /** 按登录名查平台用户。不存在返回 {@link Optional#empty()}。 */
    Optional<PlatformUserRecord> findByUsername(@NonNull String username);

    /** 按 ID 查平台用户。 */
    Optional<PlatformUserRecord> findById(@NonNull String userId);

    /** 列出全部平台用户（不含密码哈希）。 */
    List<PlatformUserRecord> listUsers();

    /** 查平台用户的角色集合（来自 {@code platform.platform_user_role}）。 */
    Set<String> findRoles(@NonNull String userId);

    /**
     * 平台用户记录。
     *
     * <p>⚠️ {@code passwordHash} 仅用于本层校验，record 不覆写 toString，避免误打印。
     */
    record PlatformUserRecord(
            @NonNull String id,
            @NonNull String username,
            @Nullable String email,
            @Nullable String displayName,
            @NonNull String status,
            @NonNull String authSource,
            boolean mfaRequired,
            long authzVersion,
            @Nullable Instant lastLoginAt,
            @NonNull String passwordHash,
            @Nullable Instant createdAt,
            @Nullable Instant updatedAt) {
    }
}
