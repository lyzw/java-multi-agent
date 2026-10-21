package cn.studykid.rag.api.retrieval;

import jakarta.validation.constraints.NotBlank;

/**
 * 版本坐标系 DTO（RAG-D00 §2.2 / RAG-D02 §6）。
 *
 * <p>五轴字符串由客户端以<b>展示名</b>传入（如 {@code "数学"/"八年级"/"上册"/"人教版"}），
 * 控制器负责映射到领域枚举（{@code EditionCoordinate}）。
 * 版本是引用可验证性的基础，缺任一轴将导致引用不可验证（RAG-D00 D-R04）。
 */
public record EditionDto(
        @NotBlank String subject,
        @NotBlank String grade,
        @NotBlank String term,
        @NotBlank String publisher,
        @NotBlank String revisionCode
) {
}
