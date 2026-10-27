package cn.studykid.multiagent.api.metrics;

import java.util.Locale;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import cn.studykid.multiagent.api.run.RunController;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.metrics.AgentMetricPort;
import cn.studykid.multiagent.ports.metrics.TraceFactPort;

/**
 * 租户侧用户反馈录入端点（P7-3「tenant 反馈按钮」+ P7-4「前端反馈埋点」的服务端落点）。
 *
 * <p><b>为何需要这个端点</b>：平台侧的
 * {@code POST /api/platform/v1/metrics/agent/feedback} 需要
 * {@code platform.resource.manage} 权限（平台令牌），tenant 前台持有的租户令牌直调必 403。
 * 若只保留平台端点，前端的「点赞/点踩/重试/放弃」按钮就是<b>死的</b>。
 *
 * <p><b>为什么收 runId 而不是 traceId</b>：浏览器知道的是「这次辅导」（runId），
 * 内部的 {@code trace_id} 是服务端派生事实（run.trace_id → 委派链路 → correlation_id 降级）。
 * 让前端回传 traceId 等于让它**断言**一个服务端才掌握的事实——
 * 既可能填错（反馈挂不上统计单元），也可能被伪造（挂到别的租户的 trace 上）。
 * 故前端只给 runId，traceId 由服务端解析。
 *
 * <p><b>安全边界（与平台侧端点的关键差异）</b>：
 * <ul>
 *   <li><b>不接受请求体内的 tenantId</b> —— 请求体只有 {@code runId} 与 {@code kind}。
 *       {@code tenantId} 一律由<b>已验签令牌</b>经
 *       {@link RunController.CurrentPrincipal#context()} 派生。</li>
 *   <li><b>runId 的解析带租户谓词</b> —— 见 {@link TraceFactPort#resolveTraceId}：
 *       跨租户的 runId 解析不到 traceId，表现为 404（不泄露归属信息）。</li>
 *   <li><b>无令牌即 401</b> —— {@code context()} 在缺令牌/令牌无效时抛
 *       {@code UnauthenticatedException}，由 {@code ApiExceptionHandler} 统一映射 401，
 *       不会退化为「默认租户」。</li>
 *   <li><b>不写原文</b> —— 反馈只落 {@code kind} 枚举 + traceId，不落自由文本，
 *       与 L3「有噪信号」定位一致。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/metrics")
public class TenantFeedbackController {

    private final AgentMetricPort metrics;
    private final TraceFactPort facts;

    /** 与 {@code RunController} 共用同一可信主体 Bean；装配缺失则启动即失败（fail-fast）。 */
    private final RunController.CurrentPrincipal currentPrincipal;

    public TenantFeedbackController(
            AgentMetricPort metrics,
            TraceFactPort facts,
            RunController.CurrentPrincipal currentPrincipal) {
        this.metrics = metrics;
        this.facts = facts;
        this.currentPrincipal = currentPrincipal;
    }

    /**
     * 录入一条用户反馈（L3 有噪信号 —— 只触发复核，不判劣化）。
     *
     * @param request 仅含 {@code runId} 与 {@code kind}；<b>刻意不含 tenantId</b>
     */
    @PostMapping("/feedback")
    public ResponseEntity<Void> recordFeedback(@RequestBody FeedbackRequest request) {
        TenantContext context = currentPrincipal.context();

        String rawRunId = request.runId() == null ? "" : request.runId().trim();
        String rawKind = request.kind() == null ? "" : request.kind().trim();
        if (rawRunId.isEmpty() || rawKind.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "runId / kind 必填");
        }

        UUID runId;
        try {
            runId = UUID.fromString(rawRunId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "runId 不是合法的标识");
        }

        AgentMetricPort.FeedbackEvent.FeedbackKind kind;
        try {
            kind = AgentMetricPort.FeedbackEvent.FeedbackKind.valueOf(
                    rawKind.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "kind 取值：THUMB_UP / THUMB_DOWN / RETRY / ABANDON");
        }

        // ⚠️ traceId 由服务端解析（带租户谓词）：
        //    解析不到 = 该 run 不存在、不属于本租户，或尚未产生可反馈的会话标识。
        //    三者**不区分**（与全站 404 口径一致：不泄露归属信息）。
        String traceId = facts.resolveTraceId(context.tenantId(), runId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "这次运行不存在，或尚未产生可反馈的记录"));

        metrics.recordFeedback(new AgentMetricPort.FeedbackEvent(
                traceId, context.tenantId(), kind));
        return ResponseEntity.ok().build();
    }

    /**
     * 请求体。
     *
     * <p>⚠️ <b>刻意没有 tenantId 字段</b>：租户身份只能来自已验签令牌，
     * 请求体携带的租户标识一律视为不可信输入。
     */
    public record FeedbackRequest(String runId, String kind) {
    }
}
