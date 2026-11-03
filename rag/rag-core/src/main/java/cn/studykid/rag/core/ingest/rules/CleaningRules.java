package cn.studykid.rag.core.ingest.rules;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 清洗规则（方案 §4 Z6 / 设计 §3.2）。
 *
 * <p><b>⚠️ 本类是 Python 离线脚本 {@code scripts/rag_import_pdf.py} 的逐条移植</b>，
 * 不是重新设计。移植的目的只有一个：让「离线验证过的清洗效果」能在生产管道里复现。
 * 因此<b>规则必须与脚本行为等价</b>，判据是方案 S2 的硬验收：
 * <b>离线脚本与生产管道对同一册的切分结果 diff 归零</b>。
 *
 * <p>对应脚本函数：{@code normalize()}、{@code strip_boilerplate()}。
 *
 * <p><b>⚠️ 为什么清洗必须发生在切分之前</b>：教材每页都印出版社名/页码，
 * 不剥离就会出现在<b>每个</b>片段的正文里——检索时这些噪声词会稀释表征，
 * 让「第五单元单词表」这种查询被页眉里反复出现的「人民教育出版社」压过去。
 */
public final class CleaningRules {

    private CleaningRules() {
    }

    /** 跨页重复行被判定为页眉页脚的最短出现次数（与脚本 {@code max(3, pages//3)} 对应）。 */
    private static final int BOILERPLATE_MIN_COUNT = 3;

    /** 判定为「可能的页眉页脚」的行长上限（脚本同值）。超长行不参与统计，避免正文段落误判。 */
    private static final int BOILERPLATE_LINE_MAX_LEN = 40;

    /** 纯页码行（1~3 位数字），如 {@code "26"}、{@code "105"}。 */
    private static final Pattern PAGE_NUMBER_ONLY = Pattern.compile("\\d{1,3}");

    /**
     * 归一化抽取噪声（脚本 {@code normalize}）。
     *
     * <p>处理四类噪声：全角空格（{@code U+3000}）、em space（{@code U+2003}）、
     * 软连字符（{@code U+00AD}）、以及行内多空格与行首尾空白。
     * <b>空行被丢弃</b>——片段文本里保留空行只会在检索时产生无意义的跳字。
     *
     * @param raw 原始文本
     * @return 归一化后的文本（行以 {@code \n} 分隔）
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        for (String ln : raw.split("\n", -1)) {
            // ⚠️ 三种特殊空白逐字对应脚本的 replace 链，不要合并成 \s——
            //    \s 会顺带吃掉某些 CJK 排版里应当保留的窄空格，导致与离线结果不一致。
            String cleaned = ln
                    .replace('\u3000', ' ')
                    .replace('\u2003', ' ')
                    .replace("\u00AD", "");
            cleaned = cleaned.replaceAll("\\s+", " ").trim();
            if (!cleaned.isEmpty()) {
                lines.add(cleaned);
            }
        }
        return String.join("\n", lines);
    }

    /**
     * 剥离跨页重复的页眉页脚（脚本 {@code strip_boilerplate}）。
     *
     * <p>算法：统计全书「长度 ≤ 40 的行」的出现次数，出现次数
     * ≥ {@code max(3, 总页数/3)} 的行视为页眉页脚，从所有页中删除；
     * 同时删除整行仅为页码的行。
     *
     * <p><b>⚠️ 阈值取 {@code max(3, pages/3)} 而非固定值</b>：
     * 页数少的小册子（如 6 页）若用「≥3 次」会误伤正文里的重复短句；
     * 页数多的教材用 {@code pages/3} 才不会被偶发重复行干扰。
     *
     * @param pages 各页文本（已 normalize）
     * @return 清洗后的各页文本，页数不变
     */
    public static List<String> stripBoilerplate(List<String> pages) {
        if (pages == null || pages.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> counter = new HashMap<>();
        for (String text : pages) {
            for (String ln : splitLines(text)) {
                if (ln.length() <= BOILERPLATE_LINE_MAX_LEN) {
                    counter.merge(ln, 1, Integer::sum);
                }
            }
        }
        int threshold = Math.max(BOILERPLATE_MIN_COUNT, pages.size() / 3);
        Set<String> junk = new HashSet<>();
        counter.forEach((ln, c) -> {
            if (c >= threshold) {
                junk.add(ln);
            }
        });

        List<String> cleaned = new ArrayList<>(pages.size());
        for (String text : pages) {
            List<String> kept = new ArrayList<>();
            for (String ln : splitLines(text)) {
                if (junk.contains(ln)) {
                    continue;
                }
                // ⚠️ 整行只有页码的也要删：页码每页都不同，按「重复次数」抓不到，
                //    但它在每个片段里都是一条无意义噪声。
                if (PAGE_NUMBER_ONLY.matcher(ln).matches()) {
                    continue;
                }
                kept.add(ln);
            }
            cleaned.add(String.join("\n", kept));
        }
        return cleaned;
    }

    /**
     * 判断是否为封底/版权页噪声（脚本 {@code BACK_COVER_RE}）。
     *
     * <p><b>⚠️ 为什么单独判</b>：封底版权页整页只有一句出版信息，
     * 字数虽少但特征词密集，在检索里拿到 top1 的实例已有记录（10-28 记录 §9）。
     * 它不满足「跨页重复」的页眉判定，必须按内容判噪。
     *
     * @param text 页文本
     * @return 是封底/版权页返回 true
     */
    public static boolean isBackCoverOrCopyright(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        // 对应脚本正则：定价[:：]\s*\d|义务教育教科书\s*（|本套教材根据|编写团队
        return Pattern.compile("定价[:：]\\s*\\d").matcher(text).find()
                || Pattern.compile("义务教育教科书\\s*（").matcher(text).find()
                || text.contains("本套教材根据")
                || text.contains("编写团队");
    }

    /**
     * 全角/半角与兼容字符归一（辅助工具）。
     *
     * <p>⚠️ 脚本里<b>没有</b>这一步，这里是可选的增强项，默认<b>不</b>在管道中启用：
     * 一旦启用就必须同步改离线脚本，否则「diff 归零」判据会失效。
     * 保留方法是为了让 S2 的规则调整有落点。
     *
     * @param text 文本
     * @return NFKC 归一后的文本
     */
    public static String nfkc(String text) {
        return text == null ? "" : Normalizer.normalize(text, Normalizer.Form.NFKC);
    }

    private static List<String> splitLines(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String ln : text.split("\n", -1)) {
            out.add(ln);
        }
        return out;
    }
}
