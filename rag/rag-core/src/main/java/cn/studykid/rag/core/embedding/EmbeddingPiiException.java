package cn.studykid.rag.core.embedding;

import org.jspecify.annotations.NonNull;

/**
 * Embedding 输入命中 PII 时的 fail-closed 异常（RAG-D28 P3-1）。
 *
 * <p>向量不可逆脱敏——PII 一旦进向量就必须整批重算代际才能撤回，
 * 故模板在写入前拒收，且异常信息<b>不回显命中的具体数字</b>
 * （只给位置，避免异常日志本身成为 PII 泄露通道）。
 */
public class EmbeddingPiiException extends RuntimeException {

    public EmbeddingPiiException(@NonNull String message) {
        super(message);
    }
}
