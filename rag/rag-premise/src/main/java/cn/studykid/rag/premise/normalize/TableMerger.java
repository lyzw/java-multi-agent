package cn.studykid.rag.premise.normalize;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.NonNull;

import cn.studykid.rag.ports.BlockType;
import cn.studykid.rag.ports.LayoutBlock;
import cn.studykid.rag.premise.model.PremiseBlock;

/**
 * 跨页表合并与超长表切片（P1-5b，D27 §4.5）。
 *
 * <p>三件事：
 * <ol>
 *   <li><b>表体合并</b>：相邻表格块（中间只允许 BOILERPLATE 页眉页脚）合并为同一逻辑表；</li>
 *   <li><b>表头继承</b>：续页表头与首页相同则剥除（不重复进正文），列结构以首页为准；</li>
 *   <li><b>切片重复表头</b>：合并后超过 {@code maxRows} 行时按行切片，每片重复表头，
 *       让「第 3 行第 2 列」这类定位在每个切片里都成立。</li>
 * </ol>
 *
 * <p><b>合并判据（两条，任一满足）</b>：
 * <ul>
 *   <li><b>强信号</b>：续页 {@link TableGrid#continuesPrev()}=true（MinerU 实测字段
 *       {@code continues_prev}）—— 但仍要求页码相邻且同章节，防止误合并不同表；</li>
 *   <li><b>Docling 兜底</b>：无显式信号时，要求 ① 页码严格相邻、② 章节路径相同、
 *       ③ 表头行文本相同（或续页无表头标记）、④ 列数相同。四条同时满足才合并——
 *       宁可漏合（两表独立可检索），不可错合（把两张无关表拼成一张）。</li>
 * </ul>
 *
 * <p><b>⚠️ 铁律 #8</b>：重建块时几何/bbox/置信度/题号/答案区标记全部逐字段带过，
 * 只重算 text 与 char 区间（否则撤回原文定位会失效）。
 *
 * <p>⚠️ 顶层类型（不得嵌套），见 {@code NestedTypeImportGuardTest}。
 */
public final class TableMerger {

    /** 默认单片最大行数（含表头）；超过则切片。工程初值，真实语料须复核。 */
    public static final int DEFAULT_MAX_ROWS = 30;

    private TableMerger() {
    }

    /**
     * 合并入口（默认 maxRows）。
     *
     * @param blocks 归一化块序列（按阅读顺序）
     * @return 合并/切片后的块序列（非表格块原样保留）
     */
    public static @NonNull List<PremiseBlock> merge(@NonNull List<PremiseBlock> blocks) {
        return merge(blocks, DEFAULT_MAX_ROWS);
    }

    /**
     * 合并入口。
     *
     * @param blocks  归一化块序列
     * @param maxRows 单片最大行数（含表头），须 ≥2
     * @return 合并/切片后的块序列
     */
    public static @NonNull List<PremiseBlock> merge(@NonNull List<PremiseBlock> blocks, int maxRows) {
        if (blocks == null) {
            throw new IllegalArgumentException("blocks 不能为空");
        }
        if (maxRows < 2) {
            throw new IllegalArgumentException("maxRows 必须 >= 2");
        }
        List<PremiseBlock> out = new ArrayList<>(blocks.size());
        int i = 0;
        while (i < blocks.size()) {
            PremiseBlock cur = blocks.get(i);
            if (!isStructuredTable(cur)) {
                out.add(cur);
                i++;
                continue;
            }
            // 收集连续表格块：中间只允许 BOILERPLATE（页眉页脚噪声）
            List<PremiseBlock> group = new ArrayList<>();
            group.add(cur);
            int j = i + 1;
            while (j < blocks.size()) {
                PremiseBlock probe = blocks.get(j);
                if (probe.type().isNoise()) {
                    j++; // 跳过中间的页眉页脚，继续观察下一块
                    continue;
                }
                if (isStructuredTable(probe) && canMerge(group.get(group.size() - 1), probe)) {
                    group.add(probe);
                    j++;
                    continue;
                }
                break;
            }
            List<PremiseBlock> merged = mergeGroup(group, maxRows);
            out.addAll(merged);
            i = j;
        }
        return List.copyOf(out);
    }

    private static boolean isStructuredTable(PremiseBlock b) {
        return b.type().isTable() && b.hasTableGrid();
    }

    /**
     * 合并判据：强信号（continues_prev）或 Docling 兜底四条件。
     */
    static boolean canMerge(PremiseBlock prev, PremiseBlock next) {
        TableGrid a = prev.tableGrid();
        TableGrid b = next.tableGrid();
        LayoutBlock la = prev.toLayoutBlock();
        LayoutBlock lb = next.toLayoutBlock();

        // 页码必须严格相邻（同页两张表不合并；跨页且中间隔多页也不合并）
        if (lb.pageNumber() != la.pageNumber() + 1) {
            return false;
        }
        // 章节路径必须相同
        if (!la.sectionPath().equals(lb.sectionPath())) {
            return false;
        }
        // 答案区属性必须一致（答案表不与正文表混合）
        if (la.inAnswerZone() != lb.inAnswerZone()) {
            return false;
        }
        if (b.continuesPrev()) {
            // 强信号：MinerU 显式说这是续表。但续表列数明显变化时仍拒绝（防止脏信号）。
            return a.columnCount() == b.columnCount();
        }
        // Docling 兜底：表头相同（或续页未标表头）+ 列数相同
        if (a.columnCount() != b.columnCount()) {
            return false;
        }
        if (b.headerRowCount() == 0) {
            return true; // 续页无表头标记 → 视为续表（继承首页表头）
        }
        return headerText(a).equals(headerText(b));
    }

    private static @NonNull String headerText(TableGrid grid) {
        if (grid.headerRowCount() <= 0 || grid.rows().isEmpty()) {
            return "";
        }
        return String.join("|", grid.rows().get(0));
    }

    /**
     * 合并一组表格块；超过 maxRows 则切片，每片重复表头。
     */
    private static @NonNull List<PremiseBlock> mergeGroup(List<PremiseBlock> group, int maxRows) {
        PremiseBlock first = group.get(0);
        TableGrid base = first.tableGrid();
        List<List<String>> rows = new ArrayList<>(base.rows());
        int headerRows = base.headerRowCount();

        for (int k = 1; k < group.size(); k++) {
            TableGrid cont = group.get(k).tableGrid();
            int skip = cont.headerRowCount();
            for (int r = skip; r < cont.rows().size(); r++) {
                rows.add(cont.rows().get(r));
            }
        }
        TableGrid merged = new TableGrid(rows, headerRows, false);

        if (merged.rowCount() <= maxRows) {
            return List.of(rebuild(first, merged));
        }

        // 切片：每片 = 表头 + 至多 (maxRows - headerRows) 个数据行
        List<List<String>> header = merged.rows().subList(0, headerRows);
        List<PremiseBlock> parts = new ArrayList<>();
        int dataPerPart = Math.max(1, maxRows - headerRows);
        int dataStart = headerRows;
        while (dataStart < merged.rowCount()) {
            int dataEnd = Math.min(dataStart + dataPerPart, merged.rowCount());
            List<List<String>> partRows = new ArrayList<>(header);
            partRows.addAll(merged.rows().subList(dataStart, dataEnd));
            TableGrid partGrid = new TableGrid(partRows, headerRows, false);
            parts.add(rebuild(first, partGrid));
            dataStart = dataEnd;
        }
        return List.copyOf(parts);
    }

    /**
     * 用合并/切片后的网格重建首块：text 与 char 区间重算，其余字段逐字段带过（铁律 #8）。
     */
    private static @NonNull PremiseBlock rebuild(PremiseBlock first, TableGrid grid) {
        LayoutBlock raw = first.toLayoutBlock();
        String text = grid.toMarkdown();
        LayoutBlock rebuilt = new LayoutBlock(
                BlockType.TABLE, text, raw.pageNumber(), raw.headingLevel(),
                raw.sectionPath(), raw.exerciseNo(), raw.inAnswerZone(),
                raw.charStart(), raw.charStart() + text.length());
        return first.withTable(rebuilt, grid);
    }
}
