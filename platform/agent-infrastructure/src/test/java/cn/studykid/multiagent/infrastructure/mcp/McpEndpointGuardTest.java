package cn.studykid.multiagent.infrastructure.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.cap.CapMcpServerDef;
import cn.studykid.multiagent.core.cap.CapabilityRiskLevel;
import cn.studykid.multiagent.core.cap.McpTrustTier;
import cn.studykid.multiagent.ports.mcp.McpAddressResolverPort;
import cn.studykid.multiagent.ports.mcp.McpTransportException;

/**
 * MCP 端点 SSRF 防御的具名安全用例（P4-T5 / P4-T8 · 退出门禁 5 · 06 §5）。
 *
 * <p>⚠️ <b>为什么解析器是端口而不是直接用 JDK DNS</b>：
 * 「DNS rebinding」的本质是<b>两次解析返回不同结果</b>。
 * 用真实 DNS 去测它，等于依赖时序 —— 测出来的是运气，不是规则。
 * 端口让测试能<b>精确控制</b>两次解析的返回值，
 * 从而证明「连接前复验」这条规则真的被执行。
 */
class McpEndpointGuardTest {

    /** 可编排的地址解析器：按调用次序返回预设结果。 */
    private static final class ScriptedResolver implements McpAddressResolverPort {
        private final List<List<InetAddress>> script;
        private int calls;

        ScriptedResolver(List<List<InetAddress>> script) {
            this.script = script;
        }

        @Override
        public @NonNull List<InetAddress> resolveAll(@NonNull String host) throws UnknownHostException {
            int index = Math.min(calls, script.size() - 1);
            calls++;
            return script.get(index);
        }
    }

    private static InetAddress ip(String literal) throws Exception {
        return InetAddress.getByName(literal);
    }

    private static CapMcpServerDef server(String endpoint, McpTrustTier tier) {
        return new CapMcpServerDef("srv-1", 1, CapMcpServerDef.Transport.HTTP, endpoint, null,
                CapMcpServerDef.AuthKind.NONE, tier, null,
                CapabilityRiskLevel.READ, false, "fp", null, 1,
                java.time.Instant.EPOCH, java.time.Instant.EPOCH);
    }

    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("SSRF：内网 / 元数据地址被拦截")
    class SsrfBlocked {

        @Test
        @DisplayName("⛔ 云元数据地址 → 拦截")
        void metadataAddressIsBlocked() throws Exception {
            McpEndpointGuard guard = new McpEndpointGuard(new ScriptedResolver(
                    List.of(List.of(ip("169.254.169.254")))));

            assertThatThrownBy(() -> guard.validate(server("http://evil.example.com/mcp",
                    McpTrustTier.PUBLIC)))
                    .as("⛔ 解析到云元数据地址却放行 = SSRF 可直接偷云凭据")
                    .isInstanceOf(McpTransportException.class)
                    .satisfies(e -> assertThat(((McpTransportException) e).kind())
                            .isEqualTo(McpTransportException.Kind.SSRF_BLOCKED));
        }

        @Test
        @DisplayName("⛔ 内网地址（非 internal 信任级别）→ 拦截")
        void privateAddressIsBlocked() throws Exception {
            McpEndpointGuard guard = new McpEndpointGuard(new ScriptedResolver(
                    List.of(List.of(ip("10.0.0.5")))));

            assertThatThrownBy(() -> guard.validate(server("http://evil.example.com/mcp",
                    McpTrustTier.PUBLIC)))
                    .as("⛔ 解析到内网地址却放行 = SSRF")
                    .isInstanceOf(McpTransportException.class);
        }

        @Test
        @DisplayName("⛔ 解析结果<b>混合</b>公网与内网 → 整体拦截（fail-closed）")
        void mixedResolutionIsBlockedEntirely() throws Exception {
            McpEndpointGuard guard = new McpEndpointGuard(new ScriptedResolver(
                    List.of(List.of(ip("93.184.216.34"), ip("127.0.0.1")))));

            assertThatThrownBy(() -> guard.validate(server("http://mixed.example.com/mcp",
                    McpTrustTier.PUBLIC)))
                    .as("⛔ 只校验第一个解析结果 = 攻击者用多 A 记录绕过")
                    .isInstanceOf(McpTransportException.class);
        }

        @Test
        @DisplayName("⛔ 非 http(s) 协议 → 拦截")
        void nonHttpSchemeIsBlocked() {
            McpEndpointGuard guard = new McpEndpointGuard(new ScriptedResolver(List.of()));

            assertThatThrownBy(() -> guard.validate(server("file:///etc/passwd",
                    McpTrustTier.PUBLIC)))
                    .as("⛔ file:// 端点被放行 = 本地文件读取")
                    .isInstanceOf(McpTransportException.class);
        }

        @Test
        @DisplayName("⛔ 端点 URL 携带凭据（user:pass@）→ 拦截")
        void userInfoInEndpointIsBlocked() {
            McpEndpointGuard guard = new McpEndpointGuard(new ScriptedResolver(List.of()));

            assertThatThrownBy(() -> guard.validate(server("http://user:pass@host.example.com/mcp",
                    McpTrustTier.PUBLIC)))
                    .as("⛔ 端点里带凭据 = 凭据会进日志/配置")
                    .isInstanceOf(McpTransportException.class);
        }
    }

    @Nested
    @DisplayName("DNS rebinding：两次解析结果不一致 → 拒绝")
    class DnsRebinding {

        @Test
        @DisplayName("⛔ 第一次解析公网、第二次解析内网 → 拦截")
        void rebindingIsDetected() throws Exception {
            ScriptedResolver resolver = new ScriptedResolver(List.of(
                    List.of(ip("93.184.216.34")),
                    List.of(ip("169.254.169.254"))));
            McpEndpointGuard guard = new McpEndpointGuard(resolver);

            assertThatThrownBy(() -> guard.validate(server("http://rebind.example.com/mcp",
                    McpTrustTier.PUBLIC)))
                    .as("⛔ DNS rebinding 未被检出 = 校验过的 IP 与连上的 IP 可以不同")
                    .isInstanceOf(McpTransportException.class)
                    .hasMessageContaining("rebinding");
        }

        @Test
        @DisplayName("⛔ 第一次解析内网、第二次解析公网 → 拦截（顺序无关）")
        void rebindingIsDetectedInEitherOrder() throws Exception {
            ScriptedResolver resolver = new ScriptedResolver(List.of(
                    List.of(ip("127.0.0.1")),
                    List.of(ip("93.184.216.34"))));
            McpEndpointGuard guard = new McpEndpointGuard(resolver);

            assertThatThrownBy(() -> guard.validate(server("http://rebind.example.com/mcp",
                    McpTrustTier.PUBLIC)))
                    .isInstanceOf(McpTransportException.class);
        }
    }

    @Nested
    @DisplayName("连接地址 = 被校验的地址（解析与连接用同一结果）")
    class IpBinding {

        @Test
        @DisplayName("✅ 返回的地址集合 = 被校验通过的那一组（传输层据此连到同一 IP）")
        void validatedAddressesAreReturned() throws Exception {
            InetAddress target = ip("93.184.216.34");
            McpEndpointGuard guard = new McpEndpointGuard(new ScriptedResolver(
                    List.of(List.of(target))));

            McpEndpointGuard.ValidatedEndpoint endpoint = guard.validate(
                    server("https://mcp.example.com/rpc", McpTrustTier.PUBLIC));

            assertThat(endpoint.primaryAddress()).isEqualTo(target);
            assertThat(endpoint.host()).isEqualTo("mcp.example.com");
            assertThat(endpoint.port()).isEqualTo(443);
            assertThat(endpoint.scheme()).isEqualTo("https");
        }

        @Test
        @DisplayName("✅ 显式端口与路径被正确解析")
        void portAndPathAreParsed() throws Exception {
            McpEndpointGuard guard = new McpEndpointGuard(new ScriptedResolver(
                    List.of(List.of(ip("93.184.216.34")))));

            McpEndpointGuard.ValidatedEndpoint endpoint = guard.validate(
                    server("http://mcp.example.com:8080/v1/rpc", McpTrustTier.PUBLIC));

            assertThat(endpoint.port()).isEqualTo(8080);
            assertThat(endpoint.path()).isEqualTo("/v1/rpc");
        }

        @Test
        @DisplayName("⚠️ trustTier=internal 显式授权内网地址")
        void internalTierAllowsPrivateAddress() throws Exception {
            McpEndpointGuard guard = new McpEndpointGuard(new ScriptedResolver(
                    List.of(List.of(ip("10.0.0.5")))));

            McpEndpointGuard.ValidatedEndpoint endpoint = guard.validate(
                    server("http://internal.example.com/mcp", McpTrustTier.INTERNAL));

            assertThat(endpoint.primaryAddress().getHostAddress()).isEqualTo("10.0.0.5");
        }
    }

    @Nested
    @DisplayName("重定向目标必须重新校验")
    class RedirectTarget {

        @Test
        @DisplayName("⛔ 重定向到内网 → 拦截")
        void redirectToPrivateAddressIsBlocked() throws Exception {
            McpEndpointGuard guard = new McpEndpointGuard(new ScriptedResolver(List.of(
                    List.of(ip("93.184.216.34")),
                    List.of(ip("10.0.0.5")))));

            assertThatThrownBy(() -> guard.validate("http://internal.example.com/mcp",
                    McpTrustTier.PUBLIC, "redirect"))
                    .as("⛔ 重定向目标未复验 = SSRF 绕过")
                    .isInstanceOf(McpTransportException.class);
        }

        @Test
        @DisplayName("⛔ 重定向到元数据地址 → 拦截")
        void redirectToMetadataIsBlocked() throws Exception {
            McpEndpointGuard guard = new McpEndpointGuard(new ScriptedResolver(List.of(
                    List.of(ip("93.184.216.34")),
                    List.of(ip("169.254.169.254")))));

            assertThatThrownBy(() -> guard.validate("http://metadata.example.com/mcp",
                    McpTrustTier.PUBLIC, "redirect"))
                    .isInstanceOf(McpTransportException.class);
        }
    }

    @Nested
    @DisplayName("解析失败")
    class ResolutionFailure {

        @Test
        @DisplayName("⛔ 域名解析失败 → 拒绝（不是「放行」）")
        void unresolvableHostIsRejected() {
            McpAddressResolverPort failing = host -> {
                throw new UnknownHostException(host);
            };
            McpEndpointGuard guard = new McpEndpointGuard(failing);

            assertThatThrownBy(() -> guard.validate(server("http://nope.example.com/mcp",
                    McpTrustTier.PUBLIC)))
                    .as("⛔ 解析失败却放行 = fail-open")
                    .isInstanceOf(McpTransportException.class);
        }

        @Test
        @DisplayName("⛔ 解析结果为空 → 拒绝")
        void emptyResolutionIsRejected() {
            McpEndpointGuard guard = new McpEndpointGuard(new ScriptedResolver(
                    List.of(new ArrayList<>())));

            assertThatThrownBy(() -> guard.validate(server("http://empty.example.com/mcp",
                    McpTrustTier.PUBLIC)))
                    .isInstanceOf(McpTransportException.class);
        }
    }
}
