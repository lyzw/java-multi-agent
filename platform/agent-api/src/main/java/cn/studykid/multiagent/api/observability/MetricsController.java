package cn.studykid.multiagent.api.observability;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import cn.studykid.multiagent.application.observability.AgentMetrics;

/**
 * 指标导出端点（P4-D02 / D-4 / FR-017）。
 *
 * <p><b>⚠️ 为什么免认证</b>：Prometheus 采用「拉取」模式，
 * 端点通常免认证，认证放在网络层（管理端口 / 内网 / 反代）。
 *
 * <p><b>⚠️ 暴露面为何可控</b>：D10 已强制「指标不含高基数标签」，
 * 故本端点<b>天然不会泄露 runId / userId / studentId</b>——
 * 免认证的风险因此被限制在「聚合计数可被读取」，而非「个体数据可被拉取」。
 *
 * <p><b>⚠️ 运维建议</b>：部署时应放在<b>管理端口</b>，
 * 不与业务端点共用端口，以免公网可达。
 */
@RestController
public class MetricsController {

    private final AgentMetrics metrics;

    public MetricsController(AgentMetrics metrics) {
        this.metrics = metrics;
    }

    /**
     * 输出 Prometheus 文本格式指标。
     *
     * <p>⚠️ 返回 {@code text/plain; version=0.0.4} 而非 JSON——
     * Prometheus 只认该格式。
     */
    @GetMapping(value = "/metrics", produces = "text/plain; version=0.0.4; charset=utf-8")
    public ResponseEntity<String> metrics() {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        "text/plain; version=0.0.4; charset=utf-8"))
                .body(metrics.exportPrometheus());
    }
}
