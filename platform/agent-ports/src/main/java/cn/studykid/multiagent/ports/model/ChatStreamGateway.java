package cn.studykid.multiagent.ports.model;

import org.jspecify.annotations.NonNull;

/**
 * 流式对话网关端口（控制台「直接对话」能力 · 真·LLM 多轮流式）。
 *
 * <p><b>为何独立于 {@link ModelGateway}</b>：{@code ModelGateway.complete()} 是
 * 领域层（Worker 执行 Agent Run）使用的一次性归一化调用，按 ADR-002 不暴露
 * 任何流式/响应式类型。控制台对话是<b>平台工具</b>而非租户 Run，
 * 其流式需求单独成端口，避免污染领域契约与 {@code ModelGatewayContractTest}。
 *
 * <p><b>领域中立</b>：入参/出参沿用 {@link ModelRequest}/{@link ModelResponse}，
 * 不引入供应商或 Spring AI 专属类型。
 *
 * <p>⚠️ <b>实现方契约（与 {@code ModelGateway} 同构的归一化纪律）</b>：
 * <ul>
 *   <li>超时、限流、鉴权、内容拦截须归一为 {@link ModelGateway.ModelErrorKind}；</li>
 *   <li>秘密（API Key）不得出现在日志或异常消息；</li>
 *   <li>每个内容增量通过 {@link StreamDeltaConsumer#onDelta} 实时回吐，
 *       不得缓冲到结束才一次性抛出；</li>
 *   <li>{@code onDelta} 回调内的异常由调用方（控制器）捕获并终止流，
 *       实现方不得吞掉或重新归一化已抛出的 {@link ModelGateway.ModelGatewayException}。</li>
 * </ul>
 */
public interface ChatStreamGateway {

    /**
     * 流式生成。
     *
     * <p>逐块把内容增量交给 {@code consumer.onDelta}，返回整合后的终态响应
     * （含完整正文与用量，供用量结算/前端兜底展示）。
     *
     * @param request  模型请求（多轮以 {@code messages} 为准）
     * @param consumer 增量消费者（实时回吐 token）
     * @return 整合后的终态响应
     * @throws ModelGateway.ModelGatewayException 归一化后的失败（分类须正确）
     */
    @NonNull ModelResponse streamChat(@NonNull ModelRequest request,
                                      @NonNull StreamDeltaConsumer consumer);

    /**
     * 内容增量消费者。
     *
     * <p>⚠️ 实现方在读取到每个内容分片时调用，<b>不保证</b>分片按词/句边界切分；
     * 消费者须能处理任意切分。
     *
     * <p>两个方法分工：<b>{@link #onDelta}</b> 是最终呈现给用户的答案增量；
     * <b>{@link #onThinking}</b> 是模型推理草稿（思考过程），默认空实现——
     * 仅当平台工具需要把思考过程展示给管理员时才覆盖它，普通对话只消费
     * {@code onDelta}（保持 lambda 写法可用）。
     */
    interface StreamDeltaConsumer {
        void onDelta(@NonNull String token);

        /**
         * 推理草稿（思考过程）增量。默认空实现：不强制所有消费方都展示思考。
         *
         * <p>⚠️ 思考内容<b>未经答案级安全审核</b>，仅展示给<b>平台管理员</b>（控制台工具），
         * 不可回落到面向学生的通道（红线 R6 相关）。
         */
        default void onThinking(@NonNull String token) {
            // 默认丢弃：仅直接对话控制台会覆盖此方法以展示思考过程。
        }
    }
}
