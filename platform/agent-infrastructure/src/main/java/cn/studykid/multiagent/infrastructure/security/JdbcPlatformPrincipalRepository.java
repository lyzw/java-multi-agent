package cn.studykid.multiagent.infrastructure.security;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.ports.security.PlatformPrincipalRepository;
import cn.studykid.multiagent.ports.security.PlatformPrincipalRepository.DuplicateUserException;
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

    @Override
    public Map<String, Set<String>> rolesByUser() {
        String sql = """
                SELECT ur.platform_user_id, r.name
                FROM platform.platform_user_role ur
                JOIN platform.platform_role r ON r.id = ur.role_id
                ORDER BY ur.platform_user_id, r.name
                """;
        Map<String, Set<String>> byUser = new LinkedHashMap<>();
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                byUser.computeIfAbsent(rs.getString(1), k -> new LinkedHashSet<>())
                        .add(rs.getString(2));
            }
            conn.rollback();
            return byUser;
        } catch (SQLException e) {
            throw new IllegalStateException("批量查询平台用户角色失败", e);
        }
    }

    // ── 写（平台用户/角色管理 · 重规划 §8）────────────────────────
    //
    // ⚠️ platform schema 无 RLS：授权在 API 服务层强制，本层保证数据完整性。
    //    写操作均显式事务 + 异常映射：23505 唯一冲突 → DuplicateUserException；
    //    外键违规 → IllegalArgumentException（调用方校验遗漏的兜底）。

    @Override
    public PlatformUserRecord insertUser(@NonNull String username,
                                         @Nullable String email,
                                         @Nullable String displayName,
                                         @NonNull String passwordHash,
                                         @NonNull String authSource,
                                         boolean mfaRequired) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        String sql = """
                INSERT INTO platform.platform_user
                    (username, email, display_name, status, auth_source,
                     password_hash, mfa_required, authz_version)
                VALUES (?, ?, ?, 'ACTIVE', ?::platform.auth_source, ?, ?, 1)
                RETURNING id, username, email, display_name, status, auth_source,
                          mfa_required, authz_version, last_login_at, password_hash,
                          created_at, updated_at
                """;
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, blankToNull(email));
            ps.setString(3, blankToNull(displayName));
            ps.setString(4, authSource);
            ps.setString(5, passwordHash);
            ps.setBoolean(6, mfaRequired);
            PlatformUserRecord record = null;
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    record = map(rs);
                }
            }
            conn.commit();
            if (record == null) {
                throw new IllegalStateException("创建平台用户未返回记录");
            }
            return record;
        } catch (SQLException e) {
            if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
                // 23505：表上唯一约束只有 username / email 两个（DDL 定义）
                throw new DuplicateUserException("用户名或邮箱已被占用: " + username);
            }
            throw new IllegalStateException("创建平台用户失败", e);
        }
    }

    @Override
    public PlatformUserRecord updateProfile(@NonNull String userId,
                                            @Nullable String email,
                                            @Nullable String displayName,
                                            @Nullable Boolean mfaRequired) {
        String sql = """
                UPDATE platform.platform_user
                SET email        = COALESCE(?, email),
                    display_name = COALESCE(?, display_name),
                    mfa_required = COALESCE(?, mfa_required),
                    updated_at   = now()
                WHERE id = ?::uuid
                RETURNING id, username, email, display_name, status, auth_source,
                          mfa_required, authz_version, last_login_at, password_hash,
                          created_at, updated_at
                """;
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, blankToNull(email));
            ps.setString(2, blankToNull(displayName));
            ps.setObject(3, mfaRequired);
            ps.setString(4, userId);
            return execUpdate(conn, ps, "更新平台用户失败");
        } catch (SQLException e) {
            if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
                throw new DuplicateUserException("邮箱已被占用: " + email);
            }
            throw new IllegalStateException("更新平台用户失败", e);
        }
    }

    @Override
    public void setStatus(@NonNull String userId, @NonNull String status) {
        String sql = """
                UPDATE platform.platform_user
                SET status = ?::platform.user_status, updated_at = now(),
                    authz_version = authz_version + 1
                WHERE id = ?::uuid
                """;
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setString(2, userId);
            requireAffected(execCounting(conn, ps), "平台用户不存在");
            conn.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("更新平台用户状态失败", e);
        }
    }

    @Override
    public void updatePasswordHash(@NonNull String userId, @NonNull String passwordHash) {
        String sql = """
                UPDATE platform.platform_user
                SET password_hash = ?, updated_at = now(),
                    authz_version = authz_version + 1
                WHERE id = ?::uuid
                """;
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, passwordHash);
            ps.setString(2, userId);
            requireAffected(execCounting(conn, ps), "平台用户不存在");
            conn.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("重置平台用户密码失败", e);
        }
    }

    @Override
    public boolean assignRole(@NonNull String userId, @NonNull String roleName,
                              @NonNull String grantedBy) {
        String sql = """
                INSERT INTO platform.platform_user_role (platform_user_id, role_id, granted_by)
                SELECT ?::uuid, r.id, ?::uuid
                FROM platform.platform_role r
                WHERE r.name = ?
                ON CONFLICT (platform_user_id, role_id) DO NOTHING
                """;
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setString(2, grantedBy);
            ps.setString(3, roleName);
            int inserted = execCounting(conn, ps);
            // ⚠️ 角色不存在时 inserted=0（SELECT 空集）—— 角色名合法性在服务层校验
            if (inserted == 0 && findRoleByName(roleName) == null) {
                throw new IllegalArgumentException("未知平台角色: " + roleName);
            }
            conn.commit();
            return inserted > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("登记平台角色绑定失败", e);
        }
    }

    @Override
    public boolean revokeRole(@NonNull String userId, @NonNull String roleName) {
        String sql = """
                DELETE FROM platform.platform_user_role ur
                USING platform.platform_role r
                WHERE ur.role_id = r.id
                  AND ur.platform_user_id = ?::uuid
                  AND r.name = ?
                """;
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setString(2, roleName);
            int deleted = execCounting(conn, ps);
            conn.commit();
            return deleted > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("撤销平台角色绑定失败", e);
        }
    }

    @Override
    public void bumpAuthzVersion(@NonNull String userId) {
        String sql = """
                UPDATE platform.platform_user
                SET authz_version = authz_version + 1, updated_at = now()
                WHERE id = ?::uuid
                """;
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            requireAffected(execCounting(conn, ps), "平台用户不存在");
            conn.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("递增平台授权版本失败", e);
        }
    }

    /** 查角色 id（assign 空集冲突确认用；仅本类内部）。 */
    private String findRoleByName(String roleName) {
        String sql = "SELECT id FROM platform.platform_role WHERE name = ?";
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, roleName);
            try (ResultSet rs = ps.executeQuery()) {
                String id = rs.next() ? rs.getString(1) : null;
                conn.rollback();
                return id;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询平台角色失败", e);
        }
    }

    /** 执行 RETURNING 型更新并映射结果行（找不到行 → 404 信号）。 */
    private PlatformUserRecord execUpdate(Connection conn, PreparedStatement ps,
                                          String failureMessage) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            PlatformUserRecord record = rs.next() ? map(rs) : null;
            conn.commit();
            if (record == null) {
                throw new java.util.NoSuchElementException("平台用户不存在");
            }
            return record;
        } catch (SQLException e) {
            conn.rollback();
            throw new IllegalStateException(failureMessage, e);
        }
    }

    private int execCounting(Connection conn, PreparedStatement ps) throws SQLException {
        return ps.executeUpdate();
    }

    private static void requireAffected(int affected, String message) {
        if (affected == 0) {
            throw new java.util.NoSuchElementException(message);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static final String UNIQUE_VIOLATION = "23505";

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
