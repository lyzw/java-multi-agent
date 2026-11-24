package cn.studykid.rag.premise.normalize;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.NonNull;

import cn.studykid.rag.core.chunk.Chunk;

/**
 * 跨文档题答配对器（RAG-D28 §2.2 / §3.3 P2-3）。
 *
 * <p><b>配对顺序（安全顺序，不可调换）</b>：
 * <ol>
 *   <li>题号未规范化 → 直接 UNMATCHED（不猜题）；</li>
 *   <li><b>同册优先</b>：同 documentId + 同作用域 + 规范题号完全相等；</li>
 *   <li>跨册：不同 documentId + 同作用域（edition 五轴 + 章节/试卷）+ 规范题号完全相等；</li>
 *   <li>任一候选池出现多条 → UNMATCHED（歧义不强绑）。</li>
 * </ol>
 *
 * <p><b>⚠️ 分组键与单级键不互配</b>：{@code GRP:Q005-Q007} 只和分组键相等，
 * 绝不回指 {@code Q005}——那是把「一整组题的答案」错绑到其中一道题。
 *
 * <p><b>⚠️ 逐字段带过</b>：{@link #rewriteExerciseRefs} 重建 {@code Chunk} 时
 * 必须把 knowledgePointIds / contentHash / layoutFacts / locator 全部带过
 * （项目铁律 #8），否则配对成功却丢掉溯源事实与角色判定依据。
 */
public final class QuestionAnswerPairer {

    private QuestionAnswerPairer() {
    }

    /**
     * 配对全部答案候选。
     *
     * @param items 题目与答案候选（顺序即处理顺序）
     * @return 每条答案一条结果（含 UNMATCHED），顺序与答案出现顺序一致
     */
    public static @NonNull PairingResult pair(@NonNull List<PairingItem> items) {
        if (items == null) {
            throw new IllegalArgumentException("items 不能为空");
        }
        Map<String, List<PairingItem>> questions = new LinkedHashMap<>();
        for (PairingItem item : items) {
            if (!item.answer() && item.key().matched()) {
                questions.computeIfAbsent(item.key().canonical(), k -> new ArrayList<>()).add(item);
            }
        }

        List<AnswerLink> links = new ArrayList<>();
        for (PairingItem answer : items) {
            if (!answer.answer()) {
                continue;
            }
            links.add(pairOne(answer, questions.getOrDefault(answer.key().canonical(), List.of())));
        }
        return new PairingResult(links);
    }

    private static AnswerLink pairOne(PairingItem answer, List<PairingItem> pool) {
        if (!answer.key().matched()) {
            return unmatched(answer, "题号未规范化，不猜题强绑");
        }
        if (pool.isEmpty()) {
            return unmatched(answer, "无同规范题号题目");
        }

        // ① 同册优先：同 documentId 且作用域一致
        List<PairingItem> sameDocument = new ArrayList<>();
        for (PairingItem q : pool) {
            if (q.chunk().documentId().equals(answer.chunk().documentId())
                    && q.scope().sameAs(answer.scope())) {
                sameDocument.add(q);
            }
        }
        if (sameDocument.size() == 1) {
            return matched(answer, sameDocument.get(0), PairingStatus.SAME_DOCUMENT,
                    "同册同题号同作用域");
        }
        if (sameDocument.size() > 1) {
            return unmatched(answer, "同册出现多个同题号候选（歧义不强绑）");
        }

        // ② 跨册：edition 五轴 + 章节/试卷 + 规范题号（题号已在 pool 键上相等）
        List<PairingItem> crossDocument = new ArrayList<>();
        for (PairingItem q : pool) {
            if (!q.chunk().documentId().equals(answer.chunk().documentId())
                    && q.scope().sameAs(answer.scope())) {
                crossDocument.add(q);
            }
        }
        if (crossDocument.size() == 1) {
            return matched(answer, crossDocument.get(0), PairingStatus.CROSS_DOCUMENT,
                    "跨册同版本同章节同题号");
        }
        if (crossDocument.size() > 1) {
            return unmatched(answer, "跨册出现多个同题号候选（歧义不强绑）");
        }
        return unmatched(answer, "同题号题目作用域不匹配（版本/章节不一致）");
    }

    private static AnswerLink matched(PairingItem answer, PairingItem question,
                                      PairingStatus status, String reason) {
        return new AnswerLink(answer.chunk().id(), question.chunk().id(),
                answer.key(), status, reason);
    }

    private static AnswerLink unmatched(PairingItem answer, String reason) {
        return new AnswerLink(answer.chunk().id(), null, answer.key(),
                PairingStatus.UNMATCHED, reason);
    }

    /**
     * 把配对结果写回答案片段的 {@code exerciseRef}（真实回指）。
     *
     * <p>题目片段与其他未参与配对的片段<b>原样返回</b>。
     * 同一答案片段若由多个小问配对（多键），写入共同父题键（如 {@code Q003}）；
     * 没有共同父题则写 null（不猜）。
     *
     * @param chunks 原始片段
     * @param result 配对结果
     * @return 回指重写后的片段（顺序不变）
     */
    public static @NonNull List<Chunk> rewriteExerciseRefs(@NonNull List<Chunk> chunks,
                                                           @NonNull PairingResult result) {
        if (chunks == null || result == null) {
            throw new IllegalArgumentException("chunks/result 不能为空");
        }
        Map<String, List<AnswerLink>> byAnswer = new LinkedHashMap<>();
        for (AnswerLink link : result.links()) {
            byAnswer.computeIfAbsent(link.answerChunkId(), k -> new ArrayList<>()).add(link);
        }
        List<Chunk> rewritten = new ArrayList<>(chunks.size());
        for (Chunk chunk : chunks) {
            List<AnswerLink> links = byAnswer.get(chunk.id());
            if (links == null || links.isEmpty()) {
                rewritten.add(chunk);
                continue;
            }
            List<String> canonicals = links.stream()
                    .filter(AnswerLink::matched)
                    .map(link -> link.key().canonical())
                    .distinct()
                    .toList();
            String ref = canonicals.isEmpty() ? null
                    : canonicals.size() == 1 ? canonicals.get(0) : commonParent(canonicals);
            // ⚠️ 铁律 #8：全参构造逐字段带过，只替换 exerciseRef。
            rewritten.add(new Chunk(
                    chunk.id(), chunk.documentId(), chunk.edition(), chunk.role(), chunk.text(),
                    chunk.locator(), chunk.knowledgePointIds(), ref, chunk.contentHash(),
                    chunk.layoutFacts()));
        }
        return List.copyOf(rewritten);
    }

    private static String commonParent(List<String> canonicals) {
        String parent = null;
        for (String canonical : canonicals) {
            String candidate = canonical.contains(".S")
                    ? canonical.substring(0, canonical.indexOf(".S")) : canonical;
            if (parent == null) {
                parent = candidate;
            } else if (!parent.equals(candidate)) {
                return null;
            }
        }
        return parent;
    }
}
