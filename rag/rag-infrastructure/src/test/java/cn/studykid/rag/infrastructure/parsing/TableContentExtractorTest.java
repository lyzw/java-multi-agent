package cn.studykid.rag.infrastructure.parsing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import cn.studykid.rag.premise.normalize.TableGrid;

/**
 * 表格内容提取单测。
 *
 * <p>⚠️ 夹具是<b>按 Docling/MinerU 官方 schema 手写</b>的合成样例（非真实响应），
 * 用于锁定映射逻辑；真实教材表格语料须在 D08 G1 阶段用真样张复测 TEDS/CDM。
 */
@ExtendWith(MockitoExtension.class)
class TableContentExtractorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("Docling table_cells[] → 网格：列头标记决定 headerRowCount，合并单元格只落左上角")
    void doclingCellsToGrid() throws Exception {
        String table = """
                {
                  "self_ref": "#/tables/0",
                  "data": {
                    "num_rows": 3, "num_cols": 2,
                    "table_cells": [
                      {"text":"名称","column_header":true,"start_row_offset_idx":0,"end_row_offset_idx":1,"start_col_offset_idx":0,"end_col_offset_idx":1},
                      {"text":"数值","column_header":true,"start_row_offset_idx":0,"end_row_offset_idx":1,"start_col_offset_idx":1,"end_col_offset_idx":2},
                      {"text":"苹果","start_row_offset_idx":1,"end_row_offset_idx":2,"start_col_offset_idx":0,"end_col_offset_idx":1},
                      {"text":"3","start_row_offset_idx":1,"end_row_offset_idx":2,"start_col_offset_idx":1,"end_col_offset_idx":2},
                      {"text":"跨两列","start_row_offset_idx":2,"end_row_offset_idx":3,"start_col_offset_idx":0,"end_col_offset_idx":2}
                    ]
                  }
                }
                """;
        JsonNode node = mapper.readTree(table);
        TableGrid grid = TableContentExtractor.fromDocling(node);

        assertThat(grid).isNotNull();
        assertThat(grid.rowCount()).isEqualTo(3);
        assertThat(grid.columnCount()).isEqualTo(2);
        assertThat(grid.headerRowCount()).isEqualTo(1);
        assertThat(grid.rows().get(0)).containsExactly("名称", "数值");
        assertThat(grid.rows().get(1)).containsExactly("苹果", "3");
        // 合并单元格 text 只落左上角，覆盖区留空（不重复放大检索信号）
        assertThat(grid.rows().get(2)).containsExactly("跨两列", "");
    }

    @Test
    @DisplayName("Docling 无 table_cells → 返回 null（mapper 保留占位符）")
    void doclingMissingCellsReturnsNull() throws Exception {
        JsonNode node = mapper.readTree("{\"self_ref\":\"#/tables/1\",\"data\":{}}");
        assertThat(TableContentExtractor.fromDocling(node)).isNull();
        assertThat(TableContentExtractor.fromDocling(null)).isNull();
    }

    @Test
    @DisplayName("Docling 畸形超大坐标 → 返回 null，防御性不撑爆内存")
    void doclingOversizeReturnsNull() throws Exception {
        String table = """
                {"data":{"table_cells":[
                  {"text":"x","start_row_offset_idx":0,"end_row_offset_idx":99999,"start_col_offset_idx":0,"end_col_offset_idx":1}
                ]}}
                """;
        assertThat(TableContentExtractor.fromDocling(mapper.readTree(table))).isNull();
    }

    @Test
    @DisplayName("MinerU content[type=html] 的 <table> → 网格，<th> 表头被识别")
    void mineruHtmlToGrid() throws Exception {
        String block = """
                {
                  "type": "table",
                  "content": [
                    {"type":"html","content":"<table><tr><th>科目</th><th>分数</th></tr><tr><td>语文</td><td>90</td></tr><tr><td colspan=\\"2\\">合计 180</td></tr></table>"}
                  ]
                }
                """;
        JsonNode node = mapper.readTree(block);
        TableGrid grid = TableContentExtractor.fromMineru(node);

        assertThat(grid).isNotNull();
        assertThat(grid.rowCount()).isEqualTo(3);
        assertThat(grid.headerRowCount()).isEqualTo(1);
        assertThat(grid.rows().get(0)).containsExactly("科目", "分数");
        assertThat(grid.rows().get(1)).containsExactly("语文", "90");
        // colspan=2 展开为两列，文本落首列、次列留空
        assertThat(grid.rows().get(2)).containsExactly("合计 180", "");
    }

    @Test
    @DisplayName("MinerU 无 html content → 返回 null")
    void mineruNoHtmlReturnsNull() throws Exception {
        JsonNode node = mapper.readTree("{\"type\":\"table\",\"content\":[{\"type\":\"text\",\"content\":\"x\"}]}");
        assertThat(TableContentExtractor.fromMineru(node)).isNull();
    }

    @Test
    @DisplayName("HTML 实体解码 + 嵌套标签剥离")
    void decodesEntitiesAndStripsTags() {
        assertThat(TableContentExtractor.decodeEntities("a&nbsp;&amp;b&lt;c&gt;"))
                .isEqualTo("a &b<c>");
        assertThat(TableContentExtractor.parseHtmlTable(
                "<table><tr><td><b>粗体</b> 文本</td></tr></table>").rows().get(0))
                .containsExactly("粗体 文本");
    }
}
