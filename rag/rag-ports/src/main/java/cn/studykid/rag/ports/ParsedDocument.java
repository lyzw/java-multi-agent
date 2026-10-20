package cn.studykid.rag.ports;

import java.util.List;

import org.jspecify.annotations.NonNull;

/**
 * 解析出的结构化文档。
 *
 * <p>⚠️ 顶层类型的原因见 {@link LayoutBlock} 的类注释
 * （JDK 21 javac 的「嵌套类型 + 外层类型同时导入」符号解析缺陷）。
 *
 * @param documentId 文档 ID
 * @param blocks 结构化块
 */
public record ParsedDocument(@NonNull String documentId, @NonNull List<LayoutBlock> blocks) {

    public ParsedDocument {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
    }
}
