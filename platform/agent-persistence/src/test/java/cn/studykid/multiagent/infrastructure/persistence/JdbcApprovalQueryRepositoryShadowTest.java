package cn.studykid.multiagent.infrastructure.persistence;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Clock;
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
import cn.studykid.multiagent.ports.persistence.AuditPort.AuditView;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link JdbcApprovalQueryRepository} 的 P1 影子态接线测试。
 *
 * <p>该仓是<b>审批台</b>（G8 · FR-014）读取面，物理表 {@code approval}
 * （policy-mapping.yaml：TENANT_ONLY + forced=t），并与 {@code tool_operation}
 * 按<b>租户等值</b> JOIN（{@code o.tenant_id = a.tenant_id}）。
 *
 * <p>⚠️ <b>两张表的行归属必须分别染色</b>：用主表 {@code a} 的租户去推断从表 {@code o} 的租户，
 * 就是同义反复（D13）。故 SELECT 分别取 {@code a_tenant_id} / {@code o_tenant_id}。
 *
 * <p>写路径 {@code lockAndRead}（FOR UPDATE，供 {@code decide} 用）属 P2 观测范围外。
 */
class JdbcApprovalQueryRepositoryShadowTest {

    private static final String TENANT = "tenant-A";
    private static final String SENTINEL = "shared-tenant";

    private static final class Fixture {
        final JdbcApprovalQueryRepository repo;
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
            this.repo = new JdbcApprovalQueryRepository(new NoConnectDataSource(),
                    new NoOpAuditPort(), Clock.systemUTC());
            this.repo.bindAuthzShadow(new AuthzShadowBinding(scoped, reporter, SENTINEL));
        }
    }

    @Test
    void dormantDefault_noOpAndNoThrow() {
        JdbcApprovalQueryRepository repo = new JdbcApprovalQueryRepository(
                new NoConnectDataSource(), new NoOpAuditPort(), Clock.systemUTC());
        InMemoryAuthzMismatchMetrics m = new InMemoryAuthzMismatchMetrics();
        assertDoesNotThrow(() -> repo.shadowCompare("approval", TENANT, true));
        assertDoesNotThrow(() -> repo.shadowTaint("approval", TENANT, TENANT));
        assertEquals(0, m.shadowSamples());
    }

    @Test
    void shadowCompare_matchingTenant_noMismatch() {
        Fixture f = new Fixture(new ScopeFilter.TenantOnly(TENANT), false);
        f.repo.shadowCompare("approval", TENANT, true);
        assertEquals(0, f.metrics.mismatches());
    }

    @Test
    void shadowCompare_mismatchedTenant_recordsMismatch() {
        Fixture f = new Fixture(new ScopeFilter.TenantOnly(TENANT), false);
        f.repo.shadowCompare("approval", "tenant-C", true);
        assertEquals(1, f.metrics.mismatches());
    }

    @Test
    void shadowCompare_withTenantPredicate_noRlsDependency() {
        Fixture f = new Fixture(new ScopeFilter.TenantOnly(TENANT), false);
        f.repo.shadowCompare("approval", TENANT, true);
        assertEquals(0, f.metrics.rlsDependent(),
                "读路径自带显式 a.tenant_id 谓词 ⇒ 不依赖 RLS 兜底");
    }

    @Test
    void shadowTaint_rowInScope_noViolation() {
        Fixture f = new Fixture(new ScopeFilter.TenantOnly(TENANT), false);
        f.repo.shadowTaint("approval", TENANT, TENANT);
        assertEquals(0, f.metrics.taintViolations());
    }

    @Test
    void shadowTaint_crossTenantRow_recordsViolation() {
        Fixture f = new Fixture(new ScopeFilter.TenantOnly(TENANT), false);
        f.repo.shadowTaint("approval", TENANT, "tenant-B");
        assertEquals(1, f.metrics.taintViolations());
    }

    /**
     * ⚠️ 从表（tool_operation）必须<b>各自</b>染色：用主表租户推断从表租户 = 同义反复。
     */
    @Test
    void shadowTaint_joinedTable_crossTenantRow_recordsViolation() {
        Fixture f = new Fixture(new ScopeFilter.TenantOnly(TENANT), false);
        f.repo.shadowTaint("tool_operation", TENANT, "tenant-B");
        assertEquals(1, f.metrics.taintViolations());
    }

    /**
     * ⚠️⚠️ 染色必须真的能抓到跨租户行（D13 回归，真实策略引擎）。
     */
    @Test
    void shadowTaint_realPolicyEngine_approval_crossTenantRowIsViolation() {
        PolicyRule rule = new PolicyRule("r-appr", "approval", Action.RETRIEVE,
                ScopeRequirement.TENANT, ScopeEffect.TENANT_ONLY);
        PolicyEngine engine = new RuleBasedPolicyEngine(new InMemoryPolicyStore(List.of(rule)));
        ScopedDataAccess scoped = new ScopedDataAccess(new EngineGate(engine));
        InMemoryAuthzMismatchMetrics metrics = new InMemoryAuthzMismatchMetrics();
        JdbcApprovalQueryRepository repo = new JdbcApprovalQueryRepository(
                new NoConnectDataSource(), new NoOpAuditPort(), Clock.systemUTC());
        repo.bindAuthzShadow(new AuthzShadowBinding(scoped,
                new AuthzShadowReporter(metrics, false), SENTINEL));

        repo.shadowTaint("approval", TENANT, TENANT);
        assertEquals(0, metrics.taintViolations(), "本租户行不得判违例");

        repo.shadowTaint("approval", TENANT, "tenant-B");
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

    /** 审计端口空实现（影子路径不写审计）。 */
    private static final class NoOpAuditPort implements AuditPort {
        @Override
        public void recordRequired(String tenantId, String actorId, String action,
                                   String resourceType, String resourceId,
                                   Map<String, Object> detail) {
            // no-op
        }

        @Override
        public void recordAccess(String tenantId, String actorId, String action,
                                 String resourceType, String resourceId,
                                 Map<String, Object> detail) {
            // no-op
        }

        @Override
        public List<AuditView> findByResource(String tenantId, String resourceType,
                                              String resourceId) {
            return List.of();
        }
    }
}
