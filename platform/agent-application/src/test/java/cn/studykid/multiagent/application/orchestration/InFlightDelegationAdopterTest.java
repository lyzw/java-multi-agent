package cn.studykid.multiagent.application.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.cap.ReasonCode;
import cn.studykid.multiagent.core.orchestration.AgentInvocation;
import cn.studykid.multiagent.core.orchestration.AgentInvocationStatus;
import cn.studykid.multiagent.ports.persistence.AgentInvocationPort;

/**
 * 在途委派接管测试（多级 Agent v1.1 · FR-022 / FR-026 · MA-T12 · P3-MA-02）。
 *
 * <p><b>⚠️ 本类存在的唯一理由</b>：Worker 崩溃后由新世代接管，
 * 而在途委派记录仍带<b>旧世代</b>的 token ——
 * 新世代写不了终态（CAS 被拒）、旧世代也写不了（已被接管），
 * 那条记录就<b>永远悬在 RUNNING</b>：无人能收敛它，
 * 重放时只能拿到「既有 RUNNING 状态」→ 父 Agent 收到「执行失败」，
 * 而真实原因（Worker 崩溃）被掩盖成一次普通的子调用失败。
 *
 * <p><b>本类的判据</b>：
 * <ul>
 *   <li>A-1：接管后，在途记录的世代令牌被提升到新世代（否则新世代写不了终态）；</li>
 *   <li>A-2：在途记录被收敛为 <b>UNKNOWN</b>（不是 FAILED）——
 *       崩溃时子执行的副作用<b>可能已经发生</b>，报 FAILED 会让对账以为
 *       「什么都没发生」而直接放行；</li>
 *   <li>A-3：<b>终态记录不得被改写</b>（终态是权威事实，接管方没资格改）；</li>
 *   <li>A-4：提升 token <b>不是</b>放弃 fencing —— 旧世代带更小的 token
 *       依然写不了终态（MA-T12 语义不变）。</li>
 * </ul>
 */
class InFlightDelegationAdopterTest {

    private static final String TENANT = "t-a";
    private static final String RUN = "run-1";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    // ── 替身 ────────────────────────────────────────────────────────

    /** 内存版委派记录仓储（与 JDBC 实现同口径）。 */
    private static final class InMemoryInvocations implements AgentInvocationPort {
        final Map<String, AgentInvocation> rows = new LinkedHashMap<>();

        @Override
        public boolean insert(@NonNull AgentInvocation invocation) {
            return rows.putIfAbsent(invocation.invocationId(), invocation) == null;
        }

        @Override
        public @NonNull Optional<AgentInvocation> find(@NonNull String tenantId,
                                                      @NonNull String invocationId) {
            return Optional.ofNullable(rows.get(invocationId));
        }

        @Override
        public @NonNull List<AgentInvocation> listByRun(@NonNull String tenantId,
                                                        @NonNull String runId) {
            return rows.values().stream().filter(r -> r.runId().equals(runId)).toList();
        }

        @Override
        public int adoptInFlight(@NonNull String tenantId, @NonNull String runId,
                                 long newFencingToken) {
            int affected = 0;
            for (Map.Entry<String, AgentInvocation> entry : rows.entrySet()) {
                AgentInvocation row = entry.getValue();
                if (row.runId().equals(runId)
                        && !row.status().isTerminal()
                        && row.fencingToken() < newFencingToken) {
                    entry.setValue(withToken(row, newFencingToken));
                    affected++;
                }
            }
            return affected;
        }

        @Override
        public @NonNull TerminalWriteOutcome markTerminal(@NonNull String tenantId,
                                                          @NonNull String invocationId,
                                                          @NonNull AgentInvocationStatus status,
                                                          String reasonCode, String outputDigest,
                                                          Instant finishedAt, long fencingToken) {
            AgentInvocation row = rows.get(invocationId);
            if (row == null) {
                return TerminalWriteOutcome.NOT_FOUND;
            }
            if (row.fencingToken() != fencingToken) {
                return TerminalWriteOutcome.TOKEN_EXPIRED;
            }
            if (row.status().isTerminal()) {
                return TerminalWriteOutcome.ALREADY_TERMINAL;
            }
            rows.put(invocationId, new AgentInvocation(row.invocationId(), row.tenantId(),
                    row.runId(), row.parentInvocationId(), row.parentAgentId(),
                    row.parentAgentVersion(), row.targetAgentId(), row.targetAgentVersion(),
row.requestedTargetAgentId(),
                    status, row.depth(), row.traceId(), row.inputDigest(), outputDigest,
                    row.startedAt(), finishedAt, reasonCode, row.fencingToken()));
            return TerminalWriteOutcome.WRITTEN;
        }
    }

    private static AgentInvocation withToken(AgentInvocation row, long token) {
        return new AgentInvocation(row.invocationId(), row.tenantId(), row.runId(),
                row.parentInvocationId(), row.parentAgentId(), row.parentAgentVersion(),
                row.targetAgentId(), row.targetAgentVersion(),
row.requestedTargetAgentId(), row.status(), row.depth(),
                row.traceId(), row.inputDigest(), row.outputDigest(), row.startedAt(),
                row.finishedAt(), row.reasonCode(), token);
    }

    private static AgentInvocation invocation(String id,
                                              AgentInvocationStatus status,
                                              long fencingToken) {
        // ⚠️ 分量顺序：... inputDigest, outputDigest, startedAt, finishedAt, reasonCode
        return new AgentInvocation(id, TENANT, RUN, null, "parent-agent", 1,
                "child-agent", 1, status, 2, "trace-1", "digest-in", null,
                Instant.EPOCH, null, null, fencingToken);
    }

    private static InFlightDelegationAdopter adopter(InMemoryInvocations invocations) {
        return new InFlightDelegationAdopter(invocations,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // ── 用例 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("✅ 接管 → 在途记录被提升世代并收敛为 UNKNOWN（不再悬着）")
    void adoptConvergesInFlightToUnknown() {
        InMemoryInvocations invocations = new InMemoryInvocations();
        invocations.rows.put("inv-1", invocation("inv-1", AgentInvocationStatus.RUNNING, 1L));

        int converged = adopter(invocations).adopt(TENANT, RUN, 2L);

        assertThat(converged).as("应收敛一条在途记录").isEqualTo(1);
        AgentInvocation record = invocations.rows.get("inv-1");
        assertThat(record.status())
                .as("⚠️⚠️ 必须是 UNKNOWN —— 崩溃时子执行的副作用**可能已经发生**，"
                        + "报 FAILED 会让对账以为「什么都没发生」而直接放行")
                .isEqualTo(AgentInvocationStatus.UNKNOWN);
        assertThat(record.reasonCode()).isEqualTo(ReasonCode.EXEC_UNKNOWN.name());
        assertThat(record.fencingToken())
                .as("A-1：必须提升到新世代，否则新世代写不了终态")
                .isEqualTo(2L);
        assertThat(record.finishedAt()).as("终态必须带完成时间").isEqualTo(NOW);
    }

    @Test
    @DisplayName("✅ 接管 → 终态记录不得被改写（终态是权威事实）")
    void terminalRecordsAreNotRewritten() {
        InMemoryInvocations invocations = new InMemoryInvocations();
        invocations.rows.put("inv-1",
                invocation("inv-1", AgentInvocationStatus.SUCCEEDED, 1L));

        int converged = adopter(invocations).adopt(TENANT, RUN, 2L);

        assertThat(converged).as("终态记录不该被接管流程处理").isZero();
        AgentInvocation record = invocations.rows.get("inv-1");
        assertThat(record.status())
                .as("⚠️ 终态不得被改写（接管方没资格改别人的结论）")
                .isEqualTo(AgentInvocationStatus.SUCCEEDED);
        assertThat(record.fencingToken())
                .as("终态记录的 token 也不动")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("✅ 提升 token ≠ 放弃 fencing：旧世代仍写不了终态（MA-T12 不变）")
    void staleGenerationStillCannotWriteTerminal() {
        InMemoryInvocations invocations = new InMemoryInvocations();
        invocations.rows.put("inv-1", invocation("inv-1", AgentInvocationStatus.RUNNING, 1L));

        adopter(invocations).adopt(TENANT, RUN, 2L);

        AgentInvocationPort.TerminalWriteOutcome outcome = invocations.markTerminal(
                TENANT, "inv-1", AgentInvocationStatus.SUCCEEDED, null, null, NOW, 1L);
        assertThat(outcome)
                .as("⚠️⚠️ 旧世代带过期 token 提交必须被拒 —— "
                        + "接管不能变成「旧世代也能写」的漏洞")
                .isEqualTo(AgentInvocationPort.TerminalWriteOutcome.TOKEN_EXPIRED);
    }

    @Test
    @DisplayName("✅ 首次领取（无在途记录）→ 影响 0 行，不报错")
    void firstClaimAdoptsNothing() {
        InMemoryInvocations invocations = new InMemoryInvocations();

        int converged = adopter(invocations).adopt(TENANT, RUN, 1L);

        assertThat(converged).isZero();
    }
}
