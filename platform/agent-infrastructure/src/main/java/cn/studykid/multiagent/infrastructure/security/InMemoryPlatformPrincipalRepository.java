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
