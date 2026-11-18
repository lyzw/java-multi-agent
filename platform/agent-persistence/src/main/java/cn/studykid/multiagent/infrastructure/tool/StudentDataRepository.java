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

    /**
     * 画像允许的年级取值（与 {@code student_profile.grade_chk} 约束**逐字一致**）。
     *
     * <p>⚠️ 枚举必须与 DB CHECK 约束同源：约束外取值会让 INSERT 直接失败，
     * 而那会把「画像缺失」伪装成「写入结果不确定」。
     */
    private static final java.util.Set<String> PROFILE_GRADES =
            java.util.Set.of("初一", "初二", "初三");

    /** 画像允许的学科取值（与 {@code student_profile.subject_chk} 约束逐字一致）。 */
    private static final java.util.Set<String> PROFILE_SUBJECTS =
            java.util.Set.of("数学", "物理", "化学", "语文", "英语", "生物");

    /** Run 输入负载解析器（Jackson 3）。 */
    private static final tools.jackson.databind.ObjectMapper MAPPER =
            new tools.jackson.databind.ObjectMapper();

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
            // ⚠️⚠️ **先确保画像存在**（学习闭环前置条件 · 2026-11-17 实跑修复）：
            //    weakness_record 有外键 weakness_student_fk → student_profile(tenant_id, student_id)，
            //    而**生产路径此前没有任何创建画像的入口**（只有测试夹具在插），
            //    于是新学生的第一次 record_weakness 必然撞外键 → SQLException
            //    → 被当成「写入结果不确定」→ 工具结果 UNKNOWN → 薄弱点永远写不进去。
            //    这正是 P0-5「weakness_record 真有行」验收长期失败的**真因**
            //    （此前被误判为「能力目录没纳管」，实际目录修好后仍写不进）。
            ensureProfile(conn, tenantId, studentId, runId);

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

    /**
     * 确保学生画像存在（按本次 Run 请求里学生<b>自己声明</b>的年级/学科建档）。
     *
     * <p><b>为什么必须在写薄弱点前建档</b>：{@code weakness_record} 有外键
     * {@code weakness_student_fk → student_profile(tenant_id, student_id)}，
     * 而生产路径此前**没有任何创建画像的入口** —— 于是新学生第一次记录薄弱点
     * 必然撞外键，SQLException 被包装成「写入结果不确定」，
     * 工具结果落到 UNKNOWN，薄弱点永远写不进去（学习闭环断在最后一步）。
     *
     * <p><b>年级/学科的权威来源</b>：Run 的 {@code input_payload}
     * （学生提交辅导请求时自己填的年级/学科），**不是**平台猜测、也不是固定默认值。
     * 取不到或取值不在画像枚举内时**不建档**，并抛
     * {@link ProfileUnavailableException} —— 由调用方转成「明确失败」，
     * 而不是让外键错误伪装成「写入结果不确定」。
     *
     * <p><b>不覆盖既有画像</b>：{@code ON CONFLICT DO NOTHING} —— 首次登记为准。
     */
    private void ensureProfile(Connection conn, String tenantId, String studentId, String runId)
            throws SQLException {
        // ① 画像已存在 → 不动它（首次登记为准）
        if (profileExists(conn, tenantId, studentId)) {
            return;
        }
        // ② 从 Run 输入取学生声明的年级/学科
        String grade = null;
        String subject = null;
        String sql = """
                SELECT input_payload::text FROM run
                WHERE tenant_id = ?::uuid AND run_id = ?::uuid
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, runId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    // ⚠️ 只解析一次 input_payload：grade/subject 从同一棵 JSON 树取，
                    //    不要对同一文本做两次 MAPPER.readTree。解析失败 = 取不到 → 不建档、绝不猜值。
                    String raw = rs.getString(1);
                    if (raw != null && !raw.isBlank()) {
                        try {
                            var payload = MAPPER.readTree(raw);
                            if (payload != null && payload.isObject()) {
                                grade = textField(payload, "grade");
                                subject = textField(payload, "subject");
                            }
                        } catch (RuntimeException e) {
                            // 与原 readText 一致：解析失败走「不建档」分支，grade/subject 保持 null。
                            grade = null;
                            subject = null;
                        }
                    }
                }
            }
        }
        if (grade == null || subject == null
                || !PROFILE_GRADES.contains(grade) || !PROFILE_SUBJECTS.contains(subject)) {
            throw new ProfileUnavailableException(
                    "学生画像未建立且无法建档：本次请求未声明有效的年级/学科"
                            + "（grade=" + grade + ", subject=" + subject + "）");
        }
        // ③ 建档（幂等：并发下 ON CONFLICT 兜住）
        String insert = """
                INSERT INTO student_profile
                    (tenant_id, student_id, grade, subject, created_at, updated_at)
                VALUES (?::uuid, ?, ?, ?, now(), now())
                ON CONFLICT (tenant_id, student_id) DO NOTHING
                """;
        try (PreparedStatement ps = conn.prepareStatement(insert)) {
            ps.setString(1, tenantId);
            ps.setString(2, studentId);
            ps.setString(3, grade);
            ps.setString(4, subject);
            ps.executeUpdate();
        }
    }

    /** 画像是否已存在（租户内）。 */
    private boolean profileExists(Connection conn, String tenantId, String studentId)
            throws SQLException {
        String sql = """
                SELECT 1 FROM student_profile
                WHERE tenant_id = ?::uuid AND student_id = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, studentId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** 从 Run 输入 JSON 里取一个文本字段；取不到返回 null。 */
    /** 从已解析的 JSON 节点取文本字段（trim，空串归一为 null）。 */
    private static @Nullable String textField(
            tools.jackson.databind.JsonNode node, @NonNull String field) {
        var value = node.path(field);
        if (value == null || !value.isTextual()) {
            return null;
        }
        String text = value.asString();
        return text == null || text.isBlank() ? null : text.trim();
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

    /**
     * 画像不可用（无法建档）。
     *
     * <p>⚠️ <b>刻意不继承 {@link IllegalStateException}</b>：写路径把
     * {@code IllegalStateException} 一律映射成「写入结果不确定（UNKNOWN）」，
     * 而本异常发生在**任何写入之前**（建档前置检查阶段），
     * 结果是确定的「没写进去」—— 映射成 UNKNOWN 会让对账流程凭空多一轮，
     * 并把一个明确的配置缺口伪装成不确定的下游故障。
     */
    public static class ProfileUnavailableException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ProfileUnavailableException(String message) {
            super(message);
        }
    }
}