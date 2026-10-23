package cn.studykid.multiagent.application.cap;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.cap.CapMcpServerDef;
import cn.studykid.multiagent.core.cap.CapabilityRiskLevel;
import cn.studykid.multiagent.core.cap.McpTrustTier;

/**
 * MCP Server 配置期校验的具名安全用例（P4-T2 / P4-T8 · 退出门禁 1 · §4.2 · 06 §5）。
 *
 * <p>退出门禁 1：「仅 http/sse 传输启用；{@code dynamicDiscovery} 强制 false（配置期拒），stdio 未启用」。
 */
class McpServerConfigValidatorTest {

    private static CapMcpServerDef def(CapMcpServerDef.Transport transport,
                                        String endpoint,
                                        boolean dynamicDiscovery) {
        return new CapMcpServerDef("srv-1", 1, transport, endpoint,
                transport == CapMcpServerDef.Transport.STDIO ? "some-binary" : null,
                CapMcpServerDef.AuthKind.NONE, McpTrustTier.PARTNER, null,
                CapabilityRiskLevel.READ, dynamicDiscovery, "fp", null, 1,
                java.time.Instant.EPOCH, java.time.Instant.EPOCH);
    }

    @Nested
    @DisplayName("退出门禁 1：传输与动态发现")
    class TransportAndDiscovery {

        @Test
        @DisplayName("⛔ dynamicDiscovery=true → 配置期拒（N4：动态发现绕过固定清单与指纹校验）")
        void dynamicDiscoveryIsRejected() {
            List<McpServerConfigValidator.Violation> violations =
                    McpServerConfigValidator.validate(
                            def(CapMcpServerDef.Transport.HTTP, "https://mcp.example.com/rpc", true));

            assertThat(violations)
                    .as("⛔ 动态发现未被拒 = 运行期工具清单可漂移，固定清单与指纹校验全部失效")
                    .isNotEmpty();
            assertThat(violations.stream().anyMatch(v -> "dynamicDiscovery".equals(v.field())))
                    .isTrue();
        }

        @Test
        @DisplayName("⛔ stdio 传输 → 配置期拒（N7：V1 未实现其安全管控）")
        void stdioIsRejected() {
            List<McpServerConfigValidator.Violation> violations =
                    McpServerConfigValidator.validate(
                            def(CapMcpServerDef.Transport.STDIO, null, false));

            assertThat(violations)
                    .as("⛔ stdio 未被拒 = 可在服务端拉起不受控子进程")
                    .isNotEmpty();
            assertThat(violations.stream().anyMatch(v -> "transport".equals(v.field())))
                    .isTrue();
        }

        @Test
        @DisplayName("✅ http / sse + dynamicDiscovery=false → 通过")
        void httpAndSseAreAccepted() {
            assertThat(McpServerConfigValidator.validate(
                    def(CapMcpServerDef.Transport.HTTP, "https://mcp.example.com/rpc", false)))
                    .isEmpty();
            assertThat(McpServerConfigValidator.validate(
                    def(CapMcpServerDef.Transport.SSE, "https://mcp.example.com/sse", false)))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("端点形态")
    class EndpointShape {

        @Test
        @DisplayName("⛔ 非 http(s) 协议 → 拒")
        void nonHttpSchemeIsRejected() {
            assertThat(McpServerConfigValidator.validate(
                    def(CapMcpServerDef.Transport.HTTP, "file:///etc/passwd", false)))
                    .isNotEmpty();
        }

        @Test
        @DisplayName("⛔ URL 携带用户信息 → 拒（凭据只能走 credential_ref）")
        void userInfoIsRejected() {
            assertThat(McpServerConfigValidator.validate(
                    def(CapMcpServerDef.Transport.HTTP, "http://u:p@mcp.example.com/rpc", false)))
                    .isNotEmpty();
        }

        @Test
        @DisplayName("⛔ http/sse 缺 endpoint → 拒")
        void missingEndpointIsRejected() {
            assertThat(McpServerConfigValidator.validate(
                    def(CapMcpServerDef.Transport.HTTP, null, false)))
                    .isNotEmpty();
        }

        @Test
        @DisplayName("✅ 合法端点 → 通过")
        void validEndpointIsAccepted() {
            assertThat(McpServerConfigValidator.validate(
                    def(CapMcpServerDef.Transport.HTTP, "https://mcp.example.com/rpc?x=1", false)))
                    .isEmpty();
        }
    }
}
