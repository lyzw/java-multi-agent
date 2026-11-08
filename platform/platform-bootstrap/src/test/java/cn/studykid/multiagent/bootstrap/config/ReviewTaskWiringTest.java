package cn.studykid.multiagent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import cn.studykid.multiagent.api.platform.PlatformReviewTaskController;
import cn.studykid.multiagent.application.metrics.AgentMetricCollector;
import cn.studykid.multiagent.application.review.ReviewAlertService;
import cn.studykid.multiagent.infrastructure.persistence.JdbcReviewTaskRepository;
import cn.studykid.multiagent.ports.persistence.ReviewTaskPort;

/**
 * 人工审查队列（P2 · G3「人审」半边）的<b>装配接线</b>守卫。
 *
 * <p><b>⚠️ 为什么必须有本类</b>：审查链路上有<b>两处可选注入</b>——
 * {@code AgentMetricCollector.reviewAlerts} 与告警服务自身的端口。
 * 任何一处漏装，症状都是同一种：
 * <b>「采集正常、指标有数、但 review_task 永远为空」</b>，
 * 而且<b>没有任何报错</b>——队列空是「没有异常」的正常表现，
 * 与「接线断了」在外部完全无法区分。
 *
 * <p>这正是本项目反复出现的「机制正确但从未接线」形状（P4-D08 R-1）。
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
@DisplayName("P2 人工审查队列 · 装配接线守卫")
class ReviewTaskWiringTest {

    @Autowired
    private ApplicationContext ctx;

    @Nested
    @DisplayName("R-1 ⚠️ 端口必须是 JDBC 实现，告警服务与端点必须已注册")
    class BeanPresence {

        @Test
        @DisplayName("R-1a ⚠️⚠️ ReviewTaskPort 必须是 JDBC 实现（替身 = 任务永远不落库）")
        void portIsJdbc() {
            assertThat(ctx.getBean(ReviewTaskPort.class))
                    .as("⚠️⚠️ 端口若被换成空实现，采集照常、指标照常，"
                            + "但 review_task 永远为空 —— 审查流程整体空转且无任何告警")
                    .isInstanceOf(JdbcReviewTaskRepository.class);
        }

        @Test
        @DisplayName("R-1b ⚠️ 告警联动服务与审查队列端点都已装配")
        void alertServiceAndControllerRegistered() {
            assertThat(ctx.getBean(ReviewAlertService.class)).isNotNull();
            assertThat(ctx.getBean(PlatformReviewTaskController.class)).isNotNull();
        }
    }

    @Nested
    @DisplayName("R-2 ⚠️⚠️ 联动真的接进了采集链路（Bean 存在 ≠ 接线完成）")
    class CollectorWiring {

        @Test
        @DisplayName("R-2a ⚠️⚠️ AgentMetricCollector.reviewAlerts 必须非 null")
        void collectorHasReviewAlerts() throws Exception {
            Object injected = field(ctx.getBean(AgentMetricCollector.class), "reviewAlerts");
            assertThat(injected)
                    .as("⚠️⚠️ 告警服务没注入采集器 = 与不存在完全等价；"
                            + "症状是「指标有数、队列恒空」且无任何报错")
                    .isNotNull();
        }

        @Test
        @DisplayName("R-2b ⚠️ 注入的是容器里同一个告警服务实例")
        void collectorUsesContainerInstance() throws Exception {
            assertThat(field(ctx.getBean(AgentMetricCollector.class), "reviewAlerts"))
                    .as("采集器另起一套告警服务 = 另一套端口实现，队列写入行为不可预测")
                    .isSameAs(ctx.getBean(ReviewAlertService.class));
        }

        @Test
        @DisplayName("R-2c ⚠️ 告警服务接的是容器里的队列端口")
        void alertServiceUsesContainerPort() throws Exception {
            assertThat(field(ctx.getBean(ReviewAlertService.class), "reviewTasks"))
                    .as("告警服务若拿了另一个端口，队列写入与端点读取会看到不同事实")
                    .isSameAs(ctx.getBean(ReviewTaskPort.class));
        }
    }

    /** 读私有字段（不给生产类加 getter —— 那等于为测试打开生产 API 面）。 */
    private static Object field(Object target, String name) throws Exception {
        java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }
}
