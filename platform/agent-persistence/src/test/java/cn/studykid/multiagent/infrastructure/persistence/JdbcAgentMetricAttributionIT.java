package cn.studykid.multiagent.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import cn.studykid.multiagent.ports.metrics.AgentMetricPort;
import cn.studykid.multiagent.ports.metrics.AgentMetricPort.EvalSample;
import cn.studykid.multiagent.ports.metrics.AgentMetricPort.MetricQuery;
import cn.studykid.multiagent.ports.metrics.AgentMetricPort.Overview;

/**
 * attribution_correct 的<b>真库闭环</b>（§13 第 3 项）。
 *
 * <p><b>⚠️ 为什么必须是 IT</b>：L2 的「写回 + 回填 + 聚合」都只在真跑 SQL 时才暴露问题 ——
 *   列不存在（结构未补）、占位符错位、n&lt;30 占比被静默置 NULL 等，mock 单测照不出。
 *
 * <p><b>⚠️ 隔离纪律</b>：平台域表无 RLS，用管理角色直连。所有造数带本 IT 专属
 *   {@code tenant_id} 与 {@code trace_id} 前缀，清理只删自己的行。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
class JdbcAgentMetricAttributionIT {

    private static final String PREFIX = "attr-it-" + UUID.randomUUID().toString().replace("-", "") + "-";
    private static final String TENANT_A = UUID.randomUUID().toString();
    private static final String TENANT_B = UUID.randomUUID().toString();

    private static HikariDataSource adminPool;
    private static JdbcAgentMetricRepository repo;

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
        repo = new JdbcAgentMetricRepository(adminPool);
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
    void seed() {
        purge();
        // 一条基线 Trace，attribution_correct 先为 NULL
        insertMetric(PREFIX + "base", TENANT_A, null);
    }

    private static MetricQuery q(String tenantId) {
        Instant to = Instant.now().plus(Duration.ofMinutes(1));
        return new MetricQuery(to.minus(Duration.ofHours(24)), to, tenantId, null, null, null);
    }

    private static void purge() {
        try (Connection c = adminPool.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM platform.agent_eval_sample WHERE trace_id LIKE '" + PREFIX + "%'");
            s.executeUpdate("DELETE FROM platform.agent_trace_metric WHERE trace_id LIKE '" + PREFIX + "%'");
        } catch (SQLException e) {
            throw new IllegalStateException("清理 attribution IT 数据失败", e);
        }
    }

    private static void insertMetric(String traceId, String tenant, Boolean attributionCorrect) {
        String sql = """
                INSERT INTO platform.agent_trace_metric
                    (trace_id, tenant_id, run_id, scenario, started_at, duration_ms,
                     terminal_state, termination_reason, input_tokens, output_tokens,
                     tool_rounds, tool_call_total, tool_call_failed, tool_call_invalid,
                     loop_suspected_count, max_rounds_reached, attribution_correct, trace_id_source)
                VALUES (?, ?::uuid, ?::uuid, '数学:一元二次方程', now() - interval '1 hour', 1000,
                        'SUCCEEDED', 'COMPLETED', 100, 200,
                        1, 5, 0, 0,
                        0, false, ?, 'RUN_TRACE_ID')
                """;
        try (Connection c = adminPool.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, traceId);
            ps.setString(2, tenant);
            ps.setString(3, UUID.randomUUID().toString());
            ps.setObject(4, attributionCorrect);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("插入 attribution IT 指标行失败: " + traceId, e);
        }
    }

    @Test
    @DisplayName("✅ 写回 + 回填：recordEvalSample 把 attribution_correct 落库并回填宽表")
    void writesAndBackfillsAttributionCorrect() {
        String trace = PREFIX + "base";
        repo.recordEvalSample(new EvalSample(
                trace, TENANT_A, L2Constants.VERDICT, null, null, Boolean.TRUE,
                "L2 LLM-as-judge", "ATTRIBUTION_JUDGE"));

        assertThat(readEvalAttribution(trace)).isTrue();
        assertThat(readMetricAttribution(trace)).isTrue();
    }

    @Test
    @DisplayName("✅ 覆盖率为 null 时不编造：单条已评级样本 → rate=null（n<30）")
    void singleGradedSampleIsNotARate() {
        // 基线 Trace 已 recordEvalSample=true（上条测试前置未清理，这里再写一次确保）
        repo.recordEvalSample(new EvalSample(
                PREFIX + "base", TENANT_A, L2Constants.VERDICT, null, null, Boolean.TRUE,
                "L2", "ATTRIBUTION_JUDGE"));

        Overview ov = repo.overview(q(TENANT_A));
        assertThat(ov.attributionGradedCount()).isEqualTo(1L);
        assertThat(ov.attributionCorrectCount()).isEqualTo(1L);
        // ⚠️ 纪律：n<30 一律不显示百分比（噪声不是指标）
        assertThat(ov.attributionCorrectRate()).isNull();
    }

    @Test
    @DisplayName("✅ 足量样本时算出真占比（30 真 / 5 假 → 0.8571），且与宽表口径闭合")
    void computesRealRateAtSufficientSample() {
        for (int i = 0; i < 35; i++) {
            insertMetric(PREFIX + "agg-" + i, TENANT_B, i < 30); // 30 true + 5 false
        }

        Overview ov = repo.overview(q(TENANT_B));
        assertThat(ov.attributionGradedCount()).isEqualTo(35L);
        assertThat(ov.attributionCorrectCount()).isEqualTo(30L);
        // 30/35 = 0.857142857...
        assertThat(ov.attributionCorrectRate()).isNotNull();
        assertThat(ov.attributionCorrectRate()).isBetween(0.8571, 0.8572);
    }

    @Test
    @DisplayName("✅ 未评级租户：graded=0 → rate=null（看板显示「未启用」，绝不 0%）")
    void noGradedSampleYieldsNullRate() {
        // TENANT_A 基线未评级（attribution_correct 为 NULL）
        Overview ov = repo.overview(q(TENANT_A));
        assertThat(ov.attributionGradedCount()).isZero();
        assertThat(ov.attributionCorrectRate()).isNull();
    }

    private Boolean readEvalAttribution(String trace) {
        return readBool(
                "SELECT attribution_correct FROM platform.agent_eval_sample WHERE trace_id = ?", trace);
    }

    private Boolean readMetricAttribution(String trace) {
        return readBool(
                "SELECT attribution_correct FROM platform.agent_trace_metric WHERE trace_id = ?", trace);
    }

    private Boolean readBool(String sql, String trace) {
        try (Connection c = adminPool.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, trace);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                boolean b = rs.getBoolean(1);
                return rs.wasNull() ? null : b;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取 attribution 失败", e);
        }
    }

    /** 与 L2AttributionEvaluator.VERDICT 同字面量；守卫不得引用被测实现常量。 */
    private static final class L2Constants {
        static final String VERDICT = "ATTRIBUTION";
    }
}
