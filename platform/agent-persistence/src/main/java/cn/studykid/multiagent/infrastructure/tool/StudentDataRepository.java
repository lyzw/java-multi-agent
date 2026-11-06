package cn.studykid.multiagent.infrastructure.tool;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 画像与薄弱点数据访问（P2-D04）。
 *
 * <p><b>⚠️ 所有查询必须启用事务</b>（P1 教训）：
 * {@code set_config('app.tenant_id', ?, true)} 的事务局部语义，
 * 自动提交模式下绑定立即失效 → 查询恒返回 0 行 → 被误判为「数据不存在」。
 *
 * <p>本类统一走 {@link #inTenantScope}，确保「关自动提交 + 绑定租户」两件事同时做。
 */
public class StudentDataRepository {

    private final DataSource dataSource;

    public StudentDataRepository(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 在租户上下文内执行。
     *
     * <p>⚠️ 事务局部语义要求**必须开启事务**，故此处强制
     * {@code setAutoCommit(false)}；成功路径 commit（写入真正落库——F-2 修复），
     * 异常路径 rollback。
     */
    private <T> T inTenantScope(String tenantId, TenantQuery<T> body) {
        try (Connection conn = dataSource.getConnection()) {
            boolean prev = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                // ⚠️ 复用 P1 的 TenantSessionBinder（关自动提交 + 绑定租户）
                cn.studykid.multiagent.infrastructure.persistence.TenantSessionBinder.bind(
                        conn, new cn.studykid.multiagent.core.tenant.TenantContext(
                                tenantId, "worker", java.util.Set.of(), 0L));
                T result = body.apply(conn);
                // ⚠️ P3-D03 修复 F-2：成功路径必须 commit。
                //    原实现无条件 rollback —— insertWeakness 的写入从未落库（P2-D4 引入），
                //    record_weakness 实为空操作。只读方法 commit 是 no-op，无害。
                conn.commit();
                return result;
            } catch (SQLException e) {
                try {
                    conn.rollback();
                } catch (SQLException ignored) {
                    // 连接即将归还
                }
                throw new IllegalStateException("数据访问失败", e);
            } finally {
                try {
                    conn.setAutoCommit(prev);
                } catch (SQLException ignored) {
                    // 连接即将归还
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("获取连接失败", e);
        }
    }

    @FunctionalInterface
    private interface TenantQuery<T> {
        T apply(Connection conn) throws SQLException;
    }

    /** 画像读取结果。 */
    public record ProfileView(
            String studentId,
            String grade,
            String subject,
            List<WeaknessView> weaknesses,
            Instant updatedAt
    ) {
        public ProfileView {
            weaknesses = weaknesses == null ? List.of() : List.copyOf(weaknesses);
        }

        /** 空画像：学生不存在时返回（不报错，避免枚举）。 */
        public static @NonNull ProfileView empty(String studentId) {
            return new ProfileView(studentId, null, null, List.of(), null);
        }
    }

    /** 单条薄弱点。 */
    public record WeaknessView(
            String knowledgePoint,
            String errorType,
            Double confidence,
            Instant createdAt
    ) {
    }

    /**
     * 读取画像。
     *
     * @return 学生不存在时返回 {@link ProfileView#empty}，**不抛异常**
     */
    public @NonNull ProfileView loadProfile(String tenantId, String studentId) {
        return inTenantScope(tenantId, conn -> {
            String sql = """
                    SELECT grade, subject, updated_at
                    FROM student_profile
                    WHERE tenant_id = ?::uuid AND student_id = ?
                    """;
            String grade = null;
            String subject = null;
            Instant updatedAt = null;

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, tenantId);
                ps.setString(2, studentId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        grade = rs.getString("grade");
                        subject = rs.getString("subject");
                        updatedAt = rs.getTimestamp("updated_at").toInstant();
                    }
                }
            }

            // ⚠️ 不存在也返回空画像而非报错：否则可枚举有效 studentId
            if (grade == null) {
                return ProfileView.empty(studentId);
            }
            return new ProfileView(studentId, grade, subject,
                    loadWeaknesses(conn, tenantId, studentId), updatedAt);
        });
    }

    private List<WeaknessView> loadWeaknesses(Connection conn, String tenantId, String studentId)
            throws SQLException {
        String sql = """
                SELECT knowledge_point, error_type, confidence, created_at
                FROM weakness_record
                WHERE tenant_id = ?::uuid AND student_id = ?
                ORDER BY updated_at DESC
                LIMIT 50
                """;
        List<WeaknessView> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, studentId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    double confidence = rs.getDouble("confidence");
                    Double conf = rs.wasNull() ? null : confidence;
                    result.add(new WeaknessView(
                            rs.getString("knowledge_point"),
                            rs.getString("error_type"),
                            conf,
                            rs.getTimestamp("created_at").toInstant()));
                }
            }
        }
        return result;
    }

    /**
     * 写入薄弱点。
     *
     * @return 已存在时返回既有记录（幂等命中）
     * @throws DuplicateWeaknessException 同一键但内容不同（摘要不一致）
     */
    public @NonNull WriteOutcome insertWeakness(
            String tenantId,
            String studentId,
            String runId,
            String knowledgePoint,
            String errorType,
            Double confidence) {

        return inTenantScope(tenantId, conn -> {
            // ⚠️ 先查：命中且内容一致 → 幂等重放，返回首次结果
            Optional<String> existing = findExisting(conn, tenantId, studentId,
                    runId, knowledgePoint);
            if (existing.isPresent()) {
                return new WriteOutcome(existing.get(), true);
            }

            String sql = """
                    INSERT INTO weakness_record
                        (tenant_id, student_id, run_id, knowledge_point, error_type, confidence)
                    VALUES (?::uuid, ?, ?::uuid, ?, ?, ?)
                    RETURNING weakness_id
                    """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, tenantId);
                ps.setString(2, studentId);
                ps.setString(3, runId);
                ps.setString(4, knowledgePoint);
                ps.setString(5, errorType);
                if (confidence == null) {
                    ps.setNull(6, java.sql.Types.NUMERIC);
                } else {
                    ps.setDouble(6, confidence);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return new WriteOutcome(rs.getString(1), false);
                    }
                }
            }
            throw new IllegalStateException("插入未返回记录");
        });
    }

    private Optional<String> findExisting(Connection conn, String tenantId, String studentId,
                                         String runId, String knowledgePoint)
            throws SQLException {
        String sql = """
                SELECT weakness_id FROM weakness_record
                WHERE tenant_id = ?::uuid AND student_id = ?
                  AND run_id = ?::uuid AND knowledge_point = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, studentId);
            ps.setString(3, runId);
            ps.setString(4, knowledgePoint);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        }
    }

    /**
     * 对账查询（P2-D04 §4.2）。
     *
     * <p>用于 UNKNOWN 状态下确定该逻辑操作的实际结果。
     *
     * @return 查到记录返回其 ID；确认不存在返回空
     *         ⚠️ 由调用方区分「不存在」与「不可查」——
     *         本方法能确定结果，故不存在时返回 {@link Optional#empty()} 而非 null
     */
    public @NonNull Optional<String> reconcileWeakness(
            String tenantId, String studentId, String runId, String knowledgePoint) {
        return inTenantScope(tenantId, conn ->
                findExisting(conn, tenantId, studentId, runId, knowledgePoint));
    }

    /** 写入结果。 */
    public record WriteOutcome(String weaknessId, boolean replayed) {
    }

    /** 同一键但内容不同的冲突。 */
    public static class DuplicateWeaknessException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public DuplicateWeaknessException(String message) {
            super(message);
        }
    }
}