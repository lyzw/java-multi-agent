package cn.studykid.multiagent.application.orchestration;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.cap.AuthzVersion;
import cn.studykid.multiagent.core.cap.AuthzUnavailableException;
import cn.studykid.multiagent.core.cap.CapAgentDef;
import cn.studykid.multiagent.core.cap.ReasonCode;
import cn.studykid.multiagent.core.monitoring.AuthzWatermark;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.AuthzVersionPort;
import cn.studykid.multiagent.ports.persistence.CapAgentDefPort;
import cn.studykid.multiagent.ports.security.AuthorizationService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 委派守卫测试（多级 Agent v1.1 · FR-024 / FR-025 · MA-T05～T07 / MA-T09 / MA-T17）。
 *
 * <p>守卫是<b>委派发生前</b>的最后一道闸门：它决定「这次委派允许发生吗」。
 * 任何一条边界不满足都必须在<b>子执行启动前</b>拒绝。
 */
class DelegationGuardTest {

    private static final String TENANT = "tenant-1";
    private static final String PARENT = "parent-agent";
    private static final String CHILD = "child-agent";
    private static final String RUN = "run-1";

    private static final String PARENT_ORCHESTRATION = """
            {"mode":"SEQUENTIAL","childAgentRefs":[{"id":"child-agent","version":1}],
             "maxDelegationDepth":2,"maxChildInvocations":2,"timeoutMs":30000,
             "failurePolicy":"FAIL_FAST"}
            """;

    @Test
    @DisplayName("合法委派 → 允许")
    void validDelegationIsAllowed() {
        DelegationGuard guard = guard(agentDef(PARENT_ORCHESTRATION), 1L, true, true);

        var decision = guard.evaluate(request(1, 0, 10_000L));

        assertThat(decision.isAllowed()).isTrue();
    }

    @Test
    @DisplayName("父 Agent 无编排声明（LEAF）→ 拒绝委派（DELEGATION_NOT_ALLOWED）")
    void leafParentCannotDelegate() {
        DelegationGuard guard = guard(agentDef("{}"), 1L, true, true);

        var decision = guard.evaluate(request(1, 0, 10_000L));

        assertThat(decision.isAllowed()).isFalse();
        assertThat(decision.reasonCode()).isEqualTo(ReasonCode.DELEGATION_NOT_ALLOWED);
    }

    @Test
    @DisplayName("目标未在 childAgentRefs 中声明 → 拒绝（DELEGATION_TARGET_NOT_DECLARED · MA-T05）")
    void undeclaredTargetIsRejected() {
        DelegationGuard guard = guard(agentDef(PARENT_ORCHESTRATION), 1L, true, true);

        var decision = guard.evaluate(new DelegationGuard.DelegationRequest(
                context(1L), RUN, PARENT, 1, "other-agent", 1, 2, 0, 10_000L));

        assertThat(decision.isAllowed()).isFalse();
        assertThat(decision.reasonCode()).isEqualTo(ReasonCode.DELEGATION_TARGET_NOT_DECLARED);
    }

    @Test
    @DisplayName("目标版本漂移 → 拒绝（RESOURCE_VERSION_MISMATCH · MA-T08）")
    void versionDriftIsRejected() {
        DelegationGuard guard = guard(agentDef(PARENT_ORCHESTRATION), 1L, true, true);

        var decision = guard.evaluate(new DelegationGuard.DelegationRequest(
                context(1L), RUN, PARENT, 1, CHILD, 2, 2, 0, 10_000L));

        assertThat(decision.isAllowed()).isFalse();
        assertThat(decision.reasonCode()).isEqualTo(ReasonCode.RESOURCE_VERSION_MISMATCH);
    }

    @Test
    @DisplayName("超深度 → 拒绝（DELEGATION_DEPTH_EXCEEDED · MA-T09）")
    void exceedingDepthIsRejected() {
        DelegationGuard guard = guard(agentDef(PARENT_ORCHESTRATION), 1L, true, true);

        var decision = guard.evaluate(request(3, 0, 10_000L));

        assertThat(decision.isAllowed()).isFalse();
        assertThat(decision.reasonCode()).isEqualTo(ReasonCode.DELEGATION_DEPTH_EXCEEDED);
    }

    @Test
    @DisplayName("超子调用次数 → 拒绝（CHILD_INVOCATION_LIMIT_EXCEEDED · MA-T09）")
    void exceedingInvocationCountIsRejected() {
        DelegationGuard guard = guard(agentDef(PARENT_ORCHESTRATION), 1L, true, true);

        var decision = guard.evaluate(request(2, 2, 10_000L));

        assertThat(decision.isAllowed()).isFalse();
        assertThat(decision.reasonCode())
                .isEqualTo(ReasonCode.CHILD_INVOCATION_LIMIT_EXCEEDED);
    }

    @Test
    @DisplayName("剩余时间耗尽 → 拒绝（DELEGATION_TIMEOUT）")
    void exhaustedTimeIsRejected() {
        DelegationGuard guard = guard(agentDef(PARENT_ORCHESTRATION), 1L, true, true);

        var decision = guard.evaluate(request(2, 0, 0L));

        assertThat(decision.isAllowed()).isFalse();
        assertThat(decision.reasonCode()).isEqualTo(ReasonCode.DELEGATION_TIMEOUT);
    }

    @Test
    @DisplayName("剩余时间超过本级声明预算 → 允许（按 min(剩余, 本级 timeout) 封顶，不放大）")
    void remainingTimeIsCappedByLevelTimeout() {
        // ⚠️ 「根 Run 剩余时间 > 本级 timeout」是**常态**（子预算本就是子集）。
        //    早期实现把它判成超时 —— 那会让**所有**经闸门的委派都被拒
        //    （闸门此刻拿不到根 Run 的真实剩余时间，只能传上界）。
        //    正确语义：有效预算 = min(剩余, 本级 timeoutMs)，本级 timeout 仍是上界。
        DelegationGuard guard = guard(agentDef(PARENT_ORCHESTRATION), 1L, true, true);

        var decision = guard.evaluate(request(2, 0, 60_000L));

        assertThat(decision.isAllowed()).isTrue();
    }

    @Test
    @DisplayName("剩余时间为负 → 拒绝（DELEGATION_TIMEOUT）")
    void negativeRemainingTimeIsRejected() {
        DelegationGuard guard = guard(agentDef(PARENT_ORCHESTRATION), 1L, true, true);

        var decision = guard.evaluate(request(2, 0, -1L));

        assertThat(decision.isAllowed()).isFalse();
        assertThat(decision.reasonCode()).isEqualTo(ReasonCode.DELEGATION_TIMEOUT);
    }

    // ── 有效时间预算必须**流出**守卫（第六批）────────────────────────
    //
    // ⚠️ 为什么这三条是独立的：守卫若只返回「允许/拒绝」，
    //    调用方就只能拿**本级声明值**去下传 —— 那样根 Run 的剩余时间
    //    只是「参与了一次判定」却毫无强制力，子 Agent 仍会跑满自己的
    //    timeoutMs，「全调用树共享超时上限」被架空。

    @Test
    @DisplayName("✅ 有效预算 = min(根 Run 剩余, 本级 timeout)：根剩余更短时按根剩余封顶")
    void effectiveBudgetIsCappedByRootRemainingTime() {
        // ⚠️ 根 Run 只剩 10 秒，而本级声明 30 秒 → 只能给 10 秒。
        //    若按本级声明值下传，子 Agent 会跑 30 秒 —— 根 Run 早就该结束了。
        DelegationGuard guard = guard(agentDef(PARENT_ORCHESTRATION), 1L, true, true);

        var verdict = guard.evaluateWithBudget(request(2, 0, 10_000L));

        assertThat(verdict.allowed()).isTrue();
        assertThat(verdict.effectiveTimeoutMs())
                .as("⚠️ 根 Run 剩余时间必须封顶子执行预算")
                .isEqualTo(10_000L);
    }

    @Test
    @DisplayName("✅ 有效预算 = min(根 Run 剩余, 本级 timeout)：本级更短时按本级封顶")
    void effectiveBudgetIsCappedByLevelTimeout() {
        // ⚠️ 根 Run 还剩 60 秒，本级只声明 30 秒 → 本级声明是上界，
        //    不得被「根剩余更长」放大（配置里的 timeout 不得形同虚设）。
        DelegationGuard guard = guard(agentDef(PARENT_ORCHESTRATION), 1L, true, true);

        var verdict = guard.evaluateWithBudget(request(2, 0, 60_000L));

        assertThat(verdict.allowed()).isTrue();
        assertThat(verdict.effectiveTimeoutMs())
                .as("本级声明的 timeout 永远是上界")
                .isEqualTo(30_000L);
    }

    @Test
    @DisplayName("✅ 被拒时有效预算为 0（不得给出非零值误导调用方继续）")
    void rejectedDelegationCarriesZeroEffectiveBudget() {
        DelegationGuard guard = guard(agentDef(PARENT_ORCHESTRATION), 1L, true, true);

        var verdict = guard.evaluateWithBudget(request(2, 0, 0L));

        assertThat(verdict.allowed()).isFalse();
        assertThat(verdict.effectiveTimeoutMs())
                .as("⚠️ 拒绝时根本没有子执行发生，预算值无意义")
                .isZero();
    }

    @Test
    @DisplayName("预算耗尽 → 拒绝（BUDGET_EXCEEDED · MA-T17）")
    void exhaustedBudgetIsRejected() {
        DelegationGuard guard = guard(agentDef(PARENT_ORCHESTRATION), 1L, false, true);

        var decision = guard.evaluate(request(2, 0, 10_000L));

        assertThat(decision.isAllowed()).isFalse();
        assertThat(decision.reasonCode()).isEqualTo(ReasonCode.BUDGET_EXCEEDED);
    }

    @Test
    @DisplayName("授权版本陈旧（已撤权）→ fail-closed（AUTHZ_STALE · MA-T07）")
    void staleAuthzVersionIsRejected() {
        // 权威版本已 bump 到 2，而上下文携带的是 1
        DelegationGuard guard = guard(agentDef(PARENT_ORCHESTRATION), 2L, true, true);

        var decision = guard.evaluate(request(2, 0, 10_000L));

        assertThat(decision.isAllowed()).isFalse();
        assertThat(decision.reasonCode()).isEqualTo(ReasonCode.AUTHZ_STALE);
    }

    @Test
    @DisplayName("授权版本读不到 → fail-closed（AUTHZ_UNAVAILABLE）")
    void unavailableAuthzVersionIsRejected() {
        DelegationGuard guard = new DelegationGuard(
                agentDefs(agentDef(PARENT_ORCHESTRATION)),
                authorization(true),
                new AuthzVersionPort() {
                    @Override
                    public @NonNull Optional<AuthzVersion> find(String tenantId, String principalId) {
                        return Optional.empty();
                    }

                    @Override
                    public @NonNull AuthzVersion require(String tenantId, String principalId) {
                        throw new AuthzUnavailableException(tenantId, principalId,
                                "版本服务不可用", null);
                    }

                    @Override
                    public @NonNull AuthzVersion initialize(String tenantId, String principalId) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public long bump(String tenantId, String principalId) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public @NonNull AuthzWatermark
                            watermark(String tenantId) {
                        throw new UnsupportedOperationException();
                    }
                },
                (tenantId, runId) -> true);

        var decision = guard.evaluate(request(2, 0, 10_000L));

        assertThat(decision.isAllowed()).isFalse();
        assertThat(decision.reasonCode()).isEqualTo(ReasonCode.AUTHZ_UNAVAILABLE);
    }

    @Test
    @DisplayName("主体无权委派到目标 → 拒绝（AUTHZ_ROLE_MISSING · MA-T06）")
    void unauthorizedDelegationIsRejected() {
        DelegationGuard guard = guard(agentDef(PARENT_ORCHESTRATION), 1L, true, false);

        var decision = guard.evaluate(request(2, 0, 10_000L));

        assertThat(decision.isAllowed()).isFalse();
        assertThat(decision.reasonCode()).isEqualTo(ReasonCode.AUTHZ_ROLE_MISSING);
    }

    // ── 夹具 ──────────────────────────────────────────────────────

    private static DelegationGuard.DelegationRequest request(int depth, int soFar, long remaining) {
        return new DelegationGuard.DelegationRequest(context(1L), RUN, PARENT, 1,
                CHILD, 1, depth, soFar, remaining);
    }

    private static TenantContext context(long authzVersion) {
        return new TenantContext(TENANT, "principal-1", Set.of("TEACHER"), authzVersion);
    }

    private static CapAgentDef agentDef(String orchestration) {
        return new CapAgentDef(TENANT, PARENT, 1, PARENT, "父 Agent",
                List.of("TEACHER"), List.of(), List.of(), List.of(),
                "{}", orchestration, "fp-parent", 1, Instant.EPOCH, Instant.EPOCH);
    }

    private static CapAgentDefPort agentDefs(CapAgentDef def) {
        return new CapAgentDefPort() {
            @Override
            public boolean insert(CapAgentDef d) {
                return true;
            }

            @Override
            public @NonNull Optional<CapAgentDef> find(String tenantId, String agentId, int version) {
                return TENANT.equals(tenantId) && PARENT.equals(agentId) && version == 1
                        ? Optional.of(def)
                        : Optional.empty();
            }

            @Override
            public @NonNull Optional<CapAgentDef> findLatest(String tenantId, String agentId) {
                return find(tenantId, agentId, 1);
            }

            @Override
            public @NonNull Optional<CapAgentDef> findLatestPublished(String tenantId,
                                                                      String agentId) {
                return find(tenantId, agentId, 1);
            }

            @Override
            public @NonNull List<CapAgentDef> listLatestPerAgent(String tenantId) {
                return List.of(def);
            }
        };
    }

    private static AuthorizationService authorization(boolean permitted) {
        return new AuthorizationService() {
            @Override
            public boolean isAuthorized(TenantContext context, String action, String resourceId) {
                return permitted;
            }

            @Override
            public long currentAuthzVersion(String tenantId, String principalId) {
                return 1L;
            }

            @Override
            public @NonNull Set<String> rolesOf(TenantContext context) {
                return context.roles();
            }
        };
    }

    private static AuthzVersionPort authzVersions(long current) {
        return new AuthzVersionPort() {
            @Override
            public @NonNull Optional<AuthzVersion> find(String tenantId, String principalId) {
                return Optional.of(new AuthzVersion(tenantId, principalId, current, Instant.EPOCH));
            }

            @Override
            public @NonNull AuthzVersion require(String tenantId, String principalId) {
                return new AuthzVersion(tenantId, principalId, current, Instant.EPOCH);
            }

            @Override
            public @NonNull AuthzVersion initialize(String tenantId, String principalId) {
                return new AuthzVersion(tenantId, principalId, current, Instant.EPOCH);
            }

            @Override
            public long bump(String tenantId, String principalId) {
                return current + 1;
            }

            @Override
            public @NonNull AuthzWatermark
                    watermark(String tenantId) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static DelegationGuard guard(CapAgentDef parentDef, long currentAuthzVersion,
                                         boolean budgetRemaining, boolean permitted) {
        return new DelegationGuard(agentDefs(parentDef), authorization(permitted),
                authzVersions(currentAuthzVersion),
                (tenantId, runId) -> budgetRemaining);
    }
}
