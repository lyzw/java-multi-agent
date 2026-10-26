package cn.studykid.multiagent.infrastructure.http;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 远程 HTTP 工具的响应（第三十七批）。
 *
 * @param status      HTTP 状态码
 * @param body        响应体文本（UTF-8 解码）
 * @param contentType 响应的 {@code Content-Type}（可空）
 * @param finalUrl    最终生效的 URL（跟随重定向后）——
 *                    用于排障（「我配的是 A，实际打的是 B」这类问题的唯一依据）
 */
public record HttpToolResponse(
        int status,
        @NonNull String body,
        @Nullable String contentType,
        @NonNull String finalUrl) {
}
