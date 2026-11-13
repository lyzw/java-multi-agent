package cn.studykid.multiagent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.MapPropertySource;

import cn.studykid.multiagent.core.security.Action;
import cn.studykid.multiagent.core.security.Decision;
import cn.studykid.multiagent.core.security.ResourceRef;
import cn.studykid.multiagent.core.security.Scope;
import cn.studykid.multiagent.core.security.ScopeFilter;
import cn.studykid.multiagent.core.tenant.SubjectContext;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.security.AccessDeniedException;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowPolicyDefaults;
import cn.studykid.multiagent.infrastructure.security.InMemoryPolicyStore;
import cn.studykid.multiagent.infrastructure.security.PolicyEngine;
import cn.studykid.multiagent.infrastructure.security.PolicyRule;
import cn.studykid.multiagent.infrastructure.security.RuleBasedPolicyEngine;
import cn.studykid.multiagent.infrastructure.security.ScopeEffect;
import cn.studykid.multiagent.infrastructure.security.ScopeRequirement;
import cn.studykid.multiagent.infrastructure.security.SubjectContextResolver;

/**
 * {@code SubjectContext.scope} 推导点的<b>反证守卫</b>（WP-M1-4 · 12 文 §2.2）。
 *
 * <p>⚠️ <b>本类存在的唯一理由</b>：{@code scope} 是「平台全域放行」的唯一开关 ——
 * {@code ScopeFilter.Unrestricted}（等价退役前的 BYPASSRLS 连接）只能被
 * {@code isPlatformOps()} 主体拿到。RLS 退役后这是<b>仅存的跨租户放行闸门</b>，
 * 必须用反证钉死三件事：
 *
 * <ol>
 *   <li><b>伪造角色升不上去</b>：租户态请求在角色集里塞 {@code PLATFORM_ADMIN}
 *       也拿不到 PLATFORM 前置的资源（scope 与角色是两个独立维度）；</li>
 *   <li><b>生产内置规则集不产 Unrestricted</b>：用<b>真实装配</b>的
 *       {@link PolicyEngine}（{@link AuthzShadowPolicyDefaults} 内置规则，
 *       {@code ShadowPolicyCoverageTest} 钉住覆盖面）验证任何租户主体
 *       拿到的 filter 都不含 Unrestricted；</li>
 *   <li><b>环境级伪造主体无出口</b>：影子期 {@link SubjectContextResolver}
 *       fail-closed（抛 {@code NO_SUBJECT}），不存在「从线程环境猜一个
 *       平台主体」的路径。</li>
 * </ol>
 *
 * <p>⚠️ <b>产出 {@code Scope.PLATFORM} 的唯一路径是运维通道认证入口</b>：
 * 主代码中 SubjectContext 的构造点只有记录自身的工厂（{@code forTenant} 硬编码
 * {@code TENANT}），没有任何业务/接口代码能以任意 scope 构造主体 —— 这一条由
 * {@code ScopeBypassArchitectureTest} 的静态守卫钉住；将来运维通道入口接入时
 * 必须显式登记进该守卫的许可清单。本类测试中的
 * {@code SubjectContext.from(ctx, Scope.PLATFORM)} 即「运维通道认证入口产出」的替身，
 * 只用于证明该入口形态是<b>唯一</b>能拿到 Unrestricted 的形态。
 *
 * <p>⚠️ <b>不用 {@code @SpringBootTest}</b>（同 {@link AuthzShadowWiringTest}）：
 * 最小容器只装配 {@link AuthzShadowConfiguration}，不拉起数据源/MQ。
 */
class SubjectScopeDerivationGuardTest {

    private static final Map<String, Object> SHADOW_ON = Map.of(
            "agent.authz.shadow.enabled", "true",
            "agent.authz.shadow.block-on-violation", "false",
            "agent.authz.shadow.sentinel-tenant-id", "shared-sentinel",
            "agent.authz.shadow.sampling.rate", "0.01",
            "agent.authz.shadow.sampling.window-seconds", "300",
            "agent.authz.shadow.sampling.always-sample-resource-types", "",
            "agent.authz.decision-log.enabled", "false");

    private static final String TENANT = "tenant-A";

    // ── 引擎级反证（生产装配路径） ────────────────────────────────

    @Nested
    @DisplayName("生产装配的引擎：租户主体拿不到 Unrestricted")
    class ProductionEngine {

        @Test
        @DisplayName("⚠️⚠️ 伪造 PLATFORM_ADMIN 角色的租户主体，任意已登记资源类型 → filter 恒为 TenantOnly")
        void forgedAdminRole_neverYieldsUnrestricted() {
            try (AnnotationConfigApplicationContext ctx = context()) {
                PolicyEngine engine = ctx.getBean(PolicyEngine.class);
                SubjectContext forged = SubjectContext.from(
                        new TenantContext(TENANT, "p-1", Set.of("PLATFORM_ADMIN"), 1L),
                        Scope.TENANT);

                // 内置规则集的全部资源类型逐一遍历 —— 不是抽样，是全量。
                for (String resourceType : AuthzShadowPolicyDefaults.RESOURCE_TYPES) {
                    Decision d = engine.decide(forged, Action.RETRIEVE,
                            ResourceRef.collection(resourceType));
                    assertThat(d).as("资源类型 %s 必须被 Permit（影子期口径）", resourceType)
                            .isInstanceOf(Decision.Permit.class);
                    Decision.Permit permit = (Decision.Permit) d;
                    assertThat(permit.filter())
                            .as("⚠️⚠️ 资源类型 %s 的 filter 不得是 Unrestricted："
                                    + "租户主体（即使伪造 PLATFORM_ADMIN 角色）"
                                    + "绝不能拿到平台全域放行", resourceType)
                            .isInstanceOf(ScopeFilter.TenantOnly.class);
                }
            }
        }

        @Test
        @DisplayName("⚠️⚠️ 租户主体请求 UNRESTRICTED 效果规则 → Deny（角色升不上去）")
        void forgedAdminRole_deniedOnUnrestrictedRule() {
            try (AnnotationConfigApplicationContext ctx = context()) {
                // 生产装配确认在容器里（供静态审查），本用例用临时规则集聚焦 scope 维度。
                assertThat(ctx.getBean(PolicyEngine.class)).isNotNull();

                SubjectContext forged = SubjectContext.from(
                        new TenantContext(TENANT, "p-1", Set.of("PLATFORM_ADMIN"), 1L),
                        Scope.TENANT);

                // 临时规则集只有一条「运维专属写」：证明被拒的是 scope 维度，
                // 不是 NO_RULE（后者与伪造无关）。
                PolicyRule opsWrite = new PolicyRule("ops-write", "run", Action.WRITE,
                        ScopeRequirement.PLATFORM, ScopeEffect.UNRESTRICTED);
                PolicyEngine opsEngine = new RuleBasedPolicyEngine(
                        new InMemoryPolicyStore(List.of(opsWrite)));

                Decision d = opsEngine.decide(forged, Action.WRITE, ResourceRef.collection("run"));
                assertThat(d).isInstanceOf(Decision.Deny.class);
                assertThat(((Decision.Deny) d).reasonCode())
                        .as("伪造角色的租户主体必须在前置校验（REQUIREMENT_NOT_MET）被拒，"
                                + "而不是 NO_RULE 等与 scope 无关的理由")
                        .isEqualTo("REQUIREMENT_NOT_MET");
            }
        }

        @Test
        @DisplayName("✅ 唯一放行形态：PLATFORM scope + PLATFORM_ADMIN 角色 → Unrestricted（运维通道认证入口产出）")
        void platformOpsEntryIsSoleUnrestrictedPath() {
            // 「运维通道认证入口」的产出替身：入口在确认请求走平台运维通道后
            // 显式以 Scope.PLATFORM 构造主体（见类注释；静态守卫钉住唯一构造点）。
            SubjectContext opsEntry = SubjectContext.from(
                    new TenantContext("platform", "ops-admin", Set.of("PLATFORM_ADMIN"), 1L),
                    Scope.PLATFORM);
            // ⚠️ 正面锚点用「运维专属规则」而非内置规则集：内置规则全部 TENANT 前置
            //    （影子期口径），运维主体在租户资源上同样被拒（REQUIREMENT_NOT_MET: TENANT）
            //    —— 平台全域读取走带外运维通道，不经这套引擎。这本身也是反证结论之一。
            PolicyRule opsWrite = new PolicyRule("ops-write", "run", Action.WRITE,
                    ScopeRequirement.PLATFORM, ScopeEffect.UNRESTRICTED);
            PolicyEngine opsEngine = new RuleBasedPolicyEngine(
                    new InMemoryPolicyStore(List.of(opsWrite)));

            Decision d = opsEngine.decide(opsEntry, Action.WRITE, ResourceRef.collection("run"));
            assertThat(d).isInstanceOf(Decision.Permit.class);
            assertThat(((Decision.Permit) d).filter())
                    .as("运维通道主体应拿到 Unrestricted —— 这是全系统唯一的放行形态")
                    .isInstanceOf(ScopeFilter.Unrestricted.class);
        }

        @Test
        @DisplayName("⚠️ 内置规则集全为 TENANT 前置：运维主体在租户资源上也被拒（平台全域走带外通道）")
        void builtinRulesDenyEvenPlatformOpsSubject() {
            try (AnnotationConfigApplicationContext ctx = context()) {
                PolicyEngine engine = ctx.getBean(PolicyEngine.class);
                SubjectContext opsEntry = SubjectContext.from(
                        new TenantContext("platform", "ops-admin", Set.of("PLATFORM_ADMIN"), 1L),
                        Scope.PLATFORM);
                Decision d = engine.decide(opsEntry, Action.RETRIEVE, ResourceRef.collection("run"));
                assertThat(d).isInstanceOf(Decision.Deny.class);
                assertThat(((Decision.Deny) d).reasonCode()).isEqualTo("REQUIREMENT_NOT_MET");
            }
        }
    }

    // ── 环境级伪造主体无出口 ──────────────────────────────────────

    @Nested
    @DisplayName("影子期主体解析器 fail-closed")
    class SubjectResolver {

        @Test
        @DisplayName("⚠️⚠️ 环境级主体解析必须抛 NO_SUBJECT（不存在「猜一个平台主体」的路径）")
        void environmentResolverIsFailClosed() {
            try (AnnotationConfigApplicationContext ctx = context()) {
                SubjectContextResolver resolver = ctx.getBean(SubjectContextResolver.class);
                assertThatThrownBy(resolver::resolve)
                        .isInstanceOf(AccessDeniedException.class)
                        .extracting(e -> ((AccessDeniedException) e).reasonCode())
                        .isEqualTo("NO_SUBJECT");
            }
        }
    }

    // ── 容器装配（与 AuthzShadowWiringTest 同形态） ───────────────

    private static AnnotationConfigApplicationContext context() {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", SHADOW_ON));
        ctx.register(Placeholders.class);
        ctx.registerBean(DataSource.class, NoConnectDataSource::new);
        ctx.registerBean(io.micrometer.core.instrument.simple.SimpleMeterRegistry.class,
                io.micrometer.core.instrument.simple.SimpleMeterRegistry::new);
        ctx.register(AuthzShadowConfiguration.class);
        ctx.refresh();
        return ctx;
    }

    /** 纯 {@code @Value} 解析所需（最小容器默认不注册占位符解析器）。 */
    @org.springframework.context.annotation.Configuration
    static class Placeholders {
        @org.springframework.context.annotation.Bean
        static PropertySourcesPlaceholderConfigurer propertyPlaceholders() {
            return new PropertySourcesPlaceholderConfigurer();
        }
    }

    /** 永不建连的 DataSource 桩（本测试不触碰数据库）。 */
    static final class NoConnectDataSource implements DataSource {
        @Override
        public Connection getConnection() throws SQLException {
            throw new UnsupportedOperationException("反证测试不建连");
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            throw new UnsupportedOperationException("反证测试不建连");
        }

        @Override
        public java.io.PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(java.io.PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public java.util.logging.Logger getParentLogger() {
            return java.util.logging.Logger.getGlobal();
        }

        @Override
        public <T> T unwrap(Class<T> iface) {
            return null;
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
