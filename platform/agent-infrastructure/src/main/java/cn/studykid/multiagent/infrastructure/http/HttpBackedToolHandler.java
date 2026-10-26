package cn.studykid.multiagent.infrastructure.http;

import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.application.cap.HttpUntrustedOutput;
import cn.studykid.multiagent.core.cap.CapHttpToolConfig;
import cn.studykid.multiagent.core.cap.CapToolDef;
import cn.studykid.multiagent.core.config.ToolRiskLevel;
import cn.studykid.multiagent.infrastructure.tool.ToolInputSchemaValidator;
import cn.studykid.multiagent.ports.http.HttpToolException;
import cn.studykid.multiagent.ports.tool.ToolExecutionException;
import cn.studykid.multiagent.ports.tool.ToolHandler;

/**
 * HTTP-backed 工具处理器（第三十七批 · 设计文档 §13.1）。
 *
 * <p>⚠️ <b>它与原生工具、MCP-backed 工具走完全相同的闸门路径</b>（ADR-011 / 07 §3）：
 * HTTP 工具不得有「自己的执行入口」——
 * 一旦 HTTP 有独立入口，Schema 校验、执行时重授权、审批、预算、账本、
 * 执行准入、执行意图、结果落盘这八道闸门全部失效。
 *
 * <p>⚠️ <b>输出不可信</b>：响应体经 {@link HttpUntrustedOutput} 包裹后才交给上层，
 * 带显式数据边界标记并限体积。HTTP 响应比 MCP 返回值更不可信 ——
 * 目标可以指向互联网上的任意站点。
 *
 * <p>⚠️ <b>失败语义必须区分「确定失败」与「结果未知」</b>：
 * <ul>
 *   <li>请求<b>没有发出去</b>（出站拦截 / 配置非法 / 连接失败）→ 确定失败，可重试；</li>
 *   <li>请求<b>已经发出</b>但结果不可信（超时 / 协议错误 / 响应超限 / 重定向超限）
 *       → <b>结果未知</b>，副作用可能已发生，必须进 UNKNOWN 对账，
 *       <b>禁止盲目重试</b>（P0-D03 §4 红线 7）。</li>
 * </ul>
 * ⚠️ 但<b>无副作用的方法（GET / HEAD）例外</b>：读操作没有副作用可对账，
 * 把它们挂进 UNKNOWN 只会制造无意义的人工工单。
 */
public class HttpBackedToolHandler implements ToolHandler {

    private static final Logger log = LoggerFactory.getLogger(HttpBackedToolHandler.class);

    private final CapToolDef toolDef;
    private final HttpToolExecutor executor;
    private final int maxOutputBytes;

    public HttpBackedToolHandler(@NonNull CapToolDef toolDef,
                                 @NonNull HttpToolExecutor executor) {
        this(toolDef, executor, HttpUntrustedOutput.DEFAULT_MAX_BYTES);
    }

    public HttpBackedToolHandler(@NonNull CapToolDef toolDef,
                                 @NonNull HttpToolExecutor executor,
                                 int maxOutputBytes) {
        this.toolDef = toolDef;
        this.executor = executor;
        this.maxOutputBytes = maxOutputBytes;
    }

    @Override
    public @NonNull String toolName() {
        return toolDef.id();
    }

    @Override
    public @NonNull String inputSchema() {
        return toolDef.inputSchema();
    }

    @Override
    public @NonNull ToolRiskLevel riskLevel() {
        return ToolRiskLevel.valueOf(toolDef.riskLevel().name());
    }

    @Override
    public void validate(@NonNull Map<String, Object> arguments) {
        // ⚠️ Schema 错误必须拒绝，不得「尽力解析」（ToolHandler 契约）。
        //    以版本化的 cap_tool_def.inputSchema 为准（§6.1 版本行不可变）——
        //    HTTP 目标不像 MCP 那样有「远端清单」，这里的 Schema 就是唯一口径。
        ToolInputSchemaValidator.validate(toolDef.inputSchema(), arguments);
    }

    @Override
    public @NonNull ToolResult execute(@NonNull ToolExecutionContext context,
                                        @NonNull Map<String, Object> arguments) {
        CapHttpToolConfig config = toolDef.httpConfig();
        if (config == null) {
            // ⚠️ 走到这里说明「kind=http 但没有 HTTP 配置」——
            //    配置期本应拒掉（ConfigValidationGate），这里再挡一道（fail-closed）。
            throw ToolExecutionException.definitelyFailed(
                    "HTTP 工具缺少 HTTP 配置（方法 / URL / 超时）: " + toolDef.id());
        }
        HttpToolResponse response;
        try {
            response = executor.execute(toolDef.id(), config, arguments);
        } catch (HttpToolException e) {
            throw translate(e, config.method(), toolDef.id());
        }

        HttpUntrustedOutput.Guarded guarded = HttpUntrustedOutput.guard(
                response.body(), toolDef.id(), maxOutputBytes);
        if (guarded.truncated()) {
            log.warn("HTTP 响应超出体积上限已截断: tool={}, 原始长度={}",
                    toolDef.id(), guarded.originalLength());
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("httpUntrusted", Boolean.TRUE);
        metadata.put("httpStatus", response.status());
        metadata.put("httpUrl", response.finalUrl());
        if (response.contentType() != null) {
            metadata.put("httpContentType", response.contentType());
        }
        if (guarded.truncated()) {
            metadata.put("httpTruncated", Boolean.TRUE);
        }
        return new ToolResult(guarded.payload(), metadata);
    }

    /**
     * 把出站异常翻译成「确定失败」或「结果未知」。
     *
     * <p>⚠️ <b>static 且包可见，是为了让这条判定能被直接单测</b>：
     * 「确定失败 / 结果未知」的分类是 P0-D03 §4 红线 7 的落点 ——
     * 把它误判成「可重试」，就会在上游造出重复副作用。
     *
     * @param e      出站异常
     * @param method HTTP 方法（决定是否有副作用）
     * @param tool   工具 id
     * @return 工具执行异常
     */
    static @NonNull ToolExecutionException translate(@NonNull HttpToolException e,
                                                     @NonNull String method,
                                                     @NonNull String tool) {
        String message = "HTTP 工具调用失败: tool=" + tool + ", kind=" + e.kind()
                + ", msg=" + e.getMessage();
        boolean sideEffectFree = "GET".equals(method) || "HEAD".equals(method);
        return switch (e.kind()) {
            // ⚠️ 请求**没有发出去** → 确定失败，可重试
            case SSRF_BLOCKED, CONFIG_INVALID, CONNECT_FAILED ->
                    ToolExecutionException.definitelyFailed(message, e);
            // ⚠️ 请求**已经发出** → 结果未知（除非是无副作用的读操作）
            case TIMEOUT, PROTOCOL_ERROR, RESPONSE_TOO_LARGE, TOO_MANY_REDIRECTS ->
                    sideEffectFree
                            ? ToolExecutionException.definitelyFailed(message, e)
                            : ToolExecutionException.unknownResult(message, e);
        };
    }
}
