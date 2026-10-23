package cn.studykid.multiagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * 消息通道健康检查的 HTTP 级端点测试。
 *
 * <p><b>⚠️ 本类盯的是一个真实缺陷</b>：
 * Spring Boot 只要 classpath 上有 {@code spring-boot-starter-amqp} 就<b>无条件</b>装配
 * {@code RabbitHealthIndicator}，与「本实例是否真的在用 Rabbit 通道」无关。
 * 而 {@code agent.messaging.mode} 默认是 {@code database} → 默认部署里<b>没有 RabbitMQ</b>
 * → 自动装配的指示器照样去连 {@code 127.0.0.1:5672} → {@code Connection refused}
 * → {@code /actuator/health} 报 <b>DOWN（实测 HTTP 503）</b>。
 *
 * <p><b>⚠️ 危害不是「多一行日志」</b>：任何以本端点为存活/就绪探针的编排
 * （k8s probe / Docker healthcheck / 云负载均衡）会把<b>完全健康的实例</b>
 * 判为不健康 → 反复重启或摘除流量。
 *
 * <p><b>⚠️ 反证</b>：本类在修复前必然红——修复前本机实跑（dev profile、database 通道、
 * 无 RabbitMQ）实测 {@code GET /actuator/health} → <b>HTTP 503 / status=DOWN</b>。
 *
 * <p><b>⚠️ 为什么必须用 {@code RANDOM_PORT} 而非 {@code MOCK}</b>：
 * {@code MOCK} 环境下安全过滤器链不装配，本端点虽为匿名可访问，
 * 但取不到「真实装配 + 真实健康聚合」这条链路。
 *
 * <p><b>⚠️ 本类的覆盖边界（如实说明）</b>：
 * 通道分配 Bean 位于 {@code WorkerLoopConfig}，带
 * {@code @ConditionalOnProperty("agent.worker.enabled")}；本 IT 与其它 IT 一样
 * 关闭 worker（避免消费共享库里的消息），故此处走的是
 * 「worker 未启用 → 没有租户在用 Rabbit 通道」这条分支；
 * 「worker 启用 + database 通道」分支由
 * {@code MessagingChannelHealthIndicatorTest} 逐条覆盖（含反证）。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
@SpringBootTest(classes = PlatformApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=${IT_PG_URL}",
        "spring.datasource.username=${IT_PG_USER:app_agent}",
        "spring.datasource.password=${IT_PG_PASSWORD:}",
        "spring.flyway.enabled=false",
        "auth.jwt.issuer=https://auth.test",
        "auth.jwt.audience=agent-platform",
        "auth.jwt.token-ttl-seconds=900",
        "agent.worker.enabled=false",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        // ⚠️ 必须开明细：否则看不到「rabbit 贡献者是谁判的、为什么」，
        //    而「看不出原因」正是本缺陷让排查方向被带偏的第二重危害。
        "management.endpoint.health.show-details=always"
})
@DisplayName("消息通道健康检查（Rabbit 不在用时不得拖垮健康端点）")
class MessagingHealthEndpointIT {

    @Autowired
    private RestTestClient rest;

    @Test
    @DisplayName("✅ Rabbit 不在用 → /actuator/health 200 + UP（修复前为 503/DOWN）")
    void healthIsUpWhenRabbitChannelIsNotInUse() {
        rest.get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP")
                // ⚠️ 关键：rabbit 贡献者必须**存在**且为 UP。
                //    若自动装配的那个还在，它会连 broker → DOWN → 本断言红。
                //    若自定义的那个顶上了，它才可能给出我们写的「reason」明细。
                .jsonPath("$.components.rabbit.status").isEqualTo("UP")
                .jsonPath("$.components.rabbit.details.reason").exists();
    }

    @Test
    @DisplayName("✅ 探针分组仍为 UP（k8s liveness/readiness 不得被 Rabbit 拖垮）")
    void probeGroupsAreUp() {
        rest.get().uri("/actuator/health/liveness")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP");

        rest.get().uri("/actuator/health/readiness")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP");
    }

    @Test
    @DisplayName("✅ 本项目的替换确实顶掉了自动装配（健康树里只有一个 rabbit 贡献者）")
    void ourIndicatorReplacedTheAutoConfiguredOne() {
        String body = rest.get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body)
                .as("⛔ 响应里不得出现 Spring 自动装配的 Rabbit 探测痕迹"
                        + "（connection refused / AmqpConnectException）——"
                        + "出现即说明我们的替换没生效")
                .doesNotContain("AmqpConnectException")
                .doesNotContain("Connection refused");
    }
}
