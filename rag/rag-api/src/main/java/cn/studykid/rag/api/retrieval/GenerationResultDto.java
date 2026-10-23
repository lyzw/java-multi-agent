package cn.studykid.rag.api.retrieval;

import java.util.List;

import cn.studykid.rag.core.chunk.Chunk;
import cn.studykid.rag.core.generation.GenerationResult;
import cn.studykid.rag.core.retrieval.RetrievalResult.HitReason;
import cn.studykid.rag.core.retrieval.RetrievalResult.RetrievalChannel;

/**
 * 生成结果 DTO（RAG-D00 §2.7 / B5）。
 *
 * <p><b>⚠️ {@link #generator} 与 {@link #disclaimer} 不是可选的装饰字段</b>：
 * 前者如实标注「这段文字是怎么来的」，后者说明「有哪些内容被策略藏起来了」。
 * 前端<b>必须</b>展示二者；只展示 {@link #answer} 而不展示它们，
 * 等于把「逐字摘录」伪装成「AI 讲解」，是把系统的局限包装成能力。
 *
 * <p><b>⚠️ 依据不足时 {@link #answer} 为空串而非解释性文字</b>：
 * 「找不到依据」必须与「讲解内容」在形态上可区分，
 * 否则调用方无法判断该显示结果还是该提示学生换种问法。
 */
public record GenerationResultDto(
        String answer,
        List<RetrievalResultDto.HitDto> citations,
        String generator,
        String disclaimer,
        boolean versionMismatchDetected,
        boolean answerContentSuppressed
) {

    public static GenerationResultDto from(GenerationResult result) {
        List<RetrievalResultDto.HitDto> citations = result.basis().stream()
                .map(GenerationResultDto::toHit)
                .toList();
        return new GenerationResultDto(
                result.answer(),
                citations,
                result.generator(),
                result.disclaimer(),
                result.versionMismatchDetected(),
                result.answerContentSuppressed());
    }

    /**
     * 把依据片段映射为命中 DTO。
     *
     * <p>⚠️ {@code score / channel / reason} 三者是<b>检索期概念</b>，
     * 生成期并不重新打分。为避免误导（让学生以为 0 分＝不相关），
     * 这里显式标注通道为 {@link RetrievalChannel#GENERATION_BASIS}、
     * 原因为 {@link HitReason#CITED_AS_BASIS}，分数置 0 并在前端说明。
     */
    private static RetrievalResultDto.HitDto toHit(Chunk chunk) {
        return new RetrievalResultDto.HitDto(
                chunk.id(),
                chunk.text(),
                chunk.role().name(),
                0.0,
                chunk.edition().display(),
                chunk.locator().display(),
                RetrievalChannel.GENERATION_BASIS.name(),
                HitReason.CITED_AS_BASIS.name());
    }
}
