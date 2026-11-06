package cn.studykid.rag.premise.model;

import java.util.List;

import org.jspecify.annotations.NonNull;

/**
 * 解析工序留痕（RAG-D26 §1.3 / §2.5）。
 *
 * <p><b>⚠️ 为什么解析留痕是一等类型而不是日志</b>：
 * {@code parser_version} 决定「这批语料是用哪版解析器切的」，换版后需要重灌，
 * 而重灌的依据就是这个字段（既有 {@code DoclingDocumentParserPort.PARSER_VERSION}
 * 的注释同源）。后端可切换后，仅一个版本号不够——还需要
 * <b>后端名 + 后端版本 + 分段方式 + 告警</b>才能完整解释一次解析。
 *
 * <p><b>与 DDL 的对应</b>：{@code backendId + backendVersion} 共同构成
 * {@code rag_document.parser_version} 的语义（写入格式由装配方决定，
 * 本类型只负责承载，不做字符串拼装）。
 *
 * <p>⚠️ 顶层类型（不得嵌套），规则来源见 {@code NestedTypeImportGuardTest}。
 *
 * @param backendId      后端标识（如 {@code docling-serve} / {@code mineru} / {@code pp-structurev3}；
 *                       兼容路径为 {@code untracked}）
 * @param backendVersion 后端版本（未知为空串，<b>不得</b>为 null）
 * @param pageCount      解析出的物理页数（未知为 0）
 * @param segmented      是否启用分段提交（大册分段是 Docling 路径的实测约束）
 * @param segments       实际分段数（不分段为 1）
 * @param warnings       归一化阶段产生的非致命告警（如「第 12 页缺失页码，按顺序回填」）
 */
public record ParseTrace(
        @NonNull String backendId,
        @NonNull String backendVersion,
        int pageCount,
        boolean segmented,
        int segments,
        @NonNull List<String> warnings) {

    public ParseTrace {
        if (backendId == null || backendId.isBlank()) {
            throw new IllegalArgumentException("backendId 不能为空");
        }
        backendVersion = backendVersion == null ? "" : backendVersion;
        if (pageCount < 0) {
            throw new IllegalArgumentException("pageCount 必须 >= 0");
        }
        if (segments < 1) {
            throw new IllegalArgumentException("segments 必须 >= 1");
        }
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /**
     * 兼容路径的留痕：解析仍走既有 {@code DocumentParserPort}，
     * 本模块只做包装、不掌握后端细节。
     *
     * @return 未跟踪留痕
     */
    public static @NonNull ParseTrace untracked() {
        return new ParseTrace("untracked", "", 0, false, 1, List.of());
    }

    /**
     * 追加告警（保持其余字段不变）。
     *
     * @param more 追加的告警
     * @return 新实例
     */
    public @NonNull ParseTrace withWarnings(@NonNull List<String> more) {
        if (more == null || more.isEmpty()) {
            return this;
        }
        var merged = new java.util.ArrayList<String>(warnings);
        merged.addAll(more);
        return new ParseTrace(backendId, backendVersion, pageCount, segmented, segments, merged);
    }
}
