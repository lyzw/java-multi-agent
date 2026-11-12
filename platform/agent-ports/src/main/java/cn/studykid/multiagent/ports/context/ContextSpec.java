package cn.studykid.multiagent.ports.context;

import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.ports.model.ModelMessage;

/**
 * 统一装配入口的输入（《方案》§6.2-B {@code ContextSpec} / M6 端口契约）。
 *
 * <p>⚠️ <b>纯数据载体</b>：本 record 只做「可空文本块 → 空串」归一化与身份字段
 * fail-closed，⛔ 不提供任何「拼 prompt / 投影成 ContextBlocks / 造 toolSchema」的便捷方法——那是装配器（M7）的职责。
 * 端口层若自带一份渲染规则，就会与 agent-context 的 {@code ContextLayout}
 * 形成两套口径，出现「计量算 A、实际发 B」的静默失真。
 *
 * <p>⚠️ <b>不可信块与主流程分离</b>：{@code memoryBlock} / {@code ragBlocks} /
 * {@code carryOverStateJson} 都是外部数据，装配器必须走带围栏 USER 块，
 * ⛔ 绝不拼进 {@code systemPrompt}（[D6] / MA-T14）。{@code carryOverStateJson}
 * 还须先过<b>六项</b>不可信校验（D49：HMAC / {@code exp} / {@code policyVersion} /
 * tenant digest / {@code max-state-chars} / {@code upToTurn ≤ history}，§4.4）。
 *
 * <p>⚠️ <b>零框架</b>：只用端口层已有的 {@link ModelMessage} / {@link ModelCapabilities}，
 * 不引入 agent-context 的 {@code ContextPolicy} / {@code StructuredState}（ADR-011 / C1）。
 * 全参构造唯一，不提供兼容构造器（铁律 #10）。
 *
 * @param tenantId              租户 id（UUID 字符串；日志/隔离用，不进请求正文）
 * @param scopeKey              checkpoint 作用域键（{@code run:<id>} / {@code chat:<digest>}）
 * @param modelId               目标模型 id
 * @param systemPrompt          稳定前缀（逐字保留，[D6]）
 * @param toolSchemas           工具 Schema 序列化串（必须计入预算）
 * @param firstUserTurn         首轮任务锚（pinned，逐字保留，独立块不入 messages）
 * @param memoryBlock           跨 Run 记忆（不可信，围栏 USER 块）
 * @param ragBlocks             RAG 命中块（不可信，围栏 USER 块）
 * @param messages              历史消息（含 tool_call/tool_result 配对）
 * @param currentInput          本轮用户输入（永不裁剪，任务锚）
 * @param policy                已裁决策略的中性投影
 * @param capabilities          模型上下文能力
 * @param upToSeq               已吸收到的 seq 游标（增量摘要只吃 {@code seq > upToSeq}）
 * @param carryOverStateJson    上一轮 carry-over 的不可信状态 JSON；null = 无
 * @param fencingToken          执行租约令牌（checkpoint CAS 用）
 */
public record ContextSpec(
        @NonNull String tenantId,
        @NonNull String scopeKey,
        @NonNull String modelId,
        @NonNull String systemPrompt,
        @NonNull String toolSchemas,
        @NonNull String firstUserTurn,
        @NonNull String memoryBlock,
        @NonNull List<String> ragBlocks,
        @NonNull List<ModelMessage> messages,
        @NonNull String currentInput,
        @NonNull EffectiveContextPolicy policy,
        @NonNull ModelCapabilities capabilities,
        long upToSeq,
        @Nullable String carryOverStateJson,
        long fencingToken) {

    public ContextSpec {
        // 身份字段必须存在：空 tenantId 会让 FORCE RLS 静默 0 行 / 空 modelId 会让
        // 能力探测退化成 unknown，都是「不报错但行为悄悄错」。这里直接 fail-closed。
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("ContextSpec.tenantId 不得为空（fail-closed）");
        }
        if (scopeKey == null || scopeKey.isBlank()) {
            throw new IllegalArgumentException("ContextSpec.scopeKey 不得为空（fail-closed）");
        }
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("ContextSpec.modelId 不得为空（fail-closed）");
        }
        if (upToSeq < -1) {
            throw new IllegalArgumentException("ContextSpec.upToSeq 不得小于 -1");
        }
        if (fencingToken < 0) {
            throw new IllegalArgumentException("ContextSpec.fencingToken 不得为负");
        }
        if (policy == null) {
            throw new IllegalArgumentException("ContextSpec.policy 不得为 null（fail-closed）");
        }
        systemPrompt = systemPrompt == null ? "" : systemPrompt;
        toolSchemas = toolSchemas == null ? "" : toolSchemas;
        firstUserTurn = firstUserTurn == null ? "" : firstUserTurn;
        memoryBlock = memoryBlock == null ? "" : memoryBlock;
        ragBlocks = ragBlocks == null ? List.of() : List.copyOf(ragBlocks);
        messages = messages == null ? List.of() : List.copyOf(messages);
        currentInput = currentInput == null ? "" : currentInput;
        if (capabilities == null) {
            capabilities = ModelCapabilities.unknown(modelId);
        }
    }
}
