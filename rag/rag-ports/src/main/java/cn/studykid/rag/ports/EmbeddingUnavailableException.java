package cn.studykid.rag.ports;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 嵌入模型服务不可用（连接失败 / 超时 / 被中断 / 非 2xx）。
 *
 * <p><b>为什么需要单独一类</b>：改造前 {@code OpenAiCompatibleEmbeddingPort}
 * 把「本机 Ollama 没启动」这类上游不可达也抛成 {@link IllegalStateException}，
 * 经 Spring 默认映射就是一条没有原因的 <b>HTTP 500</b>——运维看到的是
 * 「服务坏了」，而实际只是外部依赖没起。检索链路依赖稠密向量，
 * 拿不到向量时<b>绝不静默降级成纯词法</b>（那会让分数口径悄悄变化、
 * 页面却显示检索成功），必须 fail-closed 并给出可执行的提示。
 *
 * <p>⚠️ 只表达「上游暂时不可用」。<b>不</b>用于响应格式错误、维度不匹配等
 * 契约/配置错误——那些是部署缺陷，仍走 {@link IllegalStateException}，
 * 不该被当成「稍后重试」糊过去。
 *
 * <p>⚠️ 不携带 apiKey 或响应体原文（避免日志泄漏凭据 / 语料原文）。
 */
public final class EmbeddingUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public EmbeddingUnavailableException(@NonNull String message) {
        super(message);
    }

    public EmbeddingUnavailableException(@NonNull String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
