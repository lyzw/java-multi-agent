package cn.studykid.multiagent.application.cap;

import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.core.cap.AuthorizationDecision;
import cn.studykid.multiagent.core.cap.ReasonCode;
import cn.studykid.multiagent.core.cap.ResultEvent;
import cn.studykid.multiagent.core.run.ToolOperationState;
import cn.studykid.multiagent.ports.persistence.ResultEventPort;
import cn.studykid.multiagent.ports.persistence.RunToolLedgerPort;

/**
 * 幂等键校验（P2-T2 · §5.3 步骤 8）。
 *
 * <p>§5.3 步骤 8 的四种情形，本类逐条实现：
 * <table border="1">
 *   <caption>§5.3 步骤 8</caption>
 *   <tr><th>情形</th><th>本类结论</th></tr>
 *   <tr><td>已完成</td><td>返回首次结果（含首次 reasonCode），<b>不重复执行</b></td></tr>
 *   <tr><td>进行中</td><td>返回「进行中」，<b>不重复执行</b></td></tr>
 *   <tr><td>同键不同参数</td><td>{@code IDEMPOTENCY_CONFLICT}</td></tr>
 *   <tr><td>幂等存储不可用</td><td>fail-closed（{@code IDEMPOTENCY_UNAVAILABLE}）</td></tr>
 * </table>
 *
 * <p>⚠️ <b>「键已存在」本身就禁止重新执行</b>（§5.3.4「同幂等键重试 → 禁止重复副作用」）。
 * 重复扣款、重复写外部系统这类 Sev-0 事故，根因都是「以为重试是安全的」。
 * 故本类<b>只有一种</b>放行结论：{@link Outcome#PROCEED} —— 且仅当<b>键不存在</b>时出现。
 *
 * <p>⚠️ <b>fail-closed 的具体含义</b>：幂等存储读不到时，
 * <b>无法证明本调用没有在别处执行过</b> → 拒绝执行，
 * 而不是「先执行了再说」。这是 §5.3 步骤 8「幂等存储不可用 → fail-closed」的要求。
 *
 * <p>⚠️ <b>「进行中」的处置</b>：本类返回「进行中」而<b>不阻塞</b>线程
 * （§5.3 步骤 8 允许「阻塞或返回进行中」）——
 * Worker 是批处理进程，阻塞会占住租约并拖垮整个批次。
 * 代价是：上一次尝试若崩溃留下悬挂的 {@code IN_FLIGHT} 行，重试会被挡住。
 * 该恢复场景属 <b>P3</b> 的 COMP-* 故障恢复范围（§5.3.5 跨系统恢复表），
 * 本阶段<b>刻意</b>选择「宁可挡住也不重复执行」。
 */
public class IdempotencyGuard {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyGuard.class);

    private final RunToolLedgerPort ledger;
    private final ResultEventPort resultEvents;

    public IdempotencyGuard(@NonNull RunToolLedgerPort ledger,
                            @NonNull ResultEventPort resultEvents) {
        this.ledger = ledger;
        this.resultEvents = resultEvents;
    }

    /** 幂等校验结论。 */
    public enum Outcome {
        /** 键不存在，可以继续（唯一放行结论）。 */
        PROCEED,
        /** 该逻辑操作已完成 —— 返回首次结果。 */
        COMPLETED,
        /** 该逻辑操作正在执行中 —— 不得重复执行。 */
        IN_PROGRESS,
        /** 同键不同参数 —— 拒绝。 */
        CONFLICT,
        /** 幂等存储不可用 —— fail-closed。 */
        UNAVAILABLE
    }

    /**
     * 幂等校验结论。
     *
     * @param outcome     结论
     * @param operationId 已有的逻辑操作标识（无则为空）
     * @param decision    对应的四字段决策（{@link Outcome#PROCEED} 时为空）
     */
    public record Result(@NonNull Outcome outcome,
                         @Nullable String operationId,
                         @Nullable AuthorizationDecision decision) {

        static @NonNull Result proceed() {
            return new Result(Outcome.PROCEED, null, null);
        }

        static @NonNull Result completed(@NonNull String operationId,
                                         @NonNull AuthorizationDecision decision) {
            return new Result(Outcome.COMPLETED, operationId, decision);
        }

        static @NonNull Result inProgress(@NonNull String operationId) {
            return new Result(Outcome.IN_PROGRESS, operationId,
                    AuthorizationDecision.executionDeny(ReasonCode.IDEMPOTENCY_IN_PROGRESS));
        }

        static @NonNull Result conflict(@NonNull String operationId) {
            return new Result(Outcome.CONFLICT, operationId,
                    AuthorizationDecision.executionDeny(ReasonCode.IDEMPOTENCY_CONFLICT));
        }

        static @NonNull Result unavailable() {
            return new Result(Outcome.UNAVAILABLE, null,
                    AuthorizationDecision.executionDeny(ReasonCode.IDEMPOTENCY_UNAVAILABLE));
        }
    }

    /**
     * 校验幂等键。
     *
     * @param tenantId      租户
     * @param idempotencyKey 幂等键
     * @param requestDigest 本次请求的参数摘要（用于判定「同键不同参数」）
     * @return 校验结论
     */
    public @NonNull Result check(@NonNull String tenantId,
                                  @NonNull String idempotencyKey,
                                  @NonNull String requestDigest) {
        RunToolLedgerPort.Registration existing;
        try {
            Optional<RunToolLedgerPort.Registration> found =
                    ledger.findByIdempotencyKey(tenantId, idempotencyKey);
            if (found.isEmpty()) {
                // 键不存在 → 唯一放行路径
                return Result.proceed();
            }
            existing = found.get();
        } catch (RuntimeException e) {
            // ⚠️ 幂等存储不可用 → fail-closed（§5.3 步骤 8）
            log.error("幂等存储不可用，fail-closed 拒绝执行: tenant={}, key={}, err={}",
                    tenantId, idempotencyKey, e.getMessage());
            return Result.unavailable();
        }

        String operationId = existing.operationId();

        // ── 同键不同参数 → 冲突 ──────────────────────────────────────
        try {
            Optional<String> storedDigest = ledger.findRequestDigest(tenantId, idempotencyKey);
            if (storedDigest.isPresent() && !storedDigest.get().equals(requestDigest)) {
                log.warn("幂等键冲突（同键不同参数）: tenant={}, key={}, operation={}",
                        tenantId, idempotencyKey, operationId);
                return Result.conflict(operationId);
            }
        } catch (RuntimeException e) {
            // ⚠️ 摘要读不到 → 无法证明「同键同参」→ 同样 fail-closed
            log.error("幂等请求摘要读取失败，fail-closed 拒绝执行: tenant={}, key={}, err={}",
                    tenantId, idempotencyKey, e.getMessage());
            return Result.unavailable();
        }

        // ── 进行中 ─────────────────────────────────────────────────
        ToolOperationState state = existing.state();
        if (state == ToolOperationState.PENDING || state == ToolOperationState.IN_FLIGHT) {
            log.info("同一逻辑操作正在执行中，不重复发起副作用: tenant={}, key={}, operation={}, state={}",
                    tenantId, idempotencyKey, operationId, state);
            return Result.inProgress(operationId);
        }

        // ── 已完成 → 返回首次结果（含首次 reasonCode）────────────────
        return Result.completed(operationId, firstResult(tenantId, operationId, state));
    }

    /**
     * 还原「首次结果」的四字段结论。
     *
     * <p>优先用<b>结果事件</b>（§6.1：承载 invocationId、确定结果、终态事件 id），
     * 它才有首次 reasonCode 与结果载荷；
     * 结果事件缺失时退回账本状态（结论仍确定，只是没有载荷与首次原因码）。
     */
    private @NonNull AuthorizationDecision firstResult(@NonNull String tenantId,
                                                       @NonNull String operationId,
                                                       @NonNull ToolOperationState state) {
        try {
            Optional<ResultEvent> event = resultEvents.findByInvocation(tenantId, operationId);
            if (event.isPresent()) {
                ResultEvent e = event.get();
                ReasonCode reason = e.reasonCode() == null
                        ? defaultReason(e.resultStatus())
                        : ReasonCode.parse(e.reasonCode());
                if (reason == null) {
                    reason = defaultReason(e.resultStatus());
                }
                return switch (e.resultStatus()) {
                    case SUCCEEDED -> AuthorizationDecision.executionSucceeded(
                            AuthorizationDecision.Ledger.RECORDED);
                    case FAILED -> AuthorizationDecision.executionFailed(
                            AuthorizationDecision.Ledger.RECORDED, reason);
                    case UNKNOWN -> AuthorizationDecision.executionUnknown(
                            AuthorizationDecision.Ledger.RECORDED, reason);
                };
            }
        } catch (RuntimeException e) {
            log.warn("读取首次结果事件失败，退回账本状态: tenant={}, operation={}, err={}",
                    tenantId, operationId, e.getMessage());
        }
        return switch (state) {
            case SUCCEEDED, RECONCILED_SUCCEEDED ->
                    AuthorizationDecision.executionSucceeded(AuthorizationDecision.Ledger.RECORDED);
            case FAILED, RECONCILED_ABSENT -> AuthorizationDecision.executionFailed(
                    AuthorizationDecision.Ledger.RECORDED, ReasonCode.TOOL_EXECUTION_FAILED);
            default -> AuthorizationDecision.executionUnknown(
                    AuthorizationDecision.Ledger.RECORDED, ReasonCode.EXEC_UNKNOWN);
        };
    }

    private static @NonNull ReasonCode defaultReason(ResultEvent.@NonNull Status status) {
        return switch (status) {
            case SUCCEEDED -> ReasonCode.SUCCESS;
            case FAILED -> ReasonCode.TOOL_EXECUTION_FAILED;
            case UNKNOWN -> ReasonCode.EXEC_UNKNOWN;
        };
    }
}
