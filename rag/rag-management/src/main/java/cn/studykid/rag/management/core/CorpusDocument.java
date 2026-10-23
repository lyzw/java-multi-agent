package cn.studykid.rag.management.core;

import java.time.Instant;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.rag.core.source.SourceVerification.VerificationStatus;

/**
 * 语料载体的管理视图（对应 {@code rag_document} 表行）。
 *
 * <p><b>⚠️ source / edition / document 三层不可压平</b>（RAG-D03 §4）：
 * {@code source} 是出版层面的「作品」，{@code edition} 是版本坐标，
 * {@code document} 是<b>物理载体</b>（一份 PDF / 一册扫描件）。
 * 同一 edition 可能有多份 document（官方电子版 + 第三方扫描版），
 * 而它们的解析质量与授权状态可能不同——压平就无法表达
 * 「同一版本下撤回其中一个载体而不影响整个版本」。
 *
 * <p><b>⚠️ 载体级状态字段此前一直是死字段</b>（V3 迁移已建列，但无人写入）：
 * {@code ver_status} / {@code retrievable} / {@code revoked_at} / {@code revoked_reason} /
 * {@code license_status}。没有它们，载体级下架只能靠物理 DELETE，
 * 而 DELETE 会级联抹除该载体下的历史引用（破坏可溯源性，RAG-D07 §5.5 / INV-4-a）。
 * 本模型把它们纳入读模型，使管理面能<b>呈现</b>载体级结论而不必让前端推算。
 *
 * @param documentId 文档 ID
 * @param sourceId 所属来源 ID
 * @param editionId 所属版本坐标 ID
 * @param carrier 载体类型（PDF / SCAN / EPUB / HTML / TXT）
 * @param title 文档标题
 * @param pageCount 页数
 * @param objectRef 正文在对象存储中的引用
 * @param parseStatus 解析状态（PENDING / PARSED / CHUNKED / FAILED）
 * @param parserVersion 解析器版本（可复现性）
 * @param contentHash 整册校验和（检测语料被静默替换）
 * @param chunkCount 已切分片段数（管理面统计值）
 * @param verificationStatus 载体级校验状态（不得高于来源级）
 * @param retrievable 载体级可检索标记（仅 VERIFIED 为真）
 * @param revokedAt 撤回时间（可空）
 * @param revokedReason 撤回依据（撤回时必填，DB CHECK 约束保证）
 * @param licenseStatus 载体级版权状态（独立于来源级，G-4）
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record CorpusDocument(
        @NonNull String documentId,
        @NonNull String sourceId,
        @NonNull String editionId,
        @NonNull String carrier,
        @NonNull String title,
        @Nullable Integer pageCount,
        @Nullable String objectRef,
        @NonNull String parseStatus,
        @Nullable String parserVersion,
        @Nullable String contentHash,
        long chunkCount,
        @NonNull VerificationStatus verificationStatus,
        boolean retrievable,
        @Nullable Instant revokedAt,
        @Nullable String revokedReason,
        @NonNull String licenseStatus,
        @NonNull Instant createdAt,
        @NonNull Instant updatedAt
) {

    public CorpusDocument {
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("documentId 不能为空");
        }
        if (sourceId == null || sourceId.isBlank()) {
            throw new IllegalArgumentException("sourceId 不能为空");
        }
        if (editionId == null || editionId.isBlank()) {
            throw new IllegalArgumentException("editionId 不能为空");
        }
        if (carrier == null || carrier.isBlank()) {
            throw new IllegalArgumentException("carrier 不能为空");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title 不能为空");
        }
        if (parseStatus == null || parseStatus.isBlank()) {
            throw new IllegalArgumentException("parseStatus 不能为空");
        }
        if (chunkCount < 0) {
            throw new IllegalArgumentException("chunkCount 不能为负");
        }
        if (verificationStatus == null) {
            throw new IllegalArgumentException("verificationStatus 不能为空");
        }
        if (licenseStatus == null || licenseStatus.isBlank()) {
            throw new IllegalArgumentException("licenseStatus 不能为空");
        }
        // ⚠️ 「已撤回」与「可检索」互斥：撤回必带依据（DB CHECK 亦强制），
        //    且撤回后载体绝不能仍被检索——否则撤回形同虚设。
        if (verificationStatus == VerificationStatus.REVOKED) {
            if (revokedReason == null || revokedReason.isBlank()) {
                throw new IllegalArgumentException("已撤回的载体必须带撤回依据"
                        + "（RAG-D07 §2.5：撤回必须留痕）");
            }
            if (retrievable) {
                throw new IllegalArgumentException("已撤回的载体不得标记为可检索");
            }
        }
    }

    /**
     * @return 是否已完成切分（{@code parse_status='CHUNKED'}）
     */
    public boolean chunked() {
        return "CHUNKED".equals(parseStatus);
    }
}
