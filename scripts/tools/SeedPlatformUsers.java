import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import cn.studykid.multiagent.infrastructure.security.PrincipalRepository.PasswordHasher;

/**
 * 平台账号带外安装器（admin-console 登录用）。
 *
 * <p>口令哈希一律由运行中的服务端同一实现（{@code PasswordHasher}）现算，
 * 仓库里不留哈希、不留口令：口令只从环境变量 {@code SEED_ACCOUNT_PASSWORD} 读取，
 * 经 PreparedStatement 落库，绝不拼进 SQL 文本（避免 shell 特殊字符把写入静默吞掉）。
 *
 * <p>用法（单文件源码启动，classpath 需含 pgjdbc 与 agent-infrastructure 编译产物）：
 * <pre>
 *   SEED_DB_URL=... SEED_DB_USER=... SEED_DB_PASSWORD=... SEED_ACCOUNT_PASSWORD=... \
 *   java -cp "$PGJAR:$CLASSES" scripts/tools/SeedPlatformUsers.java            # dry-run（回滚）
 *   ... java -cp ... scripts/tools/SeedPlatformUsers.java --commit              # 真正写入
 * </pre>
 *
 * <p>幂等：账号按 username 唯一键 DO NOTHING，角色绑定按 (user, role) 主键 DO NOTHING；
 * 已存在的账号只报告、不改写口令（改口令走 --reset）。
 */
public final class SeedPlatformUsers {

    /** 安装清册：用户名 → 平台角色（与 PlatformRoles 同源，1:1 各司其职）。 */
    private static final Map<String, String> ACCOUNTS = new LinkedHashMap<>();
    private static final Map<String, String> DISPLAY = new LinkedHashMap<>();

    static {
        ACCOUNTS.put("plat-superadmin", "PLATFORM_SUPER_ADMIN");
        ACCOUNTS.put("plat-lifecycle", "TENANT_LIFECYCLE_ADMIN");
        ACCOUNTS.put("plat-resource", "PLATFORM_RESOURCE_ADMIN");
        ACCOUNTS.put("plat-security", "PLATFORM_SECURITY_ADMIN");
        ACCOUNTS.put("plat-auditor", "PLATFORM_AUDITOR");
        ACCOUNTS.put("plat-support", "PLATFORM_SUPPORT");
        DISPLAY.put("plat-superadmin", "平台超管（开发）");
        DISPLAY.put("plat-lifecycle", "租户生命周期管理员（开发）");
        DISPLAY.put("plat-resource", "平台资源管理员（开发）");
        DISPLAY.put("plat-security", "平台安全管理员（开发）");
        DISPLAY.put("plat-auditor", "平台审计员（开发）");
        DISPLAY.put("plat-support", "平台支持（开发）");
    }

    private static final String NIL_UUID = "00000000-0000-0000-0000-000000000000";

    public static void main(String[] args) throws Exception {
        String url = env("SEED_DB_URL");
        String dbUser = env("SEED_DB_USER");
        String dbPassword = env("SEED_DB_PASSWORD");
        String accountPassword = env("SEED_ACCOUNT_PASSWORD");
        boolean commit = List.of(args).contains("--commit");
        boolean reset = List.of(args).contains("--reset");
        String grantor = argValue(args, "--grantor", "e2eadmin");

        if (accountPassword.isEmpty()) {
            System.err.println("SEED_ACCOUNT_PASSWORD 未设置（口令只走环境变量，不接受命令行参数）。");
            System.exit(2);
        }
        if (accountPassword.length() < 8) {
            System.err.println("口令长度不足 8，拒绝安装。");
            System.exit(2);
        }

        try (Connection c = DriverManager.getConnection(url, orNull(dbUser), orNull(dbPassword))) {
            c.setAutoCommit(false);

            UUID grantorId = resolveGrantor(c, grantor);
            System.out.println("授权人（granted_by）: " + grantor + " → " + grantorId);
            System.out.println(commit ? "模式: COMMIT" : "模式: DRY-RUN（结束回滚）");
            System.out.println();

            String hash = PasswordHasher.hash(accountPassword);

            for (Map.Entry<String, String> e : ACCOUNTS.entrySet()) {
                String username = e.getKey();
                String roleName = e.getValue();
                UUID userId = insertUser(c, username, displayName(username), hash, reset);
                bindRole(c, userId, roleName, grantorId);
                verify(c, username, accountPassword);
            }

            System.out.println();
            System.out.println("=== 安装结果 ===");
            report(c);
            if (commit) {
                c.commit();
                System.out.println("\nCOMMITTED");
            } else {
                c.rollback();
                System.out.println("\nROLLED BACK（dry-run，未写入；加 --commit 生效）");
            }
        }
    }

    /** 返回用户 id；新建时 true，命中已有行时 false（默认不改写既有口令）。 */
    private static UUID insertUser(Connection c, String username, String displayName,
                                   String hash, boolean reset) throws SQLException {
        String sql = """
                INSERT INTO platform.platform_user
                      (username, email, display_name, status, auth_source, password_hash, mfa_required)
                VALUES (?, ?, ?, 'ACTIVE', 'LOCAL', ?, false)
                ON CONFLICT (username) DO UPDATE
                   SET password_hash = CASE WHEN ? THEN EXCLUDED.password_hash
                                            ELSE platform.platform_user.password_hash END
                RETURNING id, (xmax = 0) AS inserted
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, username + "@example.com");
            ps.setString(3, displayName);
            ps.setString(4, hash);
            ps.setBoolean(5, reset);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                UUID id = rs.getObject("id", UUID.class);
                // ⚠️ 文案必须与实际行为一致：--reset 命中已有账号时会改写口令，
                // 旧文案「跳过口令」让人误以为没生效，排查登录问题时白绕一圈。
                System.out.printf("  %-16s %s%n", username,
                        rs.getBoolean("inserted") ? "已创建"
                                : (reset ? "已存在（口令已重置）" : "已存在（口令未改动）"));
                return id;
            }
        }
    }

    private static void bindRole(Connection c, UUID userId, String roleName, UUID grantorId)
            throws SQLException {
        String sql = """
                INSERT INTO platform.platform_user_role (platform_user_id, role_id, granted_by)
                SELECT ?, r.id, ? FROM platform.platform_role r WHERE r.name = ?
                ON CONFLICT (platform_user_id, role_id) DO NOTHING
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, userId);
            ps.setObject(2, grantorId);
            ps.setString(3, roleName);
            int n = ps.executeUpdate();
            System.out.printf("      角色 %-25s %s%n", roleName, n == 1 ? "已绑定" : "已绑定（幂等命中）");
        }
    }

    /** 读回库里存的哈希并用同一实现验一遍：把「登录 401」提前变成「装完就知道能不能登」。 */
    private static void verify(Connection c, String username, String rawPassword) throws SQLException {
        String sql = "SELECT password_hash FROM platform.platform_user WHERE username = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("读不回 " + username);
                }
                String stored = rs.getString(1);
                if (!PasswordHasher.verify(rawPassword, stored)) {
                    throw new IllegalStateException("口令校验不通过：" + username);
                }
            }
        }
    }

    private static UUID resolveGrantor(Connection c, String username) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id FROM platform.platform_user WHERE username = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getObject("id", UUID.class);
                }
            }
        }
        System.out.println("  ⚠️ 授权人 " + username + " 不存在，granted_by 记为 nil（可事后按台账更正）");
        return UUID.fromString(NIL_UUID);
    }

    private static void report(Connection c) throws SQLException {
        String sql = """
                SELECT u.username, r.name AS role, u.status, u.auth_source
                FROM platform.platform_user u
                JOIN platform.platform_user_role ur ON ur.platform_user_id = u.id
                JOIN platform.platform_role r ON r.id = ur.role_id
                WHERE u.username LIKE 'plat-%'
                ORDER BY u.username, r.name
                """;
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                System.out.printf("  %-16s %-25s %s/%s%n",
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4));
            }
        }
    }

    private static String displayName(String username) {
        return DISPLAY.getOrDefault(username, username);
    }

    private static String env(String key) {
        String v = System.getenv(key);
        return v == null ? "" : v.trim();
    }

    private static String orNull(String v) {
        return v.isEmpty() ? null : v;
    }

    private static String argValue(String[] args, String flag, String def) {
        for (int i = 0; i + 1 < args.length; i++) {
            if (flag.equals(args[i])) {
                return args[i + 1];
            }
        }
        return def;
    }
}
