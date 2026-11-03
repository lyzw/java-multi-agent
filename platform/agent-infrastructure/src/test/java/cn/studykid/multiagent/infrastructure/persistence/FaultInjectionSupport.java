package cn.studykid.multiagent.infrastructure.persistence;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;

import javax.sql.DataSource;

import org.assertj.core.api.Assertions;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.testsupport.db.ItTenantProvisioner;

/**
 * P3-D1 故障注入测试支撑（可复用于 D2–D9）。
 *
 * <p><b>设计约束（继承 P2 铁律）</b>：
 * <ul>
 *   <li>所有测试用<b>非特权角色</b> {@code app_agent} {@link #requireNonPrivileged} 守护，
 *       否则超级用户绕过 RLS 会给出「隔离失效」的虚假结论（E-3）。</li>
 *   <li>池扰动用<b>大池 + 短 maxLifetime</b> 制造连接复用与回收，
 *       与既有 {@code TransactionBoundaryIT} 的 {@code maxPoolSize=1} 互补证伪。</li>
 *   <li>{@link #rawAppConnection()} 用于<b>模拟崩溃</b>：Hikari 归还连接时会把
 *       autocommit 重置为 true，PG 下等于<b>提交</b>未提交事务，
 *       会伪造「崩溃后数据仍在」——故崩溃场景必须用裸连接。</li>
 * </ul>
 */
final class FaultInjectionSupport {

    private FaultInjectionSupport() {
    }

    static HikariDataSource appPool(int maxPoolSize) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(System.getenv("IT_PG_URL"));
        cfg.setUsername(System.getenv().getOrDefault("IT_PG_USER", "app_agent"));
        String pwd = System.getenv("IT_PG_PASSWORD");
        if (pwd != null && !pwd.isBlank()) {
            cfg.setPassword(pwd);
        }
        cfg.setMaximumPoolSize(maxPoolSize);
        // ⚠️ 短生命周期强制连接回收，制造「连接复用」扰动
        cfg.setMaxLifetime(1_000);
        return new HikariDataSource(cfg);
    }

    /**
     * 受控清理通道（{@code agent_retention}）连接池 —— 凭据隔离后的<b>唯一</b>合法删除路径。
     *
     * <p>⚠️ 为什么测试必须单独建这条通道：{@code deploy/sql/platform/04_retention_role.sql}
     * 已把 {@code audit_record} / {@code erasure_certificate} 的 DELETE 从应用角色
     * {@code app_agent} 上撤销。若清理测试仍用应用池（历史写法），
     * 得到的不是「清理逻辑对不对」，而是 {@code permission denied} ——
     * 一个指向配置、与业务逻辑无关的红色。
     *
     * <p>⚠️ 反过来，为了消红而把 DELETE 授回 {@code app_agent} 是**用测试倒逼安全降级**：
     * 那条通道一旦存在，任何拿到应用凭据的会话都能抹掉审计。
     *
     * <p>凭据只从环境变量来（{@code IT_RETENTION_USER} / {@code IT_RETENTION_PASSWORD}）。
     */
    static HikariDataSource retentionPool(int maxPoolSize) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(System.getenv("IT_PG_URL"));
        cfg.setUsername(System.getenv().getOrDefault("IT_RETENTION_USER", "agent_retention"));
        String pwd = System.getenv("IT_RETENTION_PASSWORD");
        if (pwd != null && !pwd.isBlank()) {
            cfg.setPassword(pwd);
        }
        cfg.setMaximumPoolSize(Math.max(1, maxPoolSize));
        return new HikariDataSource(cfg);
    }

    /** 裸清理连接（需要「会话级」行为时用，如显式事务内声明 actor）。 */
    static Connection rawRetentionConnection() throws SQLException {
        String url = System.getenv("IT_PG_URL");
        String user = System.getenv().getOrDefault("IT_RETENTION_USER", "agent_retention");
        String pwd = System.getenv("IT_RETENTION_PASSWORD");
        return DriverManager.getConnection(url, user, pwd == null ? "" : pwd);
    }

    static HikariDataSource adminPool() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(System.getenv("IT_PG_URL"));
        cfg.setUsername(System.getenv().getOrDefault("IT_PG_ADMIN_USER", "agent"));
        // ⚠️ 管理员池（造跨租户夹具）：本地 trust 认证无需口令；
        // 密口认证的库（RDS / CI）必须显式给 IT_PG_ADMIN_PASSWORD，
        // 否则连接失败（错误作用域级隔离可能被误读为「失效」）。
        String adminPwd = System.getenv("IT_PG_ADMIN_PASSWORD");
        if (adminPwd != null && !adminPwd.isBlank()) {
            cfg.setPassword(adminPwd);
        }
        cfg.setMaximumPoolSize(1);
        return new HikariDataSource(cfg);
    }

    /** 模拟 JVM/连接崩溃的裸连接：关闭时由 PostgreSQL 服务端回滚未提交事务。 */
    static Connection rawAppConnection() throws SQLException {
        String url = System.getenv("IT_PG_URL");
        String user = System.getenv().getOrDefault("IT_PG_USER", "app_agent");
        String pwd = System.getenv("IT_PG_PASSWORD");
        return DriverManager.getConnection(url, user, pwd == null ? "" : pwd);
    }

    /** E-3 守护：非特权角色运行，否则得到「隔离失效」的虚假结论。 */
    static void requireNonPrivileged(DataSource appPool) throws SQLException {
        try (Connection conn = appPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname = current_user");
             ResultSet rs = ps.executeQuery()) {
            Assertions.assertThat(rs.next()).isTrue();
            boolean superuser = rs.getBoolean(1);
            boolean bypass = rs.getBoolean(2);
            Assertions.assertThat(superuser)
                    .as("集成测试必须用非特权角色（app_agent），超级用户绕过 RLS 会得到虚假结论")
                    .isFalse();
            Assertions.assertThat(bypass)
                    .as("集成测试必须用 NOBYPASSRLS 角色，否则 RLS 失效形同虚设")
                    .isFalse();
        }
    }

    static void bind(Connection conn, String tenantId) throws SQLException {
        TenantSessionBinder.bind(conn, new TenantContext(tenantId, "it", Set.of(), 0L));
    }

    /**
     * 幂等创建（或复用）一个 <b>IT 专用租户</b>，返回 {@code tenant_id}。
     *
     * <p>⚠️ slug 必须以 {@code it-} 开头（由 {@link ItTenantProvisioner} 硬校验）。
     *
     * <p><b>为什么 IT 不该再用种子租户 {@code t-a} / {@code t-b} 造 Run</b>：
     * 开发机上常驻运行实例会按 {@code agent.worker.tenant-slugs} 轮询这些真实租户，
     * 于是 IT 造出来的 {@code QUEUED} Run 会被<b>另一个进程</b>领走并改写终态 ——
     * 断言的失败信息（「期望 3 实得 4」「任务未被领取」）指向被测代码，
     * 真因却是「有第二个写者」。实测已因此红过 5 个 IT。
     *
     * <p>另外 t-a 上的 Run 会<b>永久累积</b>（多数 IT 不清理），
     * 累积到 {@code claimTasks} 的 {@code LIMIT} 配额之上后，
     * 连<b>别的</b> IT 的领取断言也会开始假红 —— 污染会跨测试类传染。
     */
    static String itTenant(DataSource admin, String slug) throws SQLException {
        return ItTenantProvisioner.provision(admin, slug);
    }

    /**
     * 取某个 IT 专用租户<b>自己的</b>执行快照 id。
     *
     * <p>⚠️ 不可用 {@code SELECT snapshot_id FROM execution_snapshot LIMIT 1} 代替：
     * 取到别的租户的快照后，插 Run 时复合外键 {@code (tenant_id, snapshot_id)} 报冲突，
     * 而错误只说外键，不说「你取错租户了」。
     */
    static String itSnapshot(DataSource admin, String tenantId) throws SQLException {
        return ItTenantProvisioner.snapshotId(admin, tenantId);
    }

    /** IT 专用租户的「身份 + 自己的快照」，避免各处再拼 {@code LIMIT 1} 取错租户。 */
    record ItTenantRef(String tenantId, String snapshotId) {
    }

    /**
     * 一步取得 IT 专用租户及其快照（内部开一个短命管理员池，用完即关）。
     *
     * <p>⚠️ 之所以做成「一次拿两个」而不是让调用方分别查：历史上多处代码用
     * {@code SELECT snapshot_id FROM execution_snapshot LIMIT 1} 取快照，
     * 在只有一个租户的时代是对的，多租户之后就会随机取到他租户的快照，
     * 故障表现是 {@code Key is not present in execution_snapshot}（复合外键），
     * 与「取错夹具」这个真因相距极远。
     */
    static ItTenantRef provisionItTenant(String slug) throws SQLException {
        try (HikariDataSource admin = adminPool()) {
            String tenantId = ItTenantProvisioner.provision(admin, slug);
            return new ItTenantRef(tenantId, ItTenantProvisioner.snapshotId(admin, tenantId));
        }
    }

    /**
     * 清空某 IT 专用租户的 Run（Run 及其子表全部 {@code ON DELETE CASCADE}，可删）。
     *
     * <p>⚠️ 只用于 {@code it-} 专用租户：种子租户上可能有别人正在用的数据。
     * 快照<b>不可删</b>（{@code forbid_snapshot_mutation}），故这里不处理。
     */
    static void purgeItTenantRuns(String slug) throws SQLException {
        try (HikariDataSource admin = adminPool()) {
            purgeItTenantRuns(admin, slug);
        }
    }

    /**
     * 同上，但复用调用方已有的管理池。
     *
     * <p>⚠️ 优先用这个重载：{@link #purgeItTenantRuns(String)} 每次都会新建一个
     * Hikari 池（远端库上一次握手就是几十毫秒），而多数 IT 类已持有类级管理池。
     */
    static void purgeItTenantRuns(DataSource admin, String slug) throws SQLException {
        try (Connection conn = admin.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM run WHERE tenant_id = "
                  + "(SELECT tenant_id FROM tenant WHERE slug = ?)")) {
                ps.setString(1, slug);
                ps.executeUpdate();
            }
            conn.commit();
        }
    }

    static String tenantIdBySlug(DataSource admin, String slug) throws SQLException {
        try (Connection conn = admin.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT tenant_id::text FROM tenant WHERE slug = ?")) {
            ps.setString(1, slug);
            try (ResultSet rs = ps.executeQuery()) {
                Assertions.assertThat(rs.next())
                        .as("种子数据缺失租户 %s，请先执行 ./scripts/dev-postgres.sh start", slug)
                        .isTrue();
                return rs.getString(1);
            }
        }
    }

    static String snapshotIdBySlug(DataSource admin, String slug) throws SQLException {
        try (Connection conn = admin.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT s.snapshot_id::text FROM execution_snapshot s "
                           + "JOIN tenant t ON t.tenant_id = s.tenant_id WHERE t.slug = ? LIMIT 1")) {
            ps.setString(1, slug);
            try (ResultSet rs = ps.executeQuery()) {
                Assertions.assertThat(rs.next())
                        .as("种子数据缺失快照 %s，请先执行 ./scripts/dev-postgres.sh start", slug)
                        .isTrue();
                return rs.getString(1);
            }
        }
    }

    /**
     * 为种子租户补足 agent_version + execution_snapshot（种子只给了 t-a），使租户可建 Run。
     *
     * <p>⚠️ <b>快照插入不能靠 {@code ON CONFLICT DO NOTHING} 保证幂等</b>（2026-11-02 实测修正）：
     * {@code execution_snapshot} 的唯一约束是 {@code (tenant_id, snapshot_id)}，
     * 而本语句从不指定 {@code snapshot_id}（由列默认值随机生成）⇒
     * <b>冲突永远不会发生</b>，{@code ON CONFLICT} 等于没写。
     * 结果是每跑一轮 IT 就多一份快照，而快照被不可变触发器保护、<b>删不掉</b>。
     * 线上库实测：{@code t-a} 已累积 72 份、{@code t-b} 45 份。
     * 故必须用 {@code NOT EXISTS} 显式排除「该版本已有快照」。
     */
    static void ensureTenantSnapshot(DataSource admin, String slug) throws SQLException {
        try (Connection conn = admin.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO agent_version (tenant_id, agent_id, version_number, definition) "
                  + "SELECT a.tenant_id, a.agent_id, 1, '{\"kind\":\"tutor\"}'::jsonb "
                  + "FROM agent a JOIN tenant t ON t.tenant_id = a.tenant_id "
                  + "WHERE t.slug = ? AND a.name = 'agent1' "
                  + "ON CONFLICT (tenant_id, agent_id, version_number) DO NOTHING")) {
                ps.setString(1, slug);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO execution_snapshot "
                  + "(tenant_id, version_id, agent_config, prompt_config, tool_contracts, run_config) "
                  + "SELECT v.tenant_id, v.version_id, '{}', '{}', '{}', "
                  + "'{\"maxCostPerRun\":\"2.00\",\"maxLlmCalls\":12,\"maxSteps\":20,\"approvalRequired\":\"OPTIONAL\"}' "
                  + "FROM agent_version v JOIN agent a "
                  + "  ON a.agent_id = v.agent_id AND a.tenant_id = v.tenant_id "
                  + "JOIN tenant t ON t.tenant_id = v.tenant_id "
                  + "WHERE t.slug = ? AND a.name = 'agent1' AND v.version_number = 1 "
                  + "AND NOT EXISTS ("
                  + "  SELECT 1 FROM execution_snapshot s "
                  + "   WHERE s.tenant_id = v.tenant_id AND s.version_id = v.version_id)")) {
                ps.setString(1, slug);
                ps.executeUpdate();
            }
            conn.commit();
        }
    }

    /** 在租户事务内执行（autocommit off + 绑定 + 提交）。 */
    static <T> T inTenant(DataSource pool, String tenantId, TenantQuery<T> body) throws Exception {
        try (Connection conn = pool.getConnection()) {
            conn.setAutoCommit(false);
            try {
                bind(conn, tenantId);
                T result = body.apply(conn);
                conn.commit();
                return result;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    /** 统计某租户可见的 Run 数（受 RLS 约束）。 */
    static long countRuns(DataSource pool, String tenantId) throws Exception {
        return inTenant(pool, tenantId, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT count(*) FROM run WHERE tenant_id = ?::uuid")) {
                ps.setString(1, tenantId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    @FunctionalInterface
    interface TenantQuery<T> {
        T apply(Connection conn) throws Exception;
    }
}
