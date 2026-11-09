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

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import cn.studykid.multiagent.ports.metrics.AgentMetricPort.MetricQuery;
import cn.studykid.multiagent.ports.metrics.AgentMetricPort.SamplingCandidate;

/**
 * 分层抽样的<b>候选集排序</b>真库守卫（P3 · §7.2 · 2026-11-09 自查修复）。
 *
 * <p><b>⚠️ 为什么必须是 IT</b>：缺陷 A 的本质是「分层在 Java 内存里做、截断在 SQL 里做」，
 * 于是 {@code ORDER BY started_at DESC LIMIT 500} 会把**时间较早的异常样本**整体挤出候选集。
 * 这个错配在单元测试里照不出来（测试数据只有 20 条，远小于 LIMIT），
 * 只有真跑 SQL 并让「异常样本比正常样本更早」才能暴露。
 *
 * <p><b>⚠️ 断言的是「排序契约」而不是「恰好如此」</b>：
 * 只断言候选集里含异常样本是不够的 —— 初版实现里异常样本也可能因为
 * 「测试数据少」而侥幸入选。故这里造 600 条正常样本 + 1 条**时间最早**的异常样本，
 * 候选上限给 100：正常样本足以把异常挤出「最近 100 条」，只有真按异常优先排序才能入选。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
class JdbcAgentMetricSamplingCandidatesIT {

    private static final String PREFIX = "samp-it-" + UUID.randomUUID().toString().replace("-", "") + "-";
    private static final String TENANT = UUID.randomUUID().toString();

    /** 正常样本数：必须远大于候选上限，才能形成「挤出」压力。 */
    private static final int NORMAL_ROWS = 600;

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

    private static void purge() {
        try (Connection c = adminPool.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM platform.agent_trace_metric WHERE trace_id LIKE '" + PREFIX + "%'");
        } catch (SQLException e) {
            throw new IllegalStateException("清理 sampling IT 数据失败", e);
        }
    }

    private static MetricQuery q() {
        Instant to = Instant.now().plus(Duration.ofMinutes(1));
        return new MetricQuery(to.minus(Duration.ofHours(24)), to, TENANT, null, null, null);
    }

    /**
     * 造一条候选行。
     *
     * @param startedAt   采集时刻（决定「最近 N 条」里的位置）
     * @param loopCount   循环计数（&gt;0 ⇒ 异常）
     * @param autoChecked L1 是否判过（null ⇒ 覆盖缺口）
     */
    private static void insert(String traceId, Instant startedAt, int loopCount, Boolean autoChecked) {
        String sql = """
                INSERT INTO platform.agent_trace_metric
                    (trace_id, tenant_id, run_id, scenario, started_at, duration_ms,
                     terminal_state, termination_reason, input_tokens, output_tokens,
                     tool_rounds, tool_call_total, tool_call_failed, tool_call_invalid,
                     loop_suspected_count, max_rounds_reached, auto_checked, trace_id_source)
                VALUES (?, ?::uuid, ?::uuid, '数学:一元二次方程', ?, 1000,
                        'SUCCEEDED', 'COMPLETED', 100, 200,
                        1, 5, 0, 0,
                        ?, false, ?, 'RUN_TRACE_ID')
                """;
        try (Connection c = adminPool.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, traceId);
            ps.setString(2, TENANT);
            ps.setString(3, UUID.randomUUID().toString());
            ps.setTimestamp(4, java.sql.Timestamp.from(startedAt));
            ps.setInt(5, loopCount);
            ps.setObject(6, autoChecked);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("插入 sampling IT 指标行失败: " + traceId, e);
        }
    }

    @Test
    @DisplayName("⚠️⚠️ 时间最早的异常样本必须进候选集（排序在 SQL 层分层，不在内存里）")
    void anomalyOutsideRecentWindowStillSelected() {
        Instant now = Instant.now();
        // 异常样本：最早（23 小时前）—— 若按 started_at DESC 取最近 100 条，它必然被挤掉
        String anomalyTrace = PREFIX + "anomaly";
        insert(anomalyTrace, now.minus(Duration.ofHours(23)), 3, Boolean.TRUE);

        // 600 条正常样本：全部比异常样本新 ⇒ 「最近 100 条」全是它们
        for (int i = 0; i < NORMAL_ROWS; i++) {
            insert(PREFIX + "normal-" + i, now.minus(Duration.ofMinutes(i)), 0, Boolean.TRUE);
        }

        List<SamplingCandidate> candidates = repo.samplingCandidates(q(), 100);

        assertThat(candidates)
                .as("⚠️⚠️ 异常样本被挤出候选集 = 抽样器的核心目的失效（最该审的反而永远抽不到）。"
                        + "根因：分层判定在 Java 内存、截断在 SQL，两者错配。"
                        + "修复：把分层谓词前置到 ORDER BY")
                .extracting(SamplingCandidate::traceId)
                .contains(anomalyTrace);
    }

    @Test
    @DisplayName("⚠️ 覆盖缺口样本优先于普通样本（auto_checked IS NULL 先于普通）")
    void coverageGapRankedBeforeNormal() {
        Instant now = Instant.now();
        String gapTrace = PREFIX + "gap";
        insert(gapTrace, now.minus(Duration.ofHours(23)), 0, null);   // 覆盖缺口

        for (int i = 0; i < NORMAL_ROWS; i++) {
            insert(PREFIX + "normal-" + i, now.minus(Duration.ofMinutes(i)), 0, Boolean.TRUE);
        }

        List<SamplingCandidate> candidates = repo.samplingCandidates(q(), 100);

        assertThat(candidates)
                .as("覆盖缺口样本是「机器没判过、只能人看」的那一层，应优先于普通样本")
                .extracting(SamplingCandidate::traceId)
                .contains(gapTrace);
    }

    @Test
    @DisplayName("✅ 已被人审过的样本（sampled IS TRUE）不得再进候选")
    void alreadySampledExcluded() {
        Instant now = Instant.now();
        insert(PREFIX + "done", now.minus(Duration.ofHours(1)), 0, Boolean.TRUE);
        // 标记为已抽样
        try (Connection c = adminPool.getConnection(); PreparedStatement ps = c.prepareStatement(
                "UPDATE platform.agent_trace_metric SET sampled = TRUE WHERE trace_id = ?")) {
            ps.setString(1, PREFIX + "done");
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("标记 sampled 失败", e);
        }

        List<SamplingCandidate> candidates = repo.samplingCandidates(q(), 100);

        assertThat(candidates)
                .as("已审过的样本重复入队 = reviewer 白看一遍")
                .extracting(SamplingCandidate::traceId)
                .doesNotContain(PREFIX + "done");
    }
}
