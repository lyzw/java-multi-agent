package cn.studykid.multiagent.core.cap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 远程 HTTP 工具配置的形状约束单测（第三十七批 · C37-1）。
 *
 * <p>⚠️ <b>为什么这些约束要 fail-closed</b>：
 * {@code kind=http} 的三个字段<b>同生共死</b> ——
 * 有方法就必须有 URL，有 URL 就必须有超时。
 * 摊平后「配了一半」的中间态能编译、能入库，
 * 却是一个<b>永远跑不起来</b>的 Tool 定义；
 * 而 Tool 是平台级对象，全平台引用它 ——
 * 一个坏定义会让所有引用它的 Agent 集体失效。
 */
class CapHttpToolConfigTest {

    @Test
    @DisplayName("✅ 合法配置：方法归一化为大写，URL 去除首尾空白")
    void validConfigIsNormalized() {
        CapHttpToolConfig config = new CapHttpToolConfig("get", "  https://api.example.com/x  ", 5_000);

        assertThat(config.method()).isEqualTo("GET");
        assertThat(config.url()).isEqualTo("https://api.example.com/x");
        assertThat(config.timeoutMs()).isEqualTo(5_000);
    }

    @Test
    @DisplayName("⛔ 方法不在白名单 → 直接拒（不得当成 GET 放行）")
    void methodOutsideAllowlistIsRejected() {
        assertThatThrownBy(() -> new CapHttpToolConfig("TRACE", "https://x.example", 1_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不允许的 HTTP 方法");
    }

    @Test
    @DisplayName("⛔ 空方法 / 空 URL → 直接拒")
    void blankMethodOrUrlIsRejected() {
        assertThatThrownBy(() -> new CapHttpToolConfig(" ", "https://x.example", 1_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTP 方法不能为空");
        assertThatThrownBy(() -> new CapHttpToolConfig("GET", "   ", 1_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("目标 URL 不能为空");
    }

    @Test
    @DisplayName("⛔ 超时非正数 / 超过上限 → 直接拒（防止把一个 Run 永久挂住）")
    void timeoutOutOfRangeIsRejected() {
        assertThatThrownBy(() -> new CapHttpToolConfig("GET", "https://x.example", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("超时必须为正数");
        assertThatThrownBy(() -> new CapHttpToolConfig("GET", "https://x.example",
                CapHttpToolConfig.MAX_TIMEOUT_MS + 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不得超过");
    }

    @Test
    @DisplayName("✅ 请求体的判定：只有 POST / PUT / PATCH 带请求体")
    void requestBodyAppliesToWriteMethodsOnly() {
        assertThat(new CapHttpToolConfig("POST", "https://x.example", 1_000).hasRequestBody()).isTrue();
        assertThat(new CapHttpToolConfig("PUT", "https://x.example", 1_000).hasRequestBody()).isTrue();
        assertThat(new CapHttpToolConfig("PATCH", "https://x.example", 1_000).hasRequestBody()).isTrue();
        assertThat(new CapHttpToolConfig("GET", "https://x.example", 1_000).hasRequestBody()).isFalse();
        assertThat(new CapHttpToolConfig("DELETE", "https://x.example", 1_000).hasRequestBody()).isFalse();
        assertThat(new CapHttpToolConfig("HEAD", "https://x.example", 1_000).hasRequestBody()).isFalse();
    }

    @Test
    @DisplayName("⛔ kind=http 却没有 HTTP 配置 → 拒绝（不得入库成「永远跑不起来」的行）")
    void httpKindWithoutConfigIsRejected() {
        assertThatThrownBy(() -> new CapToolDef("tool-1", 1, "t", CapToolKind.HTTP,
                CapabilityRiskLevel.READ, null, null, null,
                "{}", "{}", "fp", 1, java.time.Instant.EPOCH, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须携带完整的 HTTP 配置");
    }

    @Test
    @DisplayName("⛔ 非 HTTP 工具却携带 HTTP 配置 → 拒绝（目标必须唯一）")
    void nonHttpKindWithConfigIsRejected() {
        CapHttpToolConfig http = new CapHttpToolConfig("GET", "https://x.example", 1_000);
        assertThatThrownBy(() -> new CapToolDef("tool-1", 1, "t", CapToolKind.NATIVE,
                CapabilityRiskLevel.READ, null, null, http,
                "{}", "{}", "fp", 1, java.time.Instant.EPOCH, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不得携带 HTTP 配置");
    }

    @Test
    @DisplayName("⛔ HTTP 工具同时携带 MCP 绑定 → 拒绝（目标不唯一）")
    void httpKindWithMcpBindingIsRejected() {
        CapHttpToolConfig http = new CapHttpToolConfig("GET", "https://x.example", 1_000);
        assertThatThrownBy(() -> new CapToolDef("tool-1", 1, "t", CapToolKind.HTTP,
                CapabilityRiskLevel.READ, "srv-1", "op", http,
                "{}", "{}", "fp", 1, java.time.Instant.EPOCH, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不得同时携带 MCP 绑定");
    }

    @Test
    @DisplayName("✅ kind=HTTP 解析与判定")
    void toolKindParsesHttp() {
        assertThat(CapToolKind.parse("http")).isEqualTo(CapToolKind.HTTP);
        assertThat(CapToolKind.parse(" HTTP ")).isEqualTo(CapToolKind.HTTP);
        assertThat(CapToolKind.HTTP.isHttp()).isTrue();
        assertThat(CapToolKind.MCP.isHttp()).isFalse();
        // ⚠️ 非法值仍返回 null（fail-closed）：不得兜底成 NATIVE
        assertThat(CapToolKind.parse("ftp")).isNull();
    }
}
