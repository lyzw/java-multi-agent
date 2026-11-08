package cn.studykid.multiagent.bootstrap.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import cn.studykid.multiagent.bootstrap.PlatformApplication;
import cn.studykid.multiagent.infrastructure.messaging.TenantRoutingMessageSender;
import cn.studykid.multiagent.ports.messaging.MessageSender;
import cn.studykid.multiagent.ports.messaging.TraceCollectEvent;
import cn.studykid.multiagent.ports.persistence.OutboxMessage;
import cn.studykid.multiagent.testsupport.db.ItTenantProvisioner;

/**
 * P0 采集链路的<b>内容级</b>端到端验收（DB/PG 队列通道）。
 *
 * <p><b>为什么必须另立一类「内容级」验收</b>：
 * {@link TraceCollectDatabaseChannelEndToEndIT} 证明的是「<b>有一行</b>」——
 * 链路通了、指标落了库。但「有一行」与「<b>这一行的数字算对了</b>」是两件事，
 * 而后者才是看板真正展示的东西。
 * <p>本项目反复出现的失真形态是：<b>不报错，只是数字悄悄错</b>。
 * 已知的一例：事实端口曾把已知工具全集只取<b>目录侧</b>
 * （{@code cap_tool_def}），而 native 工具（{@code get_student_profile} /
 * {@code record_weakness}）<b>不在目录里</b> → 每次 native 调用都被判成
 * 「模型幻觉出的工具」→ {@code unknown_tool} 虚高 → 无效调用率失真。
 * 那个缺陷当时<b>全绿</b>：链路测试只断言「有一行」，没断言「数字对不对」。
 * <p>本类因此断言的是<b>具体数值</b>，而非「非空」。
 *
 * <p><b>断言的口径纪律</b>：{@code null} 表示「未采集」，与「采了是 0」<b>语义不同</b>。
 * 故本类对「有事实来源」的字段断言<b>具体数值</b>，对「无事实来源」的字段断言
 * <b>确为 null</b>（而不是 0）——把「未采集」写成 0 是本项目头号失真来源。
 *
 * <p>⚠️ <b>租户在上下文启动前必须存在</b>（{@code MessagingChannels.resolve}
 * 启动期按 slug 解析 tenantId，fail-closed）→ 静态初始化块里先 provision。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
@SpringBootTest(classes = PlatformApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=${IT_PG_URL}",
        "spring.datasource.username=${IT_PG_USER:app_agent}",
        "spring.datasource.password=${IT_PG_PASSWORD:}",
        "spring.flyway.enabled=false",
        "auth.jwt.issuer=https://auth.test",
        "auth.jwt.audience=agent-platform",
        "auth.jwt.token-ttl-seconds=900",
        // ⚠️ 走 **database 通道**（生产默认形态）。
        "agent.messaging.mode=database",
        "agent.worker.enabled=true",
        // ⚠️ 中继关闭：本类不轮询共享库的 outbox_event。
        "agent.worker.relay-enabled=false",
        // ⚠️ 本租户是本 IT 专用；启动期按 slug 解析 tenantId，故租户必须已存在。
        "agent.worker.tenant-slugs=it-trace-metric-content",
        "agent.messaging.poll-interval-ms=200"
})
@DisplayName("P0 采集链路内容级验收（指标数值必须算对，而非仅「有一行」）")
class TraceMetricContentAccuracyIT {

    private static final String TENANT_SLUG = "it-trace-metric-content";

    /** native 工具名：<b>代码注册</b>，不在 {@code cap_tool_def} 目录里。 */
    private static final String NATIVE_TOOL = "get_student_profile";

    /** 幻觉工具名：既不在目录、也不在代码注册表 → 应计入 unknown_tool。 */
    private static final String HALLUCINATED_TOOL = "totally_made_up_tool";

    /** 目录侧工具名：由 {@code cap_tool_def} 提供（造数据时写入）。 */
    private static final String CATALOG_TOOL = "tool-profile-read";

    private static HikariDataSource adminPool;

    @Autowired
    private DataSource dataSource;

    @Autowired
    @Qualifier("traceCollectRoutedMessageSender")
    private MessageSender traceCollectSender;

    private String tenantId;
    private String snapshotId;
    private UUID runId;
    private String traceId;
    private UUID catalogOperationId;

    @BeforeAll
    static void setup() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(env("IT_PG_URL", ""));
        cfg.setUsername(env("IT_PG_ADMIN_USER", "agent"));
        cfg.setPassword(env("IT_PG_ADMIN_PASSWORD", ""));
        cfg.setMaximumPoolSize(2);
        cfg.setPoolName("trace-content-it-admin");
        adminPool = new HikariDataSource(cfg);
        try {
            ItTenantProvisioner.provision(adminPool, TENANT_SLUG);
        } catch (SQLException e) {
            throw new IllegalStateException("准备 it- 租户失败: " + e.getMessage(), e);
        }
    }

    @AfterEach
    void cleanup() {
        if (tenantId == null) {
            return;
        }
        // ⚠️ FORCE RLS 下不绑租户的 DELETE **静默删 0 行** → 必须带 tenant_id 谓词。
        // ⚠️ 删除顺序：先子表（attempt → operation → usage → step），再 run。
        exec("DELETE FROM public.tool_operation_attempt WHERE tenant_id = ?::uuid", tenantId);
        exec("DELETE FROM public.tool_operation WHERE tenant_id = ?::uuid", tenantId);
        exec("DELETE FROM public.usage_record WHERE tenant_id = ?::uuid", tenantId);
        exec("DELETE FROM public.run_step WHERE tenant_id = ?::uuid", tenantId);
        exec("DELETE FROM public.run WHERE tenant_id = ?::uuid", tenantId);
        if (traceId != null) {
            exec("DELETE FROM platform.agent_trace_tool_call WHERE trace_id = ?", traceId);
            exec("DELETE FROM platform.agent_trace_metric WHERE trace_id = ?", traceId);
        }
        // ⚠️ **不造 cap_tool_def 行**：目录侧工具用种子里的 `tool-profile-read`
        //    （`kind` CHECK 只允许 native/mcp/http，且这是平台级全局对象，
        //    IT 往里插脏行会污染其它 IT 的「已知工具全集」判定）。
    }

    @Test
    @DisplayName("⚠️⚠️ 指标数值必须算对：工具计数/未知工具判定/token 聚合/未采集=null")
    void metricValuesAreComputedCorrectly() throws Exception {
        tenantId = ItTenantProvisioner.provision(adminPool, TENANT_SLUG);
        snapshotId = ItTenantProvisioner.snapshotId(adminPool, tenantId);
        runId = UUID.randomUUID();
        traceId = "trace-content-" + runId;

        // ── 造数：一个 SUCCEEDED 的 Run，带工具调用、用量、步骤 ──
        insertRun();
        insertUsage();          // input=120 / output=45，model_id=minimax-chat
        insertRunSteps();       // 3 个 THINK + 2 个 TOOL 步骤
        catalogOperationId = insertToolOperation(CATALOG_TOOL, "digest-catalog");
        insertToolAttempt(catalogOperationId, CATALOG_TOOL, "SUCCEEDED", null, 120L);
        // ⚠️ native 工具那次**故意用 UPSTREAM_ERROR 而非 INVALID_ARGUMENT**：
        //    这样它只贡献 failed 计数，不贡献 invalid 计数 ——
        //    于是 tool_call_invalid 的唯一来源就是「幻觉工具」，
        //    断言才能精确锁住「未知工具判定 = 目录 ∪ 代码注册表」这条口径。
        UUID nativeOperationId = insertToolOperation(NATIVE_TOOL, "digest-native");
        insertToolAttempt(nativeOperationId, NATIVE_TOOL, "FAILED", "UPSTREAM_ERROR", 30L);
        UUID halluOperationId = insertToolOperation(HALLUCINATED_TOOL, "digest-hallu");
        insertToolAttempt(halluOperationId, HALLUCINATED_TOOL, "SUCCEEDED", null, 10L);

        // ── 触发采集（生产发送器 → PG 队列 → 生产消费者）──
        OutboxMessage msg = new OutboxMessage(runId.toString(), tenantId, 0, 1L, Instant.now());
        traceCollectSender.send(TraceCollectEvent.TYPE, msg);

        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline && metricRowCount() == 0) {
            Thread.sleep(200);
        }
        assertThat(metricRowCount())
                .as("⚠️ 采集事件必须被消费并落一行")
                .isEqualTo(1);

        // ══════════════════════════════════════════════════════════════
        // 断言 1：token 聚合（来自 usage_record 求和）
        // ══════════════════════════════════════════════════════════════
        assertThat(metricLong("input_tokens"))
                .as("⚠️ input_tokens 必须等于 usage_record 的求和，不能是 0 或 null")
                .isEqualTo(120L);
        assertThat(metricLong("output_tokens"))
                .as("⚠️ output_tokens 必须等于 usage_record 的求和")
                .isEqualTo(45L);
        assertThat(metricString("model_id"))
                .as("⚠️ model_id 取模型行的标识，不能混入 tool: 前缀的工具名")
                .isEqualTo("minimax-chat");

        // ══════════════════════════════════════════════════════════════
        // 断言 2：工具计数（3 组工具操作，每组 1 次调用 → 总计 3）
        // ══════════════════════════════════════════════════════════════
        assertThat(metricLong("tool_call_total"))
                .as("⚠️ 工具调用总数必须是 3（三个工具各 1 次）")
                .isEqualTo(3L);
        assertThat(metricLong("tool_call_failed"))
                .as("⚠️ 失败调用数必须是 1（native 工具那次 FAILED）")
                .isEqualTo(1L);

        // ══════════════════════════════════════════════════════════════
        // 断言 3：⚠️ 未知工具判定 = 目录 ∪ 代码注册表（**本类的核心**）
        //   目录侧：tool-profile-read（cap_tool_def）
        //   代码侧：get_student_profile（ToolNameRegistry）
        //   幻觉的：totally_made_up_tool（两侧都没有）
        //   → 只有幻觉工具那 1 次应计入 invalid
        // ══════════════════════════════════════════════════════════════
        assertThat(metricLong("tool_call_invalid"))
                .as("⚠️⚠️ 无效调用数必须是 1 —— 只有「两侧都没有」的工具才算幻觉。"
                        + "若为 2，说明 native 工具（代码注册但不在目录里）被误判成幻觉工具，"
                        + "unknown_tool 虚高 → 无效调用率失真（不报错，只是数字悄悄错）")
                .isEqualTo(1L);

        // ══════════════════════════════════════════════════════════════
        // 断言 4：tool_rounds 来自 run_step 的 TOOL 步骤数（P7-2 真实口径）
        // ══════════════════════════════════════════════════════════════
        assertThat(metricLong("tool_rounds"))
                .as("⚠️ tool_rounds 必须取自 run_step 的 TOOL 步骤计数（=2），不是退化近似")
                .isEqualTo(2L);

        // ══════════════════════════════════════════════════════════════
        // 断言 5：未采集 = null（**不是 0**）—— 效果类指标本阶段无来源
        // ══════════════════════════════════════════════════════════════
        assertThat(metricIsNull("hallucination"))
                .as("⚠️ 效果类指标无采集来源时必须是 null（未采集），"
                        + "写成 0 会让「没测过幻觉」显示成「幻觉率 0%」")
                .isTrue();
        assertThat(metricIsNull("sampled"))
                .as("⚠️ 抽样标记未采集时必须是 null，不是 false")
                .isTrue();

        // ══════════════════════════════════════════════════════════════
        // 断言 6：工具维度明细表也落了行（按 trace_id + tool_name）
        // ══════════════════════════════════════════════════════════════
        List<String> toolNames = queryStrings(
                "SELECT tool_name FROM platform.agent_trace_tool_call WHERE trace_id = ?",
                traceId);
        assertThat(toolNames)
                .as("⚠️ 工具维度明细必须落 3 行（每个工具一组）")
                .containsExactlyInAnyOrder(CATALOG_TOOL, HALLUCINATED_TOOL, NATIVE_TOOL);
    }

    @Test
    @DisplayName("⚠️ 幂等：重复采集同一 Trace 必须覆盖而不是翻倍（指标是事实的确定函数）")
    void repeatedCollectIsIdempotent() throws Exception {
        tenantId = ItTenantProvisioner.provision(adminPool, TENANT_SLUG);
        snapshotId = ItTenantProvisioner.snapshotId(adminPool, tenantId);
        runId = UUID.randomUUID();
        traceId = "trace-idem-" + runId;

        insertRun();
        UUID opId = insertToolOperation(CATALOG_TOOL, "digest-idem");
        insertToolAttempt(opId, CATALOG_TOOL, "SUCCEEDED", null, 5L);

        OutboxMessage msg = new OutboxMessage(runId.toString(), tenantId, 0, 1L, Instant.now());
        traceCollectSender.send(TraceCollectEvent.TYPE, msg);

        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline && metricRowCount() == 0) {
            Thread.sleep(200);
        }
        assertThat(metricRowCount()).isEqualTo(1);
        int toolsAfterFirst = queryInt(
                "SELECT count(*) FROM platform.agent_trace_tool_call WHERE trace_id = ?", traceId);
        assertThat(toolsAfterFirst).isEqualTo(1);

        // ── 再采集一次：必须覆盖，不得翻倍 ──
        traceCollectSender.send(TraceCollectEvent.TYPE, msg);
        Thread.sleep(1500);

        assertThat(metricRowCount())
                .as("⚠️ 重复采集不得新增指标行（主键 trace_id 幂等覆盖）")
                .isEqualTo(1);
        assertThat(queryInt("SELECT count(*) FROM platform.agent_trace_tool_call "
                + "WHERE trace_id = ?", traceId))
                .as("⚠️ 重复采集不得让工具明细翻倍（必须先删后插）")
                .isEqualTo(1);
        assertThat(metricLong("tool_call_total"))
                .as("⚠️ 重复采集后工具调用总数仍是 1，不是 2")
                .isEqualTo(1L);
    }

    // ══════════════════════════════════════════════════════════════
    // 造数
    // ══════════════════════════════════════════════════════════════

    private void insertRun() throws SQLException {
        try (Connection conn = adminPool.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO public.run (run_id, tenant_id, snapshot_id, idempotency_key,
                            request_digest, state, fencing_token, attempt, input_payload,
                            result_payload, correlation_id, created_at, updated_at,
                            completed_at, trace_id)
                    VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, 'SUCCEEDED', 1, 0,
                            '{}'::jsonb, '{"content":"ok"}'::jsonb, ?, now(), now(), now(), ?)
                    """)) {
                ps.setString(1, runId.toString());
                ps.setString(2, tenantId);
                ps.setString(3, snapshotId);
                ps.setString(4, "it-trace-content-" + runId);
                ps.setString(5, "digest-" + runId);
                ps.setString(6, "corr-" + runId);
                ps.setString(7, traceId);
                ps.executeUpdate();
            }
            conn.commit();
        }
    }

    private void insertUsage() throws SQLException {
        try (Connection conn = adminPool.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO public.usage_record (tenant_id, run_id, model_id,
                            input_tokens, output_tokens, cost_amount, currency)
                    VALUES (?::uuid, ?::uuid, 'minimax-chat', 120, 45, 0.012345, 'CNY')
                    """)) {
                ps.setString(1, tenantId);
                ps.setString(2, runId.toString());
                ps.executeUpdate();
            }
            conn.commit();
        }
    }

    private void insertRunSteps() throws SQLException {
        try (Connection conn = adminPool.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO public.run_step (tenant_id, run_id, step_number, state,
                            step_type, fencing_token, started_at, completed_at)
                    VALUES (?::uuid, ?::uuid, ?, 'SUCCEEDED', ?, 1, now(), now())
                    """)) {
                int n = 1;
                for (int i = 0; i < 3; i++) {
                    ps.setString(1, tenantId);
                    ps.setString(2, runId.toString());
                    ps.setInt(3, n++);
                    ps.setString(4, "THINK");
                    ps.executeUpdate();
                }
                for (int i = 0; i < 2; i++) {
                    ps.setString(1, tenantId);
                    ps.setString(2, runId.toString());
                    ps.setInt(3, n++);
                    ps.setString(4, "TOOL");
                    ps.executeUpdate();
                }
            }
            conn.commit();
        }
    }

    private UUID insertToolOperation(String toolName, String requestDigest) throws SQLException {
        UUID operationId = UUID.randomUUID();
        try (Connection conn = adminPool.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO public.tool_operation (operation_id, tenant_id, run_id,
                            tool_name, risk_level, state, idempotency_key, request_digest,
                            tool_arguments, fencing_token)
                    VALUES (?::uuid, ?::uuid, ?::uuid, ?, 'READ', 'PENDING', ?, ?, '{}'::jsonb, 1)
                    """)) {
                ps.setString(1, operationId.toString());
                ps.setString(2, tenantId);
                ps.setString(3, runId.toString());
                ps.setString(4, toolName);
                ps.setString(5, "idem-" + operationId);
                ps.setString(6, requestDigest);
                ps.executeUpdate();
            }
            conn.commit();
        }
        return operationId;
    }

    private void insertToolAttempt(UUID operationId, String toolName, String state,
                                   String errorClass, Long durationMs) throws SQLException {
        try (Connection conn = adminPool.getConnection()) {
            conn.setAutoCommit(false);
            // ⚠️ 顺序有铁律：先插 attempt（operation 仍是 PENDING），
            //    再把 operation 推到终态 —— 触发器 `forbid_attempt_after_terminal`
            //    禁止「终态后再补 attempt」。
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO public.tool_operation_attempt (tenant_id, operation_id,
                            attempt_number, state, fencing_token, started_at, completed_at,
                            error_class)
                    VALUES (?::uuid, ?::uuid, 1, ?::public.tool_operation_state, 1,
                            now() - (? || ' milliseconds')::interval,
                            now() - (? || ' milliseconds')::interval, ?)
                    """)) {
                ps.setString(1, tenantId);
                ps.setString(2, operationId.toString());
                ps.setString(3, state);
                ps.setString(4, String.valueOf(durationMs));
                ps.setString(5, String.valueOf(durationMs));
                ps.setString(6, errorClass);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE public.tool_operation SET state = ?::public.tool_operation_state, "
                            + "updated_at = now() WHERE operation_id = ?::uuid")) {
                ps.setString(1, state);
                ps.setString(2, operationId.toString());
                ps.executeUpdate();
            }
            conn.commit();
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 断言辅助
    // ══════════════════════════════════════════════════════════════

    private int metricRowCount() {
        return queryInt("SELECT count(*) FROM platform.agent_trace_metric WHERE trace_id = ?",
                traceId);
    }

    private Long metricLong(String column) {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT " + column + " FROM platform.agent_trace_metric WHERE trace_id = ?")) {
            ps.setString(1, traceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                long v = rs.getLong(1);
                return rs.wasNull() ? null : v;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询指标列失败: " + column, e);
        }
    }

    private String metricString(String column) {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT " + column + " FROM platform.agent_trace_metric WHERE trace_id = ?")) {
            ps.setString(1, traceId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询指标列失败: " + column, e);
        }
    }

    /** ⚠️ 区分「未采集（NULL）」与「采了是 0」——本项目头号失真来源。 */
    private boolean metricIsNull(String column) {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT " + column + " IS NULL FROM platform.agent_trace_metric "
                                + "WHERE trace_id = ?")) {
            ps.setString(1, traceId);
            try (ResultSet rs = ps.executeQuery()) {
                return !rs.next() || rs.getBoolean(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询指标列失败: " + column, e);
        }
    }

    private int queryInt(String sql, String arg) {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, arg);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询失败: " + sql, e);
        }
    }

    private List<String> queryStrings(String sql, String arg) {
        List<String> out = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, arg);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询失败: " + sql, e);
        }
        return out;
    }

    /** 用 owner 连接执行写/删（应用角色对租户域表受 RLS 且无写权）。 */
    private void exec(String sql, String... args) {
        try (Connection conn = adminPool.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setString(i + 1, args[i]);
                }
                ps.executeUpdate();
            }
            conn.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("执行失败: " + sql, e);
        }
    }

    static String env(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : v;
    }
}
