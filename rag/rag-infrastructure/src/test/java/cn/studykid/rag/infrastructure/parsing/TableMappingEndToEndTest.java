package cn.studykid.rag.infrastructure.parsing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.rag.ports.BlockType;
import cn.studykid.rag.ports.LayoutBlock;

/**
 * P1-5 表格映射端到端：验证两个 mapper 把后端表格结构真实还原成 Markdown 文本，
 * 不再退化成 {@code [表格]} 占位符。
 *
 * <p>⚠️ 夹具按 Docling/MinerU 官方 schema 手写的合成样例（非真实响应），
 * 锁定映射逻辑；真实教材 TEDS 复测属 D08 G1 阶段。
 */
class TableMappingEndToEndTest {

    private final DoclingResponseMapper docling = new DoclingResponseMapper(null);
    private final MineruResponseMapper mineru = new MineruResponseMapper(null);

    @Test
    @DisplayName("Docling：table_cells[] 还原为 Markdown，不再是 [表格] 占位符")
    void doclingTableBecomesMarkdown() {
        String response = """
                {
                  "status":"success",
                  "document":{"json_content":{
                    "texts":[],
                    "tables":[{
                      "self_ref":"#/tables/0","label":"table",
                      "prov":[{"page_no":1,"bbox":{"l":10,"t":20,"r":100,"b":80}}],
                      "data":{"num_rows":2,"num_cols":2,"table_cells":[
                        {"text":"科目","column_header":true,"start_row_offset_idx":0,"end_row_offset_idx":1,"start_col_offset_idx":0,"end_col_offset_idx":1},
                        {"text":"分数","column_header":true,"start_row_offset_idx":0,"end_row_offset_idx":1,"start_col_offset_idx":1,"end_col_offset_idx":2},
                        {"text":"数学","start_row_offset_idx":1,"end_row_offset_idx":2,"start_col_offset_idx":0,"end_col_offset_idx":1},
                        {"text":"95","start_row_offset_idx":1,"end_row_offset_idx":2,"start_col_offset_idx":1,"end_col_offset_idx":2}
                      ]}
                    }],
                    "pictures":[],
                    "body":{"children":[{"$ref":"#/tables/0"}]}
                  }}
                }
                """;
        List<LayoutBlock> blocks = docling.map(response);

        assertThat(blocks).hasSize(1);
        LayoutBlock table = blocks.get(0);
        assertThat(table.type()).isEqualTo(BlockType.TABLE);
        assertThat(table.text())
                .contains("科目").contains("分数").contains("数学").contains("95")
                .contains("| --- | --- |")
                .doesNotContain("[表格]");
        // bbox 仍来自 prov（P1-3 契约不回退）
        assertThat(new DoclingResponseMapper(null).mapToPremiseBlocks(response).get(0).hasBbox())
                .isTrue();
    }

    @Test
    @DisplayName("MinerU：content[type=html] 的 <table> 还原为 Markdown，不再是 [表格]")
    void mineruTableBecomesMarkdown() {
        String middleJson = """
                {
                  "schema":"docvortex.middle",
                  "pages":[{"page_idx":0,"blocks":[
                    {"type":"table","bbox":[0.1,0.2,0.9,0.8],"content":[
                      {"type":"html","content":"<table><tr><th>名称</th><th>值</th></tr><tr><td>圆周率</td><td>3.14</td></tr></table>"}
                    ]}
                  ]}]
                }
                """;
        List<LayoutBlock> blocks = mineru.map("doc-1", middleJson);

        assertThat(blocks).hasSize(1);
        LayoutBlock table = blocks.get(0);
        assertThat(table.type()).isEqualTo(BlockType.TABLE);
        assertThat(table.text())
                .contains("名称").contains("值").contains("圆周率").contains("3.14")
                .contains("| --- | --- |")
                .doesNotContain("[表格]");
        assertThat(table.pageNumber()).isEqualTo(1); // page_idx=0 → 1 基（P1-3 契约不回退）
    }

    @Test
    @DisplayName("表格无结构数据 → 保留 [表格] 占位符（fail-soft 不伪造）")
    void tableWithoutStructureKeepsPlaceholder() {
        String response = """
                {"status":"success","document":{"json_content":{
                  "texts":[],"tables":[{"self_ref":"#/tables/0","label":"table","data":{}}],"pictures":[],
                  "body":{"children":[{"$ref":"#/tables/0"}]}
                }}}
                """;
        List<LayoutBlock> blocks = docling.map(response);
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).text()).isEqualTo("[表格]");
    }
}
