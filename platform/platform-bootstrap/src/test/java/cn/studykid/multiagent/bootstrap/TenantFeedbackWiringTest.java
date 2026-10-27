package cn.studykid.multiagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import cn.studykid.multiagent.api.metrics.TenantFeedbackController;
import cn.studykid.multiagent.api.run.RunController;
import cn.studykid.multiagent.infrastructure.persistence.JdbcAgentMetricRepository;
import cn.studykid.multiagent.infrastructure.persistence.JdbcTraceFactRepository;
import cn.studykid.multiagent.ports.metrics.AgentMetricPort;
import cn.studykid.multiagent.ports.metrics.TraceFactPort;

/**
 * 租户侧反馈端点装配连通性测试（P7-3 / P7-4 · ⛔ 反 R-1）。
 *
 * <p><b>⛔ 本测试存在的唯一理由</b>：与 {@code GovernanceWiringTest} 同源——
 * 「类写完了、单测也全绿」不等于「Bean 装配对了」。
 * 若端口被注册成一个<b>替身</b>（空实现 / NO_AUDIT 那种），
 * 启动仍然全绿，只有真的调接口才会发现；
 * 而本端点的失败模式恰恰是**静默**的：
 * 替身返回空 → 反馈「记上了」却一条没落库 → FB-1~FB-3 永远显示「未采集」，
 * 而看板看起来完全正常。
 *
 * <p>故本测试<b>显式断言「端口注入的是 JDBC 实现」</b>，
 * 把「装配对了」从「启动没报错」升级为「可断言的事实」。
 *
 * <p>⚠️ 依赖真实库（{@code IT_PG_URL}）：本类要装载完整 Spring 上下文，
 *    而上下文里的数据源需要连得上库才能完成初始化。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=${IT_PG_URL}",
        "spring.datasource.username=${IT_PG_USER:app_agent}",
        "spring.datasource.password=${IT_PG_PASSWORD:}",
        "spring.flyway.enabled=false",
        "agent.worker.enabled=false",
        "spring.rabbitmq.listener.simple.auto-startup=false"
})
@DisplayName("P7-3/P7-4 租户反馈端点装配")
class TenantFeedbackWiringTest {

    @Autowired
    private AgentMetricPort agentMetricPort;

    @Autowired
    private TraceFactPort traceFactPort;

    @Autowired
    private TenantFeedbackController tenantFeedbackController;

    @Autowired
    private RunController.CurrentPrincipal currentPrincipal;

    @Test
    @DisplayName("⛔ 两个端口都注入 JDBC 实现（而非替身）")
    void portsResolveToJdbcImplementations() {
        assertThat(agentMetricPort)
                .as("⛔ 指标端口若拿到替身，反馈会「记上了」却一条没落库，"
                        + "而 FB-1~FB-3 会永远显示「未采集」且看板看起来正常")
                .isInstanceOf(JdbcAgentMetricRepository.class);
        assertThat(traceFactPort)
                .as("⛔ 事实端口若拿到替身，runId 永远解析不出 traceId，"
                        + "反馈端点将恒返回 404（按钮点了没反应）")
                .isInstanceOf(JdbcTraceFactRepository.class);
    }

    @Test
    @DisplayName("✅ 控制器与可信主体都已装配")
    void controllerAndPrincipalAreRegistered() {
        assertThat(tenantFeedbackController).isNotNull();
        assertThat(currentPrincipal)
                .as("⛔ 可信主体缺失会启动失败——这是构造器注入的天然保护，"
                        + "不允许悄悄退化为「默认租户」")
                .isNotNull();
    }
}
