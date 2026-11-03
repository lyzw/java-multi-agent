package cn.studykid.multiagent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.MapPropertySource;

import cn.studykid.multiagent.infrastructure.security.AuthzDecisionRecorder;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowBinding;
import cn.studykid.multiagent.infrastructure.security.JdbcAuthzDecisionLog;
import cn.studykid.multiagent.infrastructure.security.NoOpAuthzDecisionRecorder;
import cn.studykid.multiagent.infrastructure.persistence.JdbcAgentMetricRepository;
import cn.studykid.multiagent.infrastructure.persistence.JdbcAgentQueryRepository;
import cn.studykid.multiagent.infrastructure.persistence.JdbcBudgetRepository;
import cn.studykid.multiagent.infrastructure.persistence.JdbcResultQueryRepository;
import cn.studykid.multiagent.infrastructure.persistence.cap.JdbcDelegationBudgetRepository;

/**
 * 权限影子态<b>接缝是否插电</b>的装配测试（P1 / 13 文 §5.2）。
 *
 * <p>⚠️ <b>本类存在的唯一理由</b>：D2–D7 的全部能力（策略引擎、染色、指标、采样）
 * 都已实现且单测全绿，但「容器把它们交给仓库」这一步此前<b>不存在</b>。
 * 缺这一步时，影子期会给出一个漂亮的读数：
 * <blockquote>AUTHZ_MISMATCH = 0（因为一次都没跑过）</blockquote>
 * 这是本项目已记录三次的同一形态（{@code RetentionConfig} 注释里写明过两次）。
 *
 * <p>因此本类断言两件事，且都是<b>可证伪</b>的：
 * <ol>
 *   <li>开启时：容器里存在 {@code AuthzShadowBinding}，且 <b>5 个仓库的私有字段真的被填上</b>
 *       （{@code shadowEnabled=true} / {@code scoped!=null} / 哨兵值透传）；</li>
 *   <li>关闭时：该 Bean <b>不存在</b>，仓库保持 {@code shadowEnabled=false} —— 零行为变更。</li>
 * </ol>
 *
 * <p>⚠️ <b>不用 {@code @SpringBootTest}</b>：那会拉起整条启动链（数据源、MQ、启动自检），
 * 无库环境下直接报错而不是跳过。本类只想验证「装配这一层」，故用最小容器，
 * 只注册配置类 + 一个永不建连的 DataSource 桩。
 */
class AuthzShadowWiringTest {

    private static final Map<String, Object> SHADOW_ON = Map.<String, Object>of(
            "agent.authz.shadow.enabled", "true",
            "agent.authz.shadow.block-on-violation", "false",
            "agent.authz.shadow.sentinel-tenant-id", "shared-sentinel",
            "agent.authz.shadow.sampling.rate", "0.01",
            "agent.authz.shadow.sampling.window-seconds", "300",
            "agent.authz.shadow.sampling.always-sample-resource-types", "run,tool_operation",
            "agent.authz.decision-log.enabled", "false");

    /** 断言目标：5 个已接影子钩子的仓库（不论其注册方式）。 */
    private static final List<Class<?>> WIRED_REPOS = List.of(
            JdbcAgentQueryRepository.class,
            JdbcResultQueryRepository.class,
            JdbcBudgetRepository.class,
            JdbcAgentMetricRepository.class,
            JdbcDelegationBudgetRepository.class);

    /**
     * 组件扫描形态的仓库（{@code @Repository}）。
     *
     * <p>⚠️ 另两个（{@code JdbcBudgetRepository} / {@code JdbcDelegationBudgetRepository}）
     * 在本项目里是<b>显式 {@code @Bean}</b> 注册（P4-D07 惯例，多构造器 + 组件扫描会踩
     * {@code No default constructor found}），故由 {@link BeanRegisteredRepos} 提供 ——
     * 这与生产装配路径一致，测试才有意义。
     */
    private static final List<Class<?>> COMPONENT_REPOS = List.of(
            JdbcAgentQueryRepository.class,
            JdbcResultQueryRepository.class,
            JdbcAgentMetricRepository.class);

    @Test
    @DisplayName("⚠️ 开启时：5 个仓库的影态字段真的被 Spring 填上（否则影子期从未运行却报 0）")
    void enabled_wiresAllRepositories() throws Exception {
        try (AnnotationConfigApplicationContext ctx = context(SHADOW_ON)) {
            assertThat(ctx.getBean(AuthzShadowBinding.class)).isNotNull();

            for (Class<?> repoType : WIRED_REPOS) {
                Object repo = ctx.getBean(repoType);
                assertThat(readBoolean(repo, "shadowEnabled"))
                        .as("%s 未接线：影子比对与染色一次都不会跑", repoType.getSimpleName())
                        .isTrue();
                assertThat(readObject(repo, "scoped"))
                        .as("%s 的 ScopedDataAccess 未注入：钩子会在第一道判空处直接返回", repoType.getSimpleName())
                        .isNotNull();
                assertThat((String) readObject(repo, "sentinelTenantId"))
                        .as("哨兵租户未透传：TenantWithShared 表会把共享行误判为越权")
                        .isEqualTo("shared-sentinel");
            }
        }
    }

    @Test
    @DisplayName("关闭时：Bean 不存在，仓库保持未接线（零行为变更）")
    void disabled_leavesRepositoriesDormant() throws Exception {
        Map<String, Object> off = Map.of("agent.authz.shadow.enabled", "false");
        try (AnnotationConfigApplicationContext ctx = context(off)) {
            assertThat(ctx.getBeansOfType(AuthzShadowBinding.class)).isEmpty();

            for (Class<?> repoType : WIRED_REPOS) {
                Object repo = ctx.getBean(repoType);
                assertThat(readBoolean(repo, "shadowEnabled"))
                        .as("%s 在关闭态不应接线", repoType.getSimpleName())
                        .isFalse();
                assertThat(readObject(repo, "scoped")).isNull();
            }
        }
    }

    @Test
    @DisplayName("决策日志关闭（P1 默认）：记录器为 NoOp，判定不落库")
    void decisionLogOff_isNoOp() {
        try (AnnotationConfigApplicationContext ctx = context(SHADOW_ON)) {
            assertThat(ctx.getBean(AuthzDecisionRecorder.class))
                    .isInstanceOf(NoOpAuthzDecisionRecorder.class);
        }
    }

    @Test
    @DisplayName("⚠️ 决策日志开启：记录器切到 Jdbc 实现，且 Gate 真的持有它（否则「记录器在、没人调」= 零记录）")
    void decisionLogOn_usesJdbcRecorder() throws Exception {
        Map<String, Object> on = new HashMap<>(SHADOW_ON);
        on.put("agent.authz.decision-log.enabled", "true");
        try (AnnotationConfigApplicationContext ctx = context(on)) {
            AuthzDecisionRecorder recorder = ctx.getBean(AuthzDecisionRecorder.class);
            assertThat(recorder).isInstanceOf(JdbcAuthzDecisionLog.class);
            // 装配断链时「记录器存在」毫无意义：必须确认 Gate 拿的就是这一个
            Object gate = ctx.getBean(cn.studykid.multiagent.infrastructure.security.AccessGate.class);
            assertThat(readObject(gate, "recorder"))
                    .as("Gate 未持有 Jdbc 记录器：判定不会写入 platform.authz_decision_log")
                    .isSameAs(recorder);
        }
    }

    // ── 容器装配 ──────────────────────────────────────────────────

    private static AnnotationConfigApplicationContext context(Map<String, Object> props) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", props));
        ctx.register(Placeholders.class, BeanRegisteredRepos.class);
        ctx.registerBean(DataSource.class, NoConnectDataSource::new);
        ctx.register(AuthzShadowConfiguration.class);
        ctx.register(COMPONENT_REPOS.toArray(new Class<?>[0]));
        ctx.refresh();
        return ctx;
    }

    /** 与生产一致的 {@code @Bean} 注册路径（两个多构造器仓库）。 */
    @Configuration
    static class BeanRegisteredRepos {
        @Bean
        JdbcBudgetRepository jdbcBudgetRepository(DataSource dataSource) {
            return new JdbcBudgetRepository(dataSource);
        }

        @Bean
        JdbcDelegationBudgetRepository jdbcDelegationBudgetRepository(DataSource dataSource) {
            return new JdbcDelegationBudgetRepository(dataSource);
        }
    }

    /** 纯 {@code @Value} 解析所需（最小容器默认不注册占位符解析器）。 */
    @Configuration
    static class Placeholders {
        @Bean
        static PropertySourcesPlaceholderConfigurer propertyPlaceholders() {
            return new PropertySourcesPlaceholderConfigurer();
        }
    }

    // ── 反射读取（断言的是「Spring 是否写了这些字段」） ─────────────

    private static boolean readBoolean(Object target, String field) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        return f.getBoolean(target);
    }

    private static Object readObject(Object target, String field) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        return f.get(target);
    }

    /** 永不建连的 DataSource 桩（装配测试不触碰数据库）。 */
    static final class NoConnectDataSource implements DataSource {
        @Override
        public Connection getConnection() throws SQLException {
            throw new UnsupportedOperationException("装配测试不建连");
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            throw new UnsupportedOperationException("装配测试不建连");
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
