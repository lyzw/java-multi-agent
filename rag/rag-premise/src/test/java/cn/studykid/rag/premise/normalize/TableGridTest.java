package cn.studykid.rag.premise.normalize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TableGridTest {

    @Test
    @DisplayName("表头行后插入 Markdown 分隔行，cell 正确渲染")
    void rendersMarkdownWithHeaderSeparator() {
        TableGrid grid = new TableGrid(List.of(
                List.of("名称", "数值"),
                List.of("苹果", "3"),
                List.of("香蕉", "5")), 1);

        assertThat(grid.toMarkdown()).isEqualTo(
                "| 名称 | 数值 |\n"
                + "| --- | --- |\n"
                + "| 苹果 | 3 |\n"
                + "| 香蕉 | 5 |");
    }

    @Test
    @DisplayName("未标注表头（headerRowCount=0）仍按语法用第一行当表头，但元数据如实记 0")
    void noHeaderMetadataButSyntaxStillNeedsFirstRow() {
        TableGrid grid = new TableGrid(List.of(
                List.of("A", "B"),
                List.of("C", "D")), 0);

        assertThat(grid.headerRowCount()).isZero();
        assertThat(grid.toMarkdown()).startsWith("| A | B |\n| --- | --- |");
    }

    @Test
    @DisplayName("cell 内的管道符/换行被转义，不破坏表格结构")
    void escapesPipesAndNewlines() {
        TableGrid grid = new TableGrid(List.of(
                List.of("列"),
                List.of("a|b\nc")), 1);

        String md = grid.toMarkdown();
        assertThat(md).contains("a\\|b c").doesNotContain("a|b\nc");
        // 数据行只有 1 列 → 分隔行后总共 3 行
        assertThat(md.lines().count()).isEqualTo(3);
    }

    @Test
    @DisplayName("列数不齐时按最大列补齐，短行不越界")
    void raggedRowsPadded() {
        TableGrid grid = new TableGrid(List.of(
                List.of("a", "b", "c"),
                List.of("1")), 1);

        assertThat(grid.columnCount()).isEqualTo(3);
        assertThat(grid.toMarkdown().lines().toList().get(2)).isEqualTo("| 1 |  |  |");
    }

    @Test
    @DisplayName("构造参数校验：空行/表头越界直接拒绝")
    void validatesInputs() {
        assertThatThrownBy(() -> new TableGrid(List.of(List.of()), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TableGrid(List.of(List.of("x")), 2))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
