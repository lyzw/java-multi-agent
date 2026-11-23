package cn.studykid.rag.infrastructure.parsing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.rag.ports.BlockType;
import cn.studykid.rag.ports.LayoutBlock;
import cn.studykid.rag.premise.normalize.SectionPathResolver;
import cn.studykid.rag.premise.normalize.SectionPathStack;

/**
 * 章节路径<b>跨链路同口径</b>守卫（RAG-D28 P1-4 验收项）。
 *
 * <p><b>⚠️ 这条测试防的是「算对了但被下游改写」</b>：P1-4 之前，mapper 用 level 值栈
 * 算出正确路径，但切分器（{@link SectionPathResolver}）用自己的 level-1 算法重新推进，
 * 把结果覆盖掉；两条链还分别输出 {@code " / "} 与 {@code " > "}。现在两者共用
 * {@link cn.studykid.rag.premise.normalize.SectionPathStack}，本测试用同一组标题
 * 分别驱动 Docling mapper 与 resolver，断言逐字符相等——任一侧漂移即转红。
 */
class SectionPathParityTest {

    private final DoclingResponseMapper mapper = new DoclingResponseMapper(null);

    /** 构造只含标题的 Docling 响应；"标题@页码" 语法还原真实页码分布。 */
    private String doc(String... pairs) {
        StringBuilder texts = new StringBuilder();
        StringBuilder children = new StringBuilder();
        for (int i = 0; i < pairs.length; i += 2) {
            if (i > 0) {
                texts.append(",");
                children.append(",");
            }
            String raw = pairs[i];
            int page = 1;
            int at = raw.lastIndexOf('@');
            if (at > 0) {
                page = Integer.parseInt(raw.substring(at + 1));
                raw = raw.substring(0, at);
            }
            texts.append(String.format(
                    "{\"self_ref\":\"#/texts/%d\",\"label\":\"section_header\","
                            + "\"text\":\"%s\",\"level\":%s,\"prov\":[{\"page_no\":%d}]}",
                    i / 2, raw, pairs[i + 1], page));
            children.append(String.format("{\"$ref\":\"#/texts/%d\"}", i / 2));
        }
        return "{\"status\":\"success\",\"document\":{\"json_content\":{"
                + "\"texts\":[" + texts + "],"
                + "\"body\":{\"children\":[" + children + "]}}}}";
    }

    /** 从同一组标题驱动 resolver 侧，返回 { 页码, level, 文本 } 列表。 */
    private static LayoutBlock heading(String text, int page, int level) {
        return new LayoutBlock(BlockType.HEADING, text, page, level, "", null, false,
                0, text.length());
    }

    @Test
    @DisplayName("连续层级：mapper 与 resolver 逐字符同路径（' > ' 分隔）")
    void contiguousLevelsMatch() {
        List<LayoutBlock> mapped = mapper.map(doc(
                "第3章 二次函数", "1", "3.1 概念", "2", "3.1.1 定义", "3"));

        SectionPathStack stack = SectionPathResolver.newStack();
        SectionPathResolver.advance(stack, heading("第3章 二次函数", 1, 1));
        SectionPathResolver.advance(stack, heading("3.1 概念", 1, 2));
        SectionPathResolver.advance(stack, heading("3.1.1 定义", 1, 3));

        assertThat(mapped.get(2).sectionPath()).isEqualTo(stack.render());
        assertThat(stack.render()).isEqualTo("第3章 二次函数 > 3.1 概念 > 3.1.1 定义");
    }

    @Test
    @DisplayName("非连续 level（1,3,5）：两侧都不按 level-1 下标错位")
    void nonContiguousLevelsMatch() {
        List<LayoutBlock> mapped = mapper.map(doc(
                "Starter 1@11", "3", "To-do list@17", "5"));

        SectionPathStack stack = SectionPathResolver.newStack();
        SectionPathResolver.advance(stack, heading("Starter 1", 11, 3));
        SectionPathResolver.advance(stack, heading("To-do list", 17, 5));

        // ⚠️ mapper 跨页 6 页且 level 更深（5 > 3）→ 不触发新分区，沿用父路径。
        assertThat(mapped.get(1).sectionPath()).isEqualTo(stack.render());
        assertThat(stack.render()).isEqualTo("Starter 1 > To-do list");
    }

    @Test
    @DisplayName("跨页新分区：两侧都在 level 变浅时清空路径")
    void newPartitionMatches() {
        List<LayoutBlock> mapped = mapper.map(doc(
                "Starter 1@11", "3", "Part I@19", "1"));

        SectionPathStack stack = SectionPathResolver.newStack();
        SectionPathResolver.advance(stack, heading("Starter 1", 11, 3));
        SectionPathResolver.advance(stack, heading("Part I", 19, 1));

        assertThat(mapped.get(1).sectionPath()).isEqualTo(stack.render());
        assertThat(stack.render()).isEqualTo("Part I");
    }

    @Test
    @DisplayName("页眉后缀：两侧都剥除出版社名（不再让 1438 片段带噪声）")
    void headerSuffixStrippedOnBothSides() {
        List<LayoutBlock> mapped = mapper.map(doc(
                "Festivals across cultures 上海教育出版社@38", "1"));

        SectionPathStack stack = SectionPathResolver.newStack();
        SectionPathResolver.advance(stack,
                heading("Festivals across cultures 上海教育出版社", 38, 1));

        assertThat(mapped.get(0).sectionPath()).isEqualTo(stack.render());
        assertThat(stack.render()).isEqualTo("Festivals across cultures");
    }
}
