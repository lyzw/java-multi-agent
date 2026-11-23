package cn.studykid.rag.infrastructure.ingest;

import java.nio.charset.StandardCharsets;

import org.jspecify.annotations.NonNull;

import cn.studykid.rag.management.ports.ObjectStorePort;
import cn.studykid.rag.premise.spi.PremiseArtifactSink;

/**
 * P1-3（RAG-D28）：{@link PremiseArtifactSink} 的落桶适配器——包装既有
 * {@link ObjectStorePort}，把 IR 三件套（Markdown / JSON / 原页缓存）落到与语料同一载体。
 *
 * <p><b>⚠️ 依赖方向</b>：persistence → premise（SPI 定义在 premise，实现在此），
 * 不反向污染 premise 的零框架依赖。
 *
 * <p><b>⚠️ 逻辑键格式</b>：{@code premise/<documentId>/<kind>.<ext>}——
 * 与语料对象键空间隔离，避免与用户上传的 objectRef 撞键。
 * documentId 经 {@link #safeSegment} 归一（拒绝路径分隔符），防路径穿越。
 *
 * <p><b>⚠️ fail-closed</b>：底层 {@link ObjectStorePort#store} 失败会抛异常，本类不吞——
 * 落盘失败即解析产物不可用，不能让「可回取原文」静默失效。
 */
public class ObjectStorePremiseArtifactSink implements PremiseArtifactSink {

    private final ObjectStorePort store;

    public ObjectStorePremiseArtifactSink(@NonNull ObjectStorePort store) {
        if (store == null) {
            throw new IllegalArgumentException("ObjectStorePort 不能为空");
        }
        this.store = store;
    }

    @Override
    public @NonNull String storeMarkdown(@NonNull String documentId, @NonNull String markdown) {
        requireText(markdown, "markdown");
        return store.store(key(documentId, "markdown", "md"),
                markdown.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public @NonNull String storeJson(@NonNull String documentId, @NonNull String json) {
        requireText(json, "json");
        return store.store(key(documentId, "ir", "json"),
                json.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public @NonNull String storePageCache(@NonNull String documentId, byte @NonNull [] original) {
        if (original == null || original.length == 0) {
            throw new IllegalArgumentException("原页缓存字节不能为空");
        }
        return store.store(key(documentId, "page-cache", "bin"), original);
    }

    @Override
    public boolean isAvailable() {
        return store.isAvailable();
    }

    private static String key(String documentId, String kind, String ext) {
        return "premise/" + safeSegment(documentId) + "/" + kind + "." + ext;
    }

    /** documentId 作为单段键：拒绝路径分隔符与空段，防逃出 premise/ 命名空间。 */
    private static String safeSegment(String documentId) {
        if (documentId == null || documentId.isBlank()
                || documentId.contains("/") || documentId.contains("\\")
                || documentId.contains("..")) {
            throw new IllegalArgumentException("documentId 不能含路径分隔符或 '..'：" + documentId);
        }
        return documentId;
    }

    private static void requireText(String value, String what) {
        if (value == null) {
            throw new IllegalArgumentException(what + " 不能为 null（fail-closed）");
        }
    }
}
