package cn.studykid.rag.premise.normalize;

/**
 * 规范题号的作用域锚点（RAG-D28 §2.2：{@code edition_id + section_path} 或 {@code paper_id}）。
 *
 * <p><b>⚠️ 为什么题号必须带作用域</b>：不同册、不同章节都可能有「第 3 题」，
 * 只按题号配对会把七年级上册第 3 题的答案绑到八年级下册第 3 题上。
 * 版本五轴由 {@code editionId} 代表（rag_edition 五轴唯一，见 RAG-D16），
 * 章节映射由 {@code sectionPath} 代表；试卷场景以 {@code paperId} 为锚。
 *
 * <p><b>⚠️ 跨册配对要求 scope 严格相等</b>：任一轴缺失即判不同作用域——
 * 缺信息时"看起来同版本"不是证据，宁可记 UNMATCHED 也不强绑。
 *
 * <p>⚠️ 顶层类型（不得嵌套），规则来源见 {@code PremiseTopLevelTypeGuardTest}。
 *
 * @param editionId   版本坐标 ID（五轴唯一；教材场景必填）
 * @param sectionPath 章节路径（教材场景必填，用于跨册章节映射）
 * @param paperId     试卷 ID（试卷场景必填）
 */
public record ExerciseScope(String editionId, String sectionPath, String paperId) {

    public ExerciseScope {
        if (isBlank(editionId) && isBlank(paperId)) {
            throw new IllegalArgumentException(
                    "作用域锚点缺失：edition_id + section_path 或 paper_id 至少一组");
        }
    }

    /**
     * 教材作用域。
     *
     * @param editionId   版本坐标 ID
     * @param sectionPath 章节路径
     * @return 作用域
     */
    public static ExerciseScope ofEdition(String editionId, String sectionPath) {
        return new ExerciseScope(editionId, sectionPath, null);
    }

    /**
     * 试卷作用域。
     *
     * @param paperId 试卷 ID
     * @return 作用域
     */
    public static ExerciseScope ofPaper(String paperId) {
        return new ExerciseScope(null, null, paperId);
    }

    /**
     * 严格作用域相等判定（跨文档配对的安全闸门）。
     *
     * @param other 待比较作用域
     * @return 同版本同章节（或同试卷）返回 true；信息不足返回 false
     */
    public boolean sameAs(ExerciseScope other) {
        if (other == null) {
            return false;
        }
        if (!isBlank(paperId) || !isBlank(other.paperId())) {
            return !isBlank(paperId) && paperId.equals(other.paperId());
        }
        return !isBlank(editionId) && editionId.equals(other.editionId())
                && !isBlank(sectionPath) && sectionPath.equals(other.sectionPath());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
