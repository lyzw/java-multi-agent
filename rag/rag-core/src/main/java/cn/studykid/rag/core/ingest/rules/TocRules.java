package cn.studykid.rag.core.ingest.rules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 目录/章节探测规则（方案 §4 Z7 / 设计 §3.2）。
 *
 * <p><b>⚠️ 逐条移植自 {@code scripts/rag_import_pdf.py}</b>（{@code toc_units}、
 * {@code page_offset}、{@code section_of}）。这几个函数解决的是「按册手标参数」
 * 这个痛点：离线脚本要人工给 {@code --toc-from/--toc-pages/--skip-pages}，
 * 移植后由页面内容自标定，S2 的验收即「零手工参数跑通」。
 *
 * <p><b>⚠️ 本类只做「探测」不做「切分」</b>：探测结果（单元列表、页序偏移、
 * 章节归属）供切分使用；切分本身见 {@link AppendixRules} 与 {@code IngestionPipeline}。
 */
public final class TocRules {

    private TocRules() {
    }

    /**
     * 目录单元：单元号、单元名、印刷起始页。
     *
     * @param unitNo     单元号（1~12；Starter 不在其中）
     * @param title      单元标题
     * @param startPage  印刷起始页
     */
    public record TocUnit(int unitNo, String title, int startPage) {
    }

    /**
     * 目录行匹配：{@code 5 Green neighbourhood (p58)} 这类形态。
     *
     * <p>⚠️ 与脚本一致：单元号 1~12、标题 4~60 字符的英文字母开头串、页码 1~400。
     * 收紧范围是为了跳过目次里的「页码 300+」的索引页与单字母题号噪声。
     */
    private static final Pattern TOC_UNIT = Pattern.compile(
            "(?:^|\\s)(\\d{1,2})\\s+([A-Za-z][A-Za-z ,'\\-]{3,60}?)\\s*\\(p(\\d{1,3})\\)");

    /** 抄录噪声把 {@code (p. 26)} 拆成两段的修复（脚本同款）。 */
    private static final Pattern BROKEN_PAGE_REF = Pattern.compile(
            "\\(\\s*p\\.?\\s*\\.?\\s*(\\d{1,3})\\s*\\)");

    /**
     * 从目录页解析单元列表（脚本 {@code toc_units}）。
     *
     * <p><b>⚠️ 必须先把目录页拼成整串再压平空白</b>：抽取噪声会把
     * {@code (p. 26)} 拆成 {@code (p} / {@code . 26)} 两段、把单元名折行成
     * {@code Green\nneighbourhood\n(p. 58)}——按行匹配会漏掉一半单元。
     *
     * @param tocPages 目录页文本（按页序）
     * @param maxPages 参与探测的页数上限
     * @return 按单元号升序的单元列表（空表示未探测到）
     */
    public static List<TocUnit> tocUnits(List<String> tocPages, int maxPages) {
        if (tocPages == null || tocPages.isEmpty() || maxPages <= 0) {
            return List.of();
        }
        int limit = Math.min(maxPages, tocPages.size());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(tocPages.get(i));
        }
        String blob = sb.toString();
        blob = BROKEN_PAGE_REF.matcher(blob).replaceAll("(p$1)");
        blob = blob.replaceAll("\\s+", " ");

        Map<Integer, TocUnit> seen = new LinkedHashMap<>();
        Matcher m = TOC_UNIT.matcher(blob);
        while (m.find()) {
            int no = Integer.parseInt(m.group(1));
            String title = m.group(2).replaceAll("\\s+", " ").trim();
            int start = Integer.parseInt(m.group(3));
            // ⚠️ 同号只取首次出现：目录里同一单元可能因页眉重复出现。
            if (seen.containsKey(no) || no < 1 || no > 12 || start < 1 || start > 400) {
                continue;
            }
            seen.put(no, new TocUnit(no, title, start));
        }
        List<TocUnit> out = new ArrayList<>(seen.values());
        out.sort(Comparator.comparingInt(TocUnit::unitNo));
        return out;
    }

    /**
     * 自标定「PDF 序号 − 印刷页码」的偏移（脚本 {@code page_offset}）。
     *
     * <p>教材每页把印刷页码单独排在首行，取「PDF 序号 − 页码」的众数即可自标定；
     * 页面里孤立的练习题号只是个别噪声，不影响众数。
     *
     * @param pages 各页文本（第 i 个元素对应 PDF 第 i+1 页）
     * @return 页序偏移（印刷页 = PDF 序号 − 偏移）；无法标定时返回 {@code null}
     */
    public static Integer pageOffset(List<String> pages) {
        if (pages == null || pages.isEmpty()) {
            return null;
        }
        Map<Integer, Integer> deltas = new LinkedHashMap<>();
        for (int idx = 0; idx < pages.size(); idx++) {
            String text = pages.get(idx);
            String first = firstLine(text);
            Matcher m = Pattern.compile("\\s*(\\d{1,3})\\s*").matcher(first);
            if (m.matches()) {
                int delta = (idx + 1) - Integer.parseInt(m.group(1));
                deltas.merge(delta, 1, Integer::sum);
            }
        }
        if (deltas.isEmpty()) {
            // ⚠️ 抽不到任何印刷页码时必须返回 null 让调用方显式失败，
            //    不能回落 0——那会让所有章节归属整体错位且不报错（比报错更坏）。
            return null;
        }
        return deltas.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    /**
     * 判断印刷页所属章节（脚本 {@code section_of}）。
     *
     * <p>⚠️ 附录判定<b>优先于</b>单元判定：附录起始页之后即使仍有单元号残留，
     * 也必须归到附录——否则词表会被错挂到最后一个单元下。
     *
     * @param printedPage    印刷页码
     * @param units          目录单元列表
     * @param starterLabel   首个单元之前的段名（如「前置单元」）
     * @param appendixStart  附录起始印刷页（可为 null 表示无附录）
     * @return 章节路径（如 {@code "Unit 5 Green neighbourhood"}）
     */
    public static String sectionOf(int printedPage, List<TocUnit> units,
                                   String starterLabel, Integer appendixStart) {
        if (appendixStart != null && printedPage >= appendixStart) {
            return "附录";
        }
        String label = null;
        for (TocUnit u : units) {
            if (printedPage >= u.startPage()) {
                label = "Unit " + u.unitNo() + " " + u.title();
            }
        }
        return label != null ? label : starterLabel;
    }

    private static String firstLine(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        int nl = text.indexOf('\n');
        return nl < 0 ? text : text.substring(0, nl);
    }
}
