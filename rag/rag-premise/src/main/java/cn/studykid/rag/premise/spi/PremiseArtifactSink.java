package cn.studykid.rag.premise.spi;

import org.jspecify.annotations.NonNull;

/**
 * P1-3（RAG-D28）：中间产物落盘端口（Markdown / JSON / 原页缓存）。
 *
 * <p><b>⚠️ 为什么是独立 SPI</b>：rag-premise 零框架依赖（只依赖 core/ports），
 * 不能反向依赖 persistence 的 {@code ObjectStorePort}。本端口在 premise 侧定义
 * 「要落什么」，由 persistence/infrastructure 侧适配「怎么落」（本地目录 / MinIO / S3）。
 *
 * <p><b>⚠️ fail-closed 契约</b>：存储失败必须抛异常，⛔ 不得返回 null/空串——
 * 空引用会让「可回取原文」与「中间产物可审计」两条门禁静默失效。
 * 未配置落盘时，装配方应传「显式禁用」实现并给出 ERROR 说明（不是静默 no-op）。
 */
public interface PremiseArtifactSink {

    /**
     * 落盘 Markdown 中间产物，返回相对引用（不放绝对路径）。
     *
     * @param documentId 文档 ID（用于逻辑键）
     * @param markdown   Markdown 内容
     * @return 相对引用键/URI
     */
    @NonNull String storeMarkdown(@NonNull String documentId, @NonNull String markdown);

    /**
     * 落盘 JSON 中间产物（D27 §4.2 机读结构），返回相对引用。
     *
     * @param documentId 文档 ID
     * @param json       JSON 内容
     * @return 相对引用键/URI
     */
    @NonNull String storeJson(@NonNull String documentId, @NonNull String json);

    /**
     * 落盘原页缓存（原始 PDF 字节或页快照），返回相对引用。
     *
     * @param documentId 文档 ID
     * @param original   原始字节
     * @return 相对引用键/URI
     */
    @NonNull String storePageCache(@NonNull String documentId, byte @NonNull [] original);

    /** 落盘通道是否可用（未配置时上层据此 fail-closed 或显式禁用）。 */
    boolean isAvailable();
}
