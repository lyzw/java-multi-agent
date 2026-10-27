package cn.studykid.multiagent.api.platform;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import cn.studykid.multiagent.api.security.PlatformSecurity;
import cn.studykid.multiagent.application.metrics.AgentMetricCollector;
import cn.studykid.multiagent.ports.metrics.AgentMetricPort;
import cn.studykid.multiagent.ports.metrics.AgentMetricPort.EvalSample;
import cn.studykid.multiagent.ports.metrics.AgentMetricPort.FeedbackEvent;
import cn.studykid.multiagent.ports.metrics.AgentMetricPort.FeedbackEvent.FeedbackKind;
import cn.studykid.multiagent.ports.metrics.AgentMetricPort.MetricQuery;
import cn.studykid.multiagent.ports.metrics.AgentMetricPort.RiskEvent;
import cn.studykid.multiagent.ports.metrics.AgentMetricPort.RiskEvent.RiskType;
import cn.studykid.multiagent.ports.metrics.AgentMetricPort.TaskOutcome;
import cn.studykid.multiagent.ports.security.IdentityDomains;
import cn.studykid.multiagent.ports.security.Namespaces;
import cn.studykid.multiagent.ports.security.PlatformPrincipal;

/**
 * 平台 Agent 效果指标端点（P7）。
 *
 * <p><b>权限</b>：读 {@code platform.resource.read}；录入（标注 / 反馈 / 风险 / 校验回执）
 * 走 {@code platform.resource.manage}。刻意复用冻结矩阵既有权限码，<b>不新增权限</b>。
 *
 * <p><b>⚠️ 本端点只做观测，不做执行</b>：不返回任何 Trace 的对话内容或工具原文。
 * 效果指标看的是「跑得好不好」，不是「说了什么」——后者是租户域隐私红线。
 *
 * <p><b>⚠️ 未采集 = {@code null}，绝不等于 0</b>（贯穿全链路的头号纪律）：
 * 尚未接入采集来源的指标一律以 {@code null} 返回，前端显示「未采集」。
 * 返回 0 会让「没测过幻觉」变成「幻觉率 0%」。
 */
@RestController
@RequestMapping("/api/platform/v1/metrics/agent")
public class PlatformAgentMetricsController {

    private static final long DEFAULT_RANGE_HOURS = 24;

    private final PlatformSecurity security;
    private final AgentMetricPort metrics;
    private final AgentMetricCollector collector;

    public PlatformAgentMetricsController(PlatformSecurity security,
                                          AgentMetricPort metrics,
                                          AgentMetricCollector collector) {
        this.security = security;
        this.metrics = metrics;
        this.collector = collector;
    }

    // ══════════════════════════════════════════════════════════════
    // 读
    // ══════════════════════════════════════════════════════════════

    /**
     * 六类核心指标总览。
     *
     * <p>响应里的 {@code null} 一律表示「未采集」，前端不得渲染为 0。
     */
    @GetMapping("/overview")
    public AgentMetricPort.Overview overview(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @RequestParam(name = "hours", defaultValue = "24") long hours,
            @RequestParam(name = "tenantId", required = false) String tenantId,
            @RequestParam(name = "agentId", required = false) String agentId,
            @RequestParam(name = "agentVersion", required = false) Integer agentVersion,
            @RequestParam(name = "scenario", required = false) String scenario) {
        security.require(principal, Namespaces.Platform.RESOURCE_READ, null, null);
        return metrics.overview(query(hours, tenantId, agentId, agentVersion, scenario));
    }

    /** 终止原因分布（9 值归一，合计必须等于总数）。 */
    @GetMapping("/terminations")
    public List<AgentMetricPort.ReasonCount> terminations(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @RequestParam(name = "hours", defaultValue = "24") long hours,
            @RequestParam(name = "tenantId", required = false) String tenantId,
            @RequestParam(name = "agentId", required = false) String agentId) {
        security.require(principal, Namespaces.Platform.RESOURCE_READ, null, null);
        return metrics.terminationDistribution(query(hours, tenantId, agentId, null, null));
    }

    /** 端到端耗时分位数（P50/P95/P99，查明细不查物化视图）。 */
    @GetMapping("/latency")
    public AgentMetricPort.LatencyPercentiles latency(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @RequestParam(name = "hours", defaultValue = "24") long hours,
            @RequestParam(name = "tenantId", required = false) String tenantId,
            @RequestParam(name = "agentId", required = false) String agentId) {
        security.require(principal, Namespaces.Platform.RESOURCE_READ, null, null);
        return metrics.latency(query(hours, tenantId, agentId, null, null));
    }

    /** 工具维度：调用次数 / 失败率 / 均值与最大耗时（不提供 P95）。 */
    @GetMapping("/tools")
    public List<AgentMetricPort.ToolMetric> tools(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @RequestParam(name = "hours", defaultValue = "24") long hours,
            @RequestParam(name = "tenantId", required = false) String tenantId) {
        security.require(principal, Namespaces.Platform.RESOURCE_READ, null, null);
        return metrics.toolBreakdown(query(hours, tenantId, null, null, null));
    }

    /** 异常 Trace Top-N：超大 token / 循环调用 / 触达轮次上限。 */
    @GetMapping("/anomalies")
    public List<AgentMetricPort.AnomalyTrace> anomalies(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @RequestParam(name = "hours", defaultValue = "24") long hours,
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        security.require(principal, Namespaces.Platform.RESOURCE_READ, null, null);
        return metrics.anomalies(query(hours, null, null, null, null), limit);
    }

    /** 单条 Trace 的指标明细。 */
    @GetMapping("/trace/{traceId}")
    public AgentMetricPort.TraceMetric trace(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @org.springframework.web.bind.annotation.PathVariable("traceId") String traceId) {
        security.require(principal, Namespaces.Platform.RESOURCE_READ, null, null);
        return metrics.findByTraceId(traceId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到该 Trace 的指标"));
    }

    /**
     * 手动触发一条 Run 的指标采集（运维补算入口）。
     *
     * <p>⚠️ 这是<b>补算</b>而非常规路径 —— 正常由 Run 收敛事件驱动。
     * 对未收敛的 Run 返回 409：不是服务故障，是「此刻算不出」。
     */
    @PostMapping("/collect")
    public ResponseEntity<CollectResponse> collect(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @RequestBody CollectRequest request) {
        security.require(principal, Namespaces.Platform.RESOURCE_MANAGE, null, null);
        if (isBlank(request.tenantId()) || isBlank(request.runId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tenantId 与 runId 必填");
        }
        java.util.UUID runId;
        try {
            runId = java.util.UUID.fromString(request.runId().trim());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "runId 不是合法 UUID");
        }
        return collector.collect(request.tenantId().trim(), runId)
                .map(traceId -> ResponseEntity.ok(new CollectResponse(traceId, "COLLECTED")))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(new CollectResponse(null, "NOT_CONVERGED")));
    }

    // ══════════════════════════════════════════════════════════════
    // 录入（回填）
    // ══════════════════════════════════════════════════════════════

    /** 录入抽样标注（L2 黄金基线）。 */
    @PostMapping("/eval-samples")
    public ResponseEntity<Void> recordEvalSample(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @RequestBody EvalSampleRequest request) {
        security.require(principal, Namespaces.Platform.RESOURCE_MANAGE, null, null);
        if (isBlank(request.traceId()) || isBlank(request.tenantId()) || isBlank(request.verdict())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "traceId / tenantId / verdict 必填");
        }
        String verdict = request.verdict().trim().toUpperCase(Locale.ROOT);
        if (!verdict.equals("SUCCESS") && !verdict.equals("PARTIAL") && !verdict.equals("FAIL")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "verdict 取值：SUCCESS / PARTIAL / FAIL");
        }
        metrics.recordEvalSample(new EvalSample(
                request.traceId().trim(), request.tenantId().trim(), verdict,
                request.hallucination(), request.instructionFollowed(),
                request.note(), principal.userId()));
        return ResponseEntity.ok().build();
    }

    /** 录入用户反馈事件（L3 有噪信号 —— 只触发复核，不判劣化）。 */
    @PostMapping("/feedback")
    public ResponseEntity<Void> recordFeedback(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @RequestBody FeedbackRequest request) {
        security.require(principal, Namespaces.Platform.RESOURCE_MANAGE, null, null);
        if (isBlank(request.traceId()) || isBlank(request.tenantId()) || isBlank(request.kind())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "traceId / tenantId / kind 必填");
        }
        FeedbackKind kind;
        try {
            kind = FeedbackKind.valueOf(request.kind().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "kind 取值：THUMB_UP / THUMB_DOWN / RETRY / ABANDON");
        }
        metrics.recordFeedback(new FeedbackEvent(request.traceId().trim(), request.tenantId().trim(), kind));
        return ResponseEntity.ok().build();
    }

    /**
     * 录入风险事件。
     *
     * <p>⚠️ <b>合规红线</b>：请求体只接受 {@code detailDigest}（摘要）。
     * 端点<b>故意不提供任何承载原文的字段</b> —— 一旦接受原文并落库，
     * 就构成二次泄露：「拦截住了」会变成「存下来了」。
     */
    @PostMapping("/risk-events")
    public ResponseEntity<Void> recordRiskEvent(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @RequestBody RiskEventRequest request) {
        security.require(principal, Namespaces.Platform.RESOURCE_MANAGE, null, null);
        if (isBlank(request.traceId()) || isBlank(request.tenantId()) || isBlank(request.eventType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "traceId / tenantId / eventType 必填");
        }
        RiskType type;
        try {
            type = RiskType.valueOf(request.eventType().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "eventType 取值：SAFETY_BLOCK / UNAUTHORIZED_CALL / SENSITIVE_OUTPUT");
        }
        metrics.recordRiskEvent(new RiskEvent(
                request.traceId().trim(), request.tenantId().trim(), type, request.detailDigest()));
        return ResponseEntity.ok().build();
    }

    /** 录入自动终态校验回执（L1）。 */
    @PostMapping("/task-outcomes")
    public ResponseEntity<Void> recordTaskOutcome(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @RequestBody TaskOutcomeRequest request) {
        security.require(principal, Namespaces.Platform.RESOURCE_MANAGE, null, null);
        if (isBlank(request.traceId()) || isBlank(request.tenantId()) || isBlank(request.checkerCode())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "traceId / tenantId / checkerCode 必填");
        }
        metrics.recordTaskOutcome(new TaskOutcome(
                request.traceId().trim(), request.tenantId().trim(), request.checkerCode().trim(),
                request.externalRef(), request.verified(), request.evidenceDigest()));
        return ResponseEntity.ok().build();
    }

    // ══════════════════════════════════════════════════════════════
    // 内部
    // ══════════════════════════════════════════════════════════════

    private MetricQuery query(long hours, String tenantId, String agentId,
                              Integer agentVersion, String scenario) {
        // ⚠️ hours 上限 90 天：明细表扫全量会把库拖垮
        long safeHours = Math.min(Math.max(hours, 1L), 24L * 90L);
        Instant to = Instant.now();
        Instant from = to.minus(safeHours, ChronoUnit.HOURS);
        return new MetricQuery(from, to, tenantId, agentId, agentVersion, scenario).clamp();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** 采集请求。 */
    public record CollectRequest(String tenantId, String runId) {
    }

    /** 采集结果。{@code status=NOT_CONVERGED} 表示 Run 尚未收敛（此刻算不出，非故障）。 */
    public record CollectResponse(String traceId, String status) {
    }

    public record EvalSampleRequest(String traceId, String tenantId, String verdict,
                                    Boolean hallucination, Boolean instructionFollowed, String note) {
    }

    public record FeedbackRequest(String traceId, String tenantId, String kind) {
    }

    /** ⚠️ 只有 {@code detailDigest} —— 刻意没有承载原文的字段。 */
    public record RiskEventRequest(String traceId, String tenantId, String eventType, String detailDigest) {
    }

    public record TaskOutcomeRequest(String traceId, String tenantId, String checkerCode,
                                     String externalRef, boolean verified, String evidenceDigest) {
    }
}
