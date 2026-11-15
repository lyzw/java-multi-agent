package cn.studykid.multiagent.worker.dispatch;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.ports.observer.TraceObserver;
import cn.studykid.multiagent.ports.persistence.RunClaimPort;

/**
 * 端到端追踪执行器包装（P0 修复 · 2026-11-15）。
 *
 * <p><b>本类存在的理由</b>：{@code TraceObserverChain} 有三个实现
 * （Slf4j / Micrometer / EventPort）与一个 Spring Bean，但<b>没有任何注入点</b>——
 * 改动前 {@code ai.trace.*} 指标与 MDC traceId 永不产出，
 * 而所有相关单测测的都是「链本身会不会逐个调用」，故全绿。
 *
 * <p><b>接线方式</b>：包装 {@link RunDispatchConsumer.RunExecutor}（生产装配在
 * {@code WorkerExecutionConfig.runTaskExecutor}），在每次执行的入口/出口触发
 * {@link TraceObserver}。选择包装执行器而非改 {@code RunDispatchConsumer} 的构造，
 * 是为了<b>不牵动 5+ 个依赖其 2 参/3 参构造的既有测试</b>，零回归面。
 *
 * <p><b>⚠️ 覆盖边界（诚实声明）</b>：这里覆盖的是「领取租约 → 执行结束」这一段，
 * 不含「MQ 消息到达 → 领取租约」的前置段。完整的 HTTP→outbox→MQ→worker 链路
 * 需要 OTel + 跨进程 trace 传播（见深度报告 10 改进点 1），不属于本次 P0 修复范围。
 *
 * <p><b>⚠️ 观测者异常绝不影响主流程</b>：{@code TraceObserverChain} 内部已逐个
 * try/catch，本类再包一层保护（双保险）。执行器抛出的异常<b>原样冒泡</b>，
 * 绝不因追踪而吞掉或改写 —— 否则会破坏 {@code RunDispatchConsumer} 的
 * 「孤儿 Run 兜底收敛」语义。
 *
 * <p><b>⚠️ traceId 格式契约</b>：{@code tenantId:runId:8位随机}，与
 * {@code Slf4jTraceObserver.generateTraceId} 一致；{@code MicrometerTraceObserver}
 * 依赖第一段作为 tenant 标签。
 */
public class TracedRunExecutor implements RunDispatchConsumer.RunExecutor {

    private static final Logger log = LoggerFactory.getLogger(TracedRunExecutor.class);

    private final RunDispatchConsumer.RunExecutor delegate;
    private final TraceObserver observer;

    // ⚠️ 铁律 20：jspecify @NonNull 是 type-use 注解，不能标注
    //    RunDispatchConsumer.RunExecutor 这类全限定/限定嵌套类型名，编译期会直接报错；
    //    这里改为不做静态标注（两个参数由 Spring 装配保证非 null，且下方
    //    delegate.execute / observer.onXxx 一旦为 null 会立刻 NPE 暴露，不会静默）。
    public TracedRunExecutor(RunDispatchConsumer.RunExecutor delegate,
                             TraceObserver observer) {
        this.delegate = delegate;
        this.observer = observer;
    }

    @Override
    public RunDispatchConsumer.Outcome execute(String tenantId,
                                               RunClaimPort.LeasedTask task) {
        String traceId = tenantId + ":" + task.runId() + ":"
                + UUID.randomUUID().toString().substring(0, 8);
        // ⚠️ payload 只放白名单中性字段，绝不把消息正文/用户输入写进追踪
        //    （观测数据中的文本必须脱敏，见 ExecutionObserver 接口纪律）。
        Map<String, Object> payload = Map.of(
                "eventType", RunDispatchConsumer.RUN_DISPATCH,
                "runId", task.runId());
        long startNanos = System.nanoTime();

        safeStart(traceId, tenantId, task.runId(), payload);
        try {
            RunDispatchConsumer.Outcome outcome = delegate.execute(tenantId, task);
            safeEnd(traceId, outcome, elapsed(startNanos));
            return outcome;
        } catch (RuntimeException e) {
            safeAborted(traceId, e.getClass().getSimpleName(), elapsed(startNanos));
            // ⚠️ 原样冒泡：消费端靠这个异常做孤儿 Run 兜底收敛，绝不能吞。
            throw e;
        }
    }

    private static Duration elapsed(long startNanos) {
        return Duration.ofNanos(Math.max(0L, System.nanoTime() - startNanos));
    }

    private void safeStart(String traceId, String tenantId, String runId,
                           Map<String, Object> payload) {
        try {
            observer.onTraceStart(traceId, tenantId, runId,
                    RunDispatchConsumer.RUN_DISPATCH, payload);
        } catch (RuntimeException e) {
            log.warn("追踪起点观测失败（不影响执行）: trace={}, cause={}", traceId, e.toString());
        }
    }

    private void safeEnd(String traceId, RunDispatchConsumer.Outcome outcome, Duration latency) {
        try {
            // Outcome 与 TraceOutcome 取值一一同名；valueOf 失败即契约漂移，必须显式暴露。
            observer.onTraceEnd(traceId,
                    TraceObserver.TraceOutcome.valueOf(outcome.name()), latency);
        } catch (RuntimeException e) {
            log.warn("追踪终点观测失败（不影响执行）: trace={}, cause={}", traceId, e.toString());
        }
    }

    private void safeAborted(String traceId, String error, Duration latency) {
        try {
            observer.onTraceAborted(traceId, error, latency);
        } catch (RuntimeException e) {
            log.warn("追踪中断观测失败（不影响执行）: trace={}, cause={}", traceId, e.toString());
        }
    }
}
