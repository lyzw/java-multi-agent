package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W-PERM-P0 集成测试基座。
 *
 * <p><b>⚠️ 必须用非特权角色 {@code app_agent}</b>（NOSUPERUSER NOBYPASSRLS）：
 * 超级用户与 BYPASSRLS 角色会<b>绕过</b>所有 RLS 策略，
 * 用它们跑隔离测试会得到「隔离生效」的<b>虚假</b>结论
 * （D01 教训；ConfigOverrideRepositoryIT 首次实现时即踩过：
 * 用管理连接时看到「B 能看到 1 行」，误判为 RLS 失效，真因是绕过了 RLS）。
 *
 * <p>夹具（跨租户数据）用管理角色 {@code agent} 造 —— 它带 BYPASSRLS，
 * 正是「造夹具」而非「验隔离」的角色。
 */
abstract class CapITBase {

    /** 应用角色连接池（非特权，验 RLS 必需）。 */
    protected static HikariDataSource appPool;

    /** 管理角色连接池（造跨租户夹具）。 */
    protected static HikariDataSource adminPool;

    protected static String tenantA;
    protected static String tenantB;

    @BeforeAll
    static void initPools() throws Exception {
        String url = System.getenv("IT_PG_URL");
        String appUser = System.getenv().getOrDefault("IT_PG_USER", "app_agent");
        String appPwd = System.getenv("IT_PG_PASSWORD");

        HikariConfig appCfg = new HikariConfig();
        appCfg.setJdbcUrl(url);
        appCfg.setUsername(appUser);
        if (appPwd != null && !appPwd.isBlank()) {
            appCfg.setPassword(appPwd);
        }
        appCfg.setMaximumPoolSize(8);
        appPool = new HikariDataSource(appCfg);

        HikariConfig adminCfg = new HikariConfig();
        adminCfg.setJdbcUrl(url);
        adminCfg.setUsername(System.getenv().getOrDefault("IT_PG_ADMIN_USER", "agent"));
        String adminPwd = System.getenv("IT_PG_ADMIN_PASSWORD");
        if (adminPwd != null && !adminPwd.isBlank()) {
            adminCfg.setPassword(adminPwd);
        }
        adminCfg.setMaximumPoolSize(2);
        adminPool = new HikariDataSource(adminCfg);

        tenantA = adminQuery("SELECT tenant_id::text FROM tenant WHERE slug='t-a'");
        tenantB = adminQuery("SELECT tenant_id::text FROM tenant WHERE slug='t-b'");
    }

    /**
     * 关闭连接池。
     *
     * <p>⚠️ <b>必须关闭</b>：本基座为每个测试类建两个池（应用角色 8 连接 + 管理角色 2 连接）。
     * 不关闭的话，每多一个测试类就多占 10 个连接 ——
     * 累积到 PostgreSQL 的 {@code max_connections} 之后，
     * <b>后续模块的测试</b>会以
     * {@code FATAL: sorry, too many clients already} 失败。
     * 那种失败的现场在别的模块（这里是 platform-bootstrap 的上下文加载），
     * 报错点离真因（本模块的测试类泄漏了连接）极远，
     * 极易被误判成「配置变更破坏了装配」。
     *
     * <p>⚠️ JUnit 5 中继承来的静态 {@code @AfterAll} <b>按子类各执行一次</b>，
     * 故每个测试类结束时关闭自己的池，互不干扰。
     */
    @AfterAll
    static void closePools() {
        if (appPool != null) {
            appPool.close();
            appPool = null;
        }
        if (adminPool != null) {
            adminPool.close();
            adminPool = null;
        }
    }

    @BeforeEach
    void cleanCapTables() {
        // ⚠️ 每例前清空：否则上一例的版本行/授权版本会影响本例判定
        //    （例如「原子自增后版本应为 3」在上一例已自增过时会变成 4）。
        // ⚠️ 顺序：**先删引用方，再删被引用方** —— cap_agent_def 复合外键指向 agent。
        // ⚠️ P3 新表：审批与补偿任务同样必须每例清理 ——
        //    cap_approval 有 UNIQUE(tenant_id, invocation_id, tool_id)，
        //    跨例残留会让下一例直接撞唯一约束失败（且失败现场指向错误方向）。
        adminExec("DELETE FROM cap_approval WHERE tenant_id IN ('"
                + tenantA + "'::uuid, '" + tenantB + "'::uuid)");
        adminExec("DELETE FROM cap_compensation_task WHERE tenant_id IN ('"
                + tenantA + "'::uuid, '" + tenantB + "'::uuid)");
        adminExec("DELETE FROM cap_result_event WHERE tenant_id IN ('"
                + tenantA + "'::uuid, '" + tenantB + "'::uuid)");
        adminExec("DELETE FROM cap_execution_intent WHERE tenant_id IN ('"
                + tenantA + "'::uuid, '" + tenantB + "'::uuid)");
        adminExec("DELETE FROM execution_capability_snapshot WHERE tenant_id IN ('"
                + tenantA + "'::uuid, '" + tenantB + "'::uuid)");
        adminExec("DELETE FROM cap_authz_version WHERE tenant_id IN ('"
                + tenantA + "'::uuid, '" + tenantB + "'::uuid)");
        adminExec("DELETE FROM cap_agent_def WHERE tenant_id IN ('"
                + tenantA + "'::uuid, '" + tenantB + "'::uuid)");
        // ⚠️ platform 级对象定义（P1）：无 RLS、无 tenant_id，按 id 清理。
        //    只清本轮用例用到的固定 id，避免误删他人数据。
        adminExec("DELETE FROM cap_tool_def WHERE id LIKE 'it-cap-%'");
        adminExec("DELETE FROM cap_skill_def WHERE id LIKE 'it-cap-%'");
        adminExec("DELETE FROM cap_mcp_server_def WHERE id LIKE 'it-cap-%'");
    }

    /**
     * 生成一个新的 UUID 字符串。
     *
     * <p>⚠️ 用于代理键（event_id / intent_id 等 UUID 列）。
     * 写死成 {@code "evt-1"} 这类字面量会在插入时被 PostgreSQL 拒绝
     * （invalid input syntax for type uuid）—— 那不是断言失败，
     * 是夹具根本没建起来，报错点离真因很远。
     */
    protected static String uuid() {
        return java.util.UUID.randomUUID().toString();
    }

    /**
     * 生成本次运行唯一的 Agent 名称。
     *
     * <p>⚠️ <b>必须唯一</b>：{@code agent} 表有 {@code agent_name_per_tenant} 唯一约束。
     * 用固定名字会在<b>第二轮</b>运行时直接撞名失败 ——
     * 症状是「第一次跑全绿、第二次跑全红」，而真因只是残留数据。
     */
    protected static String uniqueName(String prefix) {
        return prefix + "-" + java.util.UUID.randomUUID().toString().substring(0, 8);
    }

    // ── 辅助 ──────────────────────────────────────────────────────

    protected static String adminQuery(String sql) {
        try (Connection conn = adminPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             var rs = ps.executeQuery()) {
            assertThat(rs.next()).as("查询应返回一行: " + sql).isTrue();
            return rs.getString(1);
        } catch (Exception e) {
            throw new IllegalStateException("管理员查询失败: " + sql, e);
        }
    }

    protected static long adminQueryLong(String sql) {
        try (Connection conn = adminPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             var rs = ps.executeQuery()) {
            assertThat(rs.next()).as("查询应返回一行: " + sql).isTrue();
            return rs.getLong(1);
        } catch (Exception e) {
            throw new IllegalStateException("管理员查询失败: " + sql, e);
        }
    }

    protected static void adminExec(String sql) {
        try (Connection conn = adminPool.getConnection();
             var st = conn.createStatement()) {
            st.execute(sql);
        } catch (Exception e) {
            throw new IllegalStateException("管理员写入失败: " + sql, e);
        }
    }

    /**
     * 以指定租户上下文执行只读查询（走应用角色，验证 RLS 真实效果）。
     */
    protected static long appCountAsTenant(String tenantId, String sql) {
        try (Connection conn = appPool.getConnection()) {
            try (var st = conn.createStatement()) {
                st.execute("SELECT set_config('app.tenant_id', '" + tenantId + "', false)");
            }
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 var rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getLong(1);
            }
        } catch (Exception e) {
            throw new IllegalStateException("按租户查询失败: " + sql, e);
        }
    }

    /**
     * 创建一个带完整夹具链的 Agent（agent → agent_version → execution_snapshot）。
     *
     * @param definitionJson <b>原始</b> JSON 文本（不含 SQL 引号），如 {@code {"kind":"tutor"}}
     * @return agentId
     */
    protected static String createAgentWithVersion(String tenantId, String agentName,
                                                   String definitionJson, boolean published) {
        String agentId = java.util.UUID.randomUUID().toString();
        String versionId = java.util.UUID.randomUUID().toString();
        adminExec("INSERT INTO agent (agent_id, tenant_id, name) VALUES ('" + agentId
                + "'::uuid, '" + tenantId + "'::uuid, '" + agentName + "')");
        adminExec("INSERT INTO agent_version (version_id, tenant_id, agent_id, version_number, "
                + "definition, published_at) VALUES ('" + versionId + "'::uuid, '" + tenantId
                + "'::uuid, '" + agentId + "'::uuid, 1, '" + definitionJson + "'::jsonb, "
                + (published ? "now()" : "NULL") + ")");
        return agentId;
    }

    /**
     * 创建一个可挂载快照的 Run（需先有 execution_snapshot）。
     */
    protected static String createRun(String tenantId, String agentId, String snapshotId) {
        String runId = java.util.UUID.randomUUID().toString();
        adminExec("INSERT INTO run (run_id, tenant_id, snapshot_id, idempotency_key, "
                + "request_digest, input_payload, correlation_id) VALUES ('" + runId
                + "'::uuid, '" + tenantId + "'::uuid, '" + snapshotId + "'::uuid, 'cap-it-"
                + runId + "', 'digest-" + runId + "', '{}'::jsonb, 'cap-it')");
        return runId;
    }

    /**
     * 为指定 Agent 创建执行快照，返回 snapshotId。
     */
    protected static String createExecutionSnapshot(String tenantId, String agentId) {
        String versionId = adminQuery("SELECT version_id::text FROM agent_version "
                + "WHERE tenant_id = '" + tenantId + "'::uuid AND agent_id = '" + agentId
                + "'::uuid AND version_number = 1");
        String snapshotId = java.util.UUID.randomUUID().toString();
        adminExec("INSERT INTO execution_snapshot (snapshot_id, tenant_id, version_id, "
                + "agent_config, prompt_config, tool_contracts, run_config) VALUES ('"
                + snapshotId + "'::uuid, '" + tenantId + "'::uuid, '" + versionId
                + "'::uuid, '{}'::jsonb, '{}'::jsonb, '{}'::jsonb, '{}'::jsonb)");
        return snapshotId;
    }

    /**
     * 以指定租户上下文执行一段数据库操作（事务内）。
     */
    protected static <T> T inTenant(DataSource pool, String tenantId, TenantBody<T> body) {
        try (Connection conn = pool.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (var st = conn.createStatement()) {
                    st.execute("SELECT set_config('app.tenant_id', '" + tenantId + "', true)");
                }
                T result = body.apply(conn);
                conn.commit();
                return result;
            } catch (Exception e) {
                try {
                    conn.rollback();
                } catch (SQLException ignored) {
                    // 回滚失败不掩盖原始异常
                }
                if (e instanceof RuntimeException re) {
                    throw re;
                }
                throw new IllegalStateException("租户事务执行失败", e);
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("租户事务执行失败", e);
        }
    }

    /** 租户事务体。 */
    @FunctionalInterface
    protected interface TenantBody<T> {
        T apply(Connection conn) throws Exception;
    }
}
