package cn.studykid.multiagent.infrastructure.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.ports.metrics.AgentMetricPort;

/**
 * Agent 效果指标 JDBC 仓储（P7）。
 *
 * <p><b>⚠️ 指标表全部在 {@code platform} schema（无 RLS）</b>，故此处一律用
 * {@code openSystem()} 系统连接。事实源（{@code public.run} 等带 FORCE RLS）
 * 的读取<b>不在本类</b>——那必须绑定租户上下文，由采集器负责（见
 * {@code AgentMetricCollector}）。<b>读在租户域、写在平台域</b>，两侧边界都干净。
 *
 * <p><b>⚠️ 未采集 = {@code null}，绝不等于 0</b>：读列一律用 {@code getObject + wasNull}
 * 判定，聚合用 {@code count(col)} 而非 {@code coalesce(...,0)}，
 * 让「没测过」与「测了是 0」在 SQL 层就分得开。
 */
@Repository
public class JdbcAgentMetricRepository implements AgentMetricPort {

    private final DataSource dataSource;

    public JdbcAgentMetricRepository(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    // ══════════════════════════════════════════════════════════════
    // 写入
    // ══════════════════════════════════════════════════════════════

    @Override
    public void upsert(@NonNull TraceMetric m) {
        String sql = """
                INSERT INTO platform.agent_trace_metric (
                    trace_id, tenant_id, run_id, agent_id, agent_version, scenario, model_id,
                    started_at, ended_at, duration_ms, terminal_state, termination_reason,
                    input_tokens, output_tokens, cost_amount, currency,
                    tool_rounds, tool_call_total, tool_call_failed, tool_call_invalid,
                    tool_call_duplicate, loop_suspected_count, max_rounds_reached,
                    clarification_count, think_ms_total, tool_ms_total,
                    auto_checked, auto_verified, sampled, human_verdict,
                    hallucination, instruction_followed,
                    feedback_up, feedback_down, retry_count, abandoned,
                    safety_blocked_count, unauthorized_attempt_count, sensitive_output_hits,
                    trace_id_source, computed_at
                ) VALUES (?, ?::uuid, ?::uuid, ?::uuid, ?, ?, ?,
                          ?, ?, ?, ?, ?,
                          ?, ?, ?, ?,
                          ?, ?, ?, ?,
                          ?, ?, ?,
                          ?, ?, ?,
                          ?, ?, ?, ?,
                          ?, ?,
                          ?, ?, ?, ?,
                          ?, ?, ?,
                          ?, now())
                ON CONFLICT (trace_id) DO UPDATE SET
                    tenant_id = EXCLUDED.tenant_id,
                    run_id = EXCLUDED.run_id,
                    agent_id = EXCLUDED.agent_id,
                    agent_version = EXCLUDED.agent_version,
                    scenario = EXCLUDED.scenario,
                    model_id = EXCLUDED.model_id,
                    started_at = EXCLUDED.started_at,
                    ended_at = EXCLUDED.ended_at,
                    duration_ms = EXCLUDED.duration_ms,
                    terminal_state = EXCLUDED.terminal_state,
                    termination_reason = EXCLUDED.termination_reason,
                    input_tokens = EXCLUDED.input_tokens,
                    output_tokens = EXCLUDED.output_tokens,
                    cost_amount = EXCLUDED.cost_amount,
                    currency = EXCLUDED.currency,
                    tool_rounds = EXCLUDED.tool_rounds,
                    tool_call_total = EXCLUDED.tool_call_total,
                    tool_call_failed = EXCLUDED.tool_call_failed,
                    tool_call_invalid = EXCLUDED.tool_call_invalid,
                    tool_call_duplicate = EXCLUDED.tool_call_duplicate,
                    loop_suspected_count = EXCLUDED.loop_suspected_count,
                    max_rounds_reached = EXCLUDED.max_rounds_reached,
                    clarification_count = EXCLUDED.clarification_count,
                    think_ms_total = EXCLUDED.think_ms_total,
                    tool_ms_total = EXCLUDED.tool_ms_total,
                    computed_at = now()
                """;
        try (Connection conn = openSystem();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, m.traceId());
            ps.setString(i++, m.tenantId());
            ps.setString(i++, m.runId());
            ps.setString(i++, m.agentId());
            setNullableInt(ps, i++, m.agentVersion());
            ps.setString(i++, m.scenario());
            ps.setString(i++, m.modelId());
            ps.setTimestamp(i++, Timestamp.from(m.startedAt()));
            ps.setTimestamp(i++, m.endedAt() == null ? null : Timestamp.from(m.endedAt()));
            setNullableLong(ps, i++, m.durationMs());
            ps.setString(i++, m.terminalState());
            ps.setString(i++, m.terminationReason().name());
            setNullableLong(ps, i++, m.inputTokens());
            setNullableLong(ps, i++, m.outputTokens());
            ps.setBigDecimal(i++, m.costAmount());
            ps.setString(i++, m.currency());
            setNullableInt(ps, i++, m.toolRounds());
            setNullableInt(ps, i++, m.toolCallTotal());
            setNullableInt(ps, i++, m.toolCallFailed());
            setNullableInt(ps, i++, m.toolCallInvalid());
            setNullableInt(ps, i++, m.toolCallDuplicate());
            setNullableInt(ps, i++, m.loopSuspectedCount());
            setNullableBoolean(ps, i++, m.maxRoundsReached());
            setNullableInt(ps, i++, m.clarificationCount());
            setNullableLong(ps, i++, m.thinkMsTotal());
            setNullableLong(ps, i++, m.toolMsTotal());
            setNullableBoolean(ps, i++, m.autoChecked());
            setNullableBoolean(ps, i++, m.autoVerified());
            setNullableBoolean(ps, i++, m.sampled());
            ps.setString(i++, m.humanVerdict());
            setNullableBoolean(ps, i++, m.hallucination());
            setNullableBoolean(ps, i++, m.instructionFollowed());
            setNullableInt(ps, i++, m.feedbackUp());
            setNullableInt(ps, i++, m.feedbackDown());
            setNullableInt(ps, i++, m.retryCount());
            setNullableBoolean(ps, i++, m.abandoned());
            setNullableInt(ps, i++, m.safetyBlockedCount());
            setNullableInt(ps, i++, m.unauthorizedAttemptCount());
            setNullableInt(ps, i++, m.sensitiveOutputHits());
            ps.setString(i++, m.traceIdSource().name());
            ps.executeUpdate();
            conn.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("写入 Trace 指标失败: " + m.traceId(), e);
        }
    }

    @Override
    public void replaceToolCalls(@NonNull String traceId, @NonNull List<ToolCallRow> rows) {
        try (Connection conn = openSystem()) {
            try (PreparedStatement del = conn.prepareStatement(
                    "DELETE FROM platform.agent_trace_tool_call WHERE trace_id = ?")) {
                del.setString(1, traceId);
                del.executeUpdate();
            }
            if (rows.isEmpty()) {
                conn.commit();
                return;
            }
            String sql = """
                    INSERT INTO platform.agent_trace_tool_call
                        (trace_id, tenant_id, tool_name, call_count, failed_count,
                         invalid_count, duplicate_count, total_duration_ms, max_duration_ms, started_at)
                    VALUES (?, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?)
                    """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (ToolCallRow r : rows) {
                    int i = 1;
                    ps.setString(i++, r.traceId());
                    ps.setString(i++, r.tenantId());
                    ps.setString(i++, r.toolName());
                    ps.setLong(i++, r.callCount());
                    ps.setLong(i++, r.failedCount());
                    ps.setLong(i++, r.invalidCount());
                    ps.setLong(i++, r.duplicateCount());
                    setNullableLong(ps, i++, r.totalDurationMs());
                    setNullableLong(ps, i++, r.maxDurationMs());
                    ps.setTimestamp(i++, Timestamp.from(r.startedAt()));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            conn.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("覆盖 Trace 工具维度失败: " + traceId, e);
        }
    }

    @Override
    public @NonNull Optional<TraceMetric> findByTraceId(@NonNull String traceId) {
        String sql = "SELECT * FROM platform.agent_trace_metric WHERE trace_id = ?";
        try (Connection conn = openSystem();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, traceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    conn.commit();
                    return Optional.empty();
                }
                Optional<TraceMetric> out = Optional.of(mapMetric(rs));
                conn.commit();
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取 Trace 指标失败", e);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 聚合查询
    // ══════════════════════════════════════════════════════════════

    @Override
    public @NonNull Overview overview(@NonNull MetricQuery query) {
        MetricQuery q = query.clamp();
        String sql = """
                SELECT
                    count(*)                                                     AS trace_count,
                    count(*) FILTER (WHERE terminal_state = 'SUCCEEDED')          AS succeeded,
                    avg(duration_ms)                                              AS avg_duration,
                    sum(input_tokens)                                             AS in_tokens,
                    sum(output_tokens)                                            AS out_tokens,
                    sum(cost_amount)                                              AS cost,
                    avg(cost_amount)                                              AS avg_cost,
                    count(*) FILTER (WHERE coalesce(input_tokens,0) + coalesce(output_tokens,0) > 20000)
                                                                                 AS oversized,
                    avg(tool_rounds)                                              AS avg_rounds,
                    count(*) FILTER (WHERE max_rounds_reached)                    AS max_rounds,
                    sum(tool_call_total)                                          AS calls,
                    sum(tool_call_invalid)                                        AS invalid,
                    count(*) FILTER (WHERE loop_suspected_count > 0)              AS loops,
                    count(auto_checked)                                           AS auto_checked,
                    count(*) FILTER (WHERE auto_verified)                         AS auto_verified,
                    count(sampled)                                                AS sampled,
                    count(*) FILTER (WHERE human_verdict = 'SUCCESS')             AS human_ok,
                    count(*) FILTER (WHERE auto_verified AND sampled
                                      AND human_verdict <> 'SUCCESS')             AS false_ok,
                    count(*) FILTER (WHERE auto_verified AND sampled)             AS false_denom,
                    count(hallucination) FILTER (WHERE hallucination)             AS hallucinated,
                    count(*) FILTER (WHERE instruction_followed)                  AS followed,
                    count(instruction_followed)                                   AS followed_denom,
                    sum(feedback_up)                                              AS fb_up,
                    sum(feedback_down)                                            AS fb_down,
                    count(*) FILTER (WHERE abandoned)                             AS abandoned,
                    count(*) FILTER (WHERE safety_blocked_count > 0)              AS safety,
                    count(*) FILTER (WHERE unauthorized_attempt_count > 0)        AS unauthorized,
                    count(*) FILTER (WHERE sensitive_output_hits > 0)             AS sensitive
                FROM platform.agent_trace_metric
                WHERE started_at >= ? AND started_at < ?
                """;
        try (Connection conn = openSystem();
             PreparedStatement ps = conn.prepareStatement(applyFilters(sql, q, false))) {
            int i = bindRange(ps, q, 1);
            bindDims(ps, q, i, false);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                Overview out = mapOverview(rs);
                conn.commit();
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("聚合 Trace 指标失败", e);
        }
    }

    @Override
    public @NonNull List<ReasonCount> terminationDistribution(@NonNull MetricQuery query) {
        MetricQuery q = query.clamp();
        String sql = """
                SELECT termination_reason, count(*) AS c
                FROM platform.agent_trace_metric
                WHERE started_at >= ? AND started_at < ?
                """;
        sql = applyFilters(sql, q, false) + " GROUP BY termination_reason ORDER BY c DESC";
        List<ReasonCount> out = new ArrayList<>();
        try (Connection conn = openSystem();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = bindRange(ps, q, 1);
            bindDims(ps, q, i, false);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new ReasonCount(
                            TerminationReason.valueOf(rs.getString("termination_reason")),
                            rs.getLong("c")));
                }
            }
            conn.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("统计终止原因分布失败", e);
        }
        return out;
    }

    @Override
    public @NonNull LatencyPercentiles latency(@NonNull MetricQuery query) {
        MetricQuery q = query.clamp();
        // ⚠️ 分位数必须查明细表 —— 物化视图只有 count/sum，还原不出 P95。
        String sql = """
                SELECT percentile_cont(0.50) WITHIN GROUP (ORDER BY duration_ms) AS p50,
                       percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms) AS p95,
                       percentile_cont(0.99) WITHIN GROUP (ORDER BY duration_ms) AS p99
                FROM platform.agent_trace_metric
                WHERE started_at >= ? AND started_at < ? AND duration_ms IS NOT NULL
                """;
        try (Connection conn = openSystem();
             PreparedStatement ps = conn.prepareStatement(applyFilters(sql, q, false))) {
            int i = bindRange(ps, q, 1);
            bindDims(ps, q, i, false);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                LatencyPercentiles out = new LatencyPercentiles(
                        nullableDouble(rs, "p50"), nullableDouble(rs, "p95"), nullableDouble(rs, "p99"));
                conn.commit();
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("计算耗时分位数失败", e);
        }
    }

    @Override
    public @NonNull List<ToolMetric> toolBreakdown(@NonNull MetricQuery query) {
        MetricQuery q = query.clamp();
        // 平台域表：agent_trace_tool_call（public.tool_operation 带 RLS，系统连接读不到）
        String sql = """
                SELECT tool_name,
                       sum(call_count)                              AS calls,
                       sum(failed_count)                            AS failed,
                       sum(total_duration_ms)                       AS total_ms,
                       max(max_duration_ms)                         AS max_ms
                FROM platform.agent_trace_tool_call
                WHERE started_at >= ? AND started_at < ?
                """;
        sql = applyFilters(sql, q, true);
        sql += """
                 GROUP BY tool_name
                 ORDER BY calls DESC, tool_name
                 LIMIT 50
                 """;
        List<ToolMetric> out = new ArrayList<>();
        try (Connection conn = openSystem();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = bindRange(ps, q, 1);
            bindDims(ps, q, i, true);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long calls = rs.getLong("calls");
                    long failed = rs.getLong("failed");
                    double totalMs = rs.getDouble("total_ms");
                    Long maxMs = nullableLong(rs, "max_ms");
                    out.add(new ToolMetric(
                            rs.getString("tool_name"),
                            calls,
                            failed,
                            calls == 0 ? null : (double) failed / calls,
                            calls == 0 ? null : totalMs / calls,
                            maxMs == null ? null : (double) maxMs));
                }
            }
            conn.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("统计工具维度指标失败", e);
        }
        return out;
    }

    @Override
    public @NonNull List<AnomalyTrace> anomalies(@NonNull MetricQuery query, int limit) {
        MetricQuery q = query.clamp();
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        // 三类异常一次扫出，按严重度排序：超大 token（疑似死循环）> 循环调用 > 触达轮次上限
        String sql = """
                SELECT trace_id, started_at, tool_rounds, loop_suspected_count,
                       coalesce(input_tokens,0) + coalesce(output_tokens,0) AS total_tokens
                FROM platform.agent_trace_metric
                WHERE started_at >= ? AND started_at < ?
                  AND (coalesce(input_tokens,0) + coalesce(output_tokens,0) > 20000
                       OR loop_suspected_count > 0
                       OR max_rounds_reached)
                """;
        sql = applyFilters(sql, q, false);
        sql += """
                 ORDER BY (coalesce(input_tokens,0) + coalesce(output_tokens,0)) DESC,
                          started_at DESC
                 LIMIT ?
                 """;
        List<AnomalyTrace> out = new ArrayList<>();
        try (Connection conn = openSystem();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = bindRange(ps, q, 1);
            i = bindDims(ps, q, i, false);
            ps.setInt(i, safeLimit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Long tokens = nullableLong(rs, "total_tokens");
                    Integer loops = nullableInt(rs, "loop_suspected_count");
                    AnomalyTrace.AnomalyKind kind =
                            (tokens != null && tokens > OVERSIZED_TOKEN_THRESHOLD)
                                    ? AnomalyTrace.AnomalyKind.OVERSIZED_TOKEN
                                    : (loops != null && loops > 0)
                                            ? AnomalyTrace.AnomalyKind.LOOP_SUSPECTED
                                            : AnomalyTrace.AnomalyKind.MAX_ROUNDS;
                    out.add(new AnomalyTrace(
                            rs.getString("trace_id"),
                            kind,
                            tokens,
                            nullableInt(rs, "tool_rounds"),
                            loops,
                            rs.getTimestamp("started_at").toInstant()));
                }
            }
            conn.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("查询异常 Trace 失败", e);
        }
        return out;
    }

    /** 超大 token 阈值：超过它通常是死循环而不是「任务真的复杂」。 */
    public static final long OVERSIZED_TOKEN_THRESHOLD = 20_000L;

    // ══════════════════════════════════════════════════════════════
    // 回填录入
    // ══════════════════════════════════════════════════════════════

    @Override
    public void recordEvalSample(@NonNull EvalSample s) {
        String insert = """
                INSERT INTO platform.agent_eval_sample
                    (trace_id, tenant_id, verdict, hallucination, instruction_followed, note, sampler)
                VALUES (?, ?::uuid, ?, ?, ?, ?, ?)
                """;
        // ⚠️ 回写用「最新一条标注为准」，重复标注不叠加样本量（否则同一样本被算多次）
        String backfill = """
                UPDATE platform.agent_trace_metric SET
                    sampled = TRUE,
                    human_verdict = ?,
                    hallucination = ?,
                    instruction_followed = ?
                WHERE trace_id = ?
                """;
        try (Connection conn = openSystem()) {
            try (PreparedStatement ps = conn.prepareStatement(insert)) {
                ps.setString(1, s.traceId());
                ps.setString(2, s.tenantId());
                ps.setString(3, s.verdict());
                setNullableBoolean(ps, 4, s.hallucination());
                setNullableBoolean(ps, 5, s.instructionFollowed());
                ps.setString(6, s.note());
                ps.setString(7, s.sampler());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(backfill)) {
                ps.setString(1, s.verdict());
                setNullableBoolean(ps, 2, s.hallucination());
                setNullableBoolean(ps, 3, s.instructionFollowed());
                ps.setString(4, s.traceId());
                ps.executeUpdate();
            }
            conn.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("录入抽样标注失败", e);
        }
    }

    @Override
    public void recordFeedback(@NonNull FeedbackEvent e) {
        String insert = """
                INSERT INTO platform.agent_trace_feedback (trace_id, tenant_id, kind)
                VALUES (?, ?::uuid, ?)
                """;
        // ⚠️ 反馈是事件流（可能多次），故用累加而非覆盖
        // ⚠️⚠️ 回填**必须带租户谓词**：本连接是系统连接（openSystem），
        //    不受 RLS 约束；若只按 trace_id 更新，租户 A 提交一条属于
        //    租户 B 的 traceId 时，就会改写 B 的指标行（跨租户写入）。
        String backfill = """
                UPDATE platform.agent_trace_metric SET
                    feedback_up   = coalesce(feedback_up, 0)
                                    + CASE WHEN ? = 'THUMB_UP' THEN 1 ELSE 0 END,
                    feedback_down = coalesce(feedback_down, 0)
                                    + CASE WHEN ? = 'THUMB_DOWN' THEN 1 ELSE 0 END,
                    retry_count   = coalesce(retry_count, 0)
                                    + CASE WHEN ? = 'RETRY' THEN 1 ELSE 0 END,
                    abandoned     = CASE WHEN ? = 'ABANDON' THEN TRUE ELSE abandoned END
                WHERE trace_id = ? AND tenant_id = ?::uuid
                """;
        String kind = e.kind().name();
        try (Connection conn = openSystem()) {
            try (PreparedStatement ps = conn.prepareStatement(insert)) {
                ps.setString(1, e.traceId());
                ps.setString(2, e.tenantId());
                ps.setString(3, kind);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(backfill)) {
                ps.setString(1, kind);
                ps.setString(2, kind);
                ps.setString(3, kind);
                ps.setString(4, kind);
                ps.setString(5, e.traceId());
                ps.setString(6, e.tenantId());
                ps.executeUpdate();
            }
            conn.commit();
        } catch (SQLException ex) {
            throw new IllegalStateException("录入用户反馈失败", ex);
        }
    }

    @Override
    public void recordRiskEvent(@NonNull RiskEvent e) {
        String insert = """
                INSERT INTO platform.agent_risk_event (trace_id, tenant_id, event_type, detail_digest)
                VALUES (?, ?::uuid, ?, ?)
                """;
        String backfill = """
                UPDATE platform.agent_trace_metric SET
                    safety_blocked_count = coalesce(safety_blocked_count, 0)
                                           + CASE WHEN ? = 'SAFETY_BLOCK' THEN 1 ELSE 0 END,
                    unauthorized_attempt_count = coalesce(unauthorized_attempt_count, 0)
                                           + CASE WHEN ? = 'UNAUTHORIZED_CALL' THEN 1 ELSE 0 END,
                    sensitive_output_hits = coalesce(sensitive_output_hits, 0)
                                           + CASE WHEN ? = 'SENSITIVE_OUTPUT' THEN 1 ELSE 0 END
                WHERE trace_id = ?
                """;
        String type = e.eventType().name();
        try (Connection conn = openSystem()) {
            try (PreparedStatement ps = conn.prepareStatement(insert)) {
                ps.setString(1, e.traceId());
                ps.setString(2, e.tenantId());
                ps.setString(3, type);
                ps.setString(4, e.detailDigest());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(backfill)) {
                ps.setString(1, type);
                ps.setString(2, type);
                ps.setString(3, type);
                ps.setString(4, e.traceId());
                ps.executeUpdate();
            }
            conn.commit();
        } catch (SQLException ex) {
            throw new IllegalStateException("录入风险事件失败", ex);
        }
    }

    @Override
    public void recordTaskOutcome(@NonNull TaskOutcome o) {
        String insert = """
                INSERT INTO platform.agent_task_outcome
                    (trace_id, tenant_id, checker_code, external_ref, verified, evidence_digest)
                VALUES (?, ?::uuid, ?, ?, ?, ?)
                ON CONFLICT (trace_id, checker_code) DO UPDATE SET
                    verified = EXCLUDED.verified,
                    external_ref = EXCLUDED.external_ref,
                    evidence_digest = EXCLUDED.evidence_digest,
                    checked_at = now()
                """;
        // ⚠️ 多 checker 时以「全部通过才算通过」收敛，避免任一 checker 通过就冒领成功
        String backfill = """
                UPDATE platform.agent_trace_metric m SET
                    auto_checked  = TRUE,
                    auto_verified = (SELECT bool_and(verified)
                                     FROM platform.agent_task_outcome
                                     WHERE trace_id = m.trace_id)
                WHERE trace_id = ?
                """;
        try (Connection conn = openSystem()) {
            try (PreparedStatement ps = conn.prepareStatement(insert)) {
                ps.setString(1, o.traceId());
                ps.setString(2, o.tenantId());
                ps.setString(3, o.checkerCode());
                ps.setString(4, o.externalRef());
                ps.setBoolean(5, o.verified());
                ps.setString(6, o.evidenceDigest());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(backfill)) {
                ps.setString(1, o.traceId());
                ps.executeUpdate();
            }
            conn.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("录入自动校验结果失败", e);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 内部工具
    // ══════════════════════════════════════════════════════════════

    private Connection openSystem() throws SQLException {
        Connection conn = dataSource.getConnection();
        conn.setAutoCommit(false);
        TenantSessionBinder.clear(conn);
        return conn;
    }

    /** 追加维度过滤条件。{@code toolTable=true} 时工具表无 agent 列，只按租户过滤。 */
    private String applyFilters(String sql, MetricQuery q, boolean toolTable) {
        StringBuilder sb = new StringBuilder(sql);
        if (q.tenantId() != null && !q.tenantId().isBlank()) {
            sb.append(" AND tenant_id = ?::uuid");
        }
        if (!toolTable) {
            if (q.agentId() != null && !q.agentId().isBlank()) {
                sb.append(" AND agent_id = ?::uuid");
            }
            if (q.agentVersion() != null) {
                sb.append(" AND agent_version = ?");
            }
            if (q.scenario() != null && !q.scenario().isBlank()) {
                sb.append(" AND scenario = ?");
            }
        }
        return sb.toString();
    }

    private int bindRange(PreparedStatement ps, MetricQuery q, int idx) throws SQLException {
        ps.setTimestamp(idx++, Timestamp.from(q.from()));
        ps.setTimestamp(idx++, Timestamp.from(q.to()));
        return idx;
    }

    /**
     * 绑定维度参数。
     *
     * <p>⚠️ {@code toolTable=true} 时只绑租户 —— 工具表没有 agent/scenario 列，
     * 多绑一个参数会让 {@code PreparedStatement} 直接报「参数下标越界」，
     * 而这种错误只在运行期暴露，单测不查库根本发现不了。
     */
    private int bindDims(PreparedStatement ps, MetricQuery q, int idx, boolean toolTable)
            throws SQLException {
        int i = idx;
        if (q.tenantId() != null && !q.tenantId().isBlank()) {
            ps.setString(i++, q.tenantId());
        }
        if (!toolTable) {
            if (q.agentId() != null && !q.agentId().isBlank()) {
                ps.setString(i++, q.agentId());
            }
            if (q.agentVersion() != null) {
                ps.setInt(i++, q.agentVersion());
            }
            if (q.scenario() != null && !q.scenario().isBlank()) {
                ps.setString(i++, q.scenario());
            }
        }
        return i;
    }

    private Overview mapOverview(ResultSet rs) throws SQLException {
        long traceCount = rs.getLong("trace_count");
        long succeeded = rs.getLong("succeeded");
        // ⚠️ count() 永不返回 NULL，但「一条样本都没有」的语义是**未采集**，不是 0%。
        //    这里显式把 0 折算回 null，否则看板上会出现「幻觉率 0%」这种假象。
        Long autoChecked = zeroAsNull(rs.getLong("auto_checked"));
        Long autoVerified = rs.getLong("auto_verified");
        Long sampled = zeroAsNull(rs.getLong("sampled"));
        Long followedDenom = zeroAsNull(rs.getLong("followed_denom"));
        Long falseDenom = zeroAsNull(rs.getLong("false_denom"));
        Long humanOk = rs.getLong("human_ok");
        Long falseOk = rs.getLong("false_ok");
        Long hallucinated = rs.getLong("hallucinated");
        Long followed = rs.getLong("followed");
        Long fbUp = nullableLong(rs, "fb_up");
        Long fbDown = nullableLong(rs, "fb_down");
        Long calls = nullableLong(rs, "calls");
        Long invalid = nullableLong(rs, "invalid");

        return new Overview(
                traceCount,
                succeeded,
                traceCount == 0 ? null : (double) succeeded / traceCount,
                nullableDouble(rs, "avg_duration"),
                nullableLong(rs, "in_tokens"),
                nullableLong(rs, "out_tokens"),
                rs.getBigDecimal("cost"),
                nullableDouble(rs, "avg_cost"),
                traceCount == 0 ? null : (double) rs.getLong("oversized") / traceCount,
                nullableDouble(rs, "avg_rounds"),
                traceCount == 0 ? null : (double) rs.getLong("max_rounds") / traceCount,
                (calls == null || invalid == null || calls == 0) ? null : (double) invalid / calls,
                rs.getLong("loops"),
                autoChecked,
                autoVerified,
                (autoChecked == null || autoChecked == 0) ? null : (double) autoVerified / autoChecked,
                traceCount == 0 ? null : (double) (autoChecked == null ? 0 : autoChecked) / traceCount,
                sampled,
                (sampled == null || sampled == 0) ? null : (double) humanOk / sampled,
                (falseDenom == null || falseDenom == 0) ? null : (double) falseOk / falseDenom,
                (sampled == null || sampled == 0) ? null : (double) hallucinated / sampled,
                (followedDenom == null || followedDenom == 0) ? null : (double) followed / followedDenom,
                fbUp,
                fbDown,
                (fbUp == null || fbDown == null || (fbUp + fbDown) == 0)
                        ? null : (double) fbUp / (fbUp + fbDown),
                traceCount == 0 ? null : (double) rs.getLong("abandoned") / traceCount,
                rs.getLong("safety"),
                rs.getLong("unauthorized"),
                rs.getLong("sensitive"),
                Instant.now());
    }

    private TraceMetric mapMetric(ResultSet rs) throws SQLException {
        return new TraceMetric(
                rs.getString("trace_id"),
                String.valueOf(rs.getObject("tenant_id")),
                String.valueOf(rs.getObject("run_id")),
                rs.getString("agent_id"),
                nullableInt(rs, "agent_version"),
                rs.getString("scenario"),
                rs.getString("model_id"),
                rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("ended_at") == null ? null : rs.getTimestamp("ended_at").toInstant(),
                nullableLong(rs, "duration_ms"),
                rs.getString("terminal_state"),
                TerminationReason.valueOf(rs.getString("termination_reason")),
                nullableLong(rs, "input_tokens"),
                nullableLong(rs, "output_tokens"),
                rs.getBigDecimal("cost_amount"),
                rs.getString("currency"),
                nullableInt(rs, "tool_rounds"),
                nullableInt(rs, "tool_call_total"),
                nullableInt(rs, "tool_call_failed"),
                nullableInt(rs, "tool_call_invalid"),
                nullableInt(rs, "tool_call_duplicate"),
                nullableInt(rs, "loop_suspected_count"),
                nullableBoolean(rs, "max_rounds_reached"),
                nullableInt(rs, "clarification_count"),
                nullableLong(rs, "think_ms_total"),
                nullableLong(rs, "tool_ms_total"),
                nullableBoolean(rs, "auto_checked"),
                nullableBoolean(rs, "auto_verified"),
                nullableBoolean(rs, "sampled"),
                rs.getString("human_verdict"),
                nullableBoolean(rs, "hallucination"),
                nullableBoolean(rs, "instruction_followed"),
                nullableInt(rs, "feedback_up"),
                nullableInt(rs, "feedback_down"),
                nullableInt(rs, "retry_count"),
                nullableBoolean(rs, "abandoned"),
                nullableInt(rs, "safety_blocked_count"),
                nullableInt(rs, "unauthorized_attempt_count"),
                nullableInt(rs, "sensitive_output_hits"),
                TraceIdSource.valueOf(rs.getString("trace_id_source")));
    }

    private static void setNullableInt(PreparedStatement ps, int idx, @Nullable Integer v)
            throws SQLException {
        if (v == null) {
            ps.setNull(idx, java.sql.Types.INTEGER);
        } else {
            ps.setInt(idx, v);
        }
    }

    private static void setNullableLong(PreparedStatement ps, int idx, @Nullable Long v)
            throws SQLException {
        if (v == null) {
            ps.setNull(idx, java.sql.Types.BIGINT);
        } else {
            ps.setLong(idx, v);
        }
    }

    private static void setNullableBoolean(PreparedStatement ps, int idx, @Nullable Boolean v)
            throws SQLException {
        if (v == null) {
            ps.setNull(idx, java.sql.Types.BOOLEAN);
        } else {
            ps.setBoolean(idx, v);
        }
    }

    private static @Nullable Integer nullableInt(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    private static @Nullable Long nullableLong(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static @Nullable Double nullableDouble(ResultSet rs, String col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }

    private static @Nullable Boolean nullableBoolean(ResultSet rs, String col) throws SQLException {
        boolean v = rs.getBoolean(col);
        return rs.wasNull() ? null : v;
    }

    /**
     * {@code count()} 的结果 0 折算为 {@code null}。
     *
     * <p>⚠️ 这是「未采集 ≠ 0」纪律在 SQL 侧的落点：{@code count(col)} 对
     * 「没有任何一行」返回 0 而不是 NULL，直接透传会让「没测过」显示成
     * 「测了全是 0」。
     */
    private static @Nullable Long zeroAsNull(long count) {
        return count == 0L ? null : count;
    }
}
