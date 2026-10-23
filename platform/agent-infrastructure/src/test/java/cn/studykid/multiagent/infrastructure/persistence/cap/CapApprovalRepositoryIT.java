package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import cn.studykid.multiagent.core.cap.CapApproval;
import cn.studykid.multiagent.core.cap.CapCompensationTask;
import cn.studykid.multiagent.ports.persistence.CapApprovalPort;
import cn.studykid.multiagent.ports.persistence.CompensationTaskPort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CAP 审批仓储的<b>真实 PG</b> 集成测试（P3-T3 · §5.3.1 / §5.3.3）。
 *
 * <p>⚠️ 与单元测试的分工：单元测试锁「判定逻辑」，本类锁「<b>状态真的在库里</b>」：
 * <ul>
 *   <li>预占必须是<b>独占</b>的（CAS + DB 触发器双保险）；</li>
 *   <li>终态<b>不可迁出</b>（DB 触发器兜底，应用层绕过也会被拒）；</li>
 *   <li>绑定项是预占的<b>前置条件</b>，不是「预占后再校验」；</li>
 *   <li>租户隔离必须在<b>非特权角色</b>下成立（RLS 真伪只能这样验）。</li>
 * </ul>
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
class CapApprovalRepositoryIT extends CapITBase {

    private static final String TOOL_ID = "it-cap-approve-tool";
    private static final String PRINCIPAL = "worker";

    private CapApprovalPort approvals;
    private CompensationTaskPort tasks;

    private String operationId;

    private CapApprovalPort approvals() {
        if (approvals == null) {
            approvals = new JdbcCapApprovalRepository(appPool);
        }
        return approvals;
    }

    private CompensationTaskPort tasks() {
        if (tasks == null) {
            tasks = new JdbcCompensationTaskRepository(appPool);
        }
        return tasks;
    }

    /** 造一条真实的逻辑操作（cap_approval 的 operation_id 有复合外键）。 */
    private String createOperation(String tenantId) {
        String agentId = createAgentWithVersion(tenantId, uniqueName("it-op-agent"), "{}", true);
        String snapshotId = createExecutionSnapshot(tenantId, agentId);
        String runId = createRun(tenantId, agentId, snapshotId);
        String opId = UUID.randomUUID().toString();
        adminExec("INSERT INTO tool_operation (operation_id, tenant_id, run_id, tool_name,"
                + " risk_level, state, idempotency_key, request_digest, tool_arguments,"
                + " fencing_token) VALUES ('" + opId + "'::uuid, '" + tenantId + "'::uuid, '"
                + runId + "'::uuid, '" + TOOL_ID + "', 'WRITE_EXTERNAL', 'IN_FLIGHT', 'cap-op-"
                + opId + "', 'digest', '{}'::jsonb, 1)");
        return opId;
    }

    private CapApprovalPort.CapApprovalGrant grant(String tenantId, String principal,
                                                    Instant expiresAt) {
        return new CapApprovalPort.CapApprovalGrant(tenantId, principal, TOOL_ID, "resource",
                "scope-hash", "{\"tool\":\"" + TOOL_ID + "\"}", "args-hash",
                "cap_fp_v1:test", 1L, "it-approver", "SCOPE_ALL", expiresAt);
    }

    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("§5.3.3 状态机全迁移路径")
    class FullTransitionPath {

        @Test
        @DisplayName("✅ PENDING → RESERVED → INTENT_SUBMITTED → CONSUMED")
        void fullPath() {
            String id = approvals().grant(grant(tenantA, PRINCIPAL,
                    Instant.now().plusSeconds(3600)));

            assertThat(approvals().findById(tenantA, id).orElseThrow().state())
                    .isEqualTo(CapApproval.ApprovalState.PENDING);

            assertThat(approvals().reserve(tenantA, id, "inv-1", createOperation(tenantA),
                    null, "args-hash", "cap_fp_v1:test", 1L))
                    .isEqualTo(CapApprovalPort.ReservationOutcome.RESERVED);
            assertThat(approvals().findById(tenantA, id).orElseThrow().state())
                    .isEqualTo(CapApproval.ApprovalState.RESERVED);

            assertThat(approvals().markIntentSubmitted(tenantA, id, "inv-1")).isTrue();
            assertThat(approvals().findById(tenantA, id).orElseThrow().state())
                    .isEqualTo(CapApproval.ApprovalState.INTENT_SUBMITTED);

            assertThat(approvals().consume(tenantA, id, "inv-1", "SUCCEEDED")).isTrue();
            CapApproval consumed = approvals().findById(tenantA, id).orElseThrow();
            assertThat(consumed.state()).isEqualTo(CapApproval.ApprovalState.CONSUMED);
            assertThat(consumed.terminalStatus()).isEqualTo("SUCCEEDED");
        }

        @Test
        @DisplayName("✅ 取消是幂等的（重复取消不报错、不二次迁移）")
        void cancelIsIdempotent() {
            String id = approvals().grant(grant(tenantA, PRINCIPAL,
                    Instant.now().plusSeconds(3600)));
            approvals().reserve(tenantA, id, "inv-1", createOperation(tenantA),
                    null, "args-hash", "cap_fp_v1:test", 1L);

            assertThat(approvals().cancel(tenantA, id, "inv-1", "budget")).isTrue();
            assertThat(approvals().cancel(tenantA, id, "inv-1", "budget"))
                    .as("⛔ 重复取消产生了二次迁移")
                    .isFalse();
            assertThat(approvals().findById(tenantA, id).orElseThrow().state())
                    .isEqualTo(CapApproval.ApprovalState.CANCELLED);
        }
    }

    @Nested
    @DisplayName("预占独占与绑定前置条件")
    class ReservationGuards {

        @Test
        @DisplayName("⚠️ 他调用预占 → ALREADY_RESERVED_BY_OTHER")
        void reservationIsExclusive() {
            String id = approvals().grant(grant(tenantA, PRINCIPAL,
                    Instant.now().plusSeconds(3600)));
            String op = createOperation(tenantA);

            assertThat(approvals().reserve(tenantA, id, "inv-1", op,
                    null, "args-hash", "cap_fp_v1:test", 1L))
                    .isEqualTo(CapApprovalPort.ReservationOutcome.RESERVED);

            assertThat(approvals().reserve(tenantA, id, "inv-2", op,
                    null, "args-hash", "cap_fp_v1:test", 1L))
                    .as("⛔ 同一审批被两个调用预占 = 审批可被双花")
                    .isEqualTo(CapApprovalPort.ReservationOutcome.ALREADY_RESERVED_BY_OTHER);
        }

        @Test
        @DisplayName("✅ 同一调用重复预占 → 幂等命中")
        void sameInvocationIsIdempotent() {
            String id = approvals().grant(grant(tenantA, PRINCIPAL,
                    Instant.now().plusSeconds(3600)));
            String op = createOperation(tenantA);

            approvals().reserve(tenantA, id, "inv-1", op, null, "args-hash",
                    "cap_fp_v1:test", 1L);

            assertThat(approvals().reserve(tenantA, id, "inv-1", op, null, "args-hash",
                    "cap_fp_v1:test", 1L))
                    .isEqualTo(CapApprovalPort.ReservationOutcome.ALREADY_RESERVED_BY_SAME);
        }

        @Test
        @DisplayName("⚠️ 绑定项不符 → 预占失败（不是「预占成功后再校验」）")
        void bindingMismatchBlocksReservation() {
            String id = approvals().grant(grant(tenantA, PRINCIPAL,
                    Instant.now().plusSeconds(3600)));
            String op = createOperation(tenantA);

            assertThat(approvals().reserve(tenantA, id, "inv-1", op, null, "其他-哈希",
                    "cap_fp_v1:test", 1L))
                    .as("⛔ 参数哈希不符却预占成功 = 绑定校验形同虚设")
                    .isEqualTo(CapApprovalPort.ReservationOutcome.NOT_FOUND);
            assertThat(approvals().reserve(tenantA, id, "inv-1", op, null, "args-hash",
                    "cap_fp_v1:其他", 1L))
                    .isEqualTo(CapApprovalPort.ReservationOutcome.NOT_FOUND);
            assertThat(approvals().reserve(tenantA, id, "inv-1", op, null, "args-hash",
                    "cap_fp_v1:test", 99L))
                    .as("⛔ 授权版本不符却预占成功")
                    .isEqualTo(CapApprovalPort.ReservationOutcome.NOT_FOUND);
        }

        @Test
        @DisplayName("⚠️ 过期审批 → 预占被拒")
        void expiredApprovalCannotBeReserved() {
            String id = approvals().grant(grant(tenantA, PRINCIPAL,
                    Instant.now().minusSeconds(1)));

            assertThat(approvals().reserve(tenantA, id, "inv-1", createOperation(tenantA),
                    null, "args-hash", "cap_fp_v1:test", 1L))
                    .isEqualTo(CapApprovalPort.ReservationOutcome.EXPIRED);
        }

        @Test
        @DisplayName("⛔ S6a 审批不存在 → 预占被拒（NOT_FOUND）")
        void missingApprovalCannotBeReserved() {
            // ⚠️ 审批 id 根本不存在 —— 不是「存在但被取消/过期/已消费」。
            //    仓储层必须把它与「绑定不符」区分开：前者是「没有这份授权」，
            //    后者是「有这份授权但这次调用不匹配」—— 处置方式不同。
            String absentId = UUID.randomUUID().toString();

            assertThat(approvals().reserve(tenantA, absentId, "inv-1", createOperation(tenantA),
                    null, "args-hash", "cap_fp_v1:test", 1L))
                    .as("⛔ 审批不存在却预占成功 = 没有授权也能执行")
                    .isEqualTo(CapApprovalPort.ReservationOutcome.NOT_FOUND);
        }

        @Test
        @DisplayName("⛔ S6e 已消费审批 → 再次预占被拒（ALREADY_CONSUMED，重放）")
        void consumedApprovalCannotBeReservedAgain() {
            String id = approvals().grant(grant(tenantA, PRINCIPAL,
                    Instant.now().plusSeconds(3600)));
            String op = createOperation(tenantA);

            assertThat(approvals().reserve(tenantA, id, "inv-1", op,
                    null, "args-hash", "cap_fp_v1:test", 1L))
                    .isEqualTo(CapApprovalPort.ReservationOutcome.RESERVED);
            assertThat(approvals().markIntentSubmitted(tenantA, id, "inv-1")).isTrue();
            assertThat(approvals().consume(tenantA, id, "inv-1", "SUCCEEDED")).isTrue();

            // ⚠️ 换一个调用标识重来 —— 这正是「审批重放」攻击的形态：
            //    拿已消费的审批去执行另一次调用。
            assertThat(approvals().reserve(tenantA, id, "inv-2", op,
                    null, "args-hash", "cap_fp_v1:test", 1L))
                    .as("⛔ 已消费的审批能再次预占 = 一次审批可被无限重放")
                    .isEqualTo(CapApprovalPort.ReservationOutcome.ALREADY_CONSUMED);
        }
    }

    @Nested
    @DisplayName("DB 状态守卫")
    class DatabaseGuard {

        @Test
        @DisplayName("⚠️ 终态不可迁出（应用层绕过也会被 DB 拒绝）")
        void terminalStateCannotEscape() {
            String id = approvals().grant(grant(tenantA, PRINCIPAL,
                    Instant.now().plusSeconds(3600)));
            approvals().reserve(tenantA, id, "inv-1", createOperation(tenantA),
                    null, "args-hash", "cap_fp_v1:test", 1L);
            approvals().cancel(tenantA, id, "inv-1", "budget");

            assertThatThrownBy(() -> adminExec(
                    "UPDATE cap_approval SET approval_state = 'RESERVED'"
                            + " WHERE tenant_id = '" + tenantA + "'::uuid AND approval_id = '"
                            + id + "'::uuid"))
                    .as("⛔ 终态可迁出 = 被拒绝的审批能重新变得可用")
                    .rootCause()
                    .hasMessageContaining("不可迁移");
        }

        @Test
        @DisplayName("⚠️ 非法跃迁（PENDING → CONSUMED）被 DB 拒绝")
        void illegalTransitionIsRejected() {
            String id = approvals().grant(grant(tenantA, PRINCIPAL,
                    Instant.now().plusSeconds(3600)));

            assertThatThrownBy(() -> adminExec(
                    "UPDATE cap_approval SET approval_state = 'CONSUMED'"
                            + " WHERE tenant_id = '" + tenantA + "'::uuid AND approval_id = '"
                            + id + "'::uuid"))
                    .as("⛔ 非法跃迁 PENDING → CONSUMED 被放行 = 审批可被凭空消费")
                    .rootCause()
                    .hasMessageContaining("只能从");
        }
    }

    @Nested
    @DisplayName("租户隔离（RLS · 非特权角色）")
    class TenantIsolation {

        @Test
        @DisplayName("⛔ 跨租户读不到别家的审批")
        void crossTenantApprovalIsInvisible() {
            String id = approvals().grant(grant(tenantA, PRINCIPAL,
                    Instant.now().plusSeconds(3600)));

            assertThat(approvals().findById(tenantB, id))
                    .as("⛔ 跨租户读到了别家的审批")
                    .isEmpty();
            assertThat(approvals().findByTool(tenantB, TOOL_ID))
                    .as("⛔ 跨租户列出了别家的审批")
                    .isEmpty();
        }

        @Test
        @DisplayName("✅ 本租户读得到自己的审批")
        void ownTenantSeesOwnApproval() {
            String id = approvals().grant(grant(tenantA, PRINCIPAL,
                    Instant.now().plusSeconds(3600)));

            assertThat(approvals().findById(tenantA, id)).isPresent();
        }
    }

    @Nested
    @DisplayName("补偿任务仓储")
    class CompensationTasks {

        @Test
        @DisplayName("✅ 入队幂等（同一 kind + target 至多一条）")
        void enqueueIsIdempotent() {
            CapCompensationTask task = new CapCompensationTask(
                    UUID.randomUUID().toString(), tenantA,
                    CapCompensationTask.CompensationKind.RELEASE_BUDGET, "res-1", "inv-1",
                    null, CapCompensationTask.TaskState.PENDING, 0, null,
                    Instant.now(), Instant.now(), null);

            assertThat(tasks().enqueue(task)).isTrue();
            assertThat(tasks().enqueue(task))
                    .as("⛔ 重复入队成功 = 补偿可能被执行两次（额度凭空放大）")
                    .isFalse();

            List<CapCompensationTask> pending = tasks().findPending(tenantA, 50);
            assertThat(pending).hasSize(1);
            assertThat(pending.get(0).kind())
                    .isEqualTo(CapCompensationTask.CompensationKind.RELEASE_BUDGET);
        }

        @Test
        @DisplayName("✅ 标记完成 / 失败后可被状态机读出")
        void taskStateTransitions() {
            CapCompensationTask task = new CapCompensationTask(
                    UUID.randomUUID().toString(), tenantA,
                    CapCompensationTask.CompensationKind.CANCEL_APPROVAL, "ap-1", "inv-1",
                    null, CapCompensationTask.TaskState.PENDING, 0, null,
                    Instant.now(), Instant.now(), null);
            tasks().enqueue(task);

            assertThat(tasks().markFailed(tenantA, task.taskId(), "boom",
                    Instant.now().plusSeconds(60))).isTrue();
            Optional<CapCompensationTask> failed = tasks().findById(tenantA, task.taskId());
            assertThat(failed).isPresent();
            assertThat(failed.orElseThrow().state())
                    .isEqualTo(CapCompensationTask.TaskState.FAILED);
            assertThat(failed.orElseThrow().attempts()).isEqualTo(1);

            assertThat(tasks().markCompleted(tenantA, task.taskId())).isTrue();
            assertThat(tasks().findById(tenantA, task.taskId()).orElseThrow().state())
                    .isEqualTo(CapCompensationTask.TaskState.COMPLETED);
        }

        @Test
        @DisplayName("⛔ 跨租户读不到别家的补偿任务")
        void compensationTasksAreTenantIsolated() {
            CapCompensationTask task = new CapCompensationTask(
                    UUID.randomUUID().toString(), tenantA,
                    CapCompensationTask.CompensationKind.RETRY_LEDGER_POST, "op-1", "inv-1",
                    "{}", CapCompensationTask.TaskState.PENDING, 0, null,
                    Instant.now(), Instant.now(), null);
            tasks().enqueue(task);

            assertThat(tasks().findById(tenantB, task.taskId()))
                    .as("⛔ 跨租户读到了别家的补偿任务")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("查询")
    class Queries {

        @Test
        @DisplayName("✅ 按工具 / 按调用标识查找")
        void findByToolAndInvocation() {
            String id = approvals().grant(grant(tenantA, PRINCIPAL,
                    Instant.now().plusSeconds(3600)));
            String op = createOperation(tenantA);
            approvals().reserve(tenantA, id, "inv-1", op, null, "args-hash",
                    "cap_fp_v1:test", 1L);

            assertThat(approvals().findByTool(tenantA, TOOL_ID))
                    .extracting(CapApproval::approvalId).contains(id);
            assertThat(approvals().findByInvocation(tenantA, "inv-1"))
                    .as("⛔ 按调用标识未查到审批")
                    .isPresent()
                    .get()
                    .extracting(CapApproval::approvalId)
                    .isEqualTo(id);
            assertThat(approvals().findByInvocation(tenantA, "inv-none")).isEmpty();
        }

        @Test
        @DisplayName("✅ 范围定义全文随审批落库（§5.3.1「记录保存完整范围 JSON + hash」）")
        void scopeDefinitionIsPersisted() {
            String id = approvals().grant(grant(tenantA, PRINCIPAL,
                    Instant.now().plusSeconds(3600)));

            CapApproval approval = approvals().findById(tenantA, id).orElseThrow();
            assertThat(approval.scopeDefinition())
                    .as("⛔ 范围定义未落库：消费时无法重验范围，防参数替换重放形同虚设")
                    .contains(TOOL_ID);
            assertThat(approval.approvalScopeHash()).isEqualTo("scope-hash");
            assertThat(approval.principalId()).isEqualTo(PRINCIPAL);
        }
    }
}
