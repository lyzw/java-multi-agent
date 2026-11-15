package cn.studykid.multiagent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.application.observability.AgentMetrics;
import cn.studykid.multiagent.infrastructure.observer.metrics.AgentMetricsExecutionObserver;
import cn.studykid.multiagent.ports.observer.ExecutionObserver;
import cn.studykid.multiagent.ports.observer.ExecutionObserverChain;

/**
 * {@code /metrics} 从「恒空」变为「真有数」的接线守卫（P0 修复 · 2026-11-15）。
 *
 * <p><b>要钉死的不是「类存在」，而是「数据真的产出」</b>：
 * 改动前 {@code AgentMetrics} 有 Bean、有端点、有标签白名单测试，
 * 但 {@code increment} 在全仓 main 侧零调用，端点永远只返回空串。
 * 项目铁律 17：「有类/有表/有测 ≠ 被装配/被写入/被注入」。
 *
 * <p><b>三层反向验证</b>：
 * <ol>
 *   <li>撤掉 {@code AgentMetricsExecutionObserver} 里的 {@code metrics.increment}
 *       → 本类第 1 个用例（真实调用链产出计数）立即变红；</li>
 *   <li>撤掉 {@code ObserverConfiguration} 的 Bean 注册 → 第 2 个用例变红；</li>
 *   <li>撤掉 {@code WorkerExecutionConfig} 对 {@code ExecutionObserverChain}
 *       的真实注入 → 第 2 个用例的装配断言变红。</li>
 * </ol>
 */
class MetricsEndpointWiringTest {

    private static final String TENANT = "6f5d33fc-723e-4f64-beda-9e4435c72691";
    private static final String RUN = "run-p0-metrics";

    private static final String OBSERVER_CONFIG =
            "platform/platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/config/"
                    + "ObserverConfiguration.java";
    private static final String WORKER_CONFIG =
            "platform/platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/config/"
                    + "WorkerExecutionConfig.java";

    @Test
    @DisplayName("✅ 真实执行链：收到 onLoopComplete 后 /metrics 必须产出非零样本")
    void loopCompletionProducesRealMetricSample() {
        AgentMetrics metrics = new AgentMetrics();
        AgentMetricsExecutionObserver observer = new AgentMetricsExecutionObserver(metrics);
        ExecutionObserverChain chain = new ExecutionObserverChain(List.of(observer));

        assertThat(metrics.exportPrometheus())
                .as("尚未收到任何事件时端点应为空 —— 本修复绝不为了『有数』而造数")
                .isEmpty();

        chain.onLoopComplete(TENANT, RUN,
                new ExecutionObserver.LoopOutcome(
                        ExecutionObserver.LoopOutcome.Kind.SUCCEEDED, null),
                3);

        assertThat(metrics.valueOf(AgentMetricsExecutionObserver.LOOPS_TOTAL,
                Map.of("outcome", "SUCCEEDED")))
                .as("撤掉 AgentMetricsExecutionObserver 的 increment 调用，这里会红")
                .isEqualTo(1L);
        assertThat(metrics.exportPrometheus())
                .contains(AgentMetricsExecutionObserver.LOOPS_TOTAL)
                .as("导出文本必须含样本行，而不只是 # HELP / # TYPE 注释头")
                .contains(" 1\n");
        assertThat(metrics.seriesKeys())
                .as("标签必须只剩低基数 outcome，绝不能带 tenantId/runId/traceId")
                .allSatisfy(key -> assertThat(key)
                        .doesNotContain(TENANT)
                        .doesNotContain(RUN));
    }

    @Test
    @DisplayName("✅ 装配链：桥接 Bean 已注册，且执行循环真实注入 ExecutionObserverChain")
    void bridgeBeanIsRegisteredAndChainIsInjected() {
        String config = read(OBSERVER_CONFIG);
        assertThat(config)
                .as("撤掉 ObserverConfiguration 里的 @Bean 注册，/metrics 立即回到恒空")
                .contains("AgentMetricsExecutionObserver agentMetricsExecutionObserver(")
                .contains("@ConditionalOnMissingBean(AgentMetricsExecutionObserver.class)");

        // 桥接 Bean 只有进入 Spring 收集的 List<ExecutionObserver> → executionObserverChain，
        // 且该链真实注入 RunExecutionLoop，事件才会到达 onLoopComplete。
        assertThat(config)
                .contains("public ExecutionObserverChain executionObserverChain(")
                .contains("List<ExecutionObserver> observers")
                .contains("return new ExecutionObserverChain(observers)");

        String worker = read(WORKER_CONFIG);
        assertThat(worker)
                .as("撤掉 RunExecutionLoop 的 observerChain 注入，桥接 Bean 就只是个孤儿 Bean")
                .contains("ExecutionObserverChain observerChain");
    }

    private static String read(String relative) {
        try {
            return Files.readString(repoRoot().resolve(relative), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取装配源码失败: " + relative, e);
        }
    }

    /** 向上定位仓库根（模块目录 / IDE 工作目录都能命中）。 */
    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++, dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve("deploy/sql/platform/01_platform_ddl.sql"))) {
                return dir;
            }
        }
        throw new IllegalStateException("找不到仓库根（deploy/sql/platform/01_platform_ddl.sql）");
    }
}
