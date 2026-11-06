package cn.studykid.rag.premise.normalize;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.NonNull;

import cn.studykid.rag.ports.BlockType;
import cn.studykid.rag.ports.LayoutBlock;
import cn.studykid.rag.premise.model.PremiseBlock;

/**
 * 跨后端归一化规则（RAG-D26 §1.3 normalize/）。
 *
 * <p><b>⚠️ 本类的职责边界</b>：把不同解析后端的系统性差异拉平到同一条基线上，
 * 让切分器看到的输入是<b>同构</b>的。它只做「整形」，不做「教学语义判定」——
 * 角色判定在 {@code role/BlockRoleResolver}，噪声丢弃决策在切分器
 * （保持与既有管道一致的丢弃位置与口径）。
 *
 * <p>四条规则与来源：
 * <ol>
 *   <li><b>页码基准</b>（{@link #shiftPageNumbers}）：MinerU 的 {@code page_idx}
 *       从 0 起（CLI 页范围却是 1 起，官方文档明示的差异），
 *       而 DDL 约束 {@code rag_chunk.page_number >= 1}——0 起页码透传会让
 *       整册落库失败，且「第 0 页」的引用无法核对。0 基后端必须在适配器内 +1。</li>
 *   <li><b>阅读顺序</b>（{@link #ensureReadingOrder}）：块序列必须按
 *       （页码，原顺序）稳定排序——稳定排序保证相同键时原相对次序不变，
 *       不破坏后端已恢复的阅读顺序。</li>
 *   <li><b>字符区间</b>（{@link #renumberBrokenCharSpans}）：
 *       「可回取原文」门禁证据（D09 G1 ③）要求 {@code charStart/charEnd}
 *       能定位 PDF 原文。不产出字符区间的后端（如 MinerU MiddleJson 直接消费）
 *       需要按页连续化回填；<b>已有合法区间的页不动</b>——
 *       重新编号会破坏与既有语料的 chunk_id 兼容（id 由 charStart 派生）。</li>
 *   <li><b>重复行噪声</b>（{@link #markRepeatedLineNoise}）：不标注页眉页脚的后端，
 *       用离线脚本（{@code scripts/rag_import_pdf.py} 的 {@code strip_boilerplate}）
 *       同款统计特征兜底：短行跨 {@code max(3, 页数/3)} 个不同页出现 → 整块判 BOILERPLATE。
 *       阈值常数与脚本同源（已有 {@code CleaningRules} 注释记录）。</li>
 * </ol>
 *
 * <p><b>⚠️ 全部规则是纯函数</b>：输入不可变输出，确定性可单测；
 * 不在这里调用任何后端或数据库。
 */
public final class NormalizationRules {

    private NormalizationRules() {
    }

    /** 重复行被判为页眉页脚的最短出现页数（与脚本 {@code max(3, pages//3)} 对应）。 */
    public static final int REPEATED_LINE_MIN_PAGES = 3;

    /** 参与重复行统计的行长度上限（脚本同值；超长行不参与，避免正文段落误判）。 */
    public static final int REPEATED_LINE_MAX_LEN = 40;

    /**
     * 页码基准换算：把 0 基页码整体 +1（MinerU 等后端的适配器在映射时调用）。
     *
     * <p>⚠️ 换算发生在<b>归一化</b>而不是切分——切分器与角色判定看到的
     * 页码必须是最终口径，否则 chunk_id（含页码）会在两处实现间漂移。
     *
     * @param blocks 归一化块
     * @return 页码全部 +1 的新序列（原列表不动）
     */
    public static @NonNull List<PremiseBlock> shiftPageNumbers(@NonNull List<PremiseBlock> blocks) {
        List<PremiseBlock> out = new ArrayList<>(blocks.size());
        for (PremiseBlock b : blocks) {
            out.add(withPage(b, b.pageNumber() + 1));
        }
        return out;
    }

    /**
     * 阅读顺序稳定排序：按（页码升序，原相对次序）排列。
     *
     * <p>⚠️ 用<b>稳定</b>排序：相同页码内保持输入次序，
     * 不破坏后端已恢复的阅读顺序（多栏/跨栏恢复是后端的职责，这里只兜底跨页乱序）。
     *
     * @param blocks 归一化块
     * @return 排序后的新序列
     */
    public static @NonNull List<PremiseBlock> ensureReadingOrder(@NonNull List<PremiseBlock> blocks) {
        List<PremiseBlock> copy = new ArrayList<>(blocks);
        copy.sort(java.util.Comparator.comparingInt(PremiseBlock::pageNumber));
        return copy;
    }

    /**
     * 字符区间修复：仅对「存在非法区间」的页整体重新连续化。
     *
     * <p>非法 = {@code charStart < 0} 或 {@code charEnd < charStart}（有非空文本时）。
     * 修复语义与既有 Docling 分段合并一致：按页内阅读顺序累加，
     * {@code charEnd = charStart + text.length()}，页内游标清零重计。
     *
     * <p>⚠️ 合法页<b>绝不重编</b>：chunk_id 由 {@code (documentId, 页码, charStart)}
     * 派生，重编合法页等于批量换 id，破坏 upsert 去重。
     *
     * @param blocks 归一化块
     * @return 修复后的新序列（无非法区间时返回等价内容的新列表）
     */
    public static @NonNull List<PremiseBlock> renumberBrokenCharSpans(
            @NonNull List<PremiseBlock> blocks) {
        // ① 找出含非法区间的页
        java.util.Set<Integer> brokenPages = new java.util.HashSet<>();
        for (PremiseBlock b : blocks) {
            if (isBrokenSpan(b)) {
                brokenPages.add(b.pageNumber());
            }
        }
        if (brokenPages.isEmpty()) {
            return new ArrayList<>(blocks);
        }
        // ② 只重编这些页；游标按页独立
        Map<Integer, Integer> cursors = new HashMap<>();
        List<PremiseBlock> out = new ArrayList<>(blocks.size());
        for (PremiseBlock b : blocks) {
            if (brokenPages.contains(b.pageNumber())) {
                int cursor = cursors.getOrDefault(b.pageNumber(), 0);
                out.add(rebuildWithSpan(b, cursor, cursor + b.text().length()));
                cursors.put(b.pageNumber(), cursor + b.text().length());
            } else {
                out.add(b);
            }
        }
        return out;
    }

    /**
     * 重复行噪声标记：整块文本是「跨多页重复的短行」时改判 BOILERPLATE。
     *
     * <p>⚠️ 判定对象是<b>整块</b>（trim 后完全等于重复行），不做行内替换——
     * 块级噪声标记后由切分器按既有口径丢弃（与 BOILERPLATE 类型块同路径），
     * 归一化层不直接删块（删块会让「丢弃计数」失真）。
     *
     * @param blocks 归一化块
     * @return 标记后的新序列
     */
    public static @NonNull List<PremiseBlock> markRepeatedLineNoise(
            @NonNull List<PremiseBlock> blocks) {
        // ① 统计每个「短行文本」出现的不同页数（保持首个出现次序，便于确定性）
        Map<String, java.util.Set<Integer>> pageHits = new LinkedHashMap<>();
        for (PremiseBlock b : blocks) {
            String line = b.text().trim();
            if (line.isEmpty() || line.length() > REPEATED_LINE_MAX_LEN) {
                continue;
            }
            pageHits.computeIfAbsent(line, k -> new java.util.HashSet<>()).add(b.pageNumber());
        }
        // ② 阈值 = max(3, 不同页数/3)——与脚本的 pages//3 同源（pages 用块覆盖的页数近似）
        java.util.Set<Integer> distinctPages = new java.util.HashSet<>();
        for (PremiseBlock b : blocks) {
            distinctPages.add(b.pageNumber());
        }
        int threshold = Math.max(REPEATED_LINE_MIN_PAGES, distinctPages.size() / 3);
        // ③ 改判
        List<PremiseBlock> out = new ArrayList<>(blocks.size());
        for (PremiseBlock b : blocks) {
            String line = b.text().trim();
            java.util.Set<Integer> hits = line.isEmpty() ? null : pageHits.get(line);
            if (b.type() != BlockType.BOILERPLATE
                    && hits != null
                    && hits.size() >= threshold) {
                out.add(withType(b, BlockType.BOILERPLATE));
            } else {
                out.add(b);
            }
        }
        return out;
    }

    // ---------- 内部工具（重建不可变块） ----------

    private static boolean isBrokenSpan(PremiseBlock b) {
        return b.charStart() < 0
                || (b.charEnd() < b.charStart());
    }

    private static PremiseBlock withPage(PremiseBlock b, int newPage) {
        LayoutBlock raw = b.toLayoutBlock();
        LayoutBlock moved = new LayoutBlock(raw.type(), raw.text(), newPage, raw.headingLevel(),
                raw.sectionPath(), raw.exerciseNo(), raw.inAnswerZone(),
                raw.charStart(), raw.charEnd());
        return new PremiseBlock(moved, b.pageBbox(), b.confidence());
    }

    private static PremiseBlock withType(PremiseBlock b, BlockType newType) {
        LayoutBlock raw = b.toLayoutBlock();
        LayoutBlock tagged = new LayoutBlock(newType, raw.text(), raw.pageNumber(),
                raw.headingLevel(), raw.sectionPath(), raw.exerciseNo(), raw.inAnswerZone(),
                raw.charStart(), raw.charEnd());
        return new PremiseBlock(tagged, b.pageBbox(), b.confidence());
    }

    private static PremiseBlock rebuildWithSpan(PremiseBlock b, int charStart, int charEnd) {
        LayoutBlock raw = b.toLayoutBlock();
        LayoutBlock respanned = new LayoutBlock(raw.type(), raw.text(), raw.pageNumber(),
                raw.headingLevel(), raw.sectionPath(), raw.exerciseNo(), raw.inAnswerZone(),
                charStart, charEnd);
        return new PremiseBlock(respanned, b.pageBbox(), b.confidence());
    }
}
