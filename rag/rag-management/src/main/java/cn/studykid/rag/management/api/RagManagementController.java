package cn.studykid.rag.management.api;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import cn.studykid.rag.management.core.CorpusDocument;
import cn.studykid.rag.management.core.CorpusSource;
import cn.studykid.rag.management.core.DocumentVerificationDecision;
import cn.studykid.rag.management.core.RagTenantContext;
import cn.studykid.rag.management.core.SourceQuery;
import cn.studykid.rag.management.core.SourceRegistration;
import cn.studykid.rag.management.core.VerificationDecision;
import cn.studykid.rag.management.ports.CorpusIndexingPort;
import cn.studykid.rag.management.ports.CorpusManagementPort;
import cn.studykid.rag.management.ports.CorpusManagementPort.ChunkSummary;
import cn.studykid.rag.management.ports.DictionaryPort;

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

    /**
     * 单次重索引的默认片段数。
     *
     * <p>⚠️ 默认值偏小是刻意的：重索引是<b>同步 HTTP 调用</b>，
     * 一次处理太多会让管理面的响应时间不可控（详见 {@link #MAX_REINDEX_LIMIT}）。
     */
    private static final int DEFAULT_REINDEX_LIMIT = 500;

    /**
     * 单次重索引的硬上限。
     *
     * <p>⚠️ 为什么必须有上限：重索引会为每个片段调用一次嵌入模型，
     * 上限 2000 意味着最坏情况也要跑上数十秒。HTTP 请求线程被长期占用，
     * 高并发下会拖垮管理面（乃至占满 Web 容器工作线程）。
     * 剩下的片段留给下一次调用——管理面是<b>可重复触发</b>的。
     */
    private static final int MAX_REINDEX_LIMIT = 2000;

    private final CorpusManagementPort managementPort;
    private final CorpusIndexingPort indexingPort;
    private final DictionaryPort dictionaryPort;

    public RagManagementController(CorpusManagementPort managementPort,
                                   CorpusIndexingPort indexingPort) {
        this(managementPort, indexingPort, null);
    }

    public RagManagementController(CorpusManagementPort managementPort,
                                   CorpusIndexingPort indexingPort,
                                   DictionaryPort dictionaryPort) {
        if (managementPort == null || indexingPort == null) {
            throw new IllegalArgumentException("managementPort / indexingPort 不能为空");
        }
        this.managementPort = managementPort;
        this.indexingPort = indexingPort;
        this.dictionaryPort = dictionaryPort;
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
     * 批量登记语料来源（数据导入）。
     *
     * <p><b>⚠️ 与单条登记的区别</b>：单条 {@code POST /sources} 用于「录入一条」，
     * 本端点用于「按 CSV/JSON 一次性导入多条」。二者的治理语义完全一致——
     * 每条登记结果都是 {@code UNVERIFIED / PENDING}，登记人都写入审计。
     *
     * <p><b>⚠️ 逐条成败、整体不回滚</b>：一行失败（缺 ISBN/DOI、
     * 内容哈希格式非法、DB 约束冲突等）只影响该行，其余行照常入库。
     * 响应逐条给出成功/失败与原因，前端据此逐行展示，
     * 而不是「整批失败却说不清是哪一行」。
     *
     * <p><b>⚠️ 上限 100 条</b>：超出在 Bean Validation 之外由
     * {@code @Size(max=100)} 拦截（见 {@link ManagementDtos.BulkRegisterRequest}），
     * 这里只做构造期校验失败的逐条归类——不通过的行进入响应体的失败列表，
     * 而非让整次请求 400。
     *
     * @param tenantId 租户 ID
     * @param minTrustTierHeader 权威门槛头（批量登记不消费，仅沿用租户上下文约定）
     * @param request 批量登记请求
     * @return 逐条结果（与请求同序）
     */
    @PostMapping("/sources/batch")
    public ManagementDtos.BulkRegisterResponse bulkRegisterSources(
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantId,
            @RequestHeader(value = MIN_TRUST_TIER_HEADER, required = false) String minTrustTierHeader,
            @Valid @RequestBody ManagementDtos.BulkRegisterRequest request) {
        RagTenantContext tenant = requireTenantContext(tenantId, minTrustTierHeader);

        // ⚠️ 构造期校验：把「ISBN/DOI 至少一」「内容哈希格式」等前置错误
        //    逐条归入响应失败列表，而不是抛 400 让整批报废。
        List<SourceRegistration> valid = new java.util.ArrayList<>();
        List<ManagementDtos.BulkItemResultDto> results =
                new java.util.ArrayList<>(request.sources().size());
        List<Integer> validOriginalIndex = new java.util.ArrayList<>();

        for (int i = 0; i < request.sources().size(); i++) {
            ManagementDtos.BulkSourceItem item = request.sources().get(i);
            try {
                SourceRegistration registration = new SourceRegistration(
                        item.sourceType(), item.title(), item.publisher(),
                        item.isbn(), item.doi(), item.objectRef(), item.contentHash());
                valid.add(registration);
                validOriginalIndex.add(i);
            } catch (IllegalArgumentException e) {
                results.add(new ManagementDtos.BulkItemResultDto(i, false, null, e.getMessage()));
            }
        }

        if (!valid.isEmpty()) {
            CorpusManagementPort.BulkRegistrationResult portResult =
                    managementPort.registerSources(tenant, valid, request.registeredBy());
            // ⚠️ 端口结果按「有效列表」顺序返回，按下标映射回原始行号。
            List<CorpusManagementPort.BulkItemResult> portItems = portResult.items();
            for (int j = 0; j < portItems.size(); j++) {
                CorpusManagementPort.BulkItemResult r = portItems.get(j);
                int original = validOriginalIndex.get(j);
                results.add(new ManagementDtos.BulkItemResultDto(
                        original, r.ok(), r.sourceId(), r.error()));
            }
        }

        // ⚠️ 按下标升序，保证响应顺序与用户提交顺序一致（便于逐行核对）。
        results.sort(java.util.Comparator.comparingInt(ManagementDtos.BulkItemResultDto::index));
        int succeeded = 0;
        for (ManagementDtos.BulkItemResultDto r : results) {
            if (r.ok()) succeeded++;
        }
        return new ManagementDtos.BulkRegisterResponse(
                request.sources().size(), succeeded, results.size() - succeeded, results);
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
     * 每日检索次数（报表中心 · 每日搜索的问题数）。
     *
     * <p>⚠️ 数据来源是 {@code rag_access_audit} 的 {@code RETRIEVE} 动作：
     * 检索链路每次检索都会落审计（见 {@code JdbcTenantScopedRetrieval}）。
     * 上线前的历史检索没有审计记录，会体现为「之前的日子里搜索数为 0」——
     * 这是数据起点，不是接口缺陷。
     *
     * @param tenantId 租户 ID
     * @param days 最近天数（缺省 30，钳制在 1～90）
     * @return 连续日期的每日检索次数
     */
    @GetMapping("/statistics/daily-searches")
    public ManagementDtos.DailySearchResponse dailySearches(
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantId,
            @RequestHeader(value = MIN_TRUST_TIER_HEADER, required = false) String minTrustTierHeader,
            @RequestParam(value = "days", defaultValue = "30") int days) {
        RagTenantContext tenant = requireTenantContext(tenantId, minTrustTierHeader);
        var stats = managementPort.dailySearchStatistics(tenant, days);
        return ManagementDtos.toDailySearchResponse(stats);
    }

    /**
     * 每日导入语料量（报表中心 · 每日导入 RAG 的资料数）。
     *
     * <p>三层级分别计数：资料来源（登记了多少资料）、
     * 文档载体（导入了多少物理文件）、片段（切出了多少可检索片段）。
     *
     * @param tenantId 租户 ID
     * @param days 最近天数（缺省 30，钳制在 1～90）
     * @return 连续日期的每日导入量
     */
    @GetMapping("/statistics/daily-imports")
    public ManagementDtos.DailyImportResponse dailyImports(
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantId,
            @RequestHeader(value = MIN_TRUST_TIER_HEADER, required = false) String minTrustTierHeader,
            @RequestParam(value = "days", defaultValue = "30") int days) {
        RagTenantContext tenant = requireTenantContext(tenantId, minTrustTierHeader);
        var stats = managementPort.dailyImportStatistics(tenant, days);
        return ManagementDtos.toDailyImportResponse(stats);
    }

    /**
     * 载体级校验流转（认领 / 通过 / 拒绝 / 撤回某个物理载体）。
     *
     * <p><b>⚠️ 为什么需要载体级端点</b>（RAG-D03 §4 / RAG-D07 §2.3.1 G-0）：
     * 来源与载体是<b>两个独立轴</b>。同一版本下可能同时存在
     * 「官方电子版」（可信）与「第三方扫描版」（质量存疑）。
     * 只有来源级端点时，管理员只能「整部撤回」或「整部放过」——
     * 要么误伤正版电子版，要么放行劣质扫描件。
     *
     * <p><b>⚠️ 错误语义</b>：
     * <ul>
     *   <li>缺租户头 / 非法入参 → 400；</li>
     *   <li>非法状态转移、载体高于来源 → 409（治理规则被违反）；</li>
     *   <li>载体不存在 → 404。</li>
     * </ul>
     *
     * <p><b>⚠️ 诚实边界</b>：本端点把「载体已下架」如实落到
     * {@code rag_document.ver_status='REVOKED'} 与 {@code retrievable=false}，
     * 管理台因此能显示真实结论；但<b>检索期尚未过滤 {@code doc.retrievable}</b>
     * （RAG-D07 §8 登记的 RAG-R01 缺口），
     * 故「单独撤回某个载体」在检索侧仍可能被召回。
     * 来源级撤回不受此影响（它会把载体一并置 REVOKED，且来源级过滤本就生效）。
     *
     * @param tenantId 租户 ID
     * @param documentId 载体 ID
     * @param request 校验决定
     * @return 转移后的载体视图
     */
    @PostMapping("/documents/{documentId}/verification")
    public ManagementDtos.DocumentResponse applyDocumentVerification(
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantId,
            @RequestHeader(value = MIN_TRUST_TIER_HEADER, required = false) String minTrustTierHeader,
            @PathVariable("documentId") String documentId,
            @Valid @RequestBody ManagementDtos.DocumentVerificationRequest request) {
        RagTenantContext tenant = requireTenantContext(tenantId, minTrustTierHeader);
        // ⚠️ 路径参数先做形态校验：非 UUID 的 document_id 会被 PG 的
        //    `?::uuid` 转换拒绝，那会变成一条 500 的 SQL 错误——
        //    而它其实是调用方传参错误，应当是 400。
        requireUuidPathVariable(documentId, "documentId");
        CorpusDocument updated;
        try {
            // ⚠️ 命令构造与端口调用必须放在**同一个** try 里：
            //    「目标状态不在允许集合内」是命令构造期就拒绝的入参形态问题（400），
            //    若只包住端口调用，这个 IllegalArgumentException 会漏成 500。
            DocumentVerificationDecision decision = new DocumentVerificationDecision(
                    request.targetStatus(), request.evidenceRef(), request.decidedBy(),
                    request.verificationMethod(), request.trustTier(),
                    request.validDays() == null ? 365 : request.validDays());
            updated = managementPort.applyDocumentVerification(tenant, documentId, decision);
        } catch (IllegalArgumentException e) {
            // ⚠️ 入参形态问题（目标状态不在允许集合内等）→ 400。
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            // ⚠️ 非法状态转移 / 载体高于来源 → 409：
            //    那是**治理规则被违反**，不是服务故障。
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
        if (updated == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "载体不存在：" + documentId);
        }
        return ManagementDtos.toDocumentResponse(updated);
    }

    /**
     * 维护问题清单（缺向量 / 解析失败 / 待审核）。
     *
     * <p><b>⚠️ 计算属性一律服务端算</b>：清单里的 {@code stale}
     * 依赖校验有效期与当前时刻，前端没有租户权威门槛（G-3）上下文，
     * 自己推算出来的结论必然与服务端口径漂移。
     *
     * @param tenantId 租户 ID
     * @return 问题清单视图
     */
    @GetMapping("/issues")
    public ManagementDtos.IssuesResponse issues(
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantId,
            @RequestHeader(value = MIN_TRUST_TIER_HEADER, required = false) String minTrustTierHeader) {
        RagTenantContext tenant = requireTenantContext(tenantId, minTrustTierHeader);
        return ManagementDtos.toIssuesResponse(managementPort.listIssues(tenant));
    }

    /**
     * 列出全部字典类型（含是否可维护、各类型下的全部条目）。
     *
     * <p><b>⚠️ 两类字典一并返回</b>：动态字典（{@code SOURCE_TYPE}，可写）
     * 与派生字典（{@code TRUST_TIER} / {@code VERIFICATION_METHOD} /
     * {@code LICENSE_STATUS}，只读）共用同一响应结构，
     * 前端只要按 {@code editable} 决定是否显示「新增/删除」按钮即可，
     * 无需为两类字典写两套渲染逻辑。
     *
     * @param tenantId 租户 ID
     * @return 全部字典类型视图
     */
    @GetMapping("/dictionaries")
    public List<ManagementDtos.DictionaryTypeDto> listDictionaries(
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantId,
            @RequestHeader(value = MIN_TRUST_TIER_HEADER, required = false) String minTrustTierHeader) {
        RagTenantContext context = requireTenantContext(tenantId, minTrustTierHeader);
        List<String> types = List.of(
                DictionaryPort.TYPE_SOURCE_TYPE,
                DictionaryPort.TYPE_TRUST_TIER,
                DictionaryPort.TYPE_VERIFICATION_METHOD,
                DictionaryPort.TYPE_LICENSE_STATUS);
        return types.stream().map(type -> new ManagementDtos.DictionaryTypeDto(
                type, dictionaryPort.isEditable(type), toEntryDtos(context, type))).toList();
    }

    /**
     * 列出某字典类型的全部条目。
     *
     * @param tenantId 租户 ID
     * @param type 字典类型
     * @return 该类型下的条目（动态类型按 sort_order 升序）
     */
    @GetMapping("/dictionaries/{type}")
    public List<ManagementDtos.DictionaryEntryDto> listDictionaryEntries(
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantId,
            @RequestHeader(value = MIN_TRUST_TIER_HEADER, required = false) String minTrustTierHeader,
            @PathVariable("type") String type) {
        RagTenantContext context = requireTenantContext(tenantId, minTrustTierHeader);
        requireNonBlankType(type);
        return toEntryDtos(context, type);
    }

    /**
     * 写入 / 更新某字典类型下的一项（仅可编辑类型生效）。
     *
     * <p><b>⚠️ 只对 {@code SOURCE_TYPE} 这类可编辑类型开放</b>：
     * 派生字典（权威等级 / 校验手段 / 版权状态）由后端按 Java 枚举派生，
     * 参与准入与版权判定，不允许前端改写，否则会漂移治理语义。
     * 对不可编辑类型调用本端点 → 返回 409（治理规则被违反）。
     *
     * @param tenantId 租户 ID
     * @param type 字典类型
     * @param request 字典项请求（code 为幂等键）
     * @return 写入后的条目
     */
    @PostMapping("/dictionaries/{type}/entries")
    public ManagementDtos.DictionaryEntryDto upsertDictionaryEntry(
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantId,
            @RequestHeader(value = MIN_TRUST_TIER_HEADER, required = false) String minTrustTierHeader,
            @PathVariable("type") String type,
            @Valid @RequestBody ManagementDtos.UpsertDictionaryEntryRequest request) {
        RagTenantContext context = requireTenantContext(tenantId, minTrustTierHeader);
        requireNonBlankType(type);
        if (!dictionaryPort.isEditable(type)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "字典类型不可维护（派生字典只读）：" + type);
        }
        DictionaryPort.DictEntry entry = new DictionaryPort.DictEntry(
                type, request.code(), request.label(), request.sortOrder(), request.enabled());
        dictionaryPort.upsertEntry(context, entry);
        return new ManagementDtos.DictionaryEntryDto(
                entry.type(), entry.code(), entry.label(), entry.sortOrder(), entry.enabled());
    }

    /**
     * 删除某字典类型下的一项（仅可编辑类型生效）。
     *
     * @param tenantId 租户 ID
     * @param type 字典类型
     * @param code 字典项编码
     */
    @DeleteMapping("/dictionaries/{type}/entries/{code}")
    public void deleteDictionaryEntry(
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantId,
            @RequestHeader(value = MIN_TRUST_TIER_HEADER, required = false) String minTrustTierHeader,
            @PathVariable("type") String type,
            @PathVariable("code") String code) {
        RagTenantContext context = requireTenantContext(tenantId, minTrustTierHeader);
        requireNonBlankType(type);
        if (!dictionaryPort.isEditable(type)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "字典类型不可维护（派生字典只读）：" + type);
        }
        dictionaryPort.deleteEntry(context, type, code);
    }

    /**
     * 字典条目 → 响应 DTO。
     *
     * @param context 租户上下文
     * @param type 字典类型
     * @return 响应条目列表
     */
    private List<ManagementDtos.DictionaryEntryDto> toEntryDtos(
            RagTenantContext context, String type) {
        return dictionaryPort.entriesOf(context, type).stream()
                .map(e -> new ManagementDtos.DictionaryEntryDto(
                        e.type(), e.code(), e.label(), e.sortOrder(), e.enabled()))
                .toList();
    }

    /**
     * 校验字典类型非空（空类型在 SQL 里会变成「查所有 / 删所有」，是危险语义）。
     *
     * @param type 字典类型
     */
    private void requireNonBlankType(String type) {
        if (type == null || type.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "字典类型不能为空");
        }
    }

    /**
     * 重跑索引（补齐缺切词或缺向量的片段）。
     *
     * <p><b>⚠️ 只索引本租户</b>：租户上下文来自请求头，
     * 索引实现全程在设置了 {@code app.tenant_id} 的事务内操作。
     *
     * <p><b>⚠️ fail-closed</b>：只处理「来源已 VERIFIED」的片段，
     * 与 worker 的启动重索引<b>同一口径</b>（同一份 SQL）——
     * 未校验语料不会因为「点了重跑索引」就进索引。
     *
     * @param tenantId 租户 ID
     * @param request 重索引请求（可空 → 用默认上限）
     * @return 重索引结果
     */
    @PostMapping("/reindex")
    public ManagementDtos.ReindexResponse reindex(
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantId,
            @RequestHeader(value = MIN_TRUST_TIER_HEADER, required = false) String minTrustTierHeader,
            @Valid @RequestBody(required = false) ManagementDtos.ReindexRequest request) {
        RagTenantContext tenant = requireTenantContext(tenantId, minTrustTierHeader);
        int limit = request == null || request.limit() == null
                ? DEFAULT_REINDEX_LIMIT
                : request.limit();
        // ⚠️ 上限在这里再压一道：Bean Validation 只在**请求体存在**时生效，
        //    而 body 可空 → 默认值分支也要过同一道闸。
        if (limit <= 0 || limit > MAX_REINDEX_LIMIT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "limit 必须在 1～" + MAX_REINDEX_LIMIT + " 之间");
        }
        CorpusIndexingPort.ReindexOutcome outcome = indexingPort.reindex(tenant.tenantId(), limit);
        return new ManagementDtos.ReindexResponse(
                outcome.indexedChunks(), outcome.scannedChunks(), outcome.durationMs());
    }

    /**
     * 校验路径参数是否为合法 UUID。
     *
     * @param raw 原始路径参数
     * @param field 字段名（用于错误消息）
     */
    private void requireUuidPathVariable(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " 不能为空");
        }
        try {
            java.util.UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            // ⚠️ 不合法 UUID 若放行到 SQL，PG 的 `?::uuid` 转换会抛异常 → 500。
            //    那是调用方传参错误，必须是 400。
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " 不是合法 UUID：" + raw);
        }
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
