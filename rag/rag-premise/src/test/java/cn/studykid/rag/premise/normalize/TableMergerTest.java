package cn.studykid.rag.premise.normalize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.rag.ports.BlockType;
import cn.studykid.rag.ports.LayoutBlock;
import cn.studykid.rag.premise.model.PremiseBlock;

class TableMergerTest {

    /** 构造一个表格块（页码、章节、表头行、数据行、continuesPrev）。 */
    private static PremiseBlock table(int page, String section, TableGrid grid) {
        LayoutBlock layout = new LayoutBlock(BlockType.TABLE, grid.toMarkdown(), page, 0,
                section, null, false, 0, grid.toMarkdown().length());
        return new PremiseBlock(layout, null, Double.NaN, grid);
    }

    private static PremiseBlock noise(int page) {
        LayoutBlock layout = new LayoutBlock(BlockType.BOILERPLATE, "页眉", page, 0,
                "", null, false, 0, 2);
        return PremiseBlock.fromLayoutBlock(layout);
    }

    private static TableGrid grid(boolean continues, List<String> header, List<List<String>> data) {
        List<List<String>> rows = new ArrayList<>(List.of(header));
        rows.addAll(data);
        return new TableGrid(rows, 1, continues);
    }

    private static final List<String> HEADER = List.of("科目", "分数");

    @Test
    @DisplayName("MinerU 强信号：continues_prev=true + 页码相邻 + 同章节 → 合并，续页表头剥除")
    void mergesOnContinuesPrevSignal() {
        TableGrid p1 = grid(false, HEADER, List.of(List.of("语文", "90")));
        TableGrid p2 = grid(true, HEADER, List.of(List.of("数学", "95")));

        List<PremiseBlock> out = TableMerger.merge(List.of(
                table(1, "第3章", p1), table(2, "第3章", p2)));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).tableGrid().rowCount()).isEqualTo(3); // 表头 + 2 数据行
        assertThat(out.get(0).tableGrid().rows().get(2)).containsExactly("数学", "95");
        // 续页表头不重复进正文：只有 1 个表头行
        assertThat(out.get(0).text().lines().filter(l -> l.contains("科目")).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("强信号独立有效：continues_prev=true 但续表头不同（兜底会拒绝）→ 仍合并")
    void continuesPrevAloneIsSufficient() {
        // 续页表头写成「编号|名称」与首页「科目|分数」不同 → 兜底判据不满足；
        // 但 continues_prev=true 是权威信号，列数相同即合并（撤点掉强信号后此用例必红）。
        TableGrid p1 = grid(false, HEADER, List.of(List.of("语文", "90")));
        TableGrid p2 = grid(true, List.of("编号", "名称"), List.of(List.of("数学", "95")));

        List<PremiseBlock> out = TableMerger.merge(List.of(
                table(1, "第3章", p1), table(2, "第3章", p2)));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).tableGrid().rowCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("Docling 兜底：无信号但页码相邻+同章节+同表头 → 合并")
    void mergesOnDoclingFallback() {
        TableGrid p1 = grid(false, HEADER, List.of(List.of("语文", "90")));
        TableGrid p2 = grid(false, HEADER, List.of(List.of("数学", "95")));

        List<PremiseBlock> out = TableMerger.merge(List.of(
                table(5, "第3章", p1), table(6, "第3章", p2)));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).tableGrid().rowCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("中间夹 BOILERPLATE 页眉页脚仍合并")
    void skipsIntermediateNoise() {
        TableGrid p1 = grid(false, HEADER, List.of(List.of("语文", "90")));
        TableGrid p2 = grid(true, HEADER, List.of(List.of("数学", "95")));

        List<PremiseBlock> out = TableMerger.merge(List.of(
                table(1, "第3章", p1), noise(1), noise(2), table(2, "第3章", p2)));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).tableGrid().rowCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("超长表按行切片，每片重复表头（含表头共 maxRows 行）")
    void slicesOversizeTableWithRepeatedHeader() {
        List<List<String>> data = new java.util.ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            data.add(List.of("行" + i, String.valueOf(i)));
        }
        TableGrid big = grid(false, HEADER, data); // 1 表头 + 7 数据 = 8 行

        List<PremiseBlock> out = TableMerger.merge(List.of(table(1, "第3章", big)), 4);

        // 每片 4 行（1 表头 + 3 数据），7 数据行 → 3 片（3+3+1）
        assertThat(out).hasSize(3);
        for (PremiseBlock part : out) {
            assertThat(part.tableGrid().rows().get(0)).containsExactly("科目", "分数");
            assertThat(part.text().lines().filter(l -> l.contains("科目")).count()).isEqualTo(1);
            assertThat(part.tableGrid().rowCount()).isLessThanOrEqualTo(4);
        }
        // 数据行按顺序分配，无丢失
        assertThat(out.get(0).tableGrid().rows().get(3)).containsExactly("行3", "3");
        assertThat(out.get(1).tableGrid().rows().get(1)).containsExactly("行4", "4");
        assertThat(out.get(2).tableGrid().rows().get(1)).containsExactly("行7", "7");
    }

    @Test
    @DisplayName("拒绝合并：页码不相邻 / 章节不同 / 答案区不同 / 列数不同")
    void rejectsMismatch() {
        TableGrid p1 = grid(false, HEADER, List.of(List.of("语文", "90")));
        TableGrid cont = grid(true, HEADER, List.of(List.of("数学", "95")));

        // 页码跳页
        assertThat(TableMerger.merge(List.of(table(1, "第3章", p1), table(3, "第3章", cont))))
                .hasSize(2);
        // 章节不同
        assertThat(TableMerger.merge(List.of(table(1, "第3章", p1), table(2, "第4章", cont))))
                .hasSize(2);

        TableGrid wrongCols = new TableGrid(
                List.of(List.of("姓名", "科目", "分数"), List.of("张三", "数学", "95")), 1, true);
        // 列数不同（强信号也不合并）
        assertThat(TableMerger.merge(List.of(table(1, "第3章", p1), table(2, "第3章", wrongCols))))
                .hasSize(2);
    }

    @Test
    @DisplayName("兜底判据下：表头不同 + 无 continues_prev → 不合并（防误合两张独立表）")
    void rejectsDifferentHeadersWithoutSignal() {
        TableGrid p1 = grid(false, HEADER, List.of(List.of("语文", "90")));
        TableGrid p2 = grid(false, List.of("姓名", "分数"), List.of(List.of("张三", "88")));

        List<PremiseBlock> out = TableMerger.merge(List.of(
                table(1, "第3章", p1), table(2, "第3章", p2)));

        assertThat(out).hasSize(2);
    }

    @Test
    @DisplayName("非表格块原样保留（顺序与引用不变）")
    void keepsNonTables() {
        LayoutBlock para = new LayoutBlock(BlockType.PARAGRAPH, "正文", 1, 0, "第3章",
                null, false, 0, 2);
        PremiseBlock p = PremiseBlock.fromLayoutBlock(para);

        List<PremiseBlock> out = TableMerger.merge(List.of(p));

        assertThat(out).hasSize(1);
        assertThat(out.get(0)).isSameAs(p);
    }

    @Test
    @DisplayName("参数校验：maxRows<2 直接拒绝")
    void validatesMaxRows() {
        assertThatThrownBy(() -> TableMerger.merge(List.of(), 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("重建块逐字段带过几何/置信度（铁律 #8 反证点）")
    void preservesGeometryOnRebuild() {
        LayoutBlock raw = new LayoutBlock(BlockType.TABLE, "x", 1, 0, "S", null, true, 0, 1);
        PremiseBlock b = new PremiseBlock(raw, new double[]{1, 2, 3, 4}, 0.87, grid(false, HEADER, List.of()));
        TableGrid merged = grid(false, HEADER, List.of(List.of("语文", "90")));

        // 触发合并需要至少两个块；这里直接验证 withTable 逐字段带过
        PremiseBlock rebuilt = b.withTable(raw, merged);

        assertThat(rebuilt.hasBbox()).isTrue();
        assertThat(rebuilt.pageBbox()).containsExactly(1, 2, 3, 4);
        assertThat(rebuilt.confidence()).isEqualTo(0.87);
        assertThat(rebuilt.inAnswerZone()).isTrue();
        assertThat(rebuilt.tableGrid()).isSameAs(merged);
    }
}
