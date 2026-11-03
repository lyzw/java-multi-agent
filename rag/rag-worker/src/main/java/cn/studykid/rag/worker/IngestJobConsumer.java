package cn.studykid.rag.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import cn.studykid.rag.application.ingest.IngestJobOrchestrator;
import cn.studykid.rag.core.ingest.IngestJob;
import cn.studykid.rag.ports.IngestJobPort;

/**
 * 摄取任务消费循环（方案 §8.2 选型 ① / 设计 §4.2）。
 *
 * <p><b>⚠️ 为什么是 PG 轮询而非 MQ</b>：RAG 模块当前零 MQ 依赖，
 * 录入频次是「按册 / 按学期」量级，MQ 的低延迟优势无意义，
 * 反而引入 broker 运维耦合。领取语义由
 * {@code FOR UPDATE SKIP LOCKED} 保证——多 instance 并行安全，不需要分布式锁。
 *
 * <p><b>⚠️ 每次轮询处理「一条」而非「一批」</b>：单册教材可能切出上千片段、
 * 跑几分钟嵌入，批量领取会把 worker 的可用性绑在一批任务的最慢成员上。
 * 一条一条领，积压由轮询频率自然消化。
 *
 * <p><b>⚠️ 本循环不吞异常</b>：编排器已把业务失败转成 job FAILED，
 * 这里只兜住「编排器自身崩溃」这类非预期错误——记日志并继续下一轮，
 * 绝不让一次意外终止整个消费线程（否则 worker 静默停工，任务永久积压）。
 */
@Component
@ConditionalOnProperty(name = "rag.ingest.consumer.enabled", havingValue = "true", matchIfMissing = true)
public class IngestJobConsumer {

    private static final Logger log = LoggerFactory.getLogger(IngestJobConsumer.class);

    private final IngestJobPort jobPort;
    private final IngestJobOrchestrator orchestrator;

    @Value("${rag.ingest.consumer.poll-interval-ms:2000}")
    private long pollIntervalMs;

    public IngestJobConsumer(IngestJobPort jobPort, IngestJobOrchestrator orchestrator) {
        this.jobPort = jobPort;
        this.orchestrator = orchestrator;
    }

    /**
     * 轮询并处理一条待处理任务。
     *
     * <p>⚠️ {@code fixedDelay} 而非 {@code fixedRate}：本轮处理耗时（可能数分钟）
     * 不应被下一轮触发追赶，否则会堆积大量并发线程。
     */
    @Scheduled(fixedDelayString = "${rag.ingest.consumer.poll-interval-ms:2000}", initialDelayString = "5000")
    public void pollOnce() {
        IngestJob job;
        try {
            job = jobPort.claimNextJob();
        } catch (RuntimeException e) {
            // ⚠️ 领取失败（如数据库抖动）不能终止调度线程。
            log.warn("[RAG] 领取摄取任务失败，下一轮重试", e);
            return;
        }
        if (job == null) {
            return; // 无事可做是常态，不打日志（否则每 2 秒一条噪声）
        }
        log.info("[RAG] 领取摄取任务 job={} source={} attempt={}",
                job.jobId(), job.sourceId(), job.attempt());
        try {
            orchestrator.run(job);
        } catch (RuntimeException e) {
            // ⚠️ 编排器本应自行把失败写回 job；走到这里说明连回写都可能失败。
            //    尽力补一次 FAILED，仍失败则只记日志——绝不让线程死掉。
            log.error("[RAG] 摄取任务执行异常 job={}", job.jobId(), e);
            try {
                jobPort.markFailed(job.jobId(),
                        "CONSUMER_CRASH: 编排器异常 " + e.getClass().getSimpleName()
                                + ": " + e.getMessage());
            } catch (RuntimeException inner) {
                log.error("[RAG] 且失败原因也无法回写 job={}（需人工介入）", job.jobId(), inner);
            }
        }
    }
}
