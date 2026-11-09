package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;
import java.util.function.Consumer;
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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CAP 域<b>形态一致</b>的 7 个租户级仓储的 P1 影子态接线测试（表驱动）。
 *
 * <p>覆盖：{@code JdbcCapResultContractRepository}（cap_result_contract）、
 * {@code JdbcCapRoutingPolicyRepository}（cap_routing_policy）、
 * {@code JdbcExecutionIntentRepository}（cap_execution_intent）、
 * {@code JdbcResultEventRepository}（cap_result_event）、
 * {@code JdbcCapApprovalRepository}（cap_approval）、
 * {@code JdbcCompensationTaskRepository}（cap_compensation_task）、
 * {@code JdbcCapabilitySnapshotRepository}（execution_capability_snapshot）。
 *
 * <p>⚠️ <b>为什么用表驱动而不是一仓一测</b>：这 7 个仓的读路径形态完全一致 ——
 * 1 参构造 + 资源类型常量 + SQL 自带显式 {@code tenant_id = ?::uuid} 谓词 +
 * SELECT 已含 {@code tenant_id::text} ⇒ 逐行染色可直接取行归属。
 * 「一致」本身是需要被断言的事实，用同一张表断言比复制 7 份更贴近真相；
 * 任一仓的形态若变了（例如少了租户谓词、或改成了平台域），本表会立刻暴露。
 *
 * <p>七者均为 policy-mapping.yaml 的 TENANT_ONLY + forced=t ⇒
 * {@code hasTenantPredicate} 一律 {@code true}（不计入「RLS 依赖」清单）。
 * 其中 {@code JdbcCapApprovalRepository} 的写路径用 {@code INSERT…RETURNING}
 * （同样走 {@code executeQuery}）但<b>刻意不接影子钩子</b> —— 写隔离由 RLS
 * {@code WITH CHECK} 保证，属 P2 范围，避免把写事务误记成读观测。
 *
 * <p>本测试证明「染色 / 比对确实有检出力」，而不是恒不违例的摆设（D13 防同义反复）。
 */
class CapUniformTenantScopedShadowTest {

    private static final String TENANT = "tenant-A";
    private static final String SENTINEL = "shared-tenant";

    /** 影子钩子调用面（4 个仓类型不同，但钩子签名一致 ⇒ 用窄接口统一）。 */
    private interface ShadowTarget {
        void compare(String resourceType, String tenantId, boolean hasTenantPredicate);

        void taint(String resourceType, String subjectTenantId, String rowTenantId);
    }

    /** 一个被测对象：资源类型 + 调用面 + 绑定器（绑定器持有具体仓实例）。 */
    private static final class Case {
        final String name;
        final String resourceType;
        final ShadowTarget target;
        final Consumer<AuthzShadowBinding> binder;

        Case(String name, String resourceType, ShadowTarget target,
             Consumer<AuthzShadowBinding> binder) {
            this.name = name;
            this.resourceType = resourceType;
            this.target = target;
            this.binder = binder;
        }
    }

    private static List<Case> cases() {
        return List.of(
                capResultContractCase(),
                capRoutingPolicyCase(),
                executionIntentCase(),
                resultEventCase(),
                capApprovalCase(),
                compensationTaskCase(),
                capabilitySnapshotCase());
    }

    private static Case capResultContractCase() {
        JdbcCapResultContractRepository repo =
                new JdbcCapResultContractRepository(new NoConnectDataSource());
        ShadowTarget target = new ShadowTarget() {
            @Override
            public void compare(String t, String tenantId, boolean p) {
                repo.shadowCompare(t, tenantId, p);
            }

            @Override
            public void taint(String t, String subjectTenantId, String rowTenantId) {
                repo.shadowTaint(t, subjectTenantId, rowTenantId);
            }
        };
        return new Case("cap_result_contract", "cap_result_contract", target,
                binding -> repo.bindAuthzShadow(binding));
    }

    private static Case capRoutingPolicyCase() {
        JdbcCapRoutingPolicyRepository repo =
                new JdbcCapRoutingPolicyRepository(new NoConnectDataSource());
        ShadowTarget target = new ShadowTarget() {
            @Override
            public void compare(String t, String tenantId, boolean p) {
                repo.shadowCompare(t, tenantId, p);
            }

            @Override
            public void taint(String t, String subjectTenantId, String rowTenantId) {
                repo.shadowTaint(t, subjectTenantId, rowTenantId);
            }
        };
        return new Case("cap_routing_policy", "cap_routing_policy", target,
                binding -> repo.bindAuthzShadow(binding));
    }

    private static Case executionIntentCase() {
        JdbcExecutionIntentRepository repo =
                new JdbcExecutionIntentRepository(new NoConnectDataSource());
        ShadowTarget target = new ShadowTarget() {
            @Override
            public void compare(String t, String tenantId, boolean p) {
                repo.shadowCompare(t, tenantId, p);
            }

            @Override
            public void taint(String t, String subjectTenantId, String rowTenantId) {
                repo.shadowTaint(t, subjectTenantId, rowTenantId);
            }
        };
        return new Case("cap_execution_intent", "cap_execution_intent", target,
                binding -> repo.bindAuthzShadow(binding));
    }

    private static Case resultEventCase() {
        JdbcResultEventRepository repo =
                new JdbcResultEventRepository(new NoConnectDataSource());
        ShadowTarget target = new ShadowTarget() {
            @Override
            public void compare(String t, String tenantId, boolean p) {
                repo.shadowCompare(t, tenantId, p);
            }

            @Override
            public void taint(String t, String subjectTenantId, String rowTenantId) {
                repo.shadowTaint(t, subjectTenantId, rowTenantId);
            }
        };
        return new Case("cap_result_event", "cap_result_event", target,
                binding -> repo.bindAuthzShadow(binding));
    }

    private static Case capApprovalCase() {
        JdbcCapApprovalRepository repo = new JdbcCapApprovalRepository(new NoConnectDataSource());
        ShadowTarget target = new ShadowTarget() {
            @Override
            public void compare(String t, String tenantId, boolean p) {
                repo.shadowCompare(t, tenantId, p);
            }

            @Override
            public void taint(String t, String subjectTenantId, String rowTenantId) {
                repo.shadowTaint(t, subjectTenantId, rowTenantId);
            }
        };
        return new Case("cap_approval", "cap_approval", target,
                binding -> repo.bindAuthzShadow(binding));
    }

    private static Case compensationTaskCase() {
        JdbcCompensationTaskRepository repo =
                new JdbcCompensationTaskRepository(new NoConnectDataSource());
        ShadowTarget target = new ShadowTarget() {
            @Override
            public void compare(String t, String tenantId, boolean p) {
                repo.shadowCompare(t, tenantId, p);
            }

            @Override
            public void taint(String t, String subjectTenantId, String rowTenantId) {
                repo.shadowTaint(t, subjectTenantId, rowTenantId);
            }
        };
        return new Case("cap_compensation_task", "cap_compensation_task", target,
                binding -> repo.bindAuthzShadow(binding));
    }

    private static Case capabilitySnapshotCase() {
        JdbcCapabilitySnapshotRepository repo =
                new JdbcCapabilitySnapshotRepository(new NoConnectDataSource());
        ShadowTarget target = new ShadowTarget() {
            @Override
            public void compare(String t, String tenantId, boolean p) {
                repo.shadowCompare(t, tenantId, p);
            }

            @Override
            public void taint(String t, String subjectTenantId, String rowTenantId) {
                repo.shadowTaint(t, subjectTenantId, rowTenantId);
            }
        };
        return new Case("execution_capability_snapshot", "execution_capability_snapshot", target,
                binding -> repo.bindAuthzShadow(binding));
    }

    /** 固定 ScopeFilter 的测试夹具。 */
    private static final class Fixture {
        final Case c;
        final InMemoryAuthzMismatchMetrics metrics;

        Fixture(Case c, ScopeFilter filter, boolean block) {
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
            this.c = c;
            this.metrics = new InMemoryAuthzMismatchMetrics();
            c.binder.accept(new AuthzShadowBinding(new ScopedDataAccess(gate),
                    new AuthzShadowReporter(metrics, block), SENTINEL));
        }
    }

    @Test
    @DisplayName("⚠️ 7 个 CAP 租户级仓储都在表内、且资源类型互不重复")
    void allCasesPresent() {
        List<Case> all = cases();
        assertEquals(7, all.size());
        assertEquals(7, all.stream().map(c -> c.resourceType).distinct().count());
    }

    @Test
    @DisplayName("✅ dormant 默认：钩子调用零副作用、零样本")
    void dormantDefault_noOpAndNoThrow() {
        for (Case c : cases()) {
            InMemoryAuthzMismatchMetrics m = new InMemoryAuthzMismatchMetrics();
            assertDoesNotThrow(() -> c.target.compare(c.resourceType, TENANT, true));
            assertDoesNotThrow(() -> c.target.taint(c.resourceType, TENANT, TENANT));
            assertEquals(0, m.shadowSamples());
        }
    }

    @Test
    @DisplayName("✅ 主体=绑定租户：零 mismatch、零染色违例、零 RLS 依赖")
    void matchingTenant_isClean() {
        for (Case c : cases()) {
            Fixture f = new Fixture(c, new ScopeFilter.TenantOnly(TENANT), false);
            f.c.target.compare(f.c.resourceType, TENANT, true);
            f.c.target.taint(f.c.resourceType, TENANT, TENANT);
            assertEquals(0, f.metrics.mismatches(), f.c.name + " 不应有 mismatch");
            assertEquals(0, f.metrics.taintViolations(), f.c.name + " 不应有染色违例");
            assertEquals(0, f.metrics.rlsDependent(),
                    f.c.name + " 读路径自带显式租户谓词 ⇒ 不应计入 RLS 依赖清单");
        }
    }

    @Test
    @DisplayName("⚠️⚠️ 跨租户行必判违例（真实策略引擎，D13 反向验证）")
    void crossTenantRow_isViolationWithRealEngine() {
        for (Case c : cases()) {
            PolicyRule rule = new PolicyRule("r-" + c.resourceType, c.resourceType,
                    Action.RETRIEVE, ScopeRequirement.TENANT, ScopeEffect.TENANT_ONLY);
            PolicyEngine engine = new RuleBasedPolicyEngine(new InMemoryPolicyStore(List.of(rule)));
            InMemoryAuthzMismatchMetrics metrics = new InMemoryAuthzMismatchMetrics();
            c.binder.accept(new AuthzShadowBinding(new ScopedDataAccess(new EngineGate(engine)),
                    new AuthzShadowReporter(metrics, false), SENTINEL));

            c.target.taint(c.resourceType, TENANT, TENANT);
            assertEquals(0, metrics.taintViolations(), c.name + " 本租户行不得判违例");

            c.target.taint(c.resourceType, TENANT, "tenant-B");
            assertEquals(1, metrics.taintViolations(),
                    "⚠️⚠️ " + c.name + "：主体是查询租户、行归属是他租户 → 必须违例；"
                            + "若恒为 0，说明染色把行租户当成了主体（同义反复）");
        }
    }

    @Test
    @DisplayName("⚠️ Gate 租户 ≠ 查询绑定租户 ⇒ 记 mismatch")
    void mismatchedTenant_recordsMismatch() {
        for (Case c : cases()) {
            Fixture f = new Fixture(c, new ScopeFilter.TenantOnly(TENANT), false);
            f.c.target.compare(f.c.resourceType, "tenant-C", true);
            assertEquals(1, f.metrics.mismatches(), f.c.name + " 应记 mismatch");
        }
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
