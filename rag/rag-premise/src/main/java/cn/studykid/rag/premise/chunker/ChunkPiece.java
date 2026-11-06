package cn.studykid.rag.premise.chunker;

import org.jspecify.annotations.NonNull;

/**
 * 拆分片（RAG-D26 阶段 2.1）。
 *
 * <p><b>⚠️ 顶层类型而非 {@code HeadingHierarchyChunker} 的嵌套 record</b>：
 * premise 层执行与 ports 层相同的「类型顶层化」规则
 * （{@code PremiseTopLevelTypeGuardTest} / RAG-D09 §6——JDK 21 javac
 * 「外层类型与嵌套类型同时导入」的符号解析缺陷规避）。
 * 本类型只服务切分器的滑窗拆分，故放在同一包内。
 *
 * @param text 片文本（原文截取，未 trim）
 * @param start 块内相对起始偏移（含）
 * @param end   块内相对结束偏移（不含）
 */
record ChunkPiece(@NonNull String text, int start, int end) {
}
