package cn.studykid.multiagent.infrastructure.persistence;

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
import cn.studykid.multiagent.ports.persistence.ProfileQueryPort;

/**
 * 学习画像查询实现（P4-D09 / G6）。
 *
 * <p>⚠️ <b>白名单纪律</b>：只查 {@code student_profile} 的
 * {@code grade}/{@code subject}/{@code updated_at} 与 {@code weakness_record} 的
 * 知识点/错因/置信度/时刻。
 * <b>不查</b>姓名、学校、班级等——DB 层也没有这些列（V2 迁移刻意不建）。
 */
@Repository
public class JdbcProfileQueryRepository implements ProfileQueryPort {

    /**
     * ⚠️ <b>{@code tenant_id} 条件不可删除</b>（即便 RLS 已隔离，应用侧条件仍是第一道防线）。
     */
    private static final String SQL_LOAD_PROFILE = """
            SELECT grade, subject, updated_at
            FROM student_profile
            WHERE tenant_id = ?::uuid AND student_id = ?
            """;

    private static final String SQL_LOAD_WEAKNESSES = """
            SELECT knowledge_point, error_type, confidence, created_at
            FROM weakness_record
            WHERE tenant_id = ?::uuid AND student_id = ?
            ORDER BY updated_at DESC
            LIMIT 50
            """;

    /** 单次返回的薄弱点上限，防止异常数据量下响应过大。 */
    private static final int MAX_WEAKNESSES = 50;

    private final DataSource dataSource;

    public JdbcProfileQueryRepository(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public @NonNull ProfileView loadProfile(@NonNull String tenantId,
                                            @NonNull String studentId) {
        try (TenantConnectionLease lease = tenantConnection(tenantId)) {
            try (PreparedStatement ps = lease.connection().prepareStatement(SQL_LOAD_PROFILE)) {
                ps.setString(1, tenantId);
                ps.setString(2, studentId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        // ⚠️ 学生不存在 → 返回空画像而非报错：
                        //    若不存在报错、存在返回数据，攻击者可据此枚举有效 studentId。
                        return ProfileView.empty(studentId);
                    }
                    String grade = rs.getString("grade");
                    String subject = rs.getString("subject");
                    Instant updatedAt = toNullableInstant(rs.getTimestamp("updated_at"));
                    return new ProfileView(studentId, grade, subject,
                            loadWeaknesses(lease.connection(), tenantId, studentId),
                            updatedAt);
                }
            } finally {
                // ⚠️ 只读查询：回滚以释放读事务。
                lease.rollbackQuietly();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询学习画像失败", e);
        }
    }

    private List<WeaknessView> loadWeaknesses(java.sql.Connection conn,
                                               String tenantId, String studentId)
            throws SQLException {
        List<WeaknessView> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(SQL_LOAD_WEAKNESSES)) {
            ps.setString(1, tenantId);
            ps.setString(2, studentId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next() && result.size() < MAX_WEAKNESSES) {
                    double raw = rs.getDouble("confidence");
                    Double confidence = rs.wasNull() ? null : raw;
                    result.add(new WeaknessView(
                            rs.getString("knowledge_point"),
                            rs.getString("error_type"),
                            confidence,
                            toInstant(rs.getTimestamp("created_at"))));
                }
            }
        }
        return result;
    }

    private static @NonNull Instant toInstant(@Nullable Timestamp ts) {
        return ts == null ? Instant.EPOCH : ts.toInstant();
    }

    /**
     * 可空时刻：{@code null} 原样保留。
     *
     * <p>⚠️ <b>{@code updated_at} 在学生不存在时本就为 {@code null}</b>，
     * 用 EPOCH 兜底会把它伪装成「1970 年更新的画像」；
     * 而 {@code ProfileView.updatedAt} 本身声明为 {@code @Nullable}，
     * 故此处必须原样传递。
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
