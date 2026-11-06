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
import cn.studykid.rag.management.ports.DictionaryPort;

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
            String registeredBy,

            // ---- 版本五轴（RAG-D00 RD-03「缺一轴不可入库」）----
            //
            // ⚠️ 刻意全部可空：批量导入（CSV/ZIP）与既有调用方不走五轴，
            //    存量行为必须保持不变（回落到默认版本坐标）。
            //    **是否强制齐全由调用方决定**：管理台登记页在 UI 层强制五轴齐全，
            //    后端只在「部分填写」时报 400（见 SourceRegistration#partiallyFilledEdition），
            //    因为部分填写意味着用户以为登记了某版本、实际会退化成默认坐标。

            @Size(max = 32, message = "subject 长度不能超过 32")
            String subject,

            @Size(max = 32, message = "grade 长度不能超过 32")
            String grade,

            /**
             * 册次，取值白名单与 {@code rag_edition.term} 的 CHECK 同口径。
             */
            @Size(max = 16, message = "term 长度不能超过 16")
            String term,

            @Size(max = 64, message = "revisionCode 长度不能超过 64")
            String revisionCode,

            /**
             * 版本出版社。
             *
             * <p>⚠️ 与来源的 {@code publisher} 分开：来源出版社是「谁出版的」，
             * 版本出版社是「用的哪个版本坐标的教材」。二者通常相同，
             * 但同一出版社会有多个版本线。缺省回落到 {@code publisher}。
             */
            @Size(max = 256, message = "editionPublisher 长度不能超过 256")
            String editionPublisher,

            /** 登记时声明的权威等级（缺省 UNVERIFIED；登记态不允许自行提升，见服务端校验）。 */
            String trustTier,

            /** 登记时声明的版权状态（缺省 PENDING）。 */
            String licenseStatus
    ) {

        /**
         * 五轴之前的老签名（8 参数）便捷构造。
         *
         * <p>⚠️ 保留以兼容既有调用方与测试：五轴全空即走「延迟到载体上传时
         * 建默认版本坐标」的存量行为，与改造前逐字节一致。
         */
        public RegisterSourceRequest(String sourceType, String title, String publisher,
                                     String isbn, String doi, String objectRef,
                                     String contentHash, String registeredBy) {
            this(sourceType, title, publisher, isbn, doi, objectRef, contentHash, registeredBy,
                    null, null, null, null, null, null, null);
        }
    }

    /**
     * 登记请求的五轴说明。
     *
     * <p><b>⚠️ {@code trustTier} / {@code licenseStatus} 刻意被服务端忽略</b>
     * （RAG-D07 §2.2 角色表：采集员不得自行提升权威等级或置 VERIFIED）。
     * 保留这两个字段只为接口前向兼容——前端可以传，
     * 但登记落库恒为 {@code UNVERIFIED / PENDING}，由后续「校验决定」流程提升。
     * 若将来要支持「带初始状态的导入」，必须走独立的、有权限校验的端点，
     * 不能在登记上报口径上开口子。
     */

    /**
     * 批量导入的单条来源项（不含登记人：登记人对整批统一留痕）。
     *
     * <p>⚠️ 与 {@link RegisterSourceRequest} 同形但<b>不含</b> {@code registeredBy}：
     * 批量导入是「一个人导入一批」，登记人由批量请求头部的
     * {@link BulkRegisterRequest#registeredBy()} 统一提供，
     * 避免 CSV/JSON 每行都重复写一遍 UUID。
     *
     * @param sourceType 来源类型
     * @param title 书名/标题
     * @param publisher 出版社/出版方
     * @param isbn ISBN（与 doi 至少提供一个）
     * @param doi DOI（与 isbn 至少提供一个）
     * @param objectRef 正文在对象存储中的引用
     * @param contentHash 整册内容校验和（可空；若提供须为 64 位十六进制）
     */
    public record BulkSourceItem(
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
            String contentHash
    ) {
    }

    /**
     * 批量登记语料来源请求。
     *
     * <p>⚠️ <b>上限 100 条</b>：批量导入是人工操作，不应一次灌入海量数据；
     * 超出由控制器在 Bean Validation 之外显式拦截并返回 400，
     * 而不是让单次 HTTP 请求在循环里跑很久。
     *
     * @param registeredBy 登记人 UUID（对整批统一审计留痕）
     * @param sources 待登记来源列表（非空，≤ 100）
     */
    public record BulkRegisterRequest(
            @NotBlank(message = "registeredBy 不能为空（登记必须留痕）")
            @Pattern(regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
                    message = "registeredBy 必须是合法 UUID")
            String registeredBy,

            @NotNull(message = "sources 不能为空")
            @jakarta.validation.constraints.Size(
                    min = 1, max = 100,
                    message = "sources 至少 1 条、最多 100 条")
            // ⚠️ **刻意不加 @Valid**（Jakarta Bean Validation 规范：加了会级联校验元素）：
            //    批量端点的治理语义是「**逐条独立成败**」——一行不合法只影响该行，
            //    其余行照常入库（见控制器 javadoc 与端口契约）。
            //    若在此级联 @Valid，则任一行的 @NotBlank / @Pattern 失败会让
            //    **整批返回 400**，把「一行错」升级成「全批废」，
            //    与逐条成败的承诺直接冲突。
            //
            //    ⚠️ 元素级约束改由**领域层**兜住：
            //    {@link cn.studykid.rag.management.core.SourceRegistration}
            //    的构造期校验（标题/出版社非空、ISBN 与 DOI 至少其一、
            //    **contentHash 必须是 64 位十六进制**）——
            //    控制器逐条捕获该异常并归入该行的失败列表，
            //    既保住「逐条成败」语义，又不让非法值进库。
            //
            //    ⚠️ contentHash 尤其关键：rag_source.content_hash 是 CHAR(64)，
            //    短值会被 PG **静默空格填充**入库（不报错），
            //    而该列的用途是「检测语料被静默替换」——
            //    填充后的哈希失去校验能力。
            List<BulkSourceItem> sources
    ) {
    }

    /**
     * 批量登记单条结果（响应）。
     *
     * @param index 该行在请求数组中的下标
     * @param ok 是否成功
     * @param sourceId 成功时的来源 ID
     * @param error 失败时的原因
     */
    public record BulkItemResultDto(
            int index,
            boolean ok,
            String sourceId,
            String error
    ) {
    }

    /**
     * 批量登记响应。
     *
     * @param total 请求总条数
     * @param succeeded 成功条数
     * @param failed 失败条数
     * @param items 逐条结果（与请求同序）
     */
    public record BulkRegisterResponse(
            int total,
            int succeeded,
            int failed,
            List<BulkItemResultDto> items
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

            /**
             * 复核人主体（双人复核的第二个人）。
             *
             * <p>⚠️ <b>V1.0.2 S3-2 新增</b>：{@code VERIFIED} 必须由两个不同主体签署；
             * 本字段是第二个主体，<b>必须与执行人（令牌 sub）不同</b>。
             * 其余状态可留空。
             *
             * <p>⚠️⚠️ <b>格式必须显式校验</b>：该值会落库到
             * {@code rag_source.verified_by uuid}，绕过 Bean Validation 的畸形串
             * 会在 JDBC 参数绑定阶段炸成 500 —— 把一次「治理拒绝」伪装成「服务故障」。
             * 空串视为「未提供」（只对非 VERIFIED 合法，VERIFIED 由 SoD 拦下）。
             */
            @Pattern(regexp = "^$|^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}"
                    + "-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
                    message = "verifiedBy 必须是合法 UUID（或留空）")
            String verifiedBy,

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
     * 载体级校验决定请求。
     *
     * <p><b>⚠️ 与来源级 {@link VerificationRequest} 同形，但语义不同</b>：
     * {@code trustTier} 与 {@code validDays} 在载体级<b>不产生任何效果</b>——
     * 权威等级是出版层面（来源）的属性，一份扫描件再可靠也不会让整部教材升级；
     * 载体也没有独立的有效期字段（有效期绑定在来源的校验结论上）。
     * 之所以仍接受这两个字段，是为了让两份 HTTP 契约同形、前端复用同一套表单。
     *
     * @param targetStatus 目标状态（IN_REVIEW / VERIFIED / REJECTED / REVOKED）
     * @param evidenceRef 校验依据引用（撤回时落 {@code revoked_reason}）
     * @param decidedBy 决定人 UUID
     * @param verificationMethod 校验手段
     * @param trustTier 权威等级（载体级不生效，仅保持契约同形）
     * @param validDays 有效天数（载体级不生效，仅保持契约同形）
     */
    public record DocumentVerificationRequest(
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
     * 重跑索引请求。
     *
     * @param limit 本次最多处理的片段数（可空 → 用默认上限）
     */
    public record ReindexRequest(
            @jakarta.validation.constraints.Min(value = 1, message = "limit 必须 >= 1")
            @jakarta.validation.constraints.Max(value = 2000, message = "limit 不能超过 2000")
            Integer limit
    ) {
    }

    /**
     * 重跑索引响应。
     *
     * @param indexedChunks 实际完成索引的片段数
     * @param scannedChunks 扫描到的待索引片段数
     * @param durationMs 本次耗时（毫秒）
     */
    public record ReindexResponse(
            int indexedChunks,
            int scannedChunks,
            long durationMs
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
     * @param createdAt 录入（登记）时间
     * @param updatedAt 最近更新时间
     * @param reviewStartedAt 审核开始时间（可空：从未认领）
     * @param decidedAt 审核决定时间（可空：从未判定）
     * @param metadataConfirmedAt 元数据确认时间（可空：尚未确认）
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
            String updatedAt,
            /**
             * 审核开始时间（{@code rag_source.review_started_at}）。
             *
             * <p>null = 从未被认领进审核（不是「时间是 1970」也不是「等于登记时间」）。
             */
            String reviewStartedAt,
            /**
             * 审核决定时间（{@code rag_source.ver_decided_at}，最近一次判定时刻）。
             *
             * <p>⚠️ 与 {@code verifiedAt} 不同轴：{@code verifiedAt} 是
             * 「结论有效期」的起点（{@code ver_valid_days} 从它算起），
             * 而本字段记录「那次判定发生在何时」。判定被拒/撤回时
             * {@code verifiedAt} 可能无意义，{@code decidedAt} 仍然有效。
             */
            String decidedAt,
            /**
             * 元数据确认时间（{@code rag_source.metadata_confirmed_at}）。
             *
             * <p>null = 尚未由人确认元数据（来源可能仍处于「推断值待核」阶段）。
             */
            String metadataConfirmedAt,
            /**
             * 抽检状态（{@code rag_source.inspection_status}，读路径透出）。
             *
             * <p>⚠️ 仅在<b>读路径</b>（列表/详情）由服务端填充；null 表示「本次响应
             * 未读取」（写路径响应恒为 null）——前端应回退为按抽检历史推导，
             * 不得把 null 当成任何一种结论。
             */
            String inspectionStatus,
            /**
             * 载体（文档）数量（读路径透出）。
             *
             * <p>0 = 「仅元数据」登记（从未上传物理文件）。列表页用它把这类来源
             * 与真实携带语料的来源区分开——2026-11-03 端到端抽检发现两者
             * 在列表里无法辨认，管理员会点进空来源白忙一场。
             */
            int documentCount
    ) {
    }

    /**
     * 带上抽检状态拷贝来源视图。
     *
     * <p>⚠️ 刻意做成显式助手而不是让 {@link #toResponse} 直接查库：
     * 映射器保持纯函数（不碰端口），由控制器决定「哪条读路径要富化、
     * 富化失败怎么办」——职责分清，测试也好写。
     *
     * @param response 原视图
     * @param inspectionStatus 抽检状态（可 null = 未读取）
     * @return 带抽检状态的新视图
     */
    public static SourceResponse withInspectionStatus(SourceResponse response,
                                                      String inspectionStatus) {
        return new SourceResponse(
                response.sourceId(), response.sourceType(), response.title(),
                response.publisher(), response.isbn(), response.doi(),
                response.trustTier(), response.verificationStatus(),
                response.verificationMethod(), response.verificationEvidence(),
                response.verifiedAt(), response.verifiedBy(), response.validDays(),
                response.licenseStatus(), response.stale(), response.admissible(),
                response.objectRef(), response.contentHash(),
                response.createdAt(), response.updatedAt(),
                response.reviewStartedAt(), response.decidedAt(),
                response.metadataConfirmedAt(),
                inspectionStatus,
                response.documentCount());
    }

    /**
     * 带上载体数量拷贝来源视图（与 {@link #withInspectionStatus} 同款纯函数助手，
     * 由控制器决定哪条读路径要富化、失败怎么办）。
     *
     * @param response 原视图
     * @param documentCount 载体数量（0 = 仅元数据登记）
     * @return 带载体数量的新视图
     */
    public static SourceResponse withDocumentCount(SourceResponse response,
                                                   int documentCount) {
        return new SourceResponse(
                response.sourceId(), response.sourceType(), response.title(),
                response.publisher(), response.isbn(), response.doi(),
                response.trustTier(), response.verificationStatus(),
                response.verificationMethod(), response.verificationEvidence(),
                response.verifiedAt(), response.verifiedBy(), response.validDays(),
                response.licenseStatus(), response.stale(), response.admissible(),
                response.objectRef(), response.contentHash(),
                response.createdAt(), response.updatedAt(),
                response.reviewStartedAt(), response.decidedAt(),
                response.metadataConfirmedAt(),
                response.inspectionStatus(),
                documentCount);
    }

    /**
     * 文档载体视图（响应）。
     *
     * <p><b>⚠️ 载体级治理字段必须随文档一起返回</b>：
     * {@code verificationStatus} / {@code retrievable} / {@code revokedAt} /
     * {@code revokedReason} / {@code licenseStatus} 是「这份物理文件能不能用」的
     * 结论。缺了它们，前端只能看到「解析成功」这类技术状态，
     * 却看不到「已被撤回」——管理台会显示一份可下载的已下架语料。
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
     * @param verificationStatus 载体级校验状态
     * @param retrievable 载体级可检索标记
     * @param revokedAt 撤回时间（可空）
     * @param revokedReason 撤回依据（可空）
     * @param licenseStatus 载体级版权状态
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
            String verificationStatus,
            boolean retrievable,
            String revokedAt,
            String revokedReason,
            String licenseStatus,
            String createdAt,
            String updatedAt,
            // 【V1.0.2 §3.1 ③】本次上传所创建的摄取任务 ID；null 表示未建单（端口未装配/建单失败）。
            // ⚠️ 追加在末尾而非中间：前端按字段名取值，追加不破坏既有解析。
            String jobId
    ) {
    }

    /**
     * 单条抽检记录（请求）【V1.0.2 §5 R2】。
     *
     * @param chunkId      片段 ID
     * @param expectedRole 期望角色（人工判定的正确角色）
     * @param actualRole   实际角色（系统产出）
     * @param errorType    错误类型（verdict=FAIL 时必填）
     * @param verdict      结论 PASS / FAIL
     */
    public record InspectionItemRequest(
            String chunkId,
            String expectedRole,
            String actualRole,
            String errorType,
            String verdict
    ) {
    }

    /**
     * 提交抽检（请求）。
     *
     * @param inspectorId 抽检人（缺省时用令牌主体）
     * @param items       抽检记录
     */
    public record SubmitInspectionRequest(
            String inspectorId,
            java.util.List<InspectionItemRequest> items
    ) {
    }

    /**
     * 抽检判定结果（响应）。
     *
     * @param passed      是否通过
     * @param total       抽检片段总数
     * @param failed      判错数
     * @param answerLeaks 答案区漏判数
     * @param accuracy    准确率
     * @param reason      不通过原因（通过时为 null）
     */
    public record InspectionVerdictResponse(
            boolean passed,
            int total,
            int failed,
            int answerLeaks,
            double accuracy,
            String reason
    ) {
    }

    /**
     * 抽检记录（响应）。
     */
    public record InspectionRecordResponse(
            String recordId,
            String chunkId,
            String inspectorId,
            String expectedRole,
            String actualRole,
            String errorType,
            String verdict,
            String createdAt
    ) {
    }

    /**
     * 别名维护（请求）。
     *
     * @param kpId  知识点 ID
     * @param alias 别名文本
     */
    public record KnowledgeAliasRequest(String kpId, String alias) {
    }

    /**
     * 审核员名录条目视图（D-001 RAG 侧授权投影）。
     *
     * <p>⚠️ 只含「选人需要的最小信息」：主体 ID + 显示名 + 权限标记。
     *    不返回登录名、密码、真实姓名（PIPL / DEC-6）。
     *
     * @param principalId 主体 ID（与平台 JWT 的 sub 一致）
     * @param displayName 显示名（下拉里给人看的那一行）
     * @param canReview   是否可执行审核
     * @param canCoSign   是否可做复核人（双人签署的第二签）
     * @param enabled     是否启用
     */
    public record ReviewerItem(
            String principalId,
            String displayName,
            boolean canReview,
            boolean canCoSign,
            boolean enabled
    ) {
    }

    /**
     * 登记审核员请求。
     *
     * @param principalId 主体 ID（必填：名录必须指向一个确定的人）
     * @param displayName 显示名（不得含真实姓名）
     * @param canReview   是否可执行审核
     * @param canCoSign   是否可做复核
     * @param enabled     是否启用（默认 true）
     */
    public record UpsertReviewerRequest(
            @NotBlank(message = "principalId 不能为空")
            String principalId,

            @NotBlank(message = "displayName 不能为空")
            @Size(max = 128, message = "displayName 长度不能超过 128")
            String displayName,

            Boolean canReview,
            Boolean canCoSign,
            Boolean enabled
    ) {
    }

    /**
     * 重处理请求（交互文档 §4.3「追加重传」）。
     *
     * <p><b>为什么必须显式声明模式，而不是一个「重新处理」按钮</b>：
     * 三种重处理的代价与影响面差别极大——
     * 只补向量是分钟级且不中断检索，重新切分会短暂让语料不可检索，
     * 换文件则会让旧文件作废。用一个含糊的按钮把它们混在一起，
     * 等于让人在不清楚后果的情况下做不可逆决定。
     *
     * @param mode 重处理模式（REINDEX_MISSING / RESPLIT / REPLACE_FILE 之一）
     */
    public record ReprocessRequest(
            @NotBlank(message = "mode 不能为空")
            String mode
    ) {
    }

    /**
     * 重处理结果。
     *
     * @param mode         实际执行的模式
     * @param accepted     是否已受理
     * @param message      人话说明（含「接下来会发生什么」）
     * @param newJobId     新任务 ID（需要异步重跑的模式会返回）
     */
    public record ReprocessResponse(
            String mode,
            boolean accepted,
            String message,
            String newJobId
    ) {
    }

    /**
     * 语料生命周期状态（交互文档 §4.7「检索准入与撤回」）。
     *
     * <p><b>它解决什么</b>：当前「有效期/过期」只体现为列表里一个
     * 「结论已过期」标签，**没有任何提前提醒**——语料到期当天才被发现，
     * 而那时学生已经搜不到了。本视图把「还有几天到期、哪些已过期」算好并前置呈现。
     *
     * <p>⚠️ {@code stale} / {@code daysUntilExpiry} 一律**服务端计算**：
     * 前端自己拿 verifiedAt + validDays 算，会与检索侧
     * {@code admissiblePredicate} 的口径漂移（时区、边界日、时钟偏差各算一遍）。
     *
     * @param sourceId          来源 ID
     * @param title             书名
     * @param verificationStatus 校验状态
     * @param verifiedAt        校验通过时间（未通过为 null）
     * @param validDays         有效期天数
     * @param expiresAt         到期时刻（未通过为 null）
     * @param daysUntilExpiry   距到期天数（负数=已过期；未通过为 null）
     * @param stale             是否已过期（服务端计算）
     * @param expiringSoon      是否即将到期（阈值内，需人工续期）
     * @param admissible        当前是否可被检索
     */
    public record LifecycleItem(
            String sourceId,
            String title,
            String verificationStatus,
            String verifiedAt,
            int validDays,
            String expiresAt,
            Integer daysUntilExpiry,
            boolean stale,
            boolean expiringSoon,
            boolean admissible
    ) {
    }

    /**
     * 审核待办项（交互文档 §4.6「审核收件箱」）。
     *
     * <p><b>它解决什么</b>：审核员当前要「搜索 → 找人 → 读上下文」才能知道
     * 今天该审哪几本。本视图把「该不该我审、卡在哪一步」直接算好，
     * 审核员打开就是一份可执行的待办清单。
     *
     * <p>⚠️ {@code stage} 是**服务端算好的下一步**，不是让前端自己判断：
     * 前端一旦自行推算，就会与后端的状态机守卫口径漂移
     * （典型事故：界面给了一个点下去必然 409 的按钮）。
     *
     * @param sourceId        来源 ID
     * @param title           书名
     * @param publisher       出版社
     * @param verificationStatus 校验状态（PENDING / IN_REVIEW / ...）
     * @param metadataConfirmed 元数据是否已确认
     * @param inspectionStatus 抽检状态（NOT_REQUIRED / PASSED / FAILED）
     * @param documentCount   载体数
     * @param chunkCount      片段总数
     * @param answerChunkCount 答案类片段数（抽检最关键对象）
     * @param registeredBy    采集人（用于「审核员≠采集人」的界面提示）
     * @param stage           下一步该做什么（见 Stage 枚举）
     * @param stageText       下一步的人话说明
     * @param canAct          当前调用者此刻是否可执行该步骤
     * @param cantActReason   不可执行的原因（canAct=false 时非空）
     * @param createdAt       创建时间
     */
    public record ReviewQueueItem(
            String sourceId,
            String title,
            String publisher,
            String verificationStatus,
            boolean metadataConfirmed,
            String inspectionStatus,
            int documentCount,
            long chunkCount,
            long answerChunkCount,
            String registeredBy,
            String stage,
            String stageText,
            boolean canAct,
            String cantActReason,
            String createdAt
    ) {
    }

    /**
     * 抽检疑点视图（交互文档 §4.6「看红灯」）。
     *
     * <p>⚠️ 必须带 {@code text}：判「是不是答案」必须**看到字**。
     *    只给角色和页码的摘要行，人无法做判断（这正是当前抽检台的老问题）。
     *
     * @param chunkId     片段 ID
     * @param documentId  所属载体
     * @param role        当前角色
     * @param text        片段正文（已截断）
     * @param pageNumber  页码
     * @param sectionPath 章节路径
     * @param exerciseNo  题号
     * @param reason      疑点成因代码
     * @param reasonText  疑点成因的人话说明
     * @param severity    严重度（HIGH / MEDIUM / LOW）
     */
    public record InspectionSuspectResponse(
            String chunkId,
            String documentId,
            String role,
            String text,
            Integer pageNumber,
            String sectionPath,
            String exerciseNo,
            String reason,
            String reasonText,
            String severity
    ) {
    }

    /**
     * 元数据确认请求（交互文档 §4.3「产出确认」/ V1.0.2 §5 R1）。
     *
     * <p>⚠️ 携带元数据快照而非只有「已确认」一个布尔：确认的意义是
     * 「我为**这一组具体值**担保」。只记布尔的留痕，事后无法回答
     * 「他确认时书名是什么」——而书名恰是最常被改的字段。
     *
     * @param title     确认时的书名
     * @param publisher 确认时的出版社
     * @param isbn      ISBN（论文类可空）
     * @param subject   学科（可空）
     * @param grade     年级（可空）
     * @param term      册次：上册/下册/全一册（可空）
     * @param note      确认备注（可空）
     */
    public record ConfirmMetadataRequest(
            @NotBlank(message = "title 不能为空")
            @Size(max = 512, message = "title 长度不能超过 512")
            String title,

            @NotBlank(message = "publisher 不能为空")
            @Size(max = 256, message = "publisher 长度不能超过 256")
            String publisher,

            @Size(max = 64, message = "isbn 长度不能超过 64")
            String isbn,

            @Size(max = 64, message = "subject 长度不能超过 64")
            String subject,

            @Size(max = 64, message = "grade 长度不能超过 64")
            String grade,

            @Size(max = 16, message = "term 长度不能超过 16")
            String term,

            @Size(max = 512, message = "note 长度不能超过 512")
            String note
    ) {
    }

    /**
     * 元数据确认响应。
     *
     * @param sourceId            来源 ID
     * @param metadataConfirmed   是否已确认
     * @param metadataConfirmedAt 首次确认时间
     * @param metadataConfirmedBy 首次确认人
     */
    public record ConfirmMetadataResponse(
            String sourceId,
            boolean metadataConfirmed,
            String metadataConfirmedAt,
            String metadataConfirmedBy
    ) {
        /** 领域结果 → 视图（时间统一 ISO-8601）。 */
        public static ConfirmMetadataResponse from(
                cn.studykid.rag.management.core.MetadataConfirmationResult result) {
            return new ConfirmMetadataResponse(
                    result.sourceId(),
                    result.metadataConfirmed(),
                    result.metadataConfirmedAt() == null
                            ? null : result.metadataConfirmedAt().toString(),
                    result.metadataConfirmedBy());
        }
    }

    /**
     * 摄取任务视图（响应）【V1.0.2 §8.5】。
     *
     * @param jobId        任务 ID
     * @param tenantId     租户 ID
     * @param sourceId     所属来源
     * @param status       状态（8 值枚举字面量）
     * @param stage        阶段内进度（如 "PARSING 60"）
     * @param attempt      第几次尝试
     * @param createdBy    触发人
     * @param parserVersion 解析器版本
     * @param embedModel   嵌入模型
     * @param chunkCount   产出片段数
     * @param errorDetail  失败 / 取消原因
     * @param startedAt    开始时间
     * @param finishedAt   结束时间
     * @param createdAt    建单时间
     * @param sourceRevokedCancel 是否为「来源撤回导致的取消」（前端据此显示为正常结束而非失败）
     */
    public record IngestJobResponse(
            String jobId,
            String tenantId,
            String sourceId,
            String status,
            String stage,
            int attempt,
            String createdBy,
            String parserVersion,
            String embedModel,
            Integer chunkCount,
            String errorDetail,
            String startedAt,
            String finishedAt,
            String createdAt,
            boolean sourceRevokedCancel
    ) {
    }

    /**
     * 摄取任务领域对象 → 响应视图。
     *
     * <p>⚠️ {@code sourceRevokedCancel} 是本映射唯一带业务判断的字段：
     * 撤回取消（REJECTED + SOURCE_REVOKED）在 UI 上应显示为「已取消」而非「失败」——
     * 它是治理动作的正常结果，标红会误导采集员以为自己的文件出了问题（N2）。
     *
     * @param job 领域任务
     * @return 响应视图
     */
    public static IngestJobResponse toIngestJobResponse(
            cn.studykid.rag.core.ingest.IngestJob job) {
        return new IngestJobResponse(
                job.jobId(), job.tenantId(), job.sourceId(), job.status().name(),
                job.stage(), job.attempt(), job.createdBy(), job.parserVersion(),
                job.embedModel(), job.chunkCount(), job.errorDetail(),
                job.startedAt(), job.finishedAt(), job.createdAt(),
                job.isSourceRevokedCancel());
    }

    /**
     * 维护问题清单视图（响应）。
     *
     * @param documentsMissingEmbedding 缺向量的载体
     * @param documentsFailedParse 解析失败的载体
     * @param sourcesPendingReview 待审核的来源
     * @param totals 各类问题条数
     */
    public record IssuesResponse(
            List<MissingEmbeddingDocumentResponse> documentsMissingEmbedding,
            List<FailedParseDocumentResponse> documentsFailedParse,
            List<PendingReviewSourceResponse> sourcesPendingReview,
            IssueTotalsResponse totals
    ) {
    }

    /**
     * 缺向量的载体（清单项）。
     *
     * @param documentId 载体 ID
     * @param sourceId 来源 ID
     * @param title 标题
     * @param carrier 载体类型
     * @param parseStatus 解析状态
     * @param chunkCount 片段总数
     * @param missingEmbeddingCount 缺向量的片段数
     */
    public record MissingEmbeddingDocumentResponse(
            String documentId,
            String sourceId,
            String title,
            String carrier,
            String parseStatus,
            long chunkCount,
            long missingEmbeddingCount
    ) {
    }

    /**
     * 解析失败的载体（清单项）。
     *
     * @param documentId 载体 ID
     * @param sourceId 来源 ID
     * @param title 标题
     * @param carrier 载体类型
     * @param parseStatus 解析状态
     * @param chunkCount 片段总数
     */
    public record FailedParseDocumentResponse(
            String documentId,
            String sourceId,
            String title,
            String carrier,
            String parseStatus,
            long chunkCount
    ) {
    }

    /**
     * 待审核的来源（清单项）。
     *
     * <p>⚠️ {@code stale} 是<b>服务端计算</b>的结论：
     * 它依赖「校验结论有效期 + 当前时刻」，前端没有租户权威门槛上下文，
     * 自己算必然与服务端口径不一致。
     *
     * @param sourceId 来源 ID
     * @param title 标题
     * @param verificationStatus 当前校验状态
     * @param stale 校验结论是否已过期
     * @param trustTier 权威等级
     */
    public record PendingReviewSourceResponse(
            String sourceId,
            String title,
            String verificationStatus,
            boolean stale,
            String trustTier
    ) {
    }

    /**
     * 问题清单合计（响应）。
     *
     * @param documentsMissingEmbedding 缺向量的载体数
     * @param documentsFailedParse 解析失败的载体数
     * @param sourcesPendingReview 待审核的来源数
     */
    public record IssueTotalsResponse(
            int documentsMissingEmbedding,
            int documentsFailedParse,
            int sourcesPendingReview
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
     * @param text 片段正文（管理面预览，仓储侧截断至 512 字符）
     */
    public record ChunkResponse(
            String chunkId,
            String role,
            int pageNumber,
            String sectionPath,
            String exerciseNo,
            int charLength,
            boolean hasEmbedding,
            String contentHash,
            /**
             * 片段正文预览。
             *
             * <p><b>⚠️ 为什么必须给</b>：解析预览与片段校对要让人看到原文，
             * 才能判断"这段到底是答案还是讲解"（RAG-D13 §3.2.3/§3.2.4）。
             * 只给 {@code charLength} 无法支撑任何判断。
             */
            String text,
            /**
             * 版面事实：块类型（BlockType 枚举名）。
             *
             * <p>⚠️ null = 该片段早于版面事实采集，**不是**"无块类型"。
             */
            String blockType,
            /** 版面事实：是否位于答案区。 */
            boolean inAnswerZone,
            /** 版面事实：块首命中的解答前缀（如「解:」）。 */
            String matchedPrefix
    ) {
    }

    /**
     * 片段角色重判请求（RAG-D13 §3.2.4）。
     *
     * @param role 目标角色（必须是 {@code ChunkRole} 枚举名）
     * @param reason 重判理由（审计留痕；可为空但不推荐）
     */
    public record UpdateChunkRoleRequest(
            @jakarta.validation.constraints.NotBlank(message = "role 不能为空")
            @jakarta.validation.constraints.Pattern(
                    regexp = "EXPLANATION|EXAMPLE|EXAMPLE_SOLUTION|EXERCISE|EXERCISE_ANSWER"
                            + "|EXERCISE_HINT|PAPER_BODY|PAPER_METHOD|APPENDIX|BOILERPLATE",
                    message = "role 必须是合法的 ChunkRole 枚举名")
            String role,

            @jakarta.validation.constraints.Size(max = 512, message = "reason 长度不能超过 512")
            String reason
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
     * 单日检索次数（报表中心 · 每日搜索的问题数）。
     *
     * @param date 日期（UTC，YYYY-MM-DD）
     * @param searches 当日检索次数
     */
    public record DailySearchCountResponse(String date, long searches) {
    }

    /**
     * 每日检索次数聚合响应。
     *
     * @param searchesByDay 连续日期的每日检索次数（无数据的日子为 0）
     * @param generatedDays 覆盖天数
     */
    public record DailySearchResponse(
            List<DailySearchCountResponse> searchesByDay,
            int generatedDays
    ) {
    }

    /**
     * 单日导入语料量（三层级）。
     *
     * @param date 日期（UTC，YYYY-MM-DD）
     * @param sources 当日登记的资料（来源）数
     * @param documents 当日导入的文档载体数
     * @param chunks 当日切分的片段数
     */
    public record DailyImportCountResponse(
            String date,
            long sources,
            long documents,
            long chunks
    ) {
    }

    /**
     * 每日导入语料量聚合响应。
     *
     * @param importsByDay 连续日期的每日导入量（无数据的日子三层均为 0）
     * @param generatedDays 覆盖天数
     */
    public record DailyImportResponse(
            List<DailyImportCountResponse> importsByDay,
            int generatedDays
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
                source.createdAt().toString(), source.updatedAt().toString(),
                // ⚠️ 三个时间各自可空：null 表达「尚未发生」，不得回落到 createdAt ——
                //    列表要能区分「还没人认领」与「认领于登记当天」。
                toIso(source.reviewStartedAt()), toIso(source.decidedAt()),
                toIso(source.metadataConfirmedAt()),
                // ⚠️ 抽检状态不在领域快照里：读路径由控制器经 InspectionPort 富化
                //    （withInspectionStatus），写路径响应保持 null（前端回退历史推导）。
                //    documentCount 同理：写路径不查库，恒 0（前端写路径不用它）。
                null, 0);
    }

    /**
     * 可空时刻 → ISO 字符串（null 保持 null）。
     *
     * <p>⚠️ 单独抽出来而不是内联三段三元表达式：三个时间字段都会用到它，
     * 复制三遍就有三次机会把 null 写成空串或 {@code "null"} ——
     * 前端按 `null` 判断「尚未发生」，空串会被当成一个合法时刻渲染成 Invalid Date。
     *
     * @param instant 可空时刻
     * @return ISO-8601 文本，或 null
     */
    private static String toIso(java.time.Instant instant) {
        return instant == null ? null : instant.toString();
    }

    /**
     * @param documents 领域文档列表
     * @return 响应列表
     */
    public static List<DocumentResponse> toDocumentResponses(
            List<cn.studykid.rag.management.core.CorpusDocument> documents) {
        return documents.stream()
                .map(ManagementDtos::toDocumentResponse)
                .toList();
    }

    /**
     * 把领域载体转换为响应视图。
     *
     * @param document 领域载体
     * @return 响应视图
     */
    public static DocumentResponse toDocumentResponse(
            cn.studykid.rag.management.core.CorpusDocument document) {
        return toDocumentResponse(document, null);
    }

    /**
     * 把领域载体转换为响应视图（带摄取任务 ID）。
     *
     * <p>【V1.0.2 §3.1 ③】上传路径返回 jobId，前端据此轮询进度；
     * 其他查询路径（列表/详情）无 job 语义，沿用 {@code jobId=null} 的重载。
     *
     * @param document 领域载体
     * @param jobId    本次上传创建的摄取任务 ID（可为 null）
     * @return 响应视图
     */
    public static DocumentResponse toDocumentResponse(
            cn.studykid.rag.management.core.CorpusDocument document, String jobId) {
        return new DocumentResponse(
                document.documentId(), document.sourceId(), document.editionId(),
                document.carrier(), document.title(), document.pageCount(), document.objectRef(),
                document.parseStatus(), document.parserVersion(), document.contentHash(),
                document.chunkCount(),
                document.verificationStatus().name(), document.retrievable(),
                document.revokedAt() == null ? null : document.revokedAt().toString(),
                document.revokedReason(), document.licenseStatus(),
                document.createdAt().toString(), document.updatedAt().toString(), jobId);
    }

    /**
     * 把领域问题清单转换为响应视图。
     *
     * @param issues 领域问题清单
     * @return 响应视图
     */
    public static IssuesResponse toIssuesResponse(
            cn.studykid.rag.management.core.CorpusIssues issues) {
        List<MissingEmbeddingDocumentResponse> missing = issues.documentsMissingEmbedding().stream()
                .map(g -> new MissingEmbeddingDocumentResponse(
                        g.documentId(), g.sourceId(), g.title(), g.carrier(), g.parseStatus(),
                        g.chunkCount(), g.missingEmbeddingCount()))
                .toList();
        List<FailedParseDocumentResponse> failed = issues.documentsFailedParse().stream()
                .map(f -> new FailedParseDocumentResponse(
                        f.documentId(), f.sourceId(), f.title(), f.carrier(), f.parseStatus(),
                        f.chunkCount()))
                .toList();
        List<PendingReviewSourceResponse> pending = issues.sourcesPendingReview().stream()
                .map(s -> new PendingReviewSourceResponse(
                        s.sourceId(), s.title(), s.verificationStatus(), s.stale(), s.trustTier()))
                .toList();
        // ⚠️ 合计由分项长度派生（服务端同一次读的结果），
        //    不另查一次库——两次查询之间数据可能变化，
        //    「总数对不上分项」会被运维误读为数据竞态。
        var totals = issues.totals();
        return new IssuesResponse(missing, failed, pending,
                new IssueTotalsResponse(totals.documentsMissingEmbedding(),
                        totals.documentsFailedParse(), totals.sourcesPendingReview()));
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
                        c.charLength(), c.hasEmbedding(), c.contentHash(), c.text(),
                        c.blockType(), c.inAnswerZone(), c.matchedPrefix()))
                .toList();
    }

    /**
     * 每日检索统计 → 响应契约。
     *
     * @param stats 端口返回的每日检索聚合
     * @return 响应契约
     */
    public static DailySearchResponse toDailySearchResponse(
            cn.studykid.rag.management.ports.CorpusManagementPort.DailySearchStatistics stats) {
        List<DailySearchCountResponse> byDay = stats.searchesByDay().stream()
                .map(s -> new DailySearchCountResponse(s.date(), s.searches()))
                .toList();
        return new DailySearchResponse(byDay, stats.generatedDays());
    }

    /**
     * 每日导入统计 → 响应契约。
     *
     * @param stats 端口返回的每日导入聚合
     * @return 响应契约
     */
    public static DailyImportResponse toDailyImportResponse(
            cn.studykid.rag.management.ports.CorpusManagementPort.DailyImportStatistics stats) {
        List<DailyImportCountResponse> byDay = stats.importsByDay().stream()
                .map(i -> new DailyImportCountResponse(i.date(), i.sources(), i.documents(),
                        i.chunks()))
                .toList();
        return new DailyImportResponse(byDay, stats.generatedDays());
    }

    // ---------------------------------------------------------------------
    // 字典（Point 2：枚举下拉的数据源，可由前端维护）
    // ---------------------------------------------------------------------

    /**
     * 字典项响应（动态/派生类型共用）。
     *
     * <p><b>⚠️ {@code scope} 决定前端能不能给这一行放按钮</b>：
     * {@code PLATFORM} 是平台全局项（或派生枚举），对租户<b>只读</b>——
     * 删除/编辑点了也没用（后端 RLS 会拒），所以按钮本就不该出现。
     * {@code TENANT} 才是本租户自有、可维护的项。
     *
     * @param type 字典类型（SOURCE_TYPE / TRUST_TIER / VERIFICATION_METHOD / LICENSE_STATUS）
     * @param code 字典项编码（稳定标识，存库与表单提交都用它）
     * @param label 展示名
     * @param sortOrder 排序权重（升序）
     * @param enabled 是否启用
     * @param scope 作用域：PLATFORM=平台全局/派生（只读），TENANT=本租户（可维护）
     */
    public record DictionaryEntryDto(
            @NotBlank String type,
            @NotBlank String code,
            @NotBlank String label,
            int sortOrder,
            boolean enabled,
            @NotNull DictionaryPort.Scope scope
    ) {
    }

    /**
     * 字典类型视图（含该类型的全部条目 + 是否可维护）。
     *
     * @param type 字典类型
     * @param editable 是否允许前端维护（动态字典可写；派生字典只读）
     * @param entries 该类型的全部条目（已按 sortOrder 升序）
     */
    public record DictionaryTypeDto(
            @NotBlank String type,
            boolean editable,
            List<DictionaryEntryDto> entries
    ) {
    }

    /**
     * 字典项写入/更新请求（仅对可编辑类型生效）。
     *
     * <p>⚠️ {@code code} 是幂等键：同一 (租户, 类型, code) 重复提交走 upsert，
     * 不会新建重复项。{@code type} 由路径参数带入，不放在请求体里——
     * 避免「请求体里的 type 与路径 type 不一致」这种语义冲突。
     *
     * @param code 字典项编码（稳定标识）
     * @param label 展示名
     * @param sortOrder 排序权重
     * @param enabled 是否启用
     */
    public record UpsertDictionaryEntryRequest(
            @NotBlank(message = "code 不能为空")
            @Size(max = 64, message = "code 长度不能超过 64")
            @Pattern(regexp = "^[A-Za-z0-9_]+$", message = "code 只能包含字母、数字、下划线")
            String code,

            @NotBlank(message = "label 不能为空")
            @Size(max = 128, message = "label 长度不能超过 128")
            String label,

            int sortOrder,

            boolean enabled
    ) {
    }

    // ======================================================================
    // 索引代际（RAG-D13 §3.5.2 蓝绿切换）
    // ======================================================================

    /**
     * 索引代际视图（响应）。
     *
     * @param generation    代际号
     * @param embedModel    嵌入模型标识
     * @param dimension     向量维度
     * @param status        BUILDING / ACTIVE / RETIRED
     * @param indexedChunks 已写入向量行数
     * @param totalChunks   目标片段总数
     * @param progressPercent 构建进度百分比（0–100，服务端算好避免前端除零）
     * @param retrievable   是否参与检索（仅 ACTIVE）
     * @param activatable   是否可切换过去（ACTIVE / RETIRED；BUILDING 不可）
     * @param note          备注
     * @param activatedAt   激活时刻
     * @param retiredAt     退役时刻
     * @param createdAt     创建时刻
     */
    public record IndexGenerationResponse(
            int generation,
            String embedModel,
            int dimension,
            String status,
            long indexedChunks,
            long totalChunks,
            int progressPercent,
            boolean retrievable,
            boolean activatable,
            String note,
            String activatedAt,
            String retiredAt,
            String createdAt
    ) {

        static IndexGenerationResponse from(
                cn.studykid.rag.core.indexing.IndexGeneration g) {
            return new IndexGenerationResponse(
                    g.generation(), g.embedModel(), g.dimension(), g.status().name(),
                    g.indexedChunks(), g.totalChunks(), g.progressPercent(),
                    g.isRetrievable(), g.isActivatable(), g.note(),
                    g.activatedAt(), g.retiredAt(), g.createdAt());
        }
    }

    /**
     * 新建索引代际请求。
     *
     * @param embedModel 嵌入模型标识（如 BGE-M3 / qwen3-embedding:8b）
     * @param dimension  向量维度（必须与目标模型的真实维度一致，
     *                   否则构建出的向量写不进 embedding 列）
     */
    public record CreateIndexGenerationRequest(
            @jakarta.validation.constraints.NotBlank(message = "embedModel 不能为空")
            @jakarta.validation.constraints.Size(max = 128, message = "embedModel 长度不能超过 128")
            String embedModel,

            @jakarta.validation.constraints.Min(value = 1, message = "dimension 必须为正")
            @jakarta.validation.constraints.Max(value = 16384, message = "dimension 过大")
            int dimension
    ) {
    }

    /**
     * 代际构建结果。
     *
     * @param processed 本次处理的片段数
     * @param generation 构建后的代际快照（含最新进度）
     */
    public record BuildGenerationResponse(
            int processed,
            IndexGenerationResponse generation
    ) {
    }

    /**
     * 知识点摘要（响应，RAG-D15 §六 待办 #8）。
     *
     * @param kpId 知识点 ID
     * @param subject 学科
     * @param grade 年级
     * @param canonicalName 规范名
     * @param aliasCount 已有别名数量
     * @param prerequisiteCount 先修关系数量
     */
    public record KnowledgePointBriefDto(
            String kpId,
            String subject,
            String grade,
            String canonicalName,
            int aliasCount,
            int prerequisiteCount
    ) {
    }

    /**
     * 解析预览片段（响应）。
     *
     * @param chunkId    预览用片段 ID（由内容哈希派生，同一文件稳定）
     * @param role       片段角色
     * @param text       片段正文预览
     * @param pageNumber 页码
     * @param exerciseNo 题号
     * @param sectionPath 章节路径
     * @param inAnswerZone 是否位于答案区（版面事实）
     * @param blockType  版面块类型
     */
    public record PreviewChunkDto(
            String chunkId,
            String role,
            String text,
            int pageNumber,
            String exerciseNo,
            String sectionPath,
            boolean inAnswerZone,
            String blockType
    ) {
    }

    /**
     * 解析预览结果（响应，RAG-D15 §六 待办 #6）。
     *
     * @param totalChunks    切分得到的总片段数
     * @param returnedChunks 本次返回的片段数
     * @param limit          生效的条数上限
     * @param truncated      是否因上限被截断
     * @param byRole         按角色的片段计数
     * @param edition        预览所用版本坐标（展示名）
     * @param chunks         片段样本
     */
    public record PreviewResponse(
            int totalChunks,
            int returnedChunks,
            int limit,
            boolean truncated,
            java.util.Map<String, Integer> byRole,
            String edition,
            java.util.List<PreviewChunkDto> chunks
    ) {
    }
}
