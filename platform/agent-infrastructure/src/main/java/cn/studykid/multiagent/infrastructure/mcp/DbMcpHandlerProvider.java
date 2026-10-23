package cn.studykid.multiagent.infrastructure.mcp;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.core.cap.CapMcpServerDef;
import cn.studykid.multiagent.core.cap.CapObjectType;
import cn.studykid.multiagent.core.cap.CapToolDef;
import cn.studykid.multiagent.ports.mcp.McpHandlerProvider;
import cn.studykid.multiagent.ports.mcp.McpTransportPort;
import cn.studykid.multiagent.ports.persistence.CapMcpServerDefPort;
import cn.studykid.multiagent.ports.persistence.CapToolDefPort;
import cn.studykid.multiagent.ports.persistence.PublishedVersionPort;
import cn.studykid.multiagent.ports.tool.ToolHandler;

/**
 * 从权威配置解析 MCP-backed 工具处理器（P4 · ADR-011 / 07 §3）。
 *
 * <p>⚠️ <b>版本口径必须与闭包解析一致</b>：
 * 本类用 {@code PublishedVersionPort}（当前发布指针）解析版本，
 * 与 {@code PermissionResolver} 完全相同。
 * 若这里改用「最新已发布行」，处理器拿到的风险等级 / 输入 Schema
 * 可能与闭包里固化的那一版<b>不是同一版</b> ——
 * 于是「授权按 A 版判定、执行按 B 版跑」，审批绑定的是另一个风险等级。
 *
 * <p>⚠️ <b>解析不到就是解析不到</b>：不猜、不回退到「按工具名直接调用远端」。
 * MCP 工具的可达性由 §5.8 的操作级复核最终裁定，
 * 本类只负责「把执行器准备好」。
 */
public class DbMcpHandlerProvider implements McpHandlerProvider {

    private static final Logger log = LoggerFactory.getLogger(DbMcpHandlerProvider.class);

    private final CapToolDefPort toolDefs;
    private final CapMcpServerDefPort serverDefs;
    private final PublishedVersionPort publishedVersions;
    private final McpTransportPort transport;
    private final Map<String, McpBackedToolHandler> cache = new ConcurrentHashMap<>();

    public DbMcpHandlerProvider(@NonNull CapToolDefPort toolDefs,
                                 @NonNull CapMcpServerDefPort serverDefs,
                                 @NonNull PublishedVersionPort publishedVersions,
                                 @NonNull McpTransportPort transport) {
        this.toolDefs = toolDefs;
        this.serverDefs = serverDefs;
        this.publishedVersions = publishedVersions;
        this.transport = transport;
    }

    @Override
    public @NonNull Optional<ToolHandler> resolve(@NonNull String toolName) {
        Optional<Integer> toolVersion =
                publishedVersions.currentVersion(CapObjectType.TOOL, toolName);
        if (toolVersion.isEmpty()) {
            return Optional.empty();
        }
        Optional<CapToolDef> tool = toolDefs.find(toolName, toolVersion.get());
        if (tool.isEmpty()) {
            return Optional.empty();
        }
        String serverId = tool.get().mcpServerId();
        String op = tool.get().mcpToolName();
        if (serverId == null || serverId.isBlank() || op == null || op.isBlank()) {
            // ⚠️ 不是 MCP 工具 → 本类不处理（原生工具走静态注册表）
            return Optional.empty();
        }

        String cacheKey = toolName + "@" + toolVersion.get() + "|" + serverId;
        McpBackedToolHandler cached = cache.get(cacheKey);
        if (cached != null) {
            return Optional.of(cached);
        }

        Optional<Integer> serverVersion =
                publishedVersions.currentVersion(CapObjectType.MCP_SERVER, serverId);
        if (serverVersion.isEmpty()) {
            return Optional.empty();
        }
        Optional<CapMcpServerDef> server = serverDefs.find(serverId, serverVersion.get());
        if (server.isEmpty()) {
            log.warn("MCP Server 版本行缺失: server={}, version={}", serverId, serverVersion.get());
            return Optional.empty();
        }
        McpBackedToolHandler handler =
                new McpBackedToolHandler(tool.get(), server.get(), transport);
        cache.put(cacheKey, handler);
        return Optional.of(handler);
    }

    /**
     * 丢弃全部缓存（配置发布后显式失效）。
     */
    public void invalidate() {
        cache.clear();
    }
}
