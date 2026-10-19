package cn.studykid.multiagent.worker.execute;

import java.time.Duration;
import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.core.config.RunConfigSnapshot;
import cn.studykid.multiagent.ports.model.ModelGateway;
import cn.studykid.multiagent.ports.model.ModelRequest;
import cn.studykid.multiagent.ports.model.ModelResponse;
import cn.studykid.multiagent.ports.persistence.RunClaimPort;
import cn.studykid.multiagent.ports.tool.ToolExecutionException;

/**
 * Worker 执行循环（P2-D03 §2 / FR-004）。
 *
 * <p><b>只做编排</b>：串联模型调用、工具调用闸门、状态提交。
 * 工具的<b>实现</b>属 D4，本类不关心工具做什么。
 *
 * <p><b>⚠️ 重试只在 Worker 侧实现</b>（§4.1）。绝不引入 Spring 的
 * {@code @Retryable}：两者同时生效会双重重试 ——
 * 每次失败重试 2 次，对 {@code record_weakness} 意味着
 * <b>重复写入 = Sev-0</b>。
 */
public class RunExecutionLoop {

    private static final Logger log = LoggerFactory.getLogger(RunExecutionLoop.class);

    private final ModelGateway modelGateway;
    private final RunClaimPort claimPort;
    private final ToolInvocationGate toolGate;
    private final Duration leaseDuration;
    /**
     * 本 Worker 实例的身份，必须与领取时传给 {@code claimTasks}/
     * {@code claimSpecificRun} 的 workerId <b>完全一致</b>——
     * {@code renewLease} 的 WHERE 子句按 {@code lease_owner} 匹配，
     * 身份不一致会导致续租永远失败，多步 Run 在第二步被误判为「已被接管」
     * （P3-D02 §2 F-1）。
     */
    private final String workerId;

    public RunExecutionLoop(@NonNull ModelGateway modelGateway,
                             @NonNull RunClaimPort claimPort,
                             @NonNull ToolInvocationGate toolGate,
                             @NonNull Duration leaseDuration,
                             @NonNull String workerId) {
        this.modelGateway = modelGateway;
        this.claimPort = claimPort;
        this.toolGate = toolGate;
        this.leaseDuration = leaseDuration;
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId 不能为空（fencing 续租身份）");
        }
        this.workerId = workerId;
    }

    /**
     * 执行一次已领取的任务。
     *
     * @param task      D2 已领取的任务（含 fencingToken）
     * @param snapshot  Run 创建时的配置快照（<b>不是当前配置</b>）
     * @param input     Run 的输入载荷
     * @return 执行结果
     */
    public ExecutionOutcome execute(String tenantId,
                                    RunClaimPort.LeasedTask task,
                                    AgentModelConfig agentConfig,
                                    RunConfigSnapshot snapshot,
                                    Map<String, Object> input) {
        // ── §2.1 执行前检查世代（为省成本，不是安全保证）──────────────
        if (!claimPort.isCurrentGeneration(tenantId, task.runId(), task.fencingToken())) {
            // 租约已过期并被接管。此时执行模型 = 白花钱，提交也会被拒
            log.info("世代已失效，放弃执行以节省成本: runId={}", task.runId());
            return ExecutionOutcome.skippedStaleGeneration();
        }

        int steps = 0;

        try {
            while (steps < snapshot.maxSteps()) {
                steps++;

                // ── §2.2 取消检查（协作式，P3-D06 / FR-011）─────────────
                // ⚠️ 每轮迭代开头（模型调用前）即查取消。一旦观察到取消，
                //    立即停止，不再发起新一轮模型调用与工具动作。
                //    状态终态已由 requestCancel 置 CANCELLED（claimSpecificRun /
                //    commitResult 均拒绝已取消 Run），双重保险。
                if (claimPort.isCancelled(tenantId, task.runId())) {
                    log.info("检测到取消请求，停止执行: runId={}", task.runId());
                    return ExecutionOutcome.cancelled();
                }

                // ── §2 循环体 ────────────────────────────────────────────
                // ⚠️ 模型参数来自 Agent 配置（Run 关联的 Agent），
                //    运行约束（maxSteps）来自 Run 创建时的快照。
                //    两者不可混用：改 Agent 影响新 Run，改配置快照也影响新 Run，
                //    但已创建的 Run 必须按创建时的约束执行。
                ModelRequest request = new ModelRequest(
                        agentConfig.modelId(),
                        agentConfig.systemPrompt(),
                        renderInput(input),
                        agentConfig.temperature(),
                        agentConfig.maxTokens(),
                        agentConfig.timeout(),
                        agentConfig.toolSchemas());

                ModelResponse response = modelGateway.complete(request);

                if (response.toolCalls().isEmpty()) {
                    // 无工具调用 → 生成结果，正常完成
                    return commitSuccess(tenantId, task, response.content());
                }

                // ── §4 工具调用（逐个走完整闸门）───────────────────────
                ToolInvocationGate.ToolInvocationResult toolResult = toolGate.invokeAll(
                        tenantId, task, response.toolCalls());

                if (toolResult.unknownResultOccurred()) {
                    // ── §3.2 UNKNOWN 处置 ────────────────────────────────
                    // ⚠️ 绝不当普通失败重试。可能有副作用已发生。
                    //    不释放预算预留（成本可能已发生）
                    log.warn("工具结果未知，停止执行并转对账: runId={}, operation={}",
                            task.runId(), toolResult.unknownOperationId());
                    return ExecutionOutcome.unknownResult(toolResult.unknownOperationId());
                }

                if (toolResult.allRejected()) {
                    // 工具全被拒（如授权失败）→ 交由模型重新决策或失败
                    log.info("工具调用全部被拒: runId={}", task.runId());
                    return commitFailure(tenantId, task, "工具调用被拒绝");
                }

                // ── §5 续租（长任务必须，否则会被接管）──────────────────
                // ⚠️ 身份必须是构造时的 workerId（与领取时一致），
                //    不可从 task 现场拼凑 —— 那会让 lease_owner 永远不匹配（F-1）
                if (!claimPort.renewLease(tenantId, task.runId(),
                        workerId, task.fencingToken(), leaseDuration)) {
                    log.info("续租失败（已被接管），停止执行: runId={}", task.runId());
                    return ExecutionOutcome.skippedStaleGeneration();
                }
            }

            // ── §5.1 步数超限 ──────────────────────────────────────────
            log.warn("步数超限: runId={}, steps={}, max={}",
                    task.runId(), steps, snapshot.maxSteps());
            return commitFailure(tenantId, task, "步数超限");

        } catch (ModelGateway.ModelGatewayException e) {
            // ── §3 三种异常语义 ────────────────────────────────────────
            if (e.kind().retryable()) {
                log.info("模型调用可重试失败: runId={}, kind={}",
                        task.runId(), e.kind());
                return ExecutionOutcome.retryableFailure(e.kind().name());
            }
            log.info("模型调用不可重试失败: runId={}, kind={}",
                    task.runId(), e.kind());
            return commitFailure(tenantId, task, "模型调用失败：" + e.kind());

        } catch (ToolExecutionException e) {
            // ⚠️ 能走到这里说明工具闸门没有正确处理 UNKNOWN ——
            //    闸门应在内部转为 unknownResultOccurred()，而非抛出
            log.error("工具异常逃逸出闸门（不应发生）: runId={}", task.runId(), e);
            return commitFailure(tenantId, task, "工具执行异常");

        } catch (RuntimeException e) {
            log.error("执行过程未预期异常: runId={}", task.runId(), e);
            return commitFailure(tenantId, task, "执行异常");
        }
    }

    private ExecutionOutcome commitSuccess(String tenantId, RunClaimPort.LeasedTask task,
                                          String content) {
        boolean ok = claimPort.commitResult(tenantId, task.runId(), task.fencingToken(),
                "SUCCEEDED", "{\"content\":\"" + escape(content) + "\"}");
        // ⚠️ 提交失败（世代不符）不是执行失败，是「白干了」
        return ok ? ExecutionOutcome.succeeded()
                  : ExecutionOutcome.skippedStaleGeneration();
    }

    private ExecutionOutcome commitFailure(String tenantId, RunClaimPort.LeasedTask task,
                                           String reason) {
        boolean ok = claimPort.commitResult(tenantId, task.runId(), task.fencingToken(),
                "FAILED", "{\"reason\":\"" + escape(reason) + "\"}");
        return ok ? ExecutionOutcome.failed(reason)
                  : ExecutionOutcome.skippedStaleGeneration();
    }

    /**
     * 模型调用参数（来自 Run 关联的 Agent 配置）。
     *
     * <p><b>与 {@link RunConfigSnapshot} 分开的原因</b>：
     * 快照是<b>运行约束</b>（预算、步数、审批），Agent 配置是<b>模型参数</b>。
     * 二者生命周期不同 —— Agent 可更新，Run 的约束在创建时冻结。
     */
    public record AgentModelConfig(
            String modelId,
            String systemPrompt,
            double temperature,
            int maxTokens,
            Duration timeout,
            Map<String, Object> toolSchemas
    ) {
        public AgentModelConfig {
            toolSchemas = toolSchemas == null ? Map.of() : Map.copyOf(toolSchemas);
        }
    }

    private static String renderInput(Map<String, Object> input) {
        return String.valueOf(input);
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * 执行结果。
     *
     * <p><b>⚠️ 各结果对应不同后续处置，不可混淆</b>：
     * <ul>
     *   <li>{@code RETRYABLE_FAILURE} —— 可重新投递消息重试；</li>
     *   <li>{@code UNKNOWN_RESULT} —— <b>禁止重试</b>，转对账；</li>
     *   <li>{@code SKIPPED_STALE_GENERATION} —— 不重试，任务已被接管。</li>
     * </ul>
     */
    public record ExecutionOutcome(Kind kind, String detail) {

        public enum Kind {
            SUCCEEDED,
            FAILED,
            /** 可重试失败：重新投递即可。 */
            RETRYABLE_FAILURE,
            /** ⚠️ 结果未知：**禁止重试**，须对账。 */
            UNKNOWN_RESULT,
            /** 世代已失效：已被接管，不重试。 */
            SKIPPED_STALE_GENERATION,
            /** ⚠️ 用户取消：协作式停止，不再启动新动作。 */
            CANCELLED
        }

        public ExecutionOutcome {
            if (kind == null) {
                throw new IllegalArgumentException("kind 不能为空");
            }
        }

        public static ExecutionOutcome succeeded() {
            return new ExecutionOutcome(Kind.SUCCEEDED, null);
        }

        public static ExecutionOutcome failed(String reason) {
            return new ExecutionOutcome(Kind.FAILED, reason);
        }

        public static ExecutionOutcome retryableFailure(String kind) {
            return new ExecutionOutcome(Kind.RETRYABLE_FAILURE, kind);
        }

        public static ExecutionOutcome unknownResult(String operationId) {
            return new ExecutionOutcome(Kind.UNKNOWN_RESULT, operationId);
        }

        public static ExecutionOutcome skippedStaleGeneration() {
            return new ExecutionOutcome(Kind.SKIPPED_STALE_GENERATION, null);
        }

        public static ExecutionOutcome cancelled() {
            return new ExecutionOutcome(Kind.CANCELLED, null);
        }

        /** @return 是否允许重新投递重试。UNKNOWN 与世代失效均**不允许**。 */
        public boolean retryable() {
            return kind == Kind.RETRYABLE_FAILURE;
        }
    }
}