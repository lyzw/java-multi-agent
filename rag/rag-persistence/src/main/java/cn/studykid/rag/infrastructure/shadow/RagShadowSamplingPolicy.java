package cn.studykid.rag.infrastructure.shadow;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 影子采样策略（决定是否对该查询做比对/染色）。
 *
 * <p>⚠️ 采样只决定「这次查询要不要看」，不决定「看到了要不要报」：
 * 一旦进入被采样的查询，其中每一行的染色违例都会上报，绝不二次抽样。
 */
public interface RagShadowSamplingPolicy {

    boolean shouldSample(@NonNull String resourceType, @Nullable String samplingKey);
}
