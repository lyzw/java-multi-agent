package cn.studykid.multiagent.infrastructure.security;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.ports.security.PlatformPrincipalRepository;
import cn.studykid.multiagent.ports.security.PlatformPrincipalRepository.PlatformUserRecord;

/**
 * 内存版平台身份仓储（主要用于单测与 standalone 演示）。
 *
 * <p>行为与 {@link JdbcPlatformPrincipalRepository} 对齐，但不访问数据库。
 * 生产实现须替换为 JDBC 版本（接口保持不变）。
 */
public class InMemoryPlatformPrincipalRepository implements PlatformPrincipalRepository {

    private final Map<String, PlatformUserRecord> byId = new LinkedHashMap<>();
    private final Map<String, String> idByUsername = new LinkedHashMap<>();
    private final Map<String, Set<String>> rolesById = new LinkedHashMap<>();

    /** 写入一个平台用户及其角色（测试/初始化用）。 */
    public void putUser(@NonNull PlatformUserRecord user, @NonNull Set<String> roles) {
        byId.put(user.id(), user);
        idByUsername.put(user.username(), user.id());
        rolesById.put(user.id(), Set.copyOf(roles));
    }

    @Override
    public Optional<PlatformUserRecord> findByUsername(@NonNull String username) {
        String id = idByUsername.get(username);
        return id == null ? Optional.empty() : Optional.of(byId.get(id));
    }

    @Override
    public Optional<PlatformUserRecord> findById(@NonNull String userId) {
        return Optional.ofNullable(byId.get(userId));
    }

    @Override
    public List<PlatformUserRecord> listUsers() {
        return new ArrayList<>(byId.values());
    }

    @Override
    public Set<String> findRoles(@NonNull String userId) {
        return rolesById.getOrDefault(userId, Set.of());
    }

    @Override
    public Map<String, Set<String>> rolesByUser() {
        // 拷贝防外泄内部结构
        Map<String, Set<String>> copy = new LinkedHashMap<>();
        rolesById.forEach(copy::put);
        return copy;
    }

    @Override
    public PlatformUserRecord insertUser(@NonNull String username,
                                         @org.jspecify.annotations.Nullable String email,
                                         @org.jspecify.annotations.Nullable String displayName,
                                         @NonNull String passwordHash,
                                         @NonNull String authSource,
                                         boolean mfaRequired) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        // 唯一约束的内存对应物
        for (PlatformUserRecord r : byId.values()) {
            if (r.username().equals(username)) {
                throw new DuplicateUserException("用户名已存在: " + username);
            }
            if (email != null && email.equals(r.email())) {
                throw new DuplicateUserException("邮箱已被占用: " + email);
            }
        }
        Instant now = Instant.now();
        PlatformUserRecord record = new PlatformUserRecord(
                java.util.UUID.randomUUID().toString(), username, email, displayName,
                "ACTIVE", authSource, mfaRequired, 1L, null, passwordHash, now, now);
        byId.put(record.id(), record);
        idByUsername.put(username, record.id());
        return record;
    }

    @Override
    public PlatformUserRecord updateProfile(@NonNull String userId,
                                            @org.jspecify.annotations.Nullable String email,
                                            @org.jspecify.annotations.Nullable String displayName,
                                            @org.jspecify.annotations.Nullable Boolean mfaRequired) {
        PlatformUserRecord cur = requireExisting(userId);
        if (email != null && !email.isBlank()) {
            for (PlatformUserRecord r : byId.values()) {
                if (!r.id().equals(userId) && email.equals(r.email())) {
                    throw new DuplicateUserException("邮箱已被占用: " + email);
                }
            }
        }
        PlatformUserRecord next = new PlatformUserRecord(cur.id(), cur.username(),
                email != null ? email : cur.email(),
                displayName != null ? displayName : cur.displayName(),
                cur.status(), cur.authSource(),
                mfaRequired != null ? mfaRequired : cur.mfaRequired(),
                cur.authzVersion(), cur.lastLoginAt(), cur.passwordHash(),
                cur.createdAt(), Instant.now());
        byId.put(userId, next);
        return next;
    }

    @Override
    public void setStatus(@NonNull String userId, @NonNull String status) {
        PlatformUserRecord cur = requireExisting(userId);
        byId.put(userId, withAuthz(withStatusAndTime(cur, status), cur.authzVersion() + 1));
    }

    @Override
    public void updatePasswordHash(@NonNull String userId, @NonNull String passwordHash) {
        PlatformUserRecord cur = requireExisting(userId);
        byId.put(userId, new PlatformUserRecord(cur.id(), cur.username(), cur.email(),
                cur.displayName(), cur.status(), cur.authSource(), cur.mfaRequired(),
                cur.authzVersion(), cur.lastLoginAt(), passwordHash, cur.createdAt(),
                Instant.now()));
    }

    @Override
    public boolean assignRole(@NonNull String userId, @NonNull String roleName,
                              @NonNull String grantedBy) {
        requireExisting(userId);
        Set<String> current = new java.util.LinkedHashSet<>(rolesById.getOrDefault(userId, Set.of()));
        boolean added = current.add(roleName);
        rolesById.put(userId, java.util.Collections.unmodifiableSet(current));
        return added;
    }

    @Override
    public boolean revokeRole(@NonNull String userId, @NonNull String roleName) {
        requireExisting(userId);
        Set<String> current = new java.util.LinkedHashSet<>(rolesById.getOrDefault(userId, Set.of()));
        boolean removed = current.remove(roleName);
        rolesById.put(userId, java.util.Collections.unmodifiableSet(current));
        return removed;
    }

    @Override
    public void bumpAuthzVersion(@NonNull String userId) {
        PlatformUserRecord cur = requireExisting(userId);
        byId.put(userId, withAuthz(cur, cur.authzVersion() + 1));
    }

    private PlatformUserRecord requireExisting(@NonNull String userId) {
        PlatformUserRecord cur = byId.get(userId);
        if (cur == null) {
            throw new java.util.NoSuchElementException("平台用户不存在: " + userId);
        }
        return cur;
    }

    private static PlatformUserRecord withStatusAndTime(PlatformUserRecord r, String status) {
        return new PlatformUserRecord(r.id(), r.username(), r.email(), r.displayName(),
                status, r.authSource(), r.mfaRequired(), r.authzVersion(), r.lastLoginAt(),
                r.passwordHash(), r.createdAt(), Instant.now());
    }

    private static PlatformUserRecord withAuthz(PlatformUserRecord r, long version) {
        return new PlatformUserRecord(r.id(), r.username(), r.email(), r.displayName(),
                r.status(), r.authSource(), r.mfaRequired(), version, r.lastLoginAt(),
                r.passwordHash(), r.createdAt(), r.updatedAt());
    }

    /** 便捷构造器：生成一个带 PBKDF2 哈希的测试用户记录。 */
    public static PlatformUserRecord testUser(String id, String username, String rawPassword,
                                              String status, boolean mfaRequired,
                                              long authzVersion) {
        String hash = PrincipalRepository.PasswordHasher.hash(rawPassword);
        return new PlatformUserRecord(id, username, null, username, status,
                "LOCAL", mfaRequired, authzVersion, Instant.EPOCH, hash,
                Instant.EPOCH, Instant.EPOCH);
    }
}
