package cn.studykid.rag.premise.chunker;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.NonNull;

import cn.studykid.rag.core.chunk.Chunk;
import cn.studykid.rag.core.chunk.ChunkRole;
import cn.studykid.rag.core.chunk.LayoutFacts;
import cn.studykid.rag.premise.normalize.ChunkIdentifiers;

/**
 * 父子块装配器（RAG-D28 P2-1）——把切分器产出的顶层子块按小节聚合成父块。
 *
 * <p><b>模型</b>（D27 §5.2）：子块（定义/公式/例题/习题/提示/表格/图注）算 embedding、
 * 精确召回；父块（小节/知识点完整讲解，500～1000 字）送 LLM 阅读与引用展示。
 * 检索命中子块后由 {@code RetrievalPolicy.expandParents} 回带父块。
 *
 * <p><b>⚠️ 安全约束（本类存在的真正理由）：父块绝不聚合答案承载子块</b>。
 * 父块角色固定为 {@link ChunkRole#EXPLANATION}（非答案类），会通过讲解模式的
 * 泄题闸门。若把 {@link ChunkRole#EXERCISE_ANSWER}/{@link ChunkRole#EXAMPLE_SOLUTION}
 * 的文本拼进父块，标准答案就会随父块进入讲解 prompt——这是<b>结构性泄题</b>，
 * 比单个答案块被误判更隐蔽。因此答案类子块<b>不挂父块</b>（保持顶层独立），
 * 只在 VERIFY 模式按自身角色命中。
 *
 * <p><b>⚠️ 不侵入默认切分输出</b>：{@code HeadingHierarchyChunker} 的默认产物
 * 由 parity 测试逐字段锁定，本类作为<b>可选后处理</b>独立调用，
 * 不改变默认行为（与 P1 灰度开关同口径）。
 */
public final class ParentChildAssembler {

    private ParentChildAssembler() {
    }

    /**
     * 把顶层子块装配为「父块 + 挂接后的子块」。
     *
     * <p>输入应为切分器的原始输出（全部顶层块）。已进入父子结构的块原样保留，
     * 因此对同一批块重复调用是幂等的。
     *
     * @param documentId 文档 ID
     * @param chunks     顶层子块（顺序即阅读顺序）
     * @return 父块与子块的合并列表（父块置于其组内首个子块之前）
     */
    public static @NonNull List<Chunk> assemble(@NonNull String documentId,
                                                @NonNull List<Chunk> chunks) {
        Map<String, List<Chunk>> bySection = new LinkedHashMap<>();
        List<Chunk> result = new ArrayList<>();

        for (Chunk chunk : chunks) {
            // 幂等：已有父子关系的块不再装配，直接透传。
            if (chunk.isChild() || chunk.parentId() != null) {
                result.add(chunk);
                continue;
            }
            if (chunk.answerBearing()) {
                // ⚠️ 答案类子块不挂父块（防父块泄题），保持顶层独立。
                result.add(chunk);
                continue;
            }
            bySection.computeIfAbsent(chunk.locator().sectionPath(), k -> new ArrayList<>())
                    .add(chunk);
        }

        for (Map.Entry<String, List<Chunk>> entry : bySection.entrySet()) {
            String sectionPath = entry.getKey();
            List<Chunk> members = entry.getValue();
            if (members.size() < 2) {
                // 单块小节无需父块——聚合不出额外上下文，徒增一层。
                result.add(members.get(0));
                continue;
            }
            String parentId = ChunkIdentifiers.parentChunkId(documentId, sectionPath);
            Chunk parent = buildParent(documentId, sectionPath, parentId, members);
            result.add(parent);
            for (Chunk child : members) {
                result.add(child.asChildOf(parentId));
            }
        }
        return List.copyOf(result);
    }

    private static Chunk buildParent(String documentId, String sectionPath, String parentId,
                                     List<Chunk> members) {
        StringBuilder text = new StringBuilder();
        List<String> kpIds = new ArrayList<>();
        int charStart = Integer.MAX_VALUE;
        int charEnd = 0;
        int page = members.get(0).locator().pageNumber();
        for (Chunk member : members) {
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(member.text());
            for (String kp : member.knowledgePointIds()) {
                if (!kpIds.contains(kp)) {
                    kpIds.add(kp);
                }
            }
            charStart = Math.min(charStart, member.locator().charStart());
            charEnd = Math.max(charEnd, member.locator().charEnd());
        }
        String parentText = text.toString();
        Chunk.Locator locator = new Chunk.Locator(page, sectionPath, null, charStart, charEnd);
        return new Chunk(
                parentId,
                documentId,
                members.get(0).edition(),
                ChunkRole.EXPLANATION,
                parentText,
                locator,
                kpIds,
                null,
                ChunkIdentifiers.contentHash(parentText),
                // 父块是多个子块的上下文聚合，无单一版面事实来源 → null（不伪造）。
                null);
    }
}
