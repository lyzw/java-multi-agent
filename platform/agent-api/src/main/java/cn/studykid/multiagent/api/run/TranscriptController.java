package cn.studykid.multiagent.api.run;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import cn.studykid.multiagent.application.replay.TraceReplayService;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.TraceReplayPort;

/**
 * 租户侧对话回放端点（P1 · G1 闭环）。
 *
 * <p><b>用途</b>：{@code GET /api/runs/{runId}/transcript} ——
 * 让「这次辅导模型究竟说了什么」第一次变得可见。
 *
 * <p><b>⚠️ 安全边界</b>（与 {@code TenantFeedbackController} 同款范式）：
 * <ul>
 *   <li><b>不接受请求体内的 tenantId</b> —— 租户一律由<b>已验签令牌</b>经
 *       {@link RunController.CurrentPrincipal#context()} 派生；</li>
 *   <li><b>runId 的解析带租户谓词</b> —— 跨租户的 runId 解析不到任何东西，
 *       表现为 404（<b>不泄露归属信息</b>）；</li>
 *   <li><b>无令牌即 401</b> —— {@code context()} 在缺令牌/令牌无效时抛
 *       {@code UnauthenticatedException}，不会退化为「默认租户」。</li>
 *   <li><b>学生端不展示工具内部消息</b> —— 见 {@link TraceReplayService.Audience}。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/runs")
public class TranscriptController {

    private final TraceReplayService replay;

    /** 与 {@code RunController} 共用同一可信主体 Bean；装配缺失则启动即失败（fail-fast）。 */
    private final RunController.CurrentPrincipal currentPrincipal;

    public TranscriptController(TraceReplayService replay,
                                RunController.CurrentPrincipal currentPrincipal) {
        this.replay = replay;
        this.currentPrincipal = currentPrincipal;
    }

    /**
     * 回放一次辅导的完整对话时间线。
     *
     * @param runId Run 标识
     * @return 回放记录
     */
    @GetMapping("/{runId}/transcript")
    public TranscriptResponse transcript(@PathVariable UUID runId) {
        TenantContext context = currentPrincipal.context();
        TraceReplayService.Transcript transcript =
                replay.buildTranscript(context.tenantId(), runId.toString());
        if (transcript == null) {
            // ⚠️ 「不存在」与「不属于本租户」**不区分**（不泄露归属信息）
            throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,
                    "这次运行不存在");
        }
        return TranscriptResponse.of(transcript, TraceReplayService.Audience.STUDENT);
    }

    /**
     * 回放响应。
     *
     * <p>⚠️ <b>字段命名与「未采集」纪律</b>：{@code tokens} 为 {@code null}
     * 表示「没有用量数据」，<b>不是</b>「这次没花钱」——
     * 二者在外部无法区分，必须让前端如实呈现。
     */
    public record TranscriptResponse(
            String runId,
            String traceId,
            String state,
            String createdAt,
            String completedAt,
            Long durationMs,
            Map<String, Object> snapshot,
            List<Message> messages,
            List<Step> steps,
            TokenSummary tokens
    ) {

        /**
         * 按受众组装响应。
         *
         * <p>⚠️ <b>public 而非包级私有</b>：平台侧端点在另一个包
         * （{@code api.platform}）复用本组装逻辑 ——
         * 两端点的响应结构必须一致，否则同一个 Run 在两侧看到的东西会不同，
         * 审查时无从判断差异来自数据还是来自端点。
         */
        public static TranscriptResponse of(TraceReplayService.Transcript transcript,
                                            TraceReplayService.Audience audience) {
            TraceReplayPort.RunMeta run = transcript.run();
            return new TranscriptResponse(
                    run.runId(),
                    run.traceId(),
                    run.state(),
                    run.createdAt() == null ? null : run.createdAt().toString(),
                    run.completedAt() == null ? null : run.completedAt().toString(),
                    run.durationMs(),
                    transcript.snapshot(),
                    transcript.messagesFor(audience).stream().map(Message::of).toList(),
                    transcript.steps().stream().map(Step::of).toList(),
                    transcript.tokens() == null ? null : new TokenSummary(
                            transcript.tokens().inputTokens(),
                            transcript.tokens().outputTokens(),
                            transcript.tokens().costAmount()));
        }
    }

    /** 一条回放消息。 */
    public record Message(
            long seq,
            String role,
            Integer stepNumber,
            Map<String, Object> content,
            String toolOperationId,
            String modelId,
            Long inputTokens,
            Long outputTokens,
            Long latencyMs,
            String createdAt
    ) {
        static Message of(TraceReplayPort.MessageView view) {
            return new Message(
                    view.seq(),
                    view.role(),
                    view.stepNumber(),
                    view.content(),
                    view.toolOperationId(),
                    view.modelId(),
                    view.inputTokens(),
                    view.outputTokens(),
                    view.latencyMs(),
                    view.createdAt().toString());
        }
    }

    /** 执行步骤。 */
    public record Step(int stepNumber, String type, Long durationMs) {
        static Step of(TraceReplayPort.StepView view) {
            return new Step(view.stepNumber(), view.stepType(), view.durationMs());
        }
    }

    /** token 与成本汇总。 */
    public record TokenSummary(long input, long output, java.math.BigDecimal cost) {
    }
}
