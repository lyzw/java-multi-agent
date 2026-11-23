package cn.studykid.rag.premise.normalize;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.jspecify.annotations.NonNull;

/**
 * 章节路径的<b>唯一状态机</b>（RAG-D28 P1-4 归一化收敛）。
 *
 * <p><b>⚠️ 为什么必须有这个类</b>：P1-4 之前，「标题层级 → 章节路径」有三份实现——
 * <ol>
 *   <li>{@code DoclingResponseMapper.replaceAtLevel}（level 值栈 + 跨页新分区）；</li>
 *   <li>{@code MineruResponseMapper.replaceAtLevel}（同一算法的静态副本）；</li>
 *   <li>{@code SectionPathResolver.advance}（更简单的「按 level-1 截断」）。</li>
 * </ol>
 * 三份实现有两处已经确认的漂移：<b>分隔符</b>（前两者输出 {@code " / "}，后者与
 * D27 §4.3 规范输出 {@code " > "}）与<b>非连续层级</b>（前两者用 level 值栈正确处理
 * 1,2,3,5，后者按 level-1 截断会错位）。更隐蔽的是：即使 mapper 算对了，
 * 下游切分器仍用自己的算法重新推进，把 mapper 的结果覆盖掉——「算对了但被改写」。
 *
 * <p><b>本类把状态机收敛为一份</b>：mapper 与 {@link SectionPathResolver} 都调用
 * {@link #advance(int, int, String)} + {@link #render()}，从结构上杜绝再漂移。
 *
 * <p><b>血泪语义（不得凭直觉改）</b>，全部来自 2026-11 沪教版教材实测：
 * <ul>
 *   <li>同级标题<b>替换</b>而非追加（Starter 1/2/3 都是 lvl=3，串成链是错的）；</li>
 *   <li>level <b>变小</b>＝层级变浅，弹出所有更深槽（Part I(lvl=1) 不能挂在
 *       Starter 4(lvl=3) 下面）；</li>
 *   <li>level 值<b>不连续</b>（1,2,3,5 跳过 4），必须按「已见 level 值」定位槽位，
 *       不能用 level-1 当下标；</li>
 *   <li>跨页较多（&gt;1 页）且层级未变深＝<b>新的顶层分区</b>，清空路径
 *       （否则 p103~p116 会累积出 236 字符的超长路径）；</li>
 *   <li>标题里的页眉后缀（如「上海教育出版社」）要在入路径前剥掉。</li>
 * </ul>
 */
public final class SectionPathStack {

    /** 章节路径分隔符——D27 §4.3 规范值，全链唯一来源（D28 P1-4）。 */
    public static final String SEPARATOR = " > ";

    /** 最大深度（册 &gt; 单元 &gt; 小节）；超过则替换末槽而非无限追加。 */
    public static final int MAX_DEPTH = 3;

    /** 页眉后缀（沪教版实测：标题行尾拼着出版社名）。 */
    private static final Pattern HEADER_SUFFIX = Pattern.compile("上海教育出版社");

    private final List<Integer> levels = new ArrayList<>();
    private final List<String> titles = new ArrayList<>();
    private int lastHeadingPage = -1;

    /**
     * 剥掉标题里的页眉后缀。
     *
     * <p>⚠️ 与 {@code LayoutPredicates.stripPageHeaderSuffix} 同源：这里持有唯一的
     * 正则，infrastructure 侧委托本方法，避免两份实现漂移（同
     * {@code LayoutPredicatesParityTest} 守护的题号判定纪律）。
     *
     * @param text 原始标题
     * @return 剥掉页眉后的标题（剥完为空则退回原值）
     */
    public static String stripHeaderSuffix(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String stripped = HEADER_SUFFIX.matcher(text).replaceAll("").trim();
        return stripped.isEmpty() ? text : stripped;
    }

    /**
     * 推进一个标题：先判「新分区」，再按 level 值槽位替换。
     *
     * @param page  标题所在物理页码（1 基；用于跨页新分区判定）
     * @param level 标题层级（1 基；&lt;1 按 1 处理）
     * @param title 标题文本（本方法内部剥页眉后缀）
     */
    public void advance(int page, int level, String title) {
        int effectiveLevel = Math.max(1, level);
        int deepest = deepestLevel();
        // 跨页 >1 且层级未变深 → 新顶层分区，清空重来。
        boolean newPartition = page - lastHeadingPage > 1 && effectiveLevel <= deepest;
        if (newPartition) {
            levels.clear();
            titles.clear();
        }
        replaceAtLevel(effectiveLevel, stripHeaderSuffix(title));
        lastHeadingPage = page;
    }

    /** @return 当前章节路径（{@code " > "} 分隔）；空栈返回空串。 */
    public @NonNull String render() {
        return String.join(SEPARATOR, titles);
    }

    /** @return 栈是否尚无标题。 */
    public boolean isEmpty() {
        return titles.isEmpty();
    }

    /** @return 当前最深 level；空栈返回 {@link Integer#MAX_VALUE}（新分区判定的哨兵）。 */
    public int deepestLevel() {
        return levels.isEmpty() ? Integer.MAX_VALUE : levels.get(levels.size() - 1);
    }

    /** 清空栈（供测试夹具与显式重置使用）。 */
    public void clear() {
        levels.clear();
        titles.clear();
        lastHeadingPage = -1;
    }

    /**
     * 把新标题写入对应 level 槽位，并截断其后的所有更深槽。
     */
    private void replaceAtLevel(int level, String rawTitle) {
        String title = rawTitle == null ? "" : rawTitle.trim();
        int idx = levels.indexOf(level);
        if (idx >= 0) {
            titles.set(idx, title);
            trimFrom(idx + 1);
        } else if (!levels.isEmpty() && level < levels.get(levels.size() - 1)) {
            while (!levels.isEmpty() && levels.get(levels.size() - 1) > level) {
                removeLast();
            }
            pushOrReplaceLast(level, title);
        } else {
            pushOrReplaceLast(level, title);
        }
    }

    private void pushOrReplaceLast(int level, String title) {
        if (titles.size() >= MAX_DEPTH) {
            titles.set(titles.size() - 1, title);
            levels.set(levels.size() - 1, level);
        } else {
            titles.add(title);
            levels.add(level);
        }
    }

    private void trimFrom(int size) {
        while (titles.size() > size) {
            removeLast();
        }
    }

    private void removeLast() {
        titles.remove(titles.size() - 1);
        levels.remove(levels.size() - 1);
    }
}
