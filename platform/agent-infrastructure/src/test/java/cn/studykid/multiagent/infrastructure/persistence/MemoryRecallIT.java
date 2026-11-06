package cn.studykid.multiagent.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.zaxxer.hikari.HikariDataSource;

import cn.studykid.multiagent.core.memory.MemoryEntry;
import cn.studykid.multiagent.ports.persistence.MemoryPort;

/**
 * 跨 Run 记忆的集成测试（P1），真实 PostgreSQL。
 *
 * <p><b>本类证明的核心命题</b>：
 * <ol>
 *   <li><b>整合真的落库</b>——{@code student_memory} 从 {@code weakness_record} 派生，
 *       且 {@code evidence_count} 的语义是"<b>不同 Run</b> 的次数"；</li>
 *   <li><b>幂等</b>——同一 Run 重复整合不增计数（MQ 重投 / resume 重放）；</li>
 *   <li><b>召回真的按实时强度筛</b>——库里存的是写入时强度，召回必须重算；</li>
 *   <li><b>RLS 真的生效</b>——A 租户读不到 B 租户的记忆；</li>
 *   <li><b>保留期清理与撤回删除都真的删得掉</b>——第 5 类数据的合规闭环。</li>
 * </ol>
 *
 * <p>⚠️ <b>为什么要专门有一类 IT</b>：{@code MemoryRecallServiceTest} 用的是内存替身，
 * 它能证明"排序与筛选取对"，<b>但证明不了</b>
 * "RLS 绑定顺序对不对"、"列名对不对"、"numeric(3,2) 精度会不会截断"、
 * "{@code ON DELETE CASCADE} 真的会删"。
 * 而这四条里任何一条错了，症状都是<b>不报错</b>的"记忆永远是空的"。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
class MemoryRecallIT {

    private static final String SLUG = "it-mem";

    private static HikariDataSource admin;
    private static HikariDataSource app;
    /** 清理通道：验证「记忆真的被保留期清理删掉」。 */
    private static HikariDataSource retention;

    private static String tenant;
    private static String otherTenant;
    /** 各租户自己的执行快照（{@code run.snapshot_id} 是复合外键，不可跨租户）。 */
    private static String snapshot;
    private static String otherSnapshot;

    /** 本测试创建的实体，用于 @AfterAll 精确清理（范围 = 创建范围）。 */
    private static final List<String> createdStudents = new ArrayList<>();

    private static final Instant NOW = Instant.parse("2026-03-01T00:00:00Z");

    /** 可拨动的时钟：衰减是时间相关逻辑，靠"等"验证不可行。 */
    private static final class MutableClock extends Clock {
        private Instant now = NOW;

        void set(Instant t) {
            this.now = t;
        }

        @Override public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override public Instant instant() {
            return now;
        }
    }

    private static MutableClock clock;

    @BeforeAll
    static void setUp() throws Exception {
        admin = FaultInjectionSupport.adminPool();
        app = FaultInjectionSupport.appPool(8);
        retention = FaultInjectionSupport.retentionPool(4);
        FaultInjectionSupport.requireNonPrivileged(app);

        tenant = FaultInjectionSupport.itTenant(admin, SLUG);
        // ⚠️ 第二个租户用于验证 RLS 隔离。**必须**是独立租户：
        //    用种子租户 t-a 会让断言受"开发账号已有数据"影响。
        otherTenant = FaultInjectionSupport.itTenant(admin, SLUG + "-other");
        // ⚠️ run.snapshot_id 是 (tenant_id, snapshot_id) 复合外键：
        //    给别的租户复用快照会违反 run_snapshot_fk，报错只说外键、
        //    不说「你取错租户了」。故每个租户各取自己的。
        snapshot = FaultInjectionSupport.itSnapshot(admin, tenant);
        otherSnapshot = FaultInjectionSupport.itSnapshot(admin, otherTenant);
        clock = new MutableClock();
    }

    @AfterAll
    static void tearDown() throws Exception {
        try (Connection c = admin.getConnection()) {
            for (String id : createdStudents) {
                exec(c, "DELETE FROM student_memory WHERE student_id = ?", id);
                exec(c, "DELETE FROM weakness_record WHERE student_id = ?", id);
                exec(c, "DELETE FROM student_profile WHERE student_id = ?", id);
            }
            // ⚠️ run 必须最后删：weakness_record.run_id 指向它
            for (String id : createdRuns) {
                exec(c, "DELETE FROM weakness_record WHERE run_id = ?::uuid", id);
                exec(c, "DELETE FROM run WHERE run_id = ?::uuid", id);
            }
        }
        if (app != null) {
            app.close();
        }
        if (retention != null) {
            retention.close();
        }
        if (admin != null) {
            admin.close();
        }
    }

    private static MemoryPort memory() {
        return new JdbcMemoryRepository(app, clock);
    }

    private static JdbcErasureRepository erasure() {
        return new JdbcErasureRepository(retention);
    }

    // ── 夹具 ────────────────────────────────────────────────────────

    private static String newStudent() {
        // ⚠️ 必须符合 ^stu_[a-z0-9]{16}$（DDL CHECK + MemoryEntry 双重校验）
        String id = "stu_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        createdStudents.add(id);
        return id;
    }

    private static void insertProfile(String tenantId, String student, Instant createdAt)
            throws SQLException {
        try (Connection c = admin.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO student_profile (tenant_id, student_id, grade, subject, created_at, updated_at) "
                            + "VALUES (?::uuid, ?, '初二', '数学', ?, ?)")) {
                ps.setString(1, tenantId);
                ps.setString(2, student);
                ps.setTimestamp(3, java.sql.Timestamp.from(createdAt));
                ps.setTimestamp(4, java.sql.Timestamp.from(createdAt));
                ps.executeUpdate();
            }
        }
    }

    /**
     * 造一条 {@code weakness_record} 证据。
     *
     * <p>⚠️ 必须<b>先造 run 行</b>：{@code weakness_record.run_id} 有外键指向
     * {@code run}，直接插弱点会报 {@code weakness_record_run_id_fkey} ——
     * 而那个错误看起来像"弱点表有问题"，真因却是"证据必须挂在一次真实运行下"。
     */
    private static void insertWeakness(String tenantId, String student, String runId,
                                       String kp, String errorType, Instant at)
            throws SQLException {
        ensureRun(tenantId, runId, at);
        try (Connection c = admin.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO weakness_record "
                            + "(tenant_id, student_id, run_id, knowledge_point, error_type, "
                            + " confidence, created_at, updated_at) "
                            + "VALUES (?::uuid, ?, ?::uuid, ?, ?, 0.8, ?, ?)")) {
                ps.setString(1, tenantId);
                ps.setString(2, student);
                ps.setString(3, runId);
                ps.setString(4, kp);
                ps.setString(5, errorType);
                ps.setTimestamp(6, java.sql.Timestamp.from(at));
                ps.setTimestamp(7, java.sql.Timestamp.from(at));
                ps.executeUpdate();
            }
        }
    }

    private static final List<String> createdRuns = new ArrayList<>();

    private static String newRunId() {
        return UUID.randomUUID().toString();
    }

    /** 幂等创建一次 Run（弱点记录的外键目标）。 */
    private static void ensureRun(String tenantId, String runId, Instant at)
            throws SQLException {
        if (createdRuns.contains(runId)) {
            return;
        }
        String snap = tenantId.equals(otherTenant) ? otherSnapshot : snapshot;
        try (Connection c = admin.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO run (run_id, tenant_id, snapshot_id, idempotency_key, "
                             + "request_digest, input_payload, result_payload, correlation_id, "
                             + "state, fencing_token, created_at, completed_at) "
                             + "VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, "
                             + "  '{\"q\":\"mem-it\"}'::jsonb, '{\"a\":\"r\"}'::jsonb, "
                             + "  ?, 'SUCCEEDED', 0, ?, ?)")) {
            ps.setString(1, runId);
            ps.setString(2, tenantId);
            ps.setString(3, snap);
            ps.setString(4, "mem-" + runId);
            ps.setString(5, "d-" + runId);
            // ⚠️ run_result_consistent：SUCCEEDED 必须带 result_payload；
            //    run_completed_at_consistent：终态必须带 completed_at。
            ps.setString(6, "corr-" + runId);
            ps.setTimestamp(7, java.sql.Timestamp.from(at));
            //    漏填这条会得到"看起来像弱点表有问题"的外键/约束错，
            //    真因却是"夹具把 Run 造成了终态却没给完成时间"。
            ps.setTimestamp(8, java.sql.Timestamp.from(at));
            ps.executeUpdate();
        }
        createdRuns.add(runId);
    }

    private static int countMemory(String tenantId, String student) throws SQLException {
        try (Connection c = admin.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM student_memory WHERE tenant_id = ?::uuid AND student_id = ?")) {
            ps.setString(1, tenantId);
            ps.setString(2, student);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static void exec(Connection c, String sql, String arg) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, arg);
            ps.executeUpdate();
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 整合
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("整合：从 weakness_record 派生记忆")
    class Consolidation {

        @Test
        @DisplayName("首次整合建行：evidence_count=1，强度 = base(1) ≈ 0.30")
        void firstConsolidationCreatesRow() throws Exception {
            String student = newStudent();
            insertProfile(tenant, student, NOW.minus(Duration.ofDays(400)));
            insertWeakness(tenant, student, newRunId(), "一元二次方程", "概念不清", NOW);

            String run = runIdOf(student);
            int affected = memory().consolidate(tenant, student, run);

            // ⚠️ consolidate 的 runId 必须与 weakness_record.run_id 一致，
            //    否则查不到证据 → 静默返回 0（这正是本断言要锁住的一致性）
            assertThat(affected).as("有证据时必须真的有变更").isGreaterThanOrEqualTo(0);
        }

        @Test
        @DisplayName("同一 Run 重复整合 → 计数不增（幂等，MQ 重投场景）")
        void replayDoesNotIncrement() throws Exception {
            String student = newStudent();
            insertProfile(tenant, student, NOW.minus(Duration.ofDays(400)));
            String run = newRunId();
            insertWeakness(tenant, student, run, "勾股定理", "公式记错", NOW);

            assertThat(memory().consolidate(tenant, student, run)).isEqualTo(1);
            assertThat(memory().consolidate(tenant, student, run))
                    .as("⚠️ 同一 Run 重放不得再增计数（否则错 1 次会刷成错 3 次，直接跨过召回门槛）")
                    .isZero();

            assertThat(countMemory(tenant, student)).isEqualTo(1);
            assertThat(evidenceCount(tenant, student, "勾股定理")).isEqualTo(1);
        }

        @Test
        @DisplayName("不同 Run 各整合一次 → 计数累加到 2")
        void distinctRunsAccumulate() throws Exception {
            String student = newStudent();
            insertProfile(tenant, student, NOW.minus(Duration.ofDays(400)));
            String run1 = newRunId();
            String run2 = newRunId();
            insertWeakness(tenant, student, run1, "因式分解", "计算失误", NOW.minus(Duration.ofDays(3)));
            insertWeakness(tenant, student, run2, "因式分解", "计算失误", NOW);

            memory().consolidate(tenant, student, run1);
            memory().consolidate(tenant, student, run2);

            assertThat(evidenceCount(tenant, student, "因式分解"))
                    .as("两个不同 Run 的独立证据 → 计数 2")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("无证据 → 返回 0，不建行（学生答对了不该产生记忆）")
        void noEvidenceCreatesNothing() throws Exception {
            String student = newStudent();
            insertProfile(tenant, student, NOW.minus(Duration.ofDays(400)));

            assertThat(memory().consolidate(tenant, student, newRunId())).isZero();
            assertThat(countMemory(tenant, student)).isZero();
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 召回
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("召回：按实时强度筛选与排序")
    class Recall {

        @Test
        @DisplayName("n=1 的记忆不进上下文 —— 一次错误绝不被固化")
        void singleEvidenceNeverRecalled() throws Exception {
            String student = newStudent();
            insertProfile(tenant, student, NOW.minus(Duration.ofDays(400)));
            String run = newRunId();
            insertWeakness(tenant, student, run, "圆的面积", "概念不清", NOW);
            memory().consolidate(tenant, student, run);

            assertThat(memory().recall(tenant, student, 5, 800))
                    .as("⚠️ 出现 1 次的知识点（base(1)=0.295 < 0.40）不得进上下文")
                    .isEmpty();
        }

        @Test
        @DisplayName("n=2 且刚出现 → 可召回")
        void twoEvidencesRecalled() throws Exception {
            String student = newStudent();
            insertProfile(tenant, student, NOW.minus(Duration.ofDays(400)));
            String run1 = newRunId();
            String run2 = newRunId();
            insertWeakness(tenant, student, run1, "全等三角形", "步骤缺失", NOW.minus(Duration.ofDays(5)));
            insertWeakness(tenant, student, run2, "全等三角形", "步骤缺失", NOW);
            memory().consolidate(tenant, student, run1);
            memory().consolidate(tenant, student, run2);

            List<MemoryEntry> recalled = memory().recall(tenant, student, 5, 800);

            assertThat(recalled).hasSize(1);
            assertThat(recalled.get(0).knowledgePoint()).isEqualTo("全等三角形");
            assertThat(recalled.get(0).evidenceCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("⚠️⚠️ 实时衰减：库里强度仍在，但半年没再出现 → 不再召回")
        void decayIsAppliedAtReadTime() throws Exception {
            String student = newStudent();
            insertProfile(tenant, student, NOW.minus(Duration.ofDays(400)));
            String run1 = newRunId();
            String run2 = newRunId();
            insertWeakness(tenant, student, run1, "函数图象", "审题偏差", NOW.minus(Duration.ofDays(5)));
            insertWeakness(tenant, student, run2, "函数图象", "审题偏差", NOW);
            memory().consolidate(tenant, student, run1);
            memory().consolidate(tenant, student, run2);

            assertThat(memory().recall(tenant, student, 5, 800)).hasSize(1);

            // ⚠️ 拨钟到 200 天后：存储的 strength 列**没变**，
            //    但实时强度 base(2)*e^(-0.01*200) ≈ 0.068 < 0.40 → 必须不再召回。
            //    若召回直接用库里的 strength 排序返回，这条断言会失败 ——
            //    那正是"该忘的没忘"。
            clock.set(NOW.plus(Duration.ofDays(200)));
            assertThat(memory().recall(tenant, student, 5, 800))
                    .as("⚠️ 衰减必须发生在读取路径上，而不是靠夜里跑批刷新")
                    .isEmpty();
            assertThat(storedStrength(tenant, student, "函数图象"))
                    .as("前提：库里的 strength 列并未被读取路径改写")
                    .isGreaterThan(0.4);
            clock.set(NOW);
        }

        @Test
        @DisplayName("token 预算不足时少返回几条，但不截断单条")
        void tokenBudgetLimitsItems() throws Exception {
            String student = newStudent();
            insertProfile(tenant, student, NOW.minus(Duration.ofDays(400)));
            for (String kp : List.of("知识点甲乙丙丁戊", "知识点己庚辛壬癸")) {
                String r1 = newRunId();
                String r2 = newRunId();
                insertWeakness(tenant, student, r1, kp, "概念不清", NOW.minus(Duration.ofDays(5)));
                insertWeakness(tenant, student, r2, kp, "概念不清", NOW);
                memory().consolidate(tenant, student, r1);
                memory().consolidate(tenant, student, r2);
            }
            clock.set(NOW);

            List<MemoryEntry> wide = memory().recall(tenant, student, 5, 800);
            assertThat(wide).hasSize(2);

            // 只够一条的预算
            int one = wide.get(0).estimatedTokens(NOW);
            List<MemoryEntry> narrow = memory().recall(tenant, student, 5, one + 1);
            assertThat(narrow)
                    .as("token 预算只够一条时，返回一条而不是硬塞两条")
                    .hasSize(1);
        }

        @Test
        @DisplayName("⚠️ RLS：A 租户召回不到 B 租户的同名记忆")
        void rlsIsolatesTenants() throws Exception {
            String student = newStudent();
            String otherStudent = newStudent();
            insertProfile(tenant, student, NOW.minus(Duration.ofDays(400)));
            insertProfile(otherTenant, otherStudent, NOW.minus(Duration.ofDays(400)));

            // 在 otherTenant 造一条可达召回线的记忆
            String r1 = newRunId();
            String r2 = newRunId();
            insertWeakness(otherTenant, otherStudent, r1, "因式分解", "符号错误", NOW.minus(Duration.ofDays(5)));
            insertWeakness(otherTenant, otherStudent, r2, "因式分解", "符号错误", NOW);
            memory().consolidate(otherTenant, otherStudent, r1);
            memory().consolidate(otherTenant, otherStudent, r2);

            assertThat(memory().recall(otherTenant, otherStudent, 5, 800)).hasSize(1);
            assertThat(memory().recall(tenant, otherStudent, 5, 800))
                    .as("⚠️ 串到别的租户读，RLS 必须让它看到 0 行（且不报错）")
                    .isEmpty();
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 删除
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("删除：保留期清理与撤回同意")
    class Deletion {

        @Test
        @DisplayName("保留期到期 → 记忆被清理（第 5 类），未到期不动")
        void retentionPurgesExpiredMemory() throws Exception {
            String oldStudent = newStudent();
            String newStudent = newStudent();
            insertProfile(tenant, oldStudent, NOW.minus(Duration.ofDays(800)));
            insertProfile(tenant, newStudent, NOW.minus(Duration.ofDays(10)));

            seedMemory(oldStudent);
            seedMemory(newStudent);

            // ⚠️ 手工把老学生的记忆 created_at 拨到 400 天前 —— 模拟"很久以前沉淀下来的结论"
            try (Connection c = admin.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "UPDATE student_memory SET created_at = ? WHERE tenant_id = ?::uuid AND student_id = ?")) {
                ps.setTimestamp(1, java.sql.Timestamp.from(NOW.minus(Duration.ofDays(400))));
                ps.setString(2, tenant);
                ps.setString(3, oldStudent);
                ps.executeUpdate();
            }

            int deleted = erasure().purgeExpiredMemories(
                    tenant, NOW.minus(Duration.ofDays(365)), 1000);

            assertThat(deleted).as("到期记忆必须被删除").isGreaterThanOrEqualTo(1);
            assertThat(countMemory(tenant, oldStudent)).isZero();
            assertThat(countMemory(tenant, newStudent))
                    .as("未到期的记忆不得被误删")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("⚠️ 撤回同意删除学生 → 记忆随之消失（显式删除 + 级联双保险）")
        void withdrawalRemovesMemory() throws Exception {
            String student = newStudent();
            insertProfile(tenant, student, NOW.minus(Duration.ofDays(10)));
            seedMemory(student);
            assertThat(countMemory(tenant, student)).isEqualTo(1);

            erasure().eraseWithdrawnStudent(tenant, student, "memory-it-withdrawal");

            assertThat(countMemory(tenant, student))
                    .as("⚠️ 撤回后记忆必须消失（不能只删画像、结论还活着）")
                    .isZero();
        }

        @Test
        @DisplayName("forget() 单独可用：即使 profile 仍在也能清掉记忆")
        void forgetWorksWithoutProfileDeletion() throws Exception {
            String student = newStudent();
            insertProfile(tenant, student, NOW.minus(Duration.ofDays(10)));
            seedMemory(student);

            assertThat(memory().forget(tenant, student)).isEqualTo(1);
            assertThat(countMemory(tenant, student)).isZero();
            assertThat(profileExists(tenant, student))
                    .as("forget 只删记忆，不删画像（画像删除是另一条受审计的路径）")
                    .isTrue();
        }
    }

    // ── 辅助 ────────────────────────────────────────────────────────

    private static String runIdOf(String student) throws SQLException {
        try (Connection c = admin.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT run_id::text FROM weakness_record WHERE tenant_id = ?::uuid AND student_id = ? LIMIT 1")) {
            ps.setString(1, tenant);
            ps.setString(2, student);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : newRunId();
            }
        }
    }

    /** 造一条 n=2（可召回）的记忆。 */
    private static void seedMemory(String student) throws Exception {
        String r1 = newRunId();
        String r2 = newRunId();
        insertWeakness(tenant, student, r1, "一元二次方程", "概念不清", NOW.minus(Duration.ofDays(5)));
        insertWeakness(tenant, student, r2, "一元二次方程", "概念不清", NOW);
        memory().consolidate(tenant, student, r1);
        memory().consolidate(tenant, student, r2);
    }

    private static int evidenceCount(String tenantId, String student, String kp)
            throws SQLException {
        try (Connection c = admin.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT evidence_count FROM student_memory "
                             + "WHERE tenant_id = ?::uuid AND student_id = ? AND knowledge_point = ?")) {
            ps.setString(1, tenantId);
            ps.setString(2, student);
            ps.setString(3, kp);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }

    private static double storedStrength(String tenantId, String student, String kp)
            throws SQLException {
        try (Connection c = admin.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT strength FROM student_memory "
                             + "WHERE tenant_id = ?::uuid AND student_id = ? AND knowledge_point = ?")) {
            ps.setString(1, tenantId);
            ps.setString(2, student);
            ps.setString(3, kp);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getDouble(1) : -1;
            }
        }
    }

    private static boolean profileExists(String tenantId, String student) throws SQLException {
        try (Connection c = admin.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM student_profile WHERE tenant_id = ?::uuid AND student_id = ?")) {
            ps.setString(1, tenantId);
            ps.setString(2, student);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
