package cn.studykid.rag.core.chunk;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 片段角色判定的**版面事实**（RAG-D13 §3.2.4 片段校对的判定依据）。
 *
 * <p><b>⚠️ 为什么必须把判定依据持久化</b>：
 * 角色判定是<b>基于版面事实的确定性规则</b>（不是模型推断）：
 * <pre>
 *   在答案区 + 有题号        → EXERCISE_ANSWER
 *   在答案区 + 无题号        → EXAMPLE_SOLUTION
 *   块类型 ANSWER            → EXERCISE_ANSWER
 *   块首命中「解：」等前缀    → 有题号=答案 / 无题号=例题解答
 *   其余                     → EXPLANATION
 * </pre>
 * 若只存最终角色，校对员看到「这条被判成讲解」时**无从判断系统为什么这么判**——
 * 他只能凭感觉改，改完也无从复核。把三个事实（是否在答案区 / 块类型 / 是否命中前缀）
 * 一起存下来，校对页才能展示"系统依据什么下的结论"，纠正才有依据。
 *
 * <p><b>⚠️ 这些字段是"判定当时的快照"，不是可重算的派生值</b>：
 * 解析器版本升级后，同一个片段的重算结果可能不同。存快照才能解释
 * 「历史上为什么那样判」——重算会抹掉这段历史。
 *
 * @param inAnswerZone  是否位于答案区（版面级判定）
 * @param blockType     版面块类型（HEADING/PARAGRAPH/EXERCISE/ANSWER…）；旧数据为 null
 * @param matchedPrefix 块首命中的解答前缀（如「解：」）；未命中为 null
 */
public record LayoutFacts(
        boolean inAnswerZone,
        @Nullable String blockType,
        @Nullable String matchedPrefix
) {

    /**
     * 空事实（旧数据 / 非解析路径产生的片段）。
     *
     * <p>⚠️ 用 {@code false} 而不是"未知"三态：旧片段确实没有答案区信息，
     * 界面应显示"该片段早于版面事实采集，无判定依据"，而不是伪造一个"否"。
     * 是否为空由 {@link #isEmpty()} 判定，界面据此区分。
     */
    public static @NonNull LayoutFacts none() {
        return new LayoutFacts(false, null, null);
    }

    /**
     * @return 是否为「未采集」的空事实（区分"确实不在答案区"与"没有这个信息"）
     */
    public boolean isEmpty() {
        return !inAnswerZone && blockType == null && matchedPrefix == null;
    }

    /**
     * @return 人类可读的判定依据摘要（校对页直接展示）。
     */
    public @NonNull String describe() {
        if (isEmpty()) {
            return "该片段早于版面事实采集，无判定依据";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(inAnswerZone ? "位于答案区" : "不在答案区");
        if (blockType != null) {
            sb.append(" · 块类型 ").append(blockType);
        }
        if (matchedPrefix != null) {
            sb.append(" · 命中前缀「").append(matchedPrefix).append("」");
        }
        return sb.toString();
    }

    public LayoutFacts {
        if (blockType != null && blockType.isBlank()) {
            blockType = null;
        }
        if (matchedPrefix != null && matchedPrefix.isBlank()) {
            matchedPrefix = null;
        }
    }
}
