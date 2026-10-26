package cn.studykid.multiagent.infrastructure.http;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.core.cap.CapHttpToolConfig;
import cn.studykid.multiagent.infrastructure.mcp.McpEndpointGuard;
import cn.studykid.multiagent.ports.http.HttpToolException;
import cn.studykid.multiagent.ports.mcp.McpAddressResolverPort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 远程 HTTP 出站的 **SSRF 防线**单测（第三十七批 · C37-1 · 设计文档 §13.1）。
 *
 * <p>⚠️ <b>这些用例必须在「一个字节都没发出去」的前提下通过</b>：
 * 目标被拦时，连接根本不该建立 ——
 * 所以这里用「解析结果直接指向内网地址」来触发，
 * 并断言异常类型是 {@code SSRF_BLOCKED}（而不是连接失败）。
 */
class HttpToolExecutorSsrfTest {

    /** 可控的解析器：让测试精确控制「DNS 解析到什么」。 */
    private static final class FixedResolver implements McpAddressResolverPort {
        private final List<List<InetAddress>> answers;

        FixedResolver(List<List<InetAddress>> answers) {
            this.answers = answers;
        }

        @Override
        public @NonNull List<InetAddress> resolveAll(@NonNull String host)
                throws UnknownHostException {
            if (answers.isEmpty()) {
                throw new UnknownHostException(host);
            }
            int index = Math.min(counter++, answers.size() - 1);
            List<InetAddress> answer = answers.get(index);
            if (answer.isEmpty()) {
                throw new UnknownHostException(host);
            }
            return answer;
        }

        private int counter = 0;
    }

    private static List<InetAddress> ips(String... literals) {
        List<InetAddress> out = new java.util.ArrayList<>();
        for (String literal : literals) {
            try {
                out.add(InetAddress.getByName(literal));
            } catch (UnknownHostException e) {
                throw new IllegalStateException(e);
            }
        }
        return out;
    }

    private static HttpToolExecutor executorFor(List<List<InetAddress>> answers) {
        return new HttpToolExecutor(new McpEndpointGuard(new FixedResolver(answers)),
                HttpToolOptions.defaults());
    }

    @Test
    @DisplayName("⛔ 目标解析到私网地址 → 拦截（不得发出请求）")
    void privateAddressIsBlocked() throws Exception {
        HttpToolExecutor executor = executorFor(List.of(ips("10.0.0.7")));
        CapHttpToolConfig config = new CapHttpToolConfig("GET",
                "https://internal.example.com/x", 5_000);

        assertThatThrownBy(() -> executor.execute("tool-1", config, Map.of()))
                .isInstanceOf(HttpToolException.class)
                .satisfies(e -> assertThat(((HttpToolException) e).kind())
                        .isEqualTo(HttpToolException.Kind.SSRF_BLOCKED));
    }

    @Test
    @DisplayName("⛔ 目标解析到回环地址 → 拦截（HTTP 工具恒为 PUBLIC 级别，不得显式授权内网）")
    void loopbackIsBlocked() throws Exception {
        HttpToolExecutor executor = executorFor(List.of(ips("127.0.0.1")));
        CapHttpToolConfig config = new CapHttpToolConfig("GET",
                "https://localhost.example/x", 5_000);

        assertThatThrownBy(() -> executor.execute("tool-1", config, Map.of()))
                .isInstanceOf(HttpToolException.class)
                .satisfies(e -> assertThat(((HttpToolException) e).kind())
                        .isEqualTo(HttpToolException.Kind.SSRF_BLOCKED));
    }

    @Test
    @DisplayName("⛔ 目标解析到云元数据地址 → 拦截（任何信任级别都绝对禁止）")
    void cloudMetadataIsBlocked() throws Exception {
        HttpToolExecutor executor = executorFor(List.of(ips("169.254.169.254")));
        CapHttpToolConfig config = new CapHttpToolConfig("GET",
                "https://metadata.example.com/latest", 5_000);

        assertThatThrownBy(() -> executor.execute("tool-1", config, Map.of()))
                .isInstanceOf(HttpToolException.class)
                .satisfies(e -> assertThat(((HttpToolException) e).kind())
                        .isEqualTo(HttpToolException.Kind.SSRF_BLOCKED));
    }

    @Test
    @DisplayName("⛔ DNS rebinding：两次解析结果不同 → 拦截")
    void dnsRebindingIsBlocked() throws Exception {
        // 第一次解析给公网 IP，第二次解析给私网 IP —— 这正是 rebinding 的动作本身
        HttpToolExecutor executor = executorFor(
                List.of(ips("93.184.216.34"), ips("10.0.0.7")));
        CapHttpToolConfig config = new CapHttpToolConfig("GET",
                "https://rebind.example.com/x", 5_000);

        assertThatThrownBy(() -> executor.execute("tool-1", config, Map.of()))
                .isInstanceOf(HttpToolException.class)
                .satisfies(e -> assertThat(((HttpToolException) e).kind())
                        .isEqualTo(HttpToolException.Kind.SSRF_BLOCKED));
    }

    @Test
    @DisplayName("⛔ 一个域名解析出「公网 + 内网」两个地址 → 整体拒绝（不挑一个能用的）")
    void mixedResolutionIsBlocked() throws Exception {
        HttpToolExecutor executor = executorFor(List.of(ips("93.184.216.34", "10.0.0.7")));
        CapHttpToolConfig config = new CapHttpToolConfig("GET",
                "https://mixed.example.com/x", 5_000);

        assertThatThrownBy(() -> executor.execute("tool-1", config, Map.of()))
                .isInstanceOf(HttpToolException.class)
                .satisfies(e -> assertThat(((HttpToolException) e).kind())
                        .isEqualTo(HttpToolException.Kind.SSRF_BLOCKED));
    }

    @Test
    @DisplayName("⛔ 非 http/https 协议 → 拦截（file:// / gopher:// 是 SSRF 载体）")
    void nonHttpSchemeIsBlocked() {
        HttpToolExecutor executor = executorFor(List.of(ips("93.184.216.34")));
        CapHttpToolConfig config = new CapHttpToolConfig("GET",
                "file:///etc/passwd", 5_000);

        assertThatThrownBy(() -> executor.execute("tool-1", config, Map.of()))
                .isInstanceOf(HttpToolException.class);
    }

    @Test
    @DisplayName("⛔ URL 里的占位符没有对应参数 → 配置期就拒（不得把 {id} 原样发出去）")
    void missingPlaceholderArgumentIsRejected() {
        HttpToolExecutor executor = executorFor(List.of(ips("93.184.216.34")));
        CapHttpToolConfig config = new CapHttpToolConfig("GET",
                "https://api.example.com/orders/{orderId}", 5_000);

        assertThatThrownBy(() -> executor.execute("tool-1", config, Map.of()))
                .isInstanceOf(HttpToolException.class)
                .satisfies(e -> assertThat(((HttpToolException) e).kind())
                        .isEqualTo(HttpToolException.Kind.CONFIG_INVALID));
    }
}
