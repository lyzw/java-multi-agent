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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(JdbcReviewTaskRepository.class);

    private final javax.sql.DataSource dataSource;

    /**
     * 归因结论回写钩子（§8.1 · 可空 = 口径未定不接线）。
     *
     * <p>⚠️ 钩子失败**绝不影响已关闭的任务**：任务状态是事实，
     * 回写只是下游动作。若下游抛异常把 resolve 整体报失败，
     * reviewer 重试反而会造成「任务已关闭却显示失败」的双真相。
     */
    private final cn.studykid.multiagent.ports.quality.ReviewAttributionHook attributionHook;

    /** 兼容构造：无回写钩子（= 口径未定，不接线）。 */
    public JdbcReviewTaskRepository(javax.sql.DataSource dataSource) {
        this(dataSource, null);
    }

    /** 带钩子构造：由 ApplicationConfiguration 显式选择（本类刻意不挂 @Repository）。 */
    public JdbcReviewTaskRepository(
            javax.sql.DataSource dataSource,
            cn.studykid.multiagent.ports.quality.ReviewAttributionHook attributionHook) {
        this.dataSource = dataSource;
        this.attributionHook = attributionHook;
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
        TaskFacts facts = updateWithPrecondition(SQL_RESOLVE, ps -> {
            ps.setString(1, rootCause);
            ps.setString(2, note);
            ps.setString(3, taskId);
        }, taskId, "关闭");

        // ⚠️⚠️ 回写只在状态流转**确实生效之后**发生：updateWithPrecondition 在
        //    0 行命中时抛异常，执行不到这里 —— 于是「任务根本没关闭却已回写 eval_sample」
        //    这种双真相在结构上不可达。
        if (attributionHook != null && facts != null
                && facts.rootCause() != null && ITERATION_ROOT_CAUSES.contains(facts.rootCause())) {
            try {
                // ⚠️ 钩子失败绝不冒泡：任务状态是事实，回写只是下游动作。
                //    若下游抛异常把 resolve 报失败，reviewer 重试会造成
                //    「任务已关闭却显示失败」的双真相。失败必须 WARN 可见，
                //    而不是 debug 级别静默（否则「接线了但没人看得到」正是本仓反复治理的形态）。
                attributionHook.onAttributionResolved(facts.traceId(), facts.tenantId(),
                        facts.runId(), facts.rootCause(), note, facts.assignedTo());
            } catch (RuntimeException e) {
                log.warn("归因结论回写钩子失败（任务已关闭，不回滚）: task={}, rootCause={}, cause={}",
                        taskId, facts.rootCause(), e.toString());
            }
        }
    }

    /** §8.1 触发迭代工单的三类根因（TOOL/DATA/UNKNOWN 不触发）。 */
    private static final java.util.Set<String> ITERATION_ROOT_CAUSES =
            java.util.Set.of("PROMPT", "CONFIG", "MODEL");

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

    /**
     * 带状态前置条件的流转；影响行数为 0 = 前态不合法（终态不可再流转），抛出明确异常。
     *
     * <p>⚠️ 返回「流转后的任务事实」（仅 resolve 需要：回写钩子要 traceId/租户/根因）。
     *    其他流转返回 null 即可 —— 让 assign/dismiss 也回查一遍是纯浪费。
     */
    private @Nullable TaskFacts updateWithPrecondition(@NonNull String sql, @NonNull Binder binder,
                                        @NonNull String taskId, @NonNull String action) {
        try (Connection conn = openSystem();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            binder.bind(ps);
            int updated = ps.executeUpdate();
            if (updated == 0) {
                throw new IllegalStateException(
                        "审查任务" + action + "未生效（任务不存在或已终态）: " + taskId);
            }
            TaskFacts facts = needsFacts(sql) ? readFacts(conn, taskId) : null;
            conn.commit();
            return facts;
        } catch (SQLException e) {
            throw new IllegalStateException("审查任务" + action + "失败: " + e.getMessage(), e);
        }
    }

    /** 该流转是否需要回查任务事实（目前只有 resolve 的回写钩子需要）。 */
    private static boolean needsFacts(@NonNull String sql) {
        // SQL_RESOLVE 是编译期常量，但用值比较而非引用比较更稳健（S4973）。
        return SQL_RESOLVE.equals(sql);
    }

    /**
     * 在同一事务内回查任务事实。
     *
     * <p>⚠️ 必须在 commit 之前：此时 UPDATE 已生效但未提交，
     * 用同一连接能看到自己刚写入的 root_cause；提交后再查就是另一个事务、
     * 且并发下可能被别的 reviewer 改写。
     */
    private static @Nullable TaskFacts readFacts(Connection conn, String taskId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT trace_id, tenant_id::text, run_id::text, root_cause, assigned_to "
                        + "FROM platform.review_task WHERE task_id = ?::uuid")) {
            ps.setString(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new TaskFacts(rs.getString("trace_id"), rs.getString("tenant_id"),
                        rs.getString("run_id"), rs.getString("root_cause"),
                        rs.getString("assigned_to"));
            }
        }
    }

    /** 流转后的任务事实（回写钩子的入参）。 */
    private record TaskFacts(@NonNull String traceId, @NonNull String tenantId,
                             @NonNull String runId, @Nullable String rootCause,
                             @Nullable String assignedTo) {
    }

    /** 系统身份连接：清空租户上下文（platform 域无 RLS，与指标仓储同款）。 */
    private Connection openSystem() throws SQLException {
        Connection conn = dataSource.getConnection();
        conn.setAutoCommit(false);
        TenantSessionBinder.clear(conn);
        return conn;
    }
}
