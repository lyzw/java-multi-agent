package cn.studykid.multiagent.infrastructure.persistence;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.RunQueryPort;

/**
 * Run 状态查询实现（P4-D09 / G3）。
 *
 * <p>⚠️ <b>{@code tenant_id} 条件不可删除</b>：
 * 即便 RLS 已隔离，应用侧条件仍是第一道防线；
 * 若某天 RLS 策略被误删，显式条件仍能挡住跨租户读取。
 */
@Repository
public class JdbcRunQueryRepository implements RunQueryPort {

    /**
     * ⚠️ <b>不取 {@code input_payload} 与 {@code error_message}</b>：
     * 前者含学生提交的题目与解答（敏感载荷），
     * 后者是内部错误原文（可能含实现细节）。状态查询只需要状态本身。
     */
    private static final String SQL_FIND_STATUS = """
            SELECT run_id::text, state::text, created_at, completed_at, attempt, error_code
            FROM run
            WHERE tenant_id = ?::uuid AND run_id = ?::uuid
            """;

    private final DataSource dataSource;

    public JdbcRunQueryRepository(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public @Nullable RunStatus findStatus(@NonNull String tenantId, @NonNull String runId) {
        try (TenantConnectionLease lease = tenantConnection(tenantId)) {
            try (PreparedStatement ps = lease.connection().prepareStatement(SQL_FIND_STATUS)) {
                ps.setString(1, tenantId);
                ps.setString(2, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        // ⚠️ 不存在与「不属于该租户」返回同一个 null：
                        //    上层据此统一 404，不区分二者——否则构成存在性探测通道。
                        return null;
                    }
                    return new RunStatus(
                            rs.getString("run_id"),
                            rs.getString("state"),
                            toInstant(rs.getTimestamp("created_at")),
                            // ⚠️⚠️ completedAt 必须保持 null（不可退化成 Instant.EPOCH）：
                            //    契约冻结为「非终态为 null」（P4-D09 §2.1），
                            //    前端据此判断 Run 是否已进入终态。
                            //    若返回 1970-01-01T00:00:00Z，
                            //    语义上等价于「1970 年完成的 Run」——错误且误导。
                            toNullableInstant(rs.getTimestamp("completed_at")),
                            rs.getInt("attempt"),
                            rs.getString("error_code"));
                }
            } finally {
                // ⚠️ 只读查询：回滚以释放读事务。
                lease.rollbackQuietly();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询 Run 状态失败", e);
        }
    }

    private static @NonNull Instant toInstant(@Nullable Timestamp ts) {
        return ts == null ? Instant.EPOCH : ts.toInstant();
    }

    /**
     * 可空时刻：{@code null} 原样保留。
     *
     * <p>⚠️ <b>与 {@link #toInstant} 的区别是刻意的</b>：
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
     * <p>⚠️ 与 {@code JdbcRunEventQueryRepository} 同源铁律：
     * 外层有事务则复用，否则自开并绑定租户。
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
