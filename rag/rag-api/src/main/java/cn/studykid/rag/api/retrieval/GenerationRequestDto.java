package cn.studykid.rag.api.retrieval;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import cn.studykid.rag.core.retrieval.RetrievalRequest.RetrievalMode;
import cn.studykid.rag.core.version.EditionScope.Mode;

/**
 * 生成请求 DTO（RAG-D00 §2.7 / B5）。
 *
 * <p>⚠️ {@code hitIds} 是<b>必填</b>且逐条校验的：调用流程是
 * 「先检索、再把命中 ID 交给生成」。允许空 hitIds 会让本端
 * 退化成「无依据也要答」，正是 {@link cn.studykid.rag.core.grounding.GroundingPolicy}
 * 明确反对的行为。
 *
 * <p>⚠️ {@code mode} 与 {@code edition} 必须与检索时<b>一致</b>：
 * 模式决定哪些角色可被引用（换模式就能绕过泄题闸门），
 * 版本决定哪些片段算「同一本书」。二者由调用方复述，服务端重新校验。
 */
public record GenerationRequestDto(
        @NotBlank String query,
        @NotNull RetrievalMode mode,
        @NotNull EditionDto edition,
        /** 版本检索范围模式（默认 STRICT）。可空。 */
        Mode scopeMode,
        @NotEmpty @Size(max = 50) List<String> hitIds
) {
}
