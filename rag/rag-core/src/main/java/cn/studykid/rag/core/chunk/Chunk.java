package cn.studykid.rag.core.chunk;

import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.rag.core.knowledge.KnowledgePoint;
import cn.studykid.rag.core.version.EditionCoordinate;

/**
 * 语料片段（RAG-D00 §2.5 决策 D-R07）。
 *
 * <p><b>切分策略：结构优先，语义兜底</b>（RAG-D04 §3）。
 *
 * <p><b>⚠️ 为什么不按固定 token 窗口切</b>：
 * 教辅的语义单元是<b>版面结构单元</b>（知识点段落、每一道题、每一条答案），
 * 不是字符窗口。固定窗口会产生三类致命碎片：
 * <ul>
 *   <li><b>题干与选项被切开</b> → 检索到半截题目，答案对不上；</li>
 *   <li><b>答案与解析被切开</b> → 泄题或错配；</li>
 *   <li><b>定义与其适用条件被切开</b> → 检索到「三角形内角和等于 180°」
 *       却漏掉「在<b>平面内</b>的三角形」这个前提，
 *       空间几何题会因此答错。</li>
 * </ul>
 *
 * <p><b>因此</b>：切片边界由<b>版面结构</b>决定（标题层级、题号、答案区标识）；
 * 仅当结构不可得时，才退化为<b>带重叠</b>的语义切分。
 *
 * <p><b>溯源字段</b>（{@code locator}）是本类型存在的核心理由：
 * 教辅引用必须精确到「哪一册、哪一版、第几页、第几题」，
 * 否则学生无法核对，引用就不可验证。
 */
public record Chunk(
        @NonNull String id,
        @NonNull String documentId,
        @NonNull EditionCoordinate edition,
        @NonNull ChunkRole role,
        @NonNull String text,
        @NonNull Locator locator,
        @NonNull List<String> knowledgePointIds,
        @Nullable String exerciseRef,
        @NonNull String contentHash
) {

    public Chunk {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id 不能为空");
        }
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("documentId 不能为空");
        }
        if (edition == null) {
            throw new IllegalArgumentException("edition 不能为空");
        }
        if (role == null) {
            throw new IllegalArgumentException("role 不能为空");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text 不能为空");
        }
        if (locator == null) {
            throw new IllegalArgumentException("locator 不能为空");
        }
        knowledgePointIds = knowledgePointIds == null ? List.of() : List.copyOf(knowledgePointIds);
    }

    /**
     * @return 是否承载答案类敏感内容。
     */
    public boolean answerBearing() {
        return role.isAnswerBearing();
    }

    /**
     * @return 是否可以与给定知识点关联。
     */
    public boolean linkedTo(@NonNull KnowledgePoint point) {
        return knowledgePointIds.contains(point.id());
    }

    /**
     * 定位信息——引用可验证性的物理基础。
     *
     * @param pageNumber 物理页码（PDF 页序号，1 起）
     * @param sectionPath 章节路径（如「第3章 > 3.2 二次函数的图像」）
     * @param exerciseNo 题号（题干/答案/提示类片段有；正文类为 null）
     * @param charStart 片段在该页原文字符流中的起始偏移
     * @param charEnd 结束偏移
     */
    public record Locator(
            int pageNumber,
            @NonNull String sectionPath,
            @Nullable String exerciseNo,
            int charStart,
            int charEnd
    ) {

        public Locator {
            if (pageNumber < 1) {
                throw new IllegalArgumentException("pageNumber 必须 >= 1");
            }
            if (sectionPath == null) {
                throw new IllegalArgumentException("sectionPath 不能为空");
            }
            if (charStart < 0 || charEnd < charStart) {
                throw new IllegalArgumentException("char 区间非法");
            }
        }

        /**
         * @return 面向学生的引用文本，如「第 3 章 > 3.2 二次函数的图像 · 第 45 页 · 第 12 题」。
         */
        public @NonNull String display() {
            StringBuilder sb = new StringBuilder();
            sb.append(sectionPath).append(" · 第 ").append(pageNumber).append(" 页");
            if (exerciseNo != null && !exerciseNo.isBlank()) {
                sb.append(" · 第 ").append(exerciseNo).append(" 题");
            }
            return sb.toString();
        }
    }
}
