package cn.studykid.multiagent.ports.context;

import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 加载出的 checkpoint（{@link ContextCheckpointPort#load} 的返回值，M6 端口契约）。
 *
 * <p>⚠️ <b>不可信数据</b>：本对象可能来自客户端 carry-over（base64 状态）或 DB，
 * 在进入压缩链前必须做 HMAC / TTL / policyVersion / tenant digest / 大小五项校验
 * （《方案》§4.4）。{@code stateJson} 只能作为带围栏 USER 块注入，⛔ 绝不进 systemPrompt。
 *
 * <p>⚠️ {@link #expired(long)} 采用<b>显式时钟入参</b>（而非无参读系统时钟）：
 * 端口层保持确定性/可测性，调用方（M7/M11）负责传 {@code System.currentTimeMillis()}。
 * <b>空 checkpoint 一律判为已过期</b>（fail-closed），绝不把空状态当有效缓存续写。
 *
 * <p>⚠️ 全参构造是唯一构造；不提供兼容构造器（铁律 #10）。纯值对象，零框架依赖。
 */
public record Checkpoint(
        @NonNull String scopeKey,
        long fromSeq,
        long upToSeq,
        @Nullable Integer upToTurn,
        @NonNull String modelId,
        @NonNull String policyVersion,
        @NonNull String stateJson,
        @NonNull String digest,
        @NonNull List<String> sourceMessageIds,
        int tier,
        boolean fallback,
        long expiresAtEpochMs) {

    public Checkpoint {
        scopeKey = scopeKey == null ? "" : scopeKey;
        modelId = modelId == null ? "" : modelId;
        policyVersion = policyVersion == null ? "" : policyVersion;
        stateJson = stateJson == null ? "" : stateJson;
        digest = digest == null ? "" : digest;
        sourceMessageIds = sourceMessageIds == null
                ? List.of() : List.copyOf(sourceMessageIds);
        // 空 checkpoint 的 fromSeq=0/upToSeq=-1 是合法哨兵；非空状态才校验游标自洽。
        boolean empty = stateJson.isEmpty() && digest.isEmpty() && upToSeq < 0;
        if (!empty && fromSeq > upToSeq) {
            throw new IllegalArgumentException("Checkpoint.fromSeq 不得大于 upToSeq");
        }
    }

    /** 未命中：空作用域 + 游标 -1（尚未吸收任何 seq）+ 无过期时间。 */
    public static @NonNull Checkpoint empty() {
        return new Checkpoint("", 0, -1, null, "", "", "", "", List.of(),
                0, false, 0L);
    }

    /** 是否尚未吸收任何状态（{@link #empty()} 的语义判定）。 */
    public boolean isEmpty() {
        return stateJson.isEmpty() && digest.isEmpty() && upToSeq < 0;
    }

    /**
     * 是否已过期 / 不可用。<b>空对象、无过期时间、到期时刻一律判过期</b>（fail-closed），
     * 避免「缓存里躺着一个永不失效的空状态」把每轮压缩都静默降级成不压缩。
     */
    public boolean expired(long nowEpochMs) {
        return isEmpty() || expiresAtEpochMs <= 0 || nowEpochMs >= expiresAtEpochMs;
    }

    /** 设计伪代码中 {@code cp.toSeq()} 的别名；seq 坐标统一以 {@link #upToSeq} 为准。 */
    public long toSeq() {
        return upToSeq;
    }
}
