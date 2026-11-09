package cn.studykid.multiagent.infrastructure.persistence.cap;

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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link JdbcConfigLifecycleRepository} 的 P1 影子态接线测试（租户级 3 分支）。
 *
 * <p>⚠️ <b>为什么独立成测、不进 {@code CapUniformTenantScopedShadowTest}</b>：
 * 本仓是<b>混合形态</b> —— {@code stateOf} 的 6 个分支里 3 个租户级
 * （agent / result_contract / routing_policy，已接影子）、
 * 3 个平台级（tool / skill / mcp_server，无 {@code tenant_id}，<b>刻意不观测</b>）。
 * 平台级分支若被租户影子观测会制造恒定假违例，所以本测试只参数化 3 个租户级资源类型，
 * 并用 {@code stateOf("tool", null, ...)} 形态的平台分支<b>不经过</b>影子钩子这一点
 * 由资源类型映射（{@code shadowResourceType} 返回 null）保证。
 *
 * <p>三者均已在 {@code AuthzShadowPolicyDefaults.RESOURCE_TYPES} 登记（此前批次）；
 * {@code hasTenantPredicate=true}（SQL 自带显式 {@code tenant_id = ?::uuid}）。
 *
 * <p>本测试证明「染色 / 比对确实有检出力」，而不是恒不违例的摆设（D13 防同义反复）。
 */
class JdbcConfigLifecycleRepositoryShadowTest {

    private static final String TENANT = "tenant-A";
    private static final String SENTINEL = "shared-tenant";

    private static final class Fixture {
        final JdbcConfigLifecycleRepository repo;
        final InMemoryAuthzMismatchMetrics metrics;

        Fixture(ScopeFilter filter, boolean block) {
            this.repo = new JdbcConfigLifecycleRepository(new NoConnectDataSource());
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
            this.metrics = new InMemoryAuthzMismatchMetrics();
            repo.bindAuthzShadow(new AuthzShadowBinding(new ScopedDataAccess(gate),
                    new AuthzShadowReporter(metrics, block), SENTINEL));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"cap_agent_def", "cap_result_contract", "cap_routing_policy"})
    @DisplayName("✅ dormant 默认：钩子调用零副作用、零样本")
    void dormantDefault_noOpAndNoThrow(String type) {
        JdbcConfigLifecycleRepository repo =
                new JdbcConfigLifecycleRepository(new NoConnectDataSource());
        InMemoryAuthzMismatchMetrics m = new InMemoryAuthzMismatchMetrics();
        assertDoesNotThrow(() -> repo.shadowCompare(type, TENANT, true));
        assertDoesNotThrow(() -> repo.shadowTaint(type, TENANT, TENANT));
        assertEquals(0, m.shadowSamples());
    }

    @ParameterizedTest
    @ValueSource(strings = {"cap_agent_def", "cap_result_contract", "cap_routing_policy"})
    @DisplayName("✅ 主体=绑定租户：零 mismatch、零染色违例、零 RLS 依赖")
    void matchingTenant_isClean(String type) {
        Fixture f = new Fixture(new ScopeFilter.TenantOnly(TENANT), false);
        f.repo.shadowCompare(type, TENANT, true);
        f.repo.shadowTaint(type, TENANT, TENANT);
        assertEquals(0, f.metrics.mismatches());
        assertEquals(0, f.metrics.taintViolations());
        assertEquals(0, f.metrics.rlsDependent(),
                "读路径自带显式租户谓词 ⇒ 不应计入 RLS 依赖清单");
    }

    @ParameterizedTest
    @ValueSource(strings = {"cap_agent_def", "cap_result_contract", "cap_routing_policy"})
    @DisplayName("⚠️⚠️ 跨租户行必判违例（真实策略引擎，D13 反向验证）")
    void crossTenantRow_isViolationWithRealEngine(String type) {
        PolicyRule rule = new PolicyRule("r-" + type, type,
                Action.RETRIEVE, ScopeRequirement.TENANT, ScopeEffect.TENANT_ONLY);
        PolicyEngine engine = new RuleBasedPolicyEngine(new InMemoryPolicyStore(List.of(rule)));
        InMemoryAuthzMismatchMetrics metrics = new InMemoryAuthzMismatchMetrics();
        JdbcConfigLifecycleRepository repo =
                new JdbcConfigLifecycleRepository(new NoConnectDataSource());
        repo.bindAuthzShadow(new AuthzShadowBinding(new ScopedDataAccess(new EngineGate(engine)),
                new AuthzShadowReporter(metrics, false), SENTINEL));

        repo.shadowTaint(type, TENANT, TENANT);
        assertEquals(0, metrics.taintViolations(), "本租户行不得判违例");

        repo.shadowTaint(type, TENANT, "tenant-B");
        assertEquals(1, metrics.taintViolations(),
                "⚠️⚠️ 主体是查询租户、行归属是他租户 → 必须违例；"
                        + "若恒为 0，说明染色把行租户当成了主体（同义反复）");
    }

    @ParameterizedTest
    @ValueSource(strings = {"cap_agent_def", "cap_result_contract", "cap_routing_policy"})
    @DisplayName("⚠️ Gate 租户 ≠ 查询绑定租户 ⇒ 记 mismatch")
    void mismatchedTenant_recordsMismatch(String type) {
        Fixture f = new Fixture(new ScopeFilter.TenantOnly(TENANT), false);
        f.repo.shadowCompare(type, "tenant-C", true);
        assertEquals(1, f.metrics.mismatches(), "应记 mismatch");
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
