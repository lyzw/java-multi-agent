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
        @NonNull String contentHash,
        /**
         * 角色判定的版面事实快照（RAG-D13 §3.2.4）。
         *
         * <p>⚠️ 为 null 表示「未采集」（旧数据 / 非解析路径产生的片段），
         * 界面应显示"无判定依据"，而不是伪造一个"不在答案区"。
         */
        @Nullable LayoutFacts layoutFacts,
        /**
         * 父块 ID（RAG-D28 P2-1 父子块模型）。
         *
         * <p>⚠️ 为 null 表示该块是<b>顶层块</b>（独立语义单元或父块）；
         * 非空时本块是子块，{@link #chunkLevel()}={@link #LEVEL_CHILD}，
         * 检索命中本块时由 {@code RetrievalPolicy.expandParents} 回带父块送 LLM。
         */
        @Nullable String parentId,
        /**
         * 层级：{@link #LEVEL_PARENT}=0 父块（上下文聚合），
         * {@link #LEVEL_CHILD}=1 子块（精确召回/算 embedding）。
         *
         * <p>⚠️ 既有无父子概念的自然块默认 0（顶层）——它们本身就是按结构切出的
         * 完整语义单元，不因为新增字段而被降级成"孤儿子块"。
         */
        int chunkLevel,
        /**
         * 公式的 embedding 语义视图（RAG-D28 P3-1 / P1-6，受控词典命中才有）。
         *
         * <p>⚠️ 取自 {@code FormulaAnnotation.embeddingView()}：命中时＝LaTeX + 人工审核的
         * 自然语言 + 符号含义；未命中时只有规范化 LaTeX（fail-closed，不猜语义）。
         * 为 null 表示本片段不是公式块（或切分路径无 PremiseBlock 语义）——
         * 不伪造一个描述，模板渲染时该槽位留空。
         */
        @Nullable String formulaNaturalLanguage,
        /**
         * 图注/表格摘要的 embedding 文本（RAG-D28 P3-1）。
         *
         * <p>⚠️ 表格块由 {@code TableGrid} 的确定性结构摘要生成（行列数 + 表头），
         * 与原文网格绑定，不生成自然语言；图片块当前无 VLM caption（P1-7 未落地），
         * 留 null。为 null 表示无可用摘要，模板不编造。
         */
        @Nullable String captionOrTableSummary
) {

    /** 父块层级：上下文聚合，送 LLM 阅读与引用展示。 */
    public static final int LEVEL_PARENT = 0;

    /** 子块层级：精确召回，计算 embedding。 */
    public static final int LEVEL_CHILD = 1;

    /**
     * 向后兼容构造（不含版面事实）。
     *
     * <p>⚠️ 保留原因：既有单测与仓储映射按 9 参构造，
     * 而版面事实是"有则更好"的增强信息——缺失时角色判定逻辑完全不受影响
     * （判定发生在切分时，用 `LayoutBlock` 的原始值）。
     */
    public Chunk(@NonNull String id, @NonNull String documentId,
                 @NonNull EditionCoordinate edition, @NonNull ChunkRole role,
                 @NonNull String text, @NonNull Locator locator,
                 @NonNull List<String> knowledgePointIds, @Nullable String exerciseRef,
                 @NonNull String contentHash) {
        this(id, documentId, edition, role, text, locator,
                knowledgePointIds, exerciseRef, contentHash, null);
    }

    /**
     * 向后兼容构造（含版面事实，无父子字段）。
     *
     * <p>⚠️ 逐字段带过（铁律 #8）：父子字段以"顶层块"默认值补齐，
     * 不因兼容构造静默丢失既有任何字段。
     */
    public Chunk(@NonNull String id, @NonNull String documentId,
                 @NonNull EditionCoordinate edition, @NonNull ChunkRole role,
                 @NonNull String text, @NonNull Locator locator,
                 @NonNull List<String> knowledgePointIds, @Nullable String exerciseRef,
                 @NonNull String contentHash, @Nullable LayoutFacts layoutFacts) {
        this(id, documentId, edition, role, text, locator,
                knowledgePointIds, exerciseRef, contentHash, layoutFacts, null, LEVEL_PARENT,
                null, null);
    }

    /**
     * 向后兼容构造（含版面事实与父子字段，无 embedding 语义槽位）。
     *
     * <p>⚠️ 铁律 #8：本构造保留给既有调用方；新增的公式/摘要槽位以 null 补齐，
     * 表示"该路径不产语义"，由专门的全参构造逐字段带过。
     */
    public Chunk(@NonNull String id, @NonNull String documentId,
                 @NonNull EditionCoordinate edition, @NonNull ChunkRole role,
                 @NonNull String text, @NonNull Locator locator,
                 @NonNull List<String> knowledgePointIds, @Nullable String exerciseRef,
                 @NonNull String contentHash, @Nullable LayoutFacts layoutFacts,
                 @Nullable String parentId, int chunkLevel) {
        this(id, documentId, edition, role, text, locator,
                knowledgePointIds, exerciseRef, contentHash, layoutFacts,
                parentId, chunkLevel, null, null);
    }

    /** @return 是否为子块（有父块且层级为 1）。 */
    public boolean isChild() {
        return parentId != null && chunkLevel == LEVEL_CHILD;
    }

    /**
     * 派生一个指向父块的子块版本（全字段逐字段带过，铁律 #8）。
     *
     * @param newParentId 父块 ID
     * @return 子块副本
     */
    public @NonNull Chunk asChildOf(@NonNull String newParentId) {
        return new Chunk(id, documentId, edition, role, text, locator,
                knowledgePointIds, exerciseRef, contentHash, layoutFacts,
                newParentId, LEVEL_CHILD, formulaNaturalLanguage, captionOrTableSummary);
    }

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
        if (chunkLevel != LEVEL_PARENT && chunkLevel != LEVEL_CHILD) {
            throw new IllegalArgumentException("chunkLevel 只能是 0（父）或 1（子），实际 " + chunkLevel);
        }
        if (chunkLevel == LEVEL_CHILD && (parentId == null || parentId.isBlank())) {
            throw new IllegalArgumentException("子块必须有非空 parentId");
        }
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
