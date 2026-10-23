package cn.studykid.rag.management.api;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import cn.studykid.rag.core.source.SourceVerification.VerificationStatus;
import cn.studykid.rag.core.source.TrustTier;
import cn.studykid.rag.management.core.CorpusSource;
import cn.studykid.rag.management.core.RagTenantContext;
import cn.studykid.rag.management.core.SourceQuery;
import cn.studykid.rag.management.core.SourceRegistration;
import cn.studykid.rag.management.core.VerificationDecision;
import cn.studykid.rag.management.ports.CorpusManagementPort;
import cn.studykid.rag.management.ports.CorpusManagementPort.ChunkSummary;

/**
 * RAG 数据管理 REST 端点（RAG-D07 §2 准入流程的管理面入口）。
 *
 * <p><b>⚠️ 租户上下文</b>：与检索端点同构，必须携带 {@code X-Tenant-Id} 请求头。
 * 原因相同——管理 SQL 一律以 {@code rag_current_tenant()} 收口，
 * 而它读的是<b>会话变量</b> {@code app.tenant_id}，
 * 只在事务内有效。跨进程 HTTP 调用时若不重建租户上下文，
 * RLS 会把全部行过滤掉，表现为「管理台永远是空的」且不报错。
 *
 * <p><b>⚠️ 信任边界</b>：本端点不解析令牌、不自建用户体系
 * （RAG-D02 §1.1 单向调用约定）。它必须部署在主平台鉴权边界之后，
 * 不应直接暴露给终端用户——管理面包含<b>权威等级判定</b>与<b>撤回</b>能力，
 * 误暴露的代价远高于检索面。
 */
@RestController
@RequestMapping("/api/v1/management")
public class RagManagementController {

    /**
     * 租户上下文请求头名（与检索端点一致）。
     */
    public static final String TENANT_HEADER = "X-Tenant-Id";

    /**
     * 租户权威门槛头（platform 下发）。
     *
     * <p>⚠️ <b>为什么门槛要由 platform 下发</b>（RAG-D00 §4「租户与身份」边界）：
     * 租户信息（含「本租户最低可检索权威等级」G-3）的<b>权威源是 platform</b>。
     * RAG 库里若存一份租户副本，就有两份权威源互相漂移——
     * 实际后果是「platform 新建租户，RAG 侧查不到任何数据」
     * （子表 {@code tenant_id} 外键要求该租户在 RAG 清单里存在）。
     * V4 迁移已移除该副本，故门槛改由调用方下发。
     *
     * <p>⚠️ <b>信任边界</b>：该头由 platform 在<b>鉴权之后</b>下发，RAG 信任它
     * ——RAG 部署在 platform 鉴权边界之后，且自己不做认证
     * （RAG-D02 §1.1 单向调用约定）。若 RAG 被暴露到鉴权边界之外，
     * 调用方即可伪造门槛值放行低等级语料。
     *
     * <p>缺省为 {@code OFFICIAL_TUTORIAL}（RAG-D00 RD-Q7 决议的默认值）——
     * platform 侧字段尚未落地时，RAG 以该默认值行事，
     * <b>不</b>因为「拿不到」就放宽到 UNVERIFIED。
     */
    public static final String MIN_TRUST_TIER_HEADER = "X-Tenant-Min-Trust-Tier";

    /** 默认返回条数上限。 */
    private static final int DEFAULT_LIMIT = 50;

    /**
     * 缺省权威门槛（RAG-D00 RD-Q7 决议的默认值）。
     *
     * <p>⚠️ 刻意<b>不用</b> {@code UNVERIFIED}：
     * 门槛是「只收紧、不放松」的闸门，缺失时用<b>较严</b>的默认值才安全；
     * 用 UNVERIFIED 等于「配置缺失即放开全部语料」，与 fail-closed 相反。
     */
    private static final TrustTier DEFAULT_MIN_TRUST_TIER = TrustTier.OFFICIAL_TUTORIAL;

    /** 片段列举的硬上限：防止管理端一次性拉走整册内容。 */
    private static final int MAX_CHUNK_LIMIT = 500;

    private final CorpusManagementPort managementPort;

    public RagManagementController(CorpusManagementPort managementPort) {
        if (managementPort == null) {
            throw new IllegalArgumentException("managementPort 不能为空");
        }
        this.managementPort = managementPort;
    }

    /**
     * 登记语料来源（阶段① PENDING）。
     *
     * @param tenantId 租户 ID
     * @param request 登记请求
     * @return 登记后的来源视图
     */
    @PostMapping("/sources")
    public ManagementDtos.SourceResponse registerSource(
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantId,
            @RequestHeader(value = MIN_TRUST_TIER_HEADER, required = false) String minTrustTierHeader,
            @Valid @RequestBody ManagementDtos.RegisterSourceRequest request) {
        RagTenantContext tenant = requireTenantContext(tenantId, minTrustTierHeader);
        SourceRegistration registration = new SourceRegistration(
                request.sourceType(), request.title(), request.publisher(),
                request.isbn(), request.doi(), request.objectRef(), request.contentHash());
        CorpusSource source = managementPort.registerSource(tenant, registration,
                request.registeredBy());
        return ManagementDtos.toResponse(source, TrustTier.UNVERIFIED, Instant.now());
    }

    /**
     * 列出来源（支持按状态/等级/关键词筛选）。
     *
     * @param tenantId 租户 ID
     * @param status 按校验状态筛选
     * @param trustTier 按权威等级筛选
     * @param keyword 标题/ISBN/DOI 模糊匹配
     * @param includeStale 是否包含已过期来源
     * @param limit 条数上限
     * @param offset 偏移量
     * @return 来源列表
     */
    @GetMapping("/sources")
    public List<ManagementDtos.SourceResponse> listSources(
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantId,
            @RequestHeader(value = MIN_TRUST_TIER_HEADER, required = false) String minTrustTierHeader,
            @RequestParam(value = "status", required = false) VerificationStatus status,
            @RequestParam(value = "trustTier", required = false) TrustTier trustTier,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "includeStale", defaultValue = "false") boolean includeStale,
            @RequestParam(value = "limit", defaultValue = "" + DEFAULT_LIMIT) int limit,
            @RequestParam(value = "offset", defaultValue = "0") int offset) {
        RagTenantContext tenant = requireTenantContext(tenantId, minTrustTierHeader);
        SourceQuery query = new SourceQuery(status, trustTier, keyword, includeStale, limit, offset);
        List<CorpusSource> sources = managementPort.listSources(tenant, query);
        Instant now = Instant.now();
        // ⚠️ 准入结论需租户权威门槛（G-3），该门槛由 platform 经
        //    X-Tenant-Min-Trust-Tier 头下发（不在 RAG 库中存租户信息）。
        //    此处用**最低门槛**渲染——真正的准入判定在服务端由 SQL 强制，
        //    管理面只呈现状态事实，不在此处给出与真实门槛不符的结论。
        return sources.stream()
                .map(s -> ManagementDtos.toResponse(s, TrustTier.UNVERIFIED, now))
                .toList();
    }

    /**
     * 查询单个来源。
     *
     * @param tenantId 租户 ID
     * @param sourceId 来源 ID
     * @return 来源视图
     */
    @GetMapping("/sources/{sourceId}")
    public ManagementDtos.SourceResponse getSource(
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantId,
            @RequestHeader(value = MIN_TRUST_TIER_HEADER, required = false) String minTrustTierHeader,
            @PathVariable("sourceId") String sourceId) {
        RagTenantContext tenant = requireTenantContext(tenantId, minTrustTierHeader);
        CorpusSource source = managementPort.findSource(tenant, sourceId);
        if (source == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "来源不存在：" + sourceId);
        }
        return ManagementDtos.toResponse(source, TrustTier.UNVERIFIED, Instant.now());
    }

    /**
     * 执行校验状态转移（认领 / 通过 / 拒绝 / 撤回 / 重新认领）。
     *
     * @param tenantId 租户 ID
     * @param sourceId 来源 ID
     * @param request 校验决定
     * @return 转移后的来源视图
     */
    @PostMapping("/sources/{sourceId}/verification")
    public ManagementDtos.SourceResponse applyVerification(
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantId,
            @RequestHeader(value = MIN_TRUST_TIER_HEADER, required = false) String minTrustTierHeader,
            @PathVariable("sourceId") String sourceId,
            @Valid @RequestBody ManagementDtos.VerificationRequest request) {
        RagTenantContext tenant = requireTenantContext(tenantId, minTrustTierHeader);
        VerificationDecision decision = new VerificationDecision(
                request.targetStatus(), request.evidenceRef(), request.decidedBy(),
                request.verificationMethod(), request.trustTier(),
                request.validDays() == null ? 365 : request.validDays());
        CorpusSource updated;
        try {
            updated = managementPort.applyVerification(tenant, sourceId, decision);
        } catch (IllegalStateException e) {
            // ⚠️ 非法状态转移是**调用方的流程错误**（如 REJECTED 直通 VERIFIED），
            //    返回 409 而不是 500——它不是服务故障，而是一条治理规则被违反。
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
        if (updated == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "来源不存在：" + sourceId);
        }
        return ManagementDtos.toResponse(updated, TrustTier.UNVERIFIED, Instant.now());
    }

    /**
     * 列出某来源下的文档载体。
     *
     * @param tenantId 租户 ID
     * @param sourceId 来源 ID
     * @return 文档列表
     */
    @GetMapping("/sources/{sourceId}/documents")
    public List<ManagementDtos.DocumentResponse> listDocuments(
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantId,
            @RequestHeader(value = MIN_TRUST_TIER_HEADER, required = false) String minTrustTierHeader,
            @PathVariable("sourceId") String sourceId) {
        RagTenantContext tenant = requireTenantContext(tenantId, minTrustTierHeader);
        return ManagementDtos.toDocumentResponses(
                managementPort.listDocuments(tenant, sourceId));
    }

    /**
     * 列出某文档下的片段摘要。
     *
     * @param tenantId 租户 ID
     * @param documentId 文档 ID
     * @param limit 条数上限
     * @return 片段摘要列表
     */
    @GetMapping("/documents/{documentId}/chunks")
    public List<ManagementDtos.ChunkResponse> listChunks(
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantId,
            @RequestHeader(value = MIN_TRUST_TIER_HEADER, required = false) String minTrustTierHeader,
            @PathVariable("documentId") String documentId,
            @RequestParam(value = "limit", defaultValue = "" + DEFAULT_LIMIT) int limit) {
        RagTenantContext tenant = requireTenantContext(tenantId, minTrustTierHeader);
        if (limit <= 0 || limit > MAX_CHUNK_LIMIT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "limit 必须在 1～" + MAX_CHUNK_LIMIT + " 之间");
        }
        List<ChunkSummary> chunks = managementPort.listChunks(tenant, documentId, limit);
        return ManagementDtos.toChunkResponses(chunks);
    }

    /**
     * 统计租户语料与索引状况。
     *
     * @param tenantId 租户 ID
     * @return 统计视图
     */
    @GetMapping("/statistics")
    public ManagementDtos.StatisticsResponse statistics(
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantId,
            @RequestHeader(value = MIN_TRUST_TIER_HEADER, required = false) String minTrustTierHeader) {
        RagTenantContext tenant = requireTenantContext(tenantId, minTrustTierHeader);
        var stats = managementPort.statistics(tenant);
        return new ManagementDtos.StatisticsResponse(
                stats.totalSources(), stats.verifiedSources(), stats.admissibleSources(),
                stats.totalDocuments(), stats.totalChunks(), stats.retrievableChunks(),
                stats.chunksMissingEmbedding(), stats.missingEmbeddingRatio(),
                stats.chunksByRole(), stats.sourcesByVerificationStatus(),
                stats.documentsByParseStatus());
    }

    /**
     * 校验并归一租户上下文（租户 ID + 权威门槛）。
     *
     * <p>⚠️ 缺失租户 ID 时<b>显式报错</b>而不是静默返回空结果：
     * 「没有租户上下文」是调用方配置错误，必须立刻暴露；
     * 若静默返回空，运维会误判为「租户确实没有语料」。
     *
     * <p>⚠️ <b>门槛缺失时的处置</b>：用<b>默认门槛</b> {@code OFFICIAL_TUTORIAL}
     * （RAG-D00 RD-Q7 决议值），<b>不</b>回落到 {@code UNVERIFIED}。
     * 理由：门槛是「只收紧、不放松」的闸门——拿不到时用<b>较严</b>的默认值，
     * 比用最松的默认值安全。若用 UNVERIFIED，等于「配置缺失即放开全部语料」，
     * 与 fail-closed 纪律相反。
     *
     * <p>⚠️ 门槛值非法时<b>同样报错</b>而非静默忽略：调用方传了错值说明
     * 契约被破坏，静默忽略会让「门槛实际未生效」这件事完全不可见。
     *
     * @param tenantId 租户 ID 头
     * @param minTrustTier 门槛头（可空 → 用默认门槛）
     * @return 归一后的租户上下文
     */
    private RagTenantContext requireTenantContext(String tenantId, String minTrustTier) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "缺少租户上下文：请提供 " + TENANT_HEADER + " 请求头（主平台在调用时注入）");
        }
        String normalizedTenant;
        try {
            normalizedTenant = java.util.UUID.fromString(tenantId.trim()).toString();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "租户标识不是合法 UUID：" + tenantId);
        }
        return new RagTenantContext(normalizedTenant, parseMinTrustTier(minTrustTier));
    }

    /**
     * 解析门槛头。
     *
     * @param raw 原始头值（可空）
     * @return 门槛枚举
     */
    private TrustTier parseMinTrustTier(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_MIN_TRUST_TIER;
        }
        try {
            return TrustTier.valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "未知的租户权威门槛：" + raw + "（合法值：" + java.util.Arrays
                            .stream(TrustTier.values()).map(Enum::name).toList() + "）");
        }
    }
}
