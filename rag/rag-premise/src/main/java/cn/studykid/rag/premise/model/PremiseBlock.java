package cn.studykid.rag.premise.model;

import java.util.List;

import org.jspecify.annotations.NonNull;

import cn.studykid.rag.ports.BlockType;
import cn.studykid.rag.ports.LayoutBlock;

/**
 * 归一化版面块（RAG-D26 §1.3）。
 *
 * <p><b>⚠️ 为什么是「包装 {@link LayoutBlock}」而不是「逐字段复制一份」</b>：
 * RAG-D26 §4 列出的头号风险是「双词汇表漂移」——
 * {@code PremiseBlock} 与 {@code LayoutBlock} 若各自持有一份字段，
 * 任何一边的演进（如 LayoutBlock 增删字段）都可能让另一边静默错位，
 * 而角色判定恰恰依赖这些字段（答案区 / 题号 / 字符区间）。
 * 本类直接持有 {@link LayoutBlock} 实例：
 * <ul>
 *   <li>九个语义字段的访问器全部<b>委托</b>给被包装实例——不存在第二份副本；</li>
 *   <li>{@link #toLayoutBlock()} 是<b>恒等解包</b>——降维零漂移；</li>
 *   <li>后端扩展字段（页内 bbox、置信度）作为<b>附加</b>成员挂在包装层，
 *       不侵入既有端口类型。</li>
 * </ul>
 *
 * <p><b>扩展字段的口径</b>（为 MinerU / PP-StructureV3 接入预留，RAG-D26 §2.2）：
 * <ul>
 *   <li>{@code pageBbox}：页内几何框 {@code [x0, y0, x1, y1]}（后端坐标系原样保留）；
 *       后端未提供时为 {@code null}，用 {@link #hasBbox()} 判定，<b>不得</b>用空框冒充；</li>
 *   <li>{@code confidence}：后端给出的块级置信度；未提供时为 {@code NaN}，
 *       判定用 {@link #hasConfidence()}。</li>
 * </ul>
 *
 * <p>⚠️ 顶层类型（不得嵌套），规则来源见 {@code NestedTypeImportGuardTest}（RAG-D09 §6）。
 */
public final class PremiseBlock {

    private final LayoutBlock layout;
    private final double[] pageBbox;
    private final double confidence;

    /**
     * @param layout     被包装的既有端口块（权威数据源，不可为空）
     * @param pageBbox   页内几何框 [x0,y0,x1,y1]（可为 null）
     * @param confidence 后端置信度（未知传 {@link Double#NaN}）
     */
    public PremiseBlock(@NonNull LayoutBlock layout, double[] pageBbox, double confidence) {
        if (layout == null) {
            throw new IllegalArgumentException("layout 不能为空");
        }
        this.layout = layout;
        if (pageBbox != null && pageBbox.length != 4) {
            throw new IllegalArgumentException("pageBbox 必须是 [x0,y0,x1,y1] 四元组，实际长度 "
                    + pageBbox.length);
        }
        this.pageBbox = pageBbox == null ? null : pageBbox.clone();
        this.confidence = confidence;
    }

    /**
     * 从既有端口块构造（无扩展信息）。
     *
     * @param layout 既有端口块
     * @return 归一化块
     */
    public static @NonNull PremiseBlock fromLayoutBlock(@NonNull LayoutBlock layout) {
        return new PremiseBlock(layout, null, Double.NaN);
    }

    /**
     * 批量构造。
     *
     * @param layouts 既有端口块序列
     * @return 归一化块序列
     */
    public static @NonNull List<PremiseBlock> fromLayoutBlocks(@NonNull List<LayoutBlock> layouts) {
        return layouts.stream().map(PremiseBlock::fromLayoutBlock).toList();
    }

    /** @return 被包装的既有端口块（恒等解包，零字段复制） */
    public @NonNull LayoutBlock toLayoutBlock() {
        return layout;
    }

    /** @return 块类型 */
    public @NonNull BlockType type() {
        return layout.type();
    }

    /** @return 文本内容 */
    public @NonNull String text() {
        return layout.text();
    }

    /** @return 物理页码（1 起） */
    public int pageNumber() {
        return layout.pageNumber();
    }

    /** @return 标题层级（1 表示章，0 表示非标题） */
    public int headingLevel() {
        return layout.headingLevel();
    }

    /** @return 所属章节路径（解析器给出的原始值） */
    public @NonNull String sectionPath() {
        return layout.sectionPath();
    }

    /** @return 识别出的题号（非题目块为 null） */
    public String exerciseNo() {
        return layout.exerciseNo();
    }

    /** @return 是否位于答案区（版面级判定） */
    public boolean inAnswerZone() {
        return layout.inAnswerZone();
    }

    /** @return 该页字符流起始偏移 */
    public int charStart() {
        return layout.charStart();
    }

    /** @return 结束偏移 */
    public int charEnd() {
        return layout.charEnd();
    }

    /** @return 页内几何框（未提供为 null） */
    public double[] pageBbox() {
        return pageBbox == null ? null : pageBbox.clone();
    }

    /** @return 后端是否提供了页内几何框 */
    public boolean hasBbox() {
        return pageBbox != null;
    }

    /** @return 后端置信度（未提供为 {@code NaN}） */
    public double confidence() {
        return confidence;
    }

    /** @return 后端是否提供了置信度 */
    public boolean hasConfidence() {
        return !Double.isNaN(confidence);
    }
}
