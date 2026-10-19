package cn.studykid.multiagent.ports.model;

import java.time.Duration;
import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 模型调用请求（领域中立表示）。
 *
 * <p>刻意<b>不暴露</b>任何供应商或 Spring AI 专属类型，保证领域层零框架依赖。
 */
public record ModelRequest(
        @NonNull String modelId,
        @NonNull String systemPrompt,
        @NonNull String userContent,
        @NonNull Double temperature,
        @NonNull Integer maxTokens,
        @NonNull Duration timeout,
        @NonNull Map<String, Object> toolSchemas
) {

    public ModelRequest {
        if (modelId.isBlank()) {
            throw new IllegalArgumentException("modelId 不能为空");
        }
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout 必须为正");
        }
        toolSchemas = toolSchemas == null ? Map.of() : Map.copyOf(toolSchemas);
    }
}
