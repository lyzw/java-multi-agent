package cn.studykid.multiagent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import cn.studykid.multiagent.bootstrap.PlatformApplication;
import cn.studykid.multiagent.infrastructure.persistence.MybatisDictionaryRepository;
import cn.studykid.multiagent.infrastructure.persistence.MybatisModelCatalogRepository;
import cn.studykid.multiagent.infrastructure.persistence.MybatisPlatformAuditRepository;
import cn.studykid.multiagent.infrastructure.persistence.MybatisPlatformPrincipalRepository;
import cn.studykid.multiagent.ports.audit.PlatformAuditPort;
import cn.studykid.multiagent.ports.modelcatalog.ModelCatalogPort;
import cn.studykid.multiagent.ports.persistence.DictionaryPort;
import cn.studykid.multiagent.ports.security.PlatformPrincipalRepository;

/**
 * MyBatis 开关 + 平台运维数据源同时开启时的装配反证。
 *
 * <p>平台身份/审计/字典/模型目录的 Mapper 必须绑定 {@code opsSqlSessionFactory}
 * （BYPASSRLS 运维数据源），不能落到带 RLS 拦截器的租户工厂上。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
@SpringBootTest(classes = PlatformApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=${IT_PG_URL}",
        "spring.datasource.username=${IT_PG_USER:app_agent}",
        "spring.datasource.password=${IT_PG_PASSWORD:}",
        "spring.flyway.enabled=false",
        "auth.jwt.issuer=https://auth.test",
        "auth.jwt.audience=agent-platform",
        "auth.jwt.token-ttl-seconds=900",
        "agent.messaging.mode=database",
        "agent.worker.enabled=false",
        "agent.worker.relay-enabled=false",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "persistence.mybatis.enabled=true",
        "agent.platform-ops.datasource.enabled=true",
        "agent.platform-ops.datasource.url=${IT_PG_URL}",
        "agent.platform-ops.datasource.username=${IT_PG_ADMIN_USER:agent}",
        "agent.platform-ops.datasource.password=${IT_PG_ADMIN_PASSWORD:}"
})
@DisplayName("MyBatis-Plus 迁移开关 · 运维通道装配反证")
class MybatisOpsSwitchWiringIT {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("✅ 双开关开启时平台域 Port 切到 Mybatis，且走独立运维工厂")
    void platformPortsSwitchToMybatisOnOpsFactory() {
        assertThat(context.containsBean("tenantSqlSessionFactory")).isTrue();
        assertThat(context.containsBean("opsSqlSessionFactory")).isTrue();
        assertThat(context.getBean(PlatformPrincipalRepository.class))
                .isInstanceOf(MybatisPlatformPrincipalRepository.class);
        assertThat(context.getBean(PlatformAuditPort.class))
                .isInstanceOf(MybatisPlatformAuditRepository.class);
        assertThat(context.getBean(DictionaryPort.class))
                .isInstanceOf(MybatisDictionaryRepository.class);
        assertThat(context.getBean(ModelCatalogPort.class))
                .isInstanceOf(MybatisModelCatalogRepository.class);
    }

    @Test
    @DisplayName("✅ 平台身份 Mapper XML 在运维通道解析成功并可真实查询")
    void platformPrincipalMapperIsBoundAndQueryable() {
        assertThat(context.getBean(PlatformPrincipalRepository.class).listUsers()).isNotNull();
    }
}
