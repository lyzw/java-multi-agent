package cn.studykid.rag.management.api;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import cn.studykid.rag.core.scope.RagCorpusScope;
import cn.studykid.rag.core.security.RagCallerAttributes;
import cn.studykid.rag.core.source.SourceVerification.VerificationStatus;
import cn.studykid.rag.core.source.TrustTier;
import cn.studykid.rag.management.core.CorpusDocument;
import cn.studykid.rag.management.core.CorpusSource;
import cn.studykid.rag.management.core.DocumentVerificationDecision;
import cn.studykid.rag.management.core.RagManagementContext;
import cn.studykid.rag.management.core.RagTenantContext;
import cn.studykid.rag.management.core.SourceQuery;
import cn.studykid.rag.management.core.SourceRegistration;
import cn.studykid.rag.management.core.VerificationDecision;
import cn.studykid.rag.management.ports.CorpusIndexingPort;
import cn.studykid.rag.management.ports.CorpusManagementPort;
import cn.studykid.rag.management.ports.CorpusManagementPort.ChunkSummary;
import cn.studykid.rag.management.ports.CorpusManagementPort.DocumentImportCommand;
import cn.studykid.rag.management.ports.DictionaryPort;
import cn.studykid.rag.management.ports.ObjectStorePort;
import cn.studykid.rag.management.infrastructure.IngestDedupPrecheck;
import cn.studykid.rag.management.core.InspectionGuard;
import cn.studykid.rag.management.core.SegregationOfDuties;
import cn.studykid.rag.core.ingest.IngestDedupRules;
import cn.studykid.rag.ports.IngestJobPort;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RAG 数据管理 REST 端点（RAG-D07 §2 准入流程的管理面入口）。
 *
 * <p><b>⚠️ 租户上下文</b>：{@code TENANT} 档（缺省）与检索端点同构，
 * 必须携带 {@code X-Tenant-Id} 请求头。
 * 原因相同——管理 SQL 的租户谓词收的是<b>作用域声明的那一批行</b>，
 * 而会话变量 {@code app.tenant_id} 只在事务内有效。
 * 跨进程 HTTP 调用时若不重建租户上下文，
 * RLS 会把全部行过滤掉，表现为「管理台永远是空的」且不报错。
 *
 * <p><b>⚠️ 三档作用域（{@code X-Rag-Scope}，RAG-D11 第四刀·第一刀）</b>：
 * <ul>
 *   <li>{@code TENANT}（缺省，不传头即本档，行为与既往逐字节一致）：本租户语料，
 *       读与写都可；</li>
 *   <li>{@code PLATFORM}：平台全局语料桶（{@code tenant_id} 为全局哨兵）。
 *       <b>只读</b>，且必须持<b>平台域令牌</b>——
 *       {@code X-Tenant-Id} 此时<b>不允许</b>出现（两个作用域信号并存不选边，直接 400）；</li>
 *   <li>{@code ALL}：跨租户盘点。本刀返回 <b>501</b>，
 *       它需要独立运维数据源（第三刀），刻意<b>不</b>退化成租户档。</li>
 * </ul>
 *
 * <p><b>⚠️ 信任边界</b>：本端点不解析令牌、不自建用户体系
 * （RAG-D02 §1.1 单向调用约定）。它必须部署在主平台鉴权边界之后，
 * 不应直接暴露给终端用户——管理面包含<b>权威等级判定</b>与<b>撤回</b>能力，
 * 误暴露的代价远高于检索面。
 * 平台档的调用方身份取自 {@code RagJwtAuthFilter} 写入的 request attribute，
 * <b>不是</b>请求头：租户域令牌拿不到这个属性，因此冒充不了平台域。
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
     *
     * <p><b>⚠️ 平台全局档没有「本租户门槛」这回事（R-4 未解，第二刀不装 solved）</b>：
     * 全局语料对所有租户可见，而门槛是<b>逐租户</b>的概念，于是平台档的准入判定
     * 只能沿用<b>部署级</b>门槛（本头的缺省值）。这留了两个未解问题，
     * 明确记在这里而不是代码里：
     * <ul>
     *   <li>「对甲租户够格、对乙租户不够格」的全局语料没有表达方式——
     *       要么全放行要么全挡住，缺按租户差异化；</li>
     *   <li>平台档写侧读的是<b>调用方传的头</b>（控制台当前不传，走缺省），
     *       所以「全局语料的准入线由谁定」在契约上仍是空白。</li>
     * </ul>
     * 现状可接受的理由：门槛只影响<b>可见性判定</b>，不影响隔离；
     * 真要收口得等 platform 侧的逐租户门槛落地（RAG-R23），不在本片范围。
     */
    public static final String MIN_TRUST_TIER_HEADER = "X-Tenant-Min-Trust-Tier";

    /**
     * 语料作用域头（RAG-D11 第四刀·第一刀）：{@code PLATFORM} / {@code TENANT} / {@code ALL}。
     *
     * <p>缺省 {@code TENANT}，因此<b>不传这个头的调用方行为与本刀之前逐字节一致</b>；
     * 平台档与盘点档额外要求平台域令牌（见
     * {@link #requireManagementContext(String, String, String, String)}）。
     */
    public static final String SCOPE_HEADER = RagCorpusScope.HEADER;

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

    private static final Logger log = LoggerFactory.getLogger(RagManagementController.class);

    private final CorpusManagementPort managementPort;
    private final CorpusIndexingPort indexingPort;
    private final DictionaryPort dictionaryPort;
    private final ObjectStorePort objectStore;

    /**
     * 【V1.0.2 §4 Z3】去重预检（可为 null = 未装配；调用处显式 503 而非静默放行）。
     */
    private final IngestDedupPrecheck dedupPrecheck;

    /**
     * 【V1.0.2 §3.1 ③】摄取任务端口（可为 null = 未装配；建单失败只记日志不回滚载体）。
     */
    private final IngestJobPort ingestJobPort;

    /**
     * 【V1.0.2 §5 R2】抽检端口（可为 null = 未装配；此时抽检守卫不生效）。
     */
    private final cn.studykid.rag.management.ports.InspectionPort inspectionPort;

    /**
     * 【V1.0.2 §5 R7】别名端口（可为 null = 未装配）。
     */
    private final cn.studykid.rag.management.ports.KnowledgeAliasPort knowledgeAliasPort;

    /**
     * 文档载体允许的后缀白名单（与 {@code LocalObjectStoreAdapter.SAFE_EXT} 同口径，
     * 用于从文件名推断载体类型）。
     */
    private static final java.util.Set<String> SAFE_DOCUMENT_EXT = java.util.Set.of(
            ".pdf", ".epub", ".html", ".htm", ".txt", ".md",
            ".json", ".xml", ".png", ".jpg", ".jpeg");

    /** 文件后缀 → 载体类型（大写）的推断映射（SCAN 只能显式给出）。 */
    private static final java.util.Map<String, String> EXT_TO_CARRIER = java.util.Map.of(
            ".pdf", "PDF", ".epub", "EPUB", ".html", "HTML", ".htm", "HTML",
            ".txt", "TXT", ".md", "TXT");

    /**
     * 构造器注入。
     *
     * <p><b>⚠️ 必须显式 {@code @Autowired}</b>：本控制器历史上只有单个构造器时，
     * Spring 会**自动选中**该构造器做注入；但 47d891a 引入 {@code DictionaryPort}
     * 后，类内同时存在两个 public 构造器，Spring 不再自动选参，而是回退去找
     * <b>无参构造器</b>——类里没有无参构造器 → 启动期报错
     * {@code BeanInstantiationException: No default constructor found}
     * （底层是 {@code NoSuchMethodException: RagManagementController.<init>()}）。
     * 因此把依赖收敛到单构造器并显式 {@code @Autowired}，杜绝多构造器的二义性。
     *
     * <p><b>⚠️ 不再有「dictionaryPort 传 null」的旧 2 参构造器</b>：字典端点
     * （{@code /dictionaries*}）依赖 {@code dictionaryPort}，null 会在调用期 NPE。
     * 既然 {@link DictionaryPort} 已是 {@code RagManagementConfig} 装配的 bean，
     * 就不该保留允许其为 null 的入口——否则管理台字典块静默 500。
     */
    @Autowired
    public RagManagementController(CorpusManagementPort managementPort,
                                   CorpusIndexingPort indexingPort,
                                   DictionaryPort dictionaryPort,
                                   ObjectStorePort objectStore,
                                   @Autowired(required = false) IngestDedupPrecheck dedupPrecheck,
                                   @Autowired(required = false) IngestJobPort ingestJobPort,
                                   @Autowired(required = false)
                                   cn.studykid.rag.management.ports.InspectionPort inspectionPort,
                                   @Autowired(required = false)
                                   cn.studykid.rag.management.ports.KnowledgeAliasPort knowledgeAliasPort) {
        if (managementPort == null || indexingPort == null || dictionaryPort == null
                || objectStore == null) {
            throw new IllegalArgumentException(
                    "managementPort / indexingPort / dictionaryPort / objectStore 不能为空");
        }
        this.managementPort = managementPort;
        this.indexingPort = indexingPort;
        this.dictionaryPort = dictionaryPort;
        this.objectStore = objectStore;
        // ⚠️ 这两个可选依赖用 required=false：既有的单测/切片上下文未装配它们，
        //    强行必填会让所有既有测试无法构造控制器。**调用处已做显式失败处置**：
        //    去重预检缺失 → 503 拒绝（不静默放行）；job 端口缺失 → 只记日志（载体可留）。
        this.dedupPrecheck = dedupPrecheck;
        this.ingestJobPort = ingestJobPort;
        this.inspectionPort = inspectionPort;
        this.knowledgeAliasPort = knowledgeAliasPort;
    }

    /**
     * 向后兼容构造器（4 参）。
     *
     * <p><b>⚠️ 仅用于既有单测与切片测试</b>：去重预检与摄取任务端口在 v1.0.2 之前
     * 不存在，既有 130 项控制器测试均按 4 参构造。保留此重载以免大面积改动测试，
     * 但<b>生产装配必须走 6 参构造器</b>（否则去重预检缺失会以 503 拒绝录入）。
     *
     * @param managementPort 语料管理端口
     * @param indexingPort   重索引端口
     * @param dictionaryPort 字典端口
     * @param objectStore    对象存储端口
     */
    public RagManagementController(CorpusManagementPort managementPort,
                                   CorpusIndexingPort indexingPort,
                                   DictionaryPort dictionaryPort,
                                   ObjectStorePort objectStore) {
        this(managementPort, indexingPort, dictionaryPort, objectStore, null, null, null, null);
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
            @RequestHeader(value = SCOPE_HEADER, required = false) String scopeHeader,
            @RequestAttribute(value = RagCallerAttributes.DOMAIN_ATTRIBUTE, required = false) String callerDomain,
            @RequestAttribute(value = RagCallerAttributes.TICKET_SCOPE_ATTRIBUTE, required = false) String ticketScope,
            @Valid @RequestBody ManagementDtos.RegisterSourceRequest request) {
        RagManagementContext tenant = requireWriteContext(
                tenantId, minTrustTierHeader, scopeHeader, callerDomain, ticketScope);
        SourceRegistration registration = new SourceRegistration(
                request.sourceType(), request.title(), request.publisher(),
                request.isbn(), request.doi(), request.objectRef(), request.contentHash());
        CorpusSource source;
        try {
            source = managementPort.registerSource(tenant, registration,
                    request.registeredBy());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // ⚠️⚠️ V1.0.2 §4 Z3：DB 部分唯一索引拦下了重复来源。
            //    这是「去重的第二层防线」（第一层是应用层预检）。
            //    ⚠️ 不捕获会变成 500——把「重复录入」误报成「服务故障」，
            //    调用方会以为系统坏了而重试，反而放大问题（实测踩到）。
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    IngestDedupRules.Layer.SOURCE.errorCode()
                            + "：同 ISBN/DOI 的活跃来源已存在（并发或预检未覆盖）", e);
        }
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
            @RequestHeader(value = SCOPE_HEADER, required = false) String scopeHeader,
            @RequestAttribute(value = RagCallerAttributes.DOMAIN_ATTRIBUTE, required = false) String callerDomain,
            @RequestAttribute(value = RagCallerAttributes.TICKET_SCOPE_ATTRIBUTE, required = false) String ticketScope,
            @Valid @RequestBody ManagementDtos.BulkRegisterRequest request) {
        RagManagementContext tenant = requireWriteContext(
                tenantId, minTrustTierHeader, scopeHeader, callerDomain, ticketScope);

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
     * @param scopeHeader 语料作用域头（缺省 TENANT；PLATFORM 需平台域令牌）
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
            @RequestHeader(value = SCOPE_HEADER, required = false) String scopeHeader,
            @RequestAttribute(value = RagCallerAttributes.DOMAIN_ATTRIBUTE, required = false) String callerDomain,
            @RequestParam(value = "status", required = false) VerificationStatus status,
            @RequestParam(value = "trustTier", required = false) TrustTier trustTier,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "includeStale", defaultValue = "false") boolean includeStale,
            @RequestParam(value = "limit", defaultValue = "" + DEFAULT_LIMIT) int limit,
            @RequestParam(value = "offset", defaultValue = "0") int offset) {
        RagManagementContext ctx = requireManagementContext(
                tenantId, minTrustTierHeader, scopeHeader, callerDomain);
        SourceQuery query = new SourceQuery(status, trustTier, keyword, includeStale, limit, offset);
        List<CorpusSource> sources = managementPort.listSources(ctx, query);
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
     * @param scopeHeader 语料作用域头（缺省 TENANT；PLATFORM 需平台域令牌）
     * @param sourceId 来源 ID
     * @return 来源视图
     */
    @GetMapping("/sources/{sourceId}")
    public ManagementDtos.SourceResponse getSource(
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantId,
            @RequestHeader(value = MIN_TRUST_TIER_HEADER, required = false) String minTrustTierHeader,
            @RequestHeader(value = SCOPE_HEADER, required = false) String scopeHeader,
            @RequestAttribute(value = RagCallerAttributes.DOMAIN_ATTRIBUTE, required = false) String callerDomain,
            @PathVariable("sourceId") String sourceId) {
        RagManagementContext ctx = requireManagementContext(
                tenantId, minTrustTierHeader, scopeHeader, callerDomain);
        CorpusSource source = managementPort.findSource(ctx, sourceId);
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
            @RequestHeader(value = SCOPE_HEADER, required = false) String scopeHeader,
            @PathVariable("sourceId") String sourceId,
            @RequestAttribute(value = RagCallerAttributes.DOMAIN_ATTRIBUTE, required = false) String callerDomain,
            @RequestAttribute(value = RagCallerAttributes.TICKET_SCOPE_ATTRIBUTE, required = false) String ticketScope,
            @RequestAttribute(value = RagCallerAttributes.PRINCIPAL_ATTRIBUTE, required = false) String principalAttr,
            @RequestAttribute(value = RagCallerAttributes.ROLES_ATTRIBUTE, required = false) String rolesAttr,
            @RequestAttribute(value = RagCallerAttributes.PRINCIPAL_TYPE_ATTRIBUTE, required = false) String principalTypeAttr,
            @Valid @RequestBody ManagementDtos.VerificationRequest request) {
        RagManagementContext tenant = requireWriteContext(
                tenantId, minTrustTierHeader, scopeHeader, callerDomain, ticketScope);
        // ⚠️⚠️ V1.0.2 §5 R2 / 设计 §2.2.4：**抽检前置守卫**。
        //    签发 VERIFIED 之前必须确认该来源已通过人工抽检——这是治理底线，
        //    不是建议。漏判一次答案区即泄题（D04 §6 单点否决），
        //    因此宁可让签发失败，也不能放行未抽检的来源。
        //    ⚠️ 只拦 VERIFIED：REVOKED/IN_REVIEW/REJECTED 等其他目标状态不受影响。
        if (VerificationStatus.VERIFIED == request.targetStatus()
                && inspectionPort != null) {
            // ⚠️⚠️ 取抽检状态本身也可能失败（作用域用错、运维通道未接、库异常）。
            //    它必须与下面的判定**一起**包在 try 里转 409：
            //    实测平台全局档下这里曾抛 ISE 而外层无兜底 ⇒ HTTP 500，
            //    把「治理拒绝」呈现成「服务故障」，调用方会以为系统坏了而重试。
            //    ⚠️ 但 409 的语义是「这一步不被允许」——读取失败不等于判定失败，
            //    所以文案要区分开：读不到就必须显式说「无法确认」，不能伪装成「未通过」。
            try {
                String status = inspectionPort.inspectionStatusOf(tenant, sourceId);
                // ⚠️ 守卫抛的 IllegalStateException 必须**在控制器层**转成 409，
                //    不能依赖下方的 catch——那里捕获的是「非法状态转移」，
                //    若混用，会把「未抽检」误称为状态机问题（提示文案会误导排查方向）。
                InspectionGuard.requireInspectionPassed(
                        cn.studykid.rag.management.core.InspectionGuard.InspectionStatus.of(status),
                        sourceId);
            } catch (ResponseStatusException e) {
                throw e;
            } catch (IllegalStateException e) {
                // ⚠️ 这是 InspectionGuard 的**判定**结果（抽检未通过），不是读取失败：
                //    它自己带有「当前抽检状态=…，请先去抽检」这类可执行说明，原样透出。
                //    （此前这里挂了一句「无法确认…」的前缀，与紧随其后的精确原因自相矛盾。）
                throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
            } catch (IllegalArgumentException e) {
                // inspection_status 出现未知取值 ⇒ 数据/版本不一致，属于「读到的值不可信」。
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "无法确认该来源的抽检结论，已拒绝签发 VERIFIED（fail-closed）：" + e.getMessage(), e);
            } catch (RuntimeException e) {
                // 非治理性故障（库/通道）：同样 fail-closed，但要说清是「查不到」而非「没通过」。
                log.error("[RAG] 读取抽检状态失败 source={} scope={}",
                        sourceId, tenant.scope(), e);
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "无法读取该来源的抽检状态，已拒绝签发 VERIFIED（fail-closed）。请检查 RAG 运维通道后重试", e);
            }
        }
        // ⚠️⚠️ V1.0.2 S3-2：**审核职责分离**（治理从制度变机制）。
        //    ① 审核员 ≠ 采集员：防止自审（自审 = 泄题责任不可追溯）；
        //    ② 双人复核：VERIFIED 必须两个不同主体；
        //    ③ 角色授权：采集员不得签发审核结论。
        //    ⚠️ 主体与角色全部取自**验签通过的令牌 claim**，不取请求头——
        //       头可伪造，claim 受签名保护（与票据声明同一纪律）。
        boolean targetVerified = VerificationStatus.VERIFIED == request.targetStatus();
        if (principalAttr != null && !principalAttr.isBlank()) {
            // ⚠️ 整个职责分离块包在 try 里：任何**非治理性**异常都不能把审核动作变成 500。
            //    治理性拒绝（自审/缺复核/越权）会主动抛 ResponseStatusException，这里放行它。
            try {
                String registeredBy = managementPort.findRegisteredBy(tenant, sourceId);
                SegregationOfDuties.Decision sod = SegregationOfDuties.check(
                        new SegregationOfDuties.Actors(
                                principalAttr,
                                SegregationOfDuties.parsePrincipalType(principalTypeAttr),
                                registeredBy,
                                request.verifiedBy()),
                        targetVerified);
                if (!sod.allowed()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, sod.reason());
                }
            } catch (ResponseStatusException e) {
                throw e; // 治理性拒绝，原样上抛（409/403）
            } catch (RuntimeException e) {
                log.error("[RAG] 职责分离校验异常 source={} principal={}", sourceId, principalAttr, e);
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "职责分离校验异常：" + e.getClass().getSimpleName(), e);
            }
        }

        // ⚠️ **角色闸门放在双人复核判定之后**：
        //    SoD 的 409（自审/缺复核人）描述的是「这次签发缺了什么治理事实」，
        //    比 403（「你这个角色不够格」）更能直接告诉管理员该做什么。
        //    顺序反了的话，票据（roles 刻意为空，见 PlatformJwtTokenService）会
        //    永远先撞 403，把「缺少复核人 / 与执行人同一人」这些真实原因盖掉（实测）。
        //    ⚠️ 角色仍取自验签 claim，不取请求头。
        if (targetVerified) {
            java.util.Set<String> roleSet = new java.util.HashSet<>();
            if (rolesAttr != null && !rolesAttr.isBlank()) {
                for (String r : rolesAttr.split(",")) {
                    if (r != null && !r.isBlank()) {
                        roleSet.add(r.trim());
                    }
                }
            }
            SegregationOfDuties.Decision roleDecision =
                    SegregationOfDuties.checkRole(roleSet, true);
            if (!roleDecision.allowed()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, roleDecision.reason());
            }
        }

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
     * @param scopeHeader 语料作用域头（缺省 TENANT；PLATFORM 需平台域令牌）
     * @param sourceId 来源 ID
     * @return 文档列表
     */
    @GetMapping("/sources/{sourceId}/documents")
    public List<ManagementDtos.DocumentResponse> listDocuments(
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantId,
            @RequestHeader(value = MIN_TRUST_TIER_HEADER, required = false) String minTrustTierHeader,
            @RequestHeader(value = SCOPE_HEADER, required = false) String scopeHeader,
            @RequestAttribute(value = RagCallerAttributes.DOMAIN_ATTRIBUTE, required = false) String callerDomain,
            @PathVariable("sourceId") String sourceId) {
        RagManagementContext ctx = requireManagementContext(
                tenantId, minTrustTierHeader, scopeHeader, callerDomain);
        return ManagementDtos.toDocumentResponses(
                managementPort.listDocuments(ctx, sourceId));
    }

    /**
     * 导入物理文档载体（真实文件上传落桶，而非手填 objectRef）。
     *
     * <p><b>⚠️ 与「手填 objectRef 的登记」是两件不同的事</b>：后者只登记书目级元数据，
     * 物理文件位置由调用方自己保证；本端点由后端<b>亲自</b>把字节落桶、
     * 重算 SHA-256、再写 {@code rag_document}，用户无需理解对象存储定位符。
     *
     * <p><b>⚠️ 落桶与哈希一律服务端算</b>：{@code objectRef} 由
     * {@link ObjectStorePort#store(String, byte[])} 产出，{@code contentHash} 由
     * {@link ObjectStorePort#sha256(byte[])} 重算——<b>绝不</b>信任前端传入值
     * （防篡改／防哈希被静默替换）。前端算的 SHA-256 仅用于即时展示。
     *
     * <p><b>⚠️ 治理语义与手填登记完全一致</b>：新建载体恒为
     * {@code PENDING / PENDING / FALSE / PENDING}（待解析·待校验·不可检索·版权待定），
     * 且写入 {@code rag_access_audit}（动作 {@code INGEST}）。上传本身<b>不</b>意味着可检索，
     * 仍须走摄入（Docling + BGE-M3）与审核流程。
     *
     * <p><b>⚠️ 错误语义</b>：缺租户头 → 400；平台档无治理票据 → 403；
     * 空文件 / 无法推断载体类型 → 400；来源不存在 → 404。
     *
     * @param tenantId       租户 ID
     * @param minTrustTierHeader 权威门槛头
     * @param scopeHeader    语料作用域头（缺省 TENANT；PLATFORM 需平台域令牌 + 治理票据）
     * @param sourceId       所属来源 ID
     * @param callerDomain   令牌域（由鉴权过滤器写入的 request attribute）
     * @param ticketScope    治理票据声明的作用域（同上；缺省 null = 无票据）
     * @param principalAttr  令牌主体 ID（{@code sub} claim；优先作为操作人）
     * @param file           上传文件（二进制 part）
     * @param carrier        载体类型（可选 part；缺失时按文件后缀推断 PDF/EPUB/HTML/TXT）
     * @param registeredBy   操作人（可选 part；缺 principalAttr 时回落）
     * @return 新建载体视图（201）
     */
    @PostMapping(value = "/sources/{sourceId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ManagementDtos.DocumentResponse> importDocument(
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantId,
            @RequestHeader(value = MIN_TRUST_TIER_HEADER, required = false) String minTrustTierHeader,
            @RequestHeader(value = SCOPE_HEADER, required = false) String scopeHeader,
            @PathVariable("sourceId") String sourceId,
            @RequestAttribute(value = RagCallerAttributes.DOMAIN_ATTRIBUTE, required = false) String callerDomain,
            @RequestAttribute(value = RagCallerAttributes.TICKET_SCOPE_ATTRIBUTE, required = false) String ticketScope,
            @RequestAttribute(value = RagCallerAttributes.PRINCIPAL_ATTRIBUTE, required = false) String principalAttr,
            @RequestPart("file") MultipartFile file,
            @RequestPart(value = "carrier", required = false) String carrier,
            @RequestPart(value = "registeredBy", required = false) String registeredBy) {
        RagManagementContext context = requireWriteContext(
                tenantId, minTrustTierHeader, scopeHeader, callerDomain, ticketScope);
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "上传文件不能为空");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "读取上传文件失败", e);
        }
        if (content.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "上传文件内容为空");
        }
        // ⚠️ 先解析载体类型：无法从后缀推断且未显式给 carrier 时，
        //    在「落桶」之前就 400 失败——避免把注定无法登记的文件写进对象存储。
        String resolvedCarrier = resolveCarrier(carrier, file.getOriginalFilename());
        // 【V1.0.2 §4 Z1】魔数与后缀一致性校验：后缀白名单只能防「名字骗人」，
        //    魔数才防「内容与名字不符」（把 .exe 改名 .pdf）。
        //    ⚠️ 在落桶前校验，确保垃圾文件不产生任何存储与建行。
        assertMagicMatchesExtension(content, file.getOriginalFilename());
        // ⚠️ 服务端权威哈希 + 落桶：绝不信任前端传入的 objectRef / contentHash。
        String hash = objectStore.sha256(content);
        // 【V1.0.2 §4 Z3 / N1】三层去重预检（活跃口径，与部分唯一索引逐字同口径）。
        //    ⚠️ 预检在落桶之前：命中去重直接 400 拒收，**不产生任何落库与对象存储垃圾**。
        //    ⚠️ 预检只是快路径；并发穿透由 rag_document_tenant_content_hash_uq 等
        //       部分唯一索引兜底（见 catch DuplicateKeyException）。
        assertNotDuplicate(context, hash);
        // ⚠️ 操作人优先级：令牌主体 ID（sub claim）> registeredBy part。
        //    【V1.0.2 §5.1 硬规则 2 / P4】**删除哨兵 UUID 回落**：
        //    匿名操作人是审计黑洞——「谁导入了这份语料」这个第一问从此无解。
        //    两者都缺失时直接 401，而不是静默写一个全零 UUID。
        String principalId = principalAttr;
        if (principalId == null || principalId.isBlank()) {
            principalId = registeredBy;
        }
        if (principalId == null || principalId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "缺少操作人主体（令牌 {@code sub} 或 registeredBy），拒绝以匿名身份录入");
        }
        String objectRef = objectStore.store(
                buildLogicalKey(sourceId, file.getOriginalFilename()), content);
        String title = (file.getOriginalFilename() == null || file.getOriginalFilename().isBlank())
                ? "uploaded-" + UUID.randomUUID() : file.getOriginalFilename();
        DocumentImportCommand command = new DocumentImportCommand(
                resolvedCarrier, title, objectRef, hash, null, null, null, null, null);
        CorpusDocument doc;
        try {
            doc = managementPort.importDocument(context, sourceId, command, principalId);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // ⚠️ 并发穿透：两个请求同时通过预检，由部分唯一索引拒绝后到者。
            //    映射回与预检同一组错误码，用户看到的语义一致（不是 500）。
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    IngestDedupRules.Layer.CARRIER.errorCode()
                            + "：同一文件已被并发录入（内容哈希重复）", e);
        }
        if (doc == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "来源不存在：" + sourceId);
        }
        // 【V1.0.2 §3.1 ③ / 设计 §4.1】上传成功即建 job 并返回 jobId，前端轮询进度。
        //    ⚠️ job 建单失败不回滚已落库的载体——载体是事实，job 可补建，
        //       回滚会让「文件已在桶里但库里没有」这种更难解释的状态出现。
        String jobId = createIngestJobQuietly(context, sourceId, principalId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ManagementDtos.toDocumentResponse(doc, jobId));
    }

    /**
     * 【V1.0.2 §4 Z1】校验文件魔数与声明后缀一致。
     *
     * <p>⚠️ 现实边界：本方法只校验<b>有明确魔数</b>的少数格式（PDF/ZIP 系/PNG/JPEG），
     * 对 HTML/TXT/MD 等纯文本不做魔数断言（它们没有唯一魔数，硬判会误伤）。
     * 这不是疏漏，而是「能判就判、判不了宁可放过给后续解析器」——
     * 真正的格式失败会在 job 的 PARSING 阶段以 FAILED 暴露，代价可控。
     *
     * @param content      文件字节
     * @param originalName 原始文件名
     */
    private static void assertMagicMatchesExtension(byte[] content, String originalName) {
        if (content == null || content.length < 4) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件内容过短，无法识别格式");
        }
        String ext = deriveSafeExtension(originalName);
        boolean pdf = startsWith(content, new byte[]{0x25, 0x50, 0x44, 0x46}); // %PDF
        boolean zip = startsWith(content, new byte[]{0x50, 0x4B, 0x03, 0x04}); // PK..
        boolean png = startsWith(content, new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47});
        boolean jpeg = startsWith(content, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
        switch (ext) {
            case ".pdf" -> {
                if (!pdf) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "文件内容不是 PDF（魔数不符），请确认文件真实格式");
                }
            }
            // EPUB 是 ZIP 容器（mimetype 为首个条目），魔数应为 PK。
            case ".epub" -> {
                if (!zip) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "文件内容不是 EPUB（ZIP 容器魔数不符），请确认文件真实格式");
                }
            }
            case ".png" -> {
                if (!png) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "文件内容不是 PNG（魔数不符）");
                }
            }
            case ".jpg", ".jpeg" -> {
                if (!jpeg) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "文件内容不是 JPEG（魔数不符）");
                }
            }
            default -> {
                // HTML/TXT/MD/XML 等无唯一魔数，不做断言（见方法注释的诚实边界）。
            }
        }
    }

    private static boolean startsWith(byte[] content, byte[] magic) {
        if (content.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (content[i] != magic[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 【V1.0.2 §4 Z3】三层去重预检。
     *
     * @param context 作用域上下文
     * @param hash    服务端内容哈希
     */
    private void assertNotDuplicate(RagManagementContext context, String hash) {
        if (dedupPrecheck == null) {
            // ⚠️ 未装配时**降级放行并留痕**，而非 503 硬拒。
            //    权衡：去重是「两层防线」——应用层预检只是**快路径**，
            //    唯一性的最终保证在 DB 部分唯一索引（rag_document_tenant_content_hash_uq 等）。
            //    预检缺失时仍有一层兜底，硬拒会让「测试切片上下文 / 灰度未装配」
            //    这类合法场景完全无法录入，代价大于收益。
            //    但必须 warn：静默降级会让「以为在防重、实际没防」难以察觉。
            log.warn("[RAG] 去重预检未装配，本次录入降级为仅依赖 DB 唯一约束兜底");
            return;
        }
        // ⚠️ requireTenant() 返回的是租户上下文对象，不是字符串。
        String tenantId = context.requireTenant().tenantId();
        IngestDedupRules.DuplicateHit hit = dedupPrecheck.checkCarrier(tenantId, hash);
        if (hit != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    hit.layer().errorCode() + "：" + hit.describe());
        }
    }

    /**
     * 【V1.0.2 §3.1 ③】建摄取任务；失败只记日志不回滚载体。
     *
     * @return jobId；建单失败返回 null（前端可走重试/补建）
     */
    private String createIngestJobQuietly(RagManagementContext context, String sourceId,
                                          String principalId) {
        if (ingestJobPort == null) {
            log.warn("[RAG] 摄取任务端口未装配，来源 {} 的上传未创建 job", sourceId);
            return null;
        }
        try {
            String tenantId = context.requireTenant().tenantId();
            return ingestJobPort.createJob(tenantId, sourceId, principalId).jobId();
        } catch (RuntimeException e) {
            // ⚠️ 载体已落库，job 建单失败不应让整个上传失败：
            //    载体是事实，job 可补建；回滚会制造「文件在桶里但库里没有」的状态。
            log.error("[RAG] 来源 {} 上传后建摄取任务失败，载体已保留", sourceId, e);
            return null;
        }
    }

    /**
     * 构造落桶逻辑键（仅用于落盘路径；路径穿越防护在适配器内）。
     *
     * <p>⚠️ 平台全局档没有「本租户」概念（{@code context.requireTenant()} 会抛），
     * 故逻辑键只含 {@code <sourceId>/<uuid><ext>}，不拼租户 ID——
     * 全局语料本就落在同一个全局桶内，按 sourceId 分区即可。
     *
     * @param sourceId    来源 ID
     * @param originalName 原始文件名（用于派生扩展名）
     * @return 形如 {@code <sourceId>/<uuid>.pdf} 的逻辑键
     */
    private String buildLogicalKey(String sourceId, String originalName) {
        return sourceId + "/" + UUID.randomUUID() + deriveSafeExtension(originalName);
    }

    /**
     * 从文件名派生白名单内的小写扩展名（含点；不在白名单返回空串）。
     *
     * <p>⚠️ 与 {@code LocalObjectStoreAdapter} 的白名单同口径，但本方法<b>不</b>回落
     * {@code .bin}——无法识别的扩展名意味着「载体类型无法确定」，应当报错而非偷偷落盘。
     *
     * @param fileName 原始文件名（可为空）
     * @return 形如 {@code .pdf} 的后缀，或空串
     */
    private static String deriveSafeExtension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        String ext = fileName.substring(dot).toLowerCase();
        return SAFE_DOCUMENT_EXT.contains(ext) ? ext : "";
    }

    /**
     * 解析载体类型：优先用显式传入的 carrier，缺失则按文件后缀推断。
     *
     * @param carrier      显式 carrier part（可空）
     * @param originalName 原始文件名（用于推断）
     * @return 大写载体类型（PDF / EPUB / HTML / TXT）
     * @throws ResponseStatusException 无法推断且未显式给出 → 400
     */
    private String resolveCarrier(String carrier, String originalName) {
        if (carrier != null && !carrier.isBlank()) {
            return carrier.toUpperCase();
        }
        String ext = deriveSafeExtension(originalName);
        String mapped = EXT_TO_CARRIER.get(ext);
        if (mapped != null) {
            return mapped;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "无法从文件后缀推断载体类型，请显式提供 carrier 参数（PDF/EPUB/HTML/TXT/SCAN）");
    }

    /**
     * 列出某文档下的片段摘要。
     *
     * @param tenantId 租户 ID
     * @param scopeHeader 语料作用域头（缺省 TENANT；PLATFORM 需平台域令牌）
     * @param documentId 文档 ID
     * @param limit 条数上限
     * @return 片段摘要列表
     */
    @GetMapping("/documents/{documentId}/chunks")
    public List<ManagementDtos.ChunkResponse> listChunks(
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantId,
            @RequestHeader(value = MIN_TRUST_TIER_HEADER, required = false) String minTrustTierHeader,
            @RequestHeader(value = SCOPE_HEADER, required = false) String scopeHeader,
            @RequestAttribute(value = RagCallerAttributes.DOMAIN_ATTRIBUTE, required = false) String callerDomain,
            @PathVariable("documentId") String documentId,
            @RequestParam(value = "limit", defaultValue = "" + DEFAULT_LIMIT) int limit) {
        RagManagementContext ctx = requireManagementContext(
                tenantId, minTrustTierHeader, scopeHeader, callerDomain);
        if (limit <= 0 || limit > MAX_CHUNK_LIMIT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "limit 必须在 1～" + MAX_CHUNK_LIMIT + " 之间");
        }
        List<ChunkSummary> chunks = managementPort.listChunks(ctx, documentId, limit);
        return ManagementDtos.toChunkResponses(chunks);
    }

    /**
     * 统计租户语料与索引状况。
     *
     * @param tenantId 租户 ID
     * @param scopeHeader 语料作用域头（缺省 TENANT；PLATFORM 需平台域令牌）
     * @return 统计视图
     */
    @GetMapping("/statistics")
    public ManagementDtos.StatisticsResponse statistics(
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantId,
            @RequestHeader(value = MIN_TRUST_TIER_HEADER, required = false) String minTrustTierHeader,
            @RequestHeader(value = SCOPE_HEADER, required = false) String scopeHeader,
            @RequestAttribute(value = RagCallerAttributes.DOMAIN_ATTRIBUTE, required = false) String callerDomain) {
        RagManagementContext ctx = requireManagementContext(
                tenantId, minTrustTierHeader, scopeHeader, callerDomain);
        var stats = managementPort.statistics(ctx);
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
     * @param scopeHeader 语料作用域头（缺省 TENANT；PLATFORM 需平台域令牌）
     * @param days 最近天数（缺省 30，钳制在 1～90）
     * @return 连续日期的每日检索次数
     */
    @GetMapping("/statistics/daily-searches")
    public ManagementDtos.DailySearchResponse dailySearches(
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantId,
            @RequestHeader(value = MIN_TRUST_TIER_HEADER, required = false) String minTrustTierHeader,
            @RequestHeader(value = SCOPE_HEADER, required = false) String scopeHeader,
            @RequestAttribute(value = RagCallerAttributes.DOMAIN_ATTRIBUTE, required = false) String callerDomain,
            @RequestParam(value = "days", defaultValue = "30") int days) {
        RagManagementContext ctx = requireManagementContext(
                tenantId, minTrustTierHeader, scopeHeader, callerDomain);
        var stats = managementPort.dailySearchStatistics(ctx, days);
        return ManagementDtos.toDailySearchResponse(stats);
    }

    /**
     * 每日导入语料量（报表中心 · 每日导入 RAG 的资料数）。
     *
     * <p>三层级分别计数：资料来源（登记了多少资料）、
     * 文档载体（导入了多少物理文件）、片段（切出了多少可检索片段）。
     *
     * @param tenantId 租户 ID
     * @param scopeHeader 语料作用域头（缺省 TENANT；PLATFORM 需平台域令牌）
     * @param days 最近天数（缺省 30，钳制在 1～90）
     * @return 连续日期的每日导入量
     */
    @GetMapping("/statistics/daily-imports")
    public ManagementDtos.DailyImportResponse dailyImports(
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantId,
            @RequestHeader(value = MIN_TRUST_TIER_HEADER, required = false) String minTrustTierHeader,
            @RequestHeader(value = SCOPE_HEADER, required = false) String scopeHeader,
            @RequestAttribute(value = RagCallerAttributes.DOMAIN_ATTRIBUTE, required = false) String callerDomain,
            @RequestParam(value = "days", defaultValue = "30") int days) {
        RagManagementContext ctx = requireManagementContext(
                tenantId, minTrustTierHeader, scopeHeader, callerDomain);
        var stats = managementPort.dailyImportStatistics(ctx, days);
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
            @RequestHeader(value = SCOPE_HEADER, required = false) String scopeHeader,
            @PathVariable("documentId") String documentId,
            @RequestAttribute(value = RagCallerAttributes.DOMAIN_ATTRIBUTE, required = false) String callerDomain,
            @RequestAttribute(value = RagCallerAttributes.TICKET_SCOPE_ATTRIBUTE, required = false) String ticketScope,
            @Valid @RequestBody ManagementDtos.DocumentVerificationRequest request) {
        RagManagementContext tenant = requireWriteContext(
                tenantId, minTrustTierHeader, scopeHeader, callerDomain, ticketScope);
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
     * @param scopeHeader 语料作用域头（缺省 TENANT；PLATFORM 需平台域令牌）
     * @return 问题清单视图
     */
    @GetMapping("/issues")
    public ManagementDtos.IssuesResponse issues(
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantId,
            @RequestHeader(value = MIN_TRUST_TIER_HEADER, required = false) String minTrustTierHeader,
            @RequestHeader(value = SCOPE_HEADER, required = false) String scopeHeader,
            @RequestAttribute(value = RagCallerAttributes.DOMAIN_ATTRIBUTE, required = false) String callerDomain) {
        RagManagementContext ctx = requireManagementContext(
                tenantId, minTrustTierHeader, scopeHeader, callerDomain);
        return ManagementDtos.toIssuesResponse(managementPort.listIssues(ctx));
    }

    /**
     * 【V1.0.2 §4 Z10 / S3-5】批量导入（ZIP + manifest.csv）。
     *
     * <p><b>⚠️ 逐条独立成败</b>：清单某行有问题只影响该行，其余行照常受理——
     * 与既有 {@code POST /sources/batch} 的治理语义一致。
     * 整批仅在两处拒绝：ZIP 结构性错误（缺清单/超限/路径穿越）与清单缺必填列。
     *
     * <p><b>⚠️ 每个文件都走单文件路径的同一套校验</b>（魔数/去重/建 job），
     * 不另写一套——批量与单条一旦分叉，就会出现「单条能传、批量传不了」的割裂。
     *
     * @param tenantId 租户 ID
     * @param sourceId 目标来源 ID（同一批的文件都挂到该来源下）
     * @param busyZip  ZIP 包
     * @return 逐条明细
     */
    @PostMapping(value = "/sources/{sourceId}/documents/bulk",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BulkImportResult bulkImportDocuments(
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantId,
            @RequestHeader(value = MIN_TRUST_TIER_HEADER, required = false) String minTrustTierHeader,
            @RequestHeader(value = SCOPE_HEADER, required = false) String scopeHeader,
            @PathVariable("sourceId") String sourceId,
            @RequestAttribute(value = RagCallerAttributes.DOMAIN_ATTRIBUTE, required = false) String callerDomain,
            @RequestAttribute(value = RagCallerAttributes.TICKET_SCOPE_ATTRIBUTE, required = false) String ticketScope,
            @RequestAttribute(value = RagCallerAttributes.PRINCIPAL_ATTRIBUTE, required = false) String principalAttr,
            @RequestPart("file") MultipartFile busyZip) {
        RagManagementContext ctx = requireWriteContext(
                tenantId, minTrustTierHeader, scopeHeader, callerDomain, ticketScope);
        String principalId = principalAttr;
        if (principalId == null || principalId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "缺少操作人主体，拒绝以匿名身份批量导入");
        }
        if (busyZip == null || busyZip.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "批量包不能为空");
        }
        cn.studykid.rag.core.ingest.BulkImportRules.ParsedBatch batch;
        try {
            batch = cn.studykid.rag.core.ingest.BulkImportRules.parse(busyZip.getBytes());
        } catch (java.io.IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "读取批量包失败", e);
        } catch (IllegalArgumentException e) {
            // ⚠️ 结构性错误（缺清单/超限/路径穿越）整批拒绝：这些没法「逐条」处理。
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }

        java.util.List<BulkImportResult.Item> items = new java.util.ArrayList<>();
        int accepted = 0;
        int rejected = 0;

        // 清单解析阶段的逐行错误先入明细
        for (cn.studykid.rag.core.ingest.BulkImportRules.RowError err : batch.errors()) {
            items.add(new BulkImportResult.Item(
                    err.lineNo(), "", false, null, null, err.reason()));
            rejected++;
        }

        for (cn.studykid.rag.core.ingest.BulkImportRules.ManifestRow row : batch.rows()) {
            byte[] content = batch.files().get(row.filename());
            if (content == null) {
                // ⚠️ 清单列了但包里没有：报错而非跳过（跳过会让「少了哪一本」无从发现）。
                items.add(new BulkImportResult.Item(
                        row.lineNo(), row.filename(), false, null, null,
                        "批量包内缺少该文件"));
                rejected++;
                continue;
            }
            try {
                // ① 复用单文件路径的同一套校验（魔数 + 去重）
                assertMagicMatchesExtension(content, row.filename());
                if (dedupPrecheck != null) {
                    String hash = objectStore.sha256(content);
                    IngestDedupRules.DuplicateHit hit =
                            dedupPrecheck.checkCarrier(ctx.requireTenant().tenantId(), hash);
                    if (hit != null) {
                        items.add(new BulkImportResult.Item(row.lineNo(), row.filename(), false,
                                null, null, hit.layer().errorCode() + "：" + hit.describe()));
                        rejected++;
                        continue;
                    }
                }
                String hash = objectStore.sha256(content);
                String objectRef = objectStore.store(
                        buildLogicalKey(sourceId, row.filename()), content);
                CorpusDocument doc = managementPort.importDocument(ctx, sourceId,
                        new DocumentImportCommand(
                                resolveCarrier(null, row.filename()), row.title(),
                                objectRef, hash, null, null, null, null, null),
                        principalId);
                if (doc == null) {
                    items.add(new BulkImportResult.Item(row.lineNo(), row.filename(), false,
                            null, null, "来源不存在：" + sourceId));
                    rejected++;
                    continue;
                }
                String jobId = createIngestJobQuietly(ctx, sourceId, principalId);
                items.add(new BulkImportResult.Item(row.lineNo(), row.filename(), true,
                        doc.documentId(), jobId, null));
                accepted++;
            } catch (ResponseStatusException e) {
                // ⚠️ 单条校验失败（魔数不符等）只影响该条。
                items.add(new BulkImportResult.Item(row.lineNo(), row.filename(), false,
                        null, null, String.valueOf(e.getReason())));
                rejected++;
            } catch (RuntimeException e) {
                items.add(new BulkImportResult.Item(row.lineNo(), row.filename(), false,
                        null, null, "处理失败：" + e.getClass().getSimpleName()));
                rejected++;
            }
        }

        return new BulkImportResult(
                batch.rows().size() + batch.errors().size(), accepted, rejected,
                items, batch.extras());
    }

    /**
     * 【V1.0.2 §5 R2 / §8.5】提交一次抽检。
     *
     * <p>⚠️ 整组记录同事务写入，并回写 {@code rag_source.inspection_status}：
     * 该状态是 VERIFIED 迁移的前置守卫（{@link InspectionGuard}），
     * 记录与状态必须同时可见，否则会出现「记录 FAILED、状态 PASSED」的窗口期。
     *
     * @param tenantId 租户 ID
     * @param sourceId 来源 ID
     * @param request  抽检记录
     * @return 判定结果
     */
    @PostMapping("/sources/{sourceId}/inspection")
    public ManagementDtos.InspectionVerdictResponse submitInspection(
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantId,
            @RequestHeader(value = MIN_TRUST_TIER_HEADER, required = false) String minTrustTierHeader,
            @RequestHeader(value = SCOPE_HEADER, required = false) String scopeHeader,
            @PathVariable("sourceId") String sourceId,
            @RequestAttribute(value = RagCallerAttributes.DOMAIN_ATTRIBUTE, required = false) String callerDomain,
            @RequestAttribute(value = RagCallerAttributes.TICKET_SCOPE_ATTRIBUTE, required = false) String ticketScope,
            @RequestAttribute(value = RagCallerAttributes.PRINCIPAL_ATTRIBUTE, required = false) String principalAttr,
            @RequestBody ManagementDtos.SubmitInspectionRequest request) {
        RagManagementContext ctx = requireWriteContext(
                tenantId, minTrustTierHeader, scopeHeader, callerDomain, ticketScope);
        if (inspectionPort == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "抽检端口未装配");
        }
        String inspector = (request.inspectorId() == null || request.inspectorId().isBlank())
                ? principalAttr : request.inspectorId();
        if (inspector == null || inspector.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "缺少抽检人主体，拒绝以匿名身份记录抽检");
        }
        if (request.items() == null || request.items().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "抽检记录不能为空：未抽检任何片段不得提交结论");
        }
        List<cn.studykid.rag.management.core.InspectionRecord> records =
                new java.util.ArrayList<>();
        for (ManagementDtos.InspectionItemRequest item : request.items()) {
            cn.studykid.rag.management.core.InspectionRecord.Verdict verdict;
            try {
                verdict = cn.studykid.rag.management.core.InspectionRecord.Verdict
                        .valueOf(item.verdict() == null ? "" : item.verdict().trim());
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "非法抽检结论：" + item.verdict() + "（只允许 PASS / FAIL）");
            }
            cn.studykid.rag.management.core.InspectionRecord.ErrorType errorType = null;
            if (item.errorType() != null && !item.errorType().isBlank()) {
                try {
                    errorType = cn.studykid.rag.management.core.InspectionRecord.ErrorType
                            .valueOf(item.errorType().trim());
                } catch (IllegalArgumentException e) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "非法错误类型：" + item.errorType());
                }
            }
            try {
                records.add(new cn.studykid.rag.management.core.InspectionRecord(
                        // ⚠️ 用 queryTenantId() 而非 requireTenant()：抽检记录挂在来源所在的那一批行上，
                        //    平台全局档的租户列就是全局哨兵。用 requireTenant() 会让
                        //    「平台全局语料的抽检」直接抛 ISE → 500，而抽检正是 VERIFIED 的前置，
                        //    等于平台语料永远签不出通过（与 @PostMapping mapping 注释里承诺的
                        //    「平台全局写走运维通道」不符）。
                        UUID.randomUUID().toString(), ctx.queryTenantId(), sourceId,
                        item.chunkId(), inspector, item.expectedRole(), item.actualRole(),
                        errorType, verdict, Instant.now().toString()));
            } catch (IllegalArgumentException e) {
                // ⚠️ 记录自洽性（FAIL 必带 errorType / PASS 不得带）由领域对象保证，
                //    这里翻译成 400 而不是让它变成 500。
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
            }
        }
        cn.studykid.rag.management.core.InspectionRules.Verdict v =
                inspectionPort.submitInspection(ctx, sourceId, inspector, records);
        return new ManagementDtos.InspectionVerdictResponse(
                v.passed(), v.total(), v.failed(), v.answerLeaks(), v.accuracy(), v.reason());
    }

    /**
     * 【V1.0.2 §8.5】查询来源的抽检记录。
     *
     * @param tenantId 租户 ID
     * @param sourceId 来源 ID
     * @param limit    条数上限
     * @return 记录列表
     */
    @GetMapping("/sources/{sourceId}/inspection")
    public List<ManagementDtos.InspectionRecordResponse> listInspections(
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantId,
            @RequestHeader(value = MIN_TRUST_TIER_HEADER, required = false) String minTrustTierHeader,
            @RequestHeader(value = SCOPE_HEADER, required = false) String scopeHeader,
            @PathVariable("sourceId") String sourceId,
            @RequestAttribute(value = RagCallerAttributes.DOMAIN_ATTRIBUTE, required = false) String callerDomain,
            @RequestParam(value = "limit", required = false, defaultValue = "200") int limit) {
        RagManagementContext ctx = requireManagementContext(
                tenantId, minTrustTierHeader, scopeHeader, callerDomain);
        if (inspectionPort == null) {
            return List.of();
        }
        return inspectionPort.listBySource(ctx, sourceId, limit).stream()
                .map(r -> new ManagementDtos.InspectionRecordResponse(
                        r.recordId(), r.chunkId(), r.inspectorId(), r.expectedRole(),
                        r.actualRole(),
                        r.errorType() == null ? null : r.errorType().name(),
                        r.verdict().name(), r.createdAt()))
                .toList();
    }

    /**
     * 【V1.0.2 §5 R7 / §8.5】增补知识点别名。
     *
     * <p>⚠️ 幂等：重复提交同一别名不报错、不产生重复行。
     *
     * @param tenantId 租户 ID
     * @param request  别名请求
     * @return 实际新增条数
     */
    @PostMapping("/kp-aliases")
    public java.util.Map<String, Object> addKpAlias(
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantId,
            @RequestHeader(value = MIN_TRUST_TIER_HEADER, required = false) String minTrustTierHeader,
            @RequestHeader(value = SCOPE_HEADER, required = false) String scopeHeader,
            @RequestAttribute(value = RagCallerAttributes.DOMAIN_ATTRIBUTE, required = false) String callerDomain,
            @RequestAttribute(value = RagCallerAttributes.TICKET_SCOPE_ATTRIBUTE, required = false) String ticketScope,
            @RequestBody ManagementDtos.KnowledgeAliasRequest request) {
        RagManagementContext ctx = requireWriteContext(
                tenantId, minTrustTierHeader, scopeHeader, callerDomain, ticketScope);
        if (knowledgeAliasPort == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "别名端口未装配");
        }
        int added = knowledgeAliasPort.addAlias(ctx, request.kpId(), request.alias());
        return java.util.Map.of("added", added);
    }

    /**
     * 【V1.0.2 §8.5】列出知识点别名。
     *
     * @param tenantId 租户 ID
     * @param kpId     知识点 ID
     * @return 别名列表
     */
    @GetMapping("/kp-aliases")
    public List<String> listKpAliases(
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantId,
            @RequestHeader(value = MIN_TRUST_TIER_HEADER, required = false) String minTrustTierHeader,
            @RequestHeader(value = SCOPE_HEADER, required = false) String scopeHeader,
            @RequestAttribute(value = RagCallerAttributes.DOMAIN_ATTRIBUTE, required = false) String callerDomain,
            @RequestParam("kpId") String kpId) {
        RagManagementContext ctx = requireManagementContext(
                tenantId, minTrustTierHeader, scopeHeader, callerDomain);
        if (knowledgeAliasPort == null) {
            return List.of();
        }
        return knowledgeAliasPort.listAliases(ctx, kpId);
    }

    /**
     * 【V1.0.2 §8.5】删除知识点别名。
     *
     * <p>⚠️ 允许删除：别名是人工维护的提示词，维护错了必须能撤销。
     *
     * @param tenantId 租户 ID
     * @param kpId     知识点 ID
     * @param alias    别名
     * @return 实际删除条数
     */
    @DeleteMapping("/kp-aliases")
    public java.util.Map<String, Object> removeKpAlias(
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantId,
            @RequestHeader(value = MIN_TRUST_TIER_HEADER, required = false) String minTrustTierHeader,
            @RequestHeader(value = SCOPE_HEADER, required = false) String scopeHeader,
            @RequestAttribute(value = RagCallerAttributes.DOMAIN_ATTRIBUTE, required = false) String callerDomain,
            @RequestAttribute(value = RagCallerAttributes.TICKET_SCOPE_ATTRIBUTE, required = false) String ticketScope,
            @RequestParam("kpId") String kpId,
            @RequestParam("alias") String alias) {
        RagManagementContext ctx = requireWriteContext(
                tenantId, minTrustTierHeader, scopeHeader, callerDomain, ticketScope);
        if (knowledgeAliasPort == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "别名端口未装配");
        }
        return java.util.Map.of("removed", knowledgeAliasPort.removeAlias(ctx, kpId, alias));
    }

    /**
     * 【V1.0.2 §8.5】列出摄取任务。
     *
     * <p>⚠️ 按 {@code sourceId} 过滤（可选）；不传时返回该租户最近的任务，
     * 用于录入工作台的「我的任务列表」（S1-7 最小版）。
     *
     * @param tenantId  租户 ID
     * @param sourceId  来源 ID（可选）
     * @param limit     条数上限（1~200）
     * @return 任务列表
     */
    @GetMapping("/ingest-jobs")
    public List<ManagementDtos.IngestJobResponse> listIngestJobs(
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantId,
            @RequestHeader(value = MIN_TRUST_TIER_HEADER, required = false) String minTrustTierHeader,
            @RequestHeader(value = SCOPE_HEADER, required = false) String scopeHeader,
            @RequestAttribute(value = RagCallerAttributes.DOMAIN_ATTRIBUTE, required = false) String callerDomain,
            @RequestParam(value = "sourceId", required = false) String sourceId,
            @RequestParam(value = "limit", required = false, defaultValue = "50") int limit) {
        RagManagementContext ctx = requireManagementContext(
                tenantId, minTrustTierHeader, scopeHeader, callerDomain);
        if (ingestJobPort == null) {
            // ⚠️ 未装配时返回空列表而非 500：前端任务列表为空是「暂无任务」，
            //    比炸掉整个录入工作台更可用（与去重预检的降级策略一致）。
            log.warn("[RAG] 摄取任务端口未装配，返回空任务列表");
            return List.of();
        }
        String tid = ctx.requireTenant().tenantId();
        int capped = Math.max(1, Math.min(limit, 200));
        // ⚠️ 端口按 sourceId 查询；未指定时用该租户最近的 job（见实现注释）。
        return (sourceId == null || sourceId.isBlank()
                        ? ingestJobPort.listRecentByTenant(tid, capped)
                        : ingestJobPort.listBySource(sourceId, capped))
                .stream().map(ManagementDtos::toIngestJobResponse).toList();
    }

    /**
     * 【V1.0.2 §8.5】摄取任务详情（前端轮询进度的最小接口）。
     *
     * @param tenantId 租户 ID
     * @param jobId    任务 ID
     * @return 任务详情
     */
    @GetMapping("/ingest-jobs/{jobId}")
    public ManagementDtos.IngestJobResponse getIngestJob(
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantId,
            @RequestHeader(value = MIN_TRUST_TIER_HEADER, required = false) String minTrustTierHeader,
            @RequestHeader(value = SCOPE_HEADER, required = false) String scopeHeader,
            @RequestAttribute(value = RagCallerAttributes.DOMAIN_ATTRIBUTE, required = false) String callerDomain,
            @PathVariable("jobId") String jobId) {
        requireManagementContext(tenantId, minTrustTierHeader, scopeHeader, callerDomain);
        if (ingestJobPort == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "摄取任务端口未装配");
        }
        cn.studykid.rag.core.ingest.IngestJob job = ingestJobPort.findById(jobId);
        if (job == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "摄取任务不存在：" + jobId);
        }
        return ManagementDtos.toIngestJobResponse(job);
    }

    /**
     * 【V1.0.2 §8.5】人工重试（运维角色）。
     *
     * <p>⚠️ <b>重试 = 新建一行 job</b>（{@code attempt}+1），旧行永久留痕——
     * 绝不原地重置状态。否则「这个任务失败过几次」这个事实会被抹掉，
     * 而它恰恰是判断「文件有问题还是系统有问题」的关键。
     *
     * @param tenantId 租户 ID
     * @param jobId    原任务 ID
     * @return 新建的任务
     */
    @PostMapping("/ingest-jobs/{jobId}/retry")
    public ManagementDtos.IngestJobResponse retryIngestJob(
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantId,
            @RequestHeader(value = MIN_TRUST_TIER_HEADER, required = false) String minTrustTierHeader,
            @RequestHeader(value = SCOPE_HEADER, required = false) String scopeHeader,
            @RequestAttribute(value = RagCallerAttributes.DOMAIN_ATTRIBUTE, required = false) String callerDomain,
            @RequestAttribute(value = RagCallerAttributes.TICKET_SCOPE_ATTRIBUTE, required = false) String ticketScope,
            @RequestAttribute(value = RagCallerAttributes.PRINCIPAL_ATTRIBUTE, required = false) String principalAttr,
            @PathVariable("jobId") String jobId) {
        RagManagementContext ctx = requireWriteContext(
                tenantId, minTrustTierHeader, scopeHeader, callerDomain, ticketScope);
        if (ingestJobPort == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "摄取任务端口未装配");
        }
        cn.studykid.rag.core.ingest.IngestJob origin = ingestJobPort.findById(jobId);
        if (origin == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "摄取任务不存在：" + jobId);
        }
        if (!origin.status().isTerminal()) {
            // ⚠️ 未完成的任务不允许"重试"：那会产生两个并行处理同一载体的 job，
            //    结果是片段重复落库。要重跑请先让它结束（或等 Sweeper 接管）。
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "任务尚未结束（" + origin.status() + "），不能重试；请等待其完成或失败后再操作");
        }
        String principal = (principalAttr == null || principalAttr.isBlank())
                ? origin.createdBy() : principalAttr;
        if (principal == null || principal.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "缺少操作人主体，拒绝以匿名身份发起重试");
        }
        return ManagementDtos.toIngestJobResponse(ingestJobPort.createRetryJob(
                ctx.requireTenant().tenantId(), origin.sourceId(), principal, jobId));
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
     * @param scopeHeader 语料作用域头（缺省 TENANT；PLATFORM 需平台域令牌）
     * @return 全部字典类型视图
     */
    @GetMapping("/dictionaries")
    public List<ManagementDtos.DictionaryTypeDto> listDictionaries(
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantId,
            @RequestHeader(value = MIN_TRUST_TIER_HEADER, required = false) String minTrustTierHeader,
            @RequestHeader(value = SCOPE_HEADER, required = false) String scopeHeader,
            @RequestAttribute(value = RagCallerAttributes.DOMAIN_ATTRIBUTE, required = false) String callerDomain) {
        RagManagementContext ctx = requireManagementContext(
                tenantId, minTrustTierHeader, scopeHeader, callerDomain);
        List<String> types = List.of(
                DictionaryPort.TYPE_SOURCE_TYPE,
                DictionaryPort.TYPE_TRUST_TIER,
                DictionaryPort.TYPE_VERIFICATION_METHOD,
                DictionaryPort.TYPE_LICENSE_STATUS);
        return types.stream().map(type -> new ManagementDtos.DictionaryTypeDto(
                type, dictionaryPort.isEditable(type), toEntryDtos(ctx, type))).toList();
    }

    /**
     * 列出某字典类型的全部条目。
     *
     * @param tenantId 租户 ID
     * @param scopeHeader 语料作用域头（缺省 TENANT；PLATFORM 需平台域令牌）
     * @param type 字典类型
     * @return 该类型下的条目（动态类型按 sort_order 升序）
     */
    @GetMapping("/dictionaries/{type}")
    public List<ManagementDtos.DictionaryEntryDto> listDictionaryEntries(
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantId,
            @RequestHeader(value = MIN_TRUST_TIER_HEADER, required = false) String minTrustTierHeader,
            @RequestHeader(value = SCOPE_HEADER, required = false) String scopeHeader,
            @RequestAttribute(value = RagCallerAttributes.DOMAIN_ATTRIBUTE, required = false) String callerDomain,
            @PathVariable("type") String type) {
        RagManagementContext ctx = requireManagementContext(
                tenantId, minTrustTierHeader, scopeHeader, callerDomain);
        requireNonBlankType(type);
        return toEntryDtos(ctx, type);
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
            @RequestHeader(value = SCOPE_HEADER, required = false) String scopeHeader,
            @PathVariable("type") String type,
            @Valid @RequestBody ManagementDtos.UpsertDictionaryEntryRequest request) {
        RagTenantContext context = requireTenantContext(tenantId, minTrustTierHeader, scopeHeader);
        requireNonBlankType(type);
        if (!dictionaryPort.isEditable(type)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "字典类型不可维护（派生字典只读）：" + type);
        }
        DictionaryPort.DictEntry entry = new DictionaryPort.DictEntry(
                type, request.code(), request.label(), request.sortOrder(), request.enabled());
        dictionaryPort.upsertEntry(context, entry);
        return new ManagementDtos.DictionaryEntryDto(
                entry.type(), entry.code(), entry.label(), entry.sortOrder(), entry.enabled(),
                // 租户侧写出来的恒为租户私有项——不存在「租户发布全局项」这条路，
                // 全局项只能由平台侧通道写（rag_dictionary_write 策略不放行 NULL 租户）。
                entry.scope());
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
            @RequestHeader(value = SCOPE_HEADER, required = false) String scopeHeader,
            @PathVariable("type") String type,
            @PathVariable("code") String code) {
        RagTenantContext context = requireTenantContext(tenantId, minTrustTierHeader, scopeHeader);
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
            RagManagementContext context, String type) {
        return dictionaryPort.entriesOf(context, type).stream()
                .map(e -> new ManagementDtos.DictionaryEntryDto(
                        e.type(), e.code(), e.label(), e.sortOrder(), e.enabled(), e.scope()))
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
     * <p><b>⚠️ 这是写动作，按写侧闸门收</b>：它改的是 {@code rag_chunk.text_jieba} 与
     * {@code rag_chunk_embedding}，所以走 {@link #requireWriteContext} 而不是读侧档位解析——
     * 平台全局桶的重索引同样需要治理票据，租户域令牌与「没带票据的平台登录令牌」都不够格。
     *
     * <p><b>⚠️ 两档是两条连接</b>（第二刀 2.5）：租户档全程在设置了
     * {@code app.tenant_id} 的事务内操作；平台档整段跑在运维数据源会话里、
     * <b>不</b>设置会话租户（填哨兵会被 {@code rag_current_tenant()} 直接 RAISE）。
     * 档位到此为止，端口方法名收口桶——{@code reindexGlobalCorpus} 没有租户参数，
     * 「用平台档索引某个租户」这句调用在签名上就拼不出来。
     *
     * <p><b>⚠️ fail-closed</b>：只处理「来源与载体均已 VERIFIED、载体可检索」的片段，
     * 与 worker 的启动重索引<b>同一口径</b>（同一份 SQL 派生）——
     * 未校验语料不会因为「点了重跑索引」就进索引。
     *
     * <p><b>⚠️ {@code limit} 有两种给法</b>：请求体 {@code {"limit":n}}（前端两个调用点的用法）
     * 或查询参数 {@code ?limit=n}（运维手工 curl 的直觉用法）。
     * 此前只认请求体，查询参数会被<b>静默忽略</b>并回落到默认 {@value #DEFAULT_REINDEX_LIMIT}——
     * 调用方以为「只跑了 4 条」，实际跑了 500 条。两处同时给以<b>请求体为准</b>，
     * 因为它是带 Bean Validation 的正式契约；查询参数只作为「body 缺席时」的补充入口，
     * 且非整数要显式报 400（放行到端口层会变成不可读的 500）。
     *
     * @param tenantId 租户 ID
     * @param request 重索引请求（可空 → 用查询参数或默认上限）
     * @param limitParam 查询参数形式的 limit（可空）
     * @return 重索引结果
     */
    @PostMapping("/reindex")
    public ManagementDtos.ReindexResponse reindex(
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantId,
            @RequestHeader(value = MIN_TRUST_TIER_HEADER, required = false) String minTrustTierHeader,
            @RequestHeader(value = SCOPE_HEADER, required = false) String scopeHeader,
            @RequestAttribute(value = RagCallerAttributes.DOMAIN_ATTRIBUTE, required = false) String callerDomain,
            @RequestAttribute(value = RagCallerAttributes.TICKET_SCOPE_ATTRIBUTE, required = false) String ticketScope,
            @Valid @RequestBody(required = false) ManagementDtos.ReindexRequest request,
            @RequestParam(name = "limit", required = false) String limitParam) {
        RagManagementContext context = requireWriteContext(
                tenantId, minTrustTierHeader, scopeHeader, callerDomain, ticketScope);
        int limit = resolveReindexLimit(request, limitParam);
        // ⚠️ 上限在这里再压一道：Bean Validation 只在**请求体存在**时生效，
        //    而 body 可空 → 默认值分支也要过同一道闸。
        if (limit <= 0 || limit > MAX_REINDEX_LIMIT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "limit 必须在 1～" + MAX_REINDEX_LIMIT + " 之间");
        }
        CorpusIndexingPort.ReindexOutcome outcome = context.scope() == RagCorpusScope.PLATFORM
                ? indexingPort.reindexGlobalCorpus(limit)
                : indexingPort.reindex(context.requireTenant().tenantId(), limit);
        return new ManagementDtos.ReindexResponse(
                outcome.indexedChunks(), outcome.scannedChunks(), outcome.durationMs());
    }

    /**
     * 归并 {@code limit} 的两个来源：请求体优先，其次是查询参数，最后才是默认上限。
     *
     * @param request 重索引请求体（可空）
     * @param limitParam 查询参数 {@code ?limit=}（可空）
     * @return 生效的片段数上限
     */
    private int resolveReindexLimit(ManagementDtos.ReindexRequest request, String limitParam) {
        if (request != null && request.limit() != null) {
            return request.limit();
        }
        if (limitParam == null || limitParam.isBlank()) {
            return DEFAULT_REINDEX_LIMIT;
        }
        try {
            return Integer.parseInt(limitParam.trim());
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "查询参数 limit 必须是整数：" + limitParam);
        }
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
     * <p>⚠️ <b>归属绑定（评审 P0-2）</b>：租户域令牌（aud=agent-platform）的
     * {@code X-Tenant-Id} 已由 {@code RagJwtAuthFilter} 与令牌 {@code tenant_id}
     * claim 强制比对（不一致 → 403）；本方法保留形态校验，作为平台域令牌
     * （aud=admin-console-api，跨租户治理属设计内）路径上的最后一道形态闸门。
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
     * @param scopeHeader 作用域头（可空 → 租户档）
     * @return 归一后的租户上下文
     * @throws ResponseStatusException 作用域不是租户档 → 409（写通道只服务本租户语料）
     */
    private RagTenantContext requireTenantContext(String tenantId, String minTrustTier,
                                                  String scopeHeader) {
        RagCorpusScope scope = parseScope(scopeHeader);
        if (scope != RagCorpusScope.TENANT) {
            // ⚠️ 不是「忽略这个头」，而是明确拒绝：静默按租户档处理，
            //    会让调用方以为「带 PLATFORM 头登记的那条已经进了全局桶」，
            //    实际落进了请求头里那个租户——两种解释差一个租户的数据归属。
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "语料写通道只接受 " + RagCorpusScope.TENANT + " 作用域，当前 " + scope
                            + "。平台全局语料的写入需独立运维数据源（RAG-D11 第四刀·第二刀）");
        }
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
     * 校验并归一<b>读侧作用域上下文</b>（RAG-D11 第四刀·第一刀）。
     *
     * <p><b>⚠️ 作用域只认显式声明</b>：{@code X-Rag-Scope} 缺省为
     * {@link RagCorpusScope#TENANT}，<b>不</b>由「有没有传 {@code X-Tenant-Id}」推断。
     * 隐式推断会让「忘记传租户」这类本地配置错误静默变成一次全局查询，
     * 而既有纪律是「缺租户头就 400，绝不静默返回空」。
     *
     * <p><b>⚠️ 平台档必须是平台域令牌</b>（评审 P0-2 的延伸 / 第四刀 B1）：
     * {@code callerDomain} 取自 request attribute，只能由
     * {@code RagJwtAuthFilter} 在<b>验签通过后</b>写入——它是 header 伪造不了的证据。
     * 缺属性（鉴权逃生门）或非平台域一律 403：
     * 租户管理员不该看见「平台语料库的全量清单」，
     * 更不该从条目数反推出平台侧的采集进度。
     *
     * <p><b>⚠️ 平台档禁止同时传租户头</b>：两个作用域信号并存时按哪个算没有正确答案，
     * 所以不选——直接 400，让调用方把自己的意图说清楚。
     *
     * <p><b>⚠️ 跨租户盘点档本刀返回 501</b>：它的实现需要独立运维数据源（第三刀）。
     * 这里刻意不「退化成租户档」——那会让前端以为「全部租户的清单」已经出来了。
     *
     * @param tenantId 租户 ID 头
     * @param minTrustTier 门槛头（可空 → 用默认门槛）
     * @param scopeHeader 作用域头（可空 → 租户档）
     * @param callerDomain 令牌域（由鉴权过滤器写入的 request attribute）
     * @return 归一后的作用域上下文
     */
    private RagManagementContext requireManagementContext(String tenantId, String minTrustTier,
                                                          String scopeHeader, String callerDomain) {
        RagCorpusScope scope = parseScope(scopeHeader);
        if (scope.requiresPlatformCaller()) {
            String domain = RagCallerAttributes.normalizeDomain(callerDomain);
            if (!RagCallerAttributes.DOMAIN_PLATFORM.equals(domain)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "作用域 " + scope + " 只受理平台域令牌，当前调用方域：" + domain
                                + "（租户域令牌与鉴权逃生门都不够格）");
            }
        }
        return switch (scope) {
            case TENANT -> RagManagementContext.forTenant(
                    requireTenantContext(tenantId, minTrustTier, scopeHeader));
            case PLATFORM -> {
                if (tenantId != null && !tenantId.isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "作用域 " + RagCorpusScope.PLATFORM + " 表示平台全局语料，"
                                    + "不应同时提供 " + TENANT_HEADER + "（=" + tenantId + "）");
                }
                yield RagManagementContext.forPlatform(parseMinTrustTier(minTrustTier));
            }
            case ALL -> throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                    "跨租户盘点视图尚未开放：它需要平台运维数据源（BYPASSRLS 只读角色），"
                            + "属 RAG-D11 第四刀·第三档");
        };
    }

    /**
     * 校验并归一<b>写侧作用域上下文</b>（RAG-D11 第四刀·第二刀）。
     *
     * <p><b>⚠️ 与读侧 {@link #requireManagementContext} 的差别只有一处：{@code ALL}</b>。
     * 读侧对盘点档给 501（视图未开放），写侧对它是<b>语义错误</b>而非未实现——
     * 「同时往 N 个租户里写」不是任何一档的定义，所以它不复用 501，
     * 免得日后 A 档实现后这里被误当成「已经能写了」。
     *
     * <p><b>⚠️ 平台档写必须持治理票据</b>（第四刀 §1.2 / 红线 B1）：
     * 平台域<b>登录令牌</b>本身不够——它能进控制台不代表能改全局语料。
     * 票据由 platform 在权限闸门（{@code RESOURCE_MANAGE / rag_corpus}）之后签发，
     * 由 {@code RagJwtAuthFilter} 验签后写入 request attribute；
     * 缺该属性即 403，<b>不看</b>请求头里有没有自报票据（头是调用方可控的）。
     *
     * <p><b>⚠️ 票据档位必须与请求档位一致</b>：拿「盘点票据」来写全局语料
     * 说明契约被误用；静默按请求头走会让「票据到底授权了什么」失去意义。
     *
     * @param tenantId 租户 ID 头
     * @param minTrustTier 门槛头（可空 → 用默认门槛）
     * @param scopeHeader 作用域头（可空 → 租户档）
     * @param callerDomain 令牌域（由鉴权过滤器写入的 request attribute）
     * @param ticketScope 治理票据声明的作用域（同上；缺省 null = 无票据）
     * @return 归一后的作用域上下文
     */
    private RagManagementContext requireWriteContext(String tenantId, String minTrustTier,
                                                     String scopeHeader, String callerDomain,
                                                     String ticketScope) {
        RagCorpusScope scope = parseScope(scopeHeader);
        if (scope == RagCorpusScope.TENANT) {
            return RagManagementContext.forTenant(
                    requireTenantContext(tenantId, minTrustTier, scopeHeader));
        }
        if (scope == RagCorpusScope.ALL) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "跨租户盘点档是只读视图，不存在「往全部租户写」这种动作");
        }
        String domain = RagCallerAttributes.normalizeDomain(callerDomain);
        if (!RagCallerAttributes.DOMAIN_PLATFORM.equals(domain)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "作用域 " + scope + " 只受理平台域令牌，当前调用方域：" + domain
                            + "（租户域令牌与鉴权逃生门都不够格）");
        }
        RagCorpusScope granted = RagCorpusScope.parseTicket(ticketScope);
        if (granted != scope) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "平台全局语料的写动作需要治理票据（scope=" + scope + "），当前票据声明："
                            + (granted == null ? "无票据（登录令牌不含 rag_scope 声明）" : granted));
        }
        if (tenantId != null && !tenantId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "作用域 " + scope + " 表示平台全局语料，不应同时提供 "
                            + TENANT_HEADER + "（=" + tenantId + "）");
        }
        return RagManagementContext.forPlatform(parseMinTrustTier(minTrustTier));
    }

    /**
     * 解析作用域头。
     *
     * <p>⚠️ 非法值报 400 而非回落缺省：传了 {@code PLATFORME} 说明契约被破坏，
     * 静默按租户档处理会让「以为在看全局、实际在看租户」完全不可见。
     *
     * @param raw 原始头值（可空 → {@link RagCorpusScope#TENANT}）
     * @return 作用域档位
     */
    private static RagCorpusScope parseScope(String raw) {
        try {
            return RagCorpusScope.parse(raw);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
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
