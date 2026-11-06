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
 * <p><b>本类证明的核心命题</b>（真实 PG、<b>双凭据</b>：应用 {@code app_agent} + 清理 {@code agent_retention}）：
 * <ol>
 *   <li><b>四类数据按承诺被清理</b>——到期即删，未到期不动；</li>
 *   <li><b>Prompt 清理不删行</b>（I-4）：{@code run} 行保留、
 *       {@code weakness_record} <b>不被级联删除</b>；</li>
 *   <li><b>审计受控通道</b>（I-1/I-2）：UPDATE 永远被拒；
 *       删除需<b>清理身份 + actor 声明</b>同时具备 ——
 *       应用凭据连 DELETE 权限都没有（凭据隔离），有清理身份但缺 actor 仍被触发器拒绝；</li>
 *   <li><b>租户隔离</b>（I-3）：A 的清理不影响 B；</li>
 *   <li><b>凭证幂等</b>（D-6）：同一窗口重复运行只产生一张凭证；</li>
 *   <li><b>撤回履约</b>（FR-019）：登记 → 到期 → 删除 → 留凭证。</li>
 * </ol>
 *
 * <p>⚠️ <b>为什么需要 {@code IT_RETENTION_PASSWORD}</b>：{@code 04_retention_role.sql}
 * 已把 {@code audit_record} / {@code erasure_certificate} 的 DELETE 从应用角色撤销，
 * 清理测试若仍用应用池，得到的是 {@code permission denied}（指向配置，与清理逻辑无关）。
 * 缺该变量时本类整体跳过 —— 但<b>跳过不等于「清理通道已验证」</b>。
 *
 * <p>⚠️ <b>每个用例自带夹具与清理</b>（F-8 纪律）：
 * 清理范围<b>严格等于</b>创建范围（按显式 ID 删），
 * 不得按租户批量删——那会误删开发账号数据，
 * 且污染的是<b>后来者</b>，失败现场会指向完全错误的方向。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "IT_RETENTION_PASSWORD", matches = ".+")
class RetentionErasureIT {

    private static final String SLUG_A = "t-a";
    private static final String SLUG_B = "t-b";

    private static HikariDataSource admin;
    /** 应用通道（{@code app_agent}）：验证「应用凭据删不了审计」。 */
    private static HikariDataSource app;
    /** 清理通道（{@code agent_retention}）：清理逻辑唯一合法的执行凭据。 */
    private static HikariDataSource retention;

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
        retention = FaultInjectionSupport.retentionPool(4);
        FaultInjectionSupport.requireNonPrivileged(app);
        // ⚠️ 清理通道必须**是**清理通道：若它实际连成了应用角色或超级用户，
        //    「凭据隔离」这一层就没人验证（与 requireNonPrivileged 同一类守护）。
        requireRetentionIdentity(retention);
        requireAppCannotDeleteAudit(app);

        tenantA = FaultInjectionSupport.tenantIdBySlug(admin, SLUG_A);
        tenantB = FaultInjectionSupport.tenantIdBySlug(admin, SLUG_B);
        // ⚠️ 夹具必须由本类自己准备（2026-10-22 修复）：
        //    V1_seed.sql 只为 t-a 建了 agent_version/execution_snapshot，
        //    本类却直接向 t-b 取快照。旧实现依赖「别的 IT 跑过并留下数据」——
        //    而那些 IT 按纪律会在 @AfterAll 自清理，于是本类在干净库上必然失败，
        //    在脏库上则靠执行顺序侥幸通过（假绿）。
        //    ensureTenantSnapshot 幂等（ON CONFLICT DO NOTHING），故先无条件补齐两个租户。
        FaultInjectionSupport.ensureTenantSnapshot(admin, SLUG_A);
        FaultInjectionSupport.ensureTenantSnapshot(admin, SLUG_B);
        // ⚠️ run.snapshot_id 是 (tenant_id, snapshot_id) 复合外键：
        //    给 B 租户的 run 复用 A 的 snapshot 会直接违反 run_snapshot_fk，
        //    且报错指向「外键」而非真正的错因「夹具拿错了租户」。
        snapshotA = FaultInjectionSupport.snapshotIdBySlug(admin, SLUG_A);
        snapshotB = FaultInjectionSupport.snapshotIdBySlug(admin, SLUG_B);

        // ⚠️ 这里**不清理**删除凭证：凭证表由 forbid_certificate_mutation() 无条件保护（L-4）。
        //    由此带来的纪律是：凭证相关断言只能写「本次是否新增」，不能写「表里有没有」。
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
        }

        // ⚠️ 审计与删除凭证只能走**清理凭据**（2026-11-02 起应用角色已无 DELETE）。
        //    并且必须在**同一个事务内**先声明 actor 再删：
        //    set_config(..., true) 是事务局部设置，autocommit 下执行完即失效，
        //    紧接着的 DELETE 看不到 actor，会被追加不可改触发器拒绝
        //    （这正是本类改造前 tearDown 报错的原因 —— 报错点在清理，真因在事务边界）。
        try (Connection c = retention.getConnection()) {
            c.setAutoCommit(false);
            // ⚠️ 三个表（audit_record / consent_withdrawal / erasure_certificate）都是 FORCE RLS：
            //    不绑定租户时 DELETE 影响 0 行**且不报错** —— 清理静默失效。
            FaultInjectionSupport.bind(c, tenantA);
            try (PreparedStatement decl = c.prepareStatement(
                    "SELECT set_config('agent.retention.actor', ?, true)")) {
                decl.setString(1, "retention-it-cleanup");
                try (ResultSet rs = decl.executeQuery()) {
                    rs.next();
                }
            }
            for (String id : createdAudits) {
                exec(c, "DELETE FROM audit_record WHERE audit_id = ?::uuid", id);
            }
            for (String id : createdStudents) {
                exec(c, "DELETE FROM consent_withdrawal WHERE student_id = ?", id);
            }
            c.commit();
        }
        // ⚠️ 凭证（erasure_certificate）**不清理**：它由 forbid_certificate_mutation()
        //    无条件保护（连清理凭据也删不掉），这是 L-4「证据不可销毁」的本意。
        //    由此有条纪律：凭证相关断言只能写「本次是否新增」，不能写「表里有没有」。
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

            // ⚠️ 这条用例现在断言**两道**防线，而不是一道（2026-11-02 起）。
            //    加固前的实现：任何拿到应用凭据的会话都能
            //        SELECT set_config('agent.retention.actor','anyone',true); DELETE ...
            //    于是触发器那只 GUC 就是「审计不可篡改」的全部强度 ——
            //    它等价于「拿到应用凭据」，而那条凭据本来就到处都有。
            //
            //    第 ①道：应用凭据在**结构上**删不了审计行（权限即被撤销，
            //            根本走不到触发器）。这是更强的保证，必须先断它；
            //    第 ②道：即便拿到清理凭据，缺 actor 声明仍被触发器拒绝
            //            （此时 actor 只用于留痕，不再是放行依据）。
            //
            //    ⚠️ 只断 ②、不断 ①，会让「凭据已隔离」这件事无人验证 ——
            //       一旦有人把 DELETE 授回 app_agent，测试依然全绿。
            assertThatThrownBy(() -> deleteAuditDirectly(tenantA, auditId, false))
                    .as("应用凭据（app_agent）竟然能 DELETE 审计行 —— 凭据隔离已失效")
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");

            assertThatThrownBy(() -> deleteAuditDirectlyAsRetention(tenantA, auditId, false))
                    .as("⚠️ 有清理身份但未声明操作主体：必须被触发器拒绝（I-2）")
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
            // ⚠️ 必须**只断言本窗口**：凭证表由 forbid_certificate_mutation()
            //    无条件保护（连清理凭据也删不掉），同租户历史上每一次 IT 运行
            //    留下的凭证都会永久累积。写成「总数为 1」的断言，
            //    在干净库上绿、在跑过一次的库上红 —— 而红色指向的
            //    「产生了两张凭证」是假的，真因是「三天前也有一张」。
            String windowKey = java.time.LocalDate.now(
                            java.time.ZoneId.of("UTC"))
                    .format(java.time.format.DateTimeFormatter.ISO_DATE);
            List<ErasureCertificate> thisWindow = certs.stream()
                    .filter(c -> windowKey.equals(c.windowKey()))
                    .toList();
            assertThat(thisWindow)
                    .as("⚠️ 同一窗口重复运行只能有一张凭证——"
                            + "重复凭证会被误读为「删了两次」；窗口=%s", windowKey)
                    .hasSize(1);
            assertThat(thisWindow.get(0).detail())
                    .as("凭证明细应记录实际删除行数")
                    .containsKey("student_profile");
        }

        @Test
        @DisplayName("R-6b 无变更时不写凭证（避免空凭证淹没举证材料）")
        void noCertificateWhenNothingHappened() throws Exception {
            // ⚠️ 这里刻意跑**两次**，而不是「跑一次然后断言表里没有凭证」。
            //    两个原因，都是踩过的坑：
            //    ① 凭证表由 forbid_certificate_mutation() 无条件保护，连清理凭据也删不掉，
            //       同轮其它用例（R-6a 等）产生的凭证会永久留在库里 ——
            //       「表为空」既依赖执行顺序，又永远无法通过；
            //    ② 首次运行可能确实有到期数据（同轮用例留下的），会合法地产生一张凭证。
            //       第二次运行时库里已经没有到期数据，于是「什么都没发生」这个前提
            //       由**本次运行自己**保证，不依赖用例顺序。
            RetentionPurgeService service = purgeService();
            service.runOnce(tenantA);

            List<ErasureCertificate> before = erasure().findCertificates(
                    tenantA, ErasureCertificate.REASON_RETENTION_EXPIRED, 500);

            service.runOnce(tenantA);

            List<ErasureCertificate> after = erasure().findCertificates(
                    tenantA, ErasureCertificate.REASON_RETENTION_EXPIRED, 500);

            assertThat(after)
                    .as("无实际删除时不应新增凭证（空凭证会淹没真正的举证材料）")
                    .hasSameSizeAs(before);
            assertThat(after)
                    .as("除数量外，既有凭证内容也不得被改写（凭证不可变）")
                    .containsExactlyElementsOf(before);
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

    /**
     * 被测对象 —— <b>跑在清理凭据上</b>（与生产 {@code RetentionConfig} 一致）。
     *
     * <p>⚠️ 历史写法是 {@code new JdbcErasureRepository(app)}：在应用角色还有
     * DELETE 权的年代能过，凭据隔离落地后必然 {@code permission denied}。
     * 那时若为了让测试变绿而把 DELETE 授回应用角色，等于<b>用测试倒逼安全降级</b>。
     */
    private static ErasurePort erasure() {
        return new JdbcErasureRepository(retention);
    }

    /** 清理通道必须真的是清理通道（否则「凭据隔离」这层无人验证）。 */
    private static void requireRetentionIdentity(DataSource ds) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT current_user, pg_has_role(current_user, 'agent_retention', 'MEMBER')");
             ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).isTrue();
            String who = rs.getString(1);
            assertThat(rs.getBoolean(2))
                    .as("清理数据源当前角色 %s 不具备 agent_retention 成员身份："
                            + "清理会 permission denied，且报错点离真因（配错凭据）很远", who)
                    .isTrue();
        }
    }

    /** 应用通道必须删不了审计（凭据隔离的**正向**断言，防止哪一天被授回去）。 */
    private static void requireAppCannotDeleteAudit(DataSource ds) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT current_user, has_table_privilege(current_user, 'audit_record', 'DELETE')");
             ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).isTrue();
            String who = rs.getString(1);
            assertThat(rs.getBoolean(2))
                    .as("应用角色 %s 竟持有 audit_record 的 DELETE —— 凭据隔离未生效"
                            + "（04_retention_role.sql 未执行，或权限被改回）", who)
                    .isFalse();
        }
    }

    private static RetentionPurgeService purgeService() {
        return new RetentionPurgeService(
                erasure(),
                new RetentionPurgeService.RetentionSettings(12, 12, 90, 180, 30, 0, 1000),
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

    /**
     * 以<b>清理身份</b>（{@code agent_retention}）绕过 Repository 直接删除审计。
     *
     * <p>⚠️ 与 {@link #deleteAuditDirectly} 的区别是「用什么凭据」：
     * 前者用应用凭据（今天已无 DELETE 权限），后者用清理凭据（有 DELETE，但仍须 actor）。
     * 两条路径分别对应防线的两层，缺任一层都不能说明加固有效。
     *
     * @param declareActor 是否声明受控清理主体——缺它即被触发器拒绝（留痕判据）
     */
    private static void deleteAuditDirectlyAsRetention(String tenantId, String auditId, boolean declareActor)
            throws SQLException {
        try (Connection c = FaultInjectionSupport.rawRetentionConnection()) {
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
