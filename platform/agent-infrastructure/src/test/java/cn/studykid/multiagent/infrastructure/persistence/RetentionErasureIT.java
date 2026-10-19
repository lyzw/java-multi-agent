package cn.studykid.multiagent.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.zaxxer.hikari.HikariDataSource;

import cn.studykid.multiagent.application.retention.ConsentWithdrawalService;
import cn.studykid.multiagent.application.retention.RetentionPurgeService;
import cn.studykid.multiagent.ports.persistence.ErasurePort;
import cn.studykid.multiagent.ports.persistence.ErasurePort.ErasureCertificate;

/**
 * 数据保留与删除集成测试（P4-D04）。
 *
 * <p><b>本类证明的核心命题</b>（真实 PG17、非特权角色 {@code app_agent}）：
 * <ol>
 *   <li><b>四类数据按承诺被清理</b>——到期即删，未到期不动；</li>
 *   <li><b>Prompt 清理不删行</b>（I-4）：{@code run} 行保留、
 *       {@code weakness_record} <b>不被级联删除</b>；</li>
 *   <li><b>审计受控通道</b>（I-1/I-2）：UPDATE 永远被拒；
 *       DELETE 未声明 actor 时被拒，声明后放行；</li>
 *   <li><b>租户隔离</b>（I-3）：A 的清理不影响 B；</li>
 *   <li><b>凭证幂等</b>（D-6）：同一窗口重复运行只产生一张凭证；</li>
 *   <li><b>撤回履约</b>（FR-019）：登记 → 到期 → 删除 → 留凭证。</li>
 * </ol>
 *
 * <p>⚠️ <b>每个用例自带夹具与清理</b>（F-8 纪律）：
 * 清理范围<b>严格等于</b>创建范围（按显式 ID 删），
 * 不得按租户批量删——那会误删开发账号数据，
 * 且污染的是<b>后来者</b>，失败现场会指向完全错误的方向。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
class RetentionErasureIT {

    private static final String SLUG_A = "t-a";
    private static final String SLUG_B = "t-b";

    private static HikariDataSource admin;
    private static HikariDataSource app;

    private static String tenantA;
    private static String tenantB;

    /** 本测试创建的实体，用于 @AfterAll 精确清理。 */
    private static final List<String> createdStudents = new ArrayList<>();
    private static final List<String> createdRuns = new ArrayList<>();
    private static final List<String> createdAudits = new ArrayList<>();

    private static String snapshotA;
    private static String snapshotB;

    @BeforeAll
    static void setUp() throws Exception {
        admin = FaultInjectionSupport.adminPool();
        app = FaultInjectionSupport.appPool(8);
        FaultInjectionSupport.requireNonPrivileged(app);

        tenantA = FaultInjectionSupport.tenantIdBySlug(admin, SLUG_A);
        tenantB = FaultInjectionSupport.tenantIdBySlug(admin, SLUG_B);
        // ⚠️ run.snapshot_id 是 (tenant_id, snapshot_id) 复合外键：
        //    给 B 租户的 run 复用 A 的 snapshot 会直接违反 run_snapshot_fk，
        //    且报错指向「外键」而非真正的错因「夹具拿错了租户」。
        snapshotA = FaultInjectionSupport.snapshotIdBySlug(admin, SLUG_A);
        snapshotB = FaultInjectionSupport.snapshotIdBySlug(admin, SLUG_B);
    }

    /** 按租户取它自己的 snapshot，避免复合外键越租户。 */
    private static String snapshotOf(String tenantId) {
        return tenantId.equals(tenantB) ? snapshotB : snapshotA;
    }

    @AfterAll
    static void tearDown() throws Exception {
        // ⚠️ 按显式 ID 精确删除，范围 = 创建范围
        try (Connection c = admin.getConnection()) {
            for (String id : createdStudents) {
                exec(c, "DELETE FROM student_profile WHERE student_id = ?", id);
            }
            for (String id : createdRuns) {
                exec(c, "DELETE FROM run WHERE run_id = ?::uuid", id);
            }
            // ⚠️ audit_record 有追加不可改触发器，清理必须走受控通道：
            //    admin（超级用户）同样受触发器约束——触发器不区分角色，
            //    这正是 R-3 防线的设计意图。故此处必须显式声明 actor。
            for (String id : createdAudits) {
                try (PreparedStatement decl = c.prepareStatement(
                        "SELECT set_config('agent.retention.actor', ?, true)")) {
                    decl.setString(1, "retention-it-cleanup");
                    try (ResultSet rs = decl.executeQuery()) {
                        rs.next();
                    }
                }
                exec(c, "DELETE FROM audit_record WHERE audit_id = ?::uuid", id);
            }
            // 撤回登记与凭证
            for (String id : createdStudents) {
                exec(c, "DELETE FROM consent_withdrawal WHERE student_id = ?", id);
                exec(c, "DELETE FROM erasure_certificate WHERE subject_key = ?", id);
            }
            exec(c, "DELETE FROM erasure_certificate WHERE tenant_id = ?::uuid", tenantA);
            exec(c, "DELETE FROM erasure_certificate WHERE tenant_id = ?::uuid", tenantB);
        }
        if (app != null) {
            app.close();
        }
        if (admin != null) {
            admin.close();
        }
    }

    // ══════════════════════════════════════════════════════════════
    // N-1 画像到期清理
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("N-1 学习画像 12 个月到期清理")
    class ProfileRetention {

        @Test
        @DisplayName("R-1a 超过 12 个月的画像被删除")
        void expiredProfileDeleted() throws Exception {
            String student = newStudent();
            insertProfile(tenantA, student, daysAgo(400));

            int rows = erasure().purgeExpiredProfiles(tenantA, monthsAgo(12), 1000);

            assertThat(rows).as("到期画像应被删除").isEqualTo(1);
            assertThat(countProfiles(tenantA, student)).isEqualTo(0L);
        }

        @Test
        @DisplayName("R-1b 未到 12 个月的画像必须保留（过早删除 = 业务数据丢失）")
        void activeProfileKept() throws Exception {
            String student = newStudent();
            insertProfile(tenantA, student, daysAgo(300));

            int rows = erasure().purgeExpiredProfiles(tenantA, monthsAgo(12), 1000);

            assertThat(rows).isEqualTo(0);
            assertThat(countProfiles(tenantA, student))
                    .as("⚠️ 未满 11 个月的画像不得被删——保留期是下限承诺，不是建议")
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("R-1c 画像删除时其薄弱点随 CASCADE 一并消失（不留孤儿明细）")
        void weaknessCascadeRemoved() throws Exception {
            String student = newStudent();
            String runId = newRun(tenantA, daysAgo(400));
            insertProfile(tenantA, student, daysAgo(400));
            insertWeakness(tenantA, student, runId, daysAgo(400));

            erasure().purgeExpiredProfiles(tenantA, monthsAgo(12), 1000);

            assertThat(countWeakness(tenantA, student))
                    .as("薄弱点经 FK CASCADE 删除，不得残留孤儿明细")
                    .isEqualTo(0L);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // N-2 Prompt 90 天：置空且不得级联删业务数据（I-4 核心）
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("N-2 Prompt 到期置空（不可删行）")
    class PromptBlanking {

        @Test
        @DisplayName("R-2a ⚠️ Prompt 被置空，但 run 行仍在且 weakness_record 不被级联删除")
        void promptBlankedWithoutCascade() throws Exception {
            String student = newStudent();
            String runId = newRun(tenantA, daysAgo(120));
            insertProfile(tenantA, student, daysAgo(120));
            insertWeakness(tenantA, student, runId, daysAgo(120));

            int blanked = erasure().blankExpiredPrompts(tenantA, daysAgo(90), 1000);

            assertThat(blanked).as("到期 run 的 Prompt 应被置空").isEqualTo(1);
            assertThat(countRuns(tenantA, runId))
                    .as("⚠️ run 行必须保留：删行会经 ON DELETE CASCADE 抹掉学生薄弱点")
                    .isEqualTo(1L);
            assertThat(countWeakness(tenantA, student))
                    .as("⚠️⚠️ I-4：薄弱点是业务数据，Prompt 清理绝不能连带删除它")
                    .isEqualTo(1L);
            assertThat(runInputPayload(tenantA, runId))
                    .as("Prompt 载荷应已从业务内容置为空对象")
                    .isEqualTo("{}");
        }

        @Test
        @DisplayName("R-2b 未到 90 天的 Prompt 保持原样")
        void freshPromptUntouched() throws Exception {
            String runId = newRun(tenantA, daysAgo(30));

            int blanked = erasure().blankExpiredPrompts(tenantA, daysAgo(90), 1000);

            assertThat(blanked).isEqualTo(0);
            assertThat(runInputPayload(tenantA, runId)).contains("sensitive-prompt");
        }

        @Test
        @DisplayName("R-2c ⚠️ 在途（RUNNING）的 Run 不得被置空——否则 Worker 读到空输入")
        void runningRunNotBlanked() throws Exception {
            String runId = newRunningRun(tenantA, daysAgo(120));

            int blanked = erasure().blankExpiredPrompts(tenantA, daysAgo(90), 1000);

            assertThat(blanked).as("在途 Run 不在清理范围内").isEqualTo(0);
            assertThat(runInputPayload(tenantA, runId))
                    .as("⚠️ 清空在途 Run 的输入会让 Worker 收到空 Prompt，"
                            + "表现为模型故障而真实原因是误改在途数据")
                    .contains("sensitive-prompt");
        }
    }

    // ══════════════════════════════════════════════════════════════
    // N-3 审计受控清理通道（I-1 / I-2）
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("N-3 审计：更新永远禁止，删除须声明主体")
    class AuditControlledPurge {

        @Test
        @DisplayName("R-3a 声明 actor 后到期审计可被清理")
        void expiredAuditPurgedWithActor() throws Exception {
            String auditId = newAudit(tenantA, daysAgo(200));
            // 同轮其它用例也会留下到期审计行（@Nested 顺序不定），
            // 故先记录基线：清理是**批量**语义，本就不该断言 rows == 1。
            long before = countExpiredAudits(tenantA, daysAgo(180));

            int rows = erasure().purgeExpiredAudits(
                    tenantA, daysAgo(180), 1000, "retention-job");

            assertThat(rows).as("至少应清掉刚创建的那一行").isGreaterThanOrEqualTo(1);
            assertThat(rows).as("不得超出该租户当前到期总数").isLessThanOrEqualTo((int) before);
            assertThat(countAudits(tenantA, auditId))
                    .as("到期审计应被清理")
                    .isEqualTo(0L);
            assertThat(countExpiredAudits(tenantA, daysAgo(180)))
                    .as("清理后该租户不应再有到期审计")
                    .isEqualTo(0L);
        }

        @Test
        @DisplayName("R-3b ⚠️ 未声明 actor 的删除被 DB 拒绝（受控通道的唯一开关）")
        void directDeleteWithoutActorRejected() throws Exception {
            String auditId = newAudit(tenantA, daysAgo(200));

            // ⚠️ 刻意用裸连接模拟「有人绕过 Repository 直连删」的情形。
            //    若这条能删成功，R-3 防线就形同虚设。
            assertThatThrownBy(() -> deleteAuditDirectly(tenantA, auditId, false))
                    .as("⚠️ 未声明操作主体的审计删除必须被触发器拒绝（I-2）")
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("追加不可改");

            assertThat(countAudits(tenantA, auditId)).isEqualTo(1L);
        }

        @Test
        @DisplayName("R-3c ⚠️ UPDATE 永远被拒绝（清理只删不改）")
        void auditUpdateAlwaysRejected() throws Exception {
            String auditId = newAudit(tenantA, daysAgo(200));

            assertThatThrownBy(() -> updateAuditDirectly(tenantA, auditId))
                    .as("⚠️ 允许 UPDATE 就等于允许把不利审计改成有利审计，这是 R-3 底线")
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("追加不可改");
        }

        @Test
        @DisplayName("R-3d actor 为空时 Java 侧 fail-fast，不等 DB 报错")
        void blankActorRejectedInJava() {
            assertThatThrownBy(() ->
                    erasure().purgeExpiredAudits(tenantA, daysAgo(180), 10, "  "))
                    .as("空白 actor 应在应用层就被拒绝，并给出可操作的错误信息")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("操作主体");
        }
    }

    // ══════════════════════════════════════════════════════════════
    // N-4 Trace 30 天
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("N-4 Trace（run_event）30 天清理")
    class TraceRetention {

        @Test
        @DisplayName("R-4a 超过 30 天的事件被删除")
        void expiredTraceDeleted() throws Exception {
            String runId = newRun(tenantA, daysAgo(60));
            insertRunEvent(tenantA, runId, 1, daysAgo(40));

            int rows = erasure().purgeExpiredTraces(tenantA, daysAgo(30), 1000);

            assertThat(rows).isEqualTo(1);
        }

        @Test
        @DisplayName("R-4b 未到 30 天的事件保留（SSE 续传需要近期游标）")
        void freshTraceKept() throws Exception {
            String runId = newRun(tenantA, daysAgo(10));
            insertRunEvent(tenantA, runId, 1, daysAgo(5));

            int rows = erasure().purgeExpiredTraces(tenantA, daysAgo(30), 1000);

            assertThat(rows).isEqualTo(0);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // N-5 租户隔离（I-3）
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("N-5 ⚠️ 租户隔离：清理不得越界")
    class TenantIsolation {

        @Test
        @DisplayName("R-5a ⚠️⚠️ A 租户的清理不影响 B 租户的数据")
        void otherTenantUntouched() throws Exception {
            String studentB = newStudent();
            insertProfile(tenantB, studentB, daysAgo(500));

            // A 租户跑清理
            erasure().purgeExpiredProfiles(tenantA, monthsAgo(12), 1000);

            assertThat(countProfiles(tenantB, studentB))
                    .as("⚠️⚠️ B 租户到期画像必须仍然存在——"
                            + "清理越过租户边界即 Sev-0 数据销毁")
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("R-5b Prompt 置空同样不得越过租户边界")
        void promptBlankIsolated() throws Exception {
            String runB = newRun(tenantB, daysAgo(120));

            erasure().blankExpiredPrompts(tenantA, daysAgo(90), 1000);

            assertThat(runInputPayload(tenantB, runB))
                    .as("B 租户的 Prompt 不得被 A 的清理置空")
                    .contains("sensitive-prompt");
        }
    }

    // ══════════════════════════════════════════════════════════════
    // N-6 凭证幂等（D-6）
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("N-6 删除凭证幂等与举证")
    class CertificateIdempotency {

        @Test
        @DisplayName("R-6a ⚠️ 同一窗口重复运行只产生一张凭证")
        void repeatedRunYieldsOneCertificate() throws Exception {
            String student = newStudent();
            insertProfile(tenantA, student, daysAgo(400));

            RetentionPurgeService service = purgeService();
            service.runOnce(tenantA);
            service.runOnce(tenantA);
            service.runOnce(tenantA);

            List<ErasureCertificate> certs = erasure().findCertificates(
                    tenantA, ErasureCertificate.REASON_RETENTION_EXPIRED, 50);
            assertThat(certs)
                    .as("⚠️ 重复清理窗口不得产生重复凭证——"
                            + "重复凭证会被误读为「删了两次」")
                    .hasSize(1);
            assertThat(certs.get(0).detail())
                    .as("凭证明细应记录实际删除行数")
                    .containsKey("student_profile");
        }

        @Test
        @DisplayName("R-6b 无变更时不写凭证（避免空凭证淹没举证材料）")
        void noCertificateWhenNothingHappened() throws Exception {
            RetentionPurgeService service = purgeService();
            service.runOnce(tenantA);

            List<ErasureCertificate> certs = erasure().findCertificates(
                    tenantA, ErasureCertificate.REASON_RETENTION_EXPIRED, 50);
            assertThat(certs).as("无实际删除时不应留下凭证").isEmpty();
        }
    }

    // ══════════════════════════════════════════════════════════════
    // N-7 监护人撤回同意（FR-019）
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("N-7 监护人撤回同意后删除")
    class ConsentWithdrawal {

        @Test
        @DisplayName("R-7a 撤回到期后学生数据被删除并留下凭证")
        void withdrawnStudentErased() throws Exception {
            String student = newStudent();
            insertProfile(tenantA, student, daysAgo(10));

            ConsentWithdrawalService withdrawals = new ConsentWithdrawalService(
                    erasure(), 0, java.time.Clock.systemUTC());
            withdrawals.withdraw(tenantA, student);

            // 宽限期 0 天 → 立即到期
            RetentionPurgeService service = purgeService();
            service.runOnce(tenantA);

            assertThat(countProfiles(tenantA, student))
                    .as("⚠️ FR-019：撤回同意后学生数据必须被删除")
                    .isEqualTo(0L);

            List<ErasureCertificate> certs = erasure().findCertificates(
                    tenantA, ErasureCertificate.REASON_CONSENT_WITHDRAWN, 50);
            assertThat(certs)
                    .as("撤回删除必须留凭证，否则「删除了」无法举证")
                    .extracting(ErasureCertificate::subjectKey)
                    .contains(student);
        }

        @Test
        @DisplayName("R-7b 撤回未到期前不删除（过早删除 = 未经请求的销毁）")
        void notErasedBeforeDue() throws Exception {
            String student = newStudent();
            insertProfile(tenantA, student, daysAgo(10));

            new ConsentWithdrawalService(erasure(), 30, java.time.Clock.systemUTC())
                    .withdraw(tenantA, student);

            purgeService().runOnce(tenantA);

            assertThat(countProfiles(tenantA, student))
                    .as("宽限期内不得删除：FR-019 的 30 天是上限，不是立即执行")
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("R-7c ⚠️ 重复撤回不产生重复登记与重复凭证")
        void duplicateWithdrawalIdempotent() throws Exception {
            String student = newStudent();
            insertProfile(tenantA, student, daysAgo(10));

            ConsentWithdrawalService withdrawals = new ConsentWithdrawalService(
                    erasure(), 0, java.time.Clock.systemUTC());
            withdrawals.withdraw(tenantA, student);
            withdrawals.withdraw(tenantA, student);

            purgeService().runOnce(tenantA);

            List<ErasureCertificate> certs = erasure().findCertificates(
                    tenantA, ErasureCertificate.REASON_CONSENT_WITHDRAWN, 50);
            assertThat(certs)
                    .filteredOn(c -> student.equals(c.subjectKey()))
                    .as("⚠️ 重复撤回不得产生两张凭证——会被误读为「删了两次」")
                    .hasSize(1);
        }

        @Test
        @DisplayName("R-7d ⚠️ 学生标识格式非法时应用层直接拒绝")
        void malformedStudentIdRejected() {
            ConsentWithdrawalService withdrawals = new ConsentWithdrawalService(
                    erasure(), 30, java.time.Clock.systemUTC());

            assertThatThrownBy(() -> withdrawals.withdraw(tenantA, "张三-20190101"))
                    .as("⚠️ 真实姓名/学号不得进入撤回登记表——该表本身会成为违规数据源")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("格式");
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 夹具
    // ══════════════════════════════════════════════════════════════

    private static ErasurePort erasure() {
        return new JdbcErasureRepository(app);
    }

    private static RetentionPurgeService purgeService() {
        return new RetentionPurgeService(
                erasure(),
                new RetentionPurgeService.RetentionSettings(12, 90, 180, 30, 0, 1000),
                "retention-job",
                java.time.Clock.systemUTC());
    }

    private static String newStudent() {
        String id = "stu_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        createdStudents.add(id);
        return id;
    }

    private static String newRun(String tenantId, Instant createdAt) throws SQLException {
        String runId = uuid();
        createdRuns.add(runId);
        try (Connection c = admin.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO run (run_id, tenant_id, snapshot_id, idempotency_key, "
                            + "request_digest, input_payload, result_payload, correlation_id, "
                            + "state, fencing_token, created_at, completed_at) "
                            + "VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, "
                            + "  '{\"q\":\"sensitive-prompt\"}'::jsonb, '{\"a\":\"r\"}'::jsonb, "
                            + "  ?, 'SUCCEEDED', 0, ?, ?)")) {
                ps.setString(1, runId);
                ps.setString(2, tenantId);
                ps.setString(3, snapshotOf(tenantId));
                ps.setString(4, "ret-" + runId);
                ps.setString(5, "d-" + runId);
                ps.setString(6, "corr-" + runId);
                ps.setTimestamp(7, Timestamp.from(createdAt));
                ps.setTimestamp(8, Timestamp.from(createdAt));
                ps.executeUpdate();
            }
        }
        return runId;
    }

    private static String newRunningRun(String tenantId, Instant createdAt) throws SQLException {
        String runId = uuid();
        createdRuns.add(runId);
        try (Connection c = admin.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO run (run_id, tenant_id, snapshot_id, idempotency_key, "
                            + "request_digest, input_payload, correlation_id, "
                            + "state, fencing_token, created_at) "
                            + "VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, "
                            + "  '{\"q\":\"sensitive-prompt\"}'::jsonb, "
                            + "  ?, 'RUNNING', 0, ?)")) {
                ps.setString(1, runId);
                ps.setString(2, tenantId);
                ps.setString(3, snapshotOf(tenantId));
                ps.setString(4, "ret-run-" + runId);
                ps.setString(5, "d-" + runId);
                ps.setString(6, "corr-" + runId);
                ps.setTimestamp(7, Timestamp.from(createdAt));
                ps.executeUpdate();
            }
        }
        return runId;
    }

    private static void insertProfile(String tenantId, String studentId, Instant createdAt)
            throws SQLException {
        try (Connection c = admin.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO student_profile (student_id, tenant_id, grade, subject, created_at) "
                            + "VALUES (?, ?::uuid, '初二', '数学', ?)")) {
                ps.setString(1, studentId);
                ps.setString(2, tenantId);
                ps.setTimestamp(3, Timestamp.from(createdAt));
                ps.executeUpdate();
            }
        }
    }

    private static void insertWeakness(String tenantId, String studentId, String runId,
                                       Instant createdAt) throws SQLException {
        try (Connection c = admin.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO weakness_record (tenant_id, student_id, run_id, "
                            + "knowledge_point, error_type, created_at) "
                            + "VALUES (?::uuid, ?, ?::uuid, '一元二次方程', '计算失误', ?)")) {
                ps.setString(1, tenantId);
                ps.setString(2, studentId);
                ps.setString(3, runId);
                ps.setTimestamp(4, Timestamp.from(createdAt));
                ps.executeUpdate();
            }
        }
    }

    private static void insertRunEvent(String tenantId, String runId, long seq, Instant createdAt)
            throws SQLException {
        try (Connection c = admin.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO run_event (tenant_id, run_id, sequence_no, event_type, "
                            + "payload, created_at) VALUES (?::uuid, ?::uuid, ?, 'STEP', '{}'::jsonb, ?)")) {
                ps.setString(1, tenantId);
                ps.setString(2, runId);
                ps.setLong(3, seq);
                ps.setTimestamp(4, Timestamp.from(createdAt));
                ps.executeUpdate();
            }
        }
    }

    private static String newAudit(String tenantId, Instant createdAt) throws SQLException {
        String id = uuid();
        createdAudits.add(id);
        try (Connection c = admin.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO audit_record (audit_id, tenant_id, actor_id, action, "
                            + "resource_type, detail, created_at) "
                            + "VALUES (?::uuid, ?::uuid, 'actor-1', 'RUN_CREATE', 'RUN', "
                            + "'{}'::jsonb, ?)")) {
                ps.setString(1, id);
                ps.setString(2, tenantId);
                ps.setTimestamp(3, Timestamp.from(createdAt));
                ps.executeUpdate();
            }
        }
        return id;
    }

    /**
     * 绕过 Repository 直接删除审计（模拟「有人直连改数据」）。
     *
     * @param declareActor 是否声明受控清理主体——这是触发器唯一的放行开关
     */
    private static void deleteAuditDirectly(String tenantId, String auditId, boolean declareActor)
            throws SQLException {
        try (Connection c = FaultInjectionSupport.rawAppConnection()) {
            c.setAutoCommit(false);
            FaultInjectionSupport.bind(c, tenantId);
            if (declareActor) {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT set_config('agent.retention.actor', ?, true)")) {
                    ps.setString(1, "rogue");
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM audit_record WHERE audit_id = ?::uuid")) {
                ps.setString(1, auditId);
                ps.executeUpdate();
            }
            c.commit();
        }
    }

    private static void updateAuditDirectly(String tenantId, String auditId) throws SQLException {
        try (Connection c = FaultInjectionSupport.rawAppConnection()) {
            c.setAutoCommit(false);
            FaultInjectionSupport.bind(c, tenantId);
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE audit_record SET action = 'TAMPERED' WHERE audit_id = ?::uuid")) {
                ps.setString(1, auditId);
                ps.executeUpdate();
            }
            c.commit();
        }
    }

    private static long countProfiles(String tenantId, String studentId) throws SQLException {
        return countViaAdmin("SELECT count(*) FROM student_profile "
                + "WHERE tenant_id = ?::uuid AND student_id = ?", tenantId, studentId);
    }

    private static long countWeakness(String tenantId, String studentId) throws SQLException {
        return countViaAdmin("SELECT count(*) FROM weakness_record "
                + "WHERE tenant_id = ?::uuid AND student_id = ?", tenantId, studentId);
    }

    private static long countRuns(String tenantId, String runId) throws SQLException {
        return countViaAdmin("SELECT count(*) FROM run "
                + "WHERE tenant_id = ?::uuid AND run_id = ?::uuid", tenantId, runId);
    }

    private static long countAudits(String tenantId, String auditId) throws SQLException {
        return countViaAdmin("SELECT count(*) FROM audit_record "
                + "WHERE tenant_id = ?::uuid AND audit_id = ?::uuid", tenantId, auditId);
    }

    private static long countExpiredAudits(String tenantId, Instant cutoff) throws SQLException {
        try (Connection c = admin.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM audit_record "
                             + "WHERE tenant_id = ?::uuid AND created_at < ?")) {
            ps.setString(1, tenantId);
            ps.setTimestamp(2, Timestamp.from(cutoff));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    private static String runInputPayload(String tenantId, String runId) throws SQLException {
        try (Connection c = admin.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT input_payload::text FROM run "
                            + "WHERE tenant_id = ?::uuid AND run_id = ?::uuid")) {
                ps.setString(1, tenantId);
                ps.setString(2, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : "<missing>";
                }
            }
        }
    }

    private static long countViaAdmin(String sql, Object... args) throws SQLException {
        try (Connection c = admin.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setString(i + 1, String.valueOf(args[i]));
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    private static void exec(Connection c, String sql, String arg) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, arg);
            ps.executeUpdate();
        }
    }

    private static Instant daysAgo(long days) {
        return Instant.now().minus(days, ChronoUnit.DAYS);
    }

    /**
     * ⚠️ 与生产代码同源的坑（F-12）：{@code Instant.minus(n, ChronoUnit.MONTHS)}
     * 会抛 {@link java.time.temporal.UnsupportedTemporalTypeException}
     * —— Instant 不支持「月」。必须先转 ZonedDateTime 再按月回退。
     */
    private static Instant monthsAgo(long months) {
        return Instant.now().atZone(ZoneId.of("Asia/Shanghai"))
                .minus(months, ChronoUnit.MONTHS).toInstant();
    }

    private static String uuid() {
        return UUID.randomUUID().toString();
    }
}
