package cn.studykid.multiagent.api.run;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import cn.studykid.multiagent.ports.persistence.RunEventQueryPort;

/**
 * SSE 长连接推送（P3-D10 / FR-014 部分）。
 *
 * <p><b>⚠️ 这是「服务端轮询」，不是真推送</b>（§2.3 D-3，诚实声明）：
 * 把轮询从<b>客户端</b>搬到<b>服务端</b>——
 * 延迟由「客户端行为」变为「服务端配置」决定（可预期），
 * 且省掉客户端反复重建连接的开销。真正的零轮询（PG {@code LISTEN/NOTIFY}）
 * 归 P4（见 P3-D10 §4 L-1）。
 *
 * <p><b>为何不让连接悬挂</b>：终态 Run 立即发完成事件并 {@code complete()}——
 * 否则连接会一直占用到超时，客户端也拿不到「结束了」信号。
 */
public class RunEventStreamer {

    private static final Logger log = LoggerFactory.getLogger(RunEventStreamer.class);

    /** 服务端轮询间隔（毫秒）。 */
    private final long pollIntervalMillis;

    /** 连接最大存活时长（毫秒）；超时自动清理，防连接泄漏。 */
    private final long maxLifetimeMillis;

    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "sse-streamer");
                t.setDaemon(true);
                return t;
            });

    public RunEventStreamer(long pollIntervalMillis, long maxLifetimeMillis) {
        if (pollIntervalMillis <= 0 || maxLifetimeMillis <= 0) {
            throw new IllegalArgumentException(
                    "SSE 轮询间隔与连接存活时长必须为正数："
                            + pollIntervalMillis + "/" + maxLifetimeMillis);
        }
        this.pollIntervalMillis = pollIntervalMillis;
        this.maxLifetimeMillis = maxLifetimeMillis;
    }

    /**
     * 帧发送抽象（<b>可测性设计</b>）。
     *
     * <p>⚠️ 直接依赖 {@link SseEmitter} 会让单测无法断言——
     * {@code SseEmitter.send()} 在<b>异步未初始化</b>时把帧排入缓冲区，
     * {@code complete()} 也不触发回调，一切信号都被吞掉（假阴性）。
     * 故抽出本接口，生产用 {@link #sseSender(SseEmitter)}，测试用记录型实现。
     */
    public interface FrameSender {
        void send(String eventType, String sequenceNo, Object data);

        /**
         * 发送**注释行**（SSE 注释帧），如 {@code : run-terminal}。
         *
         * <p>⚠️⚛️ <b>为什么终止信号必须是注释行、不是事件帧</b>（P4-D12 M-1 实测）：
         * FE-01 §P4 的轮询策略与前端 {@code sse.ts} 解析器都规定
         * 「终止：收到 {@code : run-terminal} <b>注释行</b>」。
         * 而本类原先发的是<b>事件帧</b>（{@code event: run-terminal} + data），
         * 前端解析器的事件分支要求有 {@code id} 行 → 无 id 的终止帧被<b>直接丢弃</b>
         * → 前端<b>永远收不到终止信号</b> → 轮询每 3 秒重连、<b>永不停止</b>。
         *
         * <p>注释行是<b>控制帧</b>（不携带业务数据），用事件帧表达终止信号
         * 还会让业务事件流里混进一个「假事件」——两端都该拒绝这种表达。
         */
        void sendComment(String comment);

        void complete();
    }

    /** 生产实现：把帧写入真正的 SSE 连接。 */
    public static FrameSender sseSender(SseEmitter emitter) {
        return new FrameSender() {
            @Override
            public void send(String eventType, String sequenceNo, Object data) {
                try {
                    emitter.send(SseEmitter.event()
                            .id(sequenceNo)
                            .name(eventType)
                            .data(data));
                } catch (IOException e) {
                    throw new IllegalStateException("SSE 写入失败", e);
                }
            }

            @Override
            public void sendComment(String comment) {
                try {
                    // ⚠️ SSE 注释帧：线上形态为 ":run-terminal\n\n"
                    //    （SseEmitter 自行补冒号与空行分隔）
                    emitter.send(SseEmitter.event().comment(comment));
                } catch (IOException e) {
                    throw new IllegalStateException("SSE 注释写入失败", e);
                }
            }

            @Override
            public void complete() {
                emitter.complete();
            }
        };
    }

    /**
     * 建立长连接并开始推送（可测版本：显式传入帧发送器）。
     *
     * @param emitter 已通过授权与限流的 {@link SseEmitter}
     * @param query   事件查询端口
     * @param tenantId 租户（RLS 绑定依据）
     * @param runId    Run
     * @param cursor   续传起点
     * @param isTerminalState 终态判定（须由调用方按 {@code RunState.legalTargets()} 判定）
     * @param terminalStates 终态集合
     * @param payloadFilter 载荷白名单过滤（复用 P2-D05 的白名单语义）
     */
    public void stream(SseEmitter emitter,
                       RunEventQueryPort query,
                       String tenantId,
                       String runId,
                       long cursor,
                       java.util.function.Supplier<String> stateSupplier,
                       java.util.Set<String> terminalStates,
                       java.util.function.BiPredicate<String, Map<String, Object>> payloadFilter,
                       FrameSender sender) {

        AtomicBoolean closed = new AtomicBoolean(false);

        // ⚠️ 客户端断线 / 超时都必须停止轮询——否则 scheduleAtFixedRate 永不停，
        //    每个泄漏的连接都在持续查 DB。
        // ⚠️ emitter 可为 null（单测注入记录型 FrameSender 时）——
        //    真正的连接生命周期回调由 MVC 负责，这里只做防御。
        if (emitter != null) {
            emitter.onCompletion(() -> {
                closed.set(true);
                log.debug("SSE 连接完成: runId={}", runId);
            });
            emitter.onTimeout(() -> {
                closed.set(true);
                log.debug("SSE 连接超时（已清理轮询）: runId={}", runId);
                sender.complete();
            });
            emitter.onError(t -> {
                closed.set(true);
                log.debug("SSE 连接出错（已清理轮询）: runId={}", runId);
            });
        }

        long[] currentCursor = {cursor};

        ScheduledFuture<?>[] taskHolder = new ScheduledFuture<?>[1];
        taskHolder[0] = scheduler.scheduleAtFixedRate(() -> {
            if (closed.get()) {
                // 自我取消：避免 scheduleAtFixedRate 在连接关闭后继续执行
                taskHolder[0].cancel(false);
                return;
            }
            try {
                List<RunEventQueryPort.RunEvent> events =
                        query.findAfter(tenantId, runId, currentCursor[0], 200);
                for (RunEventQueryPort.RunEvent event : events) {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("eventType", event.eventType());
                    payload.put("payload", filterPayload(payloadFilter,
                            event.eventType(), event.payload()));
                    sender.send(event.eventType(), String.valueOf(event.sequenceNo()), payload);
                    currentCursor[0] = Math.max(currentCursor[0], event.sequenceNo());
                }

                // ⚠️ 终态判定：发完成事件后**立即关闭连接**（D-3）。
                //    不关的话连接会一直占用到超时，客户端也等不到结束信号。
                String state = stateSupplier.get();
                if (state != null && terminalStates.contains(state)) {
                    // ⚠️⚛️ 终止信号必须是**注释行**（P4-D12 M-1）——
                    //    前端解析器只认 ': run-terminal' 注释行；
                    //    发事件帧会被丢弃 → 客户端轮询永不停止。
                    sender.sendComment("run-terminal");
                    sender.complete();
                    closed.set(true);
                }
            } catch (IllegalStateException e) {
                // ⚠️ 客户端已断开：停止轮询，不刷屏报错
                closed.set(true);
                log.debug("SSE 推送结束（客户端断开）: runId={}, err={}",
                        runId, e.getMessage());
            } catch (RuntimeException e) {
                // ⚠️ 服务端故障：结束这条连接而非让任务无限重试
                closed.set(true);
                log.error("SSE 推送异常，结束该连接: runId={}", runId, e);
                if (emitter != null) {
                    emitter.completeWithError(e);
                }
            }
        }, 0, pollIntervalMillis, TimeUnit.MILLISECONDS);

        // ⚠️ 最大存活兜底：即便 Run 永不终结（如 Worker 全挂），
        //    连接也不会无限占用资源。
        scheduler.schedule(() -> {
            if (!closed.get()) {
                closed.set(true);
                log.warn("SSE 连接达到最大存活时长，强制结束: runId={}, max={}ms",
                        runId, maxLifetimeMillis);
                sender.complete();
            }
        }, maxLifetimeMillis, TimeUnit.MILLISECONDS);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> filterPayload(
            java.util.function.BiPredicate<String, Map<String, Object>> filter,
            String eventType, Map<String, Object> payload) {
        if (payload == null) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        payload.forEach((k, v) -> {
            if (filter == null || filter.test(eventType, Map.of("key", k))) {
                out.put(k, v);
            }
        });
        return out;
    }
}