package cn.studykid.multiagent.ports.context;

import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.ports.model.ModelMessage;

/**
 * W3 增量结构化摘要生成端口（《方案》§5.3 / §6.2-C，M6 端口契约）。
 *
 * <p>⚠️ <b>为什么签名是 JSON 字符串而不是 {@code StructuredState}</b>：
 * {@code StructuredState} / {@code ContextPolicy} 属于 {@code agent-context} 算法层；
 * 按 ADR-011 / C1，{@code agent-ports} <b>不得反向依赖 agent-context</b>。
 * 因此这里用「确定性结构化 JSON 字符串 + 中性策略投影」作为端口边界，
 * 由 M7 适配层在 {@code StructuredStateCodec} 与本端口之间做严格编解码。
 * ⛔ 端口层不得重新定义 12 字段结构化状态，否则会形成第二套 schema。
 *
 * <p>⚠️ <b>增量纪律</b>：{@code delta} 必须已经过滤到 {@code seq > oldState.upToSeq}，
 * 只含新消息。实现不得回看全量历史，也不得递归重摘要旧状态。
 * {@code oldStateJson} 为空串表示「尚无 checkpoint」（调用方传
 * {@link Checkpoint#empty()} 的 {@code stateJson()}）。
 *
 * <p>⚠️ <b>fail-closed</b>：模型不可用 / 超时 / 解析失败 / 置信度低 / 关键 span
 * 未逐字保留 / 超预算时一律返回 {@link Optional#empty()}，由装配器回落 W2；
 * ⛔ 绝不伪造摘要、绝不把模型自由文本直接塞回上下文。
 * 返回的 JSON 只含结构化字段，原始正文只能通过 {@code refId} 回取。
 */
public interface SummaryGeneratorPort {

    /**
     * 把增量消息合并进旧结构化状态，生成新的确定性状态 JSON。
     *
     * @param oldStateJson 旧状态的确定性 JSON；空串 = 无旧状态
     * @param delta        只含 {@code seq > upToSeq} 的新消息（不可信输入）
     * @param policy       已裁决策略的中性投影
     * @return 新状态 JSON；任何失败/不满足逐字校验一律 {@link Optional#empty()}
     */
    @NonNull Optional<String> merge(@NonNull String oldStateJson,
                                    @NonNull List<ModelMessage> delta,
                                    @NonNull EffectiveContextPolicy policy);
}
