package cn.studykid.multiagent.infrastructure.security;

import java.util.function.Supplier;

import cn.studykid.multiagent.core.security.Action;
import cn.studykid.multiagent.core.security.ScopeFilter;
import cn.studykid.multiagent.core.tenant.SubjectContext;
import cn.studykid.multiagent.infrastructure.persistence.scope.RowAttribution;
import cn.studykid.multiagent.infrastructure.persistence.scope.TaintVerifier;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 影子比对 / 染色校验的<b>统一静态入口</b>（12 文 §4.5 / D5 + D6）。
 *
 * <p>各 Repository 的影子钩子统一委托本类，避免逐仓重复比对逻辑（也避免改一处漏一处）。
 * 三个前置条件（启用 / 有 reporter / 有 gate / 有主体）不满足时<b>完全无副作用</b>，
 * 保证影子态默认关闭时零行为变更。
 *
 * <p><b>两种主体来源，两个入口</b>：
 * <ul>
 *   <li><b>显式主体</b>（推荐）：{@code compare(scoped, reporter, enabled, type, subject, ...)}
 *       —— 后台任务 / worker 路径没有请求上下文，或需要钉死某个主体时使用；</li>
 *   <li><b>环境主体</b>：{@code compare(scoped, reporter, enabled, type, ...)}
 *       —— 请求路径由 {@code TenantContext} 线程上下文提供主体，
 *       与改造前的既有行为保持一致。</li>
 * </ul>
 * 两者共用同一套比对与染色逻辑，<b>不存在「某个仓库自己写一份」的旁路</b>
 * —— {@code ScopeBypassArchitectureTest} 会扫描并否决自建实现。
 *
 * <p>比对与染色均<b>只记日志、不改变请求结果</b>（P1 影子期）；
 * 进入 P2 双写期后由 {@link AuthzShadowReporter#blockOnViolation()} 翻转为阻断。
 */
public final class AuthzShadowHooks {

    private AuthzShadowHooks() {
    }

    // ── 影子比对 ──────────────────────────────────────────────────

    /**
     * 影子比对（显式主体）。把 Gate 算出的 {@link ScopeFilter} 与本次查询实际绑定租户做离线比对。
     *
     * <ul>
     *   <li>Gate=None（期望 0 行）但查询带租户谓词 ⇒ mismatch；</li>
     *   <li>Gate 租户 ≠ 查询绑定租户 ⇒ mismatch；</li>
     *   <li>其余（含 TenantWithShared / Unrestricted）影子期不做严格比对。</li>
     * </ul>
     */
    public static void compare(@Nullable ScopedDataAccess scoped,
                               @Nullable AuthzShadowReporter reporter,
                               boolean enabled,
                               @NonNull String resourceType,
                               @Nullable SubjectContext subject,
                               @Nullable String boundTenantId,
                               boolean hasTenantPredicate) {
        if (subject == null) {
            return;
        }
        compare(scoped, reporter, enabled, resourceType,
                () -> scoped == null ? null
                        : scoped.scopeForCollection(subject, Action.RETRIEVE, resourceType),
                boundTenantId, hasTenantPredicate,
                // 采样键必须「整次查询恒定」：用主体租户，而不是逐行变化的行租户
                subject.tenantId());
    }

    /**
     * 影子比对（环境主体）。
     *
     * <p>适用于改造前已依赖线程上下文主体的仓库 —— 保持既有行为不变，
     * 但比对逻辑仍然只有这一份实现。
     */
    public static void compare(@Nullable ScopedDataAccess scoped,
                               @Nullable AuthzShadowReporter reporter,
                               boolean enabled,
                               @NonNull String resourceType,
                               @Nullable String boundTenantId,
                               boolean hasTenantPredicate) {
        compare(scoped, reporter, enabled, resourceType,
                () -> scoped == null ? null
                        : scoped.scopeForCollection(Action.RETRIEVE, resourceType),
                boundTenantId, hasTenantPredicate,
                // 环境主体形态没有认证主体，退化为「按查询绑定租户」采样（同一查询内恒定）
                boundTenantId);
    }

    private static void compare(@Nullable ScopedDataAccess scoped,
                                @Nullable AuthzShadowReporter reporter,
                                boolean enabled,
                                @NonNull String resourceType,
                                @NonNull Supplier<@Nullable ScopeFilter> resolver,
                                @Nullable String boundTenantId,
                                boolean hasTenantPredicate,
                                @Nullable String samplingKey) {
        if (!enabled || reporter == null || scoped == null) {
            return;
        }
        if (!reporter.shouldEvaluate(resourceType, samplingKey)) {
            return;
        }
        try {
            ScopeFilter f = resolver.get();
            if (f == null) {
                return;
            }
            reporter.recordScopeEvaluation(resourceType, f);
            if (f instanceof ScopeFilter.None) {
                if (hasTenantPredicate) {
                    reporter.reportMismatch(resourceType,
                            "Gate=None（期望0行）但查询带租户谓词，可能返回行");
                }
            } else if (f instanceof ScopeFilter.TenantOnly t) {
                if (boundTenantId != null && !boundTenantId.equals(t.tenantId())) {
                    reporter.reportMismatch(resourceType,
                            "Gate 租户=" + t.tenantId() + " 但查询绑定租户=" + boundTenantId);
                }
            }
        } catch (AccessDeniedException ex) {
            reporter.reportMismatch(resourceType, "Gate 拒绝: " + ex.getMessage());
        }
    }

    // ── 逐行染色 ──────────────────────────────────────────────────

    /**
     * 逐行染色校验（显式主体）：给定一行的租户归属，与 Gate 算出的 {@link ScopeFilter} 比对；
     * 越权行记 taint 违例。返回路径上逐行调用即可复现「越权即红」。
     */
    public static void taint(@Nullable ScopedDataAccess scoped,
                             @Nullable AuthzShadowReporter reporter,
                             boolean enabled,
                             @NonNull String resourceType,
                             @NonNull String sentinelTenantId,
                             @Nullable SubjectContext subject,
                             @Nullable String rowTenantId,
                             boolean shared) {
        if (subject == null) {
            return;
        }
        taint(scoped, reporter, enabled, resourceType, sentinelTenantId,
                () -> scoped == null ? null
                        : scoped.scopeForCollection(subject, Action.RETRIEVE, resourceType),
                rowTenantId, shared,
                // 采样键取主体租户（整次查询恒定），而不是本行租户（逐行变化）
                subject.tenantId());
    }

    /** 逐行染色校验（环境主体）。 */
    public static void taint(@Nullable ScopedDataAccess scoped,
                             @Nullable AuthzShadowReporter reporter,
                             boolean enabled,
                             @NonNull String resourceType,
                             @NonNull String sentinelTenantId,
                             @Nullable String rowTenantId,
                             boolean shared) {
        taint(scoped, reporter, enabled, resourceType, sentinelTenantId,
                () -> scoped == null ? null
                        : scoped.scopeForCollection(Action.RETRIEVE, resourceType),
                rowTenantId, shared,
                // 环境主体形态无查询级主体键；用空键 ⇒ 同一窗口内该资源整体采/整体不采
                "");
    }

    private static void taint(@Nullable ScopedDataAccess scoped,
                              @Nullable AuthzShadowReporter reporter,
                              boolean enabled,
                              @NonNull String resourceType,
                              @NonNull String sentinelTenantId,
                              @NonNull Supplier<@Nullable ScopeFilter> resolver,
                              @Nullable String rowTenantId,
                              boolean shared,
                              @NonNull String samplingKey) {
        if (!enabled || reporter == null || scoped == null || rowTenantId == null) {
            return;
        }
        if (!reporter.shouldEvaluate(resourceType, samplingKey)) {
            return;
        }
        try {
            ScopeFilter f = resolver.get();
            if (f == null) {
                return;
            }
            TaintVerifier verifier = new TaintVerifier(f, sentinelTenantId);
            RowAttribution row = new RowAttribution(rowTenantId, shared);
            if (!verifier.accept(row)) {
                reporter.reportTaintViolation(resourceType, row, f);
            }
        } catch (AccessDeniedException ex) {
            reporter.reportMismatch(resourceType, "Gate 拒绝(taint): " + ex.getMessage());
        }
    }
}
