package cn.studykid.rag.worker;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import cn.studykid.rag.application.ingest.IngestJobOrchestrator;
import cn.studykid.rag.core.ingest.IngestJob;
import cn.studykid.rag.core.scope.RagScope;
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
    private final RagIndexerProperties properties;

    @Value("${rag.ingest.consumer.poll-interval-ms:2000}")
    private long pollIntervalMs;

    public IngestJobConsumer(IngestJobPort jobPort, IngestJobOrchestrator orchestrator,
                             RagIndexerProperties properties) {
        this.jobPort = jobPort;
        this.orchestrator = orchestrator;
        this.properties = properties;
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
            job = claimNext();
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
                jobPort.markFailed(job.tenantId(), job.jobId(),
                        "CONSUMER_CRASH: 编排器异常 " + e.getClass().getSimpleName()
                                + ": " + e.getMessage());
            } catch (RuntimeException inner) {
                log.error("[RAG] 且失败原因也无法回写 job={}（需人工介入）", job.jobId(), inner);
            }
        }
    }

    /**
     * 逐租户领取一条待处理任务。
     *
     * <p>⚠️⚠️ 为什么不能跨租户扫全表：Worker 以「非特权 + 无 BYPASSRLS」的应用角色连接，
     * {@code rag_ingest_job} 的 RLS 策略是 {@code tenant_id = rag_current_tenant()}——
     * 未设租户的会话<b>读不到任何行</b>（2026-11-03 实测 visible_jobs=0），
     * 跨租户领取会「看起来在跑、实际永远领不到」，任务静默积压。
     * 故租户由 {@code rag.indexer.tenants} 显式给出（与 {@link ReindexRunner} 同一口径），
     * 领取在事务内设租户会话，隔离由 RLS 策略本身保证。
     *
     * <p>⚠️ 租户清单为空 = 没有被授权处理的租户 → 无事可做是常态，不刷屏日志。
     */
    private IngestJob claimNext() {
        List<String> tenants = properties.getTenants();
        if (tenants == null || tenants.isEmpty()) {
            return null;
        }
        for (String raw : tenants) {
            String t = raw == null ? "" : raw.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (RagScope.isGlobalTenant(t)) {
                // ⚠️ 全局桶不是「把哨兵当租户」：set_config 填哨兵后 rag_current_tenant()
                //    直接抛错（V8 封堵「租户伪装成平台身份」）。全局语料的摄取走
                //    ChunkIndexer#indexGlobalCorpus 那条运维通道，不在本循环范围内。
                log.warn("[RAG] 租户清单里的全局哨兵不走本领取通道，已跳过：{}", t);
                continue;
            }
            IngestJob job = jobPort.claimNextJobForTenant(t);
            if (job != null) {
                return job;
            }
        }
        return null;
    }
}
