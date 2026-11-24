package cn.studykid.rag.premise.normalize;

import java.util.List;

import org.jspecify.annotations.NonNull;

/**
 * 题答配对输出（RAG-D28 §3.3 P2-3）。
 *
 * <p>⚠️ 未配对答案必须有显式 {@link AnswerLink}，不能从列表里"消失"——
 * 否则抽检只能看到成功项，UNMATCHED 被静默吞掉（假绿）。
 *
 * <p>⚠️ 顶层类型（不得嵌套），规则来源见 {@code PremiseTopLevelTypeGuardTest}。
 *
 * @param links 全部答案的配对结果（顺序即答案输入顺序）
 */
public record PairingResult(@NonNull List<AnswerLink> links) {

    public PairingResult {
        links = links == null ? List.of() : List.copyOf(links);
    }

    /** @return 配对成功条数 */
    public int matchedCount() {
        return (int) links.stream().filter(AnswerLink::matched).count();
    }

    /** @return 未配对条数（必须可计数，供门禁统计） */
    public int unmatchedCount() {
        return links.size() - matchedCount();
    }
}
