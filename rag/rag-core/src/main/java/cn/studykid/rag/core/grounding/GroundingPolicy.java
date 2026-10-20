package cn.studykid.rag.core.grounding;

import java.util.List;

import org.jspecify.annotations.NonNull;

import cn.studykid.rag.core.chunk.ChunkRole;

/**
 * 接地策略（RAG-D00 §2.7 决策 D-R09）——决定「什么样的回答才允许交付」。
 *
 * <p><b>⚠️ 核心立场</b>：本系统的正确行为<b>不是「总是给出答案」</b>，
 * 而是「<b>只给出可验证的答案</b>」。当语料不足以支撑某条声明时，
 * 正确输出是<b>明确的「依据不足」</b>，而不是退回到模型参数内知识
 * 补一段看似合理的解释。
 *
 * <p><b>⚠️ 为什么「退回参数内知识」在教辅场景是错的</b>：
 * 模型参数里的数学结论<b>没有版本、没有页码、没有教材依据</b>。
 * 在教辅场景中，学生的疑问是「<b>书上</b>这一页讲的是什么」，
 * 用无出处的内容回答，即便数学上正确，也是<b>答非所问</b>，
 * 且学生无法核对。这是 R-018 的实质。
 *
 * <p><b>因此接地策略是 fail-closed 的</b>：
 * 任何未被引用的实质声明，要么被删除，要么整条回答被降级为
 * 「无法从已授权语料得出结论」。
 */
public record GroundingPolicy(
        @NonNull double entailmentThreshold,
        @NonNull boolean requireCitationForEveryClaim,
        @NonNull boolean allowPartialWithDisclaimer,
        @NonNull boolean allowAnswerBearingCitations,
        @NonNull double minRetrieverScore
) {

    public GroundingPolicy {
        if (entailmentThreshold < 0.0 || entailmentThreshold > 1.0) {
            throw new IllegalArgumentException("entailmentThreshold 必须落在 [0,1]");
        }
        if (minRetrieverScore < 0.0 || minRetrieverScore > 1.0) {
            throw new IllegalArgumentException("minRetrieverScore 必须落在 [0,1]");
        }
    }

    /**
     * 生产默认策略。
     *
     * <p>⚠️ 初值取<b>保守</b>档；真实阈值须由评测集实测标定
     * （RAG-D08 §4），<b>不得</b>在没有评测数据的情况下调松。
     */
    public static @NonNull GroundingPolicy productionDefault() {
        return new GroundingPolicy(
                0.75,   // entailment 阈值：低于此值不得作为「已接地」输出
                true,   // 每条实质声明都必须有引用
                true,   // 允许部分接地 + 显式标注
                false,  // ⚠️ 默认禁止在讲解模式引用答案类内容
                0.35    // 检索得分下限：低于此值视为「未找到依据」
        );
    }

    /**
     * 判断某条引用是否允许出现在当前回答中。
     *
     * @param role 被引片段的角色
     * @return 是否允许
     */
    public boolean allowsRole(@NonNull ChunkRole role) {
        if (allowAnswerBearingCitations) {
            return true;
        }
        return !role.isAnswerBearing();
    }

    /**
     * 对一组判定结果做<b>整体裁决</b>，得出回答是否可交付。
     *
     * @param verdicts 每条声明的接地判定
     * @return 整体裁决
     */
    public @NonNull DeliveryDecision decide(@NonNull List<GroundingVerdict> verdicts) {
        if (verdicts.isEmpty()) {
            return new DeliveryDecision(false, "无实质声明", List.of());
        }
        List<GroundingVerdict> ungrounded = verdicts.stream()
                .filter(v -> v.verdict() == GroundingVerdict.Verdict.UNGROUNDED)
                .toList();
        if (!ungrounded.isEmpty() && requireCitationForEveryClaim) {
            return new DeliveryDecision(false,
                    "存在无依据声明（" + ungrounded.size() + " 条），依据 fail-closed 拒绝交付",
                    ungrounded.stream().map(GroundingVerdict::claim).toList());
        }
        List<GroundingVerdict> partial = verdicts.stream()
                .filter(v -> v.verdict() == GroundingVerdict.Verdict.PARTIALLY_GROUNDED
                        || v.entailmentScore() < entailmentThreshold)
                .toList();
        if (!partial.isEmpty() && !allowPartialWithDisclaimer) {
            return new DeliveryDecision(false,
                    "存在部分接地声明且策略不允许降级交付",
                    partial.stream().map(GroundingVerdict::claim).toList());
        }
        if (!partial.isEmpty()) {
            return new DeliveryDecision(true,
                    "部分声明依据较弱，已标注为「部分依据」",
                    partial.stream().map(GroundingVerdict::claim).toList());
        }
        return new DeliveryDecision(true, "全部声明均已接地", List.of());
    }

    /**
     * 交付裁决。
     *
     * @param deliverable 是否允许交付
     * @param reason 理由（可展示给用户）
     * @param flaggedClaims 被标记的声明
     */
    public record DeliveryDecision(
            boolean deliverable,
            @NonNull String reason,
            @NonNull List<String> flaggedClaims
    ) {

        public DeliveryDecision {
            flaggedClaims = flaggedClaims == null ? List.of() : List.copyOf(flaggedClaims);
        }
    }
}
