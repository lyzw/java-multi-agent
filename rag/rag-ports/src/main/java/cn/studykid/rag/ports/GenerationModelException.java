package cn.studykid.rag.ports;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 生成侧大模型调用异常。
 *
 * <p>统一收敛「模型不可用 / 超时 / 非 2xx / 返回空 / 响应非法」为一类，
 * 让 {@code GroundedGenerationService} 用单一 catch 就能回落到逐字摘录。
 * 不携带任何密钥或响应体原文（避免日志泄漏凭据）。
 */
public final class GenerationModelException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public GenerationModelException(@NonNull String message) {
        super(message);
    }

    public GenerationModelException(@NonNull String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
