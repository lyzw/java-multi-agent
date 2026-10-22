package cn.studykid.multiagent.ports.mcp;

import java.util.Set;

import org.jspecify.annotations.NonNull;

/**
 * MCP 固定清单端口（§4.4 {@code ManifestOps}）。
 *
 * <p>{@code ManifestOps} = 「当前已发布、经校验的固定 MCP 工具清单」。
 * 它是 §4.4 交集运算的<b>基准集合</b>：
 * <pre>
 *   Server.exposedToolAllowlist = null → ServerAllowedOps := ManifestOps
 *   Server.exposedToolAllowlist = []   → ServerAllowedOps := ∅
 *   Server.exposedToolAllowlist = L   → ServerAllowedOps := L ∩ ManifestOps
 * </pre>
 *
 * <p>⚠️ <b>本端口属 P4 范围</b>（MCP 传输适配与清单采集）。
 * P1 阶段由测试桩提供；生产实现落地前，MCP 能力一律视为<b>不可用</b>（清单为空），
 * 即 fail-closed —— 而不是「未采集到就当作全部允许」。
 */
public interface McpManifestPort {

    /**
     * 读取某 MCP Server 当前的固定工具清单。
     *
     * @param serverId MCP Server 逻辑 id
     * @return 当前已发布的操作清单；无清单或未接入时为<b>空集</b>（不得返回 {@code null}）
     */
    @NonNull Set<String> currentManifestOps(@NonNull String serverId);
}
