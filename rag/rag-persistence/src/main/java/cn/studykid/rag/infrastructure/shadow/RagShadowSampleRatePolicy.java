package cn.studykid.rag.infrastructure.shadow;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 按固定采样率抽样（rate∈[0,1]）。rate≥1 视为全量；rate≤0 视为不采样。
 *
 * <p>采样键取 {@code resourceType:samplingKey} 的稳定哈希，保证同一窗口内
 * 同一查询要么全采、要么全不采（不撕裂一次查询的多行染色）。
 */
public final class RagShadowSampleRatePolicy implements RagShadowSamplingPolicy {

    private final double rate;

    public RagShadowSampleRatePolicy(double rate) {
        this.rate = Math.max(0.0, Math.min(1.0, rate));
    }

    @Override
    public boolean shouldSample(@NonNull String resourceType, @Nullable String samplingKey) {
        if (rate >= 1.0) {
            return true;
        }
        if (rate <= 0.0) {
            return false;
        }
        String key = resourceType + ":" + (samplingKey == null ? "" : samplingKey);
        int h = key.hashCode();
        return (Math.floorMod(h, 1000) / 1000.0) < rate;
    }
}
