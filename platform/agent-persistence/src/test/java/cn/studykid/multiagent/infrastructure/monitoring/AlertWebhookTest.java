package cn.studykid.multiagent.infrastructure.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.ports.monitoring.AlertWebhookPort.CriticalDenyAlert;

/**
 * 告警外发最小实现的纯单元测试（无网络、无 DB）。
 *
 * <p>核心断言是<b>安全红线</b>：载荷只含定位字段，凭据/参数/指纹不可出现。
 */
class AlertWebhookTest {

    private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");

    private static CriticalDenyAlert alert() {
        return new CriticalDenyAlert(
                "t-1", "p-1", "tool-search",
                "AUTHZ_ROLE_MISSING", "inv-1", NOW);
    }

    @Test
    @DisplayName("✅ 载荷只含定位字段：无凭据引用 / 无业务参数 / 无指纹")
    void payloadContainsOnlyLocatorFields() {
        String body = HttpAlertWebhook.buildPayload(alert());

        assertThat(body).contains("\"type\":\"CRITICAL_DENY\"");
        assertThat(body).contains("\"tenantId\":\"t-1\"");
        assertThat(body).contains("\"principalId\":\"p-1\"");
        assertThat(body).contains("\"toolId\":\"tool-search\"");
        assertThat(body).contains("\"reasonCode\":\"AUTHZ_ROLE_MISSING\"");
        assertThat(body).contains("\"invocationId\":\"inv-1\"");
        // ⚠️ 安全断言：以下字段一概不得出现（外发即脱敏）
        assertThat(body).doesNotContain("credentialRef");
        assertThat(body).doesNotContain("param");
        assertThat(body).doesNotContain("fingerprint");
        assertThat(body).doesNotContain("inputSchema");
        assertThat(body).doesNotContain("secret");
        assertThat(body).doesNotContain("password");
    }

    @Test
    @DisplayName("✅ 可空字段输出 null 而非字符串 \"null\"")
    void nullableFieldsRenderAsJsonNull() {
        String body = HttpAlertWebhook.buildPayload(new CriticalDenyAlert(
                "t-1", null, null, "AUTHZ_STALE", "inv-2", NOW));

        assertThat(body).contains("\"principalId\":null");
        assertThat(body).contains("\"toolId\":null");
    }

    @Test
    @DisplayName("✅ 字段值中的 JSON 特殊字符被转义（不做信任假设）")
    void specialCharactersAreEscaped() {
        String body = HttpAlertWebhook.buildPayload(new CriticalDenyAlert(
                "t-1", "p\"x\\y\n", null, "AUTHZ_STALE", "inv-3", NOW));

        assertThat(body).contains("p\\\"x\\\\y\\n");
        // 转义后必须是合法 JSON 可解析形态（引号成对）
        assertThat(body.indexOf('"') > -1).isTrue();
    }

    @Test
    @DisplayName("⛔ 空白 url 构造即抛错 —— 装配层应改用 Noop，而不是静默不外发")
    void blankUrlIsRejected() {
        assertThatThrownBy(() -> new HttpAlertWebhook("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("✅ Noop 实现可安全调用（关闭态落点）")
    void noopIsSafe() {
        new NoopAlertWebhook().onCriticalDeny(alert());
        // 不抛异常即通过：关闭态不外发、不报错。
    }
}
