package cn.studykid.multiagent.infrastructure.mcp;

import java.util.Set;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.ports.mcp.McpManifestPort;

/**
 * 未接入的 MCP 清单端口（fail-closed 兜底）。
 *
 * <p>⚠️ <b>P4 落地前的占位实现</b>：MCP 固定清单的采集与校验属 P4（§6.5）。
 * 在 P4 接入真实适配器之前，本实现一律返回<b>不可用</b>——
 * 于是 §4.4 的 {@code ServerAllowedOps := ManifestOps} 得到空集，
 * 任何 MCP 操作都<b>不被允许</b>。
 *
 * <p>⚠️ <b>为什么不能返回「全部允许」</b>：未采集到清单就当作全量放行，
 * 等于把「没接入 MCP 适配器」变成「MCP 可调用任意操作」——
 * 这是把「缺配置」误读成「宽松配置」，是最典型的 fail-open。
 *
 * <p>⚠️ <b>为什么标为「不可用」而不是「可用但为空」</b>：
 * 「不可用」让 §5.8 步骤 3 给出 {@code MCP_TOOL_DRIFTED}（读不到当前清单，
 * 无法确认操作仍在清单内）；「可用但为空」会被读成「这个 server 确实没有工具」——
 * 两种解释的运维处置完全不同，不能混。
 */
public class UnconfiguredMcpManifestPort implements McpManifestPort {

    @Override
    public @NonNull ManifestSnapshot manifest(@NonNull String serverId) {
        return ManifestSnapshot.unavailable("MCP 适配器未接入（P4 前的 fail-closed 占位）");
    }

    /**
     * @param serverId MCP Server 逻辑 id
     * @return 空集（不可用）
     */
    @Override
    public @NonNull Set<String> currentManifestOps(@NonNull String serverId) {
        return Set.of();
    }
}
