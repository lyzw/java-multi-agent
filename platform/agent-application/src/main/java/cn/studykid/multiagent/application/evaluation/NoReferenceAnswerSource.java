package cn.studykid.multiagent.application.evaluation;

import java.util.Optional;

import org.jspecify.annotations.NonNull;

/**
 * 默认 fail-closed 判分源：没有任何可用的参考答案 / 题库连接时返回 empty。
 *
 * <p>⚠️ 这是<b>安全默认值</b>，不是「功能已落地」。它确保：在 §13 第 3 项的判分源
 *   （题库标准解析）尚未由产品确认并接入前，L2 归因判分是<b>明确不启用</b>的，看板据此
 *   显示「未启用」而不是一个编造的 0%/100%。
 *
 * <p>接入真实判分源时：另写一个 {@link ReferenceAnswerSource} 实现（如查题库），本 bean
 *   由 {@code @ConditionalOnMissingBean} 自动让位（见 EvaluationConfiguration）。
 */
public class NoReferenceAnswerSource implements ReferenceAnswerSource {

    @Override
    public @NonNull Optional<ReferenceAnswer> resolve(@NonNull String scenario, @NonNull String question) {
        return Optional.empty();
    }
}
