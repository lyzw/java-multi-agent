package cn.studykid.multiagent.worker.agentchat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import cn.studykid.multiagent.application.cap.ClosureFingerprint;
import cn.studykid.multiagent.application.cap.ClosureResolver;
import cn.studykid.multiagent.application.prompt.PromptGuard;
import cn.studykid.multiagent.core.cap.EffectiveClosure;
import cn.studykid.multiagent.core.cap.EffectiveClosure.SkillBinding;
import cn.studykid.multiagent.core.cap.ExecutionScope;
import cn.studykid.multiagent.core.orchestration.DelegationToolSchema;
import cn.studykid.multiagent.ports.agentchat.AgentChatAuditPort;
import cn.studykid.multiagent.ports.agentchat.AgentChatAuditPort.AuditEvent;
import cn.studykid.multiagent.ports.agentchat.AgentChatAuditPort.BeginRecord;
import cn.studykid.multiagent.ports.agentchat.AgentChatEvent;
import cn.studykid.multiagent.ports.agentchat.AgentChatOrchestratorPort;
import cn.studykid.multiagent.ports.agentchat.AgentChatRequest;
import cn.studykid.multiagent.ports.agentchat.AgentChatRequest.Context;
import cn.studykid.multiagent.ports.agentchat.ContextCheckpointEmitter;
import cn.studykid.multiagent.ports.agentchat.ContextCheckpointEmitter.Emitted;
import cn.studykid.multiagent.ports.agentchat.ContextPolicyVersionPort;
import cn.studykid.multiagent.ports.agentchat.ContextStateVerifier;
import cn.studykid.multiagent.ports.agentchat.ContextStateVerifier.Accepted;
import cn.studykid.multiagent.ports.agentchat.ContextStateVerifier.Discarded;
import cn.studykid.multiagent.ports.agentchat.ContextStateVerifier.DropReason;
import cn.studykid.multiagent.ports.agentchat.ContextStateVerifier.Result;
import cn.studykid.multiagent.ports.agentchat.RagHitQuotaPort;
import cn.studykid.multiagent.ports.agentchat.RagHitQuotaPort.Allocation;
import cn.studykid.multiagent.ports.agentchat.RagRetrievalPort;
import cn.studykid.multiagent.ports.contextwiring.RequestAssemblyException;
import cn.studykid.multiagent.ports.contextwiring.RequestAssemblyInput;
import cn.studykid.multiagent.ports.contextwiring.RequestAssemblyPort;
import cn.studykid.multiagent.ports.contextwiring.RequestAssemblyResult;
import cn.studykid.multiagent.ports.metrics.ScenarioTag;
import cn.studykid.multiagent.ports.model.ChatStreamGateway;
import cn.studykid.multiagent.ports.model.ModelGateway.ModelGatewayException;
import cn.studykid.multiagent.ports.model.ModelMessage;
import cn.studykid.multiagent.ports.model.ModelRequest;
import cn.studykid.multiagent.ports.model.ModelResponse;
import cn.studykid.multiagent.ports.persistence.AgentQueryPort;
import cn.studykid.multiagent.ports.persistence.ExecutionSnapshotPort;
import cn.studykid.multiagent.ports.persistence.RunClaimPort;
import cn.studykid.multiagent.ports.persistence.RunClaimPort.LeasedTask;
import cn.studykid.multiagent.worker.execute.AgentModelConfigAssembler;
import cn.studykid.multiagent.worker.execute.AgentModelConfigAssembler.AgentModelSettings;
import cn.studykid.multiagent.worker.execute.ToolInvocationGate;
import cn.studykid.multiagent.worker.execute.ToolInvocationGate.ToolInvocationResult;
import cn.studykid.multiagent.worker.execute.ToolOutcomeRenderer;

/**
 * 请求级 SSE Agent 编排（真·路由 → RAG → Skill 指引 → Tool 循环 → 流式回答 → 审计）。
 *
 * <p><b>一次 HTTP 请求内推完 13 类事件</b>，不写 Outbox / 不发 MQ。
 * Run 审计走 {@link AgentChatAuditPort} 的两段式：
 * {@code RUNNING} 占位（工具账本外键需要）→ 终态收敛。
 *
 * <p><b>安全边界</b>：
 * <ul>
 *   <li>tenantId 只来自 {@link AgentChatRequest.Context}，绝不取请求体；</li>
 *   <li>RAG 命中是<b>外部数据</b>，经 {@link PromptGuard#wrapToolResult} 围栏后
 *       以消息形式注入，<b>不进可信 systemPrompt</b>；零命中是合法 info；</li>
 *   <li>工具执行只走 {@link ToolInvocationGate}，并传显式
 *       {@link ExecutionScope}（固定版本 + 闭包指纹），不另开旁路；</li>
 *   <li>工具结果回灌复用 {@link ToolOutcomeRenderer#renderRound}，
 *       保证 assistant(tool_calls) 与 tool 消息成对且标识一致；</li>
 *   <li>推理草稿仅 {@code context.thinkingAllowed()} 时下发，租户通道恒不下发。</li>
 *   <li>请求侧压缩态是<b>不透明外部数据</b>：控制器只逐字透传，本类在拿到冻结策略
 *       指纹后统一做六项不可信校验，验过才下传，验不过静默退回全量历史。</li>
 * </ul>
 *
 * <p><b>RAG 配额边界</b>：S3-M10 起，命中在围栏注入前先经 {@link RagHitQuotaPort} 按 token
 * 配额裁剪（去重 / 确定性排序 / 动态 top-k / 单块截断 / 降级引用），配额参数与计数口径全部
 * 由组合根持有；关闭态 / 未装配 / 配额异常一律回退 legacy 固定口径（逐位等价 + fail-soft）。
 * 无论走哪条路径，载荷都经 {@link PromptGuard#wrapToolResult} 包成不可信 USER 围栏，
 * ⛔ 绝不裸拼、绝不进 systemPrompt；被全部裁剪时也不注入空围栏。
 *
 * <p><b>压缩态边界</b>：S3-M8-b 起，模型请求只经 {@link RequestAssemblyPort} 统一装配
 * （关闭态返回 legacy 同一引用，逐位等价；开启态如实给出 tier / fallback / token）。
 * 轮末经 {@link ContextCheckpointEmitter} 尝试封包：仅当本轮<b>确实装配生效</b>且封包成功，
 * 才在 {@code answer → usage} 之后、{@code done} 之前发射第 14 事件
 * {@code context.checkpoint}，并让 {@code Done} 带 {@code contextStateVersion}。
 * ⚠️ 这是<b>可选轮末增强</b>：无新增 / 抽取回落 / 超长 / 密钥不可用 / 未装配端口一律
 * <b>静默不发</b>（Done 退回 v1 字节），绝不因它中断 RAG / 回答 / 审计主流程（fail-soft）；
 * 与装配入口的 fail-closed（拒绝对话）边界严格分开。旧 13 帧字节、审计两段式、
 * 无 outbox / MQ 全部不变；装配态多一条内部审计事实 {@code CONTEXT_ASSEMBLED}，
 * 发射态再多一条标量审计事实 {@code CONTEXT_CHECKPOINT}（均非 SSE 帧、不含 state 正文）。
 * 策略指纹由中性裁决端口提供，⛔ 编排器不自己拼全局常量、不读算法层配置（ADR-011）。
 *
 * <p><b>fail-closed</b>：路由不确定 / Agent 配置缺失 / 模型请求非法 / 工具闸门抛异常
 * 一律发 {@code error} 并收敛 FAILED；<b>绝不</b>编造命中、不静默降级成「随便选一个」。
 * RAG 服务不可用与单个工具失败是<b>组件级</b>失败，Agent 可继续生成最终回答。
 */
public class AgentChatOrchestrator implements AgentChatOrchestratorPort {

    private static final Logger log = LoggerFactory.getLogger(AgentChatOrchestrator.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 候选 Agent 上限（路由模型提示词必须有界）。 */
    private static final int MAX_CANDIDATES = 50;
    /** RAG topK 合法上限（与 rag RetrievalRequestDto 一致）。 */
    private static final int MAX_TOP_K = 50;
    /**
     * <b>Legacy-only</b> 固定注入口径：仅用于关闭态 / 端口未装配 / 配额 fail-soft 回退，
     * 保证 G1 影子期请求字节逐位等价。开启态由 {@link RagHitQuotaPort} + yml 配额驱动，
     * ⛔ 不再用这两个常量限制正式注入（M10 / D51）。
     */
    private static final int MAX_INJECT_HITS = 8;
    private static final int MAX_HIT_TEXT = 1200;
    /** SSE 事件里展示的工具输出上限（不回显完整外部数据）。 */
    private static final int MAX_TOOL_RESULT_PREVIEW = 300;
    /** 错误文案上限（不把供应商报文原文整段推给前端）。 */
    private static final int MAX_ERROR_MESSAGE = 240;
    /** 请求级 Run 的租约：仅作防御，真正防重靠 lease_owner 排除。 */
    private static final Duration DEFAULT_LEASE = Duration.ofMinutes(15);
    private static final int DEFAULT_MAX_TOOL_ROUNDS = 3;
    private static final int DEFAULT_TOP_K = 5;

    private static final PromptGuard RAG_GUARD = new PromptGuard();

    private final AgentChatAuditPort audit;
    private final RagRetrievalPort rag;
    private final ChatStreamGateway chatGateway;
    private final AgentRouter router;
    private final AgentQueryPort agents;
    private final ExecutionSnapshotPort snapshots;
    private final ClosureResolver closureResolver;
    private final AgentModelConfigAssembler modelConfig;
    private final ToolInvocationGate toolGate;
    private final @NonNull ContextStateVerifier stateVerifier;
    private final @NonNull ContextPolicyVersionPort policyVersions;
    /**
     * S3-M8-b L2 统一请求装配入口（{@code null} = 未装配 → 完全走 legacy 基线）。
     *
     * <p>⚠️ <b>唯一送模型的请求来自 {@link #assembleRequest}</b>：关闭态它返回 legacy
     * 基线同一引用，逐位等价由构造级保证；开启态只切换上下文字段并如实返回
     * tier / fallback / token 计数，供轮末的 checkpoint 发射使用。
     * worker 只认识 {@code agent-ports} 的中性端口，不碰算法层类型（ADR-011）。
     */
    private final @Nullable RequestAssemblyPort requestAssemblyPort;
    /**
     * S3-M8-b-2 轮末压缩态封包端口（{@code null} = 未装配 → 不发射 checkpoint）。
     *
     * <p>⚠️ <b>发射是可选增强</b>：本字段只在成功收口、且 {@link #lastAssembly} 判定装配
     * 生效时被调用；返回 null / 抛异常都静默降级为「不发第 14 事件、Done 走 v1 字节」，
     * ⛔ 绝不改变已产出的 answer / usage / 审计终态。worker 只认识中性端口，不碰算法层
     * 的 CheckpointDelta / StructuredState / HMAC 信封（ADR-011）。
     */
    private final @Nullable ContextCheckpointEmitter checkpointEmitter;
    /**
     * S3-M10 RAG 命中区配额端口（{@code null} = 未装配 → 完全走 legacy 固定口径）。
     *
     * <p>⚠️ <b>组件级增强</b>：配额只裁注入前的不可信命中列表，失败一律回退 legacy，
     * ⛔ 绝不发 error 帧 / FAILED 审计。worker 只构造中性 {@code RagHitQuotaPort.Hit}
     * 并消费中性 {@link Allocation}，算法层类型与配额参数都由组合根持有（ADR-011）。
     */
    private final @Nullable RagHitQuotaPort ragHitQuotaPort;
    private final int maxToolRounds;
    private final int defaultTopK;
    private final Duration lease;

    /**
     * 生产构造（组合根用）。⚠️ 本类另有测试用全参构造，故标 {@code @Autowired}
     * 固定生产入口，避免多构造下的装配歧义。
     */
    @Autowired
    public AgentChatOrchestrator(@NonNull AgentChatAuditPort audit,
                                 @NonNull RagRetrievalPort rag,
                                 @NonNull ChatStreamGateway chatGateway,
                                 @NonNull AgentRouter router,
                                 @NonNull AgentQueryPort agents,
                                 @NonNull ExecutionSnapshotPort snapshots,
                                 @NonNull ClosureResolver closureResolver,
                                 @NonNull AgentModelConfigAssembler modelConfig,
                                 @NonNull ToolInvocationGate toolGate,
                                 @NonNull ContextStateVerifier stateVerifier,
                                 @NonNull ContextPolicyVersionPort policyVersions,
                                 @Nullable RequestAssemblyPort requestAssemblyPort,
                                 @Nullable ContextCheckpointEmitter checkpointEmitter,
                                 @Nullable RagHitQuotaPort ragHitQuotaPort) {
        this(audit, rag, chatGateway, router, agents, snapshots, closureResolver,
                modelConfig, toolGate, DEFAULT_MAX_TOOL_ROUNDS, DEFAULT_TOP_K, DEFAULT_LEASE,
                stateVerifier, policyVersions, requestAssemblyPort, checkpointEmitter,
                ragHitQuotaPort);
    }

    /** 全参构造（测试可收紧轮次 / 租约以构造挤出压力）。 */
    public AgentChatOrchestrator(@NonNull AgentChatAuditPort audit,
                                 @NonNull RagRetrievalPort rag,
                                 @NonNull ChatStreamGateway chatGateway,
                                 @NonNull AgentRouter router,
                                 @NonNull AgentQueryPort agents,
                                 @NonNull ExecutionSnapshotPort snapshots,
                                 @NonNull ClosureResolver closureResolver,
                                 @NonNull AgentModelConfigAssembler modelConfig,
                                 @NonNull ToolInvocationGate toolGate,
                                 int maxToolRounds,
                                 int defaultTopK,
                                 @NonNull Duration lease,
                                 @NonNull ContextStateVerifier stateVerifier,
                                 @NonNull ContextPolicyVersionPort policyVersions,
                                 @Nullable RequestAssemblyPort requestAssemblyPort,
                                 @Nullable ContextCheckpointEmitter checkpointEmitter,
                                 @Nullable RagHitQuotaPort ragHitQuotaPort) {
        if (maxToolRounds < 0 || defaultTopK < 1 || defaultTopK > MAX_TOP_K) {
            throw new IllegalArgumentException("编排参数非法: rounds=" + maxToolRounds
                    + ", topK=" + defaultTopK);
        }
        this.audit = audit;
        this.rag = rag;
        this.chatGateway = chatGateway;
        this.router = router;
        this.agents = agents;
        this.snapshots = snapshots;
        this.closureResolver = closureResolver;
        this.modelConfig = modelConfig;
        this.toolGate = toolGate;
        this.maxToolRounds = maxToolRounds;
        this.defaultTopK = defaultTopK;
        this.lease = lease;
        this.stateVerifier = Objects.requireNonNull(stateVerifier, "stateVerifier 不得为空");
        this.policyVersions = Objects.requireNonNull(policyVersions, "policyVersions 不得为空");
        this.requestAssemblyPort = requestAssemblyPort;
        this.checkpointEmitter = checkpointEmitter;
        this.ragHitQuotaPort = ragHitQuotaPort;
    }

    @Override
    public void run(@NonNull Context context,
                    @NonNull AgentChatRequest request,
                    @NonNull Sink sink) {
        Instant started = Instant.now();
        RunState state = new RunState(UUID.randomUUID().toString());

        try {
            validate(request);

            // ① 路由：候选必须是「已发布且有执行快照」的 Agent，路由失败 fail-closed。
            List<AgentQueryPort.AgentItem> candidates = publishedCandidates(context.tenantId());
            AgentRouter.Decision decision = router.route(
                    context.tenantId(), request.message(), request.agentId(), candidates);
            state.agentId = decision.agentId();
            state.agentName = nameOf(candidates, decision.agentId());
            state.agentVersion = snapshotVersion(context.tenantId(), decision.agentId(), state);

            // ② 请求侧压缩态净化：在能取得冻结策略指纹的边界统一校验。
            //    控制器传来的原始串在这里被换成「已验状态 JSON 或 null」，下游只见已验载荷。
            ContextStateResolution carryOver = applyContextState(
                    context, request, state.agentId, state.agentVersion,
                    state.settings.modelId(), state.settings.maxTokens());
            state.carryOver = carryOver;
            AgentChatRequest effective = carryOver.request();

            // ③ 审计占位必须在任何工具调用之前存在（tool_operation 外键）。
            state.handle = audit.begin(beginRecord(context, effective, state, started));

            // ④ 协议序：run.start → route。runId 是刚落库的真实行，不是凭空生成后补写。
            sink.accept(new AgentChatEvent.RunStart(state.runId));
            state.events().add(event("ROUTE", Map.of(
                    "agentId", state.agentId,
                    "candidateCount", decision.candidates().size())));
            sink.accept(new AgentChatEvent.Route(state.agentId, state.agentName,
                    decision.reason(), decision.candidates()));

            // ⑤ RAG：组件级失败不终止整轮；零命中是合法 info。
            String ragContext = doRag(context, effective, sink, state);

            // ⑥ Skill：当前语义是「声明式能力闭包 + 提示词行为指引」，真实执行在 Tool。
            emitSkills(state, sink, state.closure);

            // ⑦ 模型循环。carry-over 已验状态作为不可信围栏 USER 块注入（S3-M8-b-2 / D49）：
            //    ⛔ 只取 applyContextState 验过的 stateJson，绝不重新解析原始 request.contextState()；
            //    未装配 / 关闭态 / 丢弃态 stateJson 为 null → 不注入，请求字节与影子期逐位一致。
            List<ModelMessage> messages = buildMessages(
                    effective, ragContext, state.carryOver.stateJson());
            ExecutionScope scope = new ExecutionScope(state.agentId, state.agentVersion,
                    ClosureFingerprint.of(state.closure));
            RunClaimPort.LeasedTask task = new RunClaimPort.LeasedTask(
                    state.runId, state.handle.fencingToken(),
                    started.plus(lease), 0, null);
            runModelLoop(context, effective, sink, state, messages, scope, task);

            // ⑧ 成功收口：answer 用「用户实际看到的 delta 拼接」，保证与已到达增量同前缀。
            sink.accept(new AgentChatEvent.Answer(state.answer().toString()));
            sink.accept(new AgentChatEvent.Usage(state.inputTokens(), state.outputTokens(),
                    state.estimated));
            state.events().add(event("USAGE", Map.of(
                    "inputTokens", state.inputTokens(),
                    "outputTokens", state.outputTokens())));
            // S3-M8-b：装配态如实记录真实档位 / 回退 / token 计数——这是 #73 轮末
            // checkpoint 的唯一数据来源。⛔ 关闭态（含全局熔断）不发此事件，逐位等价。
            // ⚠️ 只记标量事实，绝不把压缩态正文 / 状态串写进审计可信面。
            if (state.lastAssembly != null && state.lastAssembly.assembled()) {
                RequestAssemblyResult a = state.lastAssembly;
                state.events().add(event("CONTEXT_ASSEMBLED", Map.of(
                        "tier", a.tier(),
                        "fallback", a.fallback(),
                        "beforeTokens", a.beforeTokens(),
                        "afterTokens", a.afterTokens(),
                        "compactionCount", a.compactionCount())));
            }
            // S3-M8-b-2 / D49：轮末尝试封包并发射第 14 事件。⛔ 严格置于 usage 之后、done 之前，
            //    保持旧 13 帧相对次序不变，done 仍是唯一应用层终帧。
            //    ⚠️ 可选增强 fail-soft：未装配 / 关闭态 / 无新增 / 抽取回落 / 超长 / 密钥不可用
            //    一律 null → 不发事件、Done 退回 v1 字节，主流程不受影响。
            CheckpointEmission checkpoint = emitCheckpoint(context, state, effective);
            if (checkpoint != null) {
                Emitted emitted = checkpoint.emitted();
                sink.accept(new AgentChatEvent.ContextCheckpoint(
                        emitted.state(), emitted.upToTurn(), emitted.policyVersion(),
                        emitted.tier(), emitted.fallback(), emitted.digest()));
                // 标量审计事实：只记档位 / 游标 / 摘要 / 长度，⛔ 绝不把 state 信封正文写进审计。
                state.events().add(event("CONTEXT_CHECKPOINT", Map.of(
                        "tier", emitted.tier(),
                        "fallback", emitted.fallback(),
                        "upToTurn", emitted.upToTurn() == null ? -1 : emitted.upToTurn(),
                        "digest", emitted.digest(),
                        "stateChars", emitted.state().length())));
            }
            state.events().add(event("DONE", Map.of("truncated", false)));
            audit.finish(state.handle, new AgentChatAuditPort.FinishRecord(
                    "SUCCEEDED", null,
                    resultPayload(state, "STOP", null),
                    state.events(), state.steps(), Instant.now()));
            state.finished = true;
            // 版本与 checkpoint 同源：只有成功发射才带 digest；否则走 v1 兼容构造（旧字节冻结）。
            sink.accept(new AgentChatEvent.Done(
                    false, checkpoint == null ? null : checkpoint.version()));

        } catch (RuntimeException e) {
            handleFailure(context, e, sink, state);
        }
    }

    // ── 阶段 ①／② ────────────────────────────────────────────────────

    private static void validate(@NonNull AgentChatRequest request) {
        if (request.message() == null || request.message().isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        if (request.message().length() > 32_000) {
            throw new IllegalArgumentException("message 过长（上限 32000 字符）");
        }
        if (request.history() != null && request.history().size() > 100) {
            throw new IllegalArgumentException("history 轮次过多（上限 100）");
        }
        for (AgentChatRequest.Turn turn : request.history()) {
            if (turn == null || turn.content() == null) {
                throw new IllegalArgumentException("history 含空消息");
            }
            if (turn.content().length() > 32_000) {
                throw new IllegalArgumentException("history 单条消息过长");
            }
            if (!"user".equals(turn.role()) && !"assistant".equals(turn.role())) {
                throw new IllegalArgumentException("history.role 只能是 user / assistant");
            }
        }
    }

    /**
     * 请求侧压缩态的<b>唯一净化点</b>（ADR-013 D49）。
     *
     * <p>无 state 时返回传入的同一请求引用（老客户端 / 关闭态逐位等价）；有 state 时
     * 先取当前已裁决策略指纹，再过六项不可信校验：通过才把已验状态 JSON 下传，
     * 任一项失败都静默换成 {@code null} 并退回全量历史。⛔ 本方法不碰 Sink、
     * 不发事件、不抛 HTTP 异常、不打印原始状态串。
     *
     * <p>包可见仅供行为测试从同一入口断言「验过才下传、丢弃传 null」；生产路径
     * 由 {@link #run} 唯一调用。
     */
    @NonNull ContextStateResolution applyContextState(
            @NonNull Context context,
            @NonNull AgentChatRequest request,
            @NonNull String agentId,
            int agentVersion,
            @NonNull String modelId,
            int maxOutputTokens) {
        Objects.requireNonNull(context, "context 不得为空");
        Objects.requireNonNull(request, "request 不得为空");
        if (request.contextState() == null) {
            return new ContextStateResolution(request, null, null, null, null, null);
        }
        try {
            String expected = policyVersions.resolve(new ContextPolicyVersionPort.Input(
                    context.tenantId(), agentId, agentVersion, modelId, maxOutputTokens));
            Result result = stateVerifier.verify(new ContextStateVerifier.Input(
                    request.contextState(), context.tenantId(),
                    request.history().size(), expected));
            if (result instanceof Accepted accepted) {
                return new ContextStateResolution(
                        withContextState(request, accepted.stateJson()),
                        accepted.stateJson(), accepted.digest(), accepted.policyVersion(),
                        accepted.upToTurn(), null);
            }
            DropReason reason = result instanceof Discarded discarded ? discarded.reason() : null;
            if (reason != null) {
                log.debug("Agent 编排静默丢弃客户端压缩态: domain={}, reason={}",
                        context.domain(), reason);
            }
            return new ContextStateResolution(
                    withContextState(request, null), null, null, null, null, reason);
        } catch (RuntimeException e) {
            // 校验器 / 裁决端口自身出错也按「无可用压缩态」处理：
            // 请求照常跑全量历史，绝不为可选增强发 error 帧或中断审计。
            log.debug("Agent 编排压缩态校验不可用，退回全量历史: domain={}, kind={}",
                    context.domain(), e.getClass().getSimpleName());
            return new ContextStateResolution(
                    withContextState(request, null), null, null, null, null, null);
        }
    }

    /**
     * 全参同构副本（铁律 #10）：只换 {@code contextState}，其它字段逐字带过。
     * ⚠️ 绝不在这里重排 history / 顺手 trim message，否则「净化」会变成改请求字节。
     */
    private static @NonNull AgentChatRequest withContextState(
            @NonNull AgentChatRequest request, @Nullable String stateJson) {
        return new AgentChatRequest(
                request.message(), request.history(), request.agentId(), request.topK(),
                request.thinkingMode(), request.requestedTenantId(), request.scenario(),
                stateJson);
    }

    /**
     * 一次请求的压缩态净化结果。{@link #request()} 是此后唯一向下游传递的请求对象：
     * {@code contextState} 只可能是已验状态 JSON 或 {@code null}（全量历史降级）。
     */
    record ContextStateResolution(
            @NonNull AgentChatRequest request,
            @Nullable String stateJson,
            @Nullable String digest,
            @Nullable String policyVersion,
            @Nullable Integer upToTurn,
            @Nullable DropReason dropReason) {
    }

    /** 候选来自 AgentQueryPort（RLS 内查询），只保留能解析出已发布执行快照的 Agent。 */
    private @NonNull List<AgentQueryPort.AgentItem> publishedCandidates(
            @NonNull String tenantId) {
        List<AgentQueryPort.AgentItem> result = new ArrayList<>();
        for (AgentQueryPort.AgentItem item : agents.listAgents(
                tenantId, null, MAX_CANDIDATES).items()) {
            if (snapshots.resolveSnapshot(tenantId, item.agentId()).isPresent()) {
                result.add(item);
            }
        }
        return List.copyOf(result);
    }

    /** 解析固定版本闭包 + 模型配置并放入 state；快照不存在即 CONFIG_UNAVAILABLE。 */
    private int snapshotVersion(@NonNull String tenantId, @NonNull String agentId,
                                @NonNull RunState state) {
        ExecutionSnapshotPort.SnapshotRef snapshot = snapshots
                .resolveSnapshot(tenantId, agentId)
                .orElseThrow(() -> new ConfigUnavailableException(
                        "Agent 没有已发布的执行快照: " + agentId));
        state.snapshotId = snapshot.snapshotId();
        state.closure = closureResolver.resolve(tenantId, agentId, snapshot.agentVersion());
        state.settings = modelConfig.assemble(tenantId, agentId, snapshot.agentVersion())
                .orElseThrow(() -> new ConfigUnavailableException(
                        "Agent 缺少已发布的 instructions / modelRef / generationParams: " + agentId));
        return snapshot.agentVersion();
    }

    private @NonNull BeginRecord beginRecord(
            @NonNull Context context,
            @NonNull AgentChatRequest request,
            @NonNull RunState state,
            @NonNull Instant started) {
        int topK = normalizeTopK(request.topK());
        Map<String, Object> inputPayload = new LinkedHashMap<>();
        inputPayload.put("channel", "agent-chat");
        inputPayload.put("domain", context.domain().name());
        inputPayload.put("agentId", state.agentId);
        inputPayload.put("agentVersion", state.agentVersion);
        inputPayload.put("messageDigest", digest(request));
        inputPayload.put("historyTurns", request.history().size());
        inputPayload.put("topK", topK);
        inputPayload.put("thinkingMode", request.thinkingMode());
        inputPayload.put("scenario", ScenarioTag.normalize(request.scenario()));
        return new AgentChatAuditPort.BeginRecord(
                state.runId, context.tenantId(), state.agentId, state.agentVersion,
                state.snapshotId, digest(request), ScenarioTag.normalize(request.scenario()),
                inputPayload, UUID.randomUUID().toString(), 0L, started, started.plus(lease));
    }

    // ── 阶段 ④ RAG ────────────────────────────────────────────────────

    /**
     * @return 围栏后的不可信上下文；零命中 / 组件失败均返回 null（合法 info）。
     */
    private @Nullable String doRag(@NonNull Context context,
                                   @NonNull AgentChatRequest request,
                                   @NonNull Sink sink,
                                   @NonNull RunState state) {
        int topK = normalizeTopK(request.topK());
        sink.accept(new AgentChatEvent.RagQuery(request.message(), topK));
        state.events().add(event("RAG_QUERY", Map.of("topK", topK)));
        Instant ragStart = Instant.now();

        RagRetrievalPort.RagResult result;
        try {
            result = rag.retrieve(context.tenantId(), context.bearerToken(),
                    request.message(), topK);
        } catch (RagRetrievalPort.RagUnavailableException e) {
            // 组件级失败：如实可见，绝不伪造命中，也不静默当零命中。
            state.events().add(event("RAG_UNAVAILABLE", Map.of("reason", normalize(e.getMessage()))));
            sink.accept(new AgentChatEvent.Skill("rag", "RAG 检索", "error",
                    AgentChatEvent.StepStatus.ERROR, null, null, normalize(e.getMessage())));
            state.step("RAG_UNAVAILABLE", ragStart, Instant.now());
            sink.accept(new AgentChatEvent.RagHits(List.of(), false));
            state.events().add(event("RAG_HITS", Map.of("hits", 0, "injected", false)));
            return null;
        }

        List<AgentChatEvent.Hit> hits = result.hits() == null
                ? List.of() : result.hits();
        state.ragHits = hits.size();
        // ── S3-M10：注入前先按 token 配额裁剪（D51）─────────────────────
        // ⛔ 关闭态 / 端口未装配 / 配额异常一律 null → legacy 固定口径，逐位等价且 fail-soft。
        Allocation allocation = hits.isEmpty() ? null : applyRagQuota(context, state, hits);
        // 全部命中被配额丢弃时不注入空围栏；injected 反映真正送进模型的载荷，而非仅「检索到命中」。
        boolean injected = !hits.isEmpty()
                && (allocation == null
                    || !allocation.kept().isEmpty() || !allocation.refs().isEmpty());
        state.ragInjected = injected;
        sink.accept(new AgentChatEvent.RagHits(hits, injected));
        Map<String, Object> ragAudit = new LinkedHashMap<>();
        ragAudit.put("hits", hits.size());
        ragAudit.put("injected", injected);
        ragAudit.put("returnedCount", result.returnedCount());
        ragAudit.put("versionMismatchDetected", result.versionMismatchDetected());
        ragAudit.put("answerContentSuppressed", result.answerContentSuppressed());
        state.events().add(event("RAG_HITS", ragAudit));
        if (allocation != null) {
            state.events().add(event("RAG_QUOTA", ragQuotaAudit(allocation)));
        }
        state.step("RAG", ragStart, Instant.now());
        if (!injected) {
            return null;
        }
        String payload = allocation == null
                ? serializeHits(hits) : RagHitQuotaPort.payloadJson(allocation);
        return RAG_GUARD.wrapToolResult("rag", payload);
    }

    /**
     * <b>Legacy-only</b> RAG 载荷渲染：只带白名单字段 + 固定截断，围栏由 PromptGuard 完成。
     * ⚠️ 固定口径必须原样保留——关闭态 / 未装配 / 配额 fail-soft 都靠它保证 G1 逐位等价。
     * 根字段与配额路径共用 {@link RagHitQuotaPort#RAG_KIND} / {@link RagHitQuotaPort#RAG_NOTE}，
     * 避免两条渲染路径的 envelope 漂移。
     */
    private static @NonNull String serializeHits(@NonNull List<AgentChatEvent.Hit> hits) {
        ObjectNode root = JSON.createObjectNode();
        root.put("kind", RagHitQuotaPort.RAG_KIND);
        root.put("note", RagHitQuotaPort.RAG_NOTE);
        ArrayNode array = root.putArray("hits");
        for (AgentChatEvent.Hit hit : hits.subList(0, Math.min(hits.size(), MAX_INJECT_HITS))) {
            ObjectNode node = array.addObject();
            node.put("chunkId", hit.chunkId());
            node.put("text", truncate(hit.text(), MAX_HIT_TEXT));
            node.put("score", hit.score());
            node.put("locator", hit.locator());
            node.put("edition", hit.edition());
            node.put("role", hit.role());
            node.put("reason", hit.reason());
        }
        return JSON.writeValueAsString(root);
    }

    /**
     * 通过中性端口请求 token 配额裁剪。worker 只映射字段 / 消费结果，⛔ 不认识算法层类型、
     * 不读配额参数、不重算三硬不变量。任何异常一律降级为 {@code null}（legacy），
     * 与 RAG 不可用的组件级语义一致：绝不中断回答或改写审计终态。
     */
    private @Nullable Allocation applyRagQuota(@NonNull Context context,
                                               @NonNull RunState state,
                                               @NonNull List<AgentChatEvent.Hit> hits) {
        if (ragHitQuotaPort == null) {
            return null;
        }
        try {
            List<RagHitQuotaPort.Hit> mapped = new ArrayList<>(hits.size());
            for (AgentChatEvent.Hit hit : hits) {
                mapped.add(new RagHitQuotaPort.Hit(
                        hit.chunkId(), hit.text(), hit.score(), hit.locator(),
                        hit.edition(), hit.role(), hit.reason()));
            }
            Allocation allocation = ragHitQuotaPort.applyQuota(
                    new RagHitQuotaPort.Input(context.tenantId(), "chat:" + state.runId, mapped));
            return allocation == null || !quotaAllocationIsCoherent(allocation) ? null : allocation;
        } catch (RuntimeException e) {
            // 组件级 fail-soft：不打印 RAG 正文 / 命中元数据，只记异常类型供排障。
            log.debug("L2 RAG 配额跳过（组件级 fail-soft）：kind={}", e.getClass().getSimpleName());
            return null;
        }
    }

    /** 防御性核对端口结果：统计不闭合就当没配额，绝不把假数字带进审计 / 请求。 */
    private static boolean quotaAllocationIsCoherent(@NonNull Allocation allocation) {
        if (allocation.afterTokens() > allocation.beforeTokens()
                || allocation.savedTokens() != allocation.beforeTokens() - allocation.afterTokens()) {
            return false;
        }
        for (RagHitQuotaPort.KeptRagHit hit : allocation.kept()) {
            if (hit == null || hit.chunkId().isBlank() || hit.locator().isBlank()
                    || hit.text() == null || !Double.isFinite(hit.score()) || hit.tokens() < 0) {
                return false;
            }
        }
        for (RagHitQuotaPort.RefRagHit ref : allocation.refs()) {
            if (ref == null || ref.chunkId().isBlank() || ref.locator().isBlank()
                    || ref.tokens() < 0) {
                return false;
            }
        }
        return true;
    }

    /** 只审计标量事实，⛔ 绝不写 RAG 正文 / locator / 租户身份。 */
    private static @NonNull Map<String, Object> ragQuotaAudit(@NonNull Allocation allocation) {
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("kept", allocation.kept().size());
        audit.put("refs", allocation.refs().size());
        audit.put("beforeTokens", allocation.beforeTokens());
        audit.put("afterTokens", allocation.afterTokens());
        audit.put("savedTokens", allocation.savedTokens());
        audit.put("dropped", allocation.droppedCount());
        audit.put("duplicates", allocation.duplicateCount());
        audit.put("truncated", allocation.truncated());
        return audit;
    }

    // ── 阶段 ⑤ Skill ──────────────────────────────────────────────────

    /**
     * 当前 Skill 的诚实语义：<b>声明式能力闭包 + 提示词行为指引</b>。
     * 这里不做「Skill 执行器」的伪装——真实副作用只发生在 Tool 闸门里。
     * 事件用来展示闭包装载与可用指引，不虚构执行结果。
     */
    private static void emitSkills(@NonNull RunState state, @NonNull Sink sink,
                                   @NonNull EffectiveClosure closure) {
        for (SkillBinding skill : closure.skills()) {
            String name = skill.skillId();
            String purpose = skill.description() == null
                    ? "" : skill.description().purpose();
            sink.accept(new AgentChatEvent.Skill(skill.skillId(), name, "load",
                    AgentChatEvent.StepStatus.PENDING,
                    purpose.isBlank() ? "能力闭包已声明" : purpose, null, null));
            state.events().add(event("SKILL_LOAD", Map.of(
                    "skillId", skill.skillId(), "version", skill.version())));

            String usage = skill.description() == null || skill.description().usage().isEmpty()
                    ? "按已声明工具执行；描述不构成授权。"
                    : skill.description().usage().stream()
                            .map(u -> u.when() + " → " + u.how())
                            .reduce((a, b) -> a + "；" + b)
                            .orElse("");
            sink.accept(new AgentChatEvent.Skill(skill.skillId(), name, "result",
                    AgentChatEvent.StepStatus.SUCCESS, null,
                    truncate(usage, 600), null));
            state.events().add(event("SKILL_READY", Map.of(
                    "skillId", skill.skillId(), "declaredTools", skill.declaredTools().size())));
            state.step("SKILL_" + skill.skillId(), Instant.now(), Instant.now());
        }
    }

    // ── 阶段 ⑥ 模型 / 工具循环 ────────────────────────────────────────

    private static @NonNull List<ModelMessage> buildMessages(
            @NonNull AgentChatRequest request,
            @Nullable String ragContext,
            @Nullable String carryOverStateJson) {
        List<ModelMessage> messages = new ArrayList<>();
        for (AgentChatRequest.Turn turn : request.history()) {
            messages.add("assistant".equals(turn.role())
                    ? ModelMessage.assistant(turn.content())
                    : ModelMessage.user(turn.content()));
        }
        // ⚠️ RAG 上下文以 USER 消息注入并自带不可信围栏；绝不拼进 systemPrompt。
        if (ragContext != null) {
            messages.add(ModelMessage.user(ragContext));
        }
        // ⚠️ 上一轮已验压缩态经 PromptGuard 包成不可信围栏 USER 块（D49）：
        //    绝不拼 systemPrompt、绝不裸拼、绝不把 HMAC 信封原文当指令执行。
        //    放在历史之后、当前输入之前——作为「历史的事实参考」，当前输入永不裁剪。
        if (carryOverStateJson != null && !carryOverStateJson.isEmpty()) {
            messages.add(ModelMessage.user(RAG_GUARD.wrapCompactedContext(carryOverStateJson)));
        }
        messages.add(ModelMessage.user(request.message()));
        return messages;
    }

    private void runModelLoop(@NonNull Context context,
                              @NonNull AgentChatRequest request,
                              @NonNull Sink sink,
                              @NonNull RunState state,
                              @NonNull List<ModelMessage> messages,
                              @NonNull ExecutionScope scope,
                              @NonNull LeasedTask task) {
        // 最后一轮强制无工具：保证一定收敛出自然语言终答，而不是在工具循环里无限打转。
        int totalRounds = maxToolRounds + 1;
        for (int round = 1; round <= totalRounds; round++) {
            Map<String, Object> schemas = round <= maxToolRounds
                    ? state.settings.toolSchemas() : Map.of();
            // S3-M8-b / D38：legacy 基线是关闭态逐位等价的唯一真源，也是开启态 6 个
            // 非上下文字段的唯一来源；⛔ 不再直接把它送模型（那会让装配层空转）。
            ModelRequest legacyRequest = new ModelRequest(
                    state.settings.modelId(), state.settings.systemPrompt(), "",
                    state.settings.temperature(), state.settings.maxTokens(),
                    state.settings.timeout(), schemas, List.copyOf(messages));
            // ⚠️ 唯一送模型的请求来自统一装配入口：关闭态返回 legacy 同一引用（逐位等价），
            //    开启态只切换上下文字段并如实给出 tier / fallback / token 计数；
            //    装配被拒抛 RequestAssemblyException → 顶层 fail-closed，⛔ 绝不静默回退。
            RequestAssemblyResult assembly = assembleRequest(
                    context, state, messages, schemas, task, legacyRequest);
            state.lastAssembly = assembly;
            ModelRequest modelRequest = assembly.request();
            Instant roundStart = Instant.now();
            ModelResponse response = chatGateway.streamChat(modelRequest,
                    new ChatStreamGateway.StreamDeltaConsumer() {
                        @Override
                        public void onDelta(@NonNull String token) {
                            if (token.isEmpty()) {
                                return;
                            }
                            // 所有轮次的正文增量都给用户可见，answer 最终用同一拼接串补齐。
                            state.answer().append(token);
                            sink.accept(new AgentChatEvent.Delta(token));
                        }

                        @Override
                        public void onThinking(@NonNull String token) {
                            // ⛔ 推理草稿仅平台管理员可见；租户通道恒丢弃。
                            if (!context.thinkingAllowed() || token.isEmpty()) {
                                return;
                            }
                            sink.accept(new AgentChatEvent.Thinking(token));
                        }
                    });
            state.inputTokens += response.usage().inputTokens();
            state.outputTokens += response.usage().outputTokens();
            // ⚠️ P1-4（2026-11-17）：任一环节是估算值，整轮就标为估算 ——
            //    只要有一次是猜的，给出「精确数字」就是在骗人。
            if (response.usage().estimated()) {
                state.estimated = true;
            }
            state.modelRounds = round;
            state.events().add(event("MODEL_ROUND", Map.of(
                    "round", round,
                    "toolCalls", response.toolCalls().size(),
                    "finishReason", response.finishReason().name())));
            state.step("MODEL_ROUND_" + round, roundStart, Instant.now());

            if (response.finishReason() == ModelResponse.FinishReason.CONTENT_FILTER) {
                throw new ContentBlockedException("模型输出被内容安全策略拦截");
            }
            if (response.toolCalls().isEmpty()) {
                return;
            }
            if (round > maxToolRounds) {
                // 无工具 Schema 却仍报工具调用：守住协议，fail-closed。
                throw new ToolGateException("模型在无工具轮次仍请求工具调用（已 fail-closed）");
            }

            Map<String, ModelResponse.ToolCallRequest> calls = withStableCallIds(
                    response.toolCalls());
            for (Map.Entry<String, ModelResponse.ToolCallRequest> entry : calls.entrySet()) {
                // ⚠️ toolCallId 取 Map 的 key，不取 call.callId()：
                //   calls 已由 withStableCallIds 归一（key == 归一后的 callId，恒非 null），
                //   而 ModelResponse.ToolCallRequest.callId 的契约是 @Nullable（模型可能不返回 id），
                //   直接读它会把 null 灌进 AgentChatEvent.Tool 的 @NonNull toolCallId。
                String callId = entry.getKey();
                ModelResponse.ToolCallRequest call = entry.getValue();
                sink.accept(new AgentChatEvent.Tool(callId, call.toolName(), "call",
                        AgentChatEvent.StepStatus.PENDING, argumentsJson(call.arguments()),
                        null, null));
                if (DelegationToolSchema.TOOL_NAME.equals(call.toolName())) {
                    sink.accept(new AgentChatEvent.Handoff(state.agentId,
                            "模型请求委派至子 Agent（授权由委派闸门判定）"));
                }
            }
            state.events().add(event("TOOL_CALL", Map.of(
                    "round", round, "calls", calls.size())));

            Instant toolStart = Instant.now();
            ToolInvocationResult toolResult = toolGate.invokeAll(
                    context.tenantId(), task, calls, scope);
            state.toolRounds++;
            emitToolResults(sink, state, calls, toolResult);
            state.events().add(event("TOOL_ROUND", Map.of(
                    "round", round,
                    "succeeded", toolResult.succeededCount(),
                    "failed", toolResult.failedCount(),
                    "rejected", toolResult.rejectedCount(),
                    "parked", toolResult.hasParked())));
            state.step("TOOL_ROUND_" + round, toolStart, Instant.now());

            // 复用共享渲染器：assistant(tool_calls) + tool 成对、标识一致、不可信标记齐全。
            messages.addAll(ToolOutcomeRenderer.renderRound(calls, toolResult));
        }
    }

    /**
     * S3-M8-b L2 统一装配调用点：把 legacy 基线 + 六块上下文交给中性端口（D38 / G1 A.5）。
     *
     * <p>⚠️ <b>与 L1/L4 消息映射的差异</b>：L2 是请求级多轮对话，
     * {@link #buildMessages} 产出的 {@code messages} 已按对话顺序包含「历史轮 → 可选 RAG 围栏块
     * → 本轮输入」，工具回灌追加在其后（assistant tool_calls + tool 成对）。因此：
     * <ul>
     *   <li>{@code historyMessages} = 整段 messages（含 RAG 围栏块与当前输入）；</li>
     *   <li>{@code firstUserTurn / currentInput / ragBlocks} 留空：⛔ 绝不把当前输入或 RAG
     *       再渲染一遍——RAG 已由 {@link RAG_GUARD} 按 {@code wrapToolResult} 围栏，
     *       若再进 {@code ragBlocks} 会被 {@code ContextLayout} 二次包裹，关闭态也会字节漂移；</li>
     *   <li>装配内核的整轮切分器把所有 USER 判为 ANCHOR 永不裁，
     *       故本轮输入与 RAG 围栏块在开启态也绝不丢失（ADR-011：worker 不碰算法层类型）。</li>
     * </ul>
     *
     * <p>⚠️ <b>关闭等价桥</b>：端口未装配时返回 {@code passthrough(legacyRequest)} 同一引用；
     * 端口装配但 {@code agent.context.enabled=false} 时由 adapter 第一行短路，同样同一引用。
     * ⛔ 本方法不重复任何 enabled 判定（那会与 adapter 漂移）。
     *
     * <p>⚠️ <b>carry-over 来源</b>：只读 {@code state.carryOver}（M8-a 已验载荷），
     * ⛔ 绝不重新解析原始 {@code request.contextState()}；本批不消费 delta（#73 才发射）。
     */
    private @NonNull RequestAssemblyResult assembleRequest(
            @NonNull Context context,
            @NonNull RunState state,
            @NonNull List<ModelMessage> messages,
            @NonNull Map<String, Object> schemas,
            @NonNull LeasedTask task,
            @NonNull ModelRequest legacyRequest) {
        if (requestAssemblyPort == null) {
            return RequestAssemblyResult.passthrough(legacyRequest);
        }
        return requestAssemblyPort.assemble(new RequestAssemblyInput(
                context.tenantId(), "chat:" + state.runId,
                state.agentId, state.agentVersion, state.settings.modelId(),
                state.settings.systemPrompt(), schemas,
                /* firstUserTurn */ "", /* memoryBlock */ "", /* ragBlocks */ List.of(),
                List.copyOf(messages), /* currentInput */ "",
                state.settings.maxTokens(), task.fencingToken(),
                /* upToSeq */ -1L,
                state.carryOver == null ? null : state.carryOver.stateJson(),
                /* priorCompactions */ 0, legacyRequest));
    }

    /**
     * S3-M8-b-2 轮末压缩态封包（D49）。<b>可选增强 / fail-soft</b>：任何一步不满足条件或抛异常
     * 都返回 {@code null}，让 L2 静默不发第 14 事件、Done 退回 v1 字节，⛔ 绝不改变已产出的
     * answer / usage / 审计终态。
     *
     * <p>⚠️ <b>发射前置条件</b>（缺一即不发）：
     * <ol>
     *   <li>端口已装配（{@code checkpointEmitter != null}）；</li>
     *   <li>本轮<b>装配确实生效</b>（{@code lastAssembly.assembled()}）——关闭态 / 策略 OFF /
     *       Agent 关闭时恒 false，零封包、零事件，逐位等价；</li>
     *   <li>{@code policyVersion} 已由 D17 唯一裁决端口取得（与接收侧同一来源），
     *       裁决不到就 null → 不发（否则发出去的 state 下一轮必被「版本不符」丢弃）；</li>
     *   <li>封包算法层判定有新增可吸收状态（否则返回 null）。</li>
     * </ol>
     *
     * <p>⚠️ <b>seq 坐标系</b>：传纯对话历史（history turn + 当前输入，<b>不含</b> RAG 围栏 /
     * carry-over 围栏 / 工具回灌），使 {@code CheckpointDeltaFactory} 的 list-index seq 与接收侧
     * {@code upToTurn ≤ history 长度} 同口径、跨请求前缀稳定；围栏块是外部/摘要数据，
     * ⛔ 绝不能被逐字抽取器当成用户事实写入状态。
     */
    private @Nullable CheckpointEmission emitCheckpoint(
            @NonNull Context context,
            @NonNull RunState state,
            @NonNull AgentChatRequest effective) {
        // ① 未装配端口 / 非装配态：零封包（关闭态逐位等价的关键防线）。
        if (checkpointEmitter == null || state.lastAssembly == null
                || !state.lastAssembly.assembled()) {
            return null;
        }
        try {
            // ② D17 唯一裁决：与 M8-a 接收侧 applyContextState 用同一端口 / 同一 Input 口径。
            String policyVersion = policyVersions.resolve(new ContextPolicyVersionPort.Input(
                    context.tenantId(), state.agentId, state.agentVersion,
                    state.settings.modelId(), state.settings.maxTokens()));
            if (policyVersion == null || policyVersion.isBlank()) {
                return null;
            }
            // ③ 纯对话历史：只含 history turn + 本轮输入，排除所有围栏/工具回灌消息。
            List<ModelMessage> dialogue = new ArrayList<>(effective.history().size() + 1);
            for (AgentChatRequest.Turn turn : effective.history()) {
                dialogue.add("assistant".equals(turn.role())
                        ? ModelMessage.assistant(turn.content())
                        : ModelMessage.user(turn.content()));
            }
            dialogue.add(ModelMessage.user(effective.message()));

            Emitted emitted = checkpointEmitter.emit(
                    new ContextCheckpointEmitter.Input(
                            context.tenantId(), "chat:" + state.runId,
                            state.settings.modelId(), dialogue, effective.history().size(),
                            policyVersion,
                            state.carryOver == null ? null : state.carryOver.stateJson(),
                            state.lastAssembly.tier(), state.lastAssembly.fallback()));
            if (emitted == null) {
                return null;
            }
            // ④ version 与 checkpoint digest 同源：唯一标识这份状态，前端据此把 state 与 done 绑定。
            return new CheckpointEmission(emitted, emitted.digest());
        } catch (RuntimeException e) {
            // 可选增强失败静默降级；⛔ 不打印任何 state 正文 / 密钥，只记异常类型供排障。
            log.debug("L2 checkpoint 发射跳过（可选增强 fail-soft）: domain={}, kind={}",
                    context.domain(), e.getClass().getSimpleName());
            return null;
        }
    }

    /** 轮末 checkpoint 发射结果：中性载荷 + 与 Done 绑定的版本串。 */
    private record CheckpointEmission(
            @NonNull Emitted emitted,
            @NonNull String version) {
    }

    /** 为缺失 callId 的模型调用补稳定标识，保证 SSE 事件与回灌消息引用同一 id。 */
    private static @NonNull Map<String, ModelResponse.ToolCallRequest> withStableCallIds(
            @NonNull Map<String, ModelResponse.ToolCallRequest> raw) {
        Map<String, ModelResponse.ToolCallRequest> normalized = new LinkedHashMap<>();
        int index = 0;
        for (ModelResponse.ToolCallRequest call : raw.values()) {
            String callId = call.callId() == null || call.callId().isBlank()
                    ? "call-" + index : call.callId();
            normalized.put(callId, new ModelResponse.ToolCallRequest(
                    call.toolName(), call.arguments(), callId));
            index++;
        }
        return normalized;
    }

    private static void emitToolResults(@NonNull Sink sink, @NonNull RunState state,
                                        @NonNull Map<String, ModelResponse.ToolCallRequest> calls,
                                        @NonNull ToolInvocationResult result) {
        for (Map.Entry<String, ModelResponse.ToolCallRequest> entry : calls.entrySet()) {
            // ⚠️ id 取 Map 的 key（== withStableCallIds 归一后的 callId，恒非 null），
            //   不取 call.callId()（其契约为 @Nullable，模型可能不返回 id）。
            //   否则 null 会灌进 AgentChatEvent.Tool 的 @NonNull toolCallId（SpotBugs NP）。
            String id = entry.getKey();
            ModelResponse.ToolCallRequest call = entry.getValue();
            String tool = call.toolName();
            if (result.succeededTools().contains(tool)) {
                String output = result.outputOf(tool).orElse("");
                sink.accept(new AgentChatEvent.Tool(id, tool, "result",
                        AgentChatEvent.StepStatus.SUCCESS, null,
                        truncate(output, MAX_TOOL_RESULT_PREVIEW), null));
            } else if (result.rejectedTools().contains(tool)) {
                String reason = result.rejectionReasonOf(tool).orElse("工具被拒绝（未执行）");
                sink.accept(new AgentChatEvent.Tool(id, tool, "error",
                        AgentChatEvent.StepStatus.ERROR, null, null, normalize(reason)));
            } else {
                sink.accept(new AgentChatEvent.Tool(id, tool, "error",
                        AgentChatEvent.StepStatus.ERROR, null, null,
                        "工具执行失败（结果确定失败，未取得可用输出）"));
            }
            state.events().add(event("TOOL_RESULT", Map.of(
                    "toolCallId", id, "toolName", tool,
                    "operationId", result.operationIdOf(tool).orElse(""))));
        }
    }

    // ── 失败收口 ──────────────────────────────────────────────────────

    private void handleFailure(@NonNull Context context,
                               @NonNull RuntimeException failure,
                               @NonNull Sink sink,
                               @NonNull RunState state) {
        boolean clientGone = hasCause(failure, SinkClosedException.class);
        String kind = classify(failure);
        String message = clientGone ? "客户端已断开" : publicMessage(failure, kind);

        if (!clientGone) {
            log.warn("Agent 编排失败: kind={}, tenant={}, run={}, cause={}",
                    kind, context.tenantId(), state.runId, failure.toString());
            state.events().add(event("ERROR", Map.of("kind", kind)));
            try {
                sink.accept(new AgentChatEvent.Error(kind, message));
            } catch (RuntimeException ignored) {
                // 推错误帧本身失败 = 客户端已走，交由下面的终态收敛处理。
            }
        }

        if (state.handle != null && !state.finished) {
            try {
                state.events().add(event("DONE", Map.of("truncated", !clientGone)));
                audit.finish(state.handle, new AgentChatAuditPort.FinishRecord(
                        "FAILED", kind,
                        resultPayload(state, "FAILED", kind),
                        state.events(), state.steps(), Instant.now()));
                state.finished = true;
            } catch (RuntimeException auditFailure) {
                // ⚠️ 审计终态丢失是重大事故信号：绝不吞成 DEBUG，必须 ERROR 可告警。
                log.error("⚠️ Agent 对话审计终态收敛失败: run={}, tenant={}, cause={}",
                        state.runId, context.tenantId(), auditFailure.toString());
            }
        }

        if (!clientGone) {
            try {
                sink.accept(new AgentChatEvent.Done(false));
            } catch (RuntimeException ignored) {
                // 客户端已断开；终态已尽力落库。
            }
        }
    }

    private static @NonNull String classify(@NonNull RuntimeException e) {
        if (hasCause(e, SinkClosedException.class)) {
            return "CLIENT_DISCONNECTED";
        }
        if (hasCause(e, AgentRouter.RoutingException.class)) {
            return "ROUTE_FAILED";
        }
        if (hasCause(e, ConfigUnavailableException.class)) {
            return "CONFIG_UNAVAILABLE";
        }
        if (hasCause(e, ContentBlockedException.class)) {
            return "CONTENT_BLOCKED";
        }
        if (hasCause(e, ToolGateException.class)) {
            return "TOOL_EXECUTION_FAILED";
        }
        // ⚠️ S3-M8-b：装配拒因是端口层稳定码（只追加不改名），如实透出给 error 帧与审计，
        //    绝不把「配错策略」伪装成 SERVER_ERROR 后静默回退 legacy 继续发模型请求。
        RequestAssemblyException assembly = findCause(e, RequestAssemblyException.class);
        if (assembly != null) {
            return assembly.reason().name();
        }
        if (hasCause(e, RagRetrievalPort.RagUnavailableException.class)) {
            return "RAG_UNAVAILABLE";
        }
        ModelGatewayException model = findCause(e, ModelGatewayException.class);
        if (model != null) {
            return switch (model.kind()) {
                case TIMEOUT -> "TIMEOUT";
                case UNAUTHORIZED -> "UNAUTHORIZED";
                case CONTENT_BLOCKED -> "CONTENT_BLOCKED";
                default -> "PROVIDER_ERROR";
            };
        }
        if (hasCause(e, IllegalArgumentException.class)) {
            return "INVALID_REQUEST";
        }
        return "SERVER_ERROR";
    }

    /** 只对已归一化的异常透出原文；未知异常给固定文案，避免泄漏供应商报文 / 配置。 */
    private static @NonNull String publicMessage(@NonNull RuntimeException e,
                                                 @NonNull String kind) {
        if (findCause(e, RequestAssemblyException.class) != null) {
            // 只透出稳定原因码，不泄漏内部策略 / 预算细节（归一化文案）。
            return "上下文统一装配被拒（" + kind + "）";
        }
        if (findCause(e, AgentRouter.RoutingException.class) != null
                || findCause(e, ConfigUnavailableException.class) != null
                || findCause(e, ContentBlockedException.class) != null
                || findCause(e, ToolGateException.class) != null
                || findCause(e, IllegalArgumentException.class) != null) {
            return normalize(e.getMessage());
        }
        ModelGatewayException model = findCause(e, ModelGatewayException.class);
        if (model != null) {
            return "模型调用失败（" + kind + "）";
        }
        return "编排服务暂时不可用（" + kind + "）";
    }

    private static boolean hasCause(@NonNull Throwable e, @NonNull Class<? extends Throwable> type) {
        return findCause(e, type) != null;
    }

    private static @Nullable <T extends Throwable> T findCause(
            @NonNull Throwable e, @NonNull Class<T> type) {
        Throwable current = e;
        int depth = 0;
        while (current != null && depth++ < 8) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return null;
    }

    // ── 工具方法 ──────────────────────────────────────────────────────

    private static int normalizeTopK(@Nullable Integer raw) {
        if (raw == null) {
            return DEFAULT_TOP_K;
        }
        return Math.max(1, Math.min(raw, MAX_TOP_K));
    }

    private static @NonNull String nameOf(@NonNull List<AgentQueryPort.AgentItem> candidates,
                                          @NonNull String agentId) {
        return candidates.stream()
                .filter(c -> c.agentId().equals(agentId))
                .map(AgentQueryPort.AgentItem::name)
                .filter(n -> n != null && !n.isBlank())
                .findFirst()
                .orElse(agentId);
    }

    private static @NonNull String digest(@NonNull AgentChatRequest request) {
        StringBuilder sb = new StringBuilder(request.message());
        for (AgentChatRequest.Turn turn : request.history()) {
            sb.append('\n').append(turn.role()).append(':').append(turn.content());
        }
        sb.append('|').append(request.agentId()).append('|').append(request.topK());
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 结果摘要：只含标量事实，绝不把回答正文 / 问题原文写进审计。 */
    private static @NonNull String resultPayload(@NonNull RunState state,
                                                 @NonNull String finishReason,
                                                 @Nullable String errorCode) {
        ObjectNode root = JSON.createObjectNode();
        root.put("channel", "agent-chat");
        root.put("agentId", state.agentId == null ? "" : state.agentId);
        root.put("agentVersion", state.agentVersion);
        root.put("inputTokens", state.inputTokens);
        root.put("outputTokens", state.outputTokens);
        // ⚠️ P1-4：审计里也记下「是否估算」—— 否则事后无法解释用量数字的来源。
        root.put("usageEstimated", state.estimated);
        root.put("modelRounds", state.modelRounds);
        root.put("toolRounds", state.toolRounds);
        root.put("ragHits", state.ragHits);
        root.put("ragInjected", state.ragInjected);
        root.put("answerChars", state.answer().length());
        root.put("finishReason", finishReason);
        root.put("errorCode", errorCode);
        return JSON.writeValueAsString(root);
    }

    private static @NonNull AuditEvent event(
            @NonNull String type, @NonNull Map<String, Object> payload) {
        // 序号交给仓储统一分配（0 = 自动），避免跨两段事务读 MAX+1 的竞态。
        return new AuditEvent(0, type, payload);
    }

    private static @NonNull String argumentsJson(@NonNull Map<String, Object> arguments) {
        try {
            return JSON.writeValueAsString(arguments);
        } catch (RuntimeException e) {
            return "{}";
        }
    }

    private static @NonNull String truncate(@Nullable String raw, int max) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String flat = raw.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ');
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }

    /** 错误文案统一去控制字符 + 限长，避免把供应商报文 / 换行帧注入 SSE。 */
    private static @NonNull String normalize(@Nullable String raw) {
        return truncate(raw == null ? "未知错误" : raw, MAX_ERROR_MESSAGE);
    }

    // ── 内部状态 ──────────────────────────────────────────────────────

    private static final class RunState {
        final String runId;
        final StringBuilder answer = new StringBuilder();
        final List<AgentChatAuditPort.AuditEvent> events = new ArrayList<>();
        final List<AgentChatAuditPort.AuditStep> steps = new ArrayList<>();
        AgentChatAuditPort.AuditHandle handle;
        EffectiveClosure closure;
        AgentModelSettings settings;
        String snapshotId;
        String agentId;
        String agentName;
        int agentVersion;
        long inputTokens;
        long outputTokens;
        /** ⚠️ P1-4：token 数是否含本地估算值。 */
        boolean estimated;
        int modelRounds;
        int toolRounds;
        int ragHits;
        boolean ragInjected;
        /** S3-M8-a 净化结果；M8-b 从此处读取已验载荷，⛔ 不重新解析原始请求串。 */
        ContextStateResolution carryOver;
        /** S3-M8-b 最后一轮装配结果；#73 用它发射 checkpoint，#72 只记标量审计事实。 */
        @Nullable RequestAssemblyResult lastAssembly;
        int stepNo;
        boolean finished;

        RunState(@NonNull String runId) {
            this.runId = runId;
        }

        List<AgentChatAuditPort.AuditEvent> events() {
            return events;
        }

        List<AgentChatAuditPort.AuditStep> steps() {
            return steps;
        }

        StringBuilder answer() {
            return answer;
        }

        long inputTokens() {
            return inputTokens;
        }

        long outputTokens() {
            return outputTokens;
        }

        void step(@NonNull String type, @NonNull Instant started, @NonNull Instant completed) {
            steps.add(new AgentChatAuditPort.AuditStep(++stepNo, type, started, completed));
        }
    }

    /** Agent 已发布但结构化配置不可用（modelRef / instructions 缺失）。 */
    public static final class ConfigUnavailableException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ConfigUnavailableException(@NonNull String message) {
            super(message);
        }
    }

    /** 工具闸门整体不可用（区别于单工具失败，后者是组件级）。 */
    public static final class ToolGateException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ToolGateException(@NonNull String message, @NonNull Throwable cause) {
            super(message, cause);
        }

        ToolGateException(@NonNull String message) {
            super(message);
        }
    }

    /** 内容安全拦截。 */
    public static final class ContentBlockedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ContentBlockedException(@NonNull String message) {
            super(message);
        }
    }
}
