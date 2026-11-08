package cn.studykid.multiagent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import cn.studykid.multiagent.ports.persistence.ReviewTaskPort;

/**
 * ⚠️⚠️ <b>应用主数据源身份守卫（Sev-0 防回归）</b>。
 *
 * <p>2026-11-08 在 RDS 实跑 P2 审查队列时抓到：容器里
 * <b>只有一个 DataSource Bean —— {@code retentionDataSource}</b>，
 * 连接身份是 {@code agent_retention}。原因是
 * {@code DataSourceAutoConfiguration} 带 {@code @ConditionalOnMissingBean(DataSource.class)}，
 * 配置类先注册的清理池让条件不成立，主池整段没建。
 * 于是所有裸 {@code DataSource} 注入静默拿到清理角色：
 * 业务 SQL 全程以「可删审计记录」的身份执行，功能却一切正常、不报错。
 * 第一个可见症状只是 {@code review_task} 报 permission denied。
 *
 * <p><b>本类验的是身份，不是连接能不能用</b>——
 * 「连得上」与「用对角色连」在旧缺陷里同时成立，
 * 所以连通性测试、仓储 IT、端点单测全绿也发现不了。
 *
 * <p>⚠️⚠️ <b>断言里必须用字面量 {@code "dataSource"}，绝不能引用
 * {@code PrimaryDataSourceConfiguration.BEAN_NAME}</b>：那是编译期常量，
 * 会被内联进测试。反向验证时只要把常量改掉，断言会跟着一起漂 →
 * 「抽掉修复点却不红」的<b>假绿</b>（2026-11-08 实测踩过）。
 * 守卫必须钉死在<b>外部契约</b>上，而不是被测实现自己提供的字符串。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
@SpringBootTest(
        classes = cn.studykid.multiagent.bootstrap.PlatformApplication.class,
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
        "agent.worker.tenant-slug=t-a",
        "spring.rabbitmq.listener.simple.auto-startup=false"
})
@DisplayName("应用主数据源 · 身份与注入守卫")
class PrimaryDataSourceWiringTest {

    @Autowired
    private ApplicationContext ctx;

    @Nested
    @DisplayName("D-1 ⚠️ 主池必须真的存在于容器里")
    class PrimaryPresence {

        @Test
        @DisplayName("D-1a ⚠️⚠️ 名字为 dataSource 的 Bean 必须存在（被清理池顶掉则整体失效）")
        void primaryBeanExists() {
            assertThat(ctx.getBeansOfType(DataSource.class))
                    .as("⚠️⚠️ 容器里没有 dataSource：说明 DataSourceAutoConfiguration "
                            + "又被某个自定义 DataSource Bean 顶掉了，"
                            + "全部业务 SQL 会静默落到 retentionDataSource（agent_retention 身份）")
                    .containsKey("dataSource");
        }

        @Test
        @DisplayName("D-1b ⚠️⚠️ 主池连接身份绝不能是 agent_retention")
        void primaryIsNotRetentionRole() throws Exception {
            DataSource primary = ctx.getBean("dataSource", DataSource.class);
            try (Connection conn = primary.getConnection()) {
                String user = conn.getMetaData().getUserName();
                assertThat(user)
                        .as("⚠️⚠️ 主数据源竟以保留期清理角色连接 —— "
                                + "该角色持有 audit_record / erasure_certificate 的 DELETE，"
                                + "业务 SQL 将能删审计记录（审计不可篡改设计被架空）")
                        .isNotEqualToIgnoringCase("agent_retention");
            }
        }
    }

    @Nested
    @DisplayName("D-2 ⚠️ 业务仓储必须注入主池，而不是清理池")
    class RepositoryUsesPrimary {

        @Test
        @DisplayName("D-2a ⚠️⚠️ ReviewTaskPort 持有的 DataSource 必须与容器内主池同一实例")
        void reviewTaskPortUsesPrimary() throws Exception {
            DataSource primary = ctx.getBean("dataSource", DataSource.class);
            ReviewTaskPort port = ctx.getBean(ReviewTaskPort.class);
            java.lang.reflect.Field field = port.getClass().getDeclaredField("dataSource");
            field.setAccessible(true);
            Object injected = field.get(port);

            assertThat(injected)
                    .as("⚠️⚠️ 审查队列端口没拿到应用主池 —— "
                            + "若它是 retentionDataSource，建任务会 permission denied，"
                            + "告警联动 fail-soft 吞掉后队列恒空、日志只有一条 WARN")
                    .isSameAs(primary);
        }
    }
}
