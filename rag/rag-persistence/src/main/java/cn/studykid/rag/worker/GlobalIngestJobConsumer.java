package cn.studykid.rag.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import cn.studykid.rag.application.ingest.GlobalIngestJobOrchestrator;
import cn.studykid.rag.core.ingest.IngestJob;
import cn.studykid.rag.ports.IngestJobPort;

/**
 * 平台档（全局桶）摄取任务消费循环（G1 / RAG-D11 §1.3）。
 *
 * <p><b>⚠️ 与 {@link IngestJobConsumer} 的关系</b>：职责镜像（PG 轮询 + SKIP LOCKED +
 * 一条一条领 + 不吞异常），但**只**处理全局桶的任务——
 * 通过 {@link IngestJobPort#claimNextGlobalJob()} 领取（谓词写死全局哨兵），
 * 由 {@link GlobalIngestJobOrchestrator} 在运维会话上完成摄入。
 *
 * <p>⚠️ <b>为什么不能复用租户侧 {@link IngestJobConsumer}</b>：
 * 租户侧按 {@code rag.indexer.tenants} 逐租户领取，且明确跳过全局哨兵
 * （设哨兵会让 {@code rag_current_tenant()} 越权封堵）。全局桶的写入只能走 BYPASSRLS
 * 的运维通道，故必须是一条独立的、带全局方法签名的消费路径。
 *
 * <p>⚠️ 仅在运维通道启用时装配：{@code rag.ops.enabled=false} 时本 bean 不创建，
 * 避免每轮 poll 都抛 {@code RagOpsUnavailableException} 刷屏（且全局档本就 fail-closed）。
 */
@Component
@ConditionalOnProperty(name = "rag.ops.enabled", havingValue = "true", matchIfMissing = true)
public class GlobalIngestJobConsumer {

    private static final Logger log = LoggerFactory.getLogger(GlobalIngestJobConsumer.class);

    private final IngestJobPort jobPort;
    private final GlobalIngestJobOrchestrator orchestrator;

    @Value("${rag.ingest.global-consumer.poll-interval-ms:2000}")
    private long pollIntervalMs;

    public GlobalIngestJobConsumer(IngestJobPort jobPort, GlobalIngestJobOrchestrator orchestrator) {
        this.jobPort = jobPort;
        this.orchestrator = orchestrator;
    }

    /**
     * 轮询并处理一条待处理的全局摄取任务。
     *
     * <p>⚠️ {@code fixedDelay} 而非 {@code fixedRate}：单册教材可能跑几分钟嵌入，
     * 不应被下一轮追赶堆积并发线程。
     */
    @Scheduled(fixedDelayString = "${rag.ingest.global-consumer.poll-interval-ms:2000}",
            initialDelayString = "5000")
    public void pollOnce() {
        IngestJob job;
        try {
            job = jobPort.claimNextGlobalJob();
        } catch (RuntimeException e) {
            // ⚠️ 领取失败（如运维连接抖动）不能终止调度线程。
            log.warn("[RAG] 领取全局摄取任务失败，下一轮重试", e);
            return;
        }
        if (job == null) {
            return; // 无事可做是常态，不打日志（否则每 2 秒一条噪声）
        }
        log.info("[RAG] 领取全局摄取任务 job={} source={} attempt={}",
                job.jobId(), job.sourceId(), job.attempt());
        try {
            orchestrator.run(job);
        } catch (RuntimeException e) {
            // ⚠️ 编排器本应自行把失败写回 job；走到这里说明连回写都可能失败。
            //    尽力补一次 FAILED，仍失败则只记日志——绝不让线程死掉。
            log.error("[RAG] 全局摄取任务执行异常 job={}", job.jobId(), e);
            try {
                jobPort.markFailedGlobal(job.jobId(),
                        "CONSUMER_CRASH: 编排器异常 " + e.getClass().getSimpleName()
                                + ": " + e.getMessage());
            } catch (RuntimeException inner) {
                log.error("[RAG] 且失败原因也无法回写全局 job={}（需人工介入）", job.jobId(), inner);
            }
        }
    }
}
