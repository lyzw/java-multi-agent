package cn.studykid.rag.premise.ir;

import org.jspecify.annotations.NonNull;

import cn.studykid.rag.premise.model.PremiseDocument;
import cn.studykid.rag.premise.spi.PremiseArtifactSink;

/**
 * P1-3（RAG-D28）：IR 中间产物落盘门面——一步完成「渲染 → 落盘 → 回填引用」。
 *
 * <p><b>⚠️ 职责边界</b>：本类只做工序编排，存储细节交给 {@link PremiseArtifactSink}；
 * 上层摄取编排器（阶段 3 premise 管线接线点）直接调 {@link #writeAndAttach}，
 * 拿到带 markdownRef/pageCacheRef 的 {@link PremiseDocument} 后继续切分/落库。
 *
 * <p><b>⚠️ fail-closed</b>：sink 不可用或落盘抛错时不吞、不返回无引用的文档——
 * 「可回取原文」与「中间产物可审计」是 D09 G1 ③ 的门禁证据，静默跳过等于门禁失效。
 */
public final class PremiseArtifactWriter {

    private final PremiseIrRenderer renderer;
    private final PremiseArtifactSink sink;

    public PremiseArtifactWriter(@NonNull PremiseArtifactSink sink) {
        if (sink == null) {
            throw new IllegalArgumentException("PremiseArtifactSink 不能为空");
        }
        this.renderer = new PremiseIrRenderer();
        this.sink = sink;
    }

    /**
     * 渲染 IR、落盘三件套，并把引用回填到 {@link PremiseDocument}。
     *
     * @param document  归一化文档
     * @param original  原始 PDF 字节（用于原页缓存）
     * @return 带 markdownRef/pageCacheRef 的新文档（其余字段逐字段带过）
     */
    public @NonNull PremiseDocument writeAndAttach(@NonNull PremiseDocument document,
                                                    byte @NonNull [] original) {
        if (document == null || original == null) {
            throw new IllegalArgumentException("document 与 original 不能为空");
        }
        if (!sink.isAvailable()) {
            throw new IllegalStateException(
                    "IR 落盘通道不可用：中间产物与可回取原文门禁无法满足（fail-closed）");
        }
        IntermediateRepresentation ir = renderer.render(document);
        // ⚠️ JSON 中间产物落盘（机读，含 bbox/char 区间/阅读顺序），引用写入 trace 告警位之外的
        //    markdownRef；pageCacheRef 承载原页缓存。JSON 引用与 Markdown 共用 mdRef 的目录前缀，
        //    由 sink 内部的逻辑键区分（markdown.md / ir.json）。
        String mdRef = sink.storeMarkdown(document.documentId(), ir.markdown());
        sink.storeJson(document.documentId(), ir.json());
        String cacheRef = sink.storePageCache(document.documentId(), original);
        return document.withArtifacts(mdRef, cacheRef);
    }

    /** @return 渲染器（供上层做「可回取原文」自检时使用） */
    public @NonNull PremiseIrRenderer renderer() {
        return renderer;
    }
}
