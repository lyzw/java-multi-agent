package cn.studykid.rag.premise.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.rag.core.ingest.rules.TocRules;
import cn.studykid.rag.ports.BlockType;
import cn.studykid.rag.ports.LayoutBlock;
import cn.studykid.rag.premise.model.PremiseBlock;

/**
 * 附录逻辑单位接线契约（RAG-D28 P2-4）。
 *
 * <p>让 rag-core 的 {@code AppendixRules}（A-1/A-2/A-3）在 premise 链<b>真实可达</b>。
 */
class AppendixLogicalUnitResolverTest {

    private static final List<TocRules.TocUnit> UNITS = List.of(
            new TocRules.TocUnit(4, "Food", 38),
            new TocRules.TocUnit(5, "Green neighbourhood", 58));

    private static PremiseBlock block(int page, String text, String sectionPath, int start) {
        return PremiseBlock.fromLayoutBlock(new LayoutBlock(
                BlockType.PARAGRAPH, text, page, 0, sectionPath, null, false,
                start, start + text.length()));
    }

    @Test
    @DisplayName("非附录页原样透传（appendixPieces 对任何页都返回一片，不能用返回非空判附录）")
    void nonAppendixPagePassesThrough() {
        AppendixLogicalUnitResolver resolver = new AppendixLogicalUnitResolver(UNITS);
        PremiseBlock body = block(12, "二次函数顶点式定义。", "第3章", 0);

        List<PremiseBlock> out = resolver.resolve(List.of(body));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).text()).isEqualTo("二次函数顶点式定义。");
        assertThat(out.get(0).sectionPath()).isEqualTo("第3章");
        assertThat(resolver.inAppendixSection()).isFalse();
    }

    @Test
    @DisplayName("A-1 附录页同页多 Unit → 按 Unit 切成多片，中英双形标题可见")
    void appendixPageSplitsByUnit() {
        AppendixLogicalUnitResolver resolver = new AppendixLogicalUnitResolver(UNITS);
        String page = "Words and expressions in each unit\n"
                + "Unit 4 food, 食物, 很多很多很多内容用于越过四十字符阈值。\n"
                + "Unit 5 neighbourhood, 街区, 同样需要足够长的正文内容来通过过滤。";

        List<PremiseBlock> out = resolver.resolve(List.of(block(134, page, "附录", 0)));

        List<PremiseBlock> bodies = out.stream()
                .filter(b -> b.type() == BlockType.PARAGRAPH).toList();
        assertThat(bodies).hasSize(2);
        assertThat(bodies.get(0).text()).startsWith("Unit 4（第四单元）单词表：");
        assertThat(bodies.get(1).text()).startsWith("Unit 5（第五单元）单词表：");
        assertThat(bodies.get(1).sectionPath()).contains("Unit 5").contains("词表");
    }

    @Test
    @DisplayName("A-2 续页继承上一页 Unit（续页无 Unit 标记也归 Unit 5）")
    void continuationInheritsUnit() {
        AppendixLogicalUnitResolver resolver = new AppendixLogicalUnitResolver(UNITS);
        String head = "Words and expressions in each unit\n"
                + "Unit 5 neighbourhood, 街区, 很多很多内容用于越过四十字符阈值。";
        resolver.resolve(List.of(block(134, head, "附录", 0)));

        String continuation = "neighbour, 邻居, 续页内容同样需要足够长度才能通过过滤阈值。";
        List<PremiseBlock> out = resolver.resolve(List.of(block(135, continuation, "附录", 0)));

        List<PremiseBlock> bodies = out.stream()
                .filter(b -> b.type() == BlockType.PARAGRAPH).toList();
        assertThat(bodies).hasSize(1);
        assertThat(bodies.get(0).text()).startsWith("Unit 5（第五单元）单词表：")
                .as("续页必须归属上一页的 Unit 5，不能退化成「附录 · 词表」");
    }

    @Test
    @DisplayName("合成块 char 起点页内唯一（否则派生 chunk_id 碰撞互相覆盖）")
    void syntheticCharStartsAreUnique() {
        AppendixLogicalUnitResolver resolver = new AppendixLogicalUnitResolver(UNITS);
        String page = "Words and expressions in each unit\n"
                + "Unit 4 food, 食物, 很多很多很多内容用于越过四十字符阈值。\n"
                + "Unit 5 neighbourhood, 街区, 同样需要足够长的正文内容来通过过滤。";

        List<PremiseBlock> out = resolver.resolve(List.of(block(134, page, "附录", 0)));
        List<Integer> starts = out.stream()
                .filter(b -> b.type() == BlockType.PARAGRAPH)
                .map(PremiseBlock::charStart).toList();
        assertThat(starts).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("reset 清空跨页状态（换册不串状态）")
    void resetClearsState() {
        AppendixLogicalUnitResolver resolver = new AppendixLogicalUnitResolver(UNITS);
        resolver.resolve(List.of(block(134,
                "Words and expressions in each unit\n"
                        + "Unit 5 neighbourhood, 街区, 很多很多内容用于越过四十字符阈值。",
                "附录", 0)));
        assertThat(resolver.inAppendixSection()).isTrue();

        resolver.reset();
        assertThat(resolver.inAppendixSection()).isFalse();
        List<PremiseBlock> out = resolver.resolve(List.of(block(12, "正文。", "第3章", 0)));
        assertThat(out).hasSize(1).first()
                .extracting(PremiseBlock::text).isEqualTo("正文。");
    }
}
