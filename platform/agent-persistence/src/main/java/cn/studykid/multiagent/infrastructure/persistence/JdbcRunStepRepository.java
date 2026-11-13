package cn.studykid.multiagent.infrastructure.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Set;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;

import cn.studykid.multiagent.core.tenant.SubjectContext;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowBinding;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowHooks;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowReporter;
import cn.studykid.multiagent.infrastructure.security.ScopedDataAccess;
import cn.studykid.multiagent.ports.persistence.RunStepPort;

/**
 * 执行步骤 JDBC 写入（P7-2）。
 *
 * <p><b>⚠️ 写 {@code public.run_step}（带 FORCE RLS）</b>：必须在事务内先绑定租户，
 * 否则 RLS 默认拒绝 → 静默零行写入（与事实读取同源铁律）。
 * ⚠️ RLS 退役期（RLS依赖清理改造计划 M1/M4）：本连接自管（不经
 * {@code TransactionalConnectionHolder}），GUC 绑定是本类连接的 RLS 前置，
 * <b>保留至 M4 退役 {@code run_step} 策略批次</b>；SQL 自带显式
 * {@code tenant_id} 谓词，RLS 退役后功能不变。
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

    // ── 影子态接入（P1 影子 / 改造计划 WP-M1-1）──────────────────────────
    private @Nullable ScopedDataAccess scoped;
    private @Nullable AuthzShadowReporter reporter;
    private volatile boolean shadowEnabled;
    private @NonNull String sentinelTenantId = "";

    public JdbcRunStepRepository(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 影子态装配入口：容器里存在 {@link AuthzShadowBinding} 即开启（P1 影子期），
     * 不存在即完全关闭（零行为变更）。
     */
    @Autowired(required = false)
    public void bindAuthzShadow(@Nullable AuthzShadowBinding binding) {
        if (binding == null) {
            return;
        }
        this.scoped = binding.scoped();
        this.reporter = binding.reporter();
        this.shadowEnabled = true;
        this.sentinelTenantId = binding.sentinelTenantId();
    }

    @Override
    public void append(@NonNull String tenantId,
                       @NonNull String runId,
                       long fencingToken,
                       int stepNumber,
                       @NonNull String stepType,
                       @NonNull Instant startedAt,
                       @NonNull Instant completedAt) {
        // ⚠️ 影子比对（P1 只记日志）：Gate 算出的 ScopeFilter vs 本次绑定租户。
        //    INSERT 自带显式 tenant_id 谓词 ⇒ hasTenantPredicate=true（不产 rlsDependent）。
        shadowCompare(tenantId);
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

    // ── 影子辅助（守卫要求：声明与调用分离，查询路径必须真调用）────────

    /**
     * 影子比对：Gate 算出的 ScopeFilter vs 本次写入绑定租户（只记日志）。
     * 写路径没有结果行可染色，比对是本仓唯一的影子观测点。
     */
    void shadowCompare(@NonNull String boundTenantId) {
        AuthzShadowHooks.compare(scoped, reporter, shadowEnabled, "run_step",
                SubjectContext.forTenant(boundTenantId), boundTenantId, true);
    }
}
