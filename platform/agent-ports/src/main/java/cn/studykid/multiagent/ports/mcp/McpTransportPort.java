package cn.studykid.multiagent.ports.mcp;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.cap.CapMcpServerDef;

/**
 * MCP 传输端口（§6.5 · 07 §5「MCP-backed {@code ToolHandler} → agent-infrastructure」）。
 *
 * <p>⚠️ <b>V1 仅支持 http / sse</b>（§4.2 / §6.5 末句 / N7）：
 * {@code stdio} 会在服务端拉起子进程，其可执行文件白名单与命令拼接管控
 * 属 06 §5 的另一套要求，V1 未实现 → <b>本端口的实现不得接受 stdio</b>。
 *
 * <p>⚠️ <b>本端口是「唯一的外部副作用出口」</b>：
 * 所有 MCP 远端调用都必须经本端口，从而在一处集中施加
 * SSRF 校验（06 §5）、超时、限流、熔断、响应体积上限与凭据脱敏。
 * 直连 HTTP 客户端绕过本端口，等于把这层管控全部作废。
 */
public interface McpTransportPort {

    /**
     * 采集 MCP Server 的固定工具清单（{@code ManifestOps} 的来源）。
     *
     * @param server MCP Server 定义（必须已发布且 transport 为 http/sse）
     * @return 工具清单（按名称升序）；不可用时抛异常（<b>不得返回空集</b>——
     *         空集与「采集成功但无工具」不可区分，会把「传输故障」误读成「该 server 无工具」）
     * @throws McpTransportException 传输失败（含 SSRF 拦截、超时、熔断打开、协议错误）
     */
    @NonNull List<McpToolInfo> listTools(@NonNull CapMcpServerDef server);

    /**
     * 调用一个 MCP 远端操作。
     *
     * @param server    MCP Server 定义
     * @param op        远端操作名
     * @param arguments 调用参数
     * @return 调用结果
     * @throws McpTransportException 传输失败
     */
    @NonNull McpToolResult callTool(@NonNull CapMcpServerDef server,
                                    @NonNull String op,
                                    @NonNull Map<String, Object> arguments);

    /**
     * MCP 远端工具的描述。
     *
     * @param name        工具名（MCP 侧原名）
     * @param description 描述（可能为空）
     * @param inputSchema 输入 JSON Schema（JSON 文本；可能为空）
     */
    record McpToolInfo(@NonNull String name,
                       @Nullable String description,
                       @Nullable String inputSchema) {
        public McpToolInfo {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("MCP 工具名不能为空");
            }
        }
    }

    /**
     * MCP 调用结果。
     *
     * <p>⚠️ <b>内容不可信</b>（§6.5「输出不可信」）：
     * 远端返回的文本是<b>外部数据</b>，不是系统指令。
     * 本记录只是「搬运」，可信化处理由调用方在 Gate 边界完成。
     *
     * @param output        文本输出
     * @param structured    结构化输出（可能为空）
     * @param isError       远端是否报告了错误
     */
    record McpToolResult(@NonNull String output,
                         @Nullable Map<String, Object> structured,
                         boolean isError) {
        public McpToolResult {
            if (output == null) {
                output = "";
            }
        }

        public static @NonNull McpToolResult of(@NonNull String output) {
            return new McpToolResult(output, null, false);
        }
    }
}
