package cn.studykid.multiagent.infrastructure.persistence;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;
import java.util.logging.Logger;

import javax.sql.DataSource;

import cn.studykid.multiagent.core.security.Action;
import cn.studykid.multiagent.core.security.Decision;
import cn.studykid.multiagent.core.security.ResourceRef;
import cn.studykid.multiagent.core.security.ScopeFilter;
import cn.studykid.multiagent.core.tenant.SubjectContext;
import cn.studykid.multiagent.infrastructure.security.AccessDeniedException;
import cn.studykid.multiagent.infrastructure.security.AccessGate;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowReporter;
import cn.studykid.multiagent.infrastructure.security.InMemoryAuthzMismatchMetrics;
import cn.studykid.multiagent.infrastructure.security.InMemoryPolicyStore;
import cn.studykid.multiagent.infrastructure.security.PolicyEngine;
import cn.studykid.multiagent.infrastructure.security.PolicyRule;
import cn.studykid.multiagent.infrastructure.security.RuleBasedPolicyEngine;
import cn.studykid.multiagent.infrastructure.security.ScopeEffect;
import cn.studykid.multiagent.infrastructure.security.ScopeRequirement;
import cn.studykid.multiagent.infrastructure.security.ScopedDataAccess;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link JdbcResultQueryRepository} 的 P1 影子态接线测试。
 *
 * <p>该仓一次读 4 张租户表（{@code run} / {@code weakness_record} / {@code run_step} /
 * {@code tool_operation}），是染色覆盖面最广的一个 —— 也因此最需要证明
 * 「染色确实有检出力」，而不是恒不违例的摆设。
 */
class JdbcResultQueryRepositoryShadowTest {

    private static final String TENANT = "tenant-A";
    private static final String SENTINEL = "shared-tenant";

    private static final class Fixture {
        final JdbcResultQueryRepository repo;
        final InMemoryAuthzMismatchMetrics metrics;

        Fixture(ScopeFilter filter, boolean block) {
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
            ScopedDataAccess scoped = new ScopedDataAccess(gate);
            this.metrics = new InMemoryAuthzMismatchMetrics();
            AuthzShadowReporter reporter = new AuthzShadowReporter(metrics, block);
            this.repo = new JdbcResultQueryRepository(
                    new NoConnectDataSource(), scoped, reporter, true, SENTINEL);
        }
    }

    @Test
    void dormantDefault_noOpAndNoThrow() {
        JdbcResultQueryRepository repo = new JdbcResultQueryRepository(new NoConnectDataSource());
        InMemoryAuthzMismatchMetrics m = new InMemoryAuthzMismatchMetrics();
        assertDoesNotThrow(() -> repo.shadowCompare("run", TENANT, true));
        assertDoesNotThrow(() -> repo.shadowTaint("run", TENANT, TENANT));
        assertEquals(0, m.shadowSamples());
    }

    @Test
    void shadowCompare_matchingTenant_noMismatch() {
        Fixture f = new Fixture(new ScopeFilter.TenantOnly(TENANT), false);
        f.repo.shadowCompare("run", TENANT, true);
        assertEquals(0, f.metrics.mismatches());
    }

    @Test
    void shadowCompare_mismatchedTenant_recordsMismatch() {
        Fixture f = new Fixture(new ScopeFilter.TenantOnly(TENANT), false);
        f.repo.shadowCompare("run", "tenant-C", true);
        assertEquals(1, f.metrics.mismatches());
    }

    @Test
    void shadowTaint_rowInScope_noViolation() {
        Fixture f = new Fixture(new ScopeFilter.TenantOnly(TENANT), false);
        f.repo.shadowTaint("weakness_record", TENANT, TENANT);
        assertEquals(0, f.metrics.taintViolations());
    }

    @Test
    void shadowTaint_crossTenantRow_recordsViolation() {
        Fixture f = new Fixture(new ScopeFilter.TenantOnly(TENANT), false);
        f.repo.shadowTaint("run_step", TENANT, "tenant-B");
        assertEquals(1, f.metrics.taintViolations());
    }

    @Test
    void shadowTaint_sentinelRow_passesForTenantWithShared() {
        Fixture f = new Fixture(new ScopeFilter.TenantWithShared(TENANT), false);
        f.repo.shadowTaint("tool_operation", TENANT, SENTINEL);
        assertEquals(0, f.metrics.taintViolations());
    }

    @Test
    void shadowTaint_nullRowTenant_isSkipped() {
        Fixture f = new Fixture(new ScopeFilter.TenantOnly(TENANT), false);
        assertDoesNotThrow(() -> f.repo.shadowTaint("run", TENANT, null));
        assertEquals(0, f.metrics.taintViolations());
    }

    /**
     * ⚠️⚠️ 染色必须真的能抓到跨租户行（D13 回归）。
     *
     * <p>用<b>真实的策略引擎</b>（而非测试替身返回常量 filter）：
     * 主体固定为 {@code TENANT}，策略算出 {@code TenantOnly(TENANT)}。
     * 此时他租户的行必须被判违例。
     *
     * <p>若把「行租户」当作主体（{@code SubjectContext.forTenant(rowTenantId)}，
     * 旧实现的写法），策略会算出 {@code TenantOnly(行租户)}，逐行比对恒通过 ——
     * 本用例会红。这条断言存在的意义就是锁死那个同义反复。
     */
    @Test
    void shadowTaint_realPolicyEngine_crossTenantRowIsViolation() {
        PolicyRule rule = new PolicyRule("r-run", "run", Action.RETRIEVE,
                ScopeRequirement.TENANT, ScopeEffect.TENANT_ONLY);
        PolicyEngine engine = new RuleBasedPolicyEngine(new InMemoryPolicyStore(List.of(rule)));
        ScopedDataAccess scoped = new ScopedDataAccess(new EngineGate(engine));
        InMemoryAuthzMismatchMetrics metrics = new InMemoryAuthzMismatchMetrics();
        JdbcResultQueryRepository repo = new JdbcResultQueryRepository(
                new NoConnectDataSource(), scoped, new AuthzShadowReporter(metrics, false),
                true, SENTINEL);

        repo.shadowTaint("run", TENANT, TENANT);
        assertEquals(0, metrics.taintViolations(), "本租户行不得判违例");

        repo.shadowTaint("run", TENANT, "tenant-B");
        assertEquals(1, metrics.taintViolations(),
                "⚠️⚠️ 主体是查询租户、行归属是他租户 → 必须违例；"
                        + "若恒为 0，说明染色把行租户当成了主体（同义反复）");
    }

    /** 把 {@link AccessGate} 直接接到真实策略引擎上的测试门面。 */
    private static final class EngineGate implements AccessGate {
        private final PolicyEngine engine;

        EngineGate(PolicyEngine engine) {
            this.engine = engine;
        }

        @Override
        public ScopeFilter authorize(Action action, ResourceRef resource) {
            return filter(engine.decide(SubjectContext.forTenant(TENANT), action, resource));
        }

        @Override
        public ScopeFilter authorizeCollection(Action action, String resourceType) {
            return filter(engine.decide(SubjectContext.forTenant(TENANT), action,
                    ResourceRef.collection(resourceType)));
        }

        @Override
        public ScopeFilter authorizeCollection(SubjectContext subject, Action action,
                                               String resourceType) {
            return filter(engine.decide(subject, action, ResourceRef.collection(resourceType)));
        }

        private static ScopeFilter filter(Decision decision) {
            if (decision instanceof Decision.Permit permit) {
                return permit.filter();
            }
            throw new AccessDeniedException("DENIED", "测试门面：策略拒绝");
        }
    }

    /** 永不返回连接的 DataSource 桩（影子/染色路径不触碰连接，故安全）。 */
    private static final class NoConnectDataSource implements DataSource {
        @Override public Connection getConnection() throws SQLException {
            throw new UnsupportedOperationException("影子测试不触碰连接");
        }
        @Override public Connection getConnection(String u, String p) throws SQLException {
            throw new UnsupportedOperationException("影子测试不触碰连接");
        }
        @Override public PrintWriter getLogWriter() throws SQLException { return null; }
        @Override public void setLogWriter(PrintWriter out) throws SQLException { }
        @Override public void setLoginTimeout(int s) throws SQLException { }
        @Override public int getLoginTimeout() throws SQLException { return 0; }
        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return Logger.getGlobal();
        }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("Not a wrapper");
        }
    }
}
