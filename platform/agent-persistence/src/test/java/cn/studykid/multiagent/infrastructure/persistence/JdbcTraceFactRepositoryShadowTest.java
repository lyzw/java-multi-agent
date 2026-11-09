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
 * {@link JdbcTraceFactRepository} 的 P1 影子态接线测试。
 *
 * <p>该仓是<b>采集热路径</b>的事实读取面，覆盖 5 张租户级表：
 * {@code run} / {@code agent_invocation} / {@code usage_record} / {@code tool_operation} /
 * {@code run_step}（resourceType 均已登记于 {@code AuthzShadowPolicyDefaults.RESOURCE_TYPES}）。
 * 另有 {@code cap_tool_def} 属<b>平台级目录</b>（无 {@code tenant_id} 列、无 RLS），
 * 刻意不接租户级影子。
 *
 * <p>⚠️ <b>本仓是「RLS 依赖度」最典型的样本</b>：五处查询没有显式租户谓词，
 * 隔离完全靠 RLS 兜底 ⇒ RLS 一退役即越权。故本测试重点钉死：
 * <b>「无租户谓词」必须被记入独立的 RLS 依赖计数，且不得污染 AUTHZ_MISMATCH</b>。
 */
class JdbcTraceFactRepositoryShadowTest {

    private static final String TENANT = "tenant-A";
    private static final String SENTINEL = "shared-tenant";

    private static final class Fixture {
        final JdbcTraceFactRepository repo;
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
            this.repo = new JdbcTraceFactRepository(new NoConnectDataSource());
            this.repo.bindAuthzShadow(new AuthzShadowBinding(scoped, reporter, SENTINEL));
        }
    }

    @Test
    void dormantDefault_noOpAndNoThrow() {
        JdbcTraceFactRepository repo = new JdbcTraceFactRepository(new NoConnectDataSource());
        InMemoryAuthzMismatchMetrics m = new InMemoryAuthzMismatchMetrics();
        assertDoesNotThrow(() -> repo.shadowCompare("run", TENANT, false));
        assertDoesNotThrow(() -> repo.shadowTaint("run", TENANT, TENANT));
        assertEquals(0, m.shadowSamples());
    }

    @Test
    void shadowCompare_withTenantPredicate_noRlsDependency() {
        Fixture f = new Fixture(new ScopeFilter.TenantOnly(TENANT), false);
        f.repo.shadowCompare("tool_operation", TENANT, true);
        assertEquals(0, f.metrics.rlsDependent());
        assertEquals(0, f.metrics.mismatches());
    }

    /**
     * ⚠️⚠️ 核心用例：<b>没有显式租户谓词的查询必须被记入 RLS 依赖清单</b>。
     *
     * <p>这正是 {@code readRun} / {@code readUsage} / {@code readSteps} /
     * {@code readInvocationTraceId} / {@code readRootAgent} 五处的形态 ——
     * 它们当前<b>完全靠 RLS 兜底</b>，退役 RLS 后即越权。
     */
    @Test
    void shadowCompare_withoutTenantPredicate_recordsRlsDependency() {
        Fixture f = new Fixture(new ScopeFilter.TenantOnly(TENANT), false);
        f.repo.shadowCompare("run", TENANT, false);
        assertEquals(1, f.metrics.rlsDependent(),
                "⚠️⚠️ 无显式租户谓词 ⇒ 靠 RLS 兜底 ⇒ 必须被记入退役清单");
    }

    /**
     * ⚠️ 出口门禁指标（{@code mismatches}）的语义<b>不能</b>被「迁移清单」污染：
     * 否则要么门禁永不达成、要么被迫调松阈值 —— 两种都是把防线拆掉。
     */
    @Test
    void shadowCompare_withoutTenantPredicate_doesNotPolluteMismatchCounter() {
        Fixture f = new Fixture(new ScopeFilter.TenantOnly(TENANT), false);
        f.repo.shadowCompare("run", TENANT, false);
        assertEquals(0, f.metrics.mismatches(),
                "RLS 依赖不是违例 ⇒ 不得计入 AUTHZ_MISMATCH");
        assertEquals(1, f.metrics.rlsDependent());
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
     * ⚠️⚠️ 染色必须真的能抓到跨租户行（D13 回归，真实策略引擎）。
     *
     * <p>主体固定为 {@code TENANT}，真实引擎按登记的 {@code run} 规则算出
     * {@code TenantOnly(TENANT)}。他租户的行必须被判违例 ——
     * 若恒为 0，说明染色把行租户当成了主体（同义反复），是假绿。
     */
    @Test
    void shadowTaint_realPolicyEngine_run_crossTenantRowIsViolation() {
        PolicyRule rule = new PolicyRule("r-run", "run", Action.RETRIEVE,
                ScopeRequirement.TENANT, ScopeEffect.TENANT_ONLY);
        PolicyEngine engine = new RuleBasedPolicyEngine(new InMemoryPolicyStore(List.of(rule)));
        ScopedDataAccess scoped = new ScopedDataAccess(new EngineGate(engine));
        InMemoryAuthzMismatchMetrics metrics = new InMemoryAuthzMismatchMetrics();
        JdbcTraceFactRepository repo = new JdbcTraceFactRepository(new NoConnectDataSource());
        repo.bindAuthzShadow(new AuthzShadowBinding(scoped,
                new AuthzShadowReporter(metrics, false), SENTINEL));

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
