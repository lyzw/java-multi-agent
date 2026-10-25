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

import cn.studykid.multiagent.core.cap.CapApproval;
import cn.studykid.multiagent.core.cap.CapCompensationTask;
import cn.studykid.multiagent.core.cap.ExecutionIntent;
import cn.studykid.multiagent.core.cap.ResultEvent;
import cn.studykid.multiagent.core.config.ToolRiskLevel;
import cn.studykid.multiagent.core.run.ToolOperationState;
import cn.studykid.multiagent.ports.persistence.ApprovalPort;
import cn.studykid.multiagent.ports.persistence.BudgetPort;
import cn.studykid.multiagent.ports.persistence.CapApprovalPort;
import cn.studykid.multiagent.ports.persistence.CompensationTaskPort;
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
    /**
     * ⚠️ 幂等键<b>查询</b>记录（第十七批）。
     *
     * <p>用途：观察「链路实际拿去查账本的幂等键」——
     * 当调用在准入阶段（步骤 13）被拒时，账本里不会留下行，
     * 但键已经算出来并送进幂等守卫了。
     */
    final List<String> lookedUpKeys = new ArrayList<>();
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
    /** CAP 审批（id → 审批记录）。 */
    final Map<String, CapApproval> approvals = new LinkedHashMap<>();
    /** 补偿任务。 */
    final Map<String, CapCompensationTask> compensationTasks = new LinkedHashMap<>();

    /** 工具执行次数。 */
    int toolExecutions;
    /** 工具执行失败模式。 */
    RuntimeException toolFailure;
    /** ⚠️ 预算预留结局（可置为 INSUFFICIENT 模拟额度不足）。 */
    BudgetPort.ReserveOutcome budgetReserveOutcome = BudgetPort.ReserveOutcome.RESERVED;
    /** ⚠️ 账本 POST 是否失败（模拟落账失败）。 */
    boolean failLedgerPost;
    /** ⚠️ 结果事件写入是否失败（模拟「确定结果未落盘」）。 */
    boolean failResultEventWrite;
    /** ⚠️ 补偿任务入队是否失败（模拟「结局无处留存」）。 */
    boolean failCompensationEnqueue;

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
                boolean created = entry == null;
                if (entry == null) {
                    entry = new LedgerEntry(UUID.randomUUID().toString(), idempotencyKey, requestDigest);
                    ledger.put(idempotencyKey, entry);
                }
                return new Registration(entry.operationId, entry.state, 1, 0L, created);
            }

            @Override
            public void recordResult(@NonNull String tenantId, @NonNull String operationId,
                                      @NonNull ToolOperationState result, long fencingToken,
                                      String errorClass, String errorMessage) {
                timeline.add("ledger:record:" + result);
                if (failLedgerPost) {
                    throw new IllegalStateException("注入：账本落账失败");
                }
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
                lookedUpKeys.add(idempotencyKey);
                LedgerEntry entry = ledger.get(idempotencyKey);
                return entry == null
                        ? Optional.empty()
                        : Optional.of(new Registration(entry.operationId, entry.state, 1, 0L, false));
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
                if (!budgetReserveOutcome.granted()) {
                    return budgetReserveOutcome;
                }
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

    /**
     * CAP 审批端口（内存实现）。
     *
     * <p>⚠️ 预占按 CAS 语义实现：只有 {@code PENDING} 且未过期才能被预占，
     * 且绑定项（参数哈希 / 闭包指纹 / 授权版本）必须一致 ——
     * 与 JDBC 实现的 CAS 条件保持同一语义，避免测试与生产行为分叉。
     */
    @NonNull CapApprovalPort capApprovalPort() {
        return new CapApprovalPort() {
            @Override
            public @NonNull String grant(@NonNull CapApprovalGrant grant) {
                timeline.add("approval:grant");
                String id = "cap-approval-" + (approvals.size() + 1);
                Instant now = Instant.now();
                approvals.put(id, new CapApproval(id, grant.tenantId(), grant.principalId(),
                        null, null, null, grant.toolId(), grant.targetResource(),
                        grant.approvalScopeHash(), grant.scopeDefinition(),
                        grant.effectiveArgsHash(), grant.closureFingerprint(),
                        grant.authzVersion(), grant.approverId(), grant.approverScope(),
                        now, grant.expiresAt(), CapApproval.ApprovalState.PENDING,
                        null, null, now, now));
                return id;
            }

            @Override
            public @NonNull ReservationOutcome reserve(@NonNull String tenantId,
                                                        @NonNull String approvalId,
                                                        @NonNull String invocationId,
                                                        @NonNull String operationId,
                                                        @Nullable String runId,
                                                        @NonNull String effectiveArgsHash,
                                                        @NonNull String closureFingerprint,
                                                        long authzVersion) {
                timeline.add("approval:reserve");
                CapApproval existing = approvals.get(approvalId);
                if (existing == null) {
                    return ReservationOutcome.NOT_FOUND;
                }
                if (!existing.expiresAt().isAfter(Instant.now())) {
                    return ReservationOutcome.EXPIRED;
                }
                if (existing.state() == CapApproval.ApprovalState.PENDING
                        && existing.effectiveArgsHash().equals(effectiveArgsHash)
                        && existing.closureFingerprint().equals(closureFingerprint)
                        && existing.authzVersion() == authzVersion) {
                    approvals.put(approvalId, withState(existing,
                            CapApproval.ApprovalState.RESERVED, invocationId, operationId, runId));
                    return ReservationOutcome.RESERVED;
                }
                if (existing.state() == CapApproval.ApprovalState.RESERVED) {
                    return invocationId.equals(existing.invocationId())
                            ? ReservationOutcome.ALREADY_RESERVED_BY_SAME
                            : ReservationOutcome.ALREADY_RESERVED_BY_OTHER;
                }
                if (existing.state() == CapApproval.ApprovalState.INTENT_SUBMITTED) {
                    return ReservationOutcome.INTENT_SUBMITTED;
                }
                if (existing.state() == CapApproval.ApprovalState.CONSUMED) {
                    return ReservationOutcome.ALREADY_CONSUMED;
                }
                if (existing.state() == CapApproval.ApprovalState.CANCELLED) {
                    return ReservationOutcome.CANCELLED;
                }
                return ReservationOutcome.NOT_FOUND;
            }

            @Override
            public boolean markIntentSubmitted(@NonNull String tenantId,
                                                @NonNull String approvalId,
                                                @NonNull String invocationId) {
                timeline.add("approval:intent-submitted");
                CapApproval existing = approvals.get(approvalId);
                if (existing == null
                        || existing.state() != CapApproval.ApprovalState.RESERVED) {
                    return false;
                }
                approvals.put(approvalId, withState(existing,
                        CapApproval.ApprovalState.INTENT_SUBMITTED,
                        existing.invocationId(), existing.operationId(), existing.runId()));
                return true;
            }

            @Override
            public boolean consume(@NonNull String tenantId, @NonNull String approvalId,
                                   @NonNull String invocationId, @NonNull String terminalStatus) {
                timeline.add("approval:consume");
                CapApproval existing = approvals.get(approvalId);
                if (existing == null
                        || existing.state() != CapApproval.ApprovalState.INTENT_SUBMITTED) {
                    return false;
                }
                approvals.put(approvalId, withState(existing,
                        CapApproval.ApprovalState.CONSUMED,
                        existing.invocationId(), existing.operationId(), existing.runId()));
                return true;
            }

            @Override
            public boolean cancel(@NonNull String tenantId, @NonNull String approvalId,
                                  @NonNull String invocationId, @NonNull String reason) {
                timeline.add("approval:cancel");
                CapApproval existing = approvals.get(approvalId);
                if (existing == null || existing.state().isTerminal()) {
                    return false;
                }
                approvals.put(approvalId, withState(existing,
                        CapApproval.ApprovalState.CANCELLED,
                        existing.invocationId(), existing.operationId(), existing.runId()));
                return true;
            }

            @Override
            public @NonNull Optional<CapApproval> findById(@NonNull String tenantId,
                                                            @NonNull String approvalId) {
                return Optional.ofNullable(approvals.get(approvalId));
            }

            @Override
            public @NonNull List<CapApproval> findByTool(@NonNull String tenantId,
                                                          @NonNull String toolId) {
                return approvals.values().stream()
                        .filter(a -> a.toolId().equals(toolId))
                        .toList();
            }

            @Override
            public @NonNull Optional<CapApproval> findByInvocation(@NonNull String tenantId,
                                                                     @NonNull String invocationId) {
                return approvals.values().stream()
                        .filter(a -> invocationId.equals(a.invocationId()))
                        .findFirst();
            }
        };
    }

    /** 审批状态迁移辅助（内存实现）。 */
    private static @NonNull CapApproval withState(@NonNull CapApproval a,
                                                   CapApproval.@NonNull ApprovalState state,
                                                   @Nullable String invocationId,
                                                   @Nullable String operationId,
                                                   @Nullable String runId) {
        return new CapApproval(a.approvalId(), a.tenantId(), a.principalId(), runId,
                invocationId, operationId, a.toolId(), a.targetResource(),
                a.approvalScopeHash(), a.scopeDefinition(), a.effectiveArgsHash(),
                a.closureFingerprint(), a.authzVersion(), a.approverId(), a.approverScope(),
                a.decidedAt(), a.expiresAt(), state, a.terminalStatus(), a.cancelReason(),
                a.createdAt(), Instant.now());
    }

    /** 补偿任务端口（内存实现）。 */
    @NonNull CompensationTaskPort compensationTaskPort() {
        return new CompensationTaskPort() {
            @Override
            public boolean enqueue(@NonNull CapCompensationTask task) {
                timeline.add("compensation:enqueue:" + task.kind());
                if (failCompensationEnqueue) {
                    throw new IllegalStateException("注入：补偿任务入队失败");
                }
                boolean exists = compensationTasks.values().stream()
                        .anyMatch(t -> t.kind() == task.kind()
                                && t.targetId().equals(task.targetId()));
                if (!exists) {
                    compensationTasks.put(task.taskId(), task);
                }
                return !exists;
            }

            @Override
            public @NonNull Optional<CapCompensationTask> findById(@NonNull String tenantId,
                                                                     @NonNull String taskId) {
                return Optional.ofNullable(compensationTasks.get(taskId));
            }

            @Override
            public @NonNull List<CapCompensationTask> findPending(@NonNull String tenantId,
                                                                   int limit) {
                return compensationTasks.values().stream()
                        .filter(t -> t.state() == CapCompensationTask.TaskState.PENDING
                                || t.state() == CapCompensationTask.TaskState.FAILED)
                        .limit(limit)
                        .toList();
            }

            @Override
            public boolean markCompleted(@NonNull String tenantId, @NonNull String taskId) {
                CapCompensationTask t = compensationTasks.get(taskId);
                if (t == null) {
                    return false;
                }
                compensationTasks.put(taskId, new CapCompensationTask(t.taskId(), t.tenantId(),
                        t.kind(), t.targetId(), t.invocationId(), t.payload(),
                        CapCompensationTask.TaskState.COMPLETED, t.attempts(), t.lastError(),
                        t.createdAt(), Instant.now(), t.nextRetryAt()));
                return true;
            }

            @Override
            public boolean markFailed(@NonNull String tenantId, @NonNull String taskId,
                                       @NonNull String error, @NonNull Instant nextRetryAt) {
                CapCompensationTask t = compensationTasks.get(taskId);
                if (t == null) {
                    return false;
                }
                compensationTasks.put(taskId, new CapCompensationTask(t.taskId(), t.tenantId(),
                        t.kind(), t.targetId(), t.invocationId(), t.payload(),
                        CapCompensationTask.TaskState.FAILED, t.attempts() + 1, error,
                        t.createdAt(), Instant.now(), nextRetryAt));
                return true;
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
            public @NonNull List<ExecutionIntent> findPending(@NonNull String tenantId,
                                                              int limit) {
                return intents.values().stream()
                        .filter(ExecutionIntent::isPending)
                        .limit(limit)
                        .toList();
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
                if (failResultEventWrite) {
                    throw new IllegalStateException("注入：结果事件写入失败");
                }
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
