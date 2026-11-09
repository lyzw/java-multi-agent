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

import cn.studykid.multiagent.ports.metrics.AgentMetricPort.EvalSample;
import cn.studykid.multiagent.ports.metrics.AgentMetricPort.MetricQuery;
import cn.studykid.multiagent.ports.metrics.AgentMetricPort.Overview;

/**
 * attribution_correct 的<b>真库闭环</b>（§13 第 3 项）。
 *
 * <p><b>⚠️ 为什么必须是 IT</b>：L2 的「写回 + 聚合」都只在真跑 SQL 时才暴露问题 ——
 *   列不存在（结构未补）、占位符错位、n&lt;30 占比被静默置 NULL 等，mock 单测照不出。
 *   早先版本的 3 个用例正是因为「本机无 PG 未跑过」而带着两个缺陷入库：
 *   ① 拿 {@code isZero()} 断言一个按纪律折算为 {@code null} 的计数；
 *   ② 依赖「上一条用例已写入」的跨用例状态（JUnit 方法顺序不定 ⇒ 必然 flaky）。
 *   本版本每条用例自建数据，互不依赖。
 *
 * <p><b>⚠️⚠️ 两类写入路径必须物理分离</b>（2026-11-09 修正的核心）：
 * <ul>
 *   <li><b>机器判分</b> → {@code recordAttributionGrade}：只写宽表
 *       {@code attribution_correct}，<b>不碰</b> {@code sampled} / {@code human_verdict}，
 *       也不往 {@code agent_eval_sample} 插行；</li>
 *   <li><b>人工标注</b> → {@code recordEvalSample}：verdict 三值，
 *       回填 {@code sampled=TRUE} + {@code human_verdict}。</li>
 * </ul>
 * 混用的后果是「人工抽样覆盖率」把机器判分计成人审 —— 一个不报错、只是数字悄悄错的失真。
 * {@link #machineGradeMustNotPolluteHumanSampling()} 就是钉住这一点的守卫。
 *
 * <p><b>⚠️ 隔离纪律</b>：平台域表无 RLS，用管理角色直连。所有造数带本 IT 专属
 *   {@code tenant_id} 与 {@code trace_id} 前缀，清理只删自己的行。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
class JdbcAgentMetricAttributionIT {

    private static final String PREFIX = "attr-it-" + UUID.randomUUID().toString().replace("-", "") + "-";

    /** 每个用例各用一个租户：互不污染，也不依赖清理时机。 */
    private static final String TENANT_GRADE = UUID.randomUUID().toString();
    private static final String TENANT_RATE = UUID.randomUUID().toString();
    private static final String TENANT_EMPTY = UUID.randomUUID().toString();
    private static final String TENANT_HUMAN = UUID.randomUUID().toString();

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
    void reset() {
        purge();
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
    @DisplayName("✅ 机器判分写回宽表 attribution_correct（true/false 都如实写）")
    void machineGradeWritesMetricColumn() {
        String trace = PREFIX + "machine";
        insertMetric(trace, TENANT_GRADE, null);

        repo.recordAttributionGrade(trace, TENANT_GRADE, true);
        assertThat(readMetricBool("attribution_correct", trace)).isTrue();

        // 判 false 也必须落实（不是「只写 true」的假实现）
        repo.recordAttributionGrade(trace, TENANT_GRADE, false);
        assertThat(readMetricBool("attribution_correct", trace)).isFalse();
    }

    @Test
    @DisplayName("⛔ 机器判分绝不污染人审口径：sampled / human_verdict 保持 NULL，且不插 eval_sample")
    void machineGradeMustNotPolluteHumanSampling() {
        String trace = PREFIX + "nopollute";
        insertMetric(trace, TENANT_GRADE, null);

        repo.recordAttributionGrade(trace, TENANT_GRADE, true);

        // ① 宽表列写了
        assertThat(readMetricBool("attribution_correct", trace)).isTrue();
        // ② 但 sampled 必须仍是 NULL —— 否则「人工抽样覆盖率」把机器判分计成人审
        assertThat(readMetricBool("sampled", trace)).isNull();
        // ③ human_verdict 必须仍是 NULL —— 否则 false_ok 会凭空多出一条劣化样本
        assertThat(readMetricText("human_verdict", trace)).isNull();
        // ④ 人工标注表不得出现这条 trace 的行
        assertThat(countEvalSample(trace)).isZero();
    }

    @Test
    @DisplayName("✅ 人工标注路径不受影响：recordEvalSample 仍回填 sampled + human_verdict")
    void humanAnnotationStillBackfillsSampled() {
        String trace = PREFIX + "human";
        insertMetric(trace, TENANT_HUMAN, null);

        repo.recordEvalSample(new EvalSample(
                trace, TENANT_HUMAN, "SUCCESS", null, null, Boolean.TRUE,
                "人工复核", "MANUAL"));

        assertThat(countEvalSample(trace)).isEqualTo(1);
        assertThat(readEvalBool("attribution_correct", trace)).isTrue();
        assertThat(readMetricBool("sampled", trace)).isTrue();
        assertThat(readMetricText("human_verdict", trace)).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("✅ 回写带租户谓词：错租户写入必须 0 行命中（不跨租户改别人的行）")
    void gradeIsTenantScoped() {
        String trace = PREFIX + "scoped";
        insertMetric(trace, TENANT_GRADE, null);

        repo.recordAttributionGrade(trace, TENANT_EMPTY, true);

        assertThat(readMetricBool("attribution_correct", trace))
                .as("租户不匹配时不得改写该行")
                .isNull();
    }

    @Test
    @DisplayName("✅ n<30 不出占比：1 条已评级 → count=1 但 rate=null（噪声不是指标）")
    void singleGradedSampleIsNotARate() {
        String trace = PREFIX + "single";
        insertMetric(trace, TENANT_GRADE, null);
        repo.recordAttributionGrade(trace, TENANT_GRADE, true);

        Overview ov = repo.overview(q(TENANT_GRADE));

        assertThat(ov.attributionGradedCount()).isEqualTo(1L);
        assertThat(ov.attributionCorrectCount()).isEqualTo(1L);
        assertThat(ov.attributionCorrectRate()).isNull();
    }

    @Test
    @DisplayName("✅ 足量样本时算出真占比（30 真 / 5 假 → 0.8571）")
    void computesRealRateAtSufficientSample() {
        for (int i = 0; i < 35; i++) {
            insertMetric(PREFIX + "agg-" + i, TENANT_RATE, i < 30); // 30 true + 5 false
        }

        Overview ov = repo.overview(q(TENANT_RATE));

        assertThat(ov.attributionGradedCount()).isEqualTo(35L);
        assertThat(ov.attributionCorrectCount()).isEqualTo(30L);
        // 30/35 = 0.857142857...
        assertThat(ov.attributionCorrectRate()).isBetween(0.8571, 0.8572);
    }

    @Test
    @DisplayName("✅ 未评级租户：graded 折算为 null、rate=null（看板显示「未启用」，绝不 0%）")
    void noGradedSampleYieldsNullRate() {
        insertMetric(PREFIX + "ungraded", TENANT_EMPTY, null);

        Overview ov = repo.overview(q(TENANT_EMPTY));

        // ⚠️ 计数 0 按项目既有纪律折算为 null（与 auto_checked / sampled 同口径：
        //    「一条都没评级」与「没采集」在看板上都必须是「未启用」，
        //    绝不能渲染成 0%，否则会读成「归因正确率 0%」这种灾难性误判）。
        assertThat(ov.attributionGradedCount()).isNull();
        assertThat(ov.attributionCorrectCount()).isZero();
        assertThat(ov.attributionCorrectRate()).isNull();
    }

    // ── 读侧 ────────────────────────────────────────────────────

    /** 读宽表的布尔列；行不存在或值为 NULL 时返回 null。 */
    private static Boolean readMetricBool(String column, String trace) {
        return (Boolean) readOne("SELECT " + column + " FROM platform.agent_trace_metric WHERE trace_id = ?", trace);
    }

    /** 读宽表的文本列；行不存在或值为 NULL 时返回 null。 */
    private static String readMetricText(String column, String trace) {
        return (String) readOne("SELECT " + column + " FROM platform.agent_trace_metric WHERE trace_id = ?", trace);
    }

    private static Boolean readEvalBool(String column, String trace) {
        return (Boolean) readOne("SELECT " + column + " FROM platform.agent_eval_sample WHERE trace_id = ?", trace);
    }

    private static Object readOne(String sql, String trace) {
        try (Connection c = adminPool.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, trace);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                Object v = rs.getObject(1);
                return rs.wasNull() ? null : v;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取列 " + sql + " 失败: " + trace, e);
        }
    }

    private static long countEvalSample(String trace) {
        try (Connection c = adminPool.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM platform.agent_eval_sample WHERE trace_id = ?")) {
            ps.setString(1, trace);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("统计 eval_sample 失败: " + trace, e);
        }
    }
}
