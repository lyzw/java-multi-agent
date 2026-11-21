package cn.studykid.multiagent.infrastructure.persistence;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.infrastructure.persistence.mapper.PlatformPrincipalMapper;
import cn.studykid.multiagent.infrastructure.persistence.mapper.PlatformPrincipalMapper.PlatformUserRow;
import cn.studykid.multiagent.ports.security.PlatformPrincipalRepository;

/**
 * PlatformPrincipalRepository MyBatis 实现（platform schema 身份表）。
 *
 * <p>⚠️ Mapper 绑定在 {@code opsSqlSessionFactory}，只有「MyBatis + 平台运维数据源」
 * 双开关同时打开时才生效；否则必须退回 Jdbc 实现。身份表不给 app_agent 授权，
 * 绝不能让 mapper 落到租户工厂的 {@code dataSource} 上。
 */
@Repository
@ConditionalOnExpression("'${persistence.mybatis.enabled:false}'.equals('true') "
        + "&& '${agent.platform-ops.datasource.enabled:false}'.equals('true')")
public class MybatisPlatformPrincipalRepository implements PlatformPrincipalRepository {

    private final PlatformPrincipalMapper mapper;

    public MybatisPlatformPrincipalRepository(@NonNull PlatformPrincipalMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public @NonNull Optional<PlatformUserRecord> findByUsername(@NonNull String username) {
        return Optional.ofNullable(mapper.findByUsername(username)).map(this::toRecord);
    }

    @Override
    public @NonNull Optional<PlatformUserRecord> findById(@NonNull String userId) {
        return Optional.ofNullable(mapper.findById(userId)).map(this::toRecord);
    }

    @Override
    public @NonNull List<PlatformUserRecord> listUsers() {
        return mapper.listUsers().stream().map(this::toRecord).toList();
    }

    @Override
    public @NonNull Set<String> findRoles(@NonNull String userId) {
        return new HashSet<>(mapper.findRoles(userId));
    }

    @Override
    public @NonNull Map<String, Set<String>> rolesByUser() {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        mapper.rolesByUser().forEach(r ->
                out.computeIfAbsent(r.platformUserId(), k -> new HashSet<>()).add(r.roleName()));
        return out;
    }

    @Override
    public @NonNull PlatformUserRecord insertUser(@NonNull String username, @Nullable String email,
                                                    @Nullable String displayName, @NonNull String passwordHash,
                                                    @NonNull String authSource, boolean mfaRequired) {
        PlatformUserRow row = mapper.insertUser(username, email, displayName, passwordHash,
                authSource, mfaRequired);
        return toRecord(row);
    }

    @Override
    public @NonNull PlatformUserRecord updateProfile(@NonNull String userId, @Nullable String email,
                                                       @Nullable String displayName,
                                                       @Nullable Boolean mfaRequired) {
        mapper.updateProfile(userId, email, displayName, mfaRequired);
        return findById(userId).orElseThrow(() -> new IllegalStateException("updateProfile 后查不到: " + userId));
    }

    @Override
    public void setStatus(@NonNull String userId, @NonNull String status) {
        mapper.setStatus(userId, status);
    }

    @Override
    public void updatePasswordHash(@NonNull String userId, @NonNull String passwordHash) {
        mapper.updatePasswordHash(userId, passwordHash);
    }

    @Override
    public boolean assignRole(@NonNull String userId, @NonNull String roleName, @NonNull String grantedBy) {
        return mapper.assignRole(userId, roleName, grantedBy) >= 0
                && mapper.findRoles(userId).contains(roleName);
    }

    @Override
    public boolean revokeRole(@NonNull String userId, @NonNull String roleName) {
        return mapper.revokeRole(userId, roleName) > 0;
    }

    @Override
    public void bumpAuthzVersion(@NonNull String userId) {
        mapper.bumpAuthzVersion(userId);
    }

    private PlatformUserRecord toRecord(PlatformUserRow r) {
        return new PlatformUserRecord(r.id(), r.username(), r.email(), r.displayName(),
                r.status(), r.authSource(), r.mfaRequired(), r.authzVersion(),
                r.lastLoginAt(), r.passwordHash(), r.createdAt(), r.updatedAt());
    }
}
