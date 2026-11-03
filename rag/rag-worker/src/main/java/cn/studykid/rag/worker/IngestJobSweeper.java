package cn.studykid.rag.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import cn.studykid.rag.ports.IngestJobPort;

/**
 * 僵尸任务清扫器（方案 §3.2 / §7.1 系统类 / S1-5）。
 *
 * <p><b>⚠️ 没有 Sweeper，worker 崩溃的代价是「永久卡死」</b>：
 * job 停在 PARSING/CHUNKING/… 后既不会被消费循环再领取（只领 PENDING），
 * 也永远不会进终态——在录入工作台上表现为一条永远转圈的进度条。
 *
 * <p>⚠️ 接管语义：超时的「进行中」任务置 {@code FAILED} 并写
 * {@code SWEEPER_TIMEOUT} 原因，<b>不自动重试</b>——自动重试一个可能
 * 反复崩溃的任务会形成崩溃循环。重试由运维在失败处置区显式发起（新建行）。
 */
@Component
@ConditionalOnProperty(name = "rag.ingest.sweeper.enabled", havingValue = "true", matchIfMissing = true)
public class IngestJobSweeper {

    private static final Logger log = LoggerFactory.getLogger(IngestJobSweeper.class);

    private final IngestJobPort jobPort;

    /**
     * 超时阈值（分钟）。
     *
     * <p>⚠️ 默认 30 分钟：单册教材（数百页）在真实 Docling + BGE-M3 下
     * 可能需要 10 分钟以上，阈值过小会把<b>正常任务</b>误判为僵尸——
     * 误杀比漏杀更难排查（用户看到「处理失败：超时」但文件没问题）。
     */
    @Value("${rag.ingest.sweeper.timeout-minutes:30}")
    private int timeoutMinutes;

    public IngestJobSweeper(IngestJobPort jobPort) {
        this.jobPort = jobPort;
    }

    /**
     * 定期扫描并接管超时任务。
     *
     * <p>⚠️ 间隔远大于消费轮询（默认 5 分钟 vs 2 秒）：
     * Sweeper 是兜底手段，扫得太频只是无谓的全表条件扫描。
     */
    @Scheduled(fixedDelayString = "${rag.ingest.sweeper.interval-ms:300000}", initialDelayString = "60000")
    public void sweep() {
        try {
            int taken = jobPort.failTimedOutRunningJobs(timeoutMinutes);
            if (taken > 0) {
                // ⚠️ 取到就记 warn：接管频发是「worker 不稳定」的前兆，
                //    应当在它变成积压之前被看见（方案 §7.3 告警项之一）。
                log.warn("[RAG] Sweeper 接管 {} 个超时（>{}分钟）摄取任务",
                        taken, timeoutMinutes);
            }
        } catch (RuntimeException e) {
            log.warn("[RAG] Sweeper 扫描失败，下一轮重试", e);
        }
    }
}
