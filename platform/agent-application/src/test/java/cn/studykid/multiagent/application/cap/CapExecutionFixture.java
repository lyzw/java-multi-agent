package cn.studykid.multiagent.application.cap;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.cap.ExecutionIntent;
import cn.studykid.multiagent.core.cap.ResultEvent;
import cn.studykid.multiagent.core.config.ToolRiskLevel;
import cn.studykid.multiagent.core.run.ToolOperationState;
import cn.studykid.multiagent.ports.persistence.ApprovalPort;
import cn.studykid.multiagent.ports.persistence.BudgetPort;
import cn.studykid.multiagent.ports.persistence.ExecutionIntentPort;
import cn.studykid.multiagent.ports.persistence.ResultEventPort;
import cn.studykid.multiagent.ports.persistence.RunToolLedgerPort;
import cn.studykid.multiagent.ports.tool.ToolHandler;

/**
 * P2 执行准入测试夹具：<b>内存控制面 + 调用时序记录</b>。
 *
 * <p>⚠️ <b>时序记录是本夹具存在的核心理由</b>：
 * §5.3 步骤 13a 要求「执行意图必须<b>先于</b>外部副作用持久化」，
 * 而这个不变式<b>无法</b>从最终状态断言出来 ——
 * 无论意图写在前还是写在后，测试结束时账本/结果事件的状态都一样。
 * 唯一能证伪它的办法是记录<b>发生顺序</b>并在断言里比较先后。
 *
 * <p>同理，§5.3 步骤 14a 要求「确定结果先入可恢复存储，再更新 executionStatus」，
 * 也只能靠时序记录断言。
 */
final class CapExecutionFixture {

    /** 统一时序日志。 */
    final List<String> timeline = new ArrayList<>();

    /** 账本：幂等键 → 操作。 */
    final Map<String, LedgerEntry> ledger = new LinkedHashMap<>();
    /** 预算预留。 */
    final Map<String, BudgetPort.Reservation> reservations = new LinkedHashMap<>();
    /** 审批消费记录。 */
    final List<String> consumedApprovals = new ArrayList<>();
    /** 执行意图。 */
    final Map<String, ExecutionIntent> intents = new LinkedHashMap<>();
    /** 意图状态迁移记录。 */
    final List<String> intentTransitions = new ArrayList<>();
    /** 结果事件。 */
    final Map<String, ResultEvent> resultEvents = new LinkedHashMap<>();

    /** 工具执行次数。 */
    int toolExecutions;
    /** 工具执行失败模式。 */
    RuntimeException toolFailure;

    /** 账本条目。 */
    static final class LedgerEntry {
        final String operationId;
        final String idempotencyKey;
        final String requestDigest;
        ToolOperationState state = ToolOperationState.IN_FLIGHT;

        LedgerEntry(String operationId, String idempotencyKey, String requestDigest) {
            this.operationId = operationId;
            this.idempotencyKey = idempotencyKey;
            this.requestDigest = requestDigest;
        }
    }

    // ── 端口实现 ──────────────────────────────────────────────────

    @NonNull RunToolLedgerPort ledgerPort() {
        return new RunToolLedgerPort() {
            @Override
            public @NonNull Registration register(@NonNull String tenantId, @NonNull String runId,
                                                  @NonNull String toolName, @NonNull String riskLevel,
                                                  @NonNull String idempotencyKey,
                                                  @NonNull String requestDigest,
                                                  @NonNull Map<String, Object> arguments,
                                                  long fencingToken) {
                timeline.add("ledger:register");
                LedgerEntry entry = ledger.get(idempotencyKey);
                if (entry == null) {
                    entry = new LedgerEntry(UUID.randomUUID().toString(), idempotencyKey, requestDigest);
                    ledger.put(idempotencyKey, entry);
                }
                return new Registration(entry.operationId, entry.state, 1);
            }

            @Override
            public void recordResult(@NonNull String tenantId, @NonNull String operationId,
                                      @NonNull ToolOperationState result, long fencingToken,
                                      String errorClass, String errorMessage) {
                timeline.add("ledger:record:" + result);
                ledger.values().stream()
                        .filter(e -> e.operationId.equals(operationId))
                        .findFirst()
                        .ifPresent(e -> e.state = result);
            }

            @Override
            public void reconcile(@NonNull String tenantId, @NonNull String operationId,
                                  @NonNull ToolOperationState reconciled, @NonNull String source) {
            }

            @Override
            public void manualDispose(@NonNull String tenantId, @NonNull String operationId,
                                      @NonNull String disposedBy, @NonNull String note) {
            }

            @Override
            public @NonNull List<UnknownOperation> findUnknownOperations(@NonNull String tenantId) {
                return List.of();
            }

            @Override
            public @NonNull Optional<Registration> findByIdempotencyKey(
                    @NonNull String tenantId, @NonNull String idempotencyKey) {
                LedgerEntry entry = ledger.get(idempotencyKey);
                return entry == null
                        ? Optional.empty()
                        : Optional.of(new Registration(entry.operationId, entry.state, 1));
            }

            @Override
            public @NonNull Optional<String> findRequestDigest(@NonNull String tenantId,
                                                                @NonNull String idempotencyKey) {
                LedgerEntry entry = ledger.get(idempotencyKey);
                return entry == null ? Optional.empty() : Optional.of(entry.requestDigest);
            }
        };
    }

    @NonNull BudgetPort budgetPort() {
        return new BudgetPort() {
            @Override
            public @NonNull ReserveOutcome reserve(@NonNull String tenantId, @NonNull String runId,
                                                    String operationId, @NonNull BigDecimal amount) {
                timeline.add("budget:reserve");
                String reservationId = "res-" + operationId;
                reservations.put(operationId, new BudgetPort.Reservation(reservationId, runId,
                        operationId, amount, BigDecimal.ZERO, "RESERVED", "CNY"));
                return ReserveOutcome.RESERVED;
            }

            @Override
            public boolean settle(@NonNull String tenantId, @NonNull String reservationId,
                                  @NonNull BigDecimal actualAmount, String modelId) {
                timeline.add("budget:settle");
                return true;
            }

            @Override
            public boolean release(@NonNull String tenantId, @NonNull String reservationId,
                                   @NonNull String reason) {
                timeline.add("budget:release");
                return true;
            }

            @Override
            public @NonNull Optional<Reservation> findByOperation(@NonNull String tenantId,
                                                                  @NonNull String operationId) {
                return Optional.ofNullable(reservations.get(operationId));
            }

            @Override
            public @NonNull List<OrphanedReservation> findOrphaned(@NonNull String tenantId) {
                return List.of();
            }

            @Override
            public @NonNull Optional<AccountSnapshot> accountSnapshot(@NonNull String tenantId,
                                                                      @NonNull String currency) {
                return Optional.empty();
            }

            @Override
            public boolean isConsistent(@NonNull String tenantId, @NonNull String currency) {
                return true;
            }
        };
    }

    @NonNull ApprovalPort approvalPort() {
        return new ApprovalPort() {
            @Override
            public @NonNull ApprovalOutcome consume(@NonNull String tenantId, @NonNull String operationId,
                                                     @NonNull String argumentDigest,
                                                     @NonNull String targetResource,
                                                     @NonNull String runId) {
                timeline.add("approval:consume");
                consumedApprovals.add(operationId);
                return ApprovalOutcome.CONSUMED;
            }

            @Override
            public @NonNull String grant(@NonNull String tenantId, @NonNull String operationId,
                                         @NonNull String argumentDigest, @NonNull String targetResource,
                                         cn.studykid.multiagent.core.config.ApprovalRequirement requirement,
                                         @NonNull String approverScope, Instant expiresAt,
                                         @NonNull String approverId) {
                throw new UnsupportedOperationException("本替身不支持授予");
            }

            @Override
            public void revoke(@NonNull String tenantId, @NonNull String operationId,
                               @NonNull String approverId, @NonNull String reason) {
                throw new UnsupportedOperationException("本替身不支持撤销");
            }

            @Override
            public @NonNull Optional<ApprovalView> findActive(@NonNull String tenantId,
                                                              @NonNull String operationId) {
                return Optional.empty();
            }

            @Override
            public void markIntentSubmitted(@NonNull String tenantId, @NonNull String operationId) {
                timeline.add("approval:intent-submitted");
            }
        };
    }

    @NonNull ExecutionIntentPort intentPort() {
        return new ExecutionIntentPort() {
            @Override
            public @NonNull ExecutionIntent record(@NonNull ExecutionIntent intent) {
                timeline.add("intent:record");
                return intents.computeIfAbsent(intent.invocationId(), k -> intent);
            }

            @Override
            public @NonNull Optional<ExecutionIntent> findByInvocation(@NonNull String tenantId,
                                                                        @NonNull String invocationId) {
                return Optional.ofNullable(intents.get(invocationId));
            }

            @Override
            public boolean transitionState(@NonNull String tenantId, @NonNull String invocationId,
                                           ExecutionIntent.@NonNull IntentState expectedFrom,
                                           ExecutionIntent.@NonNull IntentState to) {
                timeline.add("intent:transition:" + to);
                intentTransitions.add(invocationId + ":" + expectedFrom + "->" + to);
                ExecutionIntent existing = intents.get(invocationId);
                if (existing == null || existing.intentState() != expectedFrom) {
                    return false;
                }
                intents.put(invocationId, new ExecutionIntent(existing.intentId(),
                        existing.tenantId(), existing.runId(), existing.invocationId(),
                        existing.toolId(), existing.toolVersion(), existing.argsFingerprint(),
                        existing.approvalId(), to, existing.createdAt(), Instant.now()));
                return true;
            }
        };
    }

    @NonNull ResultEventPort resultEventPort() {
        return new ResultEventPort() {
            @Override
            public boolean recordIfAbsent(@NonNull ResultEvent event) {
                timeline.add("result-event:record:" + event.resultStatus());
                if (resultEvents.containsKey(event.invocationId())) {
                    return false;
                }
                resultEvents.put(event.invocationId(), event);
                return true;
            }

            @Override
            public @NonNull Optional<ResultEvent> findByInvocation(@NonNull String tenantId,
                                                                    @NonNull String invocationId) {
                return Optional.ofNullable(resultEvents.get(invocationId));
            }
        };
    }

    /**
     * 记录型工具替身。
     *
     * @param toolName 工具名
     */
    @NonNull ToolHandler tool(@NonNull String toolName) {
        return new ToolHandler() {
            @Override
            public @NonNull String toolName() {
                return toolName;
            }

            @Override
            public @NonNull String inputSchema() {
                return "{}";
            }

            @Override
            public @NonNull ToolRiskLevel riskLevel() {
                return ToolRiskLevel.READ;
            }

            @Override
            public void validate(@NonNull Map<String, Object> arguments) {
                timeline.add("tool:validate");
            }

            @Override
            public @NonNull ToolResult execute(@NonNull ToolExecutionContext context,
                                                @NonNull Map<String, Object> arguments) {
                timeline.add("tool:execute");
                toolExecutions++;
                if (toolFailure != null) {
                    throw toolFailure;
                }
                return ToolResult.of("ok");
            }
        };
    }

    /** @return 时序日志中某事件的下标；不存在返回 -1。 */
    int indexOf(@NonNull String event) {
        return timeline.indexOf(event);
    }
}
