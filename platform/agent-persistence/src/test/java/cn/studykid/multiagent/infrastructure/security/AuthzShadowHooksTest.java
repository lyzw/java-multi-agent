package cn.studykid.multiagent.infrastructure.security;

import cn.studykid.multiagent.core.security.Action;
import cn.studykid.multiagent.core.security.ResourceRef;
import cn.studykid.multiagent.core.security.ScopeFilter;
import cn.studykid.multiagent.core.tenant.SubjectContext;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AuthzShadowHooksTest {

    private static final String TENANT = "tenant-A";
    private static final String SENTINEL = "shared-tenant";

    /** 一套「Gate→ScopeFilter 固定返回」的测试夹具（P1 影子期只观测不阻断）。 */
    private static final class Fixture {
        final InMemoryAuthzMismatchMetrics metrics = new InMemoryAuthzMismatchMetrics();
        final AuthzShadowReporter reporter;
        final ScopedDataAccess scoped;

        Fixture(ScopeFilter filter, boolean block) {
            this.reporter = new AuthzShadowReporter(metrics, block);
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
    void compare_matchingTenant_noMismatch() {
        Fixture f = new Fixture(new ScopeFilter.TenantOnly(TENANT), false);
        AuthzShadowHooks.compare(f.scoped, f.reporter, true, "agent",
                SubjectContext.forTenant(TENANT), TENANT, true);
        assertEquals(0, f.metrics.mismatches());
    }

    @Test
    void compare_mismatchedTenant_recordsMismatch() {
        Fixture f = new Fixture(new ScopeFilter.TenantOnly(TENANT), false);
        AuthzShadowHooks.compare(f.scoped, f.reporter, true, "agent",
                SubjectContext.forTenant("tenant-C"), "tenant-C", true);
        assertEquals(1, f.metrics.mismatches());
    }

    @Test
    void compare_gateNone_withPredicate_recordsMismatch() {
        Fixture f = new Fixture(ScopeFilter.NONE, false);
        AuthzShadowHooks.compare(f.scoped, f.reporter, true, "agent",
                SubjectContext.forTenant(TENANT), TENANT, true);
        assertEquals(1, f.metrics.mismatches());
    }

    @Test
    void compare_disabled_isNoOp() {
        Fixture f = new Fixture(new ScopeFilter.TenantOnly(TENANT), false);
        AuthzShadowHooks.compare(f.scoped, f.reporter, false, "agent",
                SubjectContext.forTenant("tenant-C"), "tenant-C", true);
        assertEquals(0, f.metrics.mismatches());
    }

    @Test
    void taint_inScopeRow_noViolation() {
        Fixture f = new Fixture(new ScopeFilter.TenantOnly(TENANT), false);
        AuthzShadowHooks.taint(f.scoped, f.reporter, true, "agent", SENTINEL,
                SubjectContext.forTenant(TENANT), TENANT, false);
        assertEquals(0, f.metrics.taintViolations());
    }

    @Test
    void taint_crossTenant_recordsViolation() {
        Fixture f = new Fixture(new ScopeFilter.TenantOnly(TENANT), false);
        AuthzShadowHooks.taint(f.scoped, f.reporter, true, "agent", SENTINEL,
                SubjectContext.forTenant(TENANT), "tenant-B", false);
        assertEquals(1, f.metrics.taintViolations());
    }

    @Test
    void taint_sentinelSharedRow_passesForTenantWithShared() {
        Fixture f = new Fixture(new ScopeFilter.TenantWithShared(TENANT), false);
        AuthzShadowHooks.taint(f.scoped, f.reporter, true, "agent", SENTINEL,
                SubjectContext.forTenant(TENANT), SENTINEL, true);
        assertEquals(0, f.metrics.taintViolations());
    }

    // ── RLS 依赖度（2026-11-09 新增）────────────────────────────────
    // ⚠️ 为什么必须有这个信号：RLS 仍在时，染色违例与比对不一致**结构性为 0**
    //    （RLS 保证只返回本租户行；Gate 主体租户与查询绑定租户通常相同），
    //    ⇒「2 周 AUTHZ_MISMATCH=0」无论隔离做得好不好都会达成。
    //    「查询未带显式租户谓词（靠 RLS 兜底）」才是 P1 期间唯一有非平凡信号的量。

    @Test
    void compare_tenantOnly_withoutTenantPredicate_recordsRlsDependency() {
        Fixture f = new Fixture(new ScopeFilter.TenantOnly(TENANT), false);
        AuthzShadowHooks.compare(f.scoped, f.reporter, true, "agent",
                SubjectContext.forTenant(TENANT), TENANT, false);
        assertEquals(1, f.metrics.rlsDependent());
    }

    @Test
    void compare_tenantOnly_withTenantPredicate_noRlsDependency() {
        Fixture f = new Fixture(new ScopeFilter.TenantOnly(TENANT), false);
        AuthzShadowHooks.compare(f.scoped, f.reporter, true, "agent",
                SubjectContext.forTenant(TENANT), TENANT, true);
        assertEquals(0, f.metrics.rlsDependent());
    }

    @Test
    void compare_rlsDependency_doesNotPolluteMismatchCounter() {
        // ⚠️ 出口门禁指标（mismatches）的语义不能被「迁移清单」污染：
        //    否则要么门禁永不达成，要么被迫调松阈值 —— 两种都是把防线拆掉。
        Fixture f = new Fixture(new ScopeFilter.TenantOnly(TENANT), false);
        AuthzShadowHooks.compare(f.scoped, f.reporter, true, "agent",
                SubjectContext.forTenant(TENANT), TENANT, false);
        assertEquals(0, f.metrics.mismatches());
        assertEquals(1, f.metrics.rlsDependent());
    }

    @Test
    void compare_rlsDependency_neverBlocksEvenInBlockMode() {
        // RLS 依赖是「退役前要补的工作项」，不是违例 ⇒ 阻断模式下也绝不抛异常。
        Fixture f = new Fixture(new ScopeFilter.TenantOnly(TENANT), true);
        AuthzShadowHooks.compare(f.scoped, f.reporter, true, "agent",
                SubjectContext.forTenant(TENANT), TENANT, false);
        assertEquals(1, f.metrics.rlsDependent());
        assertEquals(0, f.metrics.mismatches());
    }

    @Test
    void compare_disabled_noRlsDependency() {
        Fixture f = new Fixture(new ScopeFilter.TenantOnly(TENANT), false);
        AuthzShadowHooks.compare(f.scoped, f.reporter, false, "agent",
                SubjectContext.forTenant(TENANT), TENANT, false);
        assertEquals(0, f.metrics.rlsDependent());
    }

    @Test
    void compare_gateNone_withoutPredicate_noRlsDependency() {
        // Gate=None（期望 0 行）时，缺谓词不是「靠 RLS 兜底」而是「本来就该 0 行」。
        Fixture f = new Fixture(ScopeFilter.NONE, false);
        AuthzShadowHooks.compare(f.scoped, f.reporter, true, "agent",
                SubjectContext.forTenant(TENANT), TENANT, false);
        assertEquals(0, f.metrics.rlsDependent());
    }
}
