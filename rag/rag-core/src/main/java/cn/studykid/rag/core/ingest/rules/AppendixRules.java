package cn.studykid.rag.core.ingest.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 附录切分规则（方案 §4 Z7 的 A-1 / A-2 / A-3）。
 *
 * <p><b>⚠️ 逐条移植自 {@code scripts/rag_import_pdf.py} 的 {@code appendix_pieces}</b>。
 * 这段规则是教辅 RAG 切分质量的关键，三条子规则分别解决：
 * <ul>
 *   <li><b>A-1 按逻辑单位切</b>：整页一片时，Unit 5 的词表埋在附录 p.134~139 那几片
 *       1900 字里，同页还混着 Unit 4 / Unit 6，section_path 只能给「附录」——
 *       学生查「第五单元的单词」时表征被稀释到查不出来。故按 {@code Unit N} 边界重切。</li>
 *   <li><b>A-2 续页状态机</b>：词表起始页之后的续页<b>不再重复</b> {@code Unit N} 标题，
 *       所以当前单元必须记在跨页状态里，续页整页归给上一页留下的那个单元。</li>
 *   <li><b>A-3 中英双形标题</b>：片段开头补一行「Unit 5（第五单元）单词表：」，
 *       让「第五单元」这种中文问法也能命中纯英文词表。</li>
 * </ul>
 *
 * <p><b>⚠️ 本类是无状态纯函数的组合</b>（跨页状态由调用方持有 {@link State} 并逐页传入）：
 * 纯函数才能被单测逐条断言，也才能与离线脚本做「同一册 diff 归零」的对拍。
 */
public final class AppendixRules {

    private AppendixRules() {
    }

    /** 词表区起始标题。 */
    private static final String WORDLIST_HEAD = "Words and expressions in each unit";

    /** 字母序词表标题（出现即表示已离开「按单元」词表区）。 */
    private static final String WORDLIST_ALPHA = "Words and expressions in alphabetical order";

    /**
     * 单元标记：{@code Unit 5} 或 {@code Starter}。
     *
     * <p>⚠️ {@code (?!\s*\(p)} 排除目录式的 {@code Unit 5 (p58)}——
     * 否则附录里引用的目录行会被当成真实单元边界，切出错位的片段。
     */
    private static final Pattern UNIT_MARK =
            Pattern.compile("\\bUnit\\s+(\\d{1,2})\\b(?!\\s*\\(p)|\\bStarter\\b");

    /**
     * 附录其它栏目：出现即说明已离开词表区。
     *
     * <p><b>⚠️ 必须按特异性排序，先匹配到的算数</b>：「Notes: …」这类页眉在
     * 拼写/读音规则页里也会复现，若让泛用的「Notes」先判，整页会被错标成
     * 「附录 · 注释」。这个顺序是有实据的，调整前请先看脚本注释。
     */
    private static final String[][] APPENDIX_HEADS = {
            {"Spelling rules", "语音"},
            {"Pronunciation rules", "语音"},
            {"Irregular verbs", "不规则动词表"},
            {"Irreg ular verbs", "不规则动词表"},
            {"Months and days", "月份与星期"},
            {"Numbers Cardinal", "数字"},
            {"Word partner", "词汇拓展"},
            {"Word study", "词汇拓展"},
            {"Word group", "词汇拓展"},
            {"Sound file", "语音"},
            {"My learning notes", "学习笔记"},
            {"Grammar file", "语法"},
            {"Proper nouns", "专有名词"},
            {"Glossary", "术语表"},
            {"Culture corner", "文化角"},
            {"Notes", "注释"},
    };

    private static final String CN_NUM = "零一二三四五六七八九十";

    /**
     * 附录切分的跨页状态。
     *
     * <p>⚠️ 必须由调用方在一次整册处理内<b>持续持有</b>：每页新建一个会让 A-2
     * 失效（续页失去所属单元，被错切到「附录 · 词表」）。
     */
    public static final class State {
        private boolean inWordlist;
        private Integer currentUnit;

        /** @return 是否处于按单元的词表区 */
        public boolean inWordlist() {
            return inWordlist;
        }

        /** @return 当前所属单元号（null 表示 Starter 或尚未进入） */
        public Integer currentUnit() {
            return currentUnit;
        }

        /** 重置（换册时调用，避免跨册串状态）。 */
        public void reset() {
            inWordlist = false;
            currentUnit = null;
        }
    }

    /**
     * 一个切出的片段：章节路径 + 正文。
     *
     * @param sectionPath 章节路径（如 {@code "Unit 5 Green neighbourhood 词表"}）
     * @param text        正文（已补 A-3 前缀）
     */
    public record Piece(String sectionPath, String text) {
    }

    /**
     * 把一页附录切成若干「按单元 / 按栏目」的片段（A-1 + A-2 + A-3）。
     *
     * @param flat   该页压平后的文本
     * @param units  目录单元列表（用于取单元标题）
     * @param state  跨页状态（会被本方法修改）
     * @return 片段列表；非词表页只返回一片（栏目名比「附录」更具体）
     */
    public static List<Piece> appendixPieces(String flat, List<TocRules.TocUnit> units, State state) {
        if (flat == null || flat.isBlank()) {
            return List.of();
        }
        // ① 状态迁移：进入 / 退出词表区
        if (flat.contains(WORDLIST_HEAD)) {
            state.inWordlist = true;
        } else if (flat.contains(WORDLIST_ALPHA) || matchesAnyHead(flat)) {
            state.inWordlist = false;
            state.currentUnit = null;
        }

        // ② 非词表页：按栏目名给出比「附录」更具体的路径
        if (!state.inWordlist) {
            String kind = headKind(flat);
            return List.of(kind == null
                    ? new Piece("附录", flat)
                    : new Piece("附录 · " + kind, flat));
        }

        // ③ 词表页：按 Unit N 边界重切
        List<Piece> pieces = new ArrayList<>();
        List<int[]> marks = unitMarks(flat);
        int cursor = 0;
        for (int[] mk : marks) {
            String before = flat.substring(cursor, mk[0]);
            emit(pieces, state.currentUnit, before, units);
            String token = flat.substring(mk[0], mk[1]);
            state.currentUnit = "Starter".equals(token) ? null : Integer.parseInt(token.replaceAll("\\D", ""));
            cursor = mk[1];
        }
        emit(pieces, state.currentUnit, flat.substring(cursor), units);

        if (pieces.isEmpty()) {
            // ⚠️ 兜底：词表页但一个 Unit 标记都没有时，整页归「附录 · 词表」，
            //    而不是返回空——返回空会让这页内容彻底丢失（比切得粗更糟）。
            return List.of(new Piece("附录 · 词表", flat));
        }
        return pieces;
    }

    /**
     * 产出片段（A-3：补中英双形标题前缀）。
     *
     * @param out    结果收集
     * @param unit   所属单元（null 表示 Starter 或未归属）
     * @param body   正文
     * @param units  目录单元
     */
    private static void emit(List<Piece> out, Integer unit, String body, List<TocRules.TocUnit> units) {
        String trimmed = body == null ? "" : body.strip();
        // ⚠️ 只丢「还没进任何单元」的栏目抬头行；已归属单元的内容一律保留，
        //    哪怕只剩几十字——它可能就是一个单元的全部词条。
        if (trimmed.isEmpty() || (unit == null && trimmed.length() < 40)) {
            return;
        }
        out.add(new Piece(wordlistSection(unit, units), wordlistPrefix(unit) + " " + trimmed));
    }

    /**
     * 词表片段的栏目路径（A-3 的一半：与正文的 {@code Unit N <标题>} 命名同形状）。
     *
     * @param unit  单元号（null 表示 Starter）
     * @param units 目录单元
     * @return 章节路径
     */
    private static String wordlistSection(Integer unit, List<TocRules.TocUnit> units) {
        if (unit == null) {
            return "Starter 词表";
        }
        String title = units.stream()
                .filter(u -> u.unitNo() == unit)
                .map(TocRules.TocUnit::title)
                .findFirst()
                .orElse("");
        return ("Unit " + unit + " " + title + " 词表").strip();
    }

    /**
     * 片段开头补一行标题（A-3 的另一半）。
     *
     * <p>⚠️ 这一行是**功能性**的，不是装饰：纯英文词表里没有「第五单元」四个字，
     * 中文提问会因为词法通道完全不命中而漏召回。
     *
     * @param unit 单元号
     * @return 前缀
     */
    private static String wordlistPrefix(Integer unit) {
        if (unit == null) {
            return "Starter 单词表：";
        }
        return "Unit " + unit + "（第" + cnUnit(unit) + "单元）单词表：";
    }

    /**
     * 单元号的中文写法（脚本 {@code cn_unit}，1~12 够用）。
     *
     * @param no 单元号
     * @return 中文数字
     */
    static String cnUnit(int no) {
        if (no <= 10) {
            return String.valueOf(CN_NUM.charAt(no));
        }
        if (no < 20) {
            return "十" + CN_NUM.charAt(no - 10);
        }
        return "十" + CN_NUM.charAt(no / 10) + CN_NUM.charAt(no % 10);
    }

    private static boolean matchesAnyHead(String flat) {
        for (String[] head : APPENDIX_HEADS) {
            if (flat.contains(head[0])) {
                return true;
            }
        }
        return false;
    }

    private static String headKind(String flat) {
        for (String[] head : APPENDIX_HEADS) {
            if (flat.contains(head[0])) {
                return head[1];
            }
        }
        return null;
    }

    /**
     * 找出单元标记的位置（起始下标、结束下标）。
     *
     * @param flat 文本
     * @return 按出现顺序的位置列表
     */
    private static List<int[]> unitMarks(String flat) {
        List<int[]> out = new ArrayList<>();
        Matcher m = UNIT_MARK.matcher(flat);
        while (m.find()) {
            out.add(new int[]{m.start(), m.end()});
        }
        return out;
    }
}
