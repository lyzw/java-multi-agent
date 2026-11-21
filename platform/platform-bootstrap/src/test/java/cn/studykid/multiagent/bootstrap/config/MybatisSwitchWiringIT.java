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
import cn.studykid.multiagent.infrastructure.persistence.JdbcDictionaryRepository;
import cn.studykid.multiagent.infrastructure.persistence.MybatisAuditRepository;
import cn.studykid.multiagent.infrastructure.persistence.MybatisChatAuditRetentionRepository;
import cn.studykid.multiagent.infrastructure.persistence.MybatisRunMessageRepository;
import cn.studykid.multiagent.infrastructure.persistence.agentchataudit.JdbcChatAuditConfigRepository;
import cn.studykid.multiagent.infrastructure.persistence.agentchataudit.JdbcChatAuditRecordQueryRepository;
import cn.studykid.multiagent.infrastructure.security.JdbcPlatformAuditRepository;
import cn.studykid.multiagent.infrastructure.security.JdbcPlatformPrincipalRepository;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditConfigPort;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditRecordQueryPort;
import cn.studykid.multiagent.ports.audit.PlatformAuditPort;
import cn.studykid.multiagent.ports.persistence.AuditPort;
import cn.studykid.multiagent.ports.persistence.DictionaryPort;
import cn.studykid.multiagent.ports.persistence.RunMessagePort;
import cn.studykid.multiagent.ports.security.PlatformPrincipalRepository;

/**
 * MyBatis-Plus 迁移开关的<b>装配级反证</b>：{@code persistence.mybatis.enabled=true}
 * 时，业务端口必须真的换成 Mybatis 实现，且 MP 自动配置不能抢占 SqlSessionFactory。
 *
 * <p>⚠️ 默认验证里所有 IT 都跑在开关关闭状态（Jdbc 实现），因此「Mybatis 代码编译通过」
 * 完全不能证明开关可用。本类是最小闭环：真起 Spring 上下文 + 真连 IT 库，
 * 断言 Bean 类型与工厂边界；运行期 SQL 正确性由开关开启的端到端 IT 覆盖。
 *
 * <p>⚠️ 运维数据源未接入： DictionaryPort 必须仍为 Jdbc（fail-closed 不降级成
 * 绑定在租户工厂上的 ops Mapper）。
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
        // ★ 被测开关（默认全量验证关闭，本类唯一开启点）
        "persistence.mybatis.enabled=true"
})
@DisplayName("MyBatis-Plus 迁移开关 · 装配反证")
class MybatisSwitchWiringIT {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("✅ 开启后租户域 Port 切到 Mybatis 实现，且没有重复 Bean")
    void tenantPortsSwitchToMybatis() {
        assertThat(context.getBean(AuditPort.class)).isInstanceOf(MybatisAuditRepository.class);
        assertThat(context.getBean(RunMessagePort.class)).isInstanceOf(MybatisRunMessageRepository.class);
    }

    @Test
    @DisplayName("✅ MP 自动配置被排除：只有项目自建的租户工厂，没有 sqlSessionFactory")
    void autoConfigurationDoesNotWin() {
        assertThat(context.containsBean("sqlSessionFactory")).isFalse();
        assertThat(context.getBean("tenantSqlSessionFactory")).isNotNull();
    }

    @Test
    @DisplayName("✅ 运维通道未接入时平台域 Port 留在 Jdbc（不绑错工厂）")
    void platformOpsPortsStayOnJdbcWithoutOpsDatasource() {
        assertThat(context.getBean(DictionaryPort.class)).isInstanceOf(JdbcDictionaryRepository.class);
        assertThat(context.containsBean("opsSqlSessionFactory")).isFalse();
    }

    @Test
    @DisplayName("✅ 身份/全局配置/跨租户查询/删除通道不随开关切到租户工厂")
    void credentialIsolatedPortsStayOnJdbc() {
        assertThat(context.getBean(PlatformPrincipalRepository.class))
                .isInstanceOf(JdbcPlatformPrincipalRepository.class);
        assertThat(context.getBean(PlatformAuditPort.class))
                .isInstanceOf(JdbcPlatformAuditRepository.class);
        assertThat(context.getBean(ChatAuditConfigPort.class))
                .isInstanceOf(JdbcChatAuditConfigRepository.class);
        assertThat(context.getBean(ChatAuditRecordQueryPort.class))
                .isInstanceOf(JdbcChatAuditRecordQueryRepository.class);
        // 清理凭据未接入时端口本身就是 null；关键是带错数据源的 MyBatis 删除实现绝不能出现。
        assertThat(context.getBeansOfType(MybatisChatAuditRetentionRepository.class)).isEmpty();
    }
}
