package cn.studykid.multiagent.application.cap;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.cap.ReasonCode;
import cn.studykid.multiagent.ports.persistence.CapApprovalPort;

/**
 * 审批状态机测试（P3-T3 · §5.3.3 · §9.1 S6a~e）。
 *
 * <p>⚠️ <b>本类的核心断言是「五个码彼此可区分」</b>：
 * 若 S6a~e 全都退化成「审批不通过」，
 * 那么「审批被谁占用」「参数是否被替换」这两条最重要的安全结论
 * 就无法从决策与日志中读出 —— 验收矩阵也就形同虚设。
 *
 * <p>⚠️ <b>越权拒绝与正向放行必须成对</b>：
 * 每个「预占成功」旁边都有一个「必须被拒且原因码精确」。
 */
class ApprovalStateMachineTest {

    private static final String TENANT = "t-cap-approval";
    private static final String PRINCIPAL = "worker";
    private static final String TOOL = "send_email";
    private static final String FINGERPRINT = "cap_fp_v1:abcdef";
    private static final long AUTHZ_VERSION = 7L;

    private CapExecutionFixture exec;
    private ApprovalStateMachine machine;
    private ApprovalBindingService bindingService;

    @BeforeEach
    void setUp() {
        exec = new CapExecutionFixture();
        bindingService = new ApprovalBindingService();
        machine = new ApprovalStateMachine(exec.capApprovalPort(), bindingService);
    }

    // ── 夹具 ──────────────────────────────────────────────────────

    private ApprovalScope scope() {
        return ApprovalScope.of(TOOL,
                Map.of("to", new ApprovalScope.Constraint("eq", "a@example.com")));
    }

    private String grant(Map<String, Object> args, Instant expiresAt) {
        ApprovalScope scope = scope();
        ApprovalBindingService.Binding binding = bindingService.generate(scope, args);
        return exec.capApprovalPort().grant(new CapApprovalPort.CapApprovalGrant(
                TENANT, PRINCIPAL, TOOL, "resource",
                binding.approvalScopeHash(), scope.canonicalText(), binding.effectiveArgsHash(),
                FINGERPRINT, AUTHZ_VERSION, "approver-1", "SCOPE_ALL", expiresAt));
    }

    private ApprovalStateMachine.AcquireRequest request(Map<String, Object> args,
                                                       String invocationId) {
        return new ApprovalStateMachine.AcquireRequest(
                TENANT, PRINCIPAL, TOOL, "run-1", invocationId, invocationId,
                args, FINGERPRINT, AUTHZ_VERSION, Instant.now());
    }

    private static Map<String, Object> args() {
        return Map.of("to", "a@example.com");
    }

    // ── 正向 ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("正向放行")
    class Allow {

        @Test
        @DisplayName("✅ 审批存在且绑定一致 → 预占成功")
        void matchingApprovalIsReserved() {
            grant(args(), Instant.now().plusSeconds(3600));

            ApprovalStateMachine.Acquisition acquisition = machine.acquire(request(args(), "inv-1"));

            assertThat(acquisition.acquired()).isTrue();
            assertThat(acquisition.approvalId()).isNotBlank();
            assertThat(acquisition.reasonCode()).isEqualTo(ReasonCode.SUCCESS);
        }

        @Test
        @DisplayName("✅ 同一调用重复预占 → 幂等命中（非拒绝）")
        void sameInvocationIsIdempotent() {
            grant(args(), Instant.now().plusSeconds(3600));

            ApprovalStateMachine.Acquisition first = machine.acquire(request(args(), "inv-1"));
            ApprovalStateMachine.Acquisition second = machine.acquire(request(args(), "inv-1"));

            assertThat(first.acquired()).isTrue();
            assertThat(second.acquired())
                    .as("⚠️ 崩溃后重放同一调用必须幂等放行，而不是自相矛盾地拒绝自己")
                    .isTrue();
            assertThat(second.approvalId()).isEqualTo(first.approvalId());
        }
    }

    // ── S6a~e ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("§9.1 S6a~e 五个码必须彼此可区分")
    class ScenarioCodes {

        @Test
        @DisplayName("S6a 审批不存在 → APPROVAL_NOT_FOUND")
        void notFound() {
            ApprovalStateMachine.Acquisition acquisition =
                    machine.acquire(request(args(), "inv-1"));

            assertThat(acquisition.acquired()).isFalse();
            assertThat(acquisition.reasonCode()).isEqualTo(ReasonCode.APPROVAL_NOT_FOUND);
        }

        @Test
        @DisplayName("S6b 审批过期 → APPROVAL_EXPIRED")
        void expired() {
            grant(args(), Instant.now().minusSeconds(1));

            ApprovalStateMachine.Acquisition acquisition =
                    machine.acquire(request(args(), "inv-1"));

            assertThat(acquisition.acquired()).isFalse();
            assertThat(acquisition.reasonCode()).isEqualTo(ReasonCode.APPROVAL_EXPIRED);
        }

        @Test
        @DisplayName("S6c 参数被替换 → APPROVAL_SCOPE_MISMATCH")
        void replacedArgument() {
            grant(args(), Instant.now().plusSeconds(3600));

            ApprovalStateMachine.Acquisition acquisition = machine.acquire(
                    request(Map.of("to", "attacker@example.com"), "inv-1"));

            assertThat(acquisition.acquired()).isFalse();
            assertThat(acquisition.reasonCode())
                    .as("⚠️ 参数替换重放必须落到 APPROVAL_SCOPE_MISMATCH")
                    .isEqualTo(ReasonCode.APPROVAL_SCOPE_MISMATCH);
        }

        @Test
        @DisplayName("S6c 范围被突破 → APPROVAL_SCOPE_MISMATCH")
        void scopeViolation() {
            grant(args(), Instant.now().plusSeconds(3600));

            ApprovalStateMachine.Acquisition acquisition = machine.acquire(
                    request(Map.of("to", "a@example.com", "cc", "attacker@example.com"), "inv-1"));

            assertThat(acquisition.acquired()).isFalse();
            assertThat(acquisition.reasonCode())
                    .isEqualTo(ReasonCode.APPROVAL_SCOPE_MISMATCH);
        }

        @Test
        @DisplayName("S6d 被其他调用预占 → APPROVAL_RESERVED_BY_OTHER")
        void reservedByOther() {
            String approvalId = grant(args(), Instant.now().plusSeconds(3600));
            ApprovalScope scope = scope();
            ApprovalBindingService.Binding binding = bindingService.generate(scope, args());

            // ⚠️ 由「另一个调用」预占
            exec.capApprovalPort().reserve(TENANT, approvalId, "inv-other", "op-other",
                    "run-1", binding.effectiveArgsHash(), FINGERPRINT, AUTHZ_VERSION);

            ApprovalStateMachine.Acquisition acquisition =
                    machine.acquire(request(args(), "inv-1"));

            assertThat(acquisition.acquired()).isFalse();
            assertThat(acquisition.reasonCode())
                    .as("⚠️ 他调用抢占必须落到 APPROVAL_RESERVED_BY_OTHER")
                    .isEqualTo(ReasonCode.APPROVAL_RESERVED_BY_OTHER);
        }

        @Test
        @DisplayName("S6e 已消费重放 → APPROVAL_REPLAY_REJECTED")
        void consumedReplay() {
            String approvalId = grant(args(), Instant.now().plusSeconds(3600));
            ApprovalScope scope = scope();
            ApprovalBindingService.Binding binding = bindingService.generate(scope, args());

            exec.capApprovalPort().reserve(TENANT, approvalId, "inv-1", "op-1",
                    "run-1", binding.effectiveArgsHash(), FINGERPRINT, AUTHZ_VERSION);
            exec.capApprovalPort().markIntentSubmitted(TENANT, approvalId, "inv-1");
            exec.capApprovalPort().consume(TENANT, approvalId, "inv-1", "SUCCEEDED");

            ApprovalStateMachine.Acquisition acquisition =
                    machine.acquire(request(args(), "inv-2"));

            assertThat(acquisition.acquired()).isFalse();
            assertThat(acquisition.reasonCode())
                    .as("⚠️ 已消费重放必须落到 APPROVAL_REPLAY_REJECTED（不得再放行副作用）")
                    .isEqualTo(ReasonCode.APPROVAL_REPLAY_REJECTED);
        }
    }

    // ── 绑定项 ────────────────────────────────────────────────────

    @Nested
    @DisplayName("绑定项不符")
    class BindingMismatch {

        @Test
        @DisplayName("⚠️ 闭包指纹变化 → APPROVAL_SCOPE_MISMATCH")
        void closureFingerprintChanged() {
            grant(args(), Instant.now().plusSeconds(3600));

            ApprovalStateMachine.AcquireRequest request = new ApprovalStateMachine.AcquireRequest(
                    TENANT, PRINCIPAL, TOOL, "run-1", "inv-1", "inv-1",
                    args(), "cap_fp_v1:changed", AUTHZ_VERSION, Instant.now());

            assertThat(machine.acquire(request).reasonCode())
                    .isEqualTo(ReasonCode.APPROVAL_SCOPE_MISMATCH);
        }

        @Test
        @DisplayName("⚠️ 授权版本变化（撤权）→ APPROVAL_SCOPE_MISMATCH")
        void authzVersionChanged() {
            grant(args(), Instant.now().plusSeconds(3600));

            ApprovalStateMachine.AcquireRequest request = new ApprovalStateMachine.AcquireRequest(
                    TENANT, PRINCIPAL, TOOL, "run-1", "inv-1", "inv-1",
                    args(), FINGERPRINT, AUTHZ_VERSION + 1, Instant.now());

            assertThat(machine.acquire(request).reasonCode())
                    .as("⚠️ 授权版本变化 = 审批所依据的授权已变 → 审批失效")
                    .isEqualTo(ReasonCode.APPROVAL_SCOPE_MISMATCH);
        }

        @Test
        @DisplayName("⚠️ 非本主体的审批 → APPROVAL_NOT_FOUND（不是 SCOPE_MISMATCH）")
        void otherPrincipalSeesNoApproval() {
            ApprovalScope scope = scope();
            ApprovalBindingService.Binding binding = bindingService.generate(scope, args());
            exec.capApprovalPort().grant(new CapApprovalPort.CapApprovalGrant(
                    TENANT, "someone-else", TOOL, "resource",
                    binding.approvalScopeHash(), scope.canonicalText(),
                    binding.effectiveArgsHash(), FINGERPRINT, AUTHZ_VERSION,
                    "approver-1", "SCOPE_ALL", Instant.now().plusSeconds(3600)));

            ApprovalStateMachine.Acquisition acquisition =
                    machine.acquire(request(args(), "inv-1"));

            assertThat(acquisition.acquired()).isFalse();
            assertThat(acquisition.reasonCode())
                    .as("⚠️「有没有我的审批」与「审批是否被篡改」是两件事，不得混为一谈")
                    .isEqualTo(ReasonCode.APPROVAL_NOT_FOUND);
        }
    }

    // ── 状态机 ────────────────────────────────────────────────────

    @Nested
    @DisplayName("§5.3.3 状态迁移")
    class StateTransitions {

        @Test
        @DisplayName("✅ PENDING → RESERVED → INTENT_SUBMITTED → CONSUMED")
        void fullTransitionPath() {
            String approvalId = grant(args(), Instant.now().plusSeconds(3600));
            ApprovalScope scope = scope();
            ApprovalBindingService.Binding binding = bindingService.generate(scope, args());

            assertThat(machine.acquire(request(args(), "inv-1")).acquired()).isTrue();
            assertThat(machine.markIntentSubmitted(TENANT, approvalId, "inv-1")).isTrue();
            assertThat(machine.consume(TENANT, approvalId, "inv-1", "SUCCEEDED")).isTrue();
        }

        @Test
        @DisplayName("✅ 取消是幂等的")
        void cancelIsIdempotent() {
            String approvalId = grant(args(), Instant.now().plusSeconds(3600));

            assertThat(machine.cancel(TENANT, approvalId, "inv-1", "budget")).isTrue();
            assertThat(machine.cancel(TENANT, approvalId, "inv-1", "budget"))
                    .as("⚠️ 重复取消不得产生二次效果，也不得报错")
                    .isFalse();
        }
    }
}
