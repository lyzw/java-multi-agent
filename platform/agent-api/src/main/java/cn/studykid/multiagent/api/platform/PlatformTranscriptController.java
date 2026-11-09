package cn.studykid.multiagent.api.platform;

import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import cn.studykid.multiagent.api.run.TranscriptController;
import cn.studykid.multiagent.api.security.PlatformSecurity;
import cn.studykid.multiagent.application.replay.TraceReplayService;
import cn.studykid.multiagent.ports.persistence.TraceReplayPort;
import cn.studykid.multiagent.ports.security.IdentityDomains;
import cn.studykid.multiagent.ports.security.Namespaces;
import cn.studykid.multiagent.ports.security.PlatformPrincipal;

/**
 * 平台侧对话回放端点（P1 · G1 闭环）。
 *
 * <p><b>用途</b>：{@code GET /api/platform/v1/runs/{runId}/transcript} ——
 * 管理员/审查者跨租户回放一次辅导（「这次辅导为什么被判为低质量」）。
 *
 * <p><b>⚠️ 与租户侧的关键差异</b>：
 * <ul>
 *   <li><b>平台令牌不绑租户</b> —— 优先接受显式 {@code tenantId}
 *       （审查队列 / 指标明细都带此字段）；未传时按 runId 从平台域指标宽表反查
 *       （{@link TraceReplayPort#resolveTenantId}），再以该租户身份读取；</li>
 *   <li><b>必须过平台权限闸门</b> —— {@code platform.resource.read}，
 *       与指标端点同款权限码，<b>不新增权限</b>；</li>
 *   <li><b>受众是 ADMIN</b> —— 完整时间线（含 TOOL 消息）。</li>
 * </ul>
 *
 * <p>⚠️ <b>2026-11-08 修复的功能空转</b>：旧实现未绑租户直查
 * {@code public.run}（FORCE RLS，应用角色 NOBYPASSRLS）→ 恒 0 行
 * → 平台回放端点对所有 Run 一律 404。现在反查改走平台域宽表，
 * 并支持显式 tenantId 兜底「尚未采集指标」的 Run。
 * 反查失败时抛带诊断 reason 的 404；⚠️ 全局 {@code ApiExceptionHandler}
 * 会对 404 统一返回「资源不存在」（防归属信息泄露），故这段诊断<b>不会透到前端</b> ——
 * 可操作指引由 admin-console 的回放抽屉按场景补齐（「从审查队列进入会自动带 tenantId」）。
 * 服务端 reason 仍保留，便于日志排障与单测断言。
 *
 * <p><b>⚠️ 为什么不复用 {@code PlatformAgentMetricsController} 的响应</b>：
 * 指标端点<b>刻意不返回任何对话内容</b>（「效果指标看的是跑得好不好，不是说了什么」）。
 * 回放端点是<b>另一类能力</b>（内容级复盘），必须有独立的权限语义与独立端点，
 * 不能让指标端点「顺便」带上内容。
 */
@RestController
@RequestMapping("/api/platform/v1/runs")
public class PlatformTranscriptController {

    private final PlatformSecurity security;
    private final TraceReplayService replay;
    private final TraceReplayPort replayPort;

    public PlatformTranscriptController(PlatformSecurity security,
                                        TraceReplayService replay,
                                        TraceReplayPort replayPort) {
        this.security = security;
        this.replay = replay;
        this.replayPort = replayPort;
    }

    /**
     * 跨租户回放一次辅导。
     *
     * @param principal      平台主体（由拦截器注入）
     * @param runId          Run 标识
     * @param tenantIdParam  可选租户标识；未采集指标的 Run 必须由调用方显式给出
     * @return 回放记录
     */
    @GetMapping("/{runId}/transcript")
    public TranscriptController.TranscriptResponse transcript(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @PathVariable UUID runId,
            @RequestParam(name = "tenantId", required = false) String tenantIdParam) {
        // ⚠️ 授权必须最先：否则无权限主体能借 404/403 差异枚举全平台 Run。
        security.require(principal, Namespaces.Platform.RESOURCE_READ, null, null);

        String tenantId;
        if (tenantIdParam != null && !tenantIdParam.isBlank()) {
            // 显式租户：审查队列 / 指标明细都带此字段，覆盖「指标尚未采集」的 Run。
            // 先校验 UUID 形态，避免非法值下沉到 SQL 变成 500。
            try {
                tenantId = UUID.fromString(tenantIdParam.trim()).toString();
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "tenantId 不是合法的 UUID");
            }
        } else {
            // 平台域宽表反查（无 RLS）。查不到时**如实说明原因**：
            // 这可能是 Run 尚未被采集，也可能确实不存在；绝不能伪装成「Run 不存在」。
            tenantId = replayPort.resolveTenantId(runId.toString())
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND,
                            "无法仅凭 runId 定位所属租户（该 Run 可能尚未采集指标或不存在）。"
                                    + "请显式传 tenantId=...（审查队列 / 指标明细中都有）"));
        }

        TraceReplayService.Transcript transcript =
                replay.buildTranscript(tenantId, runId.toString());
        if (transcript == null) {
            // 「不存在」与「不属于该租户」不区分（不泄露归属信息）
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "这次运行不存在");
        }
        return TranscriptController.TranscriptResponse.of(
                transcript, TraceReplayService.Audience.ADMIN);
    }

    /**
     * 读取一次工具调用的原始详情（TOOL 消息下钻 · 详细设计 §8.3）。
     *
     * <p>⚠️ <b>为什么是独立端点而不是 transcript 的一部分</b>：
     * transcript 是「这次辅导说了什么」，而 {@code tool_operation} 是「工具被怎么调用的」——
     * 后者含<b>参数原文</b>，敏感度高一档（工具参数常含题面、检索 query）。
     * 混在 transcript 里会让「读回放」默认就拉到参数原文，
     * 而审查者多数时候并不需要它。分开后，「看对话」与「查工具入参」是两次不同的授权动作。
     *
     * <p>⚠️ <b>租户标识必须显式给出</b>：{@code tool_operation} 是 FORCE RLS 的租户域表，
     * 只有 (tenantId, operationId) 才能命中；跨租户查恒 0 行 ⇒ 表现为 404，不泄露归属。
     * 本端点<b>不</b>像 transcript 那样从 runId 反查租户 —— operationId 不是 Run 标识，
     * 没有可靠的租户反查路径。
     *
     * @param principal      平台主体（由拦截器注入）
     * @param operationId    工具操作标识
     * @param tenantIdParam  所属租户标识（必填）
     * @return 工具调用详情（参数/返回原文）
     */
    @GetMapping("/tool-operations/{operationId}")
    public ToolOperationResponse toolOperation(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @PathVariable UUID operationId,
            @RequestParam(name = "tenantId") String tenantIdParam) {
        // ⚠️ 授权必须最先：无权限主体不能靠 404/403 差异枚举工具调用。
        security.require(principal, Namespaces.Platform.RESOURCE_READ, null, null);

        String tenantId;
        try {
            tenantId = UUID.fromString(tenantIdParam.trim()).toString();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "tenantId 不是合法的 UUID");
        }

        Map<String, Object> detail = replay.toolOperation(tenantId, operationId.toString());
        if (detail == null) {
            // 「不存在」与「不属于该租户」不区分（与 transcript 端点同口径）
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "这次工具调用不存在");
        }
        return new ToolOperationResponse(operationId.toString(), detail);
    }

    /**
     * 工具调用详情响应。
     *
     * @param operationId 工具操作标识
     * @param detail      原始详情（字段与 {@code tool_operation} 表列同名）
     */
    public record ToolOperationResponse(@NonNull String operationId,
                                        @NonNull Map<String, Object> detail) {
    }
}
