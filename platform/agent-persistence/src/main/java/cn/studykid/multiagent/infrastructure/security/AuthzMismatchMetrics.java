package cn.studykid.multiagent.infrastructure.security;

import cn.studykid.multiagent.core.security.ScopeFilter;
import cn.studykid.multiagent.infrastructure.persistence.scope.RowAttribution;

import org.jspecify.annotations.NonNull;

/**
 * 权限影子/染色观测指标（12 文 §4.5 / D6）。
 *
 * <p>迁移期「双写取交集」阶段，应用层过滤与 RLS 同时生效；任何两者不一致都必须被量化，
 * 否则「退役后只剩应用层」的纵深下降无法被证明已收敛。本接口定义四项核心计数：
 * <ul>
 *   <li>{@code shadowSamples} —— 影子比对样本数（每个被比对的查询）；</li>
 *   <li>{@code unrestrictedUsages} —— {@code Unrestricted}（平台全域）使用次数；</li>
 *   <li>{@code mismatches} —— Gate 计算谓词与实际查询绑定不一致的计数；</li>
 *   <li>{@code taintViolations} —— 染色校验捕获的越权行计数。</li>
 * </ul>
 *
 * <p>生产出口门禁（13 文 §5.2）：≥2 周 {@code AUTHZ_MISMATCH = 0} 方可放行 P2。
 */
public interface AuthzMismatchMetrics {

    void recordShadowSample(@NonNull String resourceType);

    void recordUnrestricted(@NonNull String resourceType);

    void recordMismatch(@NonNull String resourceType, @NonNull String detail);

    void recordTaintViolation(@NonNull String resourceType,
                              @NonNull RowAttribution row,
                              @NonNull ScopeFilter expected);

    long shadowSamples();

    long unrestrictedUsages();

    long mismatches();

    long taintViolations();
}
