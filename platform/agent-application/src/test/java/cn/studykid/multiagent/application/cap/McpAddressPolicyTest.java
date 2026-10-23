package cn.studykid.multiagent.application.cap;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.Inet6Address;
import java.net.InetAddress;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.cap.McpTrustTier;

/**
 * MCP 端点地址策略（P4-T5 · 06 §5「SSRF 防护（端点）」）。
 *
 * <p>⚠️ <b>云元数据地址在任何信任级别下都必须被拒</b>：
 * {@code 169.254.169.254} 是云厂商的实例元数据服务，
 * 能读到它就等于读走了实例角色的临时凭据。
 * 06 §5 的「除非 trustTier=internal 显式授权」在字面上也覆盖了它，
 * 但本实现<b>刻意收紧</b> —— 没有任何业务理由需要 MCP 访问元数据地址。
 */
class McpAddressPolicyTest {

    private static InetAddress ip(String literal) throws Exception {
        return InetAddress.getByName(literal);
    }

    @Test
    @DisplayName("⛔ 云元数据地址在任何信任级别下都被拒（169.254.169.254 / fd00:ec2::254 / 100.100.100.200）")
    void metadataAddressesAreAlwaysForbidden() throws Exception {
        for (String literal : new String[]{
                "169.254.169.254", "169.254.170.2", "fd00:ec2::254", "100.100.100.200"}) {
            InetAddress address = ip(literal);
            for (McpTrustTier tier : McpTrustTier.values()) {
                assertThat(McpAddressPolicy.isAllowed(address, tier))
                        .as("⛔ 元数据地址 %s 在 tier=%s 下被放行 = SSRF 可直接偷云凭据",
                                literal, tier)
                        .isFalse();
                assertThat(McpAddressPolicy.isAbsolutelyForbidden(address))
                        .as("⛔ 元数据地址 %s 未被标记为绝对禁止", literal)
                        .isTrue();
            }
        }
    }

    @Test
    @DisplayName("⛔ 链路本地段（169.254.0.0/16 / fe80::/10）整体被拒")
    void linkLocalIsForbidden() throws Exception {
        for (String literal : new String[]{"169.254.10.1", "fe80::1"}) {
            assertThat(McpAddressPolicy.isAllowed(ip(literal), McpTrustTier.INTERNAL))
                    .as("⛔ 链路本地地址 %s 被放行", literal)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("⚠️ 回环与私有网段：仅 trustTier=internal 可显式授权")
    void loopbackAndPrivateNeedInternalTier() throws Exception {
        String[] internals = {"127.0.0.1", "10.1.2.3", "172.16.5.5", "192.168.1.1", "fd00::1"};
        for (String literal : internals) {
            InetAddress address = ip(literal);
            assertThat(McpAddressPolicy.isAllowed(address, McpTrustTier.PUBLIC))
                    .as("⛔ 内网地址 %s 在非 internal 信任级别下被放行 = SSRF", literal)
                    .isFalse();
            assertThat(McpAddressPolicy.isAllowed(address, McpTrustTier.PARTNER))
                    .as("⛔ 内网地址 %s 在 partner 信任级别下被放行 = SSRF", literal)
                    .isFalse();
            assertThat(McpAddressPolicy.isAllowed(address, McpTrustTier.INTERNAL))
                    .as("internal 信任级别应显式授权内网地址 %s", literal)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("✅ 公网地址在任何信任级别下都放行")
    void publicAddressesAreAllowed() throws Exception {
        for (String literal : new String[]{"93.184.216.34", "8.8.8.8", "2606:2800:220:1:248:1893:25c8:1946"}) {
            InetAddress address = ip(literal);
            for (McpTrustTier tier : McpTrustTier.values()) {
                assertThat(McpAddressPolicy.isAllowed(address, tier))
                        .as("公网地址 %s 不该被拦（tier=%s）", literal, tier)
                        .isTrue();
            }
        }
    }

    @Test
    @DisplayName("⛔ 多播 / 通配 / 保留段 / 文档段被拒")
    void specialRangesAreForbidden() throws Exception {
        String[] forbidden = {
                "0.0.0.0", "224.0.0.1", "240.0.0.1", "192.0.2.1", "198.51.100.1", "203.0.113.1",
                "255.255.255.255", "198.18.0.1"};
        for (String literal : forbidden) {
            assertThat(McpAddressPolicy.isAllowed(ip(literal), McpTrustTier.INTERNAL))
                    .as("⛔ 特殊地址 %s 被放行", literal)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("⚠️ IPv4-mapped IPv6 必须按内层 IPv4 判定（否则可绕过回环检查）")
    void ipv4MappedCannotBypassLoopbackCheck() throws Exception {
        // ⚠️ 必须显式构造 Inet6Address：
        //    InetAddress.getByName("::ffff:127.0.0.1") 会被 JDK 直接转成 Inet4Address，
        //    那样就测不到「映射形式」这条路径了。
        byte[] bytes = new byte[16];
        bytes[10] = (byte) 0xff;
        bytes[11] = (byte) 0xff;
        bytes[12] = 127;
        bytes[14] = 0;
        bytes[15] = 1;
        InetAddress mapped = Inet6Address.getByAddress(null, bytes, null);

        assertThat(McpAddressPolicy.isIpv4Mapped(mapped))
                .as("测试前提：::ffff:127.0.0.1 应被识别为 IPv4-mapped")
                .isTrue();
        InetAddress unwrapped = McpAddressPolicy.unwrapIpv4Mapped(mapped);
        assertThat(unwrapped.getHostAddress()).isEqualTo("127.0.0.1");
        assertThat(McpAddressPolicy.isAllowed(unwrapped, McpTrustTier.PUBLIC))
                .as("⛔ IPv4-mapped 回环地址绕过了检查 = SSRF 绕过")
                .isFalse();
    }
}
