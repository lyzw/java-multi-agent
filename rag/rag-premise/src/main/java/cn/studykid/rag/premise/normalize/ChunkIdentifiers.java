package cn.studykid.rag.premise.normalize;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.jspecify.annotations.NonNull;

/**
 * 片段标识与内容指纹（RAG-D26 阶段 0.3：从 {@code IngestionPipeline} 原样搬迁）。
 *
 * <p><b>⚠️ 派生格式不得改动</b>：
 * <ul>
 *   <li>{@link #chunkId} 的定位键是 {@code chunk:documentId:页码:字符起点}，
 *       经 type-3 确定性 UUID 派生——<b>同一位置重复摄取得到同一个 id</b>，
 *       upsert / 去重语义依赖这一点（随机 UUID 会让重跑产生重复片段）；
 *       既有注释（2026-11-03 实测）记录了它存在的理由：
 *       {@code rag_chunk.chunk_id} 列是 uuid 类型，复合串直入库会整册失败。</li>
 *   <li>{@link #contentHash} 是片段文本的 SHA-256（小写十六进制），
 *       用于内容变更检测。</li>
 * </ul>
 * 两者的格式变更都等于「所有历史 chunk_id/指纹全部换算」，
 * 属于需要迁移方案的破坏性变更——因此这里固化格式并加测试锁定。
 */
public final class ChunkIdentifiers {

    private ChunkIdentifiers() {
    }

    /**
     * 由定位键派生确定性 chunk UUID（与既有管道逐字符等价的格式）。
     *
     * @param documentId 文档 ID
     * @param pageNumber 物理页码（1 起）
     * @param charStart  页内字符起始偏移
     * @return 确定性 UUID 串
     */
    public static @NonNull String chunkId(@NonNull String documentId,
                                          int pageNumber,
                                          int charStart) {
        return java.util.UUID.nameUUIDFromBytes(
                        ("chunk:" + documentId + ":" + pageNumber + ":"
                                + charStart).getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    /**
     * 片段文本的 SHA-256 指纹（小写十六进制，与既有管道等价）。
     *
     * @param text 片段文本
     * @return 64 位十六进制串
     */
    public static @NonNull String contentHash(@NonNull String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // ⚠️ SHA-256 是 JDK 必备算法；若不可用说明运行环境异常，不应静默降级。
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
