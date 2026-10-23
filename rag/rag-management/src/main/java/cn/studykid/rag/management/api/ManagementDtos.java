package cn.studykid.rag.management.api;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import cn.studykid.rag.core.source.SourceVerification.VerificationMethod;
import cn.studykid.rag.core.source.SourceVerification.VerificationStatus;
import cn.studykid.rag.core.source.TrustTier;

/**
 * 管理端点的请求/响应契约。
 *
 * <p><b>⚠️ 为什么管理面独立于检索面</b>：
 * 检索面（{@code /api/v1/retrieval}）是 fail-closed 的自动闸门，
 * 管理面是带留痕与双人复核的<b>人工闸门</b>（RAG-D07 §2.2）。
 * 二者的输入契约不同——检索面不需要租户管理员上下文，
 * 管理面必须知道「谁在操作」才能写审计。
 */
public final class ManagementDtos {

    private ManagementDtos() {
    }

    /**
     * 来源登记请求。
     *
     * @param sourceType 来源类型
     * @param title 书名/标题
     * @param publisher 出版社/出版方
     * @param isbn ISBN
     * @param doi DOI
     * @param objectRef 正文在对象存储中的引用
     * @param contentHash 整册内容校验和
     * @param registeredBy 登记人 UUID（审计留痕，RAG-D07 §2.5）
     */
    public record RegisterSourceRequest(
            @NotBlank(message = "sourceType 不能为空")
            @Size(max = 64, message = "sourceType 长度不能超过 64")
            String sourceType,

            @NotBlank(message = "title 不能为空")
            @Size(max = 512, message = "title 长度不能超过 512")
            String title,

            @NotBlank(message = "publisher 不能为空")
            @Size(max = 256, message = "publisher 长度不能超过 256")
            String publisher,

            @Size(max = 64, message = "isbn 长度不能超过 64")
            String isbn,

            @Size(max = 128, message = "doi 长度不能超过 128")
            String doi,

            @Size(max = 1024, message = "objectRef 长度不能超过 1024")
            String objectRef,

            @Pattern(regexp = "^[0-9a-fA-F]{64}$", message = "contentHash 必须是 64 位十六进制 SHA-256")
            String contentHash,

            @NotBlank(message = "registeredBy 不能为空（登记必须留痕）")
            @Pattern(regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
                    message = "registeredBy 必须是合法 UUID")
            String registeredBy
    ) {
    }

    /**
     * 校验决定请求。
     *
     * @param targetStatus 目标状态
     * @param evidenceRef 校验依据引用
     * @param decidedBy 决定人 UUID
     * @param verificationMethod 校验手段
     * @param trustTier 判定后的权威等级（VERIFIED 时必填）
     * @param validDays 有效天数
     */
    public record VerificationRequest(
            @NotNull(message = "targetStatus 不能为空")
            VerificationStatus targetStatus,

            @NotBlank(message = "evidenceRef 不能为空"
                    + "（RAG-D07 §2.5：每次转移必须有证据留痕）")
            @Size(max = 1024, message = "evidenceRef 长度不能超过 1024")
            String evidenceRef,

            @NotBlank(message = "decidedBy 不能为空")
            @Pattern(regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
                    message = "decidedBy 必须是合法 UUID")
            String decidedBy,

            @NotNull(message = "verificationMethod 不能为空")
            VerificationMethod verificationMethod,

            TrustTier trustTier,

            @MinValidDays
            Integer validDays
    ) {
    }

    /**
     * 来源视图（响应）。
     *
     * @param sourceId 来源 ID
     * @param sourceType 来源类型
     * @param title 标题
     * @param publisher 出版方
     * @param isbn ISBN
     * @param doi DOI
     * @param trustTier 权威等级
     * @param verificationStatus 校验状态
     * @param verificationMethod 校验手段
     * @param verificationEvidence 证据引用
     * @param verifiedAt 决定时间
     * @param verifiedBy 决定人
     * @param validDays 有效天数
     * @param licenseStatus 版权状态
     * @param stale 是否已过期（计算属性）
     * @param admissible 是否当前准入可检索
     * @param objectRef 对象存储引用
     * @param contentHash 内容校验和
     * @param createdAt 创建时间
     * @param updatedAt 更新时间
     */
    public record SourceResponse(
            String sourceId,
            String sourceType,
            String title,
            String publisher,
            String isbn,
            String doi,
            String trustTier,
            String verificationStatus,
            String verificationMethod,
            String verificationEvidence,
            String verifiedAt,
            String verifiedBy,
            int validDays,
            String licenseStatus,
            boolean stale,
            boolean admissible,
            String objectRef,
            String contentHash,
            String createdAt,
            String updatedAt
    ) {
    }

    /**
     * 文档视图（响应）。
     *
     * @param documentId 文档 ID
     * @param sourceId 来源 ID
     * @param editionId 版本坐标 ID
     * @param carrier 载体类型
     * @param title 标题
     * @param pageCount 页数
     * @param objectRef 对象存储引用
     * @param parseStatus 解析状态
     * @param parserVersion 解析器版本
     * @param contentHash 校验和
     * @param chunkCount 片段数
     * @param createdAt 创建时间
     * @param updatedAt 更新时间
     */
    public record DocumentResponse(
            String documentId,
            String sourceId,
            String editionId,
            String carrier,
            String title,
            Integer pageCount,
            String objectRef,
            String parseStatus,
            String parserVersion,
            String contentHash,
            long chunkCount,
            String createdAt,
            String updatedAt
    ) {
    }

    /**
     * 片段摘要视图（响应）。
     *
     * @param chunkId 片段 ID
     * @param role 角色
     * @param pageNumber 页码
     * @param sectionPath 章节路径
     * @param exerciseNo 题号
     * @param charLength 字符数
     * @param hasEmbedding 是否已写入向量
     * @param contentHash 校验和
     */
    public record ChunkResponse(
            String chunkId,
            String role,
            int pageNumber,
            String sectionPath,
            String exerciseNo,
            int charLength,
            boolean hasEmbedding,
            String contentHash
    ) {
    }

    /**
     * 统计视图（响应）。
     *
     * @param totalSources 来源总数
     * @param verifiedSources 已校验来源数
     * @param admissibleSources 准入来源数
     * @param totalDocuments 文档总数
     * @param totalChunks 片段总数
     * @param retrievableChunks 可检索片段数
     * @param chunksMissingEmbedding 缺向量片段数
     * @param missingEmbeddingRatio 缺向量比例
     * @param chunksByRole 按角色分组
     * @param sourcesByVerificationStatus 按校验状态分组
     * @param documentsByParseStatus 按解析状态分组
     */
    public record StatisticsResponse(
            long totalSources,
            long verifiedSources,
            long admissibleSources,
            long totalDocuments,
            long totalChunks,
            long retrievableChunks,
            long chunksMissingEmbedding,
            double missingEmbeddingRatio,
            java.util.Map<String, Long> chunksByRole,
            java.util.Map<String, Long> sourcesByVerificationStatus,
            java.util.Map<String, Long> documentsByParseStatus
    ) {
    }

    /**
     * 有效天数下限约束注解。
     *
     * <p>⚠️ 单独定义是因为 {@code @Min} 允许 0，
     * 而 {@code ver_valid_days} 的 DB 约束是 {@code > 0}。
     */
    @jakarta.validation.constraints.Min(value = 1, message = "validDays 必须 >= 1")
    @jakarta.validation.constraints.Max(value = 3650, message = "validDays 不能超过 3650")
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target({java.lang.annotation.ElementType.FIELD,
            java.lang.annotation.ElementType.PARAMETER})
    public @interface MinValidDays {
    }

    /**
     * 操作结果响应（写入类操作）。
     *
     * @param success 是否成功
     * @param message 结果说明
     * @param sourceId 关联来源 ID
     */
    public record OperationResponse(
            boolean success,
            String message,
            String sourceId
    ) {
    }

    /**
     * 把领域来源转换为响应视图。
     *
     * <p>⚠️ {@code stale} 与 {@code admissible} 是<b>计算属性</b>，
     * 由服务端按当前时刻计算后返回，而不是让前端自己判断——
     * 前端没有租户门槛上下文，算不出准入结论。
     *
     * @param source 领域来源
     * @param minTrustTier 租户最低权威门槛
     * @param now 判定基准时刻
     * @return 响应视图
     */
    public static SourceResponse toResponse(cn.studykid.rag.management.core.CorpusSource source,
                                            TrustTier minTrustTier,
                                            java.time.Instant now) {
        return new SourceResponse(
                source.sourceId(), source.sourceType(), source.title(), source.publisher(),
                source.isbn(), source.doi(), source.trustTier().name(),
                source.verificationStatus().name(), source.verificationMethod().name(),
                source.verificationEvidence(),
                source.verifiedAt() == null ? null : source.verifiedAt().toString(),
                source.verifiedBy(), source.validDays(), source.licenseStatus(),
                source.stale(now), source.admissible(minTrustTier, now),
                source.objectRef(), source.contentHash(),
                source.createdAt().toString(), source.updatedAt().toString());
    }

    /**
     * @param documents 领域文档列表
     * @return 响应列表
     */
    public static List<DocumentResponse> toDocumentResponses(
            List<cn.studykid.rag.management.core.CorpusDocument> documents) {
        return documents.stream()
                .map(d -> new DocumentResponse(
                        d.documentId(), d.sourceId(), d.editionId(), d.carrier(), d.title(),
                        d.pageCount(), d.objectRef(), d.parseStatus(), d.parserVersion(),
                        d.contentHash(), d.chunkCount(),
                        d.createdAt().toString(), d.updatedAt().toString()))
                .toList();
    }

    /**
     * @param chunks 片段摘要列表
     * @return 响应列表
     */
    public static List<ChunkResponse> toChunkResponses(
            List<cn.studykid.rag.management.ports.CorpusManagementPort.ChunkSummary> chunks) {
        return chunks.stream()
                .map(c -> new ChunkResponse(
                        c.chunkId(), c.role(), c.pageNumber(), c.sectionPath(), c.exerciseNo(),
                        c.charLength(), c.hasEmbedding(), c.contentHash()))
                .toList();
    }
}
