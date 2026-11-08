package cn.studykid.multiagent.api.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import cn.studykid.multiagent.api.security.PlatformSecurity;
import cn.studykid.multiagent.application.metrics.AgentMetricCollector;
import cn.studykid.multiagent.ports.metrics.AgentMetricPort;
import cn.studykid.multiagent.ports.metrics.AgentMetricPort.MetricQuery;
import cn.studykid.multiagent.ports.security.AccessDeniedException;
import cn.studykid.multiagent.ports.security.AuthorizationDecision;
import cn.studykid.multiagent.ports.security.PlatformAuthorizationService;
import cn.studykid.multiagent.ports.security.PlatformPrincipal;

/**
 * 场景筛选「整页同口径」的控制器守卫。
 *
 * <p><b>⚠️ 守的不是「某个方法能跑」，而是五个读端点都把同一个 scenario 下传：</b>
 * 只给 {@code /overview} 带场景，分布/耗时/工具/异常仍是全量 —— 合计对不上却零报错，
 * 正是本项目反复出现的失真形态。这里直接捕获传给 port 的 {@link MetricQuery}，
 * 断言字面量（<b>绝不引用被测实现提供的常量</b>，否则抽掉修复点时断言会跟着漂）。
 *
 * <p>用真实 {@link PlatformSecurity} + 替身授权服务，不覆写 security：
 * 覆写会把「权限码是否用对」一起绕过，测出来的绿只证明控制器调了某个方法。
 */
class PlatformAgentMetricsControllerScenarioTest {

    private static final String SCENARIO = "数学:一元二次方程";
    private static final PlatformPrincipal READER = new PlatformPrincipal(
            "user-audit", Set.of("PLATFORM_AUDITOR"), 1, false);

    private final AgentMetricPort metrics = mock(AgentMetricPort.class);
    private final AgentMetricCollector collector = mock(AgentMetricCollector.class);
    private final PlatformAuthorizationService authorizer = mock(PlatformAuthorizationService.class);
    private PlatformAgentMetricsController controller;

    @BeforeEach
    void setUp() {
        when(authorizer.authorize(any(), anyString(), any(), any()))
                .thenReturn(AuthorizationDecision.allow(1));
        controller = new PlatformAgentMetricsController(
                new PlatformSecurity(authorizer), metrics, collector);
    }

    @Test
    @DisplayName("✅ 五个读端点必须收到同一个 scenario（漏一个即整页口径错配）")
    void allFiveReadEndpointsReceiveSameScenario() {
        controller.overview(READER, 24, null, null, null, SCENARIO);
        controller.terminations(READER, 24, null, null, SCENARIO);
        controller.latency(READER, 24, null, null, SCENARIO);
        controller.tools(READER, 24, null, SCENARIO);
        controller.anomalies(READER, 24, 20, SCENARIO);

        ArgumentCaptor<MetricQuery> ov = ArgumentCaptor.forClass(MetricQuery.class);
        ArgumentCaptor<MetricQuery> tm = ArgumentCaptor.forClass(MetricQuery.class);
        ArgumentCaptor<MetricQuery> lt = ArgumentCaptor.forClass(MetricQuery.class);
        ArgumentCaptor<MetricQuery> tl = ArgumentCaptor.forClass(MetricQuery.class);
        ArgumentCaptor<MetricQuery> an = ArgumentCaptor.forClass(MetricQuery.class);
        verify(metrics).overview(ov.capture());
        verify(metrics).terminationDistribution(tm.capture());
        verify(metrics).latency(lt.capture());
        verify(metrics).toolBreakdown(tl.capture());
        verify(metrics).anomalies(an.capture(), eq(20));

        // ⚠️ 字面量钉死：断言绝不能引用被测实现/端口里定义的常量。
        assertThat(ov.getValue().scenario()).isEqualTo("数学:一元二次方程");
        assertThat(tm.getValue().scenario()).isEqualTo("数学:一元二次方程");
        assertThat(lt.getValue().scenario()).isEqualTo("数学:一元二次方程");
        assertThat(tl.getValue().scenario()).isEqualTo("数学:一元二次方程");
        assertThat(an.getValue().scenario()).isEqualTo("数学:一元二次方程");
    }

    @Test
    @DisplayName("✅ 「未标注」哨兵原样透传（由仓储层译成 IS NULL，控制器不臆造语义）")
    void unlabeledSentinelPassedThrough() {
        controller.overview(READER, 24, null, null, null, "(unlabeled)");

        ArgumentCaptor<MetricQuery> cap = ArgumentCaptor.forClass(MetricQuery.class);
        verify(metrics).overview(cap.capture());
        assertThat(cap.getValue().scenario()).isEqualTo("(unlabeled)");
    }

    @Test
    @DisplayName("✅ /scenarios 是筛选器自身数据源：绝不带 scenario，hours/tenantId 仍透传")
    void scenariosEndpointNeverFiltersByItself() {
        String tenant = "2187ffed-3e2e-4fd0-b4a7-54dcc0a483e2";
        controller.scenarios(READER, 168, tenant);

        ArgumentCaptor<MetricQuery> cap = ArgumentCaptor.forClass(MetricQuery.class);
        verify(metrics).scenarios(cap.capture());
        assertThat(cap.getValue().scenario()).isNull();
        assertThat(cap.getValue().agentId()).isNull();
        assertThat(cap.getValue().tenantId()).isEqualTo(tenant);
        assertThat(Duration.between(cap.getValue().from(), cap.getValue().to()).toHours()).isEqualTo(168);
    }

    @Test
    @DisplayName("✅ 读端点先过 platform.resource.read 闸门，拒绝时不触达 port")
    void readGateIsEnforcedBeforeTouchingMetrics() {
        when(authorizer.authorize(any(), anyString(), any(), any()))
                .thenReturn(AuthorizationDecision.deny("NO_PERMISSION", 1));

        assertThatThrownBy(() -> controller.scenarios(READER, 24, null))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.overview(READER, 24, null, null, null, SCENARIO))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.tools(READER, 24, null, SCENARIO))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(metrics);
    }

    @Test
    @DisplayName("✅ 缺省不传场景时五个端点都拿到 null，不把空串误当成一个真实标签")
    void absentScenarioStaysNull() {
        controller.overview(READER, 24, null, null, null, null);
        controller.terminations(READER, 24, null, null, null);
        controller.latency(READER, 24, null, null, null);
        controller.tools(READER, 24, null, null);
        controller.anomalies(READER, 24, 20, null);

        ArgumentCaptor<MetricQuery> cap = ArgumentCaptor.forClass(MetricQuery.class);
        verify(metrics).overview(cap.capture());
        verify(metrics).terminationDistribution(cap.capture());
        verify(metrics).latency(cap.capture());
        verify(metrics).toolBreakdown(cap.capture());
        verify(metrics).anomalies(cap.capture(), eq(20));
        assertThat(cap.getAllValues()).allSatisfy(q -> assertThat(q.scenario()).isNull());
    }
}
