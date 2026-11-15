package cn.studykid.multiagent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.ports.observer.TraceObserver;
import cn.studykid.multiagent.ports.persistence.RunClaimPort;
import cn.studykid.multiagent.worker.dispatch.RunDispatchConsumer;
import cn.studykid.multiagent.worker.dispatch.TracedRunExecutor;

/**
 * {@code TraceObserverChain} 接线守卫（P0 修复 · 2026-11-15）。
 *
 * <p><b>要钉死的问题</b>：该链此前有 Bean、有三个实现、有完整的「逐个调用」单测，
 * 却<b>没有任何注入点</b> —— {@code ai.trace.*} 与 MDC traceId 恒无数据，
 * 而所有单测仍然全绿。本次通过 {@link TracedRunExecutor} 在装配层包装执行器接线。
 *
 * <p><b>反向验证</b>：撤掉 {@code WorkerExecutionConfig} 里的包装，
 * 装配断言立即变红；撤掉 {@code onTraceStart/onTraceEnd} 调用，行为断言变红。
 *
 * <p><b>诚实边界</b>：这里覆盖的是「领取租约 → 执行结束」，
 * 不是完整 HTTP→outbox→MQ→worker 链路，后者需要 OTel 跨进程传播（P1）。
 */
class ObserverWiringTest {

    private static final String TENANT = "6f5d33fc-723e-4f64-beda-9e4435c72691";
    private static final String RUN = "run-p0-trace";

    private static final String WORKER_CONFIG =
            "platform/platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/config/"
                    + "WorkerExecutionConfig.java";
    private static final String MICROMETER_OBSERVER =
            "platform/agent-infrastructure/src/main/java/cn/studykid/multiagent/infrastructure/"
                    + "observer/metrics/MicrometerTraceObserver.java";

    private static RunClaimPort.LeasedTask task() {
        return new RunClaimPort.LeasedTask(RUN, 1L, Instant.now().plusSeconds(30), 1);
    }

    @Test
    @DisplayName("✅ 正常路径：包装执行器触发 start/end，且不改写执行结局")
    void tracedExecutorEmitsStartAndEnd() {
        TraceObserver observer = mock(TraceObserver.class);
        RunDispatchConsumer.RunExecutor delegate =
                (t, task) -> RunDispatchConsumer.Outcome.SUCCEEDED;

        RunDispatchConsumer.Outcome outcome =
                new TracedRunExecutor(delegate, observer).execute(TENANT, task());

        assertThat(outcome).isEqualTo(RunDispatchConsumer.Outcome.SUCCEEDED);
        verify(observer).onTraceStart(anyString(), eq(TENANT), eq(RUN),
                eq(RunDispatchConsumer.RUN_DISPATCH), anyMap());
        verify(observer).onTraceEnd(anyString(),
                eq(TraceObserver.TraceOutcome.SUCCEEDED), any(Duration.class));
    }

    @Test
    @DisplayName("✅ 异常路径：触发 aborted 且异常原样冒泡（否则孤儿 Run 兜底失效）")
    void tracedExecutorEmitsAbortedAndRethrows() {
        TraceObserver observer = mock(TraceObserver.class);
        IllegalStateException boom = new IllegalStateException("boom");
        RunDispatchConsumer.RunExecutor failing = (t, task) -> {
            throw boom;
        };

        assertThatThrownBy(() ->
                new TracedRunExecutor(failing, observer).execute(TENANT, task()))
                .as("消费端靠这个异常做孤儿 Run 兜底收敛，绝不能被追踪层吞掉")
                .isSameAs(boom);
        verify(observer).onTraceAborted(anyString(),
                eq("IllegalStateException"), any(Duration.class));
    }

    @Test
    @DisplayName("✅ 装配链：runDispatchConsumer 必须用 TracedRunExecutor 包装执行器")
    void workerConsumerIsWiredToTraceChain() {
        String worker = read(WORKER_CONFIG);
        assertThat(worker)
                .as("撤掉这段包装，TraceObserverChain 又变回无注入点的孤儿 Bean")
                .contains("TraceObserverChain traceObserverChain)")
                .contains("new cn.studykid.multiagent.worker.dispatch.TracedRunExecutor(")
                .contains("return new RunDispatchConsumer(claimPort, Duration.ofSeconds(leaseSeconds), effective)");
    }

    @Test
    @DisplayName("✅ 并发前置：MicrometerTraceObserver 不得持有单例可变 Timer.Sample")
    void micrometerObserverIsStateless() {
        // ⚠️ 必须先剥注释：修复说明的 Javadoc 里就写着「Timer.Sample sample」，
        //    直接全文 doesNotContain 会把说明文字误判成代码（恒定假红）。
        String code = codeOnly(read(MICROMETER_OBSERVER));
        assertThat(code)
                .as("单例 Timer.Sample 会让并发 Run 互相覆盖 sample，延迟分位数静默失真")
                .doesNotContain("Timer.Sample sample")
                .doesNotContain("Timer.start(registry)");
        assertThat(code)
                .as("接线后必须由接口传入的 totalLatency 直接 record，不再依赖实例状态")
                .contains("timer.record(");
    }

    /** 丢弃纯注释行（// 与 Javadoc 的 * 行），保留代码与字符串字面量。 */
    private static String codeOnly(String src) {
        StringBuilder out = new StringBuilder(src.length());
        for (String line : src.split("\\R")) {
            String t = line.strip();
            if (t.startsWith("//") || t.startsWith("/*") || t.startsWith("*")) {
                continue;
            }
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private static String read(String relative) {
        try {
            return Files.readString(repoRoot().resolve(relative), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取装配源码失败: " + relative, e);
        }
    }

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
