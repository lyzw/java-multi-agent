package cn.studykid.multiagent.infrastructure.persistence;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;
import java.util.Map;
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
import cn.studykid.multiagent.ports.persistence.AuditPort;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link JdbcConfigPublishRequestRepository}（cap_config_publish_request）的 P1 影子态接线测试。
 *
 * <p>⚠️ <b>为什么独立成测</b>：本仓在 {@code persistence} 包，无法并入
 * {@code CapUniformTenantScopedShadowTest} 的表驱动；且读路径分 {@code find} / {@code list}
 * 两处（{@code loadForUpdate} 是 FOR UPDATE 锁，写路径不观测）。
 *
 * <p>cap_config_publish_request 支持 PLATFORM 作用域（{@code tenant_id IS NOT DISTINCT FROM}），
 * 但影子只在租户作用域（tenantId≠null）下观测；平台 NULL 行在染色时因 rowTenantId==null 被跳过。
 * 本测试覆盖租户作用域（非 null）的检出力（D13 反向验证）。
 *
 * <p>读路径自带显式 {@code tenant_id} 谓词 ⇒ {@code hasTenantPredicate=true}
 * （不计入「RLS 依赖」清单）。
 */
class JdbcConfigPublishRequestRepositoryShadowTest {

    private static final String TENANT = "tenant-A";
    private static final String SENTINEL = "shared-tenant";
    private static final String RESOURCE_TYPE = "cap_config_publish_request";

    private static final class Fixture {
        final JdbcConfigPublishRequestRepository repo;
        final InMemoryAuthzMismatchMetrics metrics;

        Fixture(ScopeFilter filter, boolean block) {
            this.repo = new JdbcConfigPublishRequestRepository(
                    new NoConnectDataSource(), NO_AUDIT);
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

    /** 空审计（测试不调用业务写路径，仅占位以满足构造约束）。 */
    private static final AuditPort NO_AUDIT = new AuditPort() {
        @Override public void recordRequired(String t, String a, String action,
                String rt, String rid, Map<String, Object> d) { }
        @Override public void recordAccess(String t, String a, String action,
                String rt, String rid, Map<String, Object> d) { }
        @Override public List<AuditView> findByResource(String t, String rt, String rid) {
            return List.of();
        }
    };

    @Test
    @DisplayName("✅ dormant 默认：钩子调用零副作用、零样本")
    void dormantDefault_noOpAndNoThrow() {
        JdbcConfigPublishRequestRepository repo =
                new JdbcConfigPublishRequestRepository(new NoConnectDataSource(), NO_AUDIT);
        InMemoryAuthzMismatchMetrics m = new InMemoryAuthzMismatchMetrics();
        assertDoesNotThrow(() -> repo.shadowCompare(RESOURCE_TYPE, TENANT, true));
        assertDoesNotThrow(() -> repo.shadowTaint(RESOURCE_TYPE, TENANT, TENANT));
        assertEquals(0, m.shadowSamples());
    }

    @Test
    @DisplayName("✅ 主体=绑定租户：零 mismatch、零染色违例、零 RLS 依赖")
    void matchingTenant_isClean() {
        Fixture f = new Fixture(new ScopeFilter.TenantOnly(TENANT), false);
        f.repo.shadowCompare(RESOURCE_TYPE, TENANT, true);
        f.repo.shadowTaint(RESOURCE_TYPE, TENANT, TENANT);
        assertEquals(0, f.metrics.mismatches());
        assertEquals(0, f.metrics.taintViolations());
        assertEquals(0, f.metrics.rlsDependent(),
                "读路径自带显式租户谓词 ⇒ 不应计入 RLS 依赖清单");
    }

    @Test
    @DisplayName("⚠️⚠️ 跨租户行必判违例（真实策略引擎，D13 反向验证）")
    void crossTenantRow_isViolationWithRealEngine() {
        PolicyRule rule = new PolicyRule("r-cpr", RESOURCE_TYPE,
                Action.RETRIEVE, ScopeRequirement.TENANT, ScopeEffect.TENANT_ONLY);
        PolicyEngine engine = new RuleBasedPolicyEngine(new InMemoryPolicyStore(List.of(rule)));
        InMemoryAuthzMismatchMetrics metrics = new InMemoryAuthzMismatchMetrics();
        JdbcConfigPublishRequestRepository repo =
                new JdbcConfigPublishRequestRepository(new NoConnectDataSource(), NO_AUDIT);
        repo.bindAuthzShadow(new AuthzShadowBinding(new ScopedDataAccess(new EngineGate(engine)),
                new AuthzShadowReporter(metrics, false), SENTINEL));

        repo.shadowTaint(RESOURCE_TYPE, TENANT, TENANT);
        assertEquals(0, metrics.taintViolations(), "本租户行不得判违例");

        repo.shadowTaint(RESOURCE_TYPE, TENANT, "tenant-B");
        assertEquals(1, metrics.taintViolations(),
                "⚠️⚠️ 主体是查询租户、行归属是他租户 → 必须违例；"
                        + "若恒为 0，说明染色把行租户当成了主体（同义反复）");
    }

    @Test
    @DisplayName("⚠️ Gate 租户 ≠ 查询绑定租户 ⇒ 记 mismatch")
    void mismatchedTenant_recordsMismatch() {
        Fixture f = new Fixture(new ScopeFilter.TenantOnly(TENANT), false);
        f.repo.shadowCompare(RESOURCE_TYPE, "tenant-C", true);
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
