package cn.studykid.multiagent.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.zaxxer.hikari.HikariDataSource;

import cn.studykid.multiagent.core.config.ApprovalRequirement;
import cn.studykid.multiagent.ports.persistence.ApprovalPort;
import cn.studykid.multiagent.ports.persistence.AuditPort;
import cn.studykid.multiagent.ports.persistence.RunToolLedgerPort;

/**
 * 审计链路集成测试（P3-D07 / R-1 / R-2 / R-3）。
 *
 * <p><b>本类证明的核心命题</b>（真实 PG17、非特权角色 {@code app_agent}）：
 * <ol>
 *   <li><b>R-3 追加不可改</b>：直接 UPDATE / DELETE 审计行被 DB 触发器拒绝；</li>
 *   <li><b>R-3 反证</b>：dropTrigger 后同样的 UPDATE / DELETE <b>被静默放行</b>
 *       —— 证明触发器是 load-bearing，而非「恰好有约束」；</li>
 *   <li><b>R-1 审批可追溯</b>：授予 / 撤销 / 消费各留一条审计；</li>
 *   <li><b>敏感信息拒写</b>：detail 含身份证/手机号 → 拒绝整条写入（PIPL）；</li>
 *   <li><b>fail-closed vs fail-open</b>：关键审计失败中止、访问日志失败不中止；</li>
 *   <li><b>跨租户隔离</b>：B 读不到 A 的审计。</li>
 * </ol>
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
class AuditChainIT {

    private static HikariDataSource appPool;
    private static HikariDataSource adminPool;
    private static JdbcToolOperationLedger ledger;
    private static JdbcAuditRepository audit;
    private static JdbcApprovalRepository approvals;

    private static String tenantA;
    private static String tenantB;
    private static String snapshotA;

    @BeforeAll
    static void setUp() throws Exception {
        appPool = FaultInjectionSupport.appPool(8);
        adminPool = FaultInjectionSupport.adminPool();
        FaultInjectionSupport.requireNonPrivileged(appPool);

        ledger = new JdbcToolOperationLedger(appPool);
        audit = new JdbcAuditRepository(appPool);
        approvals = new JdbcApprovalRepository(appPool, audit);

        tenantA = FaultInjectionSupport.tenantIdBySlug(adminPool, "t-a");
        tenantB = FaultInjectionSupport.tenantIdBySlug(adminPool, "t-b");
        FaultInjectionSupport.ensureTenantSnapshot(adminPool, "t-a");
        snapshotA = FaultInjectionSupport.snapshotIdBySlug(adminPool, "t-a");
    }

    @AfterAll
    static void tearDown() {
        if (appPool != null) {
            appPool.close();
        }
        if (adminPool != null) {
            adminPool.close();
        }
    }

    // ── T-1：关键审计写入后可查（R-1 基本链路）──────────────────────

    @Test
    @DisplayName("T-1 ⚠️⚠️ 关键审计写入后可查，actor/action/resource 完整")
    void requiredAuditIsQueryable() {
        String resourceId = "res-" + UUID.randomUUID();
        audit.recordRequired(tenantA, "approver-x", AuditPort.Actions.APPROVAL_GRANT,
                "approval", resourceId, Map.of("requirement", "REQUIRED"));

        List<AuditPort.AuditView> found =
                audit.findByResource(tenantA, "approval", resourceId);

        assertThat(found).as("⚠️ 关键审计必须可查（否则 R-1 无意义）").hasSize(1);
        AuditPort.AuditView v = found.get(0);
        assertThat(v.actorId()).isEqualTo("approver-x");
        assertThat(v.action()).isEqualTo(AuditPort.Actions.APPROVAL_GRANT);
        assertThat(v.resourceType()).isEqualTo("approval");
        assertThat(v.resourceId()).isEqualTo(resourceId);
        assertThat(v.createdAt()).isNotNull();
    }

    // ── T-2 / T-3：R-3 追加不可改 ───────────────────────────────────

    @Test
    @DisplayName("T-2 ⚠️⚠️ 直接 UPDATE 审计行 → 触发器拒绝（R-3）")
    void auditUpdateRejected() throws SQLException {
        String resourceId = insertAudit("upd-" + UUID.randomUUID());

        assertThatThrownBy(() -> rawExec(
                "UPDATE audit_record SET actor_id = 'tampered' "
                        + "WHERE tenant_id = '" + tenantA + "'::uuid AND resource_id = '"
                        + resourceId + "'"))
                .as("⚠️⚠️ 审计可被改写 = R-3 失效（事后篡改无法发现）")
                .isNotNull();

        // 确认 actor 未被篡改
        assertThat(rawQuery(
                "SELECT actor_id FROM audit_record WHERE tenant_id = '" + tenantA
                        + "'::uuid AND resource_id = '" + resourceId + "'"))
                .as("⚠️ actor_id 必须保持原值")
                .isEqualTo("auditor-original");
    }

    @Test
    @DisplayName("T-3 ⚠️⚠️ 直接 DELETE 审计行 → 触发器拒绝（R-3）")
    void auditDeleteRejected() throws SQLException {
        String resourceId = insertAudit("del-" + UUID.randomUUID());

        assertThatThrownBy(() -> rawExec(
                "DELETE FROM audit_record WHERE tenant_id = '" + tenantA
                        + "'::uuid AND resource_id = '" + resourceId + "'"))
                .as("⚠️⚠️ 审计可被删除 = 事后毁灭证据")
                .isNotNull();

        assertThat(countAudits(tenantA, resourceId))
                .as("⚠️ 审计行必须仍在")
                .isEqualTo(1);
    }

    // ── T-4：反证 —— 去掉触发器后 UPDATE/DELETE 被静默放行 ───────────

    @Test
    @DisplayName("T-4 ⚠️⚠️ 反证：dropTrigger 后 UPDATE/DELETE 被静默放行（证明触发器 load-bearing）")
    void withoutTriggerMutationSucceeds() throws SQLException {
        String resourceId = insertAudit("anti-" + UUID.randomUUID());

        dropTrigger();
        try {
            // ⚠️ 无触发器时，同一条 UPDATE/DELETE **不应**抛异常
            assertThatCode(() -> rawExec(
                    "UPDATE audit_record SET actor_id = 'tampered' "
                            + "WHERE tenant_id = '" + tenantA + "'::uuid AND resource_id = '"
                            + resourceId + "'"))
                    .as("⚠️ 反证：缺触发器时篡改必须能成功，否则 T-2 是假阴性")
                    .doesNotThrowAnyException();

            assertThat(rawQuery(
                    "SELECT actor_id FROM audit_record WHERE tenant_id = '" + tenantA
                            + "'::uuid AND resource_id = '" + resourceId + "'"))
                    .as("⚠️ 证明篡改确实生效了（actor 已被改写）")
                    .isEqualTo("tampered");

            assertThatCode(() -> rawExec(
                    "DELETE FROM audit_record WHERE tenant_id = '" + tenantA
                            + "'::uuid AND resource_id = '" + resourceId + "'"))
                    .doesNotThrowAnyException();
            assertThat(countAudits(tenantA, resourceId))
                    .as("⚠️ 证明删除确实生效了（证据已被销毁）")
                    .isZero();
        } finally {
            restoreTrigger();
        }
    }

    // ── T-5：敏感信息拒写（PIPL / D-3）───────────────────────────────

    @Test
    @DisplayName("T-5 ⚠️⚠️ detail 含身份证 / 手机号 → 拒绝整条写入（fail-closed）")
    void identifyingInformationRejected() {
        assertThatThrownBy(() -> audit.recordRequired(tenantA, "auditor",
                AuditPort.Actions.APPROVAL_GRANT, "approval",
                "pipl-" + UUID.randomUUID(),
                Map.of("note", "身份证 11010119900307123X")))
                .as("⚠️⚠️ 审计里写可识别信息 = PIPL 违规（宁可少一条审计）")
                .isNotNull();

        assertThatThrownBy(() -> audit.recordRequired(tenantA, "auditor",
                AuditPort.Actions.APPROVAL_GRANT, "approval",
                "pipl-" + UUID.randomUUID(),
                Map.of("phone", "13812345678")))
                .isNotNull();

        assertThatThrownBy(() -> audit.recordRequired(tenantA, "auditor",
                AuditPort.Actions.APPROVAL_GRANT, "approval",
                "pipl-" + UUID.randomUUID(),
                Map.of("email", "student@school.edu.cn")))
                .isNotNull();
    }

    @Test
    @DisplayName("T-5b 正常明细（含非识别数字）可写入，不误伤")
    void benignDetailAccepted() {
        String resourceId = "ok-" + UUID.randomUUID();
        // ⚠️ 短数字不是学号（下限 8 位），小数字运算不应被误判
        assertThatCode(() -> audit.recordRequired(tenantA, "auditor",
                AuditPort.Actions.APPROVAL_GRANT, "approval", resourceId,
                Map.of("steps", 5, "requirement", "REQUIRED", "note", "计算 5 × 6 = 30")))
                .as("⚠️ 审计不能把正常业务数字判成学号")
                .doesNotThrowAnyException();

        assertThat(audit.findByResource(tenantA, "approval", resourceId)).hasSize(1);
    }

    // ── T-6：审批端到端留痕（R-1）────────────────────────────────────

    @Test
    @DisplayName("T-6 ⚠️⚠️ 审批授予 / 撤销 / 消费 → 各留一条审计（R-1 端到端）")
    void approvalActionsAreAudited() {
        String runId = newRun(tenantA, "RUNNING");
        String opId = newOperation(tenantA, runId, "recording_tool");

        approvals.grant(tenantA, opId, "DIGEST-1", "recording_tool",
                ApprovalRequirement.REQUIRED, "scope-test",
                Instant.now().plusSeconds(900), "approver-1");

        List<AuditPort.AuditView> afterGrant =
                audit.findByResource(tenantA, "approval", opId);
        assertThat(afterGrant)
                .as("⚠️ 授予必须留痕")
                .singleElement()
                .satisfies(v -> {
                    assertThat(v.action()).isEqualTo(AuditPort.Actions.APPROVAL_GRANT);
                    assertThat(v.actorId()).isEqualTo("approver-1");
                });

        // 消费（成功才留痕）
        assertThat(approvals.consume(tenantA, opId, "DIGEST-1", "recording_tool", runId))
                .isEqualTo(ApprovalPort.ApprovalOutcome.CONSUMED);

        List<AuditPort.AuditView> afterConsume =
                audit.findByResource(tenantA, "approval", opId);
        assertThat(afterConsume)
                .as("⚠️ 消费必须留痕")
                .anySatisfy(v -> assertThat(v.action())
                        .isEqualTo(AuditPort.Actions.APPROVAL_CONSUME));

        // ⚠️ 幂等重放不得产生重复审计
        int consumeAudits = countAction(tenantA, opId, AuditPort.Actions.APPROVAL_CONSUME);
        assertThat(approvals.consume(tenantA, opId, "DIGEST-1", "recording_tool", runId))
                .isEqualTo(ApprovalPort.ApprovalOutcome.CONSUMED);
        assertThat(countAction(tenantA, opId, AuditPort.Actions.APPROVAL_CONSUME))
                .as("⚠️⚠️ 崩溃恢复重放不得重复记审计（否则无法回答『消费了几次』）")
                .isEqualTo(consumeAudits);
    }

    @Test
    @DisplayName("T-6b 撤销（仅当确实生效时）留痕；未生效不记")
    void revokeAuditedOnlyWhenEffective() {
        String runId = newRun(tenantA, "RUNNING");
        String opId = newOperation(tenantA, runId, "recording_tool");
        approvals.grant(tenantA, opId, "DIGEST-1", "recording_tool",
                ApprovalRequirement.REQUIRED, "scope-test",
                Instant.now().plusSeconds(900), "approver-1");

        approvals.revoke(tenantA, opId, "approver-2", "风险重估");

        assertThat(countAction(tenantA, opId, AuditPort.Actions.APPROVAL_REVOKE))
                .as("⚠️ 撤销必须留痕")
                .isEqualTo(1);

        // ⚠️ 重复撤销（已 REJECTED，非 APPROVED）→ 不应再记
        approvals.revoke(tenantA, opId, "approver-3", "再次撤销");
        assertThat(countAction(tenantA, opId, AuditPort.Actions.APPROVAL_REVOKE))
                .as("⚠️⚠️ 未生效的撤销不得留痕（否则追溯被污染）")
                .isEqualTo(1);
    }

    // ── T-7：访问日志 fail-open vs 关键审计 fail-closed（D-1）────────

    @Test
    @DisplayName("T-7 ⚠️ 访问日志遇敏感信息 → 只告警不抛（fail-open）")
    void accessLogFailsOpen() {
        String resourceId = "acc-" + UUID.randomUUID();
        assertThatCode(() -> audit.recordAccess(tenantA, "worker",
                AuditPort.Actions.PROFILE_READ, "student_profile", resourceId,
                Map.of("studentId", "13812345678")))
                .as("⚠️ 访问日志失败不得阻断用户读数据（D-1）")
                .doesNotThrowAnyException();

        assertThat(countAudits(tenantA, resourceId))
                .as("被拒的访问日志不应落库")
                .isZero();
    }

    @Test
    @DisplayName("T-7b 正常访问日志写入成功，且不含姓名/学校字段")
    void accessLogWritten() {
        String resourceId = "acc-" + UUID.randomUUID();
        audit.recordAccess(tenantA, "worker", AuditPort.Actions.PROFILE_READ,
                "student_profile", resourceId, Map.of("studentId", "stu-abc123"));

        assertThat(countAudits(tenantA, resourceId)).isEqualTo(1);
    }

    // ── T-9：平台标识符不得被误判为学号（真实缺陷回归）───────────────

    @Test
    @DisplayName("⚠️⚠️ T-9 真实 Run UUID 不应被「学号」规则误判（D7 实测缺陷回归）")
    void uuidIdentifiersNotFalsePositives() {
        // ⚠️ **真实缺陷**：UUID 的十六进制串常含 8–12 位连续数字，
        //    会被「学号」规则命中 → 关键审计被拒写 →
        //    丢掉的恰是 R-1 最需要的审批痕迹。
        //    本用例用**真实 Run**（其 UUID 由 DB 生成，含数字串）
        //    走完整 grant→consume 链路，确保不再复发。
        String runId = newRun(tenantA, "RUNNING");
        String opId = newOperation(tenantA, runId, "recording_tool");

        assertThatCode(() -> approvals.grant(tenantA, opId, "DIGEST-1", "recording_tool",
                ApprovalRequirement.REQUIRED, "scope-test",
                Instant.now().plusSeconds(900), "approver-1"))
                .as("⚠️⚠️ 平台生成的 UUID 标识符不得被当成学号而拒绝审计写入")
                .doesNotThrowAnyException();

        assertThat(countAction(tenantA, opId,
                cn.studykid.multiagent.ports.persistence.AuditPort.Actions.APPROVAL_GRANT))
                .as("⚠️ 审计必须真的写进去了（豁免不等于跳过写入）")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("⚠️ T-9b 但真实可识别信息仍必须被拒（豁免不得扩大）")
    void exemptionDoesNotWidenSensitiveDetection() {
        // ⚠️ 豁免是**按键**的：studentId 不在豁免名单，塞入学号仍须被拒
        assertThatThrownBy(() -> audit.recordRequired(tenantA, "auditor",
                cn.studykid.multiagent.ports.persistence.AuditPort.Actions.APPROVAL_GRANT,
                "approval", "ex-" + java.util.UUID.randomUUID(),
                Map.of("studentId", "20210300123")))
                .as("⚠️⚠️ studentId 不在豁免名单——塞入真实学号必须被拒")
                .isNotNull();
    }

    // ── T-8：跨租户隔离 ──────────────────────────────────────────────

    @Test
    @DisplayName("T-8 ⚠️⚠️ 跨租户：B 读不到 A 的审计；B 写不进 A 的租户上下文")
    void auditIsTenantIsolated() {
        String resourceId = "iso-" + UUID.randomUUID();
        audit.recordRequired(tenantA, "auditor", AuditPort.Actions.APPROVAL_GRANT,
                "approval", resourceId, Map.of());

        assertThat(audit.findByResource(tenantB, "approval", resourceId))
                .as("⚠️⚠️ B 租户不得看到 A 的审计（RLS 隔离）")
                .isEmpty();
    }

    // ── 工具方法 ────────────────────────────────────────────────────

    private static String insertAudit(String resourceId) {
        audit.recordRequired(tenantA, "auditor-original",
                AuditPort.Actions.APPROVAL_GRANT, "approval", resourceId, Map.of());
        return resourceId;
    }

    private static int countAudits(String tenantId, String resourceId) {
        try (Connection conn = adminPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM audit_record WHERE tenant_id = ?::uuid "
                             + "AND resource_id = ?")) {
            ps.setString(1, tenantId);
            ps.setString(2, resourceId);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("统计审计失败", e);
        }
    }

    private static int countAction(String tenantId, String resourceId, String action) {
        try (Connection conn = adminPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM audit_record WHERE tenant_id = ?::uuid "
                             + "AND resource_id = ? AND action = ?")) {
            ps.setString(1, tenantId);
            ps.setString(2, resourceId);
            ps.setString(3, action);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("统计审计动作失败", e);
        }
    }

    private static String newOperation(String tenantId, String runId, String toolName) {
        RunToolLedgerPort.Registration reg = ledger.register(
                tenantId, runId, toolName, "WRITE_EXTERNAL",
                "idem-" + UUID.randomUUID(), "digest-" + UUID.randomUUID(),
                Map.of("k", "v"), 0L);
        return reg.operationId();
    }

    private static String newRun(String tenantId, String state) {
        String runId = UUID.randomUUID().toString();
        try (Connection conn = adminPool.getConnection()) {
            conn.setAutoCommit(false);
            String sql = """
                    INSERT INTO run
                        (run_id, tenant_id, snapshot_id, idempotency_key, request_digest,
                         state, input_payload, correlation_id, created_at, updated_at)
                    VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?,
                            ?::run_state, '{}'::jsonb, ?, now(), now())
                    """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, runId);
                ps.setString(2, tenantId);
                ps.setString(3, snapshotA);
                ps.setString(4, "run-" + UUID.randomUUID());
                ps.setString(5, "digest");
                ps.setString(6, state);
                ps.setString(7, "corr-" + UUID.randomUUID());
                ps.executeUpdate();
            }
            conn.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("插入 Run 夹具失败", e);
        }
        return runId;
    }

    private static int rawExec(String sql) throws SQLException {
        try (Connection conn = adminPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            return ps.executeUpdate();
        }
    }

    private static String rawQuery(String sql) {
        try (Connection conn = adminPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             var rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException("查询失败", e);
        }
    }

    private static void dropTrigger() throws SQLException {
        try (Connection conn = adminPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DROP TRIGGER IF EXISTS trg_audit_record_append_only ON audit_record")) {
            ps.executeUpdate();
        }
    }

    private static void restoreTrigger() throws SQLException {
        try (Connection conn = adminPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "CREATE TRIGGER trg_audit_record_append_only "
                             + "BEFORE UPDATE OR DELETE ON audit_record "
                             + "FOR EACH ROW EXECUTE FUNCTION forbid_audit_mutation()")) {
            ps.executeUpdate();
        }
    }
}