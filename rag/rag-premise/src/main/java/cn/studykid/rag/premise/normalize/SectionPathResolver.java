package cn.studykid.rag.premise.normalize;

import org.jspecify.annotations.NonNull;

import cn.studykid.rag.ports.LayoutBlock;

/**
 * 章节路径推进规则（RAG-D26 阶段 0.3：从 {@code IngestionPipeline} 原样搬迁）。
 *
 * <p><b>⚠️ 本类是搬运不是重写</b>：两条方法的逻辑与既有管道<b>逐行等价</b>
 * （含「按层级截断而非追加」的关键语义），搬迁目的只有一个——
 * 让新切分器（{@code HeadingHierarchyChunker}）与既有管道共用同一份规则，
 * 消除「两处各写一套、改了一处忘了另一处」的漂移风险。
 * 等价性由既有 {@code IngestionPipelineTest} 的章节路径用例
 * + 新增的 {@code HeadingHierarchyChunkerParityTest} 共同守护。
 *
 * <p><b>语义要点（RAG-D04 §5.3）</b>：
 * 标题层级变化时<b>截断</b>路径而非追加——
 * 「第3章 &gt; 3.1 &gt; 3.1.1」之后遇到 3.2 必须回到
 * 「第3章 &gt; 3.2」，追加会产出错误路径，让学生在引用展示时找不到位置。
 */
public final class SectionPathResolver {

    private SectionPathResolver() {
    }

    /**
     * 章节路径取值（RAG-D04 §5.2 / §5.3）。
     *
     * <p><b>⚠️ 两个来源，一个权威（与既有管道同规则）</b>：
     * 有标题上下文时用累积路径（规范机制，保证路径与本文档的标题块自洽）；
     * 没有标题上下文时回退到解析器给出的 {@code sectionPath}——
     * 此时它是唯一可用的版面事实，直接丢弃会让定位退化为空串、引用无法核对。
     *
     * @param accumulated 流水线累积的标题路径
     * @param block       版面块
     * @return 最终章节路径（非 null）
     */
    public static @NonNull String resolve(StringBuilder accumulated, LayoutBlock block) {
        String fromHeadings = accumulated.toString();
        if (!fromHeadings.isBlank()) {
            return fromHeadings;
        }
        String fromParser = block.sectionPath();
        return fromParser == null ? "" : fromParser;
    }

    /**
     * 章节路径推进（与既有 {@code updateSectionPath} 逐行等价）。
     *
     * <p>⚠️ 标题层级变化时<b>截断</b>路径而非追加——
     * 否则路径会变成「第 3 章 &gt; 3.1 &gt; 3.1.1 &gt; 3.1.1.1」的累积错误。
     *
     * @param path  可变路径缓冲（由调用方跨块持有）
     * @param block 标题块
     */
    public static void advance(StringBuilder path, LayoutBlock block) {
        int level = Math.max(1, block.headingLevel());
        String[] parts = path.toString().split(" > ");
        int keep = Math.min(parts.length, level - 1);
        StringBuilder rebuilt = new StringBuilder();
        for (int i = 0; i < keep; i++) {
            if (!parts[i].isBlank()) {
                if (rebuilt.length() > 0) {
                    rebuilt.append(" > ");
                }
                rebuilt.append(parts[i]);
            }
        }
        if (rebuilt.length() > 0) {
            rebuilt.append(" > ");
        }
        rebuilt.append(block.text().trim());
        path.setLength(0);
        path.append(rebuilt);
    }
}
