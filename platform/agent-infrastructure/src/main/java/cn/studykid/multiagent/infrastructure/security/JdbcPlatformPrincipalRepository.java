package cn.studykid.multiagent.infrastructure.security;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.ports.security.PlatformPrincipalRepository;
import cn.studykid.multiagent.ports.security.PlatformPrincipalRepository.PlatformUserRecord;

/**
 * 平台身份 JDBC 仓储（重规划 §2.1 / §5.2）。
 *
 * <p><b>与租户侧 {@link PrincipalRepository} 物理隔离</b>：直接访问
 * {@code platform} schema（无 RLS、无 tenant_id），使用平台专属访问路径。
 * 不绑定租户上下文、不调用 {@code TenantSessionBinder}——
 * 平台身份域查询本就不走租户 RLS。
 *
 * <p>所有查询在事务内执行并 {@code rollback()}（只读），避免遗留悬空事务。
 * 密码哈希仅用于本层内部校验，绝不外泄。
 */
public class JdbcPlatformPrincipalRepository implements PlatformPrincipalRepository {

    private final DataSource dataSource;

    public JdbcPlatformPrincipalRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Optional<PlatformUserRecord> findByUsername(@NonNull String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        String sql = SELECT_COLUMNS + " FROM platform.platform_user WHERE username = ?";
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                PlatformUserRecord record = rs.next() ? map(rs) : null;
                conn.rollback();
                return Optional.ofNullable(record);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询平台用户失败", e);
        }
    }

    @Override
    public Optional<PlatformUserRecord> findById(@NonNull String userId) {
        if (userId == null || !UUID_PATTERN.matcher(userId).matches()) {
            return Optional.empty();
        }
        String sql = SELECT_COLUMNS + " FROM platform.platform_user WHERE id = ?::uuid";
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                PlatformUserRecord record = rs.next() ? map(rs) : null;
                conn.rollback();
                return Optional.ofNullable(record);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询平台用户失败", e);
        }
    }

    @Override
    public List<PlatformUserRecord> listUsers() {
        String sql = SELECT_COLUMNS + " FROM platform.platform_user ORDER BY username";
        List<PlatformUserRecord> users = new ArrayList<>();
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                users.add(map(rs));
            }
            conn.rollback();
            return users;
        } catch (SQLException e) {
            throw new IllegalStateException("列出平台用户失败", e);
        }
    }

    @Override
    public Set<String> findRoles(@NonNull String userId) {
        if (userId == null || !UUID_PATTERN.matcher(userId).matches()) {
            return Set.of();
        }
        String sql = """
                SELECT r.name
                FROM platform.platform_user_role ur
                JOIN platform.platform_role r ON r.id = ur.role_id
                WHERE ur.platform_user_id = ?::uuid
                """;
        Set<String> roles = new LinkedHashSet<>();
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    roles.add(rs.getString(1));
                }
            }
            conn.rollback();
            return roles;
        } catch (SQLException e) {
            throw new IllegalStateException("查询平台用户角色失败", e);
        }
    }

    private static final String SELECT_COLUMNS = """
            SELECT id, username, email, display_name, status, auth_source,
                   mfa_required, authz_version, last_login_at, password_hash,
                   created_at, updated_at
            """;

    private PlatformUserRecord map(ResultSet rs) throws SQLException {
        Timestamp lastLogin = rs.getTimestamp("last_login_at");
        Timestamp created = rs.getTimestamp("created_at");
        Timestamp updated = rs.getTimestamp("updated_at");
        return new PlatformUserRecord(
                rs.getString("id"),
                rs.getString("username"),
                rs.getString("email"),
                rs.getString("display_name"),
                rs.getString("status"),
                rs.getString("auth_source"),
                rs.getBoolean("mfa_required"),
                rs.getLong("authz_version"),
                lastLogin == null ? null : lastLogin.toInstant(),
                rs.getString("password_hash"),
                created == null ? null : created.toInstant(),
                updated == null ? null : updated.toInstant());
    }

    private Connection open() throws SQLException {
        Connection conn = dataSource.getConnection();
        conn.setAutoCommit(false);
        return conn;
    }

    private static final java.util.regex.Pattern UUID_PATTERN =
            java.util.regex.Pattern.compile(
                    "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}"
                            + "-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
}
