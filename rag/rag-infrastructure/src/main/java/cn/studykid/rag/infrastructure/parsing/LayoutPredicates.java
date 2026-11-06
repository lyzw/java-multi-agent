package cn.studykid.rag.infrastructure.parsing;

import java.util.List;
import java.util.regex.Pattern;

import cn.studykid.rag.ports.BlockType;
import cn.studykid.rag.ports.LayoutBlock;

/**
 * 解析适配器的<b>共享版面判定</b>（RAG-D26 阶段 1.2 抽取）。
 *
 * <p><b>⚠️ 为什么从 {@link DoclingResponseMapper} 抽出来</b>：
 * 题号抽取、答案区标题判定、页眉剥除、噪声标题判定——这四条规则的血泪注释
 * （89% 题干误判答案区的事故、页眉后缀污染路径的事故）记录的是<b>版面事实</b>，
 * 不是 Docling 特有行为。MinerU 映射器遇到的是同一批教材、同一批版面形态，
 * <b>必须用同一套判定</b>，否则「换解析器」会把已修复的缺陷重新引入。
 *
 * <p>本类是<b>纯函数集合</b>：无状态、无 IO，Docling 与 MinerU 两个映射器共用；
 * 判定语义的任何修改必须同时跑两个 mapper 的测试
 * （{@code DoclingSectionHierarchyTest} / {@code MineruResponseMapperTest}）。
 */
public final class LayoutPredicates {

    private LayoutPredicates() {
    }

    /** 参与章节路径推进的最大标题层级（实测 level=6 是单元内小节，几乎每页出现）。 */
    public static final int MAX_SECTION_LEVEL = 5;

    /** 题号形态（沪教版实测：1. / （1） / Exercise 3 / 2 Work out…）。 */
    private static final Pattern EXERCISE_NO = Pattern.compile(
            "^\\s*(?:[(（]?\\s*(\\d{1,3})\\s*[.、)）]"
                    + "|[(（]\\s*(\\d{1,3})\\s*[)）]"
                    + "|Exercise\\s+(\\d{1,3})"
                    + "|(\\d{1,2})\\s+(?=[A-Za-z]))");

    /** 页眉后缀（沪教版实测：标题行尾拼着出版社名）。 */
    private static final Pattern PAGE_HEADER_SUFFIX = Pattern.compile("上海教育出版社");

    /**
     * 抽取题号（与 Docling mapper 的 {@code extractExerciseNo} 逐字符同规则）。
     *
     * @param text 块文本
     * @return 题号；无则 null
     */
    public static String extractExerciseNo(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        java.util.regex.Matcher m = EXERCISE_NO.matcher(text);
        if (m.find()) {
            for (int g = 1; g <= 4; g++) {
                if (m.group(g) != null) {
                    return m.group(g);
                }
            }
        }
        return null;
    }

    /**
     * 答案区<b>标题</b>判定（与 Docling mapper 同规则，含 89% 事故的教训注释）。
     *
     * <p>⚠️ 只认「像标题的」答案区标记；正文出现 answer/答案 是教学语言，
     * 用正文关键词判区曾把 1671/1871（89%）的块判成答案区。
     *
     * @param text 块文本
     * @return 是答案区标题返回 true
     */
    public static boolean isAnswerZoneHeading(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty() || t.length() > 40) {
            return false;
        }
        String lower = t.toLowerCase();
        return lower.startsWith("answer key") || lower.startsWith("answers to")
                || lower.startsWith("answer sheet") || lower.equals("answers")
                || t.startsWith("参考答案") || t.startsWith("答案与解析")
                || t.startsWith("答案") || t.startsWith("练习答案")
                || t.startsWith("习题答案");
    }

    /**
     * 剥掉标题里的页眉后缀（与 Docling mapper 同规则）。
     *
     * @param text 原始标题
     * @return 剥掉页眉后的标题（若剥完为空则返回原值）
     */
    public static String stripPageHeaderSuffix(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String stripped = PAGE_HEADER_SUFFIX.matcher(text).replaceAll("").trim();
        return stripped.isEmpty() ? text : stripped;
    }

    /**
     * 判断标题是否为「不该进入章节路径的噪声标题」（与 Docling mapper 同规则）。
     *
     * @param text 标题文本
     * @return 是噪声标题返回 true
     */
    public static boolean isNoiseHeading(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        String t = text.trim();
        if (t.contains("出版社")) {
            String stripped = t.replaceAll("上海教育出版社", "")
                    .replaceAll("[\\s·、,，]+$", "").trim();
            if (stripped.isEmpty()) {
                return true;
            }
            return false;
        }
        if (t.equals("英语") || t.equals("ENGLISH") || t.equals("YINGYU")) {
            return true;
        }
        if (t.contains("义务教育教科书") || t.contains("五·四学制")
                || t.contains("课标") || t.contains("后 记") || t.contains("后记")) {
            return true;
        }
        if (t.matches("\\d{1,3}")) {
            return true;
        }
        if (t.equals("Contents") || t.equals("目录") || t.equals("Table of contents")) {
            return true;
        }
        if (t.matches("^\\d{1,3}\\.\\s*\\(Page\\s+\\d+\\).*")) {
            return true;
        }
        return t.length() > 60;
    }

    /**
     * 视觉占位文本（空文本块按类型回填；与 Docling mapper 同表）。
     *
     * @param type 块类型
     * @return 占位文本
     */
    public static String placeholderFor(BlockType type) {
        return switch (type) {
            case TABLE -> "[表格]";
            case FIGURE -> "[图]";
            case FORMULA -> "[公式]";
            default -> "[空块]";
        };
    }

    /**
     * 答案区状态推进（跨块状态机，与 Docling mapper 同语义）：
     * ① 答案区标题 → 进入；② 已在答案区遇任意新标题 → 退出；③ 其余沿用。
     *
     * <p>⚠️ 答案区是<b>跨块状态</b>不是单块属性：只标标题会让标题下的答案内容
     * 以讲解身份进入检索 → 泄题。
     *
     * @param type         当前块类型
     * @param text         当前块文本
     * @param inAnswerZone 状态槽（长度 1，由调用方跨块持有）
     */
    public static void advanceAnswerZone(BlockType type, String text, boolean[] inAnswerZone) {
        if (type == BlockType.HEADING && isAnswerZoneHeading(text)) {
            inAnswerZone[0] = true;
        } else if (inAnswerZone[0] && type == BlockType.HEADING) {
            inAnswerZone[0] = false;
        }
    }

    /**
     * 章节路径里的标题主体是否参与推进（层级 + 噪声双闸）。
     *
     * @param type 块类型
     * @param level 标题层级
     * @param text 标题文本
     * @return 参与推进返回 true
     */
    public static boolean advancesSectionPath(BlockType type, int level, String text) {
        return type == BlockType.HEADING
                && level >= 1
                && level <= MAX_SECTION_LEVEL
                && !isNoiseHeading(text);
    }

    /**
     * 便捷重载：直接由 {@link LayoutBlock} 判定（MinerU 映射器的标题栈使用）。
     *
     * @param block 块
     * @return 参与推进返回 true
     */
    public static boolean advancesSectionPath(LayoutBlock block) {
        return advancesSectionPath(block.type(), block.headingLevel(), block.text());
    }

    /**
     * 跨页重复短行的页眉阈值（Docling 实测 5 起判，正文偶然重复不误伤）。
     */
    public static final int REPEATED_HEADER_MIN_PAGES = 5;

    /**
     * 页眉候选行长度上限。
     */
    public static final int REPEATED_HEADER_MAX_LEN = 30;

    /**
     * 页眉候选集合接口（供映射器传入自己的统计结果；
     * 判定阈值统一在本类，防两个 mapper 漂移）。
     *
     * @param line 候选行
     * @return 是否达到页眉阈值
     */
    public static boolean isRepeatedHeaderLine(List<Integer> pagesContainingLine) {
        return pagesContainingLine != null
                && pagesContainingLine.size() >= REPEATED_HEADER_MIN_PAGES;
    }
}
