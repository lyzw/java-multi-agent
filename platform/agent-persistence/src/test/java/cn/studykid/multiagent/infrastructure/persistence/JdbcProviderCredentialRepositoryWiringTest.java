package cn.studykid.multiagent.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import cn.studykid.multiagent.ports.modelcredential.ProviderCredentialPort;

/**
 * 组件扫描装配回归（启动期 "No default constructor found" 事故）。
 *
 * <p>本仓储有两个公开构造：Spring 入口 {@code (DataSource, @Value String)} 与
 * 测试用 {@code (DataSource, AesGcmCipher)}。多构造且无 {@code @Autowired} 时，
 * {@code AutowiredAnnotationBeanPostProcessor} 不会给出候选构造，Spring 回落
 * 无参构造 → 启动直接失败。本测试用与 {@code @SpringBootApplication} 同一套
 * {@code AnnotatedBeanDefinitionReader} 注册，保证「编译通过 ≠ 能启动」这类
 * 缺陷在单测阶段就暴露。
 */
class JdbcProviderCredentialRepositoryWiringTest {

    private static final String MASTER_KEY = "wiring-test-master-secret-2026";

    @Test
    @DisplayName("@Repository 组件扫描可用 DataSource + @Value 主密钥完成装配")
    void componentScanInstantiatesRepository() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "test-model-credential",
                    Map.of("agent.model-credential.master-key", MASTER_KEY)));
            DataSource dataSource = new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/none");
            ctx.registerBean(DataSource.class, () -> dataSource);
            // 与组件扫描等价的注解式 BeanDefinition 注册路径
            ctx.register(JdbcProviderCredentialRepository.class);

            ctx.refresh();

            JdbcProviderCredentialRepository repo =
                    ctx.getBean(JdbcProviderCredentialRepository.class);
            assertThat(repo).isInstanceOf(ProviderCredentialPort.class);
            assertThat(ctx.getBean(ProviderCredentialPort.class)).isSameAs(repo);
            // 必须走 Spring 入口构造（主密钥注入），而非测试用 AesGcmCipher 构造
            assertThat(masterKeyOf(repo)).isEqualTo(MASTER_KEY);
        }
    }

    private static String masterKeyOf(JdbcProviderCredentialRepository repo) {
        try {
            Field field = JdbcProviderCredentialRepository.class.getDeclaredField("masterKey");
            field.setAccessible(true);
            return (String) field.get(repo);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法读取 masterKey 字段", e);
        }
    }
}
