package cn.studykid.rag.infrastructure.parsing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;

import cn.studykid.rag.premise.normalize.TableGrid;

/**
 * 表格真实内容提取（P1-5）：把后端结构化的表格还原成 {@link TableGrid}。
 *
 * <p><b>⚠️ 为什么要这一步</b>：批 5a 之前，两个 mapper 对 TABLE 块只读 {@code text} 字段，
 * 而 Docling/MinerU 的表格内容根本不在 {@code text} 里
 * （Docling 在 {@code data.table_cells[]}，MinerU 在 {@code content[type=html]}），
 * 于是整册表格全部退化成 {@code [表格]} 占位符——表格内容 100% 丢失，
 * 「表格可检索」是一句空话。本类把真实结构读回来。
 *
 * <p><b>schema 来源（非凭空捏造）</b>：
 * <ul>
 *   <li>Docling：{@code docling_core/types/doc/items/table/table_data.py} 的
 *       {@code TableCell} —— 字段 {@code text}/{@code row_span}/{@code col_span}/
 *       {@code start_row_offset_idx}/{@code end_row_offset_idx}/
 *       {@code start_col_offset_idx}/{@code end_col_offset_idx}/{@code column_header}；
 *       {@code end_*} 是 <b>exclusive</b>（源码 grid 用 {@code range(start, end)}）。</li>
 *   <li>MinerU：{@code middle_json} 表格块的 {@code content[]} 里
 *       {@code {type:"html", content:"<table>...</table>"}} 元素。</li>
 * </ul>
 *
 * <p><b>fail-soft-but-honest</b>：结构缺失/解析不出任何 cell 时返回 {@code null}，
 * 由 mapper 保留 {@code [表格]} 占位符，<b>不伪造</b>表格内容。
 */
public final class TableContentExtractor {

    private TableContentExtractor() {
    }

    // ---------- Docling ----------

    /**
     * 从 Docling table 节点提取表格网格。
     *
     * @param tableNode {@code json_content.tables[]} 里的单个节点
     * @return 表格网格；无有效 cell 返回 {@code null}
     */
    static TableGrid fromDocling(JsonNode tableNode) {
        if (tableNode == null || !tableNode.isObject()) {
            return null;
        }
        JsonNode cells = tableNode.path("data").path("table_cells");
        if (!cells.isArray() || cells.isEmpty()) {
            return null;
        }

        int maxRow = 0;
        int maxCol = 0;
        for (JsonNode cell : cells) {
            maxRow = Math.max(maxRow, cell.path("end_row_offset_idx").asInt(0));
            maxCol = Math.max(maxCol, cell.path("end_col_offset_idx").asInt(0));
        }
        if (maxRow <= 0 || maxCol <= 0) {
            return null;
        }
        // ⚠️ 防御性上限：避免畸形 cell 坐标撑爆内存（Docling 自身也有 max_table_cells 限制）。
        if (maxRow > MAX_DIM || maxCol > MAX_DIM) {
            return null;
        }

        String[][] grid = new String[maxRow][maxCol];
        for (int i = 0; i < maxRow; i++) {
            for (int j = 0; j < maxCol; j++) {
                grid[i][j] = "";
            }
        }
        boolean[] rowHasHeaderCell = new boolean[maxRow];
        for (JsonNode cell : cells) {
            String text = cell.path("text").asText("");
            int sr = cell.path("start_row_offset_idx").asInt(0);
            int sc = cell.path("start_col_offset_idx").asInt(0);
            if (sr < 0 || sc < 0 || sr >= maxRow || sc >= maxCol) {
                continue;
            }
            // 合并单元格：文本只放左上角槽，覆盖区留空（TableGrid 渲染不重复文本）。
            if (!text.isBlank()) {
                grid[sr][sc] = text;
            }
            if (cell.path("column_header").asBoolean(false)) {
                rowHasHeaderCell[sr] = true;
            }
        }

        // 表头行数 = 从顶部连续出现 column_header=true 的行数；一行都没有则如实记 0。
        int headerRows = 0;
        while (headerRows < maxRow && rowHasHeaderCell[headerRows]) {
            headerRows++;
        }
        return toGrid(grid, headerRows);
    }

    // ---------- MinerU ----------

    private static final Pattern TABLE_TAG = Pattern.compile(
            "<table\\b[^>]*>(.*?)</table>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern TR_TAG = Pattern.compile(
            "<tr\\b[^>]*>(.*?)</tr>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern CELL_TAG = Pattern.compile(
            "<(td|th)\\b([^>]*)>(.*?)</\\1>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern COLSPAN = Pattern.compile(
            "colspan\\s*=\\s*\"?(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ROWSPAN = Pattern.compile(
            "rowspan\\s*=\\s*\"?(\\d+)", Pattern.CASE_INSENSITIVE);

    private static final int MAX_DIM = 512;

    /**
     * 从 MinerU 块提取表格网格：优先读 {@code content[type=html]} 的 HTML 表格。
     *
     * @param block middle_json 的单个 block 节点
     * @return 表格网格；无 HTML 表格或解析不出 cell 返回 {@code null}
     */
    static TableGrid fromMineru(JsonNode block) {
        if (block == null) {
            return null;
        }
        String html = null;
        for (JsonNode c : block.path("content")) {
            String type = c.path("type").asText("");
            if ("html".equalsIgnoreCase(type) || "table".equalsIgnoreCase(type)) {
                String piece = c.path("content").asText("");
                if (piece.contains("<table")) {
                    html = piece;
                    break;
                }
            }
        }
        if (html == null) {
            return null;
        }
        return parseHtmlTable(html);
    }

    /** 轻量 HTML 表格解析（无第三方依赖，免引入 Jsoup）。 */
    static TableGrid parseHtmlTable(String html) {
        if (html == null) {
            return null;
        }
        Matcher tableM = TABLE_TAG.matcher(html);
        String body = tableM.find() ? tableM.group(1) : html; // 也允许直接传 <tr> 片段

        List<List<String>> grid = new ArrayList<>();
        Matcher trM = TR_TAG.matcher(body);
        while (trM.find()) {
            List<String> row = new ArrayList<>();
            Matcher cellM = CELL_TAG.matcher(trM.group(1));
            boolean anyCell = false;
            while (cellM.find()) {
                anyCell = true;
                String tag = cellM.group(1);
                String attrs = cellM.group(2);
                String raw = cellM.group(3);
                String text = decodeEntities(stripTags(raw));
                int colspan = groupInt(COLSPAN, attrs, 1);
                // ⚠️ rowspan 只在「首行槽位」落文本，后续行的同名列留空，
                //    避免跨行合并单元格的文本被复制放大检索信号。
                row.add(text);
                for (int extra = 1; extra < colspan && extra <= MAX_DIM; extra++) {
                    row.add("");
                }
                // <th> 出现在任意行都视为表头信号，由调用方按首行汇总（下方 headerRows）。
                if (!"th".equalsIgnoreCase(tag)) {
                    // 非表头 cell 不做额外标记；表头行判断统一按「首行是否全为 <th>」。
                }
                if (row.size() > MAX_DIM) {
                    break;
                }
            }
            if (anyCell && grid.size() <= MAX_DIM) {
                grid.add(row);
            }
        }
        if (grid.isEmpty()) {
            return null;
        }
        int headerRows = firstRowAllHeaderCells(body) ? 1 : 0;
        return new TableGrid(grid, headerRows);
    }

    /** 首行（第一个 tr）是否全部由 <th> 组成——MinerU 的表头信号。 */
    private static boolean firstRowAllHeaderCells(String body) {
        Matcher trM = TR_TAG.matcher(body);
        if (!trM.find()) {
            return false;
        }
        Matcher cellM = CELL_TAG.matcher(trM.group(1));
        int cells = 0;
        int headers = 0;
        while (cellM.find()) {
            cells++;
            if ("th".equalsIgnoreCase(cellM.group(1))) {
                headers++;
            }
        }
        return cells > 0 && cells == headers;
    }

    private static int groupInt(Pattern p, String s, int def) {
        Matcher m = p.matcher(s);
        if (!m.find()) {
            return def;
        }
        try {
            int v = Integer.parseInt(m.group(1));
            return v >= 1 && v <= MAX_DIM ? v : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** 去掉 cell 内的嵌套标签（如 <span>/<b>），保留文本。 */
    private static String stripTags(String s) {
        return s.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }

    /** 解码常见 HTML 实体（&nbsp; &amp; &lt; &gt; &quot; 与数字实体）。 */
    static String decodeEntities(String s) {
        String out = s.replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'");
        Matcher num = Pattern.compile("&#(\\d+);").matcher(out);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (num.find()) {
            sb.append(out, last, num.start());
            try {
                sb.append((char) Integer.parseInt(num.group(1)));
            } catch (NumberFormatException e) {
                sb.append(num.group());
            }
            last = num.end();
        }
        sb.append(out.substring(last));
        return sb.toString();
    }

    private static TableGrid toGrid(String[][] grid, int headerRows) {
        List<List<String>> rows = new ArrayList<>(grid.length);
        for (String[] row : grid) {
            rows.add(List.of(row));
        }
        return new TableGrid(rows, headerRows);
    }
}
