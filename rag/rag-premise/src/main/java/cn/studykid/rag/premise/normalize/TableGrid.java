package cn.studykid.rag.premise.normalize;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.NonNull;

/**
 * 表格的二维网格中间表示（P1-5）。
 *
 * <p><b>为什么单独建模型而不是直接拼 Markdown</b>：P1-5 的三件事——
 * ① 跨页表体合并、② 续页表头继承、③ 超长表按行切片并重复表头——
 * 都需要对「行」这个单位做结构化操作。若 mapper 直接输出一坨 Markdown 字符串，
 * 下游只能按换行再切回来，既脆弱又会把转义过的 {@code \|} 认错。
 * 因此两个 mapper 统一先把后端表格结构落成 {@code TableGrid}，
 * 再由 {@link #toMarkdown()} 渲染成进入 chunk/embedding 的文本。
 *
 * <p><b>表头口径</b>：{@code headerRowCount} 表示「真正的表头行数」。
 * Docling 用 {@code column_header=true} 标注；MinerU 用 {@code <th>} 标注。
 * 渲染成 Markdown 时，若后端没给表头标记（{@code headerRowCount==0}），
 * 仍按 Markdown 语法把第一行当作表头行渲染（但元数据里如实记 0，不伪造）。
 *
 * <p><b>合并单元格</b>：后端一个 cell 覆盖多行多列时，文本只放在左上角槽位，
 * 被覆盖的其余槽位留空字符串（Markdown 表格无法表达 span，不重复文本以免放大检索信号）。
 *
 * <p>⚠️ 顶层类型（不得嵌套），规则来源见 {@code NestedTypeImportGuardTest}（RAG-D09 §6）。
 */
public final class TableGrid {

    /** 单行内的 cell 文本（已 trim，不含 span 占位重复）。 */
    private final List<List<String>> rows;

    /** 后端明确标注的表头行数；0 表示后端未标注（不伪造）。 */
    private final int headerRowCount;

    /**
     * @param rows            行×列的 cell 文本矩阵（每行至少 1 个 cell）
     * @param headerRowCount  后端标注的表头行数，须在 [0, rows.size()] 内
     */
    public TableGrid(@NonNull List<List<String>> rows, int headerRowCount) {
        if (rows == null) {
            throw new IllegalArgumentException("rows 不能为空");
        }
        List<List<String>> copy = new ArrayList<>(rows.size());
        for (List<String> row : rows) {
            if (row == null || row.isEmpty()) {
                throw new IllegalArgumentException("表格每一行至少要有 1 个 cell");
            }
            copy.add(List.copyOf(row));
        }
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("表格至少要有 1 行");
        }
        if (headerRowCount < 0 || headerRowCount > copy.size()) {
            throw new IllegalArgumentException(
                    "headerRowCount 超范围：" + headerRowCount + "，行数=" + copy.size());
        }
        this.rows = List.copyOf(copy);
        this.headerRowCount = headerRowCount;
    }

    /** @return 行矩阵（不可变） */
    public @NonNull List<List<String>> rows() {
        return rows;
    }

    /** @return 后端标注的表头行数（0 = 未标注） */
    public int headerRowCount() {
        return headerRowCount;
    }

    /** @return 行数 */
    public int rowCount() {
        return rows.size();
    }

    /** @return 最大列数（各行列数可能不同，取最大） */
    public int columnCount() {
        int max = 0;
        for (List<String> row : rows) {
            max = Math.max(max, row.size());
        }
        return max;
    }

    /**
     * 渲染为 Markdown 表格文本。
     *
     * <p>第一行（或后端标注的表头区）作为 Markdown 表头，紧跟 {@code |---|} 分隔行；
     * cell 内的 {@code |} 转义为 {@code \|}，换行压成空格（避免破坏行结构）。
     *
     * @return Markdown 表格文本
     */
    public @NonNull String toMarkdown() {
        int cols = columnCount();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            appendRow(sb, rows.get(i), cols);
            if (i == 0) {
                // Markdown 语法要求表头后紧跟分隔行；表头行数 >1 时，其余表头行按数据行渲染。
                sb.append("|");
                for (int c = 0; c < cols; c++) {
                    sb.append(" --- |");
                }
                sb.append('\n');
            }
        }
        return sb.toString().stripTrailing();
    }

    private static void appendRow(StringBuilder sb, List<String> row, int cols) {
        sb.append("|");
        for (int c = 0; c < cols; c++) {
            String cell = c < row.size() ? row.get(c) : "";
            sb.append(' ').append(escape(cell)).append(" |");
        }
        sb.append('\n');
    }

    /** cell 转义：管道符转义、换行/制表压成空格。 */
    private static @NonNull String escape(@NonNull String cell) {
        return cell.replace("\\", "\\\\")
                .replace("|", "\\|")
                .replace("\r", " ")
                .replace("\n", " ")
                .replace("\t", " ")
                .trim();
    }
}
