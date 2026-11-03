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
import cn.studykid.multiagent.infrastructure.security.AccessGate;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowReporter;
import cn.studykid.multiagent.infrastructure.security.InMemoryAuthzMismatchMetrics;
import cn.studykid.multiagent.infrastructure.security.ScopedDataAccess;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class JdbcBudgetRepositoryShadowTest {

    private static final String TENANT = "tenant-A";
    private static final String SENTINEL = "shared-tenant";

    private static final class Fixture {
        final JdbcBudgetRepository repo;
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
            this.repo = new JdbcBudgetRepository(
                    new NoConnectDataSource(), scoped, reporter, true, SENTINEL);
        }
    }

    @Test
    void dormantDefault_noOpAndNoThrow() {
        JdbcBudgetRepository repo = new JdbcBudgetRepository(new NoConnectDataSource());
        InMemoryAuthzMismatchMetrics m = new InMemoryAuthzMismatchMetrics();
        AuthzShadowReporter reporter = new AuthzShadowReporter(m, false);
        assertDoesNotThrow(() -> repo.shadowCompare("budget_reservation", TENANT, true));
        assertDoesNotThrow(() -> repo.shadowTaint("budget_reservation", TENANT, TENANT));
        assertEquals(0, m.shadowSamples());
    }

    @Test
    void shadowCompare_matchingTenant_noMismatch() {
        Fixture f = new Fixture(new ScopeFilter.TenantOnly(TENANT), false);
        f.repo.shadowCompare("budget_reservation", TENANT, true);
        assertEquals(0, f.metrics.mismatches());
    }

    @Test
    void shadowCompare_mismatchedTenant_recordsMismatch() {
        Fixture f = new Fixture(new ScopeFilter.TenantOnly(TENANT), false);
        f.repo.shadowCompare("budget_reservation", "tenant-C", true);
        assertEquals(1, f.metrics.mismatches());
    }

    @Test
    void shadowTaint_inScope_noViolation() {
        Fixture f = new Fixture(new ScopeFilter.TenantOnly(TENANT), false);
        f.repo.shadowTaint("budget_reservation", TENANT, TENANT);
        assertEquals(0, f.metrics.taintViolations());
    }

    @Test
    void shadowTaint_crossTenant_recordsViolation() {
        Fixture f = new Fixture(new ScopeFilter.TenantOnly(TENANT), false);
        f.repo.shadowTaint("budget_reservation", TENANT, "tenant-B");
        assertEquals(1, f.metrics.taintViolations());
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
