package cn.studykid.multiagent.infrastructure.persistence;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.ResultQueryPort;

/**
 * Run 结果查询实现（P4-D10 / G5）。
 *
 * <p>⚠️ <b>{@code tenant_id} 条件不可删除</b>：
 * 即便 RLS 已隔离，应用侧条件仍是第一道防线；
 * 若某天 RLS 策略被误删，显式条件仍能挡住跨租户读取。
 *
 * <p>⚠️ <b>失败原因不外泄，且由 SQL 层保证</b>：
 * 失败 Run 的 {@code result_payload} 形状是 {@code {"reason":"<内部失败原因>"}}，
 * 原因文本属内部错误细节。取讲解内容时用 {@code CASE WHEN state='SUCCEEDED'}
 * **在 SQL 层就把它挡掉**——这样「返回失败原文」不是一个可能被忘记的分支，
 * 而是**结构上不可能**。
 */
@Repository
public class JdbcResultQueryRepository implements ResultQueryPort {

    /**
     * ⚠️ <b>不取 {@code input_payload} 与 {@code error_message}</b>：
     * 前者含学生提交的题目与解答（敏感载荷），
     * 后者是内部错误原文（可能含实现细节）。
     *
     * <p>⚠️ <b>讲解内容由 SQL 层按状态截取</b>：
     * 只有 {@code SUCCEEDED} 才取 {@code result_payload->>'content'}，
     * 其余状态一律返回 {@code NULL}。
     */
    private static final String SQL_FIND_RESULT = """
            SELECT run_id::text, state::text, completed_at, error_code,
                   CASE WHEN state = 'SUCCEEDED' THEN result_payload->>'content' END AS explanation
            FROM run
            WHERE tenant_id = ?::uuid AND run_id = ?::uuid
            """;

    /** 薄弱点：按 Run 检索（索引 idx_weakness_run 由 V9 迁移提供）。 */
    private static final String SQL_LOAD_WEAKNESSES = """
            SELECT knowledge_point, error_type, confidence
            FROM weakness_record
            WHERE tenant_id = ?::uuid AND run_id = ?::uuid
            ORDER BY created_at ASC
            LIMIT 50
            """;

    /** 步骤：按 Run 检索（索引 idx_run_step_run 已存在）。 */
    private static final String SQL_LOAD_STEPS = """
            SELECT step_number, state::text, step_type, output_payload::text AS output
            FROM run_step
            WHERE tenant_id = ?::uuid AND run_id = ?::uuid
            ORDER BY step_number ASC
            LIMIT 100
            """;

    /**
     * 未决操作：<b>权威来源是 tool_operation 的 UNKNOWN 状态</b>。
     *
     * <p>⚠️ <b>不是 run.state</b>：工具结果未知时 Run 停留在 {@code RUNNING}
     * 等待对账（重投 = 重复副作用 = Sev-0，故一律 ack 丢弃），
     * {@code run_state} 枚举中<b>不存在</b> {@code RECONCILIATION_REQUIRED}。
     */
    private static final String SQL_LOAD_UNRESOLVED = """
            SELECT operation_id::text, tool_name, state::text
            FROM tool_operation
            WHERE tenant_id = ?::uuid AND run_id = ?::uuid AND state = 'UNKNOWN'
            ORDER BY created_at ASC
            LIMIT 50
            """;

    private final DataSource dataSource;

    public JdbcResultQueryRepository(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public @Nullable RunResult findResult(@NonNull String tenantId, @NonNull String runId) {
        try (TenantConnectionLease lease = tenantConnection(tenantId)) {
            try (PreparedStatement ps = lease.connection().prepareStatement(SQL_FIND_RESULT)) {
                ps.setString(1, tenantId);
                ps.setString(2, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        // ⚠️ 不存在与「不属于该租户」返回同一个 null：
                        //    上层据此统一 404，不区分二者——否则构成存在性探测通道。
                        return null;
                    }
                    String state = rs.getString("state");
                    String explanation = rs.getString("explanation");
                    // ⚠️ 纵深防御：即使 SQL 被改坏（去掉了 CASE WHEN），
                    //    非成功状态也**不会**返回讲解内容。
                    if (!"SUCCEEDED".equals(state)) {
                        explanation = null;
                    }
                    List<Weakness> weaknesses = loadWeaknesses(lease.connection(), tenantId, runId);
                    List<Step> steps = loadSteps(lease.connection(), tenantId, runId);
                    List<UnknownOperation> unresolved =
                            loadUnresolved(lease.connection(), tenantId, runId);

                    return new RunResult(
                            rs.getString("run_id"),
                            state,
                            // ⚠️⚠️ completedAt 必须保持 null（不可退化成 Instant.EPOCH）：
                            //    非终态返回 1970-01-01T00:00:00Z 等于宣称
                            //    「该 Run 于 1970 年完成」——错误且误导。
                            toNullableInstant(rs.getTimestamp("completed_at")),
                            rs.getString("error_code"),
                            explanation,
                            weaknesses,
                            steps,
                            new Reconciliation(!unresolved.isEmpty(), unresolved));
                }
            } finally {
                // ⚠️ 只读查询：回滚以释放读事务。
                lease.rollbackQuietly();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询 Run 结果失败", e);
        }
    }

    private List<Weakness> loadWeaknesses(java.sql.Connection conn,
                                          String tenantId, String runId) throws SQLException {
        List<Weakness> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(SQL_LOAD_WEAKNESSES)) {
            ps.setString(1, tenantId);
            ps.setString(2, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next() && result.size() < 50) {
                    BigDecimal confidence = rs.getBigDecimal("confidence");
                    result.add(new Weakness(
                            rs.getString("knowledge_point"),
                            rs.getString("error_type"),
                            confidence));
                }
            }
        }
        return result;
    }

    private List<Step> loadSteps(java.sql.Connection conn,
                                  String tenantId, String runId) throws SQLException {
        List<Step> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(SQL_LOAD_STEPS)) {
            ps.setString(1, tenantId);
            ps.setString(2, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next() && result.size() < 100) {
                    result.add(new Step(
                            rs.getInt("step_number"),
                            rs.getString("state"),
                            rs.getString("step_type"),
                            rs.getString("output")));
                }
            }
        }
        return result;
    }

    private List<UnknownOperation> loadUnresolved(java.sql.Connection conn,
                                                   String tenantId, String runId) throws SQLException {
        List<UnknownOperation> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(SQL_LOAD_UNRESOLVED)) {
            ps.setString(1, tenantId);
            ps.setString(2, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next() && result.size() < 50) {
                    result.add(new UnknownOperation(
                            rs.getString("operation_id"),
                            rs.getString("tool_name"),
                            rs.getString("state")));
                }
            }
        }
        return result;
    }

    /**
     * 可空时刻：{@code null} 原样保留。
     *
     * <p>⚠️ 与 {@code JdbcRunQueryRepository.toInstant} 的区别是刻意的：
     * {@code created_at} 是 NOT NULL 列，用 EPOCH 兜底可掩盖数据异常；
     * 而 {@code completed_at} 在非终态下<b>本就应该</b>为 {@code null}——
     * 把 {@code null} 变成 EPOCH 会伪造出一个「已完成」的时间点。
     */
    private static @Nullable Instant toNullableInstant(@Nullable Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    /**
     * 取得已绑定租户的连接。
     *
     * <p>⚠️ 与 {@code JdbcRunQueryRepository} / {@code JdbcProfileQueryRepository} 同源铁律：
     * 外层有事务则复用，否则自开并绑定租户。
     * {@code set_config(is_local=true)} <b>必须配合事务</b>，自动提交下立即失效。
     */
    private TenantConnectionLease tenantConnection(String tenantId) throws SQLException {
        java.sql.Connection outer = TransactionalConnectionHolder.current();
        if (outer != null) {
            return TenantConnectionLease.borrowed(outer);
        }
        return TransactionalConnectionHolder.openTenantConnection(
                dataSource, systemContext(tenantId));
    }

    private static TenantContext systemContext(String tenantId) {
        return new TenantContext(tenantId, "system", java.util.Set.of(), 0L);
    }
}
