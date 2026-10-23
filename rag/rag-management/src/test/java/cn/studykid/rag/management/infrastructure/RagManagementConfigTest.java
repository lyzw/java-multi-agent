package cn.studykid.rag.management.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import cn.studykid.rag.management.ports.CorpusManagementPort;

/**
 * 管理面装配测试（RAG-D10）。
 *
 * <p><b>⚠️ 为什么要专门测装配</b>：本项目出现过「工具未注册为 Bean」
 * 与「两个同类型 bean 导致按类型注入歧义」两类问题
 * （后者即本模块 {@code @Qualifier} 消歧的由来）。
 * 二者都是<b>编译期发现不了、运行期才暴露</b>的问题——
 * 表现为「启动就崩」或「注入到错误的 bean」。
 *
 * <p><b>⚠️ 用 {@link ApplicationContextRunner} 而非 {@code @SpringBootTest}</b>：
 * 后者会连带触发数据源自动配置，尝试连接真实数据库。
 * 本测试只关心<b>bean 图</b>是否正确（无歧义、无缺失），
 * 用 runner 显式排除数据源自动配置即可完全离线运行。
 */
class RagManagementConfigTest {

    /**
     * 测试装配：提供 mock 的 JDBC 与事务基础设施。
     *
     * <p>⚠️ 刻意<b>不</b>引入任何数据源自动配置：
     * 本测试不验证 SQL，验证的是「bean 能否正确装配」——
     * 引入真实数据源只会让测试依赖外部环境。
     */
    @Configuration(proxyBeanMethods = false)
    static class TestConfig {

        @Bean
        JdbcTemplate jdbcTemplate() {
            return mock(JdbcTemplate.class);
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return mock(PlatformTransactionManager.class);
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of())
            .withUserConfiguration(TestConfig.class, RagManagementConfig.class);

    @Test
    @DisplayName("管理端口被注册为 Bean，且实现为 JDBC 版本")
    void corpusManagementPortIsRegistered() {
        runner.run(context -> {
            assertNotNull(context.getBean(CorpusManagementPort.class),
                    "管理端口应被注册为 Bean");
            assertSame(JdbcCorpusManagementRepository.class,
                    context.getBean(CorpusManagementPort.class).getClass(),
                    "管理端口应装配为 JDBC 实现");
        });
    }

    @Test
    @DisplayName("存在且仅存在一个 CorpusManagementPort（无歧义）")
    void exactlyOneManagementPortExists() {
        runner.run(context -> {
            String[] names = context.getBeanNamesForType(CorpusManagementPort.class);
            assertNotNull(names);
            assertEquals(1, names.length,
                    "管理端口应恰好有一个实现，实际 " + names.length + " 个");
        });
    }

    @Test
    @DisplayName("管理面事务模板按名可取（@Qualifier 消歧生效）")
    void managementTransactionTemplateResolvableByName() {
        runner.run(context -> {
            assertNotNull(context.getBean("ragManagementTransactionTemplate"));
            assertNotNull(context.getBean(TransactionTemplate.class),
                    "只存在一个事务模板时按类型注入也必须可用");
        });
    }
}
