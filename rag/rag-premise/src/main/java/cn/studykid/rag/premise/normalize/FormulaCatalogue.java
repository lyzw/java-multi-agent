package cn.studykid.rag.premise.normalize;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.NonNull;

/**
 * 核心公式受控词典（P1-6，RAG-D29 DEC-P3「先受控规则 + 人工抽检，不上小模型」）。
 *
 * <p><b>⚠️ 这是全部自然语言描述的唯一来源</b>：每条描述都由人工审核后写死，
 * 不是运行时拼出来的。DEC-P3 明令「不确定处显式标注，禁生成期自猜」——
 * 因此本词典只收录小初高频、表述无歧义的核心公式；不在表内的公式
 * 由 {@link FormulaEnricher} 标为「待抽检」，<b>绝不</b>凭 LaTeX 结构乱猜含义。
 *
 * <p><b>匹配口径</b>：{@link #lookup} 用 {@link #normalizeKey} 规范化后精确匹配
 * （去 {@code $} 定界、去空白、下标写法归一），不用「相似/包含」模糊匹配——
 * 模糊命中会把 {@code v=v0+at} 的描述套到 {@code v=v0+bt} 上，正是要防的幻觉。
 *
 * <p><b>扩容纪律</b>：新增条目须 ① 人工审核描述与符号含义；② 补对应单测；
 * ③ 在黄金集上抽检通过。禁止为「让某个用例过」临时加词条（放水）。
 */
public final class FormulaCatalogue {

    /**
     * 受控词条。key 为 {@link #normalizeKey} 规范化后的匹配键。
     *
     * <p>⚠️ 描述取自 D27 §4.4 示例与学科通行表述；每条都需人工抽检确认。
     * 选取原则：小初高频 + 符号含义无歧义。
     */
    private static final Map<String, FormulaEntry> ENTRIES = Map.ofEntries(
            Map.entry("v=v0+at", new FormulaEntry(
                    "v = v_0 + at",
                    "匀变速直线运动速度时间公式，v 为末速度，v_0 为初速度，a 为加速度，t 为时间",
                    Map.of("v", "末速度", "v_0", "初速度", "a", "加速度", "t", "时间"))),
            Map.entry("e=mc2", new FormulaEntry(
                    "E = mc^2",
                    "质能方程，E 为能量，m 为质量，c 为光速",
                    Map.of("E", "能量", "m", "质量", "c", "光速"))),
            Map.entry("f=ma", new FormulaEntry(
                    "F = ma",
                    "牛顿第二定律，F 为合外力，m 为质量，a 为加速度",
                    Map.of("F", "合外力", "m", "质量", "a", "加速度"))),
            Map.entry("i=ur", new FormulaEntry(
                    "I = U/R",
                    "欧姆定律，I 为电流，U 为电压，R 为电阻",
                    Map.of("I", "电流", "U", "电压", "R", "电阻"))),
            Map.entry("s=vt", new FormulaEntry(
                    "s = vt",
                    "匀速直线运动路程公式，s 为路程，v 为速度，t 为时间",
                    Map.of("s", "路程", "v", "速度", "t", "时间"))),
            Map.entry("c=2πr", new FormulaEntry(
                    "C = 2πr",
                    "圆的周长公式，C 为周长，r 为半径，π 为圆周率",
                    Map.of("C", "周长", "r", "半径", "π", "圆周率"))),
            Map.entry("s=πr2", new FormulaEntry(
                    "S = πr²",
                    "圆的面积公式，S 为面积，r 为半径，π 为圆周率",
                    Map.of("S", "面积", "r", "半径", "π", "圆周率"))),
            Map.entry("a2+b2=c2", new FormulaEntry(
                    "a² + b² = c²",
                    "勾股定理，直角三角形两直角边平方和等于斜边平方，a、b 为直角边，c 为斜边",
                    Map.of("a", "直角边", "b", "直角边", "c", "斜边"))),
            Map.entry("w=fs", new FormulaEntry(
                    "W = Fs",
                    "功的计算公式，W 为功，F 为力，s 为力的方向上的位移",
                    Map.of("W", "功", "F", "力", "s", "位移")))
    );

    private FormulaCatalogue() {
    }

    /**
     * 按规范化键精确查表；未收录返回 null（调用方须 fail-closed 标为待抽检）。
     *
     * @param raw 公式原文（可带 LaTeX 定界）
     * @return 命中条目或 null
     */
    public static FormulaEntry lookup(@NonNull String raw) {
        if (raw == null) {
            return null;
        }
        return ENTRIES.get(normalizeKey(raw));
    }

    /**
     * 规范化匹配键：小写化、去 {@code $} 定界、去所有空白、去下标标记
     * （{@code _}/{@code ^} 后紧跟的花括号），把常见上标数字压平。
     *
     * <p>例：{@code $$v = v_{0} + at$$} → {@code v=v0+at}；
     * {@code E = mc^2} → {@code e=mc2}。
     */
    public static @NonNull String normalizeKey(@NonNull String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.toLowerCase(java.util.Locale.ROOT)
                .replace("$", "")
                .replace("\\left", "")
                .replace("\\right", "")
                .replace("−", "-")
                .replace("－", "-");
        StringBuilder sb = new StringBuilder(s.length());
        boolean inBrace = false; // 处于下标/上标花括号组内（内容保留，花括号本身丢弃）
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            if (c == '_' || c == '^') {
                // 下标/上标标记：跳过标记本身；若下一非空字符是 '{'，进入花括号组。
                int j = i + 1;
                while (j < s.length() && Character.isWhitespace(s.charAt(j))) {
                    j++;
                }
                if (j < s.length() && s.charAt(j) == '{') {
                    inBrace = true;
                    i = j; // 跳过 '{'
                }
                continue;
            }
            if (inBrace && c == '}') {
                inBrace = false; // 跳过闭合花括号
                continue;
            }
            sb.append(c);
        }
        // 上标字符压平为 ASCII 数字（Superscript ¹²³ 等）
        String flat = sb.toString()
                .replace("⁰", "0").replace("¹", "1").replace("²", "2")
                .replace("³", "3").replace("⁴", "4").replace("⁵", "5")
                .replace("⁶", "6").replace("⁷", "7").replace("⁸", "8")
                .replace("⁹", "9");
        return flat;
    }

    /** @return 受控词条数量（供守卫测试与登记） */
    public static int size() {
        return ENTRIES.size();
    }

    /** @return 受控词条列表（按 key 排序，供抽检清单导出） */
    public static @NonNull List<FormulaEntry> entries() {
        return ENTRIES.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .toList();
    }
}
