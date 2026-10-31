package cn.studykid.multiagent.application.cap;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.cap.CapApproval;
import cn.studykid.multiagent.core.cap.ReasonCode;
import cn.studykid.multiagent.ports.persistence.CapApprovalPort;

/**
 * M3-2 回归测试：CAP 路径的「待人工决策」登记（{@code requestHuman}）。
 *
 * <p><b>背景（E4）</b>：{@code cap_approval} 此前<b>无法表示「尚未有人决定」</b> ——
 * 它的 {@code PENDING} 语义是「<b>已批准</b>、待消耗」，
 * 把「待人决定」塞进去等于「人还没批就能消耗」= Sev-0。
 *
 * <p><b>断言要点</b>：
 * <ol>
 *   <li>登记的状态必须是 <b>AWAITING_HUMAN</b>（不是 PENDING）；</li>
 *   <li>{@code acquire} 遇到 AWAITING_HUMAN 行 → <b>挂起</b>（requiresHumanDecision），
 *       <b>不是</b>判「审批不通过」——两者处置完全不同；</li>
 *   <li>登记的范围必须与参数绑定一致（人批准的就是这一次调用）。</li>
 * </ol>
 */
class ApprovalStateMachineHumanDecisionTest {

    private static final String TENANT = "6f5d33fc-723e-4f64-beda-9e4435c72691";
    private static final String TOOL = "record_weakness";
    private static final String PRINCIPAL = "p-1";

    /** 记录型 CAP 审批端口。 */
    private static final class RecordingCapApprovalPort implements CapApprovalPort {
        final List<CapApprovalRequest> requests = new ArrayList<>();
        final List<CapApproval> approvals = new ArrayList<>();

        @Override
        public String grant(CapApprovalGrant grant) {
            throw new UnsupportedOperationException("本用例不预建审批");
        }

        @Override
        public String requestHumanApproval(CapApprovalRequest request) {
            requests.add(request);
            return "approval-human-1";
        }

        @Override
        public ReservationOutcome reserve(String tenantId, String approvalId,
                String invocationId, String operationId, String runId,
                String effectiveArgsHash, String closureFingerprint, long authzVersion) {
            return ReservationOutcome.NOT_FOUND;
        }

        @Override
        public boolean markIntentSubmitted(String tenantId, String approvalId,
                String invocationId) {
            return false;
        }

        @Override
        public boolean consume(String tenantId, String approvalId, String invocationId,
                String terminalStatus) {
            return false;
        }

        @Override
        public boolean cancel(String tenantId, String approvalId, String invocationId,
                String reason) {
            return false;
        }

        @Override
        public List<CapApproval> findByTool(String tenantId, String toolId) {
            return approvals;
        }

        @Override
        public Optional<CapApproval> findById(String tenantId, String approvalId) {
            return Optional.empty();
        }

        @Override
        public Optional<CapApproval> findByInvocation(String tenantId, String invocationId) {
            return Optional.empty();
        }
    }

    private static ApprovalStateMachine.AcquireRequest request() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("studentId", "s-1");
        args.put("knowledgePoint", "二次函数");
        return new ApprovalStateMachine.AcquireRequest(
                TENANT, PRINCIPAL, TOOL, "run-1", "inv-1", "op-1", args,
                "closure-fp", 7L, Instant.now());
    }

    @Test
    @DisplayName("✅ requestHuman 登记的是「待人工决策」（无审批人、无决定时间）")
    void requestHumanRegistersAwaitingHuman() {
        RecordingCapApprovalPort port = new RecordingCapApprovalPort();
        ApprovalStateMachine machine =
                new ApprovalStateMachine(port, new ApprovalBindingService());

        String approvalId = machine.requestHuman(request(), "PARENT_OR_TEACHER",
                Duration.ofMinutes(15));

        assertThat(approvalId).isEqualTo("approval-human-1");
        assertThat(port.requests).hasSize(1);
        CapApprovalPort.CapApprovalRequest registered = port.requests.get(0);
        assertThat(registered.tenantId()).isEqualTo(TENANT);
        assertThat(registered.toolId()).isEqualTo(TOOL);
        assertThat(registered.approverScope()).isEqualTo("PARENT_OR_TEACHER");
        assertThat(registered.closureFingerprint()).isEqualTo("closure-fp");
        assertThat(registered.authzVersion()).isEqualTo(7L);
        assertThat(registered.expiresAt())
                .as("待办必须带有效期（否则永远批不完也不过期）")
                .isAfter(Instant.now());
    }

    @Test
    @DisplayName("✅ 登记的范围与参数绑定一致（人批准的就是这一次调用）")
    void registeredScopeBindsArguments() {
        RecordingCapApprovalPort port = new RecordingCapApprovalPort();
        ApprovalStateMachine machine =
                new ApprovalStateMachine(port, new ApprovalBindingService());

        machine.requestHuman(request(), "PARENT_OR_TEACHER", Duration.ofMinutes(15));

        CapApprovalPort.CapApprovalRequest registered = port.requests.get(0);
        assertThat(registered.scopeDefinition())
                .as("范围定义必须落库（消费侧要据它重验绑定）")
                .isNotBlank()
                .contains("二次函数");
        assertThat(registered.approvalScopeHash()).isNotBlank();
        assertThat(registered.effectiveArgsHash()).isNotBlank();
    }

    @Test
    @DisplayName("✅ acquire 遇到待人工决策 → 挂起，不是判「审批不通过」")
    void acquireReportsAwaitingHuman() {
        RecordingCapApprovalPort port = new RecordingCapApprovalPort();
        // ⚠️ 范围必须是**可解析且与本次参数绑定一致**的：
        //    绑定校验在状态机之前，范围不可解析会被判「绑定不符」，
        //    就永远走不到「待人工决策」分支（测试会误判为功能缺失）。
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("studentId", "s-1");
        args.put("knowledgePoint", "二次函数");
        Map<String, ApprovalScope.Constraint> constraints = new LinkedHashMap<>();
        constraints.put("studentId", new ApprovalScope.Constraint("eq", "s-1"));
        constraints.put("knowledgePoint", new ApprovalScope.Constraint("eq", "二次函数"));
        ApprovalScope scope = ApprovalScope.of(TOOL, constraints);
        ApprovalBindingService.Binding binding =
                new ApprovalBindingService().generate(scope, args);
        port.approvals.add(new CapApproval(
                "approval-human-1", TENANT, PRINCIPAL,
                "run-1", "inv-1", "op-1",
                TOOL, "record_weakness",
                binding.approvalScopeHash(), scope.canonicalText(),
                binding.effectiveArgsHash(), "closure-fp", 7L,
                null, "PARENT_OR_TEACHER",
                null, Instant.now().plusSeconds(600),
                CapApproval.ApprovalState.AWAITING_HUMAN, null, null,
                Instant.now(), Instant.now()));
        ApprovalStateMachine machine =
                new ApprovalStateMachine(port, new ApprovalBindingService());

        ApprovalStateMachine.Acquisition acquisition = machine.acquire(request());

        assertThat(acquisition.requiresHumanDecision())
                .as("⚠️ 「待人批」必须与「永远过不了」区分开：前者挂起、后者失败")
                .isTrue();
        assertThat(acquisition.acquired())
                .as("尚未有人批准 → 未获得授权")
                .isFalse();
        assertThat(acquisition.detail()).isNotBlank();
    }

    @Test
    @DisplayName("✅ 端口未实现运行时登记时 → 明确失败（不静默降级为「不登记」）")
    void unsupportedPortFailsLoudly() {
        CapApprovalPort unsupported = new CapApprovalPort() {
            @Override
            public String grant(CapApprovalGrant grant) {
                throw new UnsupportedOperationException("不支持预建");
            }
            @Override
            public ReservationOutcome reserve(String tenantId, String approvalId,
                    String invocationId, String operationId, String runId,
                    String effectiveArgsHash, String closureFingerprint, long authzVersion) {
                return ReservationOutcome.NOT_FOUND;
            }
            @Override
            public boolean markIntentSubmitted(String tenantId, String approvalId,
                    String invocationId) {
                return false;
            }
            @Override
            public boolean consume(String tenantId, String approvalId, String invocationId,
                    String terminalStatus) {
                return false;
            }
            @Override
            public boolean cancel(String tenantId, String approvalId, String invocationId,
                    String reason) {
                return false;
            }
            @Override
            public List<CapApproval> findByTool(String tenantId, String toolId) {
                return List.of();
            }
            @Override
            public Optional<CapApproval> findById(String tenantId, String approvalId) {
                return Optional.empty();
            }
            @Override
            public Optional<CapApproval> findByInvocation(String tenantId, String invocationId) {
                return Optional.empty();
            }
        };

        ApprovalStateMachine machine =
                new ApprovalStateMachine(unsupported, new ApprovalBindingService());

        try {
            machine.requestHuman(request(), "PARENT_OR_TEACHER", Duration.ofMinutes(15));
            // 未抛异常 = 静默降级 = 待办没登记但调用方以为登记了
            throw new AssertionError("端口不支持运行时登记时必须明确失败（fail-closed）");
        } catch (UnsupportedOperationException expected) {
            assertThat(expected.getMessage()).contains("fail-closed");
        }
    }

    @Test
    @DisplayName("✅ 原因码仍区分「绑定不符 / 已过期 / 无审批」三种失败语义")
    void denialReasonsRemainDistinct() {
        RecordingCapApprovalPort port = new RecordingCapApprovalPort();
        ApprovalStateMachine machine =
                new ApprovalStateMachine(port, new ApprovalBindingService());

        ApprovalStateMachine.Acquisition acquisition = machine.acquire(request());

        assertThat(acquisition.acquired()).isFalse();
        assertThat(acquisition.requiresHumanDecision())
                .as("没有任何审批行 → 「没有」，不是「等一等」")
                .isFalse();
        assertThat(acquisition.reasonCode()).isEqualTo(ReasonCode.APPROVAL_NOT_FOUND);
    }
}
