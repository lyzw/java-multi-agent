package cn.studykid.rag.premise.chunker;

import java.util.List;

import org.jspecify.annotations.NonNull;

/**
 * 分块桥返回的单个 chunk（{@code HybridChunkerClient} 的中间表示，
 * RAG-D26 阶段 2.2）。
 *
 * <p><b>⚠️ 顶层类型而非客户端的嵌套 record</b>：
 * premise 层执行「类型顶层化」规则（{@code PremiseTopLevelTypeGuardTest} /
 * RAG-D09 §6——JDK 21 javac「外层类型与嵌套类型同时导入」的符号解析缺陷规避）。
 *
 * @param text      chunk 文本（merge_peers 时可能含标题上下文行）
 * @param headings  所属标题路径（桥从 chunk.meta.headings 取，层级序）
 * @param pages     覆盖页码（去重升序；来自覆盖 item 的 prov.page_no，1 基）
 * @param charStart 首个 char_span 起点（页内绝对偏移；无有效 span 为 -1）
 * @param charEnd   最远 char_span 终点（无有效 span 为 -1）
 * @param itemRefs  覆盖的 DoclingDocument item 引用（如 {@code #/texts/12}；
 *                  角色回填的锚点，实测覆盖率 100%）
 */
record ChunkBridgeChunk(
        @NonNull String text,
        @NonNull List<String> headings,
        @NonNull List<Integer> pages,
        int charStart,
        int charEnd,
        @NonNull List<String> itemRefs) {
}
