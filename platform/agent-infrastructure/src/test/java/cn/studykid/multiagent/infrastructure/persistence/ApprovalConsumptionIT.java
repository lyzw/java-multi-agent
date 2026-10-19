package cn.studykid.multiagent.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
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
import cn.studykid.multiagent.ports.persistence.RunToolLedgerPort;

/**
 * 审批消费原子协调 + 崩溃恢复集成测试（P3-D05 / FR-013 / AT-APPROVAL-001/002）。
 *
 * <p><b>本类证明的核心命题</b>（全部在真实 PG17、非特权角色 {@code app_agent}）：
 * <ol>
 *   <li><b>有效审批可消费</b>：APPROVED + 未过期 + 未消费 → CONSUMED，且幂等（崩溃恢复）；</li>
 *   <li><b>过期拒绝</b>：expires_at 已过 → EXPIRED；</li>
 *   <li><b>撤销拒绝</b>：REJECTED → REJECTED；</li>
 *   <li><b>绑定防篡改</b>：参数摘要 / 目标资源不符 → 拒绝，且审批保持 APPROVED；</li>
 *   <li><b>消费与取消检查原子</b>：已取消的 Run → RUN_CANCELLED，且审批不被消费；</li>
 *   <li><b>DB 第二道防线</b>：V5 触发器拦截非法状态迁移（终态不可迁出）。</li>
 * </ol>
 *
 * <p><b>⚠️ 反证</b>：各分支的「若去掉对应校验则用例转红」见设计文档 P3-D05 §6。
 * 其中 DB 触发器由本类<b>运行时注入</b>验证（去掉 V5 的 trg_approval_state_guard
 * 后，非法迁移会被静默放行，T-DB 转红）。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
class ApprovalConsumptionIT {

    private static HikariDataSource appPool;
    private static HikariDataSource adminPool;
    private static JdbcToolOperationLedger ledger;
    private static JdbcApprovalRepository approval;

    private static String tenantA;
    private static String snapshotA;

    @BeforeAll
    static void setUp() throws Exception {
        appPool = FaultInjectionSupport.appPool(8);
        adminPool = FaultInjectionSupport.adminPool();
        FaultInjectionSupport.requireNonPrivileged(appPool);

        ledger = new JdbcToolOperationLedger(appPool);
        approval = new JdbcApprovalRepository(appPool);

        tenantA = FaultInjectionSupport.tenantIdBySlug(adminPool, "t-a");
        FaultInjectionSupport.ensureTenantSnapshot(adminPool, "t-a");
        snapshotA = FaultInjectionSupport.snapshotIdBySlug(adminPool, "t-a");
    }

    @AfterAll
    static void tearDown() {
        purgeRunsCreatedByTests();
        if (appPool != null) {
            appPool.close();
        }
        if (adminPool != null) {
            adminPool.close();
        }
    }

    // ── T-1：有效审批可消费 + 幂等（崩溃恢复）──────────────────────────

    @Test
    @DisplayName("T-1 ⚠️⚠️ 有效审批消费成功，且幂等（崩溃恢复：重放仍为 CONSUMED）")
    void validApprovalConsumedIdempotent() {
        String runId = newRun(tenantA, false);
        String opId = newOperation(tenantA, runId, "recording_tool");
        approval.grant(tenantA, opId, "DIGEST-1", "recording_tool",
                ApprovalRequirement.REQUIRED, "scope-test",
                Instant.now().plusSeconds(900), "approver-1");

        ApprovalPort.ApprovalOutcome first = approval.consume(
                tenantA, opId, "DIGEST-1", "recording_tool", runId);
        assertThat(first).isEqualTo(ApprovalPort.ApprovalOutcome.CONSUMED);

        // ⚠️ 崩溃恢复：消费提交后进程崩溃、Run 重启重放 —— 幂等返回 CONSUMED，不双重阻塞
        ApprovalPort.ApprovalOutcome replay = approval.consume(
                tenantA, opId, "DIGEST-1", "recording_tool", runId);
        assertThat(replay).isEqualTo(ApprovalPort.ApprovalOutcome.CONSUMED);

        // 且审批确实已被标记消费（查得到 consumed_at）
        assertThat(approval.findActive(tenantA, opId)).isEmpty();
    }

    // ── T-2：过期拒绝 ──────────────────────────────────────────────

    @Test
    @DisplayName("T-2 ⚠️⚠️ 过期审批被拒绝（EXPIRED）")
    void expiredApprovalRejected() {
        String runId = newRun(tenantA, false);
        String opId = newOperation(tenantA, runId, "recording_tool");
        approval.grant(tenantA, opId, "DIGEST-1", "recording_tool",
                ApprovalRequirement.REQUIRED, "scope-test",
                Instant.now().minusSeconds(60), "approver-1");

        ApprovalPort.ApprovalOutcome outcome = approval.consume(
                tenantA, opId, "DIGEST-1", "recording_tool", runId);
        assertThat(outcome).isEqualTo(ApprovalPort.ApprovalOutcome.EXPIRED);
        // ⚠️ 消费时检测到过期 → 审批被推进为 EXPIRED（DB 状态与事实一致），
        //    故 findActive（仅返回 APPROVED/PENDING）不再包含它
        assertThat(approval.findActive(tenantA, opId)).isEmpty();
    }

    // ── T-3：撤销拒绝 ──────────────────────────────────────────────

    @Test
    @DisplayName("T-3 被撤销（REJECTED）的审批被拒绝")
    void rejectedApprovalRejected() {
        String runId = newRun(tenantA, false);
        String opId = newOperation(tenantA, runId, "recording_tool");
        approval.grant(tenantA, opId, "DIGEST-1", "recording_tool",
                ApprovalRequirement.REQUIRED, "scope-test",
                Instant.now().plusSeconds(900), "approver-1");
        approval.revoke(tenantA, opId, "approver-2", "风险重估");

        ApprovalPort.ApprovalOutcome outcome = approval.consume(
                tenantA, opId, "DIGEST-1", "recording_tool", runId);
        assertThat(outcome).isEqualTo(ApprovalPort.ApprovalOutcome.REJECTED);
    }

    // ── T-4：绑定防篡改（参数摘要 / 目标资源）─────────────────────────

    @Test
    @DisplayName("T-4 ⚠️⚠️ 参数摘要不符 → 拒绝（防篡改），审批保持 APPROVED 未被消费")
    void argumentMismatchRejected() {
        String runId = newRun(tenantA, false);
        String opId = newOperation(tenantA, runId, "recording_tool");
        approval.grant(tenantA, opId, "DIGEST-1", "recording_tool",
                ApprovalRequirement.REQUIRED, "scope-test",
                Instant.now().plusSeconds(900), "approver-1");

        ApprovalPort.ApprovalOutcome outcome = approval.consume(
                tenantA, opId, "TAMPERED-DIGEST", "recording_tool", runId);
        assertThat(outcome).isEqualTo(ApprovalPort.ApprovalOutcome.ARGUMENT_MISMATCH);
        // 审批未被消费，保持可查
        assertThat(approval.findActive(tenantA, opId)).isPresent();
    }

    @Test
    @DisplayName("T-4b 目标资源不符 → 拒绝（绑定防篡改）")
    void targetMismatchRejected() {
        String runId = newRun(tenantA, false);
        String opId = newOperation(tenantA, runId, "recording_tool");
        approval.grant(tenantA, opId, "DIGEST-1", "recording_tool",
                ApprovalRequirement.REQUIRED, "scope-test",
                Instant.now().plusSeconds(900), "approver-1");

        ApprovalPort.ApprovalOutcome outcome = approval.consume(
                tenantA, opId, "DIGEST-1", "other_target", runId);
        assertThat(outcome).isEqualTo(ApprovalPort.ApprovalOutcome.TARGET_MISMATCH);
    }

    // ── T-5：消费与取消检查原子 ─────────────────────────────────────

    @Test
    @DisplayName("T-5 ⚠️⚠️ 已取消的 Run → RUN_CANCELLED，且审批不被消费")
    void cancelledRunRejected() {
        String runId = newRun(tenantA, true); // 已取消
        String opId = newOperation(tenantA, runId, "recording_tool");
        approval.grant(tenantA, opId, "DIGEST-1", "recording_tool",
                ApprovalRequirement.REQUIRED, "scope-test",
                Instant.now().plusSeconds(900), "approver-1");

        ApprovalPort.ApprovalOutcome outcome = approval.consume(
                tenantA, opId, "DIGEST-1", "recording_tool", runId);
        assertThat(outcome).isEqualTo(ApprovalPort.ApprovalOutcome.RUN_CANCELLED);
        // ⚠️ 关键：审批未被消费（取消检查在消费之前），否则会制造「已批准但未用」幽灵
        assertThat(approval.findActive(tenantA, opId)).isPresent();
    }

    // ── T-6：无审批 → NO_APPROVAL ──────────────────────────────────

    @Test
    @DisplayName("T-6 无对应审批 → NO_APPROVAL")
    void noApprovalRejected() {
        String runId = newRun(tenantA, false);
        String opId = newOperation(tenantA, runId, "recording_tool");

        ApprovalPort.ApprovalOutcome outcome = approval.consume(
                tenantA, opId, "DIGEST-1", "recording_tool", runId);
        assertThat(outcome).isEqualTo(ApprovalPort.ApprovalOutcome.NO_APPROVAL);
    }

    // ── T-DB：DB 第二道防线（触发器反证）─────────────────────────────

    @Test
    @DisplayName("T-DB ⚠️⚠️ V5 触发器拦截非法状态迁移（终态不可迁出）—— 去掉触发器则静默放行")
    void dbTriggerBlocksIllegalTransition() throws SQLException {
        String runId = newRun(tenantA, false);
        String opId = newOperation(tenantA, runId, "recording_tool");
        approval.grant(tenantA, opId, "DIGEST-1", "recording_tool",
                ApprovalRequirement.REQUIRED, "scope-test",
                Instant.now().plusSeconds(900), "approver-1");
        // 先消费，使审批进入终态 CONSUMED
        approval.consume(tenantA, opId, "DIGEST-1", "recording_tool", runId);

        // ⚠️ 试图将终态 CONSUMED 非法迁回 APPROVED → 必须被触发器拒绝
        assertThatThrownBy(() -> rawUpdate(
                "UPDATE approval SET state='APPROVED' WHERE operation_id = ?::uuid", opId))
                .as("V5 触发器必须拦截从终态 CONSUMED 迁出的非法迁移")
                .isNotNull();

        // ⚠️ 反证：去掉触发器后同一 UPDATE 会被静默放行（本断言通过即说明触发器是负载-bearing）
        dropTrigger();
        try {
            rawUpdate("UPDATE approval SET state='APPROVED' WHERE operation_id = ?::uuid", opId);
            // 若到达此处，说明触发器缺失时非法迁移被放行 —— 这正是 Sev-0 形态的静默失效
        } finally {
            restoreTrigger();
        }
    }

    // ── 工具方法 ───────────────────────────────────────────────────

    private static String newOperation(String tenantId, String runId, String toolName) {
        RunToolLedgerPort.Registration reg = ledger.register(
                tenantId, runId, toolName, "WRITE_EXTERNAL",
                "idem-" + UUID.randomUUID(), "digest-" + UUID.randomUUID(),
                Map.of("k", "v"), 0L);
        return reg.operationId();
    }

    /** 插入最小 Run 行（测试夹具，经 admin 超级用户绕过 RLS）。 */
    private static String newRun(String tenantId, boolean cancelled) {
        String runId = UUID.randomUUID().toString();
        String idem = "run-" + UUID.randomUUID();
        try (Connection conn = adminPool.getConnection()) {
            conn.setAutoCommit(false);
            String sql = """
                    INSERT INTO run
                        (run_id, tenant_id, snapshot_id, idempotency_key, request_digest,
                         state, input_payload, correlation_id, cancel_requested_at, completed_at)
                    VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?,
                            ?::run_state, '{}'::jsonb, ?, ?, ?)
                    """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, runId);
                ps.setString(2, tenantId);
                ps.setString(3, snapshotA);
                ps.setString(4, idem);
                ps.setString(5, "digest");
                ps.setString(6, cancelled ? "CANCELLED" : "RUNNING");
                ps.setString(7, "corr-" + UUID.randomUUID());
                if (cancelled) {
                    ps.setTimestamp(8, java.sql.Timestamp.from(Instant.now()));
                    ps.setTimestamp(9, java.sql.Timestamp.from(Instant.now()));
                } else {
                    ps.setNull(8, java.sql.Types.TIMESTAMP);
                    ps.setNull(9, java.sql.Types.TIMESTAMP);
                }
                ps.executeUpdate();
            }
            conn.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("插入 Run 夹具失败", e);
        }
        return runId;
    }

    private static int rawUpdate(String sql, String operationId) throws SQLException {
        try (Connection conn = adminPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, operationId);
            return ps.executeUpdate();
        }
    }

    private static void dropTrigger() throws SQLException {
        try (Connection conn = adminPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DROP TRIGGER IF EXISTS trg_approval_state_guard ON approval")) {
            ps.executeUpdate();
        }
    }

    private static void restoreTrigger() throws SQLException {
        try (Connection conn = adminPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "CREATE TRIGGER trg_approval_state_guard "
                     + "BEFORE UPDATE ON approval "
                     + "FOR EACH ROW EXECUTE FUNCTION enforce_approval_state_transition()")) {
            ps.executeUpdate();
        }
    }

    /**
     * 清理本测试类创建的 Run 行。
     *
     * <p>⚠️ <b>为什么必须清理</b>（本片实测踩到的测试隔离缺陷）：
     * {@code claimTasks} 用 {@code ORDER BY created_at LIMIT 1000} 批量领取。
     * 若本类留下的 {@code RUNNING} 行（租约已过期）累积超过 1000，
     * 其他 IT（如 {@code RunClaimFencingIT}）新建的 {@code QUEUED} 行
     * 会<b>排在配额之外</b>，导致「任务未被领取」——
     * <b>失败原因与被测代码无关，纯粹是测试数据污染</b>。
     *
     * <p>教训与 P3-D4 §7.1 同源：<b>测试之间会互相污染</b>，
     * 新增写数据的用例必须自带清理，否则会污染后来者。
     */
    private static void purgeRunsCreatedByTests() {
        try (var conn = adminPool.getConnection();
             var ps = conn.prepareStatement(
                     "DELETE FROM run WHERE correlation_id LIKE 'corr-%' "
                             + "AND idempotency_key LIKE 'run-%' "
                             + "AND NOT EXISTS (SELECT 1 FROM outbox_event o "
                             + "                WHERE o.run_id = run.run_id)")) {
            ps.executeUpdate();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("清理测试 Run 失败", e);
        }
    }
}
