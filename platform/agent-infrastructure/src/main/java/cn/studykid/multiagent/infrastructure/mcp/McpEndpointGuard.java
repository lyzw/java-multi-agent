package cn.studykid.multiagent.infrastructure.mcp;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.application.cap.McpAddressPolicy;
import cn.studykid.multiagent.core.cap.CapMcpServerDef;
import cn.studykid.multiagent.ports.mcp.McpAddressResolverPort;
import cn.studykid.multiagent.ports.mcp.McpTransportException;

/**
 * MCP 端点安全守卫（W-PERM-P4-T5 · §6.5 · 06 §5「SSRF（DNS/重定向）」）。
 *
 * <p>06 §5 原文：
 * <blockquote>
 * DNS 解析后对<b>实际 IP</b> 校验；防 DNS rebinding（<b>解析与连接用同一结果、连接前复验</b>）；
 * 禁止未经授权 HTTP 重定向跳内网/云元数据；每次连接与重定向<b>重新校验</b>目标地址。
 * </blockquote>
 *
 * <p>本类实现其中三件事，缺一不可：
 * <ol>
 *   <li><b>解析后校验</b>：域名再正常，解析结果落在 {@code 169.254.169.254} 就是 SSRF；</li>
 *   <li><b>二次解析复验</b>：解析一次与解析两次的结果必须一致，
 *       不一致说明 DNS 在两次解析之间换了指向 —— 这正是 rebinding 的动作本身；</li>
 *   <li><b>连接地址 = 被校验的地址</b>：返回给传输层的是「已校验的 IP 集合」，
 *       传输层据此连到<b>具体 IP</b>，而不是让 JDK 自己去解析一遍
 *       —— 后者会让「校验过的地址」和「实际连上的地址」变成两个东西。</li>
 * </ol>
 *
 * <p>⚠️ <b>fail-closed</b>：任一解析结果被禁即整体拒绝（不是「挑一个能用的」）。
 * 一个域名解析出 {@code [公网 IP, 内网 IP]} 本身就值得警惕 ——
 * 攻击者正是用这种方式把流量引到内网。
 */
public class McpEndpointGuard {

    private static final Logger log = LoggerFactory.getLogger(McpEndpointGuard.class);

    private final McpAddressResolverPort resolver;

    public McpEndpointGuard(@NonNull McpAddressResolverPort resolver) {
        this.resolver = resolver;
    }

    /**
     * 已通过校验的端点。
     *
     * @param host      原始主机名（用于 Host 头与 TLS SNI）
     * @param port      端口
     * @param scheme    协议
     * @param addresses 已校验的地址集合（传输层必须连到这里面给出的 IP）
     * @param path      请求路径
     */
    public record ValidatedEndpoint(@NonNull String host,
                                     int port,
                                     @NonNull String scheme,
                                     @NonNull Set<InetAddress> addresses,
                                     @NonNull String path) {

        public ValidatedEndpoint {
            if (host == null || host.isBlank()) {
                throw new IllegalArgumentException("host 不能为空");
            }
            if (port <= 0 || port > 65535) {
                throw new IllegalArgumentException("端口越界: " + port);
            }
            if (addresses == null || addresses.isEmpty()) {
                throw new IllegalArgumentException("已校验的地址集合不得为空");
            }
            addresses = Set.copyOf(addresses);
        }

        /**
         * @return 首选连接地址（解析结果中的第一个）
         */
        public @NonNull InetAddress primaryAddress() {
            return addresses.iterator().next();
        }
    }

    /**
     * 校验一个 MCP Server 的端点。
     *
     * @param server MCP Server 定义
     * @return 已校验的端点
     * @throws McpTransportException 端点非法 / 解析失败 / 目标被禁 / DNS 不稳定
     */
    public @NonNull ValidatedEndpoint validate(@NonNull CapMcpServerDef server) {
        String endpoint = server.endpoint();
        if (endpoint == null || endpoint.isBlank()) {
            throw new McpTransportException(McpTransportException.Kind.CONNECT_FAILED,
                    "MCP Server 未配置 endpoint: " + server.id());
        }
        return validate(endpoint, server.trustTier(), server.id());
    }

    /**
     * 校验一个 URL（重定向目标也走这里）。
     *
     * @param url    待校验 URL
     * @param tier   信任级别
     * @param origin 来源标识（仅用于日志）
     * @return 已校验的端点
     * @throws McpTransportException 端点非法 / 解析失败 / 目标被禁 / DNS 不稳定
     */
    public @NonNull ValidatedEndpoint validate(@NonNull String url,
                                                cn.studykid.multiagent.core.cap.@NonNull
                                                        McpTrustTier tier,
                                                @NonNull String origin) {
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            throw new McpTransportException(McpTransportException.Kind.PROTOCOL_ERROR,
                    "MCP 端点 URL 非法: " + origin, e);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new McpTransportException(McpTransportException.Kind.SSRF_BLOCKED,
                    "MCP 端点协议不被允许（仅 http/https）: " + origin + " scheme=" + scheme);
        }
        if (uri.getUserInfo() != null) {
            throw new McpTransportException(McpTransportException.Kind.SSRF_BLOCKED,
                    "MCP 端点 URL 携带用户信息，拒绝: " + origin);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new McpTransportException(McpTransportException.Kind.PROTOCOL_ERROR,
                    "MCP 端点 URL 缺少主机名: " + origin);
        }
        int port = uri.getPort() == -1 ? ("https".equals(scheme) ? 443 : 80) : uri.getPort();
        String path = uri.getRawPath() == null || uri.getRawPath().isBlank() ? "/" : uri.getRawPath();

        // ── 第一次解析 ───────────────────────────────────────────────
        List<InetAddress> first = resolve(host, origin);
        Set<InetAddress> validated = new LinkedHashSet<>();
        for (InetAddress raw : first) {
            InetAddress address = McpAddressPolicy.unwrapIpv4Mapped(raw);
            String reason = McpAddressPolicy.forbiddenReason(address, tier);
            if (reason != null) {
                // ⚠️ fail-closed：任一解析结果被禁即整体拒绝
                throw new McpTransportException(McpTransportException.Kind.SSRF_BLOCKED,
                        "MCP 端点解析结果被拒（" + origin + " host=" + host
                                + " ip=" + McpAddressPolicy.literalOf(address) + "）：" + reason);
            }
            validated.add(address);
        }
        if (validated.isEmpty()) {
            throw new McpTransportException(McpTransportException.Kind.CONNECT_FAILED,
                    "MCP 端点解析结果为空: " + origin + " host=" + host);
        }

        // ── 第二次解析（连接前复验 · 防 rebinding）───────────────────
        //    06 §5：「解析与连接用同一结果、连接前复验」。
        //    两次结果不一致 = DNS 在两次解析之间换了指向 → rebinding 的动作本身。
        List<InetAddress> second = resolve(host, origin);
        Set<InetAddress> secondSet = new LinkedHashSet<>();
        for (InetAddress raw : second) {
            secondSet.add(McpAddressPolicy.unwrapIpv4Mapped(raw));
        }
        if (!secondSet.equals(validated)) {
            throw new McpTransportException(McpTransportException.Kind.SSRF_BLOCKED,
                    "MCP 端点 DNS 解析结果在两次解析间发生变化（疑似 DNS rebinding），拒绝: "
                            + origin + " host=" + host);
        }

        log.debug("MCP 端点校验通过: origin={}, host={}, port={}, ip={}",
                origin, host, port, McpAddressPolicy.literalOf(validated.iterator().next()));
        return new ValidatedEndpoint(host, port, scheme, validated, path);
    }

    private @NonNull List<InetAddress> resolve(@NonNull String host, @NonNull String origin) {
        try {
            List<InetAddress> addresses = resolver.resolveAll(host);
            if (addresses == null || addresses.isEmpty()) {
                throw new McpTransportException(McpTransportException.Kind.CONNECT_FAILED,
                        "MCP 端点解析结果为空: " + origin + " host=" + host);
            }
            return new ArrayList<>(addresses);
        } catch (UnknownHostException e) {
            throw new McpTransportException(McpTransportException.Kind.CONNECT_FAILED,
                    "MCP 端点域名解析失败: " + origin + " host=" + host, e);
        }
    }
}
