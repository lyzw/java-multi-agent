package cn.studykid.multiagent.application.cap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 平台级发布体（Tool 草稿）对 **HTTP 工具**的形状校验（第三十七批 · C37-1）。
 *
 * <p>⚠️ <b>这是作者真正会撞到的那一层</b>：
 * 管理台提交登记/改版 → API 反序列化成本体 → 记录构造器校验 →
 * 非法形状在这里就变成 400，作者当场看见；
 * 而不是等到审批、甚至运行期才发现。
 *
 * <p>⚠️ <b>HTTP 三件套「要么齐全、要么全无」</b>：
 * {@code kind=http} 却缺方法/URL/超时，是一个「能提交、能入库、
 * 但永远跑不起来」的 Tool 定义 —— 平台级对象全平台引用它，
 * 一个坏定义会让所有引用它的 Agent 集体失效。
 */
class ConfigPublishPayloadToolSpecHttpTest {

    private static ConfigPublishPayload.ToolSpec httpSpec(String method, String url,
                                                         Integer timeoutMs) {
        return new ConfigPublishPayload.ToolSpec("tool-1", "订单查询", "HTTP", "READ",
                null, null, method, url, timeoutMs, "{}", "{}");
    }

    @Test
    @DisplayName("✅ 合法的 HTTP 草稿通过")
    void validHttpSpecIsAccepted() {
        assertThatCode(() -> httpSpec("GET", "https://api.example.com/orders", 10_000))
                .doesNotThrowAnyException();
        assertThatCode(() -> httpSpec("post", "https://api.example.com/orders", 10_000))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("⛔ kind=HTTP 缺三件套之一 → 拒（不得入库成永远跑不起来的定义）")
    void httpSpecWithoutAllFieldsIsRejected() {
        assertThatThrownBy(() -> httpSpec(null, "https://api.example.com/x", 10_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须同时声明");
        assertThatThrownBy(() -> httpSpec("GET", null, 10_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> httpSpec("GET", "https://api.example.com/x", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("⛔ 方法不在白名单 → 拒")
    void methodOutsideAllowlistIsRejected() {
        assertThatThrownBy(() -> httpSpec("TRACE", "https://api.example.com/x", 10_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不允许的 HTTP 方法");
    }

    @Test
    @DisplayName("⛔ 超时越界 → 拒（防止把一个 Run 永久挂住）")
    void timeoutOutOfRangeIsRejected() {
        assertThatThrownBy(() -> httpSpec("GET", "https://api.example.com/x", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("超时必须在 1 ~");
        assertThatThrownBy(() -> httpSpec("GET", "https://api.example.com/x", 300_001))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("超时必须在 1 ~");
    }

    @Test
    @DisplayName("⛔ 非 HTTP 工具却携带 HTTP 配置 → 拒")
    void nativeSpecWithHttpFieldsIsRejected() {
        assertThatThrownBy(() -> new ConfigPublishPayload.ToolSpec("tool-1", "t", "NATIVE", "READ",
                null, null, "GET", "https://api.example.com/x", 10_000, "{}", "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不得携带 HTTP 配置");
    }

    @Test
    @DisplayName("⛔ HTTP 工具却携带 MCP 绑定 → 拒（目标必须唯一）")
    void httpSpecWithMcpBindingIsRejected() {
        assertThatThrownBy(() -> new ConfigPublishPayload.ToolSpec("tool-1", "t", "HTTP", "READ",
                "srv-1", "op", "GET", "https://api.example.com/x", 10_000, "{}", "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不得携带 MCP 绑定");
    }

    @Test
    @DisplayName("⛔ MCP 工具却携带 HTTP 配置 → 拒")
    void mcpSpecWithHttpFieldsIsRejected() {
        assertThatThrownBy(() -> new ConfigPublishPayload.ToolSpec("tool-1", "t", "MCP", "READ",
                "srv-1", "op", "GET", "https://api.example.com/x", 10_000, "{}", "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不得携带 HTTP 配置");
    }
}
