package cn.studykid.multiagent.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.zaxxer.hikari.HikariDataSource;

import cn.studykid.multiagent.core.idempotency.IdempotencyKey;
import cn.studykid.multiagent.core.idempotency.RequestDigest;
import cn.studykid.multiagent.core.run.IllegalStateTransitionException;
import cn.studykid.multiagent.core.run.ToolOperationState;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.tool.StudentDataRepository;
import cn.studykid.multiagent.infrastructure.tool.ToolOperationReconciliationService;
import cn.studykid.multiagent.ports.persistence.OutboxMessage;
import cn.studykid.multiagent.ports.persistence.RunToolLedgerPort;

/**
 * ToolOperation 账本与对账集成测试（P3-D03 / FR-009 / AT-UNKNOWN-001/002）。
 *
 * <p>证明的是设计文档 §5 的核心命题：
 * <ul>
 *   <li>UNKNOWN 真实落账（而非随请求蒸发）；</li>
 *   <li>UNKNOWN 后新增 attempt 被 <b>DB 触发器</b>拦截（防盲目重放的最终防线）；</li>
 *   <li>对账收敛合法且可复查；同幂等键命中同一逻辑操作。</li>
 * </ul>
 *
 * <p><b>⚠️ 非特权角色</b>（{@link FaultInjectionSupport#requireNonPrivileged}）。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
class ToolLedgerReconciliationIT {

    private static HikariDataSource appPool;
    private static HikariDataSource adminPool;
    private static JdbcRunCreationRepository creationRepo;
    private static JdbcToolOperationLedger ledger;
    private static StudentDataRepository studentData;
    private static ToolOperationReconciliationService reconciliation;
    private static String tenantId;
    private static String snapshotId;

    @BeforeAll
    static void setUp() throws Exception {
        appPool = FaultInjectionSupport.appPool(4);
        adminPool = FaultInjectionSupport.adminPool();
        FaultInjectionSupport.requireNonPrivileged(appPool);
        creationRepo = new JdbcRunCreationRepository(appPool);
        ledger = new JdbcToolOperationLedger(appPool);
        studentData = new StudentDataRepository(appPool);
        // ⚠️ 负宽限期 = 「没查到即判 ABSENT」，专测 ABSENT 路径；
        //    SUCCEEDED 路径不受宽限期影响（查到即收敛）
        reconciliation = new ToolOperationReconciliationService(
                ledger, studentData, Duration.ofSeconds(-60));
        tenantId = FaultInjectionSupport.tenantIdBySlug(adminPool, "t-a");
        FaultInjectionSupport.ensureTenantSnapshot(adminPool, "t-a");
        snapshotId = FaultInjectionSupport.snapshotIdBySlug(adminPool, "t-a");
    }

    /** 建 Run（账本外键要求真实 Run 存在）并登记一个 UNKNOWN 操作。 */
    private record UnknownFixture(String runId, String operationId, String idemKey) { }

    /** student_profile 的格式约束：stu_ + 16 位小写十六进制（DEC-6 白名单）。 */
    private static String studentIdOf(long n) {
        return "stu_" + String.format("%016x", n);
    }

    /** 确保学生画像存在（weakness_record 的 FK 要求）。 */
    private void ensureStudent(String studentId) throws Exception {
        inTenant(conn -> {
            try (var ps = conn.prepareStatement(
                    "INSERT INTO student_profile (student_id, tenant_id, grade, subject) "
                  + "VALUES (?, ?::uuid, '初二', '数学') "
                  + "ON CONFLICT (student_id, tenant_id) DO NOTHING")) {
                ps.setString(1, studentId);
                ps.setString(2, tenantId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private UnknownFixture unknownOperation(String studentId, String knowledgePoint) {
        String runId = UUID.randomUUID().toString();
        creationRepo.insertRunAtomically(tenantId, runId, snapshotId,
                new IdempotencyKey("run.create", "led-" + runId),
                RequestDigest.ofText("d-" + runId),
                Map.of("q", "一元二次方程"), "corr-" + runId,
                new OutboxMessage(runId, tenantId, 0, 0L, Instant.now()));

        String idemKey = IdempotencyKey.forRecordWeakness(
                studentId, runId, knowledgePoint).composite();
        var reg = ledger.register(tenantId, runId, "record_weakness",
                "WRITE_PROFILE", idemKey, "digest-" + runId,
                Map.of("studentId", studentId, "knowledgePoint", knowledgePoint),
                0L);
        ledger.recordResult(tenantId, reg.operationId(),
                ToolOperationState.UNKNOWN, 0L, null, "下游超时，结果未知");
        return new UnknownFixture(runId, reg.operationId(), idemKey);
    }

    // ── T-1：UNKNOWN 真实落账 ──────────────────────────────────────

    @Test
    @DisplayName("T-1 ✅ 未知结果 → operation=UNKNOWN + attempt(result_unknown=true)")
    void unknownPersistsInLedger() throws Exception {
        UnknownFixture fx = unknownOperation(studentIdOf(0x11), "一元二次方程");

        var unknown = ledger.findUnknownOperations(tenantId);
        assertThat(unknown)
                .anyMatch(op -> op.operationId().equals(fx.operationId()));

        // attempt 行强制 result_unknown=true（CHECK 约束语义）
        Boolean resultUnknown = queryOne(
                "SELECT result_unknown FROM tool_operation_attempt "
              + "WHERE tenant_id=?::uuid AND operation_id=?::uuid",
                rs -> rs.getBoolean(1), fx.runId(), fx.operationId());
        assertThat(resultUnknown)
                .as("UNKNOWN 的 attempt 必须带 result_unknown 标记（区分可重试与须对账）")
                .isTrue();
    }

    // ── T-2：UNKNOWN 后新增 attempt 被 DB 触发器拒绝 ───────────────

    @Test
    @DisplayName("T-2 ⚠️⚠️ UNKNOWN 后新增 attempt → DB 触发器拒绝（防盲目重放最终防线）")
    void attemptAfterUnknownRejectedByTrigger() throws Exception {
        UnknownFixture fx = unknownOperation(studentIdOf(0x22), "一元二次方程");

        assertThatThrownBy(() -> inTenant(conn -> {
            try (var ps = conn.prepareStatement(
                    "INSERT INTO tool_operation_attempt "
                  + "(tenant_id, operation_id, attempt_number, state, fencing_token, "
                  + " completed_at, result_unknown) "
                  + "VALUES (?::uuid, ?::uuid, 2, 'IN_FLIGHT', 0, now(), false)")) {
                ps.setString(1, tenantId);
                ps.setString(2, fx.operationId());
                ps.executeUpdate();
            }
            return null;
        }))
                .as("⚠️⚠️ UNKNOWN 下新增 attempt = 盲目重试（可能重复写入 = Sev-0），"
                        + "须先完成对账")
                .isInstanceOf(Exception.class)
                .hasMessageContaining("UNKNOWN");
    }

    // ── T-3：状态机拒绝 UNKNOWN 的非法迁移 ─────────────────────────

    @Test
    @DisplayName("T-3 ⚠️ 状态机拒绝 UNKNOWN → FAILED / → SUCCEEDED 直接迁移")
    void stateMachineRejectsIllegalTransitions() {
        UnknownFixture fx = unknownOperation(studentIdOf(0x33), "一元二次方程");

        // UNKNOWN → FAILED（把「结果未知」当「明确失败」= 盲目重试的前提）
        assertThatThrownBy(() -> ledger.recordResult(tenantId, fx.operationId(),
                ToolOperationState.FAILED, 0L, null, null))
                .isInstanceOf(IllegalStateTransitionException.class);

        // UNKNOWN → SUCCEEDED（把「没把握」说成「成功了」）
        assertThatThrownBy(() -> ledger.recordResult(tenantId, fx.operationId(),
                ToolOperationState.SUCCEEDED, 0L, null, null))
                .isInstanceOf(IllegalStateTransitionException.class);

        // 对账目标只能是 RECONCILED_*
        assertThatThrownBy(() -> ledger.reconcile(tenantId, fx.operationId(),
                ToolOperationState.SUCCEEDED, "bogus"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── T-4：对账收敛 ──────────────────────────────────────────────

    @Test
    @DisplayName("T-4 ✅ 查到下游记录 → RECONCILED_SUCCEEDED；收敛后 attempt 仍被拒")
    void reconcileConvergesToSucceeded() throws Exception {
        String studentId = studentIdOf(0x44);
        ensureStudent(studentId);
        UnknownFixture fx = unknownOperation(studentId, "一元二次方程");

        // 模拟「副作用其实已发生」：下游画像库已有该记录
        studentData.insertWeakness(tenantId, studentId, fx.runId(),
                "一元二次方程", "概念不清", 0.9d);

        var op = ledger.findUnknownOperations(tenantId).stream()
                .filter(o -> o.operationId().equals(fx.operationId()))
                .findFirst().orElseThrow();

        var outcome = reconciliation.reconcileOne(tenantId, op);
        assertThat(outcome)
                .as("查到下游幂等记录 = 副作用已发生 → SUCCEEDED（预算不释放）")
                .isEqualTo(ToolOperationReconciliationService.Outcome.SUCCEEDED);

        String state = queryOne(
                "SELECT state::text FROM tool_operation "
              + "WHERE tenant_id=?::uuid AND operation_id=?::uuid",
                rs -> rs.getString(1), fx.runId(), fx.operationId());
        assertThat(state).isEqualTo("RECONCILED_SUCCEEDED");

        String source = queryOne(
                "SELECT reconciliation_source FROM tool_operation "
              + "WHERE tenant_id=?::uuid AND operation_id=?::uuid",
                rs -> rs.getString(1), fx.runId(), fx.operationId());
        assertThat(source).startsWith("profile-lookup:hit:");

        // ⚠️ 收敛（终态）后新增 attempt 仍被拒 —— 对账后重试同样非法
        assertThatThrownBy(() -> inTenant(conn -> {
            try (var ps = conn.prepareStatement(
                    "INSERT INTO tool_operation_attempt "
                  + "(tenant_id, operation_id, attempt_number, state, fencing_token, "
                  + " completed_at, result_unknown) "
                  + "VALUES (?::uuid, ?::uuid, 2, 'IN_FLIGHT', 0, now(), false)")) {
                ps.setString(1, tenantId);
                ps.setString(2, fx.operationId());
                ps.executeUpdate();
            }
            return null;
        }))
                .as("逻辑操作已终态，新增 attempt 必须被拒绝（对账后重试 = 重复写入）")
                .isInstanceOf(Exception.class)
                .hasMessageContaining("终态");
    }

    @Test
    @DisplayName("T-4b ✅ 宽限期后仍无下游记录 → RECONCILED_ABSENT")
    void reconcileAbsentAfterGrace() throws Exception {
        UnknownFixture fx = unknownOperation(studentIdOf(0x45), "一元二次方程");

        var op = ledger.findUnknownOperations(tenantId).stream()
                .filter(o -> o.operationId().equals(fx.operationId()))
                .findFirst().orElseThrow();

        var outcome = reconciliation.reconcileOne(tenantId, op);
        assertThat(outcome)
                .as("宽限期已过且下游无记录 → 确认未发生")
                .isEqualTo(ToolOperationReconciliationService.Outcome.ABSENT);

        String state = queryOne(
                "SELECT state::text FROM tool_operation "
              + "WHERE tenant_id=?::uuid AND operation_id=?::uuid",
                rs -> rs.getString(1), fx.runId(), fx.operationId());
        assertThat(state).isEqualTo("RECONCILED_ABSENT");
    }

    // ── T-5：幂等键命中同一逻辑操作 ────────────────────────────────

    @Test
    @DisplayName("T-5 ✅ 同幂等键重复登记 → 命中同一逻辑操作，不新建")
    void duplicateRegistrationHitsSameOperation() {
        UnknownFixture fx = unknownOperation(studentIdOf(0x55), "一元二次方程");

        var again = ledger.register(tenantId, fx.runId(), "record_weakness",
                "WRITE_PROFILE", fx.idemKey(), "digest-again",
                Map.of("studentId", studentIdOf(0x55)), 0L);

        assertThat(again.operationId())
                .as("幂等键唯一约束：重复调用指向同一逻辑操作（对账与幂等的最小单位）")
                .isEqualTo(fx.operationId());
    }

    // ── 辅助 ────────────────────────────────────────────────────────

    private <T> T queryOne(String sql, SqlReader<T> reader,
                           String runId, String operationId) throws Exception {
        return inTenant(conn -> {
            try (var ps = conn.prepareStatement(sql)) {
                ps.setString(1, tenantId);
                ps.setString(2, operationId);
                try (var rs = ps.executeQuery()) {
                    assertThat(rs.next())
                            .as("查询应有结果: %s", operationId)
                            .isTrue();
                    return reader.read(rs);
                }
            }
        });
    }

    @FunctionalInterface
    private interface SqlReader<T> {
        T read(java.sql.ResultSet rs) throws Exception;
    }

    private <T> T inTenant(TenantQuery<T> body) throws Exception {
        return FaultInjectionSupport.inTenant(appPool, tenantId, body::apply);
    }

    @FunctionalInterface
    private interface TenantQuery<T> {
        T apply(Connection conn) throws Exception;
    }
}
