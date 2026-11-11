package cn.studykid.multiagent.ports.context;

import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 一次压缩产生的 checkpoint <b>写入载荷</b>（《方案》§6.2-B / M6 端口契约）。
 *
 * <p>⚠️ <b>不含原始对话正文</b>：{@code stateJson} 是 {@code StructuredState}
 * （12 字段结构化状态）的确定性 JSON，⛔ 绝不能塞回消息全文；
 * {@code sourceMessageIds} 只存可回取的引用，不存内容（PIPL 未成年人最小保留）。
 *
 * <p>⚠️ {@code modelId} / {@code checkpointTtlSeconds} 是 §6.2-B 最小字段集之外
 * 的补充：M11 的INSERT 需要 model_id 做加载过滤、需要 TTL 计算 expires_at；
 * 若漏带，Repository 只能从别处猜，形成静默漂移。
 *
 * <p>⚠️ 全参构造是唯一构造；不提供兼容构造器（铁律 #10）。纯值对象，零框架依赖。
 *
 * @param scopeKey             作用域键（{@code run:<id>} / {@code chat:<digest>}）
 * @param fromSeq              状态覆盖的最早 seq（含）
 * @param toSeq                本批吸收到的 seq（含），对应单调游标
 * @param upToTurn             本批吸收到的轮次；null = 未用轮次坐标
 * @param modelId              模型 id，加载时须与当前模型一致
 * @param stateJson            结构化状态的确定性 JSON（不含正文）
 * @param digest               状态摘要（{@code sha256:…}），缓存与幂等键
 * @param sourceMessageIds     来源消息 id（可回取原文）
 * @param tier                 触发水位档位 W1/W2/W3
 * @param fallback             是否回退（摘要失败 → W2）
 * @param checkpointTtlSeconds 存活秒数
 * @param policyVersion        策略指纹
 */
public record CheckpointDelta(
        @NonNull String scopeKey,
        long fromSeq,
        long toSeq,
        @Nullable Integer upToTurn,
        @NonNull String modelId,
        @NonNull String stateJson,
        @NonNull String digest,
        @NonNull List<String> sourceMessageIds,
        int tier,
        boolean fallback,
        int checkpointTtlSeconds,
        @NonNull String policyVersion) {

    public CheckpointDelta {
        if (scopeKey == null || scopeKey.isBlank()) {
            throw new IllegalArgumentException("CheckpointDelta.scopeKey 不得为空");
        }
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("CheckpointDelta.modelId 不得为空");
        }
        if (stateJson == null || stateJson.isBlank()) {
            throw new IllegalArgumentException("CheckpointDelta.stateJson 不得为空（不得写入空状态）");
        }
        if (digest == null || digest.isBlank()) {
            throw new IllegalArgumentException("CheckpointDelta.digest 不得为空（幂等键缺失）");
        }
        if (policyVersion == null || policyVersion.isBlank()) {
            throw new IllegalArgumentException("CheckpointDelta.policyVersion 不得为空");
        }
        if (fromSeq > toSeq) {
            throw new IllegalArgumentException("CheckpointDelta.fromSeq 不得大于 toSeq");
        }
        if (tier < 1 || tier > 3) {
            throw new IllegalArgumentException("CheckpointDelta.tier 必须是 1/2/3（W1/W2/W3）");
        }
        if (checkpointTtlSeconds <= 0) {
            throw new IllegalArgumentException("CheckpointDelta.checkpointTtlSeconds 必须为正");
        }
        sourceMessageIds = sourceMessageIds == null
                ? List.of() : List.copyOf(sourceMessageIds);
    }
}
