package cn.studykid.multiagent.ports.mcp;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import org.jspecify.annotations.NonNull;

/**
 * MCP 端点的地址解析端口（§6.5 · 06 §5「SSRF（DNS/重定向）」）。
 *
 * <p>06 §5 要求「DNS 解析后对<b>实际 IP</b> 校验」——
 * 判定对象必须是解析结果，而不是域名或配置文本。
 * 把解析抽成端口，是为了让<b>防 rebinding 的时序行为可测</b>：
 * 测试可以精确控制「第一次解析返回 A、第二次解析返回 B」，
 * 从而验证「解析与连接用同一结果、连接前复验」这条规则真的被执行，
 * 而不是碰运气靠真实的 DNS 时序。
 */
public interface McpAddressResolverPort {

    /**
     * 解析主机名的全部地址。
     *
     * @param host 主机名
     * @return 全部解析结果（可能多个 —— 任一被禁即整体拒绝）
     * @throws UnknownHostException 解析失败
     */
    @NonNull List<InetAddress> resolveAll(@NonNull String host) throws UnknownHostException;
}
