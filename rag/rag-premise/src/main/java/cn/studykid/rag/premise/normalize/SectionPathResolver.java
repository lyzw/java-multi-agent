package cn.studykid.rag.premise.normalize;

import org.jspecify.annotations.NonNull;

import cn.studykid.rag.ports.LayoutBlock;

/**
 * 章节路径推进规则（RAG-D26 阶段 0.3 从 {@code IngestionPipeline} 搬迁；RAG-D28 P1-4 收敛）。
 *
 * <p><b>⚠️ 本类现在是 {@link SectionPathStack} 的门面，不再自带算法</b>：
 * P1-4 之前这里用「按 level-1 截断」的简单算法，与两个 mapper 的 level 值栈
 * 算法漂移——<b>分隔符</b>（{@code " > "} vs {@code " / "}）与<b>非连续层级</b>
 * 都不一致。更糟的是切分器会用本类重新推进，把 mapper 算对的路径覆盖掉。
 * 现在 mapper 与本类共用同一份 {@link SectionPathStack}，从结构上保证同口径；
 * 状态由调用方跨块持有 {@link SectionPathStack} 实例（不可变字符串不足以承载 level 栈）。
 *
 * <p><b>语义要点（RAG-D04 §5.3 / D27 §4.3）</b>：
 * 标题层级变化时<b>截断</b>路径而非追加——
 * 「第3章 &gt; 3.1 &gt; 3.1.1」之后遇到 3.2 必须回到
 * 「第3章 &gt; 3.2」，追加会产出错误路径，让学生在引用展示时找不到位置。
 */
public final class SectionPathResolver {

    private SectionPathResolver() {
    }

    /** 创建一份空栈（调用方跨块持有）。 */
    public static @NonNull SectionPathStack newStack() {
        return new SectionPathStack();
    }

    /**
     * 章节路径取值（RAG-D04 §5.2 / §5.3）。
     *
     * <p><b>⚠️ 两个来源，一个权威（与既有管道同规则）</b>：
     * 有标题上下文时用累积路径（规范机制，保证路径与本文档的标题块自洽）；
     * 没有标题上下文时回退到解析器给出的 {@code sectionPath}——
     * 此时它是唯一可用的版面事实，直接丢弃会让定位退化为空串、引用无法核对。
     *
     * @param stack 跨块累积的标题栈
     * @param block 版面块
     * @return 最终章节路径（非 null）
     */
    public static @NonNull String resolve(@NonNull SectionPathStack stack, LayoutBlock block) {
        String fromHeadings = stack.render();
        if (!fromHeadings.isBlank()) {
            return fromHeadings;
        }
        String fromParser = block.sectionPath();
        return fromParser == null ? "" : fromParser;
    }

    /**
     * 章节路径推进（委托 {@link SectionPathStack#advance}）。
     *
     * <p>⚠️ 层级变化时<b>截断</b>而非追加；完整血泪语义见 {@link SectionPathStack} 类注释。
     *
     * @param stack 跨块持有的标题栈（会被原地修改）
     * @param block 标题块
     */
    public static void advance(@NonNull SectionPathStack stack, LayoutBlock block) {
        stack.advance(block.pageNumber(), block.headingLevel(), block.text());
    }
}
