package cn.studykid.multiagent.ports.contextwiring;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.ports.model.ModelMessage;
import cn.studykid.multiagent.ports.model.ModelRequest;

/**
 * {@link RequestAssemblyPort} 的中立输入（ADR-013 D38 / 开发计划 §6.4-④，M7-b）。
 *
 * <p>⚠️ <b>携带两条并行事实，缺一不可</b>：
 * <ol>
 *   <li>{@link #legacyRequest()}：调用方按 legacy 路径<b>逐字构造</b>的完整请求。
 *       它是关闭态逐位等价的<b>基线</b>，也是开启态 6 个非上下文字段的<b>唯一真源</b>。
 *       实现必须在关闭态返回该对象的<b>同一引用</b>；</li>
 *   <li>六块上下文字段（{@link #stableSystemPrompt()} / {@link #firstUserTurn()} /
 *       {@link #memoryBlock()} / {@link #ragBlocks()} / {@link #historyMessages()} /
 *       {@link #currentInput()}）+ 冻结身份（{@link #agentId()} / {@link #agentVersion()}）：
 *       供组合根 adapter 解析冻结策略、算全窗口预算、跑 W1/W2/W3 管线与围栏布局。</li>
 * </ol>
 *
 * <p>⚠️ <b>首轮锚不得重复</b>：{@link #historyMessages()} 必须是<b>已剔除首轮锚</b>的历史序列。
 * {@code ContextLayout} 会把 {@link #firstUserTurn()} 渲染为独立 pinned USER 消息；
 * 若历史里再带一遍，模型会看到两次、预算也会双算（[D27]）。
 *
 * <p>⚠️ <b>{@link #stableSystemPrompt()} 逐字稳定</b>：不含 memory / trim notice / summary。
 * 这些不可信数据由 adapter 作为围栏 USER 块注入，绝不拼进 systemPrompt（[D6]）。
 *
 * <p>⚠️ <b>纯端口层值对象</b>：不出现 agent-context / Spring / JDBC 类型（ADR-011）；
 * 全参构造唯一，不提供兼容构造器（铁律 #10）。
 *
 * @param tenantId            租户 id（日志与 checkpoint 作用域用，不进请求正文）
 * @param scopeKey            checkpoint 作用域键（{@code run:<id>}）
 * @param agentId             Agent 标识（冻结策略的权威源 {@code cap_agent_def} 的查询键）
 * @param agentVersion        Agent <b>固定 release</b>（D4：绝不读「当前已发布版本」）
 * @param modelId             目标模型标识（能力与窗口的查询键）
 * @param stableSystemPrompt  稳定前缀（逐字保留，⛔ 不含 memory / notice）
 * @param toolSchemas         真正送模型的工具 Schema（预算计量用其确定性序列化）
 * @param firstUserTurn       首轮任务锚（独立 pinned 块，不入 historyMessages）
 * @param memoryBlock         跨 Run 记忆（不可信，围栏 USER 块；空串 = 无）
 * @param ragBlocks           RAG 命中块（不可信，围栏 USER 块）
 * @param historyMessages     首轮锚之后的历史消息（含 tool_call/tool_result 配对）
 * @param currentInput        本轮用户输入；空串 = 本轮无新增输入（L1/L4 首轮锚即输入）
 * @param maxOutputTokens     输出上限（参与总窗口溢出 fail-closed 判定）
 * @param fencingToken        执行租约令牌（checkpoint CAS 用）
 * @param upToSeq             增量游标（-1 = 无 checkpoint）
 * @param carryOverStateJson  上一轮 carry-over 状态（M8 才消费；M7 恒 null）
 * @param priorCompactions    本 Run 已发生的压缩次数（M7 恒 0）
 * @param legacyRequest       legacy 完整请求：关闭等价基线 + 非上下文字段真源
 */
public record RequestAssemblyInput(
        @NonNull String tenantId,
        @NonNull String scopeKey,
        @NonNull String agentId,
        int agentVersion,
        @NonNull String modelId,
        @NonNull String stableSystemPrompt,
        @NonNull Map<String, Object> toolSchemas,
        @NonNull String firstUserTurn,
        @NonNull String memoryBlock,
        @NonNull List<String> ragBlocks,
        @NonNull List<ModelMessage> historyMessages,
        @NonNull String currentInput,
        int maxOutputTokens,
        long fencingToken,
        long upToSeq,
        @Nullable String carryOverStateJson,
        int priorCompactions,
        @NonNull ModelRequest legacyRequest) {

    public RequestAssemblyInput {
        require(tenantId, "tenantId");
        require(scopeKey, "scopeKey");
        require(agentId, "agentId");
        require(modelId, "modelId");
        if (agentVersion <= 0) {
            throw new IllegalArgumentException(
                    "RequestAssemblyInput.agentVersion 必须为正（固定 release，fail-closed）");
        }
        if (maxOutputTokens <= 0) {
            throw new IllegalArgumentException(
                    "RequestAssemblyInput.maxOutputTokens 必须为正（总窗口判定缺输出上界会静默超窗）");
        }
        if (fencingToken < 0) {
            throw new IllegalArgumentException("RequestAssemblyInput.fencingToken 不得为负");
        }
        if (upToSeq < -1) {
            throw new IllegalArgumentException("RequestAssemblyInput.upToSeq 不得小于 -1");
        }
        if (priorCompactions < 0) {
            throw new IllegalArgumentException("RequestAssemblyInput.priorCompactions 不得为负");
        }
        Objects.requireNonNull(legacyRequest,
                "RequestAssemblyInput.legacyRequest 不得为 null（关闭等价桥基线）");
        stableSystemPrompt = stableSystemPrompt == null ? "" : stableSystemPrompt;
        toolSchemas = toolSchemas == null ? Map.of() : Map.copyOf(toolSchemas);
        firstUserTurn = firstUserTurn == null ? "" : firstUserTurn;
        memoryBlock = memoryBlock == null ? "" : memoryBlock;
        ragBlocks = ragBlocks == null ? List.of() : List.copyOf(ragBlocks);
        historyMessages = historyMessages == null
                ? List.of() : List.copyOf(historyMessages);
        currentInput = currentInput == null ? "" : currentInput;
    }

    private static void require(@Nullable String value, @NonNull String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "RequestAssemblyInput." + field + " 不得为空（fail-closed）");
        }
    }
}
