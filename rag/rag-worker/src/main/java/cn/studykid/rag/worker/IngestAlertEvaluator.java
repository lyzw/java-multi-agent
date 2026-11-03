package cn.studykid.rag.worker;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import cn.studykid.rag.core.ingest.IngestAlertRules;
import cn.studykid.rag.ports.IngestHealth;
import cn.studykid.rag.ports.IngestJobPort;

/**
 * 摄取链告警评估器（V1.0.2 §7.3 / S4-1）。
 *
 * <p><b>⚠️ 本类只做评估与记录，不自己发 webhook</b>：
 * 平台已有 {@code agent.alerts.webhook-url} 通道，RAG 再建一套等于有两个告警源，
 * 运维要盯两个地方才能判断「是不是同一个故障」。这里的告警以
 * <b>ERROR/WARN 日志 + 结构化输出</b>呈现，由平台的日志告警规则统一收集。
 *
 * <p><b>⚠️ 为什么失败率要剔除 SOURCE_REVOKED</b>（N2）：
 * 撤回是治理动作的正常结果。若把它计入失败率，
 * 一次「法务撤回 20 册」会立刻触发 CRITICAL 告警，而系统其实一切正常——
 * 误报比漏报更能摧毁告警的可信度。
 */
@Component
@ConditionalOnProperty(name = "rag.ingest.alerts.enabled", havingValue = "true", matchIfMissing = true)
public class IngestAlertEvaluator {

    private static final Logger log = LoggerFactory.getLogger(IngestAlertEvaluator.class);

    private final IngestJobPort jobPort;

    /** 评估间隔（默认 5 分钟）：告警是「趋势」信号，不需要秒级。 */
    @Value("${rag.ingest.alerts.interval-ms:300000}")
    private long intervalMs;

    public IngestAlertEvaluator(IngestJobPort jobPort) {
        this.jobPort = jobPort;
    }

    /**
     * 定期评估并输出告警。
     *
     * <p>⚠️ 评估失败（如数据库抖动）只记 warn 并继续，不能让调度线程死掉——
     * 告警器自己挂掉会让「告警失效」这件事静默发生。
     */
    @Scheduled(fixedDelayString = "${rag.ingest.alerts.interval-ms:300000}",
            initialDelayString = "120000")
    public void evaluate() {
        IngestHealth h;
        try {
            h = jobPort.health();
        } catch (RuntimeException e) {
            log.warn("[RAG][ALERT] 摄取链健康快照读取失败，本轮跳过", e);
            return;
        }

        List<IngestAlertRules.Alert> alerts = IngestAlertRules.evaluateAll(
                h.failed(), h.finished(), h.pending(),
                h.oldestPendingMinutes(), h.takeoversLastHour());

        for (IngestAlertRules.Alert a : alerts) {
            String line = String.format(
                    "[RAG][ALERT][%s] metric=%s value=%.2f threshold=%.2f | %s",
                    a.level(), a.metric(), a.value(), a.threshold(), a.message());
            if (a.level() == IngestAlertRules.Level.CRITICAL) {
                log.error(line);
            } else {
                log.warn(line);
            }
        }
        if (alerts.isEmpty()) {
            log.debug("[RAG][ALERT] 摄取链正常：finished={} failed={} pending={}",
                    h.finished(), h.failed(), h.pending());
        }
    }
}
