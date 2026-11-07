package cn.studykid.rag.ports;

import java.util.List;

import org.jspecify.annotations.NonNull;

import cn.studykid.rag.core.chunk.Chunk;

/**
 * 生成侧大模型端口（RAG 接地讲解的「合成」环节）。
 *
 * <p><b>职责边界</b>：本端口<b>只负责把给定的依据片段合成自然语言讲解</b>，
 * 不负责检索、不负责依据校验、不负责角色/版本闸门——那些由
 * {@code GroundedGenerationService} 在调用本端口<b>之前</b>完成。
 * 这样「先校验依据、再让模型基于已校验依据说话」的顺序被编码进类型，
 * 模型永远拿不到未通过闸门的片段。
 *
 * <p><b>⚠️ 必须是「接地」合成</b>：调用方传入的 {@code basis} 是已通过
 * 租户/存在性/角色/版本四道闸门的逐字片段；端口实现须在提示里要求模型
 * <b>只能使用这些片段作答</b>，不得引入片段之外的事实或结论。
 * 这是对 {@code GroundedGenerationService} 反幻觉底线的外部约束——
 * 端口实现若违反，等于把「编造」伪装成「讲解」。
 *
 * <p><b>失败语义</b>：模型不可用 / 超时 / 返回空 / 非 2xx，一律抛
 * {@link GenerationModelException}；调用方据此<b>回落到逐字摘录</b>
 * （fail-soft：保证学生至少能看到可核对的原文，而不是一段无出处的话）。
 */
public interface GenerationModelPort {

    /**
     * 基于给定依据片段合成讲解。
     *
     * @param query     学生的原始问题（用于组织讲解结构，不用于检索）
     * @param modeLabel 讲解模式的中文标签（讲解 / 解题 / 核对 / 概览）
     * @param basis     已通过全部闸门的逐字依据片段（顺序即引用编号）
     * @return 模型生成的自然语言讲解（不含逐字引用块；引用块由调用方强制追加）
     * @throws GenerationModelException 模型调用失败 / 返回空 / 非法响应
     */
    @NonNull String synthesize(@NonNull String query, @NonNull String modeLabel,
                              @NonNull List<Chunk> basis);
}
