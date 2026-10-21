package cn.studykid.rag.api.retrieval;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import cn.studykid.rag.core.retrieval.RetrievalRequest.RetrievalMode;
import cn.studykid.rag.core.version.EditionScope.Mode;

/**
 * 检索请求 DTO（RAG-D02 §6 / RAG-D00 §2.8）。
 *
 * <p>⚠️ 检索是「带模式的请求」而非「一个查询串」：同一问题在不同模式下召回不同内容，
 * 这是防止泄题的第一道闸门（RAG-D00 D-R10）。{@code mode} 与 {@code scopeMode}
 * 经 Bean Validation 强制非空。
 */
public record RetrievalRequestDto(
        @NotBlank String query,
        @NotNull RetrievalMode mode,
        @NotNull EditionDto edition,
        /** 版本检索范围模式（默认 STRICT：严格锁定学生当前版本）。可空。 */
        Mode scopeMode,
        String knowledgePointId,
        String subject,
        String grade,
        @Min(1) @Max(50) int topK
) {
}
