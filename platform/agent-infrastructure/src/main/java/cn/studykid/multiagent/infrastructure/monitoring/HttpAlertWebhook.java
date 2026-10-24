package cn.studykid.multiagent.infrastructure.monitoring;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.ports.monitoring.AlertWebhookPort;

/**
 * HTTP Webhook 告警外发（管理面 P5 · 最小实现）。
 *
 * <p><b>三条红线的落地</b>：
 * <ul>
 *   <li><b>不阻塞主链路</b>：{@code sendAsync} 异步发送，调用方（审计写入）
 *       立即返回，不等网络。</li>
 *   <li><b>best-effort</b>：响应与异常全部吞掉、只留 WARN 日志 ——
 *       重试 / 退避 / 死信属告警通道自身的运维升级，不在最小实现内
 *       （丢了还有审计表可查，外发只是加速「有人知道」）。</li>
 *   <li><b>载荷最小化</b>：{@link #buildPayload} 只拼定位字段；
 *       {@code credentialRef}、业务参数、指纹一概不出现在载荷里。</li>
 * </ul>
 *
 * <p>⚠️ <b>url 为空白时构造即视为关闭</b>：装配层应改用
 * {@link NoopAlertWebhook}；本类构造收到空白 url 属装配错误，直接抛异常暴露。
 */
public final class HttpAlertWebhook implements AlertWebhookPort {

    private static final Logger log = LoggerFactory.getLogger(HttpAlertWebhook.class);

    /** 外发超时：告警不值得久等（异步路径，超时只影响本次外发）。 */
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private final HttpClient client;
    private final URI target;

    public HttpAlertWebhook(@NonNull String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("webhook url 为空白时应使用 NoopAlertWebhook");
        }
        this.target = URI.create(url);
        this.client = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
    }

    @Override
    public void onCriticalDeny(@NonNull CriticalDenyAlert alert) {
        String body = buildPayload(alert);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(target)
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        // ⚠️ 异步 + 全吞：外发失败不重试、不抛出、不回压主链路。
        client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .whenComplete((response, error) -> {
                    if (error != null) {
                        log.warn("严重拒绝告警外发失败（best-effort，不重试）: {}",
                                error.getMessage());
                    } else if (response.statusCode() >= 300) {
                        log.warn("严重拒绝告警外发收到非 2xx 响应: status={}",
                                response.statusCode());
                    }
                });
    }

    /**
     * 构造最小化 JSON 载荷（包私有，供测试断言「无凭据字段」）。
     *
     * <p>⚠️ 字段值经 {@link #jsonEscape} 转义：id 类字段虽多为受控字符集，
     * 但外发载荷按「可能含任意字符」处理，不做信任假设。
     */
    static @NonNull String buildPayload(@NonNull CriticalDenyAlert alert) {
        return "{"
                + "\"type\":\"CRITICAL_DENY\""
                + ",\"tenantId\":\"" + jsonEscape(alert.tenantId()) + "\""
                + ",\"principalId\":" + nullableString(alert.principalId())
                + ",\"toolId\":" + nullableString(alert.toolId())
                + ",\"reasonCode\":\"" + jsonEscape(alert.reasonCode()) + "\""
                + ",\"invocationId\":\"" + jsonEscape(alert.invocationId()) + "\""
                + ",\"occurredAt\":\"" + alert.occurredAt() + "\""
                + "}";
    }

    private static @NonNull String nullableString(String value) {
        return value == null ? "null" : "\"" + jsonEscape(value) + "\"";
    }

    private static @NonNull String jsonEscape(@NonNull String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
