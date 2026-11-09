package cn.studykid.multiagent.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * TOOL 消息下钻的<b>真库闭环</b>（2026-11-09 补 · 详细设计 §8.3）。
 *
 * <p><b>⚠️ 为什么必须是 IT</b>：整条链跨两张 FORCE RLS 的租户域表
 *   （{@code run_message} 存 {@code tool_operation_id}，{@code tool_operation} 存参数原文），
 *   且仓储读取必须绑定租户上下文（{@code openTenant}）。
 *   mock 单测照不出「没绑租户 ⇒ 静默 0 行」这一类失真。
 *
 * <p><b>⚠️ 本 IT 守的是「能查到」而不是「字段存在」</b>：
 *   只断言列存在是弱守卫 —— 真正会坏的是「run_message 写了 id、
 *   但 tool_operation 里没有对应行」或「租户不匹配导致查不到」。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
class JdbcToolOperationDrilldownIT {

    private static final String PREFIX = "tooldrill-it-";
    /** 运行时从库里取真实存在的 (tenant, run)，满足 tool_operation_run_fk。 */
    private static String tenantA;
    private static String runA;
    /** 另一个真实租户（用于跨租户回查断言）。 */
    private static String tenantB;

    private static HikariDataSource adminPool;
    private static JdbcTraceReplayRepository repo;

    @BeforeAll
    static void setUp() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(System.getenv("IT_PG_URL"));
        cfg.setUsername(System.getenv().getOrDefault("IT_PG_ADMIN_USER", "agent"));
        String pwd = System.getenv("IT_PG_ADMIN_PASSWORD");
        if (pwd != null && !pwd.isBlank()) {
            cfg.setPassword(pwd);
        }
        cfg.setMaximumPoolSize(3);
        adminPool = new HikariDataSource(cfg);
        repo = new JdbcTraceReplayRepository(adminPool);
        try (Connection c = adminPool.getConnection();
             Statement st = c.createStatement();
             var rs = st.executeQuery(
                     "SELECT tenant_id::text, run_id::text FROM public.run ORDER BY created_at DESC LIMIT 1")) {
            if (!rs.next()) {
                throw new IllegalStateException("IT 库里没有 run 行，无法构造 tool_operation（FK 依赖）");
            }
            tenantA = rs.getString(1);
            runA = rs.getString(2);
        } catch (SQLException e) {
            throw new IllegalStateException("读取 IT 库 run 失败", e);
        }
        try (Connection c = adminPool.getConnection();
             Statement st = c.createStatement();
             var rs = st.executeQuery(
                     "SELECT tenant_id::text FROM public.execution_snapshot "
                             + "WHERE tenant_id <> '" + tenantA + "' LIMIT 1")) {
            tenantB = rs.next() ? rs.getString(1) : tenantA;
        } catch (SQLException e) {
            throw new IllegalStateException("读取第二租户失败", e);
        }
    }

    @AfterAll
    static void tearDown() {
        purge();
        if (adminPool != null) {
            adminPool.close();
            adminPool = null;
        }
    }

    @BeforeEach
    void reset() {
        purge();
    }

    private static void purge() {
        try (Connection c = adminPool.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM public.run_message WHERE trace_id LIKE '" + PREFIX + "%'");
            // ⚠️ 按 idempotency_key 前缀直删（本 IT 造数时统一加了 PREFIX）。
            //    不能用 `operation_id IN (SELECT operation_id::text ...)`：
            //    operation_id 是 uuid，与 text 比较会报 operator does not exist。
            s.executeUpdate("DELETE FROM public.tool_operation WHERE idempotency_key LIKE '"
                    + PREFIX + "%'");
        } catch (SQLException e) {
            throw new IllegalStateException("清理 tool drilldown IT 数据失败", e);
        }
    }

    private static String insertToolOperation(String tenantId, String toolName,
                                              String arguments, String result) throws SQLException {
        String opId = UUID.randomUUID().toString();
        try (Connection c = adminPool.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO public.tool_operation "
                             + "(operation_id, tenant_id, run_id, tool_name, state, risk_level, "
                             + " tool_arguments, result_payload, request_digest, idempotency_key, "
                             + " created_at, fencing_token) "
                             + "VALUES (?::uuid, ?::uuid, ?::uuid, ?, 'SUCCEEDED', 'READ', "
                             + "        ?::jsonb, ?::jsonb, ?, ?, ?, 0)")) {
            ps.setString(1, opId);
            ps.setString(2, tenantId);
            ps.setString(3, runA);
            ps.setString(4, toolName);
            ps.setString(5, arguments);
            ps.setString(6, result);
            ps.setString(7, "digest-" + opId);
            ps.setString(8, PREFIX + opId);
            ps.setTimestamp(9, Timestamp.from(Instant.now().minusSeconds(3600)));
            ps.executeUpdate();
        }
        return opId;
    }

    private static void insertRunMessage(String tenantId, String runId, String opId)
            throws SQLException {
        try (Connection c = adminPool.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO public.run_message "
                             + "(tenant_id, run_id, trace_id, role, content, tool_operation_id, "
                             + " idem_key, created_at, fencing_token) "
                             + "VALUES (?::uuid, ?::uuid, ?, 'TOOL', "
                             + "        '{\"toolName\":\"get_student_profile\"}'::jsonb, ?::uuid, ?, ?, 0)")) {
            ps.setString(1, tenantId);
            ps.setString(2, runId);
            ps.setString(3, PREFIX + runId);
            ps.setString(4, opId);
            ps.setString(5, PREFIX + "msg-" + UUID.randomUUID());
            ps.setTimestamp(6, Timestamp.from(Instant.now().minusSeconds(3600)));
            ps.executeUpdate();
        }
    }

    @Test
    @DisplayName("✅ 完整下钻：run_message 带 tool_operation_id → 能回查到原始参数与返回")
    void drilldownReturnsOriginalArguments() throws Exception {
        String opId = insertToolOperation(tenantA, "get_student_profile",
                "{\"studentId\":\"s-1\"}", "{\"weakness\":\"一元二次方程\"}");
        insertRunMessage(tenantA, runA, opId);

        // ① 回放时间线里 TOOL 行确实带上了 operationId
        var messages = repo.messages(tenantA, runA);
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).toolOperationId()).isEqualTo(opId);

        // ② 按 (tenantId, operationId) 回查到参数/返回原文
        Map<String, Object> detail = repo.toolOperation(tenantA, opId).orElseThrow();
        // ⚠️ 仓储返回的 key 是 camelCase（与 transcript 端点同口径）
        assertThat(detail).containsEntry("toolName", "get_student_profile")
                .containsEntry("state", "SUCCEEDED");
        assertThat(String.valueOf(detail.get("toolArguments"))).contains("s-1");
        assertThat(String.valueOf(detail.get("resultPayload"))).contains("一元二次方程");
    }

    @Test
    @DisplayName("⚠️⚠️ 跨租户回查必须查不到（FORCE RLS 生效，不泄露归属）")
    void crossTenantDrilldownIsEmpty() throws Exception {
        String opId = insertToolOperation(tenantA, "get_student_profile", "{\"a\":1}", "{\"r\":2}");

        // 用 B 租户去查 A 的操作：FORCE RLS 下必须返回空，而不是抛错或泄露
        assertThat(repo.toolOperation(tenantB, opId)).isEmpty();
    }

    @Test
    @DisplayName("⚠️ operationId 不存在时返回空（不是伪造一条空详情）")
    void missingOperationIsEmpty() {
        assertThat(repo.toolOperation(tenantA, UUID.randomUUID().toString())).isEmpty();
    }
}
