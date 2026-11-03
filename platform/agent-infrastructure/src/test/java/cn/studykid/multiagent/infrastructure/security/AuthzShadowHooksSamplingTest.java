package cn.studykid.multiagent.infrastructure.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;

import cn.studykid.multiagent.core.security.Action;
import cn.studykid.multiagent.core.security.ResourceRef;
import cn.studykid.multiagent.core.security.ScopeFilter;
import cn.studykid.multiagent.core.tenant.SubjectContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 影子钩子的<b>采样闸门</b>单测（12 文 §7.1 R7 · 13 文 §5.2）。
 *
 * <p>要点：采样只决定「这次查询要不要看」，<b>不决定「看到了要不要报」</b>；
 * 且采样必须发生在「算 filter」之前 —— 否则采样的性能意义不成立。
 */
class AuthzShadowHooksSamplingTest {

    private static final String TENANT = "tenant-A";

    private static final class Fixture {
        final InMemoryAuthzMismatchMetrics metrics = new InMemoryAuthzMismatchMetrics();
        final AuthzShadowReporter reporter;
        final ScopedDataAccess scoped;

        Fixture(ScopeFilter filter, AuthzSamplingPolicy sampling) {
            this.reporter = new AuthzShadowReporter(metrics, false, sampling);
            AccessGate gate = new AccessGate() {
                @Override
                public ScopeFilter authorize(Action a, ResourceRef r) {
                    return filter;
                }

                @Override
                public ScopeFilter authorizeCollection(Action a, String t) {
                    return filter;
                }

                @Override
                public ScopeFilter authorizeCollection(SubjectContext s, Action a, String t) {
                    return filter;
                }
            };
            this.scoped = new ScopedDataAccess(gate);
        }
    }

    @Test
    @DisplayName("未命中采样 → 连比对样本都不记（不是「记了但不算 mismatch」）")
    void notSampled_recordsNothing() {
        Fixture f = new Fixture(new ScopeFilter.TenantOnly(TENANT),
                AuthzSamplingPolicy.of(0.0d, 60_000L, Set.of()));

        AuthzShadowHooks.compare(f.scoped, f.reporter, true, "agent",
                SubjectContext.forTenant("tenant-C"), "tenant-C", true);
        AuthzShadowHooks.taint(f.scoped, f.reporter, true, "agent", "",
                SubjectContext.forTenant(TENANT), "tenant-C", false);

        assertEquals(0, f.metrics.shadowSamples(), "未采样不应产生比对样本");
        assertEquals(0, f.metrics.mismatches(), "未采样不应产生 mismatch");
        assertEquals(0, f.metrics.taintViolations(), "未采样不应产生 taint 违例");
    }

    @Test
    @DisplayName("⚠️ 高敏资源命中 100% → 即使 rate=0 也照常检出越权行")
    void highSensitivity_stillDetects() {
        Fixture f = new Fixture(new ScopeFilter.TenantOnly(TENANT),
                AuthzSamplingPolicy.of(0.0d, 60_000L, Set.of("run")));

        AuthzShadowHooks.taint(f.scoped, f.reporter, true, "run", "",
                SubjectContext.forTenant(TENANT), "tenant-B", false);

        assertEquals(1, f.metrics.taintViolations(), "高敏资源必须 100% 染色，不得被采样丢掉");
    }

    @Test
    @DisplayName("采样开启但命中 → 与非采样路径结论一致")
    void sampled_hasSameEffectAsUnsampled() {
        Fixture f = new Fixture(new ScopeFilter.TenantOnly(TENANT),
                AuthzSamplingPolicy.of(1.0d, 60_000L, Set.of()));

        AuthzShadowHooks.compare(f.scoped, f.reporter, true, "agent",
                SubjectContext.forTenant("tenant-C"), "tenant-C", true);

        assertEquals(1, f.metrics.shadowSamples());
        assertEquals(1, f.metrics.mismatches());
    }
}
