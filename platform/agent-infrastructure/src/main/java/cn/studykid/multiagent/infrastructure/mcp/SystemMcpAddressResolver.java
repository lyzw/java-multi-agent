package cn.studykid.multiagent.infrastructure.mcp;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.ports.mcp.McpAddressResolverPort;

/**
 * 基于 JDK DNS 的地址解析（§6.5）。
 *
 * <p>用 {@link InetAddress#getAllByName(String)} 而非 {@code getByName}：
 * 前者返回全部解析结果，SSRF 校验必须看到<b>每一个</b>候选地址 ——
 * 只校验第一个等于给攻击者留了一半余地（fail-closed 要求全查）。
 */
public class SystemMcpAddressResolver implements McpAddressResolverPort {

    @Override
    public @NonNull List<InetAddress> resolveAll(@NonNull String host) throws UnknownHostException {
        InetAddress[] resolved = InetAddress.getAllByName(host);
        List<InetAddress> result = new ArrayList<>(resolved.length);
        for (InetAddress address : resolved) {
            result.add(address);
        }
        return result;
    }
}
