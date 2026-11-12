package cn.studykid.multiagent.context;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.context.RollingStateMerger.DeltaExtractor;
import cn.studykid.multiagent.context.RollingStateMerger.MergeResult;
import cn.studykid.multiagent.context.RollingStateMerger.SeqMessage;
import cn.studykid.multiagent.ports.context.CheckpointDelta;
import cn.studykid.multiagent.ports.model.ModelMessage;

/**
 * 把「上一轮 carry-over + 本轮历史」增量合并成可发射的 {@link CheckpointDelta}
 * （S3-M8-b / ADR-013 D49）。
 *
 * <p>⚠️ <b>为什么单独成类</b>：装配器（{@link ContextAssembler}）只负责「本轮发什么请求」，
 * 不持有 checkpoint 生命周期；把 delta 生产放在这里，可被三条链路（L2 SSE / L4 / S4）
 * 共用同一实现，避免各写一套游标与摘要口径。
 *
 * <p>⚠️ <b>无新增即不产 delta</b>：{@link RollingStateMerger} 判定没有可吸收轮次
 * （全部 {@code seq <= oldState.upToSeq()}）或抽取回落时，本类返回 {@code null}——
 * 上层据此**不发射第 14 事件**。绝不发「空状态 + 推进游标」的假 checkpoint。
 *
 * <p>⚠️ <b>超长不发射</b>：{@code stateJson} 超过 {@code max-state-chars} 时返回 {@code null}，
 * 因为这种状态发出去也会被服务端自己的六项校验丢弃（D49），发它只是浪费请求字节。
 *
 * <p>纯算法：零 Spring / 零 JDBC / 零 IO / 零时钟。
 */
public final class CheckpointDeltaFactory {

    private CheckpointDeltaFactory() {
    }

    /**
     * 输入事实。
     *
     * @param scopeKey           作用域键（SSE 用 {@code chat:<runId>}）
     * @param modelId            目标模型（加载时须一致）
     * @param historyMessages    本轮可见历史（顺序即对话顺序；索引 + 1 作为 seq 坐标）
     * @param historyTurns       本轮历史轮次（写入 {@code upToTurn}，接收侧校验其 ≤ 当前历史）
     * @param policyVersion      已裁决策略指纹（D17，唯一来源）
     * @param carryOverStateJson 上一轮已验状态 JSON；null = 无
     * @param tier               触发档位 1/2/3
     * @param fallback           是否 W3 回落 W2
     * @param checkpointTtlSeconds TTL
     * @param maxStateChars      状态长度硬顶（与 {@code agent.context.max-state-chars} 同源）
     */
    public record Input(
            @NonNull String scopeKey,
            @NonNull String modelId,
            @NonNull List<ModelMessage> historyMessages,
            int historyTurns,
            @NonNull String policyVersion,
            @Nullable String carryOverStateJson,
            int tier,
            boolean fallback,
            int checkpointTtlSeconds,
            int maxStateChars) {

        public Input {
            require(scopeKey, "scopeKey");
            require(modelId, "modelId");
            require(policyVersion, "policyVersion");
            if (historyTurns < 0) {
                throw new IllegalArgumentException("historyTurns 不得为负");
            }
            if (tier < 1 || tier > 3) {
                throw new IllegalArgumentException("tier 必须是 1/2/3（W1/W2/W3）");
            }
            if (checkpointTtlSeconds <= 0) {
                throw new IllegalArgumentException("checkpointTtlSeconds 必须为正");
            }
            if (maxStateChars <= 0) {
                throw new IllegalArgumentException("maxStateChars 必须为正");
            }
            historyMessages = historyMessages == null ? List.of() : List.copyOf(historyMessages);
        }
    }

    /**
     * 增量合并；无新增 / 抽取回落 / 状态超长一律返回 {@code null}（不发射）。
     */
    public static @Nullable CheckpointDelta build(@NonNull Input input,
                                                  @NonNull DeltaExtractor extractor) {
        Objects.requireNonNull(input, "CheckpointDeltaFactory.Input 不得为空");
        Objects.requireNonNull(extractor, "DeltaExtractor 不得为空");

        StructuredState oldState = parseCarryOver(input.carryOverStateJson());
        List<SeqMessage> delta = new ArrayList<>(input.historyMessages().size());
        for (int i = 0; i < input.historyMessages().size(); i++) {
            delta.add(new SeqMessage(i + 1L, input.historyMessages().get(i)));
        }

        MergeResult merged = RollingStateMerger.merge(oldState, delta,
                input.policyVersion(), extractor);
        if (!merged.advanced()) {
            return null;
        }

        String stateJson = StructuredStateCodec.toJson(merged.state());
        if (stateJson.length() > input.maxStateChars()) {
            return null;
        }
        return new CheckpointDelta(
                input.scopeKey(),
                merged.state().fromSeq(),
                merged.state().upToSeq(),
                input.historyTurns(),
                input.modelId(),
                stateJson,
                StructuredStateCodec.digest(merged.state()),
                List.of(),
                input.tier(),
                input.fallback(),
                input.checkpointTtlSeconds(),
                input.policyVersion());
    }

    /**
     * 解析上一轮状态。⚠️ 到这里的状态**已经过六项不可信校验**；
     * 仍做防御性兜底：解析失败按「无状态」重建，绝不让脏状态阻断本轮对话，
     * 也绝不把解析失败的原文当状态续写。
     */
    private static @NonNull StructuredState parseCarryOver(@Nullable String carryOverStateJson) {
        if (carryOverStateJson == null || carryOverStateJson.isBlank()) {
            return StructuredState.empty();
        }
        try {
            return StructuredStateCodec.fromJson(carryOverStateJson);
        } catch (RuntimeException e) {
            return StructuredState.empty();
        }
    }

    private static void require(@Nullable String value, @NonNull String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "CheckpointDeltaFactory." + field + " 不得为空（fail-closed）");
        }
    }
}
