package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.cap.CapCompensationTask;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;
import cn.studykid.multiagent.ports.persistence.CompensationTaskPort;

/**
 * {@link CompensationTaskPort} 的 PostgreSQL 实现（W-PERM-P3 · §5.3.5）。
 *
 * <p>⚠️ <b>入队幂等靠唯一约束，不靠「先查后插」</b>：
 * {@code UNIQUE (tenant_id, kind, target_id)} + {@code ON CONFLICT DO NOTHING}。
 * 先查后插存在 check-then-act 窗口 —— 并发补偿失败会落两条任务，
 * 而补偿必须幂等，多余的一条会让额度被释放两次（额度凭空放大）。
 *
 * <p>⚠️ <b>扫描只取「到期可重试」的任务</b>：{@code next_retry_at IS NULL OR next_retry_at <= now()}。
 * 无退避的直接重试会在下游持续故障时形成热循环。
 */
public class JdbcCompensationTaskRepository implements CompensationTaskPort {

    private static final String COLUMNS = """
            task_id::text, tenant_id::text, kind, target_id, invocation_id,
            payload, state, attempts, last_error, created_at, updated_at, next_retry_at
            """;

    private static final String SQL_INSERT = """
            INSERT INTO cap_compensation_task
                (task_id, tenant_id, kind, target_id, invocation_id, payload,
                 state, attempts, last_error, created_at, updated_at, next_retry_at)
            VALUES (?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (tenant_id, kind, target_id) DO NOTHING
            """;

    // ⚠️⚠️ 下面 SQL 里的「SELECT + 反斜杠 s」**不是笔误**：
    //   Java 文本块会<b>剥掉每行的行尾空白</b>，
    //   故 `SELECT ` + COLUMNS 会拼成 `SELECTtask_id::text` —— 语法错误，
    //   且报错信息（syntax error at or near "SELECTtask_id"）完全看不出根因。
    //   反斜杠 s 是「保留一个空格」的标准转义。
    private static final String SQL_FIND_BY_ID = """
            SELECT \s""" + COLUMNS + """
            FROM cap_compensation_task
            WHERE tenant_id = ?::uuid AND task_id = ?::uuid
            """;

    private static final String SQL_FIND_PENDING = """
            SELECT \s""" + COLUMNS + """
            FROM cap_compensation_task
            WHERE tenant_id = ?::uuid
              AND state IN ('PENDING', 'FAILED')
              AND (next_retry_at IS NULL OR next_retry_at <= now())
            ORDER BY created_at ASC
            LIMIT ?
            """;

    private static final String SQL_MARK_COMPLETED = """
            UPDATE cap_compensation_task
            SET state = 'COMPLETED', updated_at = now()
            WHERE tenant_id = ?::uuid AND task_id = ?::uuid
              AND state IN ('PENDING', 'RUNNING', 'FAILED')
            """;

    private static final String SQL_MARK_FAILED = """
            UPDATE cap_compensation_task
            SET state = 'FAILED', attempts = attempts + 1, last_error = ?,
                next_retry_at = ?, updated_at = now()
            WHERE tenant_id = ?::uuid AND task_id = ?::uuid
              AND state IN ('PENDING', 'RUNNING')
            """;

    private final DataSource dataSource;

    public JdbcCompensationTaskRepository(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public boolean enqueue(@NonNull CapCompensationTask task) {
        Boolean inserted = TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(task.tenantId()),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_INSERT)) {
                        ps.setString(1, task.taskId());
                        ps.setString(2, task.tenantId());
                        ps.setString(3, task.kind().name());
                        ps.setString(4, task.targetId());
                        if (task.invocationId() == null) {
                            ps.setNull(5, java.sql.Types.VARCHAR);
                        } else {
                            ps.setString(5, task.invocationId());
                        }
                        if (task.payload() == null) {
                            ps.setNull(6, java.sql.Types.VARCHAR);
                        } else {
                            ps.setString(6, task.payload());
                        }
                        ps.setString(7, task.state().name());
                        ps.setInt(8, task.attempts());
                        if (task.lastError() == null) {
                            ps.setNull(9, java.sql.Types.VARCHAR);
                        } else {
                            ps.setString(9, task.lastError());
                        }
                        ps.setTimestamp(10, Timestamp.from(task.createdAt()));
                        ps.setTimestamp(11, Timestamp.from(task.updatedAt()));
                        if (task.nextRetryAt() == null) {
                            ps.setNull(12, java.sql.Types.TIMESTAMP);
                        } else {
                            ps.setTimestamp(12, Timestamp.from(task.nextRetryAt()));
                        }
                        return ps.executeUpdate() > 0;
                    }
                });
        return Boolean.TRUE.equals(inserted);
    }

    @Override
    public @NonNull Optional<CapCompensationTask> findById(@NonNull String tenantId,
                                                           @NonNull String taskId) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_FIND_BY_ID)) {
                        ps.setString(1, tenantId);
                        ps.setString(2, taskId);
                        try (ResultSet rs = ps.executeQuery()) {
                            return rs.next()
                                    ? Optional.of(map(rs))
                                    : Optional.<CapCompensationTask>empty();
                        }
                    }
                });
    }

    @Override
    public @NonNull List<CapCompensationTask> findPending(@NonNull String tenantId, int limit) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> {
                    List<CapCompensationTask> out = new ArrayList<>();
                    try (PreparedStatement ps = conn.prepareStatement(SQL_FIND_PENDING)) {
                        ps.setString(1, tenantId);
                        ps.setInt(2, Math.max(1, limit));
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                out.add(map(rs));
                            }
                        }
                    }
                    return out;
                });
    }

    @Override
    public boolean markCompleted(@NonNull String tenantId, @NonNull String taskId) {
        Boolean updated = TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_MARK_COMPLETED)) {
                        ps.setString(1, tenantId);
                        ps.setString(2, taskId);
                        return ps.executeUpdate() > 0;
                    }
                });
        return Boolean.TRUE.equals(updated);
    }

    @Override
    public boolean markFailed(@NonNull String tenantId,
                              @NonNull String taskId,
                              @NonNull String error,
                              @NonNull Instant nextRetryAt) {
        Boolean updated = TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_MARK_FAILED)) {
                        ps.setString(1, error);
                        ps.setTimestamp(2, Timestamp.from(nextRetryAt));
                        ps.setString(3, tenantId);
                        ps.setString(4, taskId);
                        return ps.executeUpdate() > 0;
                    }
                });
        return Boolean.TRUE.equals(updated);
    }

    private static CapCompensationTask map(ResultSet rs) throws SQLException {
        Timestamp nextRetry = rs.getTimestamp("next_retry_at");
        return new CapCompensationTask(
                rs.getString("task_id"),
                rs.getString("tenant_id"),
                CapCompensationTask.CompensationKind.valueOf(rs.getString("kind")),
                rs.getString("target_id"),
                rs.getString("invocation_id"),
                rs.getString("payload"),
                CapCompensationTask.TaskState.valueOf(rs.getString("state")),
                rs.getInt("attempts"),
                rs.getString("last_error"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                nextRetry == null ? null : nextRetry.toInstant());
    }

    private static TenantContext tenantContext(String tenantId) {
        return new TenantContext(tenantId, "system", Set.of(), 0L);
    }

    /** 生成新的补偿任务 id。 */
    public static @NonNull String newTaskId() {
        return UUID.randomUUID().toString();
    }

    /** @return 可空的 invocationId（供构造任务时保持类型一致）。 */
    @Nullable
    public static String nullable(@Nullable String value) {
        return value;
    }
}
