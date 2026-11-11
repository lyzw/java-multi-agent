package cn.studykid.multiagent.application.evaluation;

import java.util.Optional;

import org.jspecify.annotations.NonNull;

/**
 * 默认 fail-closed 判分源：没有任何可用的参考答案 / 题库时返回 empty。
 *
 * <p>⚠️ 这是<b>安全默认值</b>，不是「功能已落地」。两个开关默认都关时，
 *   {@link EvaluationConfiguration} 装配的就是本实现 —— L2 归因判分<b>明确不启用</b>，
 *   看板显示「未启用 / 样本不足」，而不是一个编造的 0%/100%。
 *
 * <p>启用本地题库文件源后：另一个 {@link ReferenceAnswerSource} 实现接管，
 *   本 bean 由 {@code @ConditionalOnMissingBean} 自动让位。后续换 DB / HTTP 题库同理。
 */
public class NoReferenceAnswerSource implements ReferenceAnswerSource {

    @Override
    public @NonNull Optional<ReferenceAnswer> resolve(@NonNull String questionId) {
        return Optional.empty();
    }
}
