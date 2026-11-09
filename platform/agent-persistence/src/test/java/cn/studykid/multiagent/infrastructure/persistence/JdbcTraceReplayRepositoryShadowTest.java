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
import cn.studykid.multiagent.infrastructure.security.AuthzShadowBinding;
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
 * {@link JdbcTraceReplayRepository} 的 P1 影子态接线测试。
 *
 * <p>该仓是 P1 对话回放（G1 闭环）的<b>只读</b>读取面，覆盖 6 张租户级表：
 * {@code run_message} / {@code tool_operation} / {@code run} / {@code run_step} /
 * {@code execution_snapshot} / {@code usage_record}（resourceType 均已登记于
 * {@code AuthzShadowPolicyDefaults.RESOURCE_TYPES}，对齐 policy-mapping.yaml 的
 * TENANT_ONLY + FORCE RLS）。另有 {@code resolveTenantId} 属平台域反查（无租户谓词），
 * 不在租户级影子观测范围内（与 {@code JdbcReviewTaskRepository} 同口径）。
 *
 * <p>本测试证明「染色 / 比对确实有检出力」，而不是恒不违例的摆设（D13 防同义反复）。
 */
class JdbcTraceReplayRepositoryShadowTest {

    private static final String TENANT = "tenant-A";
    private static final String SENTINEL = "shared-tenant";

    private static final class Fixture {
        final JdbcTraceReplayRepository repo;
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
            // 本仓经 @Bean 实例化（单参构造），由容器 @Autowired(required=false) setter 接线；
            // 测试里手动 bindAuthzShadow 复现该装配。
            this.repo = new JdbcTraceReplayRepository(new NoConnectDataSource());
            this.repo.bindAuthzShadow(new AuthzShadowBinding(scoped, reporter, SENTINEL));
        }
    }

    @Test
    void dormantDefault_noOpAndNoThrow() {
        JdbcTraceReplayRepository repo = new JdbcTraceReplayRepository(
                new NoConnectDataSource());
        InMemoryAuthzMismatchMetrics m = new InMemoryAuthzMismatchMetrics();
        assertDoesNotThrow(() -> repo.shadowCompare("run_message", TENANT, true));
        assertDoesNotThrow(() -> repo.shadowTaint("run", TENANT, TENANT));
        assertEquals(0, m.shadowSamples());
    }

    @Test
    void shadowCompare_matchingTenant_noMismatch() {
        Fixture f = new Fixture(new ScopeFilter.TenantOnly(TENANT), false);
        f.repo.shadowCompare("run_message", TENANT, true);
        assertEquals(0, f.metrics.mismatches());
    }

    @Test
    void shadowCompare_mismatchedTenant_recordsMismatch() {
        Fixture f = new Fixture(new ScopeFilter.TenantOnly(TENANT), false);
        f.repo.shadowCompare("run_message", "tenant-C", true);
        assertEquals(1, f.metrics.mismatches());
    }

    @Test
    void shadowTaint_rowInScope_noViolation() {
        Fixture f = new Fixture(new ScopeFilter.TenantOnly(TENANT), false);
        f.repo.shadowTaint("run", TENANT, TENANT);
        assertEquals(0, f.metrics.taintViolations());
    }

    @Test
    void shadowTaint_crossTenantRow_recordsViolation() {
        Fixture f = new Fixture(new ScopeFilter.TenantOnly(TENANT), false);
        f.repo.shadowTaint("run_step", TENANT, "tenant-B");
        assertEquals(1, f.metrics.taintViolations());
    }

    /**
     * {@code usage_record} 是聚合查询（无逐行 tenant_id），仅带租户谓词的保护——
     * 比对层确认谓词存在即足，不要求逐行染色。
     */
    @Test
    void shadowCompare_usageRecord_aggregate_withPredicate_noMismatch() {
        Fixture f = new Fixture(new ScopeFilter.TenantOnly(TENANT), false);
        f.repo.shadowCompare("usage_record", TENANT, true);
        assertEquals(0, f.metrics.mismatches());
    }

    /**
     * ⚠️⚠️ 染色必须真的能抓到跨租户行（D13 回归，真实策略引擎）。
     *
     * <p>主体固定为 {@code TENANT}，真实引擎按登记的 {@code run_message} 规则算出
     * {@code TenantOnly(TENANT)}。他租户的行必须被判违例。
     */
    @Test
    void shadowTaint_realPolicyEngine_runMessage_crossTenantRowIsViolation() {
        PolicyRule rule = new PolicyRule("r-rm", "run_message", Action.RETRIEVE,
                ScopeRequirement.TENANT, ScopeEffect.TENANT_ONLY);
        PolicyEngine engine = new RuleBasedPolicyEngine(new InMemoryPolicyStore(List.of(rule)));
        ScopedDataAccess scoped = new ScopedDataAccess(new EngineGate(engine));
        InMemoryAuthzMismatchMetrics metrics = new InMemoryAuthzMismatchMetrics();
        JdbcTraceReplayRepository repo = new JdbcTraceReplayRepository(new NoConnectDataSource());
        repo.bindAuthzShadow(new AuthzShadowBinding(scoped,
                new AuthzShadowReporter(metrics, false), SENTINEL));

        repo.shadowTaint("run_message", TENANT, TENANT);
        assertEquals(0, metrics.taintViolations(), "本租户行不得判违例");

        repo.shadowTaint("run_message", TENANT, "tenant-B");
        assertEquals(1, metrics.taintViolations(),
                "⚠️⚠️ 主体是查询租户、行归属是他租户 → 必须违例；"
                        + "若恒为 0，说明染色把行租户当成了主体（同义反复）");
    }

    /**
     * ⚠️⚠️ {@code execution_snapshot} 路径同样必须真的能抓到跨租户行（D13 回归，独立规则）。
     */
    @Test
    void shadowTaint_realPolicyEngine_executionSnapshot_crossTenantRowIsViolation() {
        PolicyRule rule = new PolicyRule("r-es", "execution_snapshot", Action.RETRIEVE,
                ScopeRequirement.TENANT, ScopeEffect.TENANT_ONLY);
        PolicyEngine engine = new RuleBasedPolicyEngine(new InMemoryPolicyStore(List.of(rule)));
        ScopedDataAccess scoped = new ScopedDataAccess(new EngineGate(engine));
        InMemoryAuthzMismatchMetrics metrics = new InMemoryAuthzMismatchMetrics();
        JdbcTraceReplayRepository repo = new JdbcTraceReplayRepository(new NoConnectDataSource());
        repo.bindAuthzShadow(new AuthzShadowBinding(scoped,
                new AuthzShadowReporter(metrics, false), SENTINEL));

        repo.shadowTaint("execution_snapshot", TENANT, TENANT);
        assertEquals(0, metrics.taintViolations(), "本租户行不得判违例");

        repo.shadowTaint("execution_snapshot", TENANT, "tenant-B");
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
