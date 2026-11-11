package cn.studykid.multiagent.ports.context;

import org.jspecify.annotations.NonNull;

/**
 * 三链路统一的上下文装配入口（《方案》§4.1 / §6.2-B / §6.4-②，M6 端口契约）。
 *
 * <p>⚠️ <b>唯一送模型请求</b>：Worker / SSE / Console 都只消费
 * {@link AssembledContext#request()}；⛔ 调用方不得再自行拼 prompt、
 * 追加记忆块或裁剪说明，否则 {@code systemPrompt} / {@code toolSchemas}
 * 的逐字稳定前缀会被动态改写（[D6] 失效，且影子期请求字节不再等价）。
 *
 * <p>⚠️ <b>关闭态逐位等价</b>：当 {@link EffectiveContextPolicy#enabled()} 为 false，
 * 实现必须走 legacy trim 路径，产出与「没有压缩功能」逐位一致的
 * {@code ModelRequest}；所有新指标不注册/不递增（D25）。
 *
 * <p>⚠️ <b>纯装配</b>：本端口不写 outbox、不发 MQ、不提交事务；
 * checkpoint 的持久化通过 {@link ContextCheckpointPort} 另行完成。
 * 端口层不依赖 {@code agent-context}，复用已存在的 {@link TokenCounter} 作为第四端口。
 */
public interface ContextAssemblerPort {

    /**
     * 按已裁决策略装配一次模型请求。
     *
     * @param spec 已收集齐六块上下文、策略与租约的输入
     * @return 唯一送模型的请求 + 可选 checkpoint 写入载荷 + 水位/倒退证据
     */
    @NonNull AssembledContext assemble(@NonNull ContextSpec spec);
}
