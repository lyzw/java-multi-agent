package cn.studykid.multiagent.infrastructure.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.application.cap.McpUntrustedOutput;
import cn.studykid.multiagent.core.cap.CapMcpServerDef;
import cn.studykid.multiagent.core.cap.CapToolDef;
import cn.studykid.multiagent.core.config.ToolRiskLevel;
import cn.studykid.multiagent.infrastructure.tool.ToolInputSchemaValidator;
import cn.studykid.multiagent.ports.mcp.McpTransportException;
import cn.studykid.multiagent.ports.mcp.McpTransportPort;
import cn.studykid.multiagent.ports.tool.ToolExecutionException;
import cn.studykid.multiagent.ports.tool.ToolHandler;

/**
 * MCP-backed 工具处理器（07 §5「MCP-backed {@code ToolHandler} → agent-infrastructure」 · §6.5）。
 *
 * <p>⚠️ <b>它与原生工具走完全相同的闸门路径</b>（ADR-011 / 07 §3）：
 * MCP 工具不得有「自己的执行入口」——
 * 一旦 MCP 有独立入口，Schema 校验、执行时重授权、审批、预算、账本、
 * 执行准入、执行意图、结果落盘这八道闸门全部失效。
 *
 * <p>⚠️ <b>输出不可信</b>（§6.5 · P4-T7）：
 * 远端返回值经 {@link McpUntrustedOutput} 包裹后才交给上层，
 * 带显式数据边界标记并限体积。
 *
 * <p>⚠️ <b>失败语义必须区分「确定失败」与「结果未知」</b>：
 * <ul>
 *   <li>请求<b>没有发出去</b>（SSRF 拦截 / 凭据不可用 / 熔断打开 / 限流 / 连接失败）
 *       → 确定失败，可重试；</li>
 *   <li>请求<b>已经发出</b>但响应不可信（超时 / 协议错误）
 *       → <b>结果未知</b>，副作用可能已发生，必须进 UNKNOWN 对账，<b>禁止盲目重试</b>
 *       （P0-D03 §4 红线 7）。</li>
 * </ul>
 * 把「可能已生效」误报成「确定失败」而让上游重试，就是重复副作用的制造机。
 */
public class McpBackedToolHandler implements ToolHandler {

    private static final Logger log = LoggerFactory.getLogger(McpBackedToolHandler.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CapToolDef toolDef;
    private final CapMcpServerDef server;
    private final McpTransportPort transport;
    private final int maxOutputBytes;

    public McpBackedToolHandler(@NonNull CapToolDef toolDef,
                                @NonNull CapMcpServerDef server,
                                @NonNull McpTransportPort transport) {
        this(toolDef, server, transport, McpUntrustedOutput.DEFAULT_MAX_BYTES);
    }

    public McpBackedToolHandler(@NonNull CapToolDef toolDef,
                                @NonNull CapMcpServerDef server,
                                @NonNull McpTransportPort transport,
                                int maxOutputBytes) {
        this.toolDef = toolDef;
        this.server = server;
        this.transport = transport;
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
        //    MCP 的远端 schema 来自清单（可变），**不可信**；
        //    这里以版本化的 cap_tool_def.inputSchema 为准（§6.1 版本行不可变）。
        //    ⚠️ 校验实现与 HTTP-backed 工具共用一处（ToolInputSchemaValidator）——
        //    两处各写一遍，迟早漂移，而漂移的方向通常是
        //    「某一处忘了校验 additionalProperties:false」，那会让未声明的参数被放行。
        ToolInputSchemaValidator.validate(toolDef.inputSchema(), arguments);
    }

    @Override
    public @NonNull ToolResult execute(@NonNull ToolExecutionContext context,
                                        @NonNull Map<String, Object> arguments) {
        String op = toolDef.mcpToolName();
        if (op == null || op.isBlank()) {
            throw ToolExecutionException.definitelyFailed(
                    "MCP 工具未绑定远端操作: " + toolDef.id());
        }
        McpTransportPort.McpToolResult result;
        try {
            result = transport.callTool(server, op, arguments);
        } catch (McpTransportException e) {
            throw translate(e, toolDef.id(), op);
        }

        // ⚠️ 输出不可信：包裹后才交给上层（P4-T7）
        McpUntrustedOutput.Guarded guarded = McpUntrustedOutput.guard(
                result.output(), server.id(), op, maxOutputBytes);
        if (guarded.truncated()) {
            log.warn("MCP 输出超出体积上限已截断: tool={}, server={}, op={}, 原始长度={}",
                    toolDef.id(), server.id(), op, guarded.originalLength());
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("mcpUntrusted", Boolean.TRUE);
        metadata.put("mcpServerId", server.id());
        metadata.put("mcpOp", op);
        metadata.put("mcpIsError", result.isError());
        if (guarded.truncated()) {
            metadata.put("mcpTruncated", Boolean.TRUE);
        }
        return new ToolResult(guarded.payload(), metadata);
    }

    /**
     * 把传输异常翻译成「确定失败」或「结果未知」。
     *
     * @param e    传输异常
     * @param tool 工具 id
     * @param op   远端操作名
     * @return 工具执行异常
     */
    private @NonNull ToolExecutionException translate(@NonNull McpTransportException e,
                                                      @NonNull String tool,
                                                      @NonNull String op) {
        String message = "MCP 调用失败: tool=" + tool + ", op=" + op
                + ", kind=" + e.kind();
        return switch (e.kind()) {
            // ⚠️ 请求**没有发出去** → 确定失败，可重试
            case SSRF_BLOCKED, CIRCUIT_OPEN, RATE_LIMITED, CREDENTIAL_UNAVAILABLE,
                 CONNECT_FAILED -> ToolExecutionException.definitelyFailed(message, e);
            // ⚠️ 请求**已经发出**但响应不可信 → 结果未知，必须对账
            case TIMEOUT, PROTOCOL_ERROR -> ToolExecutionException.unknownResult(message, e);
            case OTHER -> ToolExecutionException.unknownResult(message, e);
        };
    }
}
