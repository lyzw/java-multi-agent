package cn.studykid.rag.premise.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.rag.ports.BlockType;
import cn.studykid.rag.ports.LayoutBlock;

/**
 * {@link SectionPathStack}——P1-4 收敛后的章节路径<b>唯一状态机</b>单测。
 *
 * <p><b>⚠️ 这些用例同时是「mapper 与 resolver 同口径」的可执行契约</b>：
 * Docling/MinerU mapper 与 {@link SectionPathResolver} 都委托本类，
 * 因此这里的每条断言对三条链路同时生效。改本类算法必须先跑
 * {@code DoclingSectionHierarchyTest} 与 {@code SectionPathParityTest}。
 */
class SectionPathStackTest {

    @Test
    @DisplayName("分隔符是 D27 规范值 ' > '（P1-4 撤点：改成 ' / ' 本例转红）")
    void separatorIsSpecValue() {
        SectionPathStack stack = new SectionPathStack();
        stack.advance(1, 1, "第3章");
        stack.advance(1, 2, "3.1");

        assertThat(stack.render()).isEqualTo("第3章 > 3.1");
        assertThat(SectionPathStack.SEPARATOR).isEqualTo(" > ");
    }

    @Test
    @DisplayName("同级标题替换而非追加（Starter 1/2/3 都是 lvl=3）")
    void siblingsReplace() {
        SectionPathStack stack = new SectionPathStack();
        stack.advance(3, 3, "Starter 1");
        stack.advance(3, 3, "Starter 2");
        stack.advance(3, 3, "Starter 3");

        assertThat(stack.render()).isEqualTo("Starter 3");
    }

    @Test
    @DisplayName("非连续 level（1,3,5）按值定位槽位，不用 level-1 下标")
    void nonContiguousLevels() {
        SectionPathStack stack = new SectionPathStack();
        stack.advance(11, 3, "Starter 1");
        stack.advance(17, 5, "To-do list");

        assertThat(stack.render()).isEqualTo("Starter 1 > To-do list");
    }

    @Test
    @DisplayName("level 变小＝层级变浅，弹出更深槽（Part I 不挂在 Starter 4 下）")
    void shallowerLevelPopsDeeper() {
        SectionPathStack stack = new SectionPathStack();
        stack.advance(18, 3, "Starter 4");
        stack.advance(19, 1, "Part I");

        assertThat(stack.render()).isEqualTo("Part I");
    }

    @Test
    @DisplayName("深层嵌套截断：level 回退时回到正确父级，不累积")
    void truncatesOnLevelDecrease() {
        SectionPathStack stack = new SectionPathStack();
        stack.advance(40, 1, "第3章");
        stack.advance(41, 2, "3.1");
        stack.advance(42, 3, "3.1.1");
        stack.advance(43, 2, "3.2");

        assertThat(stack.render()).isEqualTo("第3章 > 3.2");
    }

    @Test
    @DisplayName("跨页 >1 页且层级未变深 → 新顶层分区，清空路径（防 236 字符超长路径）")
    void pageGapStartsNewPartition() {
        SectionPathStack stack = new SectionPathStack();
        // 实测上册：p11 'Starter 1'(lvl=3) → p19 'Part I'(lvl=1)，
        // 二者是平级独立编排；level 更浅 + 跨页 >1 → 新分区清空。
        stack.advance(11, 3, "Starter 1");
        stack.advance(19, 1, "Part I");

        assertThat(stack.render()).isEqualTo("Part I");
    }

    @Test
    @DisplayName("同页紧邻标题是子标题，沿用父路径不清空")
    void adjacentPageKeepsPartition() {
        SectionPathStack stack = new SectionPathStack();
        stack.advance(11, 1, "Part I");
        stack.advance(12, 2, "Unit 1");

        assertThat(stack.render()).isEqualTo("Part I > Unit 1");
    }

    @Test
    @DisplayName("深度封顶 3 层（超过替换末槽，不无限追加）")
    void depthIsBounded() {
        SectionPathStack stack = new SectionPathStack();
        stack.advance(1, 1, "L1");
        stack.advance(1, 2, "L2");
        stack.advance(1, 3, "L3");
        stack.advance(1, 4, "L4");

        assertThat(stack.render().split(" > ")).hasSize(3);
        assertThat(stack.render()).isEqualTo("L1 > L2 > L4");
    }

    @Test
    @DisplayName("页眉后缀在入栈时剥除（SectionPathResolver 与 mapper 同口径）")
    void stripsHeaderSuffix() {
        SectionPathStack stack = new SectionPathStack();
        stack.advance(38, 1, "Festivals across cultures 上海教育出版社");

        assertThat(stack.render()).isEqualTo("Festivals across cultures");
        assertThat(SectionPathStack.stripHeaderSuffix("标题 上海教育出版社"))
                .isEqualTo("标题");
    }

    @Test
    @DisplayName("SectionPathResolver 委托本类：标题块推进后 resolve 取累积路径")
    void resolverDelegatesToStack() {
        SectionPathStack stack = SectionPathResolver.newStack();
        SectionPathResolver.advance(stack,
                new LayoutBlock(BlockType.HEADING, "第3章", 40, 1, "", null, false, 0, 3));
        LayoutBlock body = new LayoutBlock(BlockType.PARAGRAPH, "顶点式。", 41, 0,
                "解析器路径", null, false, 0, 4);

        assertThat(SectionPathResolver.resolve(stack, body)).isEqualTo("第3章");
        assertThat(SectionPathResolver.resolve(new SectionPathStack(), body))
                .isEqualTo("解析器路径");
    }
}
