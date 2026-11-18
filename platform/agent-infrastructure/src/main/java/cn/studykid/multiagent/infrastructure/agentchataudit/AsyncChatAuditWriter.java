package cn.studykid.multiagent.infrastructure.agentchataudit;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditConfig;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditRecord;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditSinkPort;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditWriterPort;

/**
 * 对话审计异步写入器（fail-open 核心）。
 *
 * <p><b>⚠️ 决策 6「不影响业务」的四层保证</b>：
 * <ol>
 *   <li>业务线程只做 {@link #submit} → 有界队列 {@code offer()}（满即丢弃，绝不阻塞）；</li>
 *   <li>后台 worker 逐个 sink 写入，单 sink 异常被吞并计数，不影响其它 sink；</li>
 *   <li>worker 线程为 daemon + 顶层 try/catch，任何漏网异常也不会杀死写入循环；</li>
 *   <li>队列容量<b>每次提交时按当前配置读取</b>，且硬上限兜住内存——
 *       配置写错少打一个数量级，也不能把堆吃光。</li>
 * </ol>
 *
 * <p><b>⚠️ 双写</b>：一条记录依次经全部 sink；一个失败另一个仍写。
 *
 * <p><b>⚠️ 容量为何做成动态</b>：队列在构造时定容量，但「全部配置项前端可改、
 * 改完即时生效」是决策 7。折中做法是构造一个<b>硬上限</b>队列，提交时比较
 * 「当前配置容量」，超了按丢弃处理——效果与重建队列一致，且不会丢在途记录。
 *
 * <p><b>⚠️ 可观测</b>：{@link #submittedTotal()}/{@link #droppedTotal()}/
 * {@link #sinkErrorTotal()}/{@link #writtenTotal()} 供监控读取。
 * 「请求在跑、三个计数全 0」就是审计没工作的最短证据链。
 */
public final class AsyncChatAuditWriter implements ChatAuditWriterPort, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AsyncChatAuditWriter.class);

    /**
     * 队列硬上限（绝对上界，不受配置影响）。
     *
     * <p>一条 envelope 含逐分片原文，量级可达数百 KiB；10 万条的极端堆积在内存上不可接受，
     * 因此本上限与「单请求分片上限」共同构成两道内存闸，缺一不可。
     */
    private static final int HARD_CAPACITY = 100_000;

    private final List<ChatAuditSinkPort> sinks;
    private final Supplier<ChatAuditConfig> configSupplier;
    private final BlockingQueue<ChatAuditRecord> queue;
    private final Thread worker;
    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong sinkErrors = new AtomicLong();
    private final AtomicLong written = new AtomicLong();
    private volatile boolean running = true;

    public AsyncChatAuditWriter(@NonNull List<ChatAuditSinkPort> sinks,
                                @NonNull Supplier<ChatAuditConfig> configSupplier) {
        this.sinks = List.copyOf(sinks);
        this.configSupplier = configSupplier;
        this.queue = new LinkedBlockingQueue<>(HARD_CAPACITY);
        this.worker = new Thread(this::loop, "chat-audit-writer");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    /**
     * 业务线程调用：非阻塞提交。
     *
     * <p>{@code async=false} 时在调用线程同步写（仍吞异常）；生产热路径恒为异步。
     */
    @Override
    public void submit(@NonNull ChatAuditRecord record, boolean async) {
        submitted.incrementAndGet();
        if (!async) {
            writeAll(record);
            return;
        }
        if (queue.size() >= effectiveCapacity()) {
            countDropped(record, "队列已达配置容量");
            return;
        }
        if (!queue.offer(record)) {
            countDropped(record, "队列已满");
        }
    }

    /** 当前生效容量：配置值与硬上限的较小者，且至少 1。 */
    private int effectiveCapacity() {
        int configured;
        try {
            configured = configSupplier.get().writer().queueCapacity();
        } catch (RuntimeException e) {
            // 配置读失败不能连累审计本身：退回硬上限，而不是「一条都不收」。
            return HARD_CAPACITY;
        }
        return Math.max(1, Math.min(HARD_CAPACITY, configured));
    }

    private void countDropped(@NonNull ChatAuditRecord record, @NonNull String why) {
        long d = dropped.incrementAndGet();
        // 丢弃率异常时每 100 条打一次：既留证据，又不让日志本身变成第二个故障
        if (d == 1 || d % 100 == 0) {
            log.warn("对话审计丢弃记录（{}）: auditId={}，累计丢弃 {} 条",
                    why, record.auditId(), d);
        }
    }

    private void loop() {
        while (running) {
            try {
                writeAll(queue.take());
            } catch (InterruptedException e) {
                if (!running) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } catch (RuntimeException e) {
                // 漏网异常也不能杀死写入循环
                sinkErrors.incrementAndGet();
                log.warn("对话审计写入循环异常（已继续）", e);
            }
        }
        // 停机排空：尽力写完已在队列里的记录（poll 到空即止，不无限等）
        ChatAuditRecord remaining;
        while ((remaining = queue.poll()) != null) {
            writeAll(remaining);
        }
    }

    private void writeAll(@NonNull ChatAuditRecord record) {
        boolean anySuccess = false;
        for (ChatAuditSinkPort sink : sinks) {
            try {
                sink.write(record);
                anySuccess = true;
            } catch (RuntimeException e) {
                sinkErrors.incrementAndGet();
                log.warn("对话审计 sink 写入失败: sink={}, auditId={}, cause={}",
                        sink.name(), record.auditId(), e.toString());
            }
        }
        if (anySuccess) {
            written.incrementAndGet();
        }
    }

    @Override public long submittedTotal() { return submitted.get(); }
    @Override public long droppedTotal() { return dropped.get(); }
    @Override public long sinkErrorTotal() { return sinkErrors.get(); }
    @Override public long writtenTotal() { return written.get(); }

    /** 停止 worker（应用关闭时调用；会尽力排空队列）。 */
    @Override
    public void close() {
        running = false;
        worker.interrupt();
    }
}
