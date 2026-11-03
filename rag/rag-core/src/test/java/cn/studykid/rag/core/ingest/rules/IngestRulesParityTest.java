package cn.studykid.rag.core.ingest.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import cn.studykid.rag.core.chunk.ChunkRole;

/**
 * 规则移植的对拍测试（S2-2 验收判据的可执行形式）。
 *
 * <p>方案 S2 的硬判据是「离线脚本与生产管道对同一册切分结果 diff 归零」。
 * 整册 diff 需要真实教材 PDF + Python 环境，此处以<b>规则级用例</b>覆盖：
 * 每个用例的期望值都取自 {@code scripts/rag_import_pdf.py} 的实际行为
 * （逐条对照脚本源码写出），因此这组测试失败 = 移植与脚本产生了漂移。
 */
class IngestRulesParityTest {

    @Nested
    @DisplayName("Z6 清洗：normalize（对照脚本 normalize()）")
    class Normalize {

        @Test
        @DisplayName("压掉全角空格、em space、软连字符与行内多空格")
        void collapsesNoiseWhitespace() {
            String raw = "第　一章\u2003引言\u00AD正文   有多空格";
            assertEquals("第 一章 引言正文 有多空格", CleaningRules.normalize(raw));
        }

        @Test
        @DisplayName("空行被丢弃（脚本同行为：只保留非空行）")
        void dropsBlankLines() {
            assertEquals("A\nB", CleaningRules.normalize("A\n\n  \nB"));
        }

        @Test
        @DisplayName("空输入返回空串")
        void emptyInEmptyOut() {
            assertEquals("", CleaningRules.normalize(""));
            assertEquals("", CleaningRules.normalize(null));
        }
    }

    @Nested
    @DisplayName("Z6 清洗：stripBoilerplate（对照脚本 strip_boilerplate()）")
    class StripBoilerplate {

        @Test
        @DisplayName("跨页重复的页眉被剥离，正文保留")
        void stripsRepeatedHeader() {
            List<String> pages = List.of(
                    "人民教育出版社\n第一章 引言\n正文一",
                    "人民教育出版社\n第二章 方法\n正文二",
                    "人民教育出版社\n第三章 结果\n正文三");
            List<String> out = CleaningRules.stripBoilerplate(pages);

            assertEquals(3, out.size(), "页数必须不变（按页对齐是切分的前置）");
            assertFalse(out.get(0).contains("人民教育出版社"));
            assertTrue(out.get(0).contains("第一章 引言"));
            assertTrue(out.get(0).contains("正文一"));
        }

        @Test
        @DisplayName("纯页码行被剥离（页码每页不同，必须靠「整行仅数字」规则删，而非重复统计）")
        void stripsPageNumbers() {
            List<String> pages = List.of(
                    "26\n正文一", "27\n正文二", "28\n正文三", "29\n正文四", "30\n正文五");
            List<String> out = CleaningRules.stripBoilerplate(pages);
            assertFalse(out.get(0).contains("26"), "页码应在清洗阶段被移除");
            assertTrue(out.get(0).contains("正文一"), "正文必须保留");
        }

        @Test
        @DisplayName("只出现 2 次的短行不算页眉（阈值 max(3, pages/3)）")
        void rareLinesSurvive() {
            List<String> pages = List.of("偶然短句\n正文一", "偶然短句\n正文二", "正文三");
            List<String> out = CleaningRules.stripBoilerplate(pages);
            assertTrue(out.get(0).contains("偶然短句"), "未达阈值的重复行是正文，不得误删");
        }

        @Test
        @DisplayName("空输入返回空列表")
        void emptyInEmptyOut() {
            assertEquals(List.of(), CleaningRules.stripBoilerplate(List.of()));
        }
    }

    @Nested
    @DisplayName("Z6 清洗：封底/版权页判噪")
    class BackCover {

        @Test
        @DisplayName("含定价/出版信息判为封底")
        void detectsBackCover() {
            assertTrue(CleaningRules.isBackCoverOrCopyright("定价：36.80 元"));
            assertTrue(CleaningRules.isBackCoverOrCopyright("本套教材根据课程标准编写"));
            assertFalse(CleaningRules.isBackCoverOrCopyright("第一章 引言"));
        }
    }

    @Nested
    @DisplayName("Z7 目录探测（对照脚本 toc_units() / page_offset() / section_of()）")
    class Toc {

        @Test
        @DisplayName("从折行且带噪声的目录解析单元列表")
        void parsesWrappedToc() {
            // 模拟抽取噪声：(p. 26) 被拆成两段、单元名折行
            List<String> toc = List.of(
                    "Contents\n1 Hello (p. 2)\n2 School life (p. 14)\n"
                            + "3 Green\nneighbourhood\n(p. 26)\n4 Food (p. 38)");
            List<TocRules.TocUnit> units = TocRules.tocUnits(toc, 3);

            assertEquals(4, units.size());
            assertEquals(1, units.get(0).unitNo());
            assertEquals(2, units.get(0).startPage());
            assertEquals("Green neighbourhood", units.get(2).title(), "折行的标题必须被拼回");
            assertEquals(26, units.get(2).startPage());
        }

        @Test
        @DisplayName("目录为空时返回空列表（不抛异常）")
        void emptyToc() {
            assertEquals(List.of(), TocRules.tocUnits(List.of(), 5));
            assertEquals(List.of(), TocRules.tocUnits(null, 5));
        }

        @Test
        @DisplayName("页序偏移取众数")
        void pageOffsetByMode() {
            // PDF 第 1 页 = 印刷页 1 → 偏移 0；第 2 页 = 印刷 2 → 0
            List<String> pages = List.of("1\n正文", "2\n正文", "3\n正文");
            assertEquals(0, TocRules.pageOffset(pages));
        }

        @Test
        @DisplayName("抽不到印刷页码时返回 null（不得回落 0）")
        void noPageNumberReturnsNull() {
            assertNull(TocRules.pageOffset(List.of("没有页码\n正文")),
                    "回落 0 会让章节归属整体错位且不报错");
        }

        @Test
        @DisplayName("附录判定优先于单元判定")
        void appendixWins() {
            List<TocRules.TocUnit> units = List.of(
                    new TocRules.TocUnit(1, "Hello", 2),
                    new TocRules.TocUnit(5, "Neighbourhood", 58));
            assertEquals("Unit 5 Neighbourhood", TocRules.sectionOf(60, units, "前置", null));
            assertEquals("附录", TocRules.sectionOf(134, units, "前置", 105),
                    "附录起始页之后即使有单元号残留，也必须归附录");
            assertEquals("前置", TocRules.sectionOf(1, units, "前置", null));
        }
    }

    @Nested
    @DisplayName("Z7 附录切分 A-1/A-2/A-3（对照脚本 appendix_pieces()）")
    class Appendix {

        private final List<TocRules.TocUnit> units = List.of(
                new TocRules.TocUnit(4, "Food", 38),
                new TocRules.TocUnit(5, "Green neighbourhood", 58));

        @Test
        @DisplayName("A-1 按 Unit 边界把同页的多单元词表切开")
        void splitsByUnit() {
            String page = "Words and expressions in each unit\n"
                    + "Unit 4 food, 食物, 很多很多很多内容用于越过四十字符阈值。\n"
                    + "Unit 5 neighbourhood, 街区, 同样需要足够长的正文内容来通过过滤。";
            AppendixRules.State state = new AppendixRules.State();
            List<AppendixRules.Piece> pieces = AppendixRules.appendixPieces(page, units, state);

            assertEquals(2, pieces.size(), "同页两个单元必须切成两片");
            assertTrue(pieces.get(0).sectionPath().contains("Unit 4"));
            assertTrue(pieces.get(1).sectionPath().contains("Unit 5"));
        }

        @Test
        @DisplayName("A-3 片段补中英双形标题（中文问法也能命中纯英文词表）")
        void addsBilingualPrefix() {
            String page = "Words and expressions in each unit\n"
                    + "Unit 5 neighbourhood, 街区, 很多很多内容用于越过四十字符阈值。";
            AppendixRules.State state = new AppendixRules.State();
            List<AppendixRules.Piece> pieces = AppendixRules.appendixPieces(page, units, state);

            assertEquals(1, pieces.size());
            assertTrue(pieces.get(0).text().startsWith("Unit 5（第五单元）单词表："),
                    "前缀是功能性的：纯英文词表里没有「第五单元」，中文提问会漏召回");
        }

        @Test
        @DisplayName("A-2 续页继承上一页的单元状态")
        void continuationInheritsUnit() {
            AppendixRules.State state = new AppendixRules.State();
            String head = "Words and expressions in each unit\n"
                    + "Unit 5 neighbourhood, 街区, 很多很多内容用于越过四十字符阈值。";
            AppendixRules.appendixPieces(head, units, state);

            // 续页不再重复 Unit 5 标题
            String continuation = "neighbour, 邻居, 续页内容同样需要足够长度才能通过过滤阈值。";
            List<AppendixRules.Piece> cont = AppendixRules.appendixPieces(continuation, units, state);

            assertEquals(1, cont.size());
            assertTrue(cont.get(0).sectionPath().contains("Unit 5"),
                    "续页必须归属上一页的单元，而不是退化成「附录 · 词表」");
        }

        @Test
        @DisplayName("字母序词表标题出现即退出按单元词表区")
        void alphaListExitsWordlist() {
            AppendixRules.State state = new AppendixRules.State();
            // ⚠️ 与脚本一致：同一页同时出现「按单元」与「字母序」两个标题时，
            //    脚本的 if/elif 分支让按单元分支优先（先判 HEAD 再判 ALPHA）。
            //    故退出必须发生在「只含 ALPHA 的那一页」——这才是真实的版面形态
            //    （字母序词表是独立的另一节，不会与按单元标题同页）。
            AppendixRules.appendixPieces(
                    "Words and expressions in each unit\n"
                            + "Unit 5 neighbourhood, 街区, 很多很多内容用于越过四十字符阈值。",
                    units, state);
            assertTrue(state.inWordlist(), "先进入按单元词表区");

            AppendixRules.appendixPieces("Words and expressions in alphabetical order", units, state);
            assertFalse(state.inWordlist(), "出现字母序标题后不应再处于按单元词表区");
        }

        @Test
        @DisplayName("非词表页按栏目名给出比「附录」更具体的路径")
        void nonWordlistPageGetsSpecificPath() {
            AppendixRules.State state = new AppendixRules.State();
            // 先退出词表区
            AppendixRules.appendixPieces("Words and expressions in alphabetical order", units, state);
            List<AppendixRules.Piece> pieces =
                    AppendixRules.appendixPieces("Spelling rules 拼写规则说明", units, state);
            assertEquals(1, pieces.size());
            assertTrue(pieces.get(0).sectionPath().contains("附录"));
        }
    }

    @Nested
    @DisplayName("Z8 角色分类（对照脚本 classify()）")
    class Classify {

        @Test
        @DisplayName("附录优先：词表页即使含「答案」也判 APPENDIX")
        void appendixWinsOverAnswer() {
            assertEquals(ChunkRole.APPENDIX,
                    RoleRules.classify("Word list 单词表，答案见附录"),
                    "先判答案会把整页词表误判成答案区");
        }

        @Test
        @DisplayName("答案信号判 EXERCISE_ANSWER")
        void detectsAnswer() {
            assertEquals(ChunkRole.EXERCISE_ANSWER, RoleRules.classify("参考答案：1. A 2. B"));
            assertEquals(ChunkRole.EXERCISE_ANSWER, RoleRules.classify("Answers to the exercises"));
        }

        @Test
        @DisplayName("≥3 个题眼词才判 EXERCISE（单个动词不足以说明是题区）")
        void needsThreeExerciseSignals() {
            assertEquals(ChunkRole.EXERCISE, RoleRules.classify("Ask and answer. Write down. Complete it."));
            assertEquals(ChunkRole.PAPER_BODY, RoleRules.classify("Write something here"),
                    "单个 Write 可能出现在任何讲解段落");
        }

        @Test
        @DisplayName("讲解信号判 EXPLANATION")
        void detectsExplanation() {
            assertEquals(ChunkRole.EXPLANATION, RoleRules.classify("Grammar Focus 语法要点"));
        }

        @Test
        @DisplayName("无信号时回落 PAPER_BODY")
        void fallsBackToPaperBody() {
            assertEquals(ChunkRole.PAPER_BODY, RoleRules.classify("这是一段普通的课文内容"));
        }
    }

    @Nested
    @DisplayName("辅助：中文数字（对照脚本 cn_unit()）")
    class CnUnit {

        @Test
        @DisplayName("1~12 的中文写法")
        void chineseNumbers() {
            assertEquals("一", AppendixRules.cnUnit(1));
            assertEquals("五", AppendixRules.cnUnit(5));
            assertEquals("十", AppendixRules.cnUnit(10));
            assertEquals("十一", AppendixRules.cnUnit(11));
            assertEquals("十二", AppendixRules.cnUnit(12));
        }
    }
}
