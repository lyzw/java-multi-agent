package cn.studykid.multiagent.infrastructure.persistence;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

import javax.sql.DataSource;

import cn.studykid.multiagent.core.security.Action;
import cn.studykid.multiagent.core.security.ResourceRef;
import cn.studykid.multiagent.core.security.ScopeFilter;
import cn.studykid.multiagent.core.tenant.SubjectContext;
import cn.studykid.multiagent.infrastructure.security.AccessDeniedException;
import cn.studykid.multiagent.infrastructure.security.AccessGate;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowReporter;
import cn.studykid.multiagent.infrastructure.security.InMemoryAuthzMismatchMetrics;
import cn.studykid.multiagent.infrastructure.security.ScopedDataAccess;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P1 影子态接线验证（无需数据库）：直接调用包级 {@code shadowEnter}/{@code checkTaint}，
 * 验证「影子比对 + 染色」在启用时只记录不阻断、在关闭（默认单参构造）时完全无副作用。
 */
class JdbcAgentMetricRepositoryShadowTest {

    private static final String TENANT = "tenant-A";
    private static final String SENTINEL = "shared-tenant";

    private static final class Fixture {
        final JdbcAgentMetricRepository repo;
        final InMemoryAuthzMismatchMetrics metrics;

        Fixture(ScopeFilter filter, boolean block) {
            AccessGate gate = new AccessGate() {
                @Override
                public ScopeFilter authorize(Action action, ResourceRef resource) {
                    return filter;
                }

                @Override
                public ScopeFilter authorizeCollection(Action action, String resourceType) {
                    return filter;
                }

                @Override
                public ScopeFilter authorizeCollection(SubjectContext subject, Action action,
                                                       String resourceType) {
                    return filter;
                }
            };
            ScopedDataAccess scoped = new ScopedDataAccess(gate);
            this.metrics = new InMemoryAuthzMismatchMetrics();
            AuthzShadowReporter reporter = new AuthzShadowReporter(metrics, block);
            this.repo = new JdbcAgentMetricRepository(
                    new ThrowingDataSource(), scoped, reporter, true, SENTINEL);
        }
    }

    @Test
    void taint_inScopeRow_noViolation() {
        Fixture f = new Fixture(new ScopeFilter.TenantOnly(TENANT), false);
        f.repo.checkTaint("platform.agent_trace_metric", TENANT, TENANT, false);
        assertEquals(0, f.metrics.taintViolations());
    }

    @Test
    void taint_crossTenant_recordsViolation() {
        Fixture f = new Fixture(new ScopeFilter.TenantOnly(TENANT), false);
        f.repo.checkTaint("platform.agent_trace_metric", TENANT, "tenant-B", false);
        assertEquals(1, f.metrics.taintViolations());
    }

    @Test
    void taint_noSubject_isSkipped() {
        // 无租户主体的查询（如 findByTraceId）不做染色：
        // 用行租户当主体是同义反复，用空主体则 Gate 只能算 Unrestricted，都无法判越权。
        Fixture f = new Fixture(new ScopeFilter.TenantOnly(TENANT), false);
        f.repo.checkTaint("platform.agent_trace_metric", null, "tenant-B", false);
        assertEquals(0, f.metrics.taintViolations());
        assertEquals(0, f.metrics.shadowSamples());
    }

    @Test
    void shadowEnter_matchingTenant_noMismatch() {
        Fixture f = new Fixture(new ScopeFilter.TenantOnly(TENANT), false);
        f.repo.shadowEnter("platform.agent_trace_metric", TENANT, true);
        assertEquals(0, f.metrics.mismatches());
    }

    @Test
    void shadowEnter_mismatchedTenant_recordsMismatch() {
        Fixture f = new Fixture(new ScopeFilter.TenantOnly(TENANT), false);
        f.repo.shadowEnter("platform.agent_trace_metric", "tenant-C", true);
        assertEquals(1, f.metrics.mismatches());
    }

    @Test
    void shadowEnter_gateNone_withPredicate_recordsMismatch() {
        Fixture f = new Fixture(ScopeFilter.NONE, false);
        f.repo.shadowEnter("platform.agent_trace_metric", TENANT, true);
        assertEquals(1, f.metrics.mismatches());
    }

    @Test
    void shadowEnter_platformWide_isSkipped() {
        // 平台全域聚合（无租户主体）不做比对：拿租户主体去比 Unrestricted 只会得到恒定假 mismatch。
        // 该路径的可观测性属 D12（非 Repository 面）/P3 平台审计，不在影子期冒充已验证。
        Fixture f = new Fixture(new ScopeFilter.TenantOnly(TENANT), false);
        f.repo.shadowEnter("platform.agent_trace_metric", null, false);
        assertEquals(0, f.metrics.shadowSamples());
        assertEquals(0, f.metrics.mismatches());
    }

    @Test
    void dormantDefault_noOpAndNoThrow() {
        // 单参构造 ⇒ shadowEnabled=false，既不应抛也不应记录
        JdbcAgentMetricRepository repo = new JdbcAgentMetricRepository(new ThrowingDataSource());
        InMemoryAuthzMismatchMetrics m = new InMemoryAuthzMismatchMetrics();
        AuthzShadowReporter reporter = new AuthzShadowReporter(m, false);
        assertDoesNotThrow(() -> repo.shadowEnter("t", "x", true));
        assertDoesNotThrow(() -> repo.checkTaint("t", "x", "x", false));
        assertEquals(0, m.shadowSamples());
    }

    /** 永不返回连接的 DataSource 桩（影子/染色路径不触碰连接，故安全）。 */
    private static final class ThrowingDataSource implements DataSource {
        @Override
        public Connection getConnection() throws SQLException {
            throw new UnsupportedOperationException("影子测试不触碰连接");
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            throw new UnsupportedOperationException("影子测试不触碰连接");
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return 0;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return Logger.getGlobal();
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("Not a wrapper");
        }
    }
}
