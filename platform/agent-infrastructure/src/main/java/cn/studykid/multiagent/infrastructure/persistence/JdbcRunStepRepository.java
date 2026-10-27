package cn.studykid.multiagent.infrastructure.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Set;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.RunStepPort;

/**
 * 执行步骤 JDBC 写入（P7-2）。
 *
 * <p><b>⚠️ 写 {@code public.run_step}（带 FORCE RLS）</b>：必须在事务内先绑定租户，
 * 否则 RLS 默认拒绝 → 静默零行写入（与事实读取同源铁律）。
 *
 * <p><b>⚠️ 幂等</b>：按 {@code (tenant_id, run_id, step_number)} 冲突时覆盖——
 * 执行循环重放/重试不重复造步骤。覆盖分支会触发 {@code run_step} 上的
 * {@code trg_run_step_fencing_guard} 触发器，旧世代 token 回退被 DB 拒绝（红线 5）。
 *
 * <p><b>⚠️ 步骤 {@code state} 用 {@code RUNNING}</b>：{@code run_state} 枚举无「步骤已完成」语义值，
 * 步骤是已执行事实记录，用 {@code RUNNING} 表达「该步骤已发生」；指标只看时长与类型，不读 state。
 *
 * <p><b>⚠️ 写入失败绝不抛出到主流程</b>：步骤是执行事实，但失败（如 DB 抖动）绝不能让一次
 * 本可成功的 Run 变成失败——异常在此吞掉并记 WARN（与 {@code appendEvent} 同纪律）。
 */
public class JdbcRunStepRepository implements RunStepPort {

    private final DataSource dataSource;

    public JdbcRunStepRepository(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void append(@NonNull String tenantId,
                       @NonNull String runId,
                       long fencingToken,
                       int stepNumber,
                       @NonNull String stepType,
                       @NonNull Instant startedAt,
                       @NonNull Instant completedAt) {
        try (Connection conn = openTenant(tenantId)) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO public.run_step "
                            + "(tenant_id, run_id, step_number, state, step_type, fencing_token, started_at, completed_at) "
                            + "VALUES (?::uuid, ?::uuid, ?, 'RUNNING'::run_state, ?, ?, ?, ?) "
                            + "ON CONFLICT (tenant_id, run_id, step_number) DO UPDATE SET "
                            + "  step_type = EXCLUDED.step_type, "
                            + "  started_at = EXCLUDED.started_at, "
                            + "  completed_at = EXCLUDED.completed_at, "
                            + "  fencing_token = EXCLUDED.fencing_token")) {
                int i = 1;
                ps.setString(i++, tenantId);
                ps.setString(i++, runId);
                ps.setInt(i++, stepNumber);
                ps.setString(i++, stepType);
                ps.setLong(i++, fencingToken);
                ps.setTimestamp(i++, java.sql.Timestamp.from(startedAt));
                ps.setTimestamp(i++, java.sql.Timestamp.from(completedAt));
                ps.executeUpdate();
            }
            conn.commit();
        } catch (SQLException e) {
            // ⚠️ 步骤写入失败绝不冒泡到执行主流程（见类注释）
            org.slf4j.LoggerFactory.getLogger(JdbcRunStepRepository.class)
                    .warn("⚠️ 写入 run_step 失败（不影响本次执行结果）: runId={}, step={}, cause={}",
                            runId, stepNumber, e.toString());
        }
    }

    private Connection openTenant(String tenantId) throws SQLException {
        Connection conn = dataSource.getConnection();
        conn.setAutoCommit(false);
        // ⚠️ RLS 铁律：事务内、任何业务 SQL 之前绑定
        TenantSessionBinder.bind(conn,
                new TenantContext(tenantId, "agent-run-step-writer", Set.of(), 0L));
        return conn;
    }
}
