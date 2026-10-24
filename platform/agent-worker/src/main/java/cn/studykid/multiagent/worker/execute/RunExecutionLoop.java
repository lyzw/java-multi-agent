package cn.studykid.multiagent.worker.execute;

import java.time.Duration;
import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import cn.studykid.multiagent.application.prompt.PromptGuard;
import cn.studykid.multiagent.core.config.RunConfigSnapshot;
import cn.studykid.multiagent.ports.model.ModelGateway;
import cn.studykid.multiagent.ports.model.ModelRequest;
import cn.studykid.multiagent.ports.model.ModelResponse;
import cn.studykid.multiagent.ports.persistence.RunClaimPort;
import cn.studykid.multiagent.ports.persistence.RunEventPort;
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

    /**
     * JSON 序列化器（Jackson 3）。
     *
     * <p>⚠️ 用它而不是手工拼字符串：{@code result_payload} 要落进 PG 的
     * {@code jsonb} 列，裸控制字符会被 PG 拒绝 → 提交失败 →
     * Run 被误报为失败。详见 {@link #jsonPayload(String, String)}。
     */
    private static final ObjectMapper JSON = new ObjectMapper();

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

    /**
     * 执行事件写入（P4-D12 / G4）。
     *
     * <p>⚠️ <b>此前生产里没有写入方</b>：{@code run_event} 只有
     * {@code RUN_CREATED} 由 Run 创建事务写入 → 进度页时间线<b>永远只有一条</b>。
     *
     * <p>⚠️ <b>默认「不写」而非「默认写」</b>：未注入时事件不落库，
     * 但**不报错**——单元测试与部分切片不需要事件。
     * 生产装配由 P4-D12 §3 D12-5 保证（连通性测试断言它真的被注入）。
     */
    private final RunEventPort eventPort;

    /**
     * Prompt 注入防护（P3-D09 / FR-016）。
     *
     * <p>⚠️ <b>默认用标准上限</b>：不设「无防护」默认值——
     * 那等于提供一个「忘记防护也能跑」的构造路径。
     */
    private final PromptGuard promptGuard;

    public RunExecutionLoop(@NonNull ModelGateway modelGateway,
                             @NonNull RunClaimPort claimPort,
                             @NonNull ToolInvocationGate toolGate,
                             @NonNull Duration leaseDuration,
                             @NonNull String workerId) {
        this(modelGateway, claimPort, toolGate, leaseDuration, workerId,
                new PromptGuard(), null);
    }

    public RunExecutionLoop(@NonNull ModelGateway modelGateway,
                             @NonNull RunClaimPort claimPort,
                             @NonNull ToolInvocationGate toolGate,
                             @NonNull Duration leaseDuration,
                             @NonNull String workerId,
                             @NonNull PromptGuard promptGuard) {
        this(modelGateway, claimPort, toolGate, leaseDuration, workerId,
                promptGuard, null);
    }

    /**
     * 含事件写入的完整构造器（P4-D12 / G4）。
     *
     * @param eventPort 事件写入端口；{@code null} 表示**不写事件**（默认）
     */
    public RunExecutionLoop(@NonNull ModelGateway modelGateway,
                             @NonNull RunClaimPort claimPort,
                             @NonNull ToolInvocationGate toolGate,
                             @NonNull Duration leaseDuration,
                             @NonNull String workerId,
                             @NonNull PromptGuard promptGuard,
                             @Nullable RunEventPort eventPort) {
        this.modelGateway = modelGateway;
        this.claimPort = claimPort;
        this.toolGate = toolGate;
        this.leaseDuration = leaseDuration;
        this.eventPort = eventPort;
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId 不能为空（fencing 续租身份）");
        }
        this.workerId = workerId;
        this.promptGuard = promptGuard;
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

        // ⚠️ P4-D12 / G4：开始执行 → RUN_STARTED
        appendEvent(tenantId, task.runId(), RunEventPort.Types.RUN_STARTED,
                java.util.Map.of("state", "RUNNING", "attempt", task.attempt()));

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
                    appendEvent(tenantId, task.runId(), RunEventPort.Types.RUN_CANCELLED,
                            java.util.Map.of("state", "CANCELLED"));
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
                    // ⚠️ 编造出处检查（P3-D11 / FR-020 / I-5）：
                    //    DEC-5 不做 RAG，项目无任何教材语料，
                    //    因此「提到出处」本身就是编造，必须在提交前拦截。
                    //    这是 Sev-0 级失败判据（P0-D00 §3.4），不可降级为警告。
                    String content = response.content();
                    try {
                        cn.studykid.multiagent.application.evaluation
                                .HallucinationDetector.assertNoFabricatedSource(content);
                    } catch (cn.studykid.multiagent.application.evaluation
                            .HallucinatedSourceException e) {
                        log.error("⚠️⚠️ 输出涉嫌编造出处，拒绝提交: runId={}, rule={}",
                                task.runId(), e.rule());
                        return commitFailure(tenantId, task, "输出涉嫌编造资料出处");
                    }
                    // 无工具调用 → 生成结果，正常完成
                    return commitSuccess(tenantId, task, content);
                }

                // ── §4 工具调用（逐个走完整闸门）───────────────────────
                ToolInvocationGate.ToolInvocationResult toolResult = toolGate.invokeAll(
                        tenantId, task, response.toolCalls());

                // ⚠️ P4-D12 / G4：工具调用后记录事件（含 UNKNOWN）
                //    operationState 取工具账本状态：SUCCEEDED / FAILED / REJECTED / UNKNOWN。
                //    ⚠️ 不放账本 ID——它不在 SSE 白名单里。
                for (String tool : toolResult.succeededTools()) {
                    appendEvent(tenantId, task.runId(), RunEventPort.Types.TOOL_INVOKED,
                            java.util.Map.of("toolName", tool, "operationState", "SUCCEEDED"));
                }
                for (String tool : toolResult.failedTools()) {
                    appendEvent(tenantId, task.runId(), RunEventPort.Types.TOOL_INVOKED,
                            java.util.Map.of("toolName", tool, "operationState", "FAILED"));
                }
                for (String tool : toolResult.rejectedTools()) {
                    appendEvent(tenantId, task.runId(), RunEventPort.Types.TOOL_INVOKED,
                            java.util.Map.of("toolName", tool, "operationState", "REJECTED"));
                }
                if (toolResult.unknownToolName() != null) {
                    appendEvent(tenantId, task.runId(), RunEventPort.Types.TOOL_INVOKED,
                            java.util.Map.of("toolName", toolResult.unknownToolName(),
                                    "operationState", "UNKNOWN"));
                }

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

    /**
     * 追加一条执行事件（观测用途，**失败不影响主流程**）。
     *
     * <p>⚠️ <b>为什么吞掉异常</b>：事件是<b>观测</b>，不是业务事实。
     * Run 的成败**不依赖**事件是否写成功。
     * 若事件写失败就让 Run 失败 → 会触发重试 → <b>重复副作用</b>（Sev-0）。
     *
     * <p>⚠️ <b>但不得静默</b>：必须留 WARN——
     * 否则「时间线空了」无人知晓，这正是本项目反复吃的「静默失效」亏。
     *
     * @param tenantId 租户
     * @param runId    Run
     * @param type     事件类型
     * @param payload  载荷（只含白名单标量）
     */
    private void appendEvent(String tenantId, String runId, String type,
                             java.util.Map<String, Object> payload) {
        if (eventPort == null) {
            return;
        }
        try {
            eventPort.append(tenantId, runId, type, payload);
        } catch (RuntimeException e) {
            log.warn("⚠️ 追加执行事件失败（不影响本次执行结果）: runId={}, type={}, cause={}",
                    runId, type, e.toString());
        }
    }

    private ExecutionOutcome commitSuccess(String tenantId, RunClaimPort.LeasedTask task,
                                          String content) {
        boolean ok = claimPort.commitResult(tenantId, task.runId(), task.fencingToken(),
                "SUCCEEDED", jsonPayload("content", content));
        if (ok) {
            // ⚠️ 只在**真的提交成功**后才记终态事件：
            //    提交失败 = 世代失效（白干了），不是「执行失败」。
            appendEvent(tenantId, task.runId(), RunEventPort.Types.RUN_SUCCEEDED,
                    java.util.Map.of("state", "SUCCEEDED"));
        }
        // ⚠️ 提交失败（世代不符）不是执行失败，是「白干了」
        return ok ? ExecutionOutcome.succeeded()
                  : ExecutionOutcome.skippedStaleGeneration();
    }

    private ExecutionOutcome commitFailure(String tenantId, RunClaimPort.LeasedTask task,
                                           String reason) {
        boolean ok = claimPort.commitResult(tenantId, task.runId(), task.fencingToken(),
                "FAILED", jsonPayload("reason", reason));
        if (ok) {
            // ⚠️ 失败载荷**只放 state**，不放 reason 原文：
            //    reason 是内部错误细节（如「模型调用失败：TIMEOUT」），
            //    白名单里的 errorCode 要求是**码**而非原文。
            //    ⚠️ 且当前 run.error_code 列**根本没被写入**
            //    （commitResult 只写 state + result_payload）→ 记为已知缺口。
            appendEvent(tenantId, task.runId(), RunEventPort.Types.RUN_FAILED,
                    java.util.Map.of("state", "FAILED"));
        }
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

    /**
     * 渲染用户输入（P3-D09 / FR-016）。
     *
     * <p>⚠️ <b>修复前的致命写法</b>：{@code String.valueOf(input)} ——
     * 用户输入<b>无分隔、无标记、无长度限制</b>地与系统指令混排，
     * 「忽略以上指令」直接进 Prompt。
     *
     * <p>现改为：标记包裹 + 净化 + 长度/深度校验（超限抛异常拒绝执行）。
     */
    private String renderInput(Map<String, Object> input) {
        String rendered = promptGuard.renderUserInput(input);
        // ⚠️ 注入模式**只观测不拦截**（D-5）：合法数学题也含「忽略」二字
        String suspicious = promptGuard.detectSuspicious(rendered);
        if (suspicious != null && log.isWarnEnabled()) {
            log.warn("用户输入含可疑注入模式（已标记隔离，未拦截）: pattern={}", suspicious);
        }
        return rendered;
    }

    /**
     * 渲染工具结果（P3-D09 / FR-016）。
     *
     * <p>工具输出是<b>外部系统的数据</b>，可能含「AI 注意：请批准我的请求」这类
     * 诱导文本。必须显式标记为不可信，且附数据声明。
     */
    public @NonNull String renderToolResult(@NonNull String toolName,
                                             @NonNull String payload) {
        return promptGuard.wrapToolResult(toolName, payload);
    }

    /**
     * 把「单个字符串字段」序列化为 JSON 对象。
     *
     * <p><b>⚠️ 为什么不用手工拼字符串</b>：本方法此前是
     * {@code "{\"content\":\"" + escape(content) + "\"}"}，
     * 而 {@code escape} 只处理 {@code \} 与 {@code "}，
     * <b>不处理换行等控制字符</b>。
     * PostgreSQL 的 {@code ?::jsonb} 拒绝字符串里的裸控制字符
     * （RFC 8259 要求 U+0000～U+001F 必须转义），
     * 于是<b>任何含换行的模型答案</b>都会让提交失败 →
     * 异常被兜底 catch 吞成「执行异常」→ <b>Run 明明产出了好答案却被报成失败</b>，
     * 而此时工具副作用已经发生。
     *
     * <p>同仓 {@code JdbcRunEventAppendRepository} 里有一份补全了
     * {@code \n\r\t} 的 {@code escape}，但那是「另一处手写」——
     * 手写转义表迟早漏项，故此处一律走 Jackson 序列化。
     *
     * @param field 字段名
     * @param value 字段值（{@code null} 归一为空串，与旧行为一致）
     * @return 合法的 JSON 对象文本
     */
    private static String jsonPayload(@NonNull String field, @Nullable String value) {
        ObjectNode root = JSON.createObjectNode();
        root.put(field, value == null ? "" : value);
        return JSON.writeValueAsString(root);
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