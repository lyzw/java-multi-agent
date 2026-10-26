package cn.studykid.multiagent.application.cap;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.cap.CapHttpToolConfig;
import cn.studykid.multiagent.core.cap.CapToolDef;
import cn.studykid.multiagent.core.cap.CapToolKind;
import cn.studykid.multiagent.core.cap.CapabilityRiskLevel;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tool 内容指纹对 **HTTP 配置**的覆盖（第三十七批 · C37-1 · §6.1 / §6.4）。
 *
 * <p>⚠️ <b>为什么 HTTP 字段必须入指纹</b>：
 * 目标 URL / 方法 / 超时是 Tool 的<b>行为定义</b>。
 * 改了 URL 却指纹不变 ⇒ 闭包指纹的失效检测（§6.4）检测不到这次变化，
 * 在途 Run 不会被判为失效 —— 那正是内容指纹要防的事。
 */
class CapObjectFingerprintHttpTest {

    private static CapToolDef httpTool(CapHttpToolConfig http) {
        return new CapToolDef("tool-1", 1, "订单查询", CapToolKind.HTTP,
                CapabilityRiskLevel.READ, null, null, http,
                "{}", "{}", "pending", 1, Instant.EPOCH, null);
    }

    @Test
    @DisplayName("⛔ 改了目标 URL → 指纹必须变（否则失效检测形同虚设）")
    void changingUrlChangesFingerprint() {
        String before = CapObjectFingerprint.of(
                httpTool(new CapHttpToolConfig("GET", "https://a.example/x", 10_000)));
        String after = CapObjectFingerprint.of(
                httpTool(new CapHttpToolConfig("GET", "https://b.example/x", 10_000)));

        assertThat(after).isNotEqualTo(before);
    }

    @Test
    @DisplayName("⛔ 改了请求方法 / 超时 → 指纹必须变")
    void changingMethodOrTimeoutChangesFingerprint() {
        String base = CapObjectFingerprint.of(
                httpTool(new CapHttpToolConfig("GET", "https://a.example/x", 10_000)));

        assertThat(CapObjectFingerprint.of(
                httpTool(new CapHttpToolConfig("POST", "https://a.example/x", 10_000))))
                .isNotEqualTo(base);
        assertThat(CapObjectFingerprint.of(
                httpTool(new CapHttpToolConfig("GET", "https://a.example/x", 20_000))))
                .isNotEqualTo(base);
    }

    @Test
    @DisplayName("✅ 内容未变 → 指纹稳定（同一份配置两次发布必须得到同一个指纹）")
    void unchangedContentKeepsFingerprint() {
        String first = CapObjectFingerprint.of(
                httpTool(new CapHttpToolConfig("GET", "https://a.example/x", 10_000)));
        String second = CapObjectFingerprint.of(
                httpTool(new CapHttpToolConfig("GET", "https://a.example/x", 10_000)));

        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("✅ HTTP 工具与 NATIVE 工具的指纹不同（kind 参与计算）")
    void httpAndNativeDiffer() {
        CapToolDef http = new CapToolDef("tool-1", 1, "订单查询", CapToolKind.HTTP,
                CapabilityRiskLevel.READ, null, null,
                new CapHttpToolConfig("GET", "https://a.example/x", 10_000),
                "{}", "{}", "pending", 1, Instant.EPOCH, null);
        CapToolDef nativeTool = new CapToolDef("tool-1", 1, "订单查询", CapToolKind.NATIVE,
                CapabilityRiskLevel.READ, null, null, null,
                "{}", "{}", "pending", 1, Instant.EPOCH, null);

        assertThat(CapObjectFingerprint.of(http))
                .isNotEqualTo(CapObjectFingerprint.of(nativeTool));
    }
}
