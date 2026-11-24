package cn.studykid.rag.premise.normalize;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.NonNull;

import cn.studykid.rag.core.ingest.rules.AppendixRules;
import cn.studykid.rag.core.ingest.rules.TocRules;
import cn.studykid.rag.ports.BlockType;
import cn.studykid.rag.ports.LayoutBlock;
import cn.studykid.rag.premise.model.PremiseBlock;

/**
 * 附录逻辑单位接线器（RAG-D28 P2-4）——把 rag-core 已实现但<b>运行期不可达</b>的
 * {@link AppendixRules}（A-1/A-2/A-3）接入 premise 切分链。
 *
 * <p><b>⚠️ 为什么要单独接线</b>：{@code AppendixRules} 此前只被
 * {@code IngestRulesParityTest} 引用，生产链从未调用——「有类、有测、有规则」但
 * 不可达（项目铁律 #15）。本类让它在切分前<b>真实生效</b>：附录词表页按 Unit 重切、
 * 续页继承当前 Unit、片段补中英双形标题，使「第五单元单词」这类中文问法可召回。
 *
 * <p><b>⚠️ 附录段判据（不臆造信号）</b>：以版面给出的 {@code sectionPath} 含「附录」
 * 作为进入附录段的信号（与 {@link TocRules#sectionOf} 的附录段语义一致——附录起始页
 * 之后一律返回「附录」）。一旦进入即跨页锁存（附录位于书末，不回退正文），
 * 续页即使标题缺失也继续按 Unit 重切。<b>版面未标记附录的页原样透传</b>——
 * 宁可漏切，不可把正文页误判成词表页（{@code appendixPieces} 对任何页都返回一片，
 * 不能用"返回非空"当判据）。
 *
 * <p><b>⚠️ 合成块的路径如何生效</b>：每个 Piece 转成一个 {@code HEADING(level=1)}
 * + 一个 {@code PARAGRAPH}。切分器主循环对 level=1 标题会截断所有更深槽，
 * 于是段落的 resolvedPath 恰为 Piece 的逻辑单位路径（如「Unit 5 ... 词表」），
 * 不会被此前的正文标题栈覆盖。
 *
 * <p><b>⚠️ char 区间页内唯一</b>：同一页多个 Piece 若共用 charStart，派生出的
 * chunk_id 会碰撞并互相覆盖（upsert 语义）。本类按 Piece 在页内的累计字符偏移
 * 分配递进起点，保证同页 id 唯一；区间是<b>逻辑近似</b>（合成块非原文逐字切片），
 * 如实登记，不宣称精确回取。
 */
public final class AppendixLogicalUnitResolver {

    /** 附录段信号：版面章节路径含此子串。 */
    private static final String APPENDIX_MARK = "附录";

    private final AppendixRules.State state = new AppendixRules.State();
    private final List<TocRules.TocUnit> units;
    private boolean inAppendixSection;

    /**
     * @param units 目录单元列表（供 {@code AppendixRules} 取单元标题；
     *              空列表时词表路径退化为「Unit N 词表」，中英前缀仍生效）
     */
    public AppendixLogicalUnitResolver(@NonNull List<TocRules.TocUnit> units) {
        this.units = units == null ? List.of() : List.copyOf(units);
    }

    /** 无目录单元的构造（前缀仍可用，标题留空）。 */
    public AppendixLogicalUnitResolver() {
        this(List.of());
    }

    /**
     * 把附录段的块重写为按 Unit 的逻辑单位块；非附录块原样透传。
     *
     * <p>本方法可跨页多次调用，{@link AppendixRules.State} 与 {@link #inAppendixSection}
     * 持续持有——这正是 A-2 续页继承所需的跨页状态。换册时请新建实例（或调 {@link #reset()}）。
     *
     * @param blocks 归一化后的块序列（顺序即阅读顺序）
     * @return 重写后的块序列
     */
    public @NonNull List<PremiseBlock> resolve(@NonNull List<PremiseBlock> blocks) {
        Map<Integer, List<PremiseBlock>> byPage = new LinkedHashMap<>();
        for (PremiseBlock block : blocks) {
            byPage.computeIfAbsent(block.pageNumber(), k -> new ArrayList<>()).add(block);
        }
        List<PremiseBlock> out = new ArrayList<>();
        for (Map.Entry<Integer, List<PremiseBlock>> page : byPage.entrySet()) {
            List<PremiseBlock> pageBlocks = page.getValue();
            boolean marked = pageBlocks.stream().anyMatch(b -> b.sectionPath().contains(APPENDIX_MARK));
            if (marked) {
                inAppendixSection = true;
            }
            if (!inAppendixSection) {
                out.addAll(pageBlocks);
                continue;
            }
            String flat = flatten(pageBlocks);
            List<AppendixRules.Piece> pieces = AppendixRules.appendixPieces(flat, units, state);
            if (pieces.isEmpty()) {
                // 空页：原样透传，不静默丢内容。
                out.addAll(pageBlocks);
                continue;
            }
            int baseStart = pageBlocks.get(0).charStart();
            int cursor = baseStart;
            for (AppendixRules.Piece piece : pieces) {
                int pageNo = page.getKey();
                LayoutBlock heading = new LayoutBlock(BlockType.HEADING, piece.sectionPath(),
                        pageNo, 1, piece.sectionPath(), null, false, cursor, cursor);
                int end = cursor + piece.text().length();
                LayoutBlock body = new LayoutBlock(BlockType.PARAGRAPH, piece.text(),
                        pageNo, 0, piece.sectionPath(), null, false, cursor, end);
                out.add(PremiseBlock.fromLayoutBlock(heading));
                out.add(PremiseBlock.fromLayoutBlock(body));
                cursor = end + 1;
            }
        }
        return List.copyOf(out);
    }

    /** 重置跨页状态（换册时调用，避免串状态）。 */
    public void reset() {
        state.reset();
        inAppendixSection = false;
    }

    /** @return 是否已进入附录段（供诊断/测试）。 */
    public boolean inAppendixSection() {
        return inAppendixSection;
    }

    private static String flatten(List<PremiseBlock> pageBlocks) {
        StringBuilder sb = new StringBuilder();
        for (PremiseBlock block : pageBlocks) {
            if (block.type().isNoise()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(block.text());
        }
        return sb.toString().strip();
    }
}
