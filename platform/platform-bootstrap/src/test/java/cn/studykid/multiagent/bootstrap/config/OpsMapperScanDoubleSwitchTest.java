package cn.studykid.multiagent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import cn.studykid.multiagent.infrastructure.persistence.cap.PlatformOpsDataSourceProvider;

/**
 * 回归守卫：<b>ops Mapper 的扫描必须与 {@code opsSqlSessionFactory} 同生共死</b>。
 *
 * <p>⚠️ <b>本类钉死一次真实启动失败</b>：当「平台运维开关开、MyBatis 迁移开关关」
 * （例如只配了 {@code AGENT_PLATFORM_OPS_ENABLED=true}）时，
 * {@code OpsMapperScanConfiguration} 的 {@code @MapperScan} 曾因只检查运维单开关，
 * 借 {@code @Import(MapperScannerRegistrar)} 在配置类解析早期注册了 ops Mapper，
 * 而外层工厂 {@code opsSqlSessionFactory} 因 MyBatis 关闭并不存在，最终启动失败：
 * <blockquote>
 * Error creating bean with name 'dictionaryMapper': Cannot resolve reference to bean
 * 'opsSqlSessionFactory' while setting bean property 'sqlSessionFactory'
 * </blockquote>
 * 修复后该类改为双开关联合条件，与 {@code MybatisModelCatalogRepository} 等 ops 仓储同口径。
 *
 * <p>⚠️ 本类不依赖数据库/Broker：只加载 {@link MybatisRlsConfiguration} 配置片，
 * 用测试替身满足依赖，故可在 CI 无库环境运行。
 */
class OpsMapperScanDoubleSwitchTest {

    private static final String MYBATIS = "persistence.mybatis.enabled";
    private static final String OPS = "agent.platform-ops.datasource.enabled";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MybatisRlsConfiguration.class, OpsDeps.class);

    @Test
    @DisplayName("⛔ 仅运维开关开启（MyBatis 关）：ops Mapper 与工厂都不得注册")
    void opsOnlyMustNotRegisterMapperOrFactory() {
        runner.withPropertyValues(OPS + "=true", MYBATIS + "=false").run(context -> {
            assertThat(context.getStartupFailure()).isNull();
            assertThat(context.containsBean("dictionaryMapper"))
                    .as("⛔ MyBatis 关闭却注册了 ops Mapper，将引用不存在的 opsSqlSessionFactory")
                    .isFalse();
            assertThat(context.containsBean("opsSqlSessionFactory"))
                    .as("⛔ MyBatis 关闭时不应创建运维工厂")
                    .isFalse();
        });
    }

    @Test
    @DisplayName("⛔ 两个开关都未配置（默认值）：ops Mapper 不得注册")
    void neitherSwitchMustNotRegisterMapper() {
        runner.run(context -> {
            assertThat(context.getStartupFailure()).isNull();
            assertThat(context.containsBean("dictionaryMapper")).isFalse();
            assertThat(context.containsBean("opsSqlSessionFactory")).isFalse();
        });
    }

    @Test
    @DisplayName("⛔ 仅 MyBatis 开关开启（运维关）：ops Mapper 不得注册")
    void mybatisOnlyMustNotRegisterOpsMapper() {
        runner.withPropertyValues(MYBATIS + "=true", OPS + "=false").run(context -> {
            assertThat(context.getStartupFailure()).isNull();
            assertThat(context.containsBean("dictionaryMapper"))
                    .as("⛔ 运维数据源未接入却注册 ops Mapper（应退回 Jdbc 实现）")
                    .isFalse();
        });
    }

    /** 满足配置片装配的最小依赖替身（不连库）。 */
    @Configuration(proxyBeanMethods = false)
    static class OpsDeps {
        @Bean
        PlatformOpsDataSourceProvider platformOpsDataSourceProvider() {
            return PlatformOpsDataSourceProvider.disabled();
        }

        //  tenants / 工厂所需数据源占位；条件不满足时不会被真正取用。
        @Bean
        DataSource dataSource() {
            return new org.springframework.jdbc.datasource.DriverManagerDataSource();
        }
    }
}
