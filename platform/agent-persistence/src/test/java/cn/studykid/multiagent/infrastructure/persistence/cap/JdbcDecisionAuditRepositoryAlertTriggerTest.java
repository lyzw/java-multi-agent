package cn.studykid.multiagent.infrastructure.persistence.cap;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.cap.AuthorizationDecision;
import cn.studykid.multiagent.core.cap.DecisionAuditEntry;
import cn.studykid.multiagent.core.cap.ReasonCode;

/**
 * 严重拒绝告警触发判据的纯单元测试（P5 · 告警通道最小实现）。
 *
 * <p>口径：仅 {@code DENY} 且原因码定档 {@code CRITICAL} 才外发；
 * WARNING / INFO（配置漂移、业务节流）走常规审计查询，不制造外发噪声。
 */
class JdbcDecisionAuditRepositoryAlertTriggerTest {

    private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");
    private static final String TENANT = "00000000-0000-0000-0000-000000000001";

    private static DecisionAuditEntry entry(AuthorizationDecision.Authorization authz,
                                            ReasonCode code) {
        return new DecisionAuditEntry(
                TENANT, "run-1", "inv-1",
                authz,
                AuthorizationDecision.Execution.DENY,
                AuthorizationDecision.Status.NOT_STARTED,
                AuthorizationDecision.Ledger.PENDING,
                code, 3L, "fp-1", null, null, "p-1", "t-1",
                DecisionAuditEntry.Kind.INVOCATION, code.phase(), NOW);
    }

    @Test
    @DisplayName("✅ DENY + 越权（CRITICAL）→ 外发")
    void denyWithCriticalTriggers() {
        assertThat(JdbcDecisionAuditRepository.isCriticalDeny(
                entry(AuthorizationDecision.Authorization.DENY,
                        ReasonCode.AUTHZ_ROLE_MISSING))).isTrue();
    }

    @Test
    @DisplayName("✅ DENY + EXEC_UNKNOWN（副作用不确定，CRITICAL）→ 外发")
    void denyWithExecUnknownTriggers() {
        assertThat(JdbcDecisionAuditRepository.isCriticalDeny(
                entry(AuthorizationDecision.Authorization.DENY,
                        ReasonCode.EXEC_UNKNOWN))).isTrue();
    }

    @Test
    @DisplayName("⛔ DENY + 预算超限（INFO）→ 不外发")
    void denyWithBudgetExceededDoesNotTrigger() {
        assertThat(JdbcDecisionAuditRepository.isCriticalDeny(
                entry(AuthorizationDecision.Authorization.DENY,
                        ReasonCode.BUDGET_EXCEEDED))).isFalse();
    }

    @Test
    @DisplayName("⛔ DENY + Skill 未启用（WARNING）→ 不外发")
    void denyWithWarningDoesNotTrigger() {
        assertThat(JdbcDecisionAuditRepository.isCriticalDeny(
                entry(AuthorizationDecision.Authorization.DENY,
                        ReasonCode.SKILL_NOT_ENABLED))).isFalse();
    }

    @Test
    @DisplayName("⛔ ALLOW（即使原因码为 SUCCESS）→ 不外发")
    void allowDoesNotTrigger() {
        assertThat(JdbcDecisionAuditRepository.isCriticalDeny(
                entry(AuthorizationDecision.Authorization.ALLOW,
                        ReasonCode.SUCCESS))).isFalse();
    }
}
