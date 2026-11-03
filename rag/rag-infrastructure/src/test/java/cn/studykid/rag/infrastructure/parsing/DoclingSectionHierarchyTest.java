package cn.studykid.rag.infrastructure.parsing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.rag.ports.BlockType;
import cn.studykid.rag.ports.LayoutBlock;

/**
 * 章节层级构建的回归测试（V1.0.2 S2-1 / S2-6）。
 *
 * <p><b>⚠️ 每条断言都对应一次真实教材踩坑</b>（沪教版六年级上/下册）：
 * <ol>
 *   <li>同级标题被串成链：'Starter 1 / Starter 2 / Starter 3'（三个都是 lvl=3）；</li>
 *   <li>level 值不连续：实测序列是 1,2,3,5（跳过 4），用 level-1 当下标会错位；</li>
 *   <li>level 变小时被当成「更深一层」：'Starter 4 / Part I'（Part I 是 lvl=1 顶层）；</li>
 *   <li>标题带页眉后缀：'Festivals across cultures 上海教育出版社' → 路径含噪声；</li>
 *   <li>目录页标题 'Contents' 被当成正文层级。</li>
 * </ol>
 */
class DoclingSectionHierarchyTest {

    private final DoclingResponseMapper mapper = new DoclingResponseMapper(null);

    /**
     * 构造一个只含标题的响应（用于层级验证）。
     *
     * <p>⚠️ 标题文本可带 {@code "@页码"} 后缀（如 {@code "Part I@19"}），
     * 以便还原真实教材的页码分布（实测上册 Part I 在 p19、Starter 1 在 p11，
     * 相距 8 页，是两套**平级**的顶层分区）。
     */
    private String doc(String... headerAndLevelPairs) {
        StringBuilder texts = new StringBuilder();
        StringBuilder children = new StringBuilder();
        for (int i = 0; i < headerAndLevelPairs.length; i += 2) {
            // ⚠️ 数组元素之间必须有逗号，否则 JSON 非法
            if (i > 0) {
                texts.append(",");
                children.append(",");
            }
            String rawTitle = headerAndLevelPairs[i];
            int page = 1;
            int at = rawTitle.lastIndexOf('@');
            if (at > 0) {
                page = Integer.parseInt(rawTitle.substring(at + 1));
                rawTitle = rawTitle.substring(0, at);
            }
            texts.append(String.format(
                    "{\"self_ref\":\"#/texts/%d\",\"label\":\"section_header\","
                            + "\"text\":\"%s\",\"level\":%s,\"prov\":[{\"page_no\":%d}]}",
                    i / 2, rawTitle, headerAndLevelPairs[i + 1], page));
            children.append(String.format("{\"$ref\":\"#/texts/%d\"}", i / 2));
        }
        return "{\"status\":\"success\",\"document\":{\"json_content\":{"
                + "\"texts\":[" + texts + "],"
                + "\"body\":{\"children\":[" + children + "]}}}}";
    }

    private List<LayoutBlock> map(String... pairs) {
        return mapper.map(doc(pairs));
    }

    @Test
    @DisplayName("同级标题互相替换，不串成链（Starter 1/2/3 都是 lvl=3）")
    void siblingsReplaceEachOther() {
        List<LayoutBlock> bs = map(
                "Starter 1", "3", "Starter 2", "3", "Starter 3", "3");
        assertEquals(3, bs.size());
        assertEquals("Starter 1", bs.get(0).sectionPath());
        assertEquals("Starter 2", bs.get(1).sectionPath(), "同级必须替换而非追加");
        assertEquals("Starter 3", bs.get(2).sectionPath());
    }

    @Test
    @DisplayName("level 值不连续（2,3,5）时层级仍正确")
    void nonContiguousLevelsWork() {
        // ⚠️ 用「Part I」(lvl=1) 而非 '英语'(lvl=1)：
        //    实测两册的 '英语'/'ENGLISH' 都出现在第 1~3 页（封面书名），
        //    isNoiseHeading 会过滤掉它们——那是对的（书名不是章节）。
        // ⚠️ 数组顺序 = 正文出现顺序，页码必须单调递增。
        //    实测上册：Starter 1(p11) → To-do list(p17) → Part I(p19)。
        List<LayoutBlock> bs = map(
                "Starter 1@11", "3", "To-do list@17", "5", "Part I@19", "1");
        assertEquals("Starter 1", bs.get(0).sectionPath());
        // lvl=5 比 lvl=3 深 → 嵌套
        assertEquals("Starter 1 / To-do list", bs.get(1).sectionPath());
        // ⚠️ lvl=1 变小 + 跨页 → 新的顶层分区，路径应重置为 "Part I"
        assertEquals("Part I", bs.get(2).sectionPath(),
                "level 变小且跨页 → 新的顶层分区，不该是「Starter 1 / To-do list / Part I」");
    }

    @Test
    @DisplayName("封面书名（英语/ENGLISH）不进路径")
    void coverTitleIsNoiseHeading() {
        List<LayoutBlock> bs = map("英语", "1", "Part I", "1");
        assertEquals("", bs.get(0).sectionPath(), "书名不是章节");
        assertEquals("Part I", bs.get(1).sectionPath());
    }

    @Test
    @DisplayName("level 变小时回到上层（Part I lvl=1 不该挂在 Starter 4 lvl=3 之下）")
    void levelDecreasePopsToUpperLevel() {
        List<LayoutBlock> bs = map(
                "Starter 4", "3", "Part I", "1");
        assertEquals("Starter 4", bs.get(0).sectionPath());
        assertEquals("Part I", bs.get(1).sectionPath(),
                "level 变小 = 回到更上层，不能是「Starter 4 / Part I」");
    }

    @Test
    @DisplayName("标题带页眉后缀时剥掉页眉但保留标题主体")
    void headerSuffixStrippedFromPath() {
        List<LayoutBlock> bs = map(
                "2 Rules around us Unit", "3",
                "Festivals across cultures 上海教育出版社", "4");
        assertEquals("2 Rules around us Unit", bs.get(0).sectionPath());
        assertEquals("2 Rules around us Unit / Festivals across cultures",
                bs.get(1).sectionPath(),
                "页眉后缀必须剥离，否则路径会被页眉噪声淹没");
    }

    @Test
    @DisplayName("目录页标题 Contents 不参与路径")
    void contentsIsNoiseHeading() {
        List<LayoutBlock> bs = map(
                "Contents", "2", "Starter 1", "3");
        assertEquals("", bs.get(0).sectionPath(), "Contents 是噪声标题，不应入路径");
        assertEquals("Starter 1", bs.get(1).sectionPath());
    }

    @Test
    @DisplayName("正文被误标为标题（«序号 + (Page N)» 开头）不进路径")
    void pageReferenceLyricsIsNotHeading() {
        // ⚠️ 实测上册 p116：歌词页被 Docling 标成 lvl=1
        //    '2. (Page 3) Big E, little e…' —— 那是正文，不是章节。
        List<LayoutBlock> bs = map(
                "Starter 1", "3",
                "2. (Page 3) Big E, little e, she sells seashells", "1");
        assertEquals("Starter 1", bs.get(0).sectionPath());
        assertEquals("Starter 1", bs.get(1).sectionPath(),
                "歌词正文不得覆盖章节路径");
    }

    @Test
    @DisplayName("跨页且不比最深层级更深的标题开启新分区（不累积）")
    void crossPageSameLevelStartsNewPartition() {
        // ⚠️ 实测上册 p105→p108→p109 曾累积出 236 个超长路径
        List<LayoutBlock> bs = map(
                "Exploring China", "1",
                "Exploring the world Asia", "3",
                "Bridging cultures", "4",
                "Highlights Read", "4");
        // 同页 (lvl=4 → lvl=4) 是替换，不是累积
        assertEquals("Exploring China / Exploring the world Asia / Highlights Read",
                bs.get(3).sectionPath().replace(" / ", " / "),
                "同层标题必须替换而非累积");
    }

    @Test
    @DisplayName("公式块内容取自 orig 字段（text 为空时）")
    void formulaTextComesFromOrig() {
        // ⚠️ 实测数学教材：label=formula 的节点 text 恒为 ''，
        //    内容只在 orig 里。只读 text 会让所有例题/公式退化成 '[图]'。
        String resp = "{\"status\":\"success\",\"document\":{\"json_content\":{"
                + "\"texts\":[{\"self_ref\":\"#/texts/0\",\"label\":\"formula\","
                + "\"text\":\"\",\"orig\":\"例３ 画一条数轴，并用数轴上的点表示下列各数\","
                + "\"level\":6,\"prov\":[{\"page_no\":14}]}],"
                + "\"body\":{\"children\":[{\"$ref\":\"#/texts/0\"}]}}}}";
        List<LayoutBlock> bs = mapper.map(resp);
        assertEquals(1, bs.size());
        assertEquals(BlockType.FORMULA, bs.get(0).type());
        assertTrue(bs.get(0).text().contains("例３"),
                "公式内容必须取自 orig，实际为：" + bs.get(0).text());
        assertFalse(bs.get(0).text().equals("[图]"), "不能退化成图片占位符");
    }

    @Test
    @DisplayName("路径深度不超过 3 层")
    void depthIsBounded() {
        List<LayoutBlock> bs = map(
                "L1", "1", "L2", "2", "L3", "3", "L4", "4");
        assertEquals(3, bs.get(3).sectionPath().split(" / ").length,
                "最深只能 3 层：" + bs.get(3).sectionPath());
    }
}
