package cn.studykid.multiagent.infrastructure.security;

import cn.studykid.multiagent.ports.security.PrincipalStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TenantSessionBinder;

/**
 * 账号存储与密码校验（D-001 自建认证）。
 *
 * <p><b>⚠️ 本类承载本平台最敏感的凭据逻辑</b>，三点必须谨慎：
 * <ol>
 *   <li><b>恒定耗时</b> —— 账号不存在时也必须跑一次哈希验证，
 *       否则攻击者可通过响应时间差异枚举有效账号（设计文档 §6）。
 *   <li><b>RLS 事务铁律</b> —— 所有查询必须经 {@code tenantConnection}，
 *       否则 {@code set_config(is_local=true)} 立即失效（本项目踩过 6 处）。
 *   <li><b>密码哈希绝不出现在任何返回路径</b>，包括日志与异常消息。
 * </ol>
 */
public class PrincipalRepository implements PrincipalStore {

    private static final Logger log = LoggerFactory.getLogger(PrincipalRepository.class);

    /**
     * 「账号不存在」时用于消耗等量时间的假哈希。
     *
     * <p>⚠️ <b>这是防枚举的关键</b>：若此处直接返回，「不存在」比
     * 「密码错误」快数十毫秒（Argon2id 单次约 50–100ms），
     * 攻击者据此即可枚举出哪些账号真实存在。
     * 这里的值是一个<b>真实生成的 Argon2id 哈希</b>（不是格式串），
     * 保证校验路径完全一致。
     */
    private static final String DUMMY_HASH = generateDummyHash();

    private final DataSource dataSource;

    public PrincipalRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    // ── 查询 ────────────────────────────────────────────────────

    /**
     * 按登录名查账号。
     *
     * @return 不存在时返回 {@link Optional#empty()}
     */
    public Optional<PrincipalRecord> findByLogin(String tenantId, String loginName) {
        // 校验 tenantId 格式：非法 UUID 会让 SQL 抛异常并返回 500。
        // 后果有二：错误分类错误，且 500 与其他失败的 401 不同，
        // 构成「该租户不存在」的探测通道。故 fail-closed 返回空。
        if (tenantId == null || !UUID_PATTERN.matcher(tenantId).matches()) {
            return Optional.empty();
        }
        if (loginName == null || loginName.isBlank()) {
            return Optional.empty();
        }

        String sql = """
                SELECT principal_id, tenant_id, login_name, password_hash, display_name,
                       status, failed_attempts, locked_until, authz_version
                FROM principal
                WHERE tenant_id = ?::uuid AND login_name = ?
                """;
        try (Connection conn = tenantConnection(tenantId)) {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, tenantId);
                ps.setString(2, loginName);
                try (ResultSet rs = ps.executeQuery()) {
                    PrincipalRecord record = rs.next() ? map(rs) : null;
                    conn.rollback();
                    return Optional.ofNullable(record);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询账号失败", e);
        }
    }

    /**
     * 租户/主体 ID 格式（与数据库 uuid 列类型一致）。
     */
    private static final java.util.regex.Pattern UUID_PATTERN =
            java.util.regex.Pattern.compile(
                    "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}"
                            + "-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /** 校验密码。
     *
     * @return 正确返回 {@code true}；<b>账号不存在也返回 {@code false}</b>
     *         —— 调用方<b>不得</b>据此区分「不存在」与「密码错」
     */
    public boolean verifyPassword(PrincipalRecord record, String rawPassword, Instant now) {
        String hash = record == null ? DUMMY_HASH : record.passwordHash();
        // 无条件执行校验：账号不存在时同样消耗时间
        boolean matched = PasswordHasher.verify(rawPassword, hash);
        if (record == null) {
            return false;
        }
        if (!matched) {
            return false;
        }
        // 已锁定则即使密码正确也拒绝 —— 否则锁定形同虚设
        return !isLocked(record, now);
    }

    // ── 防爆破 ──────────────────────────────────────────────────

    /**
     * 账号是否处于锁定窗口。
     *
     * <p>⚠️ 锁定判定只在<b>登录路径</b>使用。已签发令牌不受影响 ——
     * 这正是设计文档 §7 限制 4「无令牌撤销列表」的已知后果，
     * 靠 access token 短时效（15 分钟）限制暴露窗口。
     */
    public static boolean isLocked(PrincipalRecord record, Instant now) {
        return record.lockedUntil() != null && now.isBefore(record.lockedUntil());
    }

    /**
     * 记录一次登录失败，达阈值则锁定。
     *
     * <p>⚠️ 计数<b>持久化到数据库</b>而非内存：内存计数在重启后归零，
     * 攻击者只需等重启即可绕过限流。
     */
    public void recordFailure(PrincipalRecord record, Instant now,
                              int maxAttempts, java.time.Duration lockDuration) {
        int attempts = record.failedAttempts() + 1;
        boolean shouldLock = attempts >= maxAttempts;

        String sql = shouldLock
                ? """
                  UPDATE principal
                  SET failed_attempts = 0,
                      locked_until = ?,
                      status = CASE WHEN status = 'ACTIVE' THEN 'LOCKED' ELSE status END,
                      updated_at = now()
                  WHERE tenant_id = ?::uuid AND principal_id = ?::uuid
                  """
                : """
                  UPDATE principal
                  SET failed_attempts = ?, updated_at = now()
                  WHERE tenant_id = ?::uuid AND principal_id = ?::uuid
                  """;

        try (Connection conn = tenantConnection(record.tenantId())) {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                if (shouldLock) {
                    ps.setTimestamp(1, Timestamp.from(now.plus(lockDuration)));
                } else {
                    ps.setInt(1, attempts);
                }
                // ⚠️ 两条 SQL 的占位符**排布相同**（1=值，2=租户，3=主体），
                //    此前按分支写成 2/3 与 3/4，非锁定分支多绑了一位 →
                //    PSQLException「参数数值多于参数占位元 #4」，
                //    又被下面的 catch 吞成一句 WARN：
                //    于是**每一次普通密码错误都不增计数，锁定阈值永远达不到**（防爆破失效）。
                ps.setString(2, record.tenantId());
                ps.setString(3, record.principalId());
                ps.executeUpdate();
            }
            conn.commit();
        } catch (SQLException e) {
            // ⚠️ 记录失败本身失败**不应放行登录**，但也不能让登录端点崩掉。
            //    此处只记日志：攻击者可通过制造 DB 压力让计数不准，
            //    属于已知残余风险（设计文档 §7 限制 6 的前置问题）。
            //    ⚠️ 必须带上异常原文：只打「出错」不打 cause，
            //    参数绑定这类**编码错误**会被伪装成「DB 压力」，永不被发现。
            log.warn("记录登录失败计数时出错: loginName={}, cause={}",
                    record.loginName(), e.toString());
        }
    }

    /** 登录成功：清零失败计数、解锁。 */
    public void recordSuccess(PrincipalRecord record, Instant now) {
        String sql = """
                UPDATE principal
                SET failed_attempts = 0,
                    locked_until = NULL,
                    status = CASE WHEN status = 'LOCKED' THEN 'ACTIVE' ELSE status END,
                    updated_at = ?
                WHERE tenant_id = ?::uuid AND principal_id = ?::uuid
                """;
        try (Connection conn = tenantConnection(record.tenantId())) {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setTimestamp(1, Timestamp.from(now));
                ps.setString(2, record.tenantId());
                ps.setString(3, record.principalId());
                ps.executeUpdate();
            }
            conn.commit();
        } catch (SQLException e) {
            log.warn("重置登录计数时出错: loginName={}", record.loginName());
        }
    }

    /**
     * 撤销授权：递增 {@code authz_version}。
     *
     * <p>已签发令牌携带旧版本号，执行时重新授权会识别并拒绝
     * （P0-D03 §4 红线 9）。
     */
    public void revokeAuthorization(PrincipalRecord record, Instant now) {
        String sql = """
                UPDATE principal
                SET authz_version = authz_version + 1, updated_at = ?
                WHERE tenant_id = ?::uuid AND principal_id = ?::uuid
                """;
        try (Connection conn = tenantConnection(record.tenantId())) {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setTimestamp(1, Timestamp.from(now));
                ps.setString(2, record.tenantId());
                ps.setString(3, record.principalId());
                ps.executeUpdate();
            }
            conn.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("撤销授权失败", e);
        }
    }

    /**
     * 查账号的角色集合。
     *
     * <p>⚠️ 这是「防止令牌自封管理员」的权威来源：JWT 里的 roles 只是声明，
     * <b>实际生效的角色以本查询结果为准</b>。若不查库，攻击者自签一个
     * 带 {@code TENANT_ADMIN} 的令牌即可提权。
     */
    public Set<String> findRoles(String tenantId, String principalId) {
        if (tenantId == null || principalId == null
                || !UUID_PATTERN.matcher(tenantId).matches()
                || !UUID_PATTERN.matcher(principalId).matches()) {
            return java.util.Collections.emptySet();
        }
        String sql = """
                SELECT role FROM principal_role
                WHERE tenant_id = ?::uuid AND principal_id = ?::uuid
                """;
        Set<String> roles = new java.util.LinkedHashSet<>();
        try (Connection conn = tenantConnection(tenantId)) {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, tenantId);
                ps.setString(2, principalId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        roles.add(rs.getString(1));
                    }
                }
            }
            conn.rollback();
            return roles;
        } catch (SQLException e) {
            throw new IllegalStateException("查询账号角色失败", e);
        }
    }

    // ── 内部 ────────────────────────────────────────────────────

    /**
     * 已开启事务且已绑定租户上下文的连接。
     *
     * <p>⚠️ 两处都不可省：{@code set_config(..., is_local=true)} 的
     * 事务局部语义<b>要求先开启事务</b>（自动提交模式下立即失效）。
     */
    private Connection tenantConnection(String tenantId) throws SQLException {
        Connection conn = dataSource.getConnection();
        conn.setAutoCommit(false);
        TenantSessionBinder.bind(conn,
                new TenantContext(tenantId, "auth", Set.of(), 0L));
        return conn;
    }

    private PrincipalRecord map(ResultSet rs) throws SQLException {
        Timestamp lockedUntil = rs.getTimestamp("locked_until");
        return new PrincipalRecord(
                rs.getString("principal_id"),
                rs.getString("tenant_id"),
                rs.getString("login_name"),
                rs.getString("password_hash"),
                rs.getString("display_name"),
                rs.getString("status"),
                rs.getInt("failed_attempts"),
                lockedUntil == null ? null : lockedUntil.toInstant(),
                rs.getLong("authz_version"));
    }

    private static String generateDummyHash() {
        try {
            return PasswordHasher.hash("dummy-password-for-timing-equalization");
        } catch (RuntimeException e) {
            throw new IllegalStateException("生成防枚举占位哈希失败", e);
        }
    }

    public static void main(String[] args) {
        System.out.println(PasswordHasher.hash("123456"));
    }
}