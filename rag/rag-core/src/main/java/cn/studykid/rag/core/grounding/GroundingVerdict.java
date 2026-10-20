package cn.studykid.rag.core.grounding;

import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.rag.core.citation.Citation;

/**
 * 接地判定（Grounding Verdict）——防幻觉的<b>裁决结果</b>（RAG-D00 §2.7 决策 D-R09）。
 *
 * <p><b>⚠️ 为什么「有引用」不等于「已接地」</b>：
 * 模型可以在答案末尾堆上真实存在的引用（页码对、书名对），
 * 但正文里的论断与这些引用<b>毫无关系</b>。这叫<b>「装饰性引用」</b>
 * ——它能骗过「引用是否存在」的检查，却让整段回答<b>不可信</b>。
 *
 * <p>因此接地判定必须检查<b>蕴含关系</b>：被引用片段是否<b>真的支撑</b>
 * 它所标注的那句声明。
 *
 * <p><b>判定是三态而非二态</b>：{@code PARTIALLY} 的存在很重要——
 * 它对应「这一句只有部分被支撑」，其处理是<b>保留声明但标注</b>或
 * <b>降级为「部分依据」</b>，而不是把整段回答丢掉。
 * 二态判定会导致「一段话里有一句没依据就整段作废」，
 * 实践中会促使系统<b>倾向于少写</b>，最终诱发「无引用空话」。
 */
public record GroundingVerdict(
        @NonNull Verdict verdict,
        @NonNull String claim,
        @NonNull List<Citation> supportingCitations,
        @Nullable String reason,
        @NonNull double entailmentScore
) {

    public GroundingVerdict {
        if (verdict == null) {
            throw new IllegalArgumentException("verdict 不能为空");
        }
        if (claim == null || claim.isBlank()) {
            throw new IllegalArgumentException("claim 不能为空");
        }
        supportingCitations = supportingCitations == null ? List.of() : List.copyOf(supportingCitations);
        if (entailmentScore < 0.0 || entailmentScore > 1.0) {
            throw new IllegalArgumentException("entailmentScore 必须落在 [0,1]");
        }
    }

    /**
     * @param threshold 接地判定阈值
     * @return 是否<b>完全接地</b>（可直接输出）。
     */
    public boolean grounded(double threshold) {
        return verdict == Verdict.GROUNDED && entailmentScore >= threshold;
    }

    /** 判定结论。 */
    public enum Verdict {
        /** 完全接地：有引用且蕴含度达标。 */
        GROUNDED,
        /** 部分接地：有引用但蕴含度不足，需标注或降级。 */
        PARTIALLY_GROUNDED,
        /** 未接地：无引用支撑。 */
        UNGROUNDED,
        /** 引用冲突：引用的内容之间互相矛盾。 */
        CONFLICTING
    }
}
