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

import cn.studykid.multiagent.application.evaluation.L1AttributionEvaluator;
import cn.studykid.multiagent.application.evaluation.TraceEvaluationService;
import cn.studykid.multiagent.application.metrics.AgentMetricCollector;

/**
 * 质量评估双轨（P3）的<b>装配接线守卫</b>。
 *
 * <p>⚠️ <b>为什么必须有本类</b>：评估服务是采集器的<b>可选构造参数</b>。
 * 漏装时 Spring 照常启动、指标照常落库、告警照常联动，
 * 只是 {@code agent_task_outcome} 永远没有 L1 回执、
 * 看板的「自动校验覆盖率」恒为 0、黄金基线抽样任务恒为空。
 * <b>没有任何报错</b>——又是「机制正确但从未接线」的形状。
 * 由 {@code EvaluationClosedLoopIT} 做闭环验证，本类负责指出
 * 失败时究竟是「装配断链」还是「判分逻辑错了」。
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
@DisplayName("P3 评估双轨 · 装配接线守卫")
class EvaluationWiringTest {

    @Autowired
    private ApplicationContext ctx;

    @Nested
    @DisplayName("E-W1 Bean 必须在生产装配里真的存在")
    class BeanPresence {

        @Test
        @DisplayName("E-W1a 评估服务与 L1 判分器都已注册")
        void beansExist() {
            assertThat(ctx.getBean(TraceEvaluationService.class)).isNotNull();
            assertThat(ctx.getBean(L1AttributionEvaluator.class)).isNotNull();
        }
    }

    @Nested
    @DisplayName("E-W2 ⚠️ 评估服务必须真的接进采集器")
    class CollectorWiring {

        @Test
        @DisplayName("E-W2a ⚠️⚠️ AgentMetricCollector.evaluation 非 null 且与容器同一实例")
        void evaluationInjected() throws Exception {
            TraceEvaluationService bean = ctx.getBean(TraceEvaluationService.class);
            AgentMetricCollector collector = ctx.getBean(AgentMetricCollector.class);
            java.lang.reflect.Field field = collector.getClass().getDeclaredField("evaluation");
            field.setAccessible(true);

            assertThat(field.get(collector))
                    .as("⚠️⚠️ 评估服务没接进采集器 —— "
                            + "覆盖率恒 0、抽样队列恒空，而所有其它测试全绿、无任何报错")
                    .isSameAs(bean);
        }
    }
}
