package cn.studykid.multiagent.application.cap;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.cap.EffectiveClosure;
import cn.studykid.multiagent.core.cap.ReasonCode;
import cn.studykid.multiagent.ports.persistence.CapApprovalPort;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 委派链上的<b>审批范围绑定</b>测试（多级 Agent v1.1 · MA-T18）。
 *
 * <p>MA-T18 的原文：
 * 「委派目标 / 有效参数 / 闭包变化后复用旧审批 → 审批范围不匹配，拒绝执行」。
 *
 * <p>⚠️ <b>本类要证明的是「审批绑定的那一个指纹真的会变」</b>：
 * {@link ApprovalStateMachine} 早就按闭包指纹校验审批绑定，
 * 但「闭包指纹」本身是否覆盖了<b>编排声明</b>，决定了这条校验是否有效 ——
 * 若编排声明不在指纹里，那么「闭包变了」这件事在指纹层面<b>根本不可见</b>，
 * 绑定旧指纹的旧审批就会被继续复用，而审计看到的仍是「配置没动过」。
 *
 * <p>⚠️ <b>正向放行与反向拒绝必须成对</b>：
 * 每个「旧审批被拒」旁边都有一个「指纹未变 → 旧审批仍可用」，
 * 否则「全都拒」也能让本类全绿，而那不是正确性。
 */
class DelegationApprovalScopeTest {

    private static final String TENANT = "t-deleg-approval";
    private static final String PRINCIPAL = "worker";
    private static final String TOOL = "send_email";
    private static final long AUTHZ_VERSION = 7L;

    private static final String PARENT_WITH_CHILD_A = """
            {"mode":"SEQUENTIAL","childAgentRefs":[{"id":"child-a","version":1}]}
            """;

    private static final String PARENT_WITH_CHILD_B = """
            {"mode":"SEQUENTIAL","childAgentRefs":[{"id":"child-b","version":1}]}
            """;

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

    private static Map<String, Object> args() {
        return Map.of("to", "a@example.com");
    }

    private ApprovalScope scope() {
        return ApprovalScope.of(TOOL,
                Map.of("to", new ApprovalScope.Constraint("eq", "a@example.com")));
    }

    /** 按给定闭包指纹授予审批（模拟「审批台在某个闭包状态下批的」）。 */
    private void grant(String closureFingerprint) {
        ApprovalScope scope = scope();
        ApprovalBindingService.Binding binding = bindingService.generate(scope, args());
        exec.capApprovalPort().grant(new CapApprovalPort.CapApprovalGrant(
                TENANT, PRINCIPAL, TOOL, "resource",
                binding.approvalScopeHash(), scope.canonicalText(), binding.effectiveArgsHash(),
                closureFingerprint, AUTHZ_VERSION, "approver-1", "SCOPE_ALL",
                Instant.now().plusSeconds(3600)));
    }

    private ApprovalStateMachine.AcquireRequest request(String closureFingerprint,
                                                       String invocationId) {
        return new ApprovalStateMachine.AcquireRequest(
                TENANT, PRINCIPAL, TOOL, "run-1", invocationId, invocationId,
                args(), closureFingerprint, AUTHZ_VERSION, Instant.now());
    }

    /** 父 Agent 的闭包指纹（委派方）。 */
    private String parentFingerprint(String parentOrchestration) {
        CapFixture fixture = new CapFixture()
                .withAgent(TENANT, "parent-agent", 1, List.of(), "{}", parentOrchestration)
                .withAgent(TENANT, "child-a", 1, List.of(), "{}", "{}")
                .withAgent(TENANT, "child-b", 1, List.of(), "{}", "{}");
        EffectiveClosure closure = resolver(fixture).resolve(TENANT, "parent-agent", 1);
        return ClosureFingerprint.of(closure);
    }

    /** 子 Agent 的闭包指纹（被委派方；审批就绑在它上面）。 */
    private String childFingerprint(String childOrchestration) {
        CapFixture fixture = new CapFixture()
                .withAgent(TENANT, "child-a", 1, List.of(), "{}", childOrchestration);
        EffectiveClosure closure = resolver(fixture).resolve(TENANT, "child-a", 1);
        return ClosureFingerprint.of(closure);
    }

    private static PermissionResolver resolver(CapFixture fixture) {
        return new PermissionResolver(fixture.agentDefs(), fixture.skillDefs(),
                fixture.toolDefs(), fixture.serverDefs(), fixture.publishedVersions(),
                fixture.manifest());
    }

    // ── 用例 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("✅ 闭包未变 → 旧审批仍可用（正向：否则本类「全都拒」也能全绿）")
    void unchangedClosureKeepsApprovalUsable() {
        String fingerprint = childFingerprint("{\"mode\":\"SEQUENTIAL\","
                + "\"resultContractRef\":\"contract@1\"}");
        grant(fingerprint);

        ApprovalStateMachine.Acquisition acquisition =
                machine.acquire(request(fingerprint, "inv-1"));

        assertThat(acquisition.acquired())
                .as("⛔ 闭包没变却连合法审批也拒：审批链路整体失效")
                .isTrue();
    }

    @Test
    @DisplayName("⛔ 闭包变化（子 Agent 编排漂移）后复用旧审批 → APPROVAL_SCOPE_MISMATCH")
    void staleApprovalCannotBeReusedAfterClosureChange() {
        String before = childFingerprint("{\"mode\":\"SEQUENTIAL\","
                + "\"resultContractRef\":\"contract@1\"}");
        String after = childFingerprint("{\"mode\":\"SEQUENTIAL\","
                + "\"resultContractRef\":\"contract@2\"}");
        grant(before);

        ApprovalStateMachine.Acquisition acquisition =
                machine.acquire(request(after, "inv-1"));

        assertThat(acquisition.acquired())
                .as("⛔ 闭包已变却复用了旧审批：旧的授权范围被静默扩大（MA-T18）")
                .isFalse();
        assertThat(acquisition.reasonCode())
                .as("⛔ 必须落到 APPROVAL_SCOPE_MISMATCH：审批范围不匹配需可区分")
                .isEqualTo(ReasonCode.APPROVAL_SCOPE_MISMATCH);
    }

    @Test
    @DisplayName("⛔ 委派目标变化后复用旧审批 → APPROVAL_SCOPE_MISMATCH")
    void staleApprovalCannotBeReusedAfterDelegationTargetChange() {
        String before = parentFingerprint(PARENT_WITH_CHILD_A);
        String after = parentFingerprint(PARENT_WITH_CHILD_B);
        grant(before);

        ApprovalStateMachine.Acquisition acquisition =
                machine.acquire(request(after, "inv-1"));

        assertThat(acquisition.acquired())
                .as("⛔ 委派目标已变却复用了旧审批：批的是 A，跑的却是 B")
                .isFalse();
        assertThat(acquisition.reasonCode())
                .isEqualTo(ReasonCode.APPROVAL_SCOPE_MISMATCH);
    }

    @Test
    @DisplayName("⛔ 父 Agent 的审批不得被子 Agent 的调用复用（委派关系≠授权关系）")
    void parentApprovalCannotBeReusedByChild() {
        String parentFp = parentFingerprint(PARENT_WITH_CHILD_A);
        String childFp = childFingerprint("{}");
        grant(parentFp);

        ApprovalStateMachine.Acquisition acquisition =
                machine.acquire(request(childFp, "inv-1"));

        assertThat(acquisition.acquired())
                .as("⛔ 父的审批被子复用：父能批的工具，子不批也能用")
                .isFalse();
        assertThat(acquisition.reasonCode())
                .isEqualTo(ReasonCode.APPROVAL_SCOPE_MISMATCH);
    }
}
