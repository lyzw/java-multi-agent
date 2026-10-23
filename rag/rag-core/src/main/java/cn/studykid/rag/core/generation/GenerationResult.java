package cn.studykid.rag.core.generation;

import java.util.List;

import org.jspecify.annotations.NonNull;

import cn.studykid.rag.core.chunk.Chunk;

/**
 * 生成结果（RAG-D00 §2.7 决策 D-R09 的出口契约）。
 *
 * <p><b>⚠️ 本结果的核心不变量：{@link #answer} 的每一个字都来自
 * {@link #basis} 中各片段的逐字原文或由其派生的事实性陈述</b>
 * （片段条数、版本名、页码）。不存在「模型补充的知识」——
 * 这是教辅场景下唯一可接受的生成形态，理由见
 * {@link cn.studykid.rag.core.grounding.GroundingPolicy}：
 * 模型参数里的数学结论没有版本、没有页码，学生无法核对，
 * 答得再对也是答非所问。
 *
 * <p><b>⚠️ {@link #generator} 必须如实标注生成方式</b>：
 * 在真实大模型接入前（RAG-D09 G3 条件，当前受 A-008 阻断），
 * 它恒为 {@link #GENERATOR_EXCERPT_ASSEMBLY}。
 * 把它说成「AI 生成」是对用户的欺骗；前端必须原样展示这个字段。
 *
 * @param answer 讲解正文（空串表示「依据不足」，此时应读 {@link #disclaimer}）
 * @param basis 实际构成讲解的依据片段（已通过角色与版本硬过滤）
 * @param generator 生成方式标识（诚实标注，非营销话术）
 * @param disclaimer 必须随回答一同展示的说明（为空串表示无附加说明）
 * @param versionMismatchDetected 依据中是否存在非首选版本内容
 * @param answerContentSuppressed 是否因模式过滤丢弃过答案类内容
 */
public record GenerationResult(
        @NonNull String answer,
        @NonNull List<Chunk> basis,
        @NonNull String generator,
        @NonNull String disclaimer,
        boolean versionMismatchDetected,
        boolean answerContentSuppressed
) {

    /**
     * 逐字摘录装配器。
     *
     * <p>⚠️ 这是当前唯一可用的生成方式：把调用方指定的依据片段
     * <b>逐字</b>摘录出来，加上版本与页码，不加任何改写。
     * 它不产生新知识，因此不可能幻觉——代价是它也不会「讲得更好」。
     */
    public static final String GENERATOR_EXCERPT_ASSEMBLY = "EXCERPT_ASSEMBLY";

    public GenerationResult {
        if (answer == null) {
            throw new IllegalArgumentException("answer 不能为空");
        }
        if (basis == null) {
            throw new IllegalArgumentException("basis 不能为空");
        }
        if (generator == null || generator.isBlank()) {
            throw new IllegalArgumentException("generator 不能为空：生成方式必须如实标注");
        }
        if (disclaimer == null) {
            throw new IllegalArgumentException("disclaimer 不能为空");
        }
        basis = List.copyOf(basis);
    }

    /**
     * 构造「依据不足」结果。
     *
     * <p>⚠️ <b>「找不到依据」是一个合法且必须被允许的输出</b>。
     * 若此处退化为「给一段泛泛的解释」，就复现了
     * {@link cn.studykid.rag.core.grounding.GroundingPolicy} 明确反对的行为：
     * 用无出处的内容补齐回答。
     *
     * @param reason 依据不足的原因（可直接展示给学生）
     * @return 空讲解 + 原因说明
     */
    public static @NonNull GenerationResult insufficient(@NonNull String reason) {
        return new GenerationResult("", List.of(), GENERATOR_EXCERPT_ASSEMBLY,
                reason, false, false);
    }

    /**
     * @return 是否有可交付的讲解内容。
     */
    public boolean hasAnswer() {
        return !answer.isBlank() && !basis.isEmpty();
    }
}
