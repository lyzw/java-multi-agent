package cn.studykid.multiagent.infrastructure.mcp;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.application.cap.McpServerConfigValidator;
import cn.studykid.multiagent.core.cap.CapMcpServerDef;
import cn.studykid.multiagent.ports.mcp.McpManifestPort;
import cn.studykid.multiagent.ports.mcp.McpTransportException;
import cn.studykid.multiagent.ports.mcp.McpTransportPort;
import cn.studykid.multiagent.ports.persistence.CapMcpServerDefPort;

/**
 * 经传输采集的 MCP 固定清单（W-PERM-P4-T2 · §4.4 {@code ManifestOps} · §6.5）。
 *
 * <p>⚠️ <b>清单采集失败必须表现为「不可用」而不是「空清单」</b>：
 * 「这个 server 确实没有工具」与「读不到这个 server」是两件事，
 * 前者是配置事实，后者是运行故障 —— 混淆二者会让运维在「server 宕机」时
 * 去查配置白名单。
 *
 * <p>⚠️ <b>配置期校验在这里再兜一次底</b>：
 * {@code dynamicDiscovery=true} 与 {@code stdio} 传输在写入侧已被拒，
 * 但数据库可能被绕过写入（直接 SQL、迁移脚本、恢复操作）——
 * 采集路径必须<b>再次</b>拒绝，不能信任「配置已经保证了」。
 *
 * <p>⚠️ <b>缓存 TTL 是漂移暴露窗口</b>：
 * 清单变了之后，最长 TTL 时间内容器仍按旧清单判定。
 * TTL 越短越安全、越费资源；本实现取默认值并允许配置。
 */
public class TransportMcpManifestPort implements McpManifestPort {

    private static final Logger log = LoggerFactory.getLogger(TransportMcpManifestPort.class);

    private final McpTransportPort transport;
    private final CapMcpServerDefPort serverDefs;
    private final Duration ttl;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public TransportMcpManifestPort(@NonNull McpTransportPort transport,
                                     @NonNull CapMcpServerDefPort serverDefs) {
        this(transport, serverDefs, Duration.ofSeconds(60));
    }

    public TransportMcpManifestPort(@NonNull McpTransportPort transport,
                                     @NonNull CapMcpServerDefPort serverDefs,
                                     @NonNull Duration ttl) {
        this.transport = transport;
        this.serverDefs = serverDefs;
        this.ttl = ttl;
    }

    @Override
    public @NonNull ManifestSnapshot manifest(@NonNull String serverId) {
        CacheEntry cached = cache.get(serverId);
        if (cached != null && cached.isFresh(Instant.now())) {
            return cached.snapshot();
        }
        ManifestSnapshot snapshot = collect(serverId);
        cache.put(serverId, new CacheEntry(snapshot, Instant.now().plus(ttl)));
        return snapshot;
    }

    private @NonNull ManifestSnapshot collect(@NonNull String serverId) {
        Optional<CapMcpServerDef> def;
        try {
            def = serverDefs.findLatestPublished(serverId);
        } catch (RuntimeException e) {
            // ⚠️ 读配置失败也是「不可用」，不是异常 —— 端口契约要求永不抛
            log.warn("MCP Server 定义读取失败: server={}, err={}", serverId, e.getMessage());
            return ManifestSnapshot.unavailable("MCP Server 定义读取失败: " + e.getMessage());
        }
        if (def.isEmpty()) {
            return ManifestSnapshot.unavailable("MCP Server 未找到已发布版本: " + serverId);
        }
        CapMcpServerDef server = def.get();

        List<McpServerConfigValidator.Violation> violations =
                McpServerConfigValidator.validate(server);
        if (!violations.isEmpty()) {
            // ⚠️ 配置非法 → 不可用（绝不「带着非法配置去连」）
            String reason = "MCP Server 配置非法: " + serverId + " → "
                    + violations.get(0).toString();
            log.warn("{}", reason);
            return ManifestSnapshot.unavailable(reason);
        }

        try {
            List<McpTransportPort.McpToolInfo> tools = transport.listTools(server);
            Set<String> ops = new LinkedHashSet<>();
            for (McpTransportPort.McpToolInfo tool : tools) {
                ops.add(tool.name());
            }
            log.debug("MCP 清单采集完成: server={}, ops={}", serverId, ops.size());
            return ManifestSnapshot.available(ops);
        } catch (McpTransportException e) {
            // ⚠️ 不抛异常：清单不可用是一种<b>状态</b>，不是一个编程错误
            log.warn("MCP 清单采集失败: server={}, kind={}, err={}",
                    serverId, e.kind(), e.getMessage());
            return ManifestSnapshot.unavailable("清单采集失败（" + e.kind() + "）: " + e.getMessage());
        } catch (RuntimeException e) {
            log.warn("MCP 清单采集异常: server={}, err={}", serverId, e.getMessage());
            return ManifestSnapshot.unavailable("清单采集异常: " + e.getMessage());
        }
    }

    /**
     * 丢弃某 server 的缓存条目（配置发布后显式失效）。
     *
     * @param serverId MCP Server 逻辑 id
     */
    public void invalidate(@NonNull String serverId) {
        cache.remove(serverId);
    }

    /**
     * 缓存条目。
     *
     * @param snapshot 清单快照
     * @param expiresAt 过期时刻
     */
    private record CacheEntry(@NonNull ManifestSnapshot snapshot, @NonNull Instant expiresAt) {
        boolean isFresh(@NonNull Instant now) {
            return now.isBefore(expiresAt);
        }
    }
}
