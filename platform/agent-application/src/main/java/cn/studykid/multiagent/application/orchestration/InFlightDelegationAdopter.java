package cn.studykid.multiagent.application.orchestration;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.core.cap.ReasonCode;
import cn.studykid.multiagent.core.orchestration.AgentInvocation;
import cn.studykid.multiagent.core.orchestration.AgentInvocationStatus;
import cn.studykid.multiagent.ports.persistence.AgentInvocationPort;

/**
 * 接管时收敛在途委派（多级 Agent v1.1 · FR-022 / FR-026 · MA-T12 · P3-MA-02）。
 *
 * <p>⚠️ <b>它补的是哪个洞</b>：Worker 崩溃后由新世代接管，
 * 但 {@code agent_invocation} 里的在途记录仍带着<b>旧世代</b>的 token：
 * <ul>
 *   <li>新世代<b>写不了</b>终态（CAS 被 fencing 拦下）；</li>
 *   <li>旧世代<b>也</b>写不了（它已被接管）。</li>
 * </ul>
 * 结果那条记录<b>永远悬在 RUNNING</b>：无人能收敛它，重放时只能拿到
 * 「既有 RUNNING 状态」→ 父 Agent 收到「执行失败」，而真实原因（Worker 崩溃）
 * 被掩盖成了一次普通的子调用失败。
 *
 * <p>⚠️ <b>三步动作</b>：
 * <ol>
 *   <li><b>提升世代令牌</b>（{@code adoptInFlight}）：让新世代获得写权限。
 *       ⚠️ 这<b>不是</b>放弃 fencing —— 旧世代带着更小的 token 依然被拒
 *       （MA-T12 语义不变）；</li>
 *   <li><b>收敛为 UNKNOWN</b>：子执行是<b>同步</b>跑在委派调用栈里的，
 *       Worker 崩溃意味着子执行也中断 → 结果<b>不可知</b>
 *       （可能已产生副作用）→ 唯一诚实的结论是 UNKNOWN（MA-T13，走对账）；</li>
 *   <li><b>绝不重放</b>：重跑子 Agent 可能产生<b>重复副作用</b>（Sev-0），
 *       所以宁可交人工对账，也不自动重试。</li>
 * </ol>
 *
 * <p>⚠️ <b>为什么收敛成 UNKNOWN 而不是 FAILED</b>：FAILED 意味着
 * 「确定失败、无副作用」；而崩溃中断时<b>副作用可能已经发生</b> ——
 * 报 FAILED 会让对账流程以为「什么都没发生」而直接放行。
 */
public class InFlightDelegationAdopter {

    private static final Logger log = LoggerFactory.getLogger(InFlightDelegationAdopter.class);

    private final AgentInvocationPort invocations;
    private final Clock clock;

    public InFlightDelegationAdopter(@NonNull AgentInvocationPort invocations,
                                     @NonNull Clock clock) {
        this.invocations = invocations;
        this.clock = clock;
    }

    /**
     * 接管该 Run 的在途委派。
     *
     * <p>⚠️ <b>可重复调用</b>：首次领取（无在途记录）时影响 0 行，
     * 因此无需区分「首次」与「接管」—— 区别由数据本身表达。
     *
     * @param tenantId       租户标识
     * @param runId          根 Run 标识
     * @param newFencingToken 新世代令牌
     * @return 收敛为 UNKNOWN 的记录条数
     */
    public int adopt(@NonNull String tenantId,
                     @NonNull String runId,
                     long newFencingToken) {
        int promoted;
        try {
            promoted = invocations.adoptInFlight(tenantId, runId, newFencingToken);
        } catch (RuntimeException e) {
            // ⚠️ 提升失败 → 不能收敛（没有写权限）→ 记录留在 RUNNING 等人工处置。
            //    不得为了「干净」而强行写终态 —— 那样可能覆盖别人的结果。
            log.error("在途委派接管失败（记录将保持 RUNNING 待人工处置）: run={}", runId, e);
            return 0;
        }
        if (promoted == 0) {
            return 0;
        }
        log.info("接管提升在途委派: run={}, 记录数={}, 新世代={}", runId, promoted,
                newFencingToken);

        int converged = 0;
        for (AgentInvocation record : listInFlight(tenantId, runId)) {
            if (record.status() != AgentInvocationStatus.RUNNING) {
                continue;
            }
            if (convergeToUnknown(tenantId, record, newFencingToken)) {
                converged++;
            }
        }
        return converged;
    }

    /** 列出该 Run 的委派记录（读取失败按「无记录」处理，不阻断接管）。 */
    private List<AgentInvocation> listInFlight(@NonNull String tenantId,
                                               @NonNull String runId) {
        try {
            return invocations.listByRun(tenantId, runId);
        } catch (RuntimeException e) {
            log.error("在途委派列表读取失败: run={}", runId, e);
            return List.of();
        }
    }

    /**
     * 把一条在途记录收敛为 UNKNOWN。
     *
     * @return 是否收敛成功
     */
    private boolean convergeToUnknown(@NonNull String tenantId,
                                      @NonNull AgentInvocation record,
                                      long newFencingToken) {
        try {
            AgentInvocationPort.TerminalWriteOutcome outcome = invocations.markTerminal(
                    tenantId, record.invocationId(), AgentInvocationStatus.UNKNOWN,
                    ReasonCode.EXEC_UNKNOWN.name(), null, clock.instant(), newFencingToken);
            if (outcome == AgentInvocationPort.TerminalWriteOutcome.WRITTEN) {
                log.warn("在途委派收敛为 UNKNOWN（Worker 崩溃/接管，结果不可知）: invocation={}",
                        record.invocationId());
                return true;
            }
            // ⚠️ ALREADY_TERMINAL：已被别的路径收敛 → 不算本次收敛
            // ⚠️ TOKEN_EXPIRED：token 又被更新了（有更新的世代）→ 交给那个世代处理
            log.info("在途委派未收敛: invocation={}, outcome={}",
                    record.invocationId(), outcome);
            return false;
        } catch (RuntimeException e) {
            log.error("在途委派收敛失败（记录保持 RUNNING 待人工处置）: invocation={}",
                    record.invocationId(), e);
            return false;
        }
    }
}
