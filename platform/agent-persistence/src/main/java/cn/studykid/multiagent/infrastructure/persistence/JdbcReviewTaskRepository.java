package cn.studykid.multiagent.infrastructure.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import cn.studykid.multiagent.ports.persistence.ReviewTaskPort;

/**
 * 人工审查队列 JDBC 仓储（P2）。
 *
 * <p><b>⚠️ 平台域、无 RLS</b>：{@code platform.review_task} 与指标宽表同域，
 * 一律 {@code openSystem()} 系统连接（清空租户上下文）——
 * 与 {@link JdbcAgentMetricRepository} 的边界一致：
 * <b>对话内容读在租户域（run_message / RLS），审查队列读写全在平台域（授权闸门）</b>。
 *
 * <p><b>⚠️ 状态机流转用「状态前置条件」守卫</b>（{@code WHERE status = 期望前态}）：
 * 认领 / 关闭 / 驳回只允许从合法前态进入，两个 reviewer 并发认领时
 * 只有第一个生效（update 计数 = 1），第二个静默不生效——由调用方按返回判断。
 * 这比「无条件 UPDATE 再判空」少一次竞态窗口。
 *
 * <p>⚠️ <b>刻意不挂 {@code @Repository}</b>：与 P1 的 {@code JdbcRunMessageRepository}
 * 同款纪律 —— repository 在本项目的组件扫描里<b>选不到</b>，
 * 必须在 {@code ApplicationConfiguration} 显式 {@code @Bean} 注册。
 * 两处都挂会得到两个同类型 Bean → 注入点歧义（启动期失败）。
 */
public class JdbcReviewTaskRepository implements ReviewTaskPort {

    private final javax.sql.DataSource dataSource;

    public JdbcReviewTaskRepository(javax.sql.DataSource dataSource) {
        this.dataSource = dataSource;
    }

    private static final String SQL_INSERT = """
            INSERT INTO platform.review_task
                (trace_id, tenant_id, run_id, source, priority, note)
            VALUES (?, ?::uuid, ?::uuid, ?, ?, ?)
            RETURNING task_id
            """;

    private static final String SQL_ASSIGN = """
            UPDATE platform.review_task
               SET assigned_to = ?, status = 'IN_REVIEW', updated_at = now()
             WHERE task_id = ?::uuid AND status IN ('PENDING', 'IN_REVIEW')
            """;

    private static final String SQL_RESOLVE = """
            UPDATE platform.review_task
               SET status = 'RESOLVED', root_cause = ?, note = ?,
                   resolved_at = now(), updated_at = now()
             WHERE task_id = ?::uuid AND status IN ('PENDING', 'IN_REVIEW')
            """;

    private static final String SQL_DISMISS = """
            UPDATE platform.review_task
               SET status = 'DISMISSED', note = ?, resolved_at = now(), updated_at = now()
             WHERE task_id = ?::uuid AND status IN ('PENDING', 'IN_REVIEW')
            """;

    @Override
    public @NonNull String create(@NonNull ReviewTaskSpec spec) {
        try (Connection conn = openSystem();
                PreparedStatement ps = conn.prepareStatement(SQL_INSERT)) {
            ps.setString(1, spec.traceId());
            ps.setString(2, spec.tenantId());
            ps.setString(3, spec.runId());
            ps.setString(4, spec.source().name());
            ps.setString(5, spec.priority().name());
            ps.setString(6, spec.note());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("审查任务创建未返回 task_id");
                }
                conn.commit();
                return rs.getString("task_id");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("审查任务创建失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void assign(@NonNull String taskId, @NonNull String assignee) {
        updateWithPrecondition(SQL_ASSIGN, ps -> {
            ps.setString(1, assignee);
            ps.setString(2, taskId);
        }, taskId, "认领");
    }

    @Override
    public void resolve(@NonNull String taskId, @Nullable String rootCause, @Nullable String note) {
        updateWithPrecondition(SQL_RESOLVE, ps -> {
            ps.setString(1, rootCause);
            ps.setString(2, note);
            ps.setString(3, taskId);
        }, taskId, "关闭");
    }

    @Override
    public void dismiss(@NonNull String taskId, @Nullable String note) {
        updateWithPrecondition(SQL_DISMISS, ps -> {
            ps.setString(1, note);
            ps.setString(2, taskId);
        }, taskId, "驳回");
    }

    private static final String SQL_HAS_OPEN = """
            SELECT 1 FROM platform.review_task
             WHERE trace_id = ? AND status IN ('PENDING', 'IN_REVIEW')
             LIMIT 1
            """;

    @Override
    public boolean hasOpenTask(@NonNull String traceId) {
        try (Connection conn = openSystem();
                PreparedStatement ps = conn.prepareStatement(SQL_HAS_OPEN)) {
            ps.setString(1, traceId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("审查任务去重查询失败: " + e.getMessage(), e);
        }
    }

    @Override
    public @NonNull List<ReviewTaskView> list(@Nullable String status,
                                              @Nullable String tenantId,
                                              int limit) {
        // 服务端钳制页大小：防 limit=100000 之类把队列整表拖出（与列表端点纪律一致）
        int safeLimit = Math.max(1, Math.min(limit, 200));
        StringBuilder sql = new StringBuilder("""
                SELECT task_id, trace_id, tenant_id, run_id, source, priority,
                       status, assigned_to, root_cause, created_at
                  FROM platform.review_task
                 WHERE 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append("   AND status = ?\n");
            args.add(status);
        } else {
            // 待处理视图：PENDING + IN_REVIEW（已关闭的不进队列）
            sql.append("   AND status IN ('PENDING', 'IN_REVIEW')\n");
        }
        if (tenantId != null && !tenantId.isBlank()) {
            sql.append("   AND tenant_id = ?::uuid\n");
            args.add(tenantId);
        }
        sql.append(" ORDER BY CASE priority WHEN 'HIGH' THEN 0 WHEN 'NORMAL' THEN 1 ELSE 2 END,")
           .append(" created_at ASC LIMIT ")
           .append(safeLimit);

        try (Connection conn = openSystem();
                PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < args.size(); i++) {
                ps.setObject(i + 1, args.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<ReviewTaskView> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new ReviewTaskView(
                            rs.getString("task_id"),
                            rs.getString("trace_id"),
                            rs.getObject("tenant_id", UUID.class).toString(),
                            rs.getObject("run_id", UUID.class).toString(),
                            rs.getString("source"),
                            rs.getString("priority"),
                            rs.getString("status"),
                            rs.getString("assigned_to"),
                            rs.getString("root_cause"),
                            String.valueOf(rs.getTimestamp("created_at").toInstant())));
                }
                conn.commit();
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("审查队列读取失败: " + e.getMessage(), e);
        }
    }

    // ── 内部 ────────────────────────────────────────────────

    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    /** 带状态前置条件的流转；影响行数为 0 = 前态不合法（终态不可再流转），抛出明确异常。 */
    private void updateWithPrecondition(@NonNull String sql, @NonNull Binder binder,
                                        @NonNull String taskId, @NonNull String action) {
        try (Connection conn = openSystem();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            binder.bind(ps);
            int updated = ps.executeUpdate();
            if (updated == 0) {
                throw new IllegalStateException(
                        "审查任务" + action + "未生效（任务不存在或已终态）: " + taskId);
            }
            conn.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("审查任务" + action + "失败: " + e.getMessage(), e);
        }
    }

    /** 系统身份连接：清空租户上下文（platform 域无 RLS，与指标仓储同款）。 */
    private Connection openSystem() throws SQLException {
        Connection conn = dataSource.getConnection();
        conn.setAutoCommit(false);
        TenantSessionBinder.clear(conn);
        return conn;
    }
}
