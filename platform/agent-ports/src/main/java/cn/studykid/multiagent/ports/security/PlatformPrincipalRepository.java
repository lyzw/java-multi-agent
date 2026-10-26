package cn.studykid.multiagent.ports.security;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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
     * 批量取平台用户的角色集合（一次 join，列表页直显用，避免 N+1）。
     *
     * <p>key = 用户 id；没有角色的用户不在结果里（调用方按 {@link java.util.Map#getOrDefault} 兜底）。
     */
    Map<String, Set<String>> rolesByUser();

    // ── 写（平台用户/角色管理 · 重规划 §8）────────────────────
    //
    // ⚠️ 写路径与租户侧不同：platform schema 无 RLS，授权在 API 服务层强制
    //    （PlatformSecurity.require），仓储只承担数据完整性（唯一约束/外键）。
    // ⚠️ passwordHash 由调用方生成（PBKDF2），仓储不接触明文。

    /**
     * 创建平台用户（LOCAL 认证源；用户名/邮箱由 DB 唯一约束兜底）。
     *
     * @return 创建后的记录
     * @throws DuplicateUserException 用户名或邮箱已存在
     * @throws IllegalArgumentException 用户名为空（服务层已校验，此处兜底）
     */
    PlatformUserRecord insertUser(@NonNull String username,
                                  @Nullable String email,
                                  @Nullable String displayName,
                                  @NonNull String passwordHash,
                                  @NonNull String authSource,
                                  boolean mfaRequired);

    /**
     * 更新用户管理元数据（email/displayName/mfaRequired；{@code null} = 不变）。
     *
     * @return 更新后的记录
     * @throws java.util.NoSuchElementException 用户不存在
     */
    PlatformUserRecord updateProfile(@NonNull String userId,
                                     @Nullable String email,
                                     @Nullable String displayName,
                                     @Nullable Boolean mfaRequired);

    /**
     * 生命周期状态置位（ACTIVE / DISABLED）并递增授权版本（撤权传播）。
     *
     * @throws java.util.NoSuchElementException 用户不存在
     */
    void setStatus(@NonNull String userId, @NonNull String status);

    /** 覆盖密码哈希（调用方已产 PBKDF2 哈希）。 */
    void updatePasswordHash(@NonNull String userId, @NonNull String passwordHash);

    /**
     * 登记角色绑定（幂等：已存在幂等成功）。
     *
     * @param grantedBy 赋权操作者（审计追溯）
     * @return true = 本次新登记；false = 原已存在
     */
    boolean assignRole(@NonNull String userId, @NonNull String roleName,
                       @NonNull String grantedBy);

    /**
     * 撤销角色绑定。
     *
     * @return true = 本次有删除；false = 原本就没有该绑定
     */
    boolean revokeRole(@NonNull String userId, @NonNull String roleName);

    /** 递增授权版本（角色/状态变更后调用，撤权传播）。 */
    void bumpAuthzVersion(@NonNull String userId);

    /** 用户名或邮箱已被占用。 */
    class DuplicateUserException extends RuntimeException {
        public DuplicateUserException(@NonNull String message) {
            super(message);
        }
    }

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
