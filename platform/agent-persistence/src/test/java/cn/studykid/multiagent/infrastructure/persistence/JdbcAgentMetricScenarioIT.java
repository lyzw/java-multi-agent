package cn.studykid.multiagent.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.pool.DruidDataSource;

import cn.studykid.multiagent.ports.metrics.AgentMetricPort.MetricQuery;
import cn.studykid.multiagent.ports.metrics.AgentMetricPort.ScenarioCount;

/**
 * 场景筛选「整页同口径」的真实库闭环（质量看板收尾）。
 *
 * <p><b>⚠️ 为什么必须是 IT 而不是单测</b>：本轮修的两类缺陷都只在真跑 SQL 时才暴露 ——
 * <ol>
 *   <li>工具明细表没有 scenario 列，用 {@code trace_id} 子查询跟随过滤；
 *       {@code applyFilters} 与 {@code bindDims} 的条件/占位符顺序一旦错位，
 *       运行期才会报「参数下标越界」，mock 单测全绿也照不出。</li>
 *   <li>「未标注」哨兵要译成 {@code scenario IS NULL}，翻译错了不报错，
 *       只是悄悄选出 0 行。</li>
 * </ol>
 *
 * <p><b>⚠️ 隔离纪律</b>：平台域表无 RLS，用管理角色直连（与「RLS 隔离必须用
 * app_agent 验」的铁律不冲突）。所有查询都带本 IT 专属 {@code tenant_id}，
 * 造数/清理只按一次性 UUID 前缀删自己的行，绝不碰别的 IT 或种子数据。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
class JdbcAgentMetricScenarioIT {

    private static final String PREFIX = "scenario-it-" + UUID.randomUUID().toString().replace("-", "") + "-";
    private static final String MATH = PREFIX + "math";
    private static final String ENGLISH = PREFIX + "english";
    private static final String UNLABELED = PREFIX + "unlabeled";

    private static DruidDataSource adminPool;
    private static JdbcAgentMetricRepository repo;
    private static String tenantId;

    @BeforeAll
    static void setUp() {
        DruidDataSource cfg = new DruidDataSource();
        cfg.setMaxWait(10_000L);
        cfg.setConnectTimeout(10_000);
        cfg.setUrl(System.getenv("IT_PG_URL"));
        cfg.setUsername(System.getenv().getOrDefault("IT_PG_ADMIN_USER", "agent"));
        String pwd = System.getenv("IT_PG_ADMIN_PASSWORD");
        if (pwd != null && !pwd.isBlank()) {
            cfg.setPassword(pwd);
        }
        cfg.setMaxActive(3);
        adminPool = cfg;
        repo = new JdbcAgentMetricRepository(adminPool);
        tenantId = UUID.randomUUID().toString();
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
        insertMetric(MATH, "数学:一元二次方程", "SUCCEEDED", "COMPLETED", 100L, 200L, 1000L, 5);
        insertMetric(ENGLISH, "英语:完形填空", "FAILED", "MODEL_ERROR", 30_000L, 0L, 3000L, 3);
        insertMetric(UNLABELED, null, "SUCCEEDED", "COMPLETED", 100L, 200L, 2000L, 2);
        insertTool(MATH, 5);
        insertTool(ENGLISH, 3);
        insertTool(UNLABELED, 2);
    }

    /** 24 小时窗口 + 本 IT 专属租户；scenario 为 null 表示全量。 */
    private static MetricQuery q(String scenario) {
        Instant to = Instant.now().plus(Duration.ofMinutes(1));
        return new MetricQuery(to.minus(Duration.ofHours(24)), to, tenantId, null, null, scenario);
    }

    private static void purge() {
        try (Connection c = adminPool.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM platform.agent_trace_tool_call WHERE trace_id LIKE '" + PREFIX + "%'");
            s.executeUpdate("DELETE FROM platform.agent_trace_metric WHERE trace_id LIKE '" + PREFIX + "%'");
        } catch (SQLException e) {
            throw new IllegalStateException("清理场景 IT 数据失败", e);
        }
    }

    private static void insertMetric(String traceId, String scenario, String state, String reason,
                                     long inputTokens, long outputTokens, long durationMs, int toolCalls) {
        insertMetric(traceId, scenario, state, reason, inputTokens, outputTokens, durationMs,
                toolCalls, tenantId);
    }

    private static void insertMetric(String traceId, String scenario, String state, String reason,
                                     long inputTokens, long outputTokens, long durationMs,
                                     int toolCalls, String tenant) {
        String sql = """
                INSERT INTO platform.agent_trace_metric
                    (trace_id, tenant_id, run_id, scenario, started_at, duration_ms,
                     terminal_state, termination_reason, input_tokens, output_tokens,
                     tool_rounds, tool_call_total, tool_call_failed, tool_call_invalid,
                     loop_suspected_count, max_rounds_reached, trace_id_source)
                VALUES (?, ?::uuid, ?::uuid, ?, now() - interval '1 hour', ?,
                        ?, ?, ?, ?,
                        ?, ?, 0, 0,
                        0, false, 'RUN_TRACE_ID')
                """;
        try (Connection c = adminPool.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, traceId);
            ps.setString(2, tenant);
            ps.setString(3, UUID.randomUUID().toString());
            ps.setString(4, scenario);
            ps.setLong(5, durationMs);
            ps.setString(6, state);
            ps.setString(7, reason);
            ps.setLong(8, inputTokens);
            ps.setLong(9, outputTokens);
            ps.setInt(10, 1);
            ps.setInt(11, toolCalls);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("插入场景 IT 指标行失败: " + traceId, e);
        }
    }

    private static void insertTool(String traceId, long callCount) {
        String sql = """
                INSERT INTO platform.agent_trace_tool_call
                    (trace_id, tenant_id, tool_name, call_count, failed_count,
                     total_duration_ms, max_duration_ms, started_at)
                VALUES (?, ?::uuid, 'solver', ?, 0, 1000, 500, now() - interval '1 hour')
                """;
        try (Connection c = adminPool.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, traceId);
            ps.setString(2, tenantId);
            ps.setLong(3, callCount);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("插入场景 IT 工具行失败: " + traceId, e);
        }
    }

    @Test
    @DisplayName("✅ 五个读端点同口径：总览/分布/耗时/工具/异常都只看选中场景")
    void allFivePanelsShareScenarioFilter() {
        // 全量基线：3 条 Trace、工具调用合计 10 次、1 条超大 token 异常
        assertThat(repo.overview(q(null)).traceCount()).isEqualTo(3);
        assertThat(repo.toolBreakdown(q(null))).singleElement()
                .satisfies(t -> assertThat(t.callCount()).isEqualTo(10));
        assertThat(repo.anomalies(q(null), 20)).hasSize(1);

        MetricQuery math = q("数学:一元二次方程");
        assertThat(repo.overview(math).traceCount()).isEqualTo(1);
        assertThat(repo.terminationDistribution(math).stream()
                .mapToLong(r -> r.count()).sum()).isEqualTo(1);
        assertThat(repo.latency(math).p50()).isEqualTo(1000.0);
        // ⚠️ 关键守卫：工具明细表没有 scenario 列，靠 trace_id 反查宽表跟随过滤。
        //    漏了这段子查询，这里会是 10（全量），而总览已是 1 —— 合计对不上却不报错。
        assertThat(repo.toolBreakdown(math)).singleElement()
                .satisfies(t -> assertThat(t.callCount()).isEqualTo(5));
        assertThat(repo.anomalies(math, 20)).isEmpty();
    }

    @Test
    @DisplayName("✅ 「未标注」哨兵译成 IS NULL，选中 scenario 为 NULL 的那一行")
    void unlabeledSentinelSelectsNullScenario() {
        // ⚠️ 字面量钉死，不引用 AgentMetricPort.UNLABELED_SCENARIO（守卫不能引用被测实现常量）
        MetricQuery unlabeled = q("(unlabeled)");

        assertThat(repo.overview(unlabeled).traceCount()).isEqualTo(1);
        assertThat(repo.terminationDistribution(unlabeled).stream()
                .mapToLong(r -> r.count()).sum()).isEqualTo(1);
        assertThat(repo.latency(unlabeled).p50()).isEqualTo(2000.0);
        assertThat(repo.toolBreakdown(unlabeled)).singleElement()
                .satisfies(t -> assertThat(t.callCount()).isEqualTo(2));
        assertThat(repo.anomalies(unlabeled, 20)).isEmpty();
    }

    @Test
    @DisplayName("✅ 英语场景单独成组，超大 token 异常只在该场景出现")
    void anotherScenarioIsIndependent() {
        MetricQuery english = q("英语:完形填空");

        assertThat(repo.overview(english).traceCount()).isEqualTo(1);
        assertThat(repo.toolBreakdown(english)).singleElement()
                .satisfies(t -> assertThat(t.callCount()).isEqualTo(3));
        assertThat(repo.anomalies(english, 20)).hasSize(1);
    }

    @Test
    @DisplayName("✅ /scenarios 是筛选器自身数据源：调用方误传 scenario 也不得自锁")
    void scenariosEnumerationIsNotPollutedByCurrentFilter() {
        // 刻意传一个已选中的场景，模拟「控制器忘了清空维度」的回归
        List<ScenarioCount> groups = repo.scenarios(q("数学:一元二次方程"));

        assertThat(groups).extracting(ScenarioCount::value)
                .containsExactlyInAnyOrder("数学:一元二次方程", "英语:完形填空", "(unlabeled)");
        assertThat(groups).filteredOn(g -> g.value().equals("(unlabeled)"))
                .singleElement()
                .satisfies(g -> {
                    assertThat(g.label()).isEqualTo("未标注");
                    assertThat(g.traceCount()).isEqualTo(1);
                });
    }

    @Test
    @DisplayName("✅ 场景枚举也带走租户边界，不把别的租户标签混进下拉")
    void scenariosEnumerationRespectsTenantBoundary() {
        String otherTrace = PREFIX + "other-tenant";
        insertMetric(otherTrace, "别的租户:独占场景", "SUCCEEDED", "COMPLETED",
                100L, 100L, 1000L, 1, UUID.randomUUID().toString());

        // ⚠️ 防空转：先证明确实插进去了，否则「doesNotContain」会因为没数据而恒绿
        assertThat(countByTraceId(otherTrace)).isEqualTo(1);
        assertThat(repo.scenarios(q(null))).extracting(ScenarioCount::value)
                .doesNotContain("别的租户:独占场景");
    }

    private static long countByTraceId(String traceId) {
        try (Connection c = adminPool.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM platform.agent_trace_metric WHERE trace_id = ?")) {
            ps.setString(1, traceId);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("核对场景 IT 造数失败", e);
        }
    }
}
