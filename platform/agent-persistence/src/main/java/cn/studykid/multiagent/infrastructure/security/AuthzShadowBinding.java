package cn.studykid.multiagent.infrastructure.security;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 影子态接线的<b>装配载体</b>（12 文 §4.5 / 13 文 §5.2 P1）。
 *
 * <p>⚠️ <b>为什么要有这个载体，而不是让每个仓库各注入三个依赖</b>：
 * <ol>
 *   <li><b>开关只有一个</b>：容器里<b>存在</b>本 Bean 就等于「影子态开启」，
 *       不存在就等于「完全关闭」。若改成「Bean 存在 + 一个 {@code enabled} 布尔」，
 *       就多出「Bean 在、开关关」的半开状态 —— 那是「配了却什么都不做」的经典静默失效
 *       （本项目已在 {@code platform-ops} / {@code alerts} 两个配置块上踩过同类事故）。</li>
 *   <li><b>仓库不必知道采样/上报的存在</b>：仓库只需把 {@link #scoped()}、
 *       {@link #reporter()}、{@link #sentinelTenantId()} 取出来交给
 *       {@link AuthzShadowHooks}，三个依赖的构造与生命周期全部由装配层负责。</li>
 * </ol>
 *
 * <p><b>哨兵租户</b>为 {@code TenantWithShared} 表（RAG 语料的共享行）判定共享行用；
 * 平台域无哨兵行，取空串即可 —— <b>空串 ≠ 任意租户</b>，{@code TaintVerifier}
 * 对空哨兵一律按「非共享」处理。
 *
 * @param scoped           仓储取 {@code ScopeFilter} 的统一接缝
 * @param reporter         影子比对 / 染色上报桥（内含采样策略）
 * @param sentinelTenantId 哨兵（共享）租户标识；无哨兵模型时为空串
 */
public record AuthzShadowBinding(
        @NonNull ScopedDataAccess scoped,
        @NonNull AuthzShadowReporter reporter,
        @NonNull String sentinelTenantId) {

    public AuthzShadowBinding {
        if (scoped == null) {
            throw new IllegalArgumentException("scoped 不能为空（fail-closed）");
        }
        if (reporter == null) {
            throw new IllegalArgumentException("reporter 不能为空（fail-closed）");
        }
        sentinelTenantId = sentinelTenantId == null ? "" : sentinelTenantId.trim();
    }

    /** 便捷构造：平台域（无哨兵行）。 */
    public static @NonNull AuthzShadowBinding platform(@NonNull ScopedDataAccess scoped,
                                                       @NonNull AuthzShadowReporter reporter) {
        return new AuthzShadowBinding(scoped, reporter, "");
    }

    /**
     * 当前签名参数是否被本载体覆盖（供装配期自检与测试使用）。
     *
     * @return 永远是 {@code true}；存在即开启，本方法只是把该语义显式化
     */
    public boolean enabled() {
        return true;
    }

    /** 采样键辅助：无主体时返回 {@code null}（由采样策略按空键处理）。 */
    public static @Nullable String samplingKey(@Nullable String subjectTenantId) {
        return subjectTenantId == null || subjectTenantId.isBlank() ? null : subjectTenantId;
    }
}
