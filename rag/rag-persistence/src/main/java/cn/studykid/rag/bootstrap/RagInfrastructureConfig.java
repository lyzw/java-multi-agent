package cn.studykid.rag.bootstrap;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.rag.application.generation.GroundedGenerationService;
import cn.studykid.rag.application.retrieval.RetrievalOrchestrator;
import cn.studykid.rag.infrastructure.embedding.EmbeddingPorts;
import cn.studykid.rag.infrastructure.embedding.LocalDeterministicEmbeddingPort;
import cn.studykid.rag.infrastructure.generation.OpenAiCompatibleGenerationAdapter;
import cn.studykid.rag.infrastructure.indexing.ChunkIndexer;
import cn.studykid.rag.infrastructure.modelcatalog.ModelCatalogClient;
import cn.studykid.rag.infrastructure.ops.RagOpsSessionPort;
import cn.studykid.rag.infrastructure.persistence.JdbcCorpusRepository;
import cn.studykid.rag.infrastructure.persistence.JdbcRetrievalRepository;
import cn.studykid.rag.infrastructure.persistence.JdbcTenantScopedGeneration;
import cn.studykid.rag.infrastructure.persistence.JdbcTenantScopedRetrieval;
import cn.studykid.rag.infrastructure.persistence.VectorColumn;
import cn.studykid.rag.ports.CorpusRepository;
import cn.studykid.rag.ports.EmbeddingPort;
import cn.studykid.rag.ports.GenerationModelPort;
import cn.studykid.rag.ports.RetrievalPort;
import cn.studykid.rag.ports.TenantScopedGeneration;
import cn.studykid.rag.ports.TenantScopedRetrieval;

/**
 * RAG 检索链路装配（RAG-D09 G2：可部署检索制品）。
 *
 * <p>仅装配<b>检索与生成</b>所需 bean（G2 范围是「可部署检索制品」，对应 RAG-D09 G2 条件②）：
 * {@link EmbeddingPort} → {@link RetrievalPort} → {@link RetrievalOrchestrator}；
 * 生成侧（B5）复用同一个 {@link RetrievalPort} 的 {@code loadByIds}，
 * 经 {@link GroundedGenerationService} 装配。
 *
 * <p><b>⚠️ 写入侧的两处例外</b>：本启动器还装配了
 * {@link CorpusRepository} 与 {@link ChunkIndexer}。
 * 原因不是「检索需要写库」，而是<b>管理面</b>新增了两个维护端点
 * ——「重跑索引」需要补齐切词与向量。
 * 实现下沉在 {@code rag-infrastructure}，与 rag-worker 共用同一份口径；
 * 本类只负责<b>装配</b>（选哪个嵌入模型、哪个事务模板、每页多少条）。
 * 解析器与入库流水线（{@code IngestionPipeline} / 文档解析器）
 * 仍由 rag-worker 负责，不在本启动器的职责内。
 *
 * <p>{@link JdbcTemplate} 由 Spring Boot 基于 {@code spring.datasource.*} 自动装配，
 * 此处仅消费。
 *
 * <p>⚠️ 诚实边界：{@link EmbeddingPort} 的实现<b>由配置决定</b>（见 {@link EmbeddingPorts}）——
 * 配了 {@code rag.embedding.base-url} 走真实模型；没配则退回本地确定性占位
 * （{@link LocalDeterministicEmbeddingPort}）。占位向量<b>仅</b>用于链路验证，
 * <b>不可</b>作为语义相关性结论（与生产「语义相关性判定」之间隔着 RAG-D08 评测集标定）。
 */
@Configuration
public class RagInfrastructureConfig {

    private static final Logger log = LoggerFactory.getLogger(RagInfrastructureConfig.class);

    /**
     * 分数门禁阈值：低于此值的命中判为「未找到依据」（RAG-D05 §⑦）。
     *
     * <p>兜底值 0.38 与 {@code application.yml} 同源，标定依据见那里的注释（RAG-D08 §4）。
     */
    @Value("${rag.retrieval.min-score:0.38}")
    private double minScore;

    /**
     * 重索引每页扫描的片段数（键集分页）。
     *
     * <p>⚠️ 与 worker 共用同一属性名（{@code rag.indexer.batch-size}）：
     * 两处跑的是<b>同一份</b> {@link ChunkIndexer} 实现，
     * 批大小不同只会影响翻页次数，不影响口径——但统一配置可避免
     * 「管理面与 worker 的重索引行为不一致」这种无法解释的差异。
     */
    @Value("${rag.indexer.batch-size:200}")
    private int indexerBatchSize;

    /**
     * 占位端口显式启用开关（评审 P0-6，缺省 {@code false} = fail-closed）。
     * 与 {@code LocalRagPortsConfig} 共用同一属性：三处占位实现
     * （嵌入/解析/蕴含）要么全部显式放行，要么全部拒绝装配，不存在半开状态。
     */
    @Value("${rag.local-ports.enabled:false}")
    private boolean localPortsEnabled;

    @Value("${rag.embedding.base-url:}")
    private String embeddingBaseUrl;

    @Value("${rag.embedding.api-key:}")
    private String embeddingApiKey;

    @Value("${rag.embedding.model:}")
    private String embeddingModel;

    /** ⚠️ 必须与 {@code rag_chunk_embedding.embedding} 列的维度一致，见 {@link EmbeddingPorts}。 */
    @Value("${rag.embedding.dimension:1024}")
    private int embeddingDimension;

    @Value("${rag.embedding.batch-size:16}")
    private int embeddingBatchSize;

    @Value("${rag.embedding.timeout-seconds:120}")
    private int embeddingTimeoutSeconds;

    // ── 生成侧大模型（RAG 接地讲解的「合成」环节）────────────────────
    // ⚠️ fail-closed：默认不启用（enabled=false），此时 GroundedGenerationService 走逐字摘录；
    //    enabled=true 且 base-url/api-key/model 三者齐备才装配真实适配器，否则回落摘录（不静默编造）。
    // ⚠️ 密钥只从环境变量注入，绝不写进本文件。
    @Value("${rag.generation.model.enabled:false}")
    private boolean generationModelEnabled;

    @Value("${rag.generation.model.base-url:}")
    private String generationBaseUrl;

    @Value("${rag.generation.model.api-key:}")
    private String generationApiKey;

    @Value("${rag.generation.model.model:}")
    private String generationModel;

    @Value("${rag.generation.model.temperature:0.3}")
    private double generationTemperature;

    @Value("${rag.generation.model.max-tokens:1500}")
    private int generationMaxTokens;

    @Value("${rag.generation.model.timeout-seconds:60}")
    private int generationTimeoutSeconds;

    /**
     * 统一模型目录（平台侧）的 base-url；配置后 RAG 优先从目录解析 embedding 模型，
     * 否则回落到下方 yml 配置。留空 = 不启用目录（沿用历史 yml 行为，保证存量部署不变）。
     */
    @Value("${rag.model-catalog.platform-base-url:}")
    private String modelCatalogBaseUrl;

    /** 访问平台内部解析端点的共享令牌（与平台 {@code model-catalog.internal-token} 对应）。 */
    @Value("${rag.model-catalog.token:}")
    private String modelCatalogToken;

    /** 目录解析结果缓存秒数（默认 5 分钟，避免每条索引批都打平台）。 */
    @Value("${rag.model-catalog.cache-ttl-seconds:300}")
    private long modelCatalogCacheTtlSeconds;

    /**
     * 嵌入端口。
     *
     * <p>配置了 {@code rag.embedding.base-url} → 走真实模型（OpenAI 兼容 {@code /embeddings}）；
     * 未配置 → 退回本地确定性占位，此时受评审 P0-6 fail-closed 闸门约束
     * （占位向量把「二次函数」与「反二次函数」判为强相关，默认<b>拒绝装配</b>，
     * 仅本地开发时显式设置 {@code rag.local-ports.enabled=true} 放行）。
     *
     * <p>选择逻辑与 rag-worker 共用 {@link EmbeddingPorts}，保证查询向量与索引向量同源。
     */
    @Bean
    public EmbeddingPort embeddingPort() {
        // ① 优先：统一模型目录（平台侧）解析 embedding 场景模型；未配置或解析失败则回落 yml
        String baseUrl = embeddingBaseUrl;
        String model = embeddingModel;
        int dimension = embeddingDimension;
        if (!modelCatalogBaseUrl.isBlank()) {
            ModelCatalogClient client = new ModelCatalogClient(
                    modelCatalogBaseUrl, modelCatalogToken, modelCatalogCacheTtlSeconds);
            Optional<ModelCatalogClient.ResolvedEmbeddingModel> resolved =
                    client.resolveEmbeddingModel();
            if (resolved.isPresent()) {
                ModelCatalogClient.ResolvedEmbeddingModel r = resolved.get();
                // ⚠️ 模型名与维度始终取自目录（统一模型定义优先于 yml）；
                //    base_url 仅当目录明确给出时才覆盖 yml（目录 base_url 为空则沿用 yml 嵌入地址）。
                model = r.modelName();
                if (r.dimension() != null) {
                    dimension = r.dimension();
                }
                if (r.baseUrl() != null && !r.baseUrl().isBlank()) {
                    log.info("RAG 嵌入模型取自统一模型目录: provider={}, model={}, baseUrl={}",
                            r.provider(), r.modelName(), r.baseUrl());
                    baseUrl = r.baseUrl();
                } else {
                    log.info("RAG 嵌入模型名/维度取自统一模型目录（基址沿用 yml）: provider={}, model={}",
                            r.provider(), r.modelName());
                }
            }
        }
        return EmbeddingPorts.create(localPortsEnabled, baseUrl, embeddingApiKey,
                model, dimension, embeddingBatchSize, embeddingTimeoutSeconds,
                "rag-bootstrap");
    }

    /**
     * 生成侧大模型端口。
     *
     * <p>⚠️ fail-closed：{@code rag.generation.model.enabled=false}（默认）或
     * base-url/api-key/model 任一缺失 → 返回 {@code null}，
     * {@code GroundedGenerationService} 据此走逐字摘录；绝不因配置不全而编造回答。
     *
     * <p>⚠️ 仅支持 OpenAI 兼容协议（openai / deepseek / workbuddy / ollama / 本地 vLLM），
     * 与平台侧网关同源、但完全独立（RAG 自带配置，不跨进程依赖平台）。
     */
    @Bean
    public GenerationModelPort generationModelPort() {
        if (!generationModelEnabled
                || generationBaseUrl.isBlank()
                || generationApiKey.isBlank()
                || generationModel.isBlank()) {
            if (generationModelEnabled) {
                log.warn("生成模型已启用但 base-url/api-key/model 不完整，回落逐字摘录（fail-closed）");
            } else {
                log.info("生成模型未启用（rag.generation.model.enabled=false），RAG 生成走逐字摘录");
            }
            return null;
        }
        log.info("装配生成模型适配器: baseUrl={}, model={}", generationBaseUrl, generationModel);
        return new OpenAiCompatibleGenerationAdapter(generationBaseUrl, generationApiKey,
                generationModel, generationTemperature, generationMaxTokens, generationTimeoutSeconds);
    }

    /**
     * 语料持久化端口（写入侧）。
     *
     * <p>⚠️ 检索服务原本不托管它——因为「检索」确实不需要写库。
     * 但管理面新增的「重跑索引」端点需要它（补齐切词与向量），
     * 故此处补上装配。
     *
     * <p>⚠️ {@link JdbcCorpusRepository} 的租户写入自带
     * {@code set_config('app.tenant_id', ...)}，租户隔离由它保证；
     * <b>全局桶写入走另一个角色</b>——故注入 {@link RagOpsSessionPort}
     * （用 {@link ObjectProvider}：不接运维库的部署照样能启动，代价是平台档重索引 503，
     * 租户档与检索链路不受影响）。
     *
     * @param jdbcTemplate JDBC 模板
     * @param vectorColumn 向量列形态
     * @param opsSessionProvider 平台运维通道（可选装配）
     * @return 语料持久化端口
     */
    @Bean
    public CorpusRepository corpusRepository(JdbcTemplate jdbcTemplate, VectorColumn vectorColumn,
                                             ObjectProvider<RagOpsSessionPort> opsSessionProvider) {
        return new JdbcCorpusRepository(jdbcTemplate, vectorColumn,
                opsSessionProvider.getIfAvailable());
    }

    /**
     * 片段索引器（管理面「重跑索引」与 worker「启动重索引」共用同一实现）。
     *
     * <p>⚠️ 实现位于 {@code rag-infrastructure} 而不是 {@code rag-worker}：
     * 管理面需要触发重索引，而 {@code rag-management} <b>不依赖</b>
     * {@code rag-worker}。下沉实现（而非复制一份）是为了保证
     * 「哪些片段算待索引」这条 fail-closed 口径<b>只有一处</b>——
     * 两份实现一旦漂移，未校验语料就可能经由其中一条路径进索引。
     *
     * @param jdbcTemplate JDBC 模板
     * @param transactionTemplate 事务模板（租户上下文必须在事务内建立）
     * @param corpusRepository 语料持久化端口
     * @param embeddingPort 嵌入端口
     * @param opsSessionProvider 平台运维通道（全局桶索引用它；可选装配，缺失时全局档抛 503）
     * @return 片段索引器
     */
    @Bean
    public ChunkIndexer chunkIndexer(JdbcTemplate jdbcTemplate,
                                     @Qualifier("ragTransactionTemplate")
                                     TransactionTemplate transactionTemplate,
                                     CorpusRepository corpusRepository,
                                     EmbeddingPort embeddingPort,
                                     ObjectProvider<RagOpsSessionPort> opsSessionProvider) {
        return new ChunkIndexer(jdbcTemplate, transactionTemplate, corpusRepository,
                embeddingPort, indexerBatchSize, opsSessionProvider.getIfAvailable());
    }

    /**
     * 向量列形态（类型 + 维度）。
     *
     * <p>维度<b>取自 {@link EmbeddingPort#dimension()}</b>而不是另配一个属性：
     * 配置里两个「维度」迟早会漂移，而列维度与模型维度不一致是最隐蔽的故障形态
     * （稠密通道恒报错或恒零命中）。类型只能取 {@code vector} / {@code halfvec}，
     * 由白名单校验——它会被拼进 SQL 的 cast，不接受任意字符串。
     *
     * <p>⚠️ 迁列（如 {@code vector(1024) → halfvec(4096)}）之后必须改
     * {@code rag.embedding.vector-type} 并<b>全量重建索引</b>。
     */
    @Bean
    public VectorColumn vectorColumn(EmbeddingPort embeddingPort,
                                     @Value("${rag.embedding.vector-type:vector}") String type) {
        return new VectorColumn(type.trim(), embeddingPort.dimension());
    }

    /**
     * 检索仓储（评审 P0-7）：显式注入租户权威门槛（G-3）。
     *
     * <p>⚠️ 门槛是<b>部署级服务端策略</b>（{@code rag.retrieval.min-trust-tier}），
     * 默认 {@code OFFICIAL_TUTORIAL}（RAG-R07 生产下限）——
     * 绝不读调用方请求头（可伪造）。逐租户差异化待 RAG-R23 落地。
     */
    @Bean
    public RetrievalPort retrievalPort(JdbcTemplate jdbcTemplate, EmbeddingPort embeddingPort,
                                      @Value("${rag.retrieval.min-trust-tier:OFFICIAL_TUTORIAL}")
                                      String minTrustTier,
                                      VectorColumn vectorColumn) {
        return new JdbcRetrievalRepository(jdbcTemplate, embeddingPort,
                cn.studykid.rag.core.source.TrustTier.valueOf(minTrustTier.trim()), vectorColumn);
    }

    @Bean
    public RetrievalOrchestrator retrievalOrchestrator(RetrievalPort retrievalPort) {
        return new RetrievalOrchestrator(retrievalPort, minScore);
    }

    /**
     * 事务模板：RLS 租户上下文必须在事务内建立
     * （{@code set_config('app.tenant_id', ?, true)} 的 is_local 语义）。
     *
     * <p>⚠️ 用 {@code @Qualifier} 显式限定 bean 名：管理面
     * （{@code rag-management}）也定义了 {@link TransactionTemplate}，
     * 两个同类型 bean 并存时按类型注入会抛
     * {@code NoUniqueBeanDefinitionException}。
     */
    @Bean
    @Qualifier("ragTransactionTemplate")
    public TransactionTemplate ragTransactionTemplate(PlatformTransactionManager txManager) {
        return new TransactionTemplate(txManager);
    }

    /**
     * 租户作用域检索：HTTP 端点跨进程调用时主平台的事务上下文已丢失，
     * 必须在此重建租户上下文，否则 RLS 会静默过滤掉全部行（检索恒空）。
     */
    @Bean
    public TenantScopedRetrieval tenantScopedRetrieval(JdbcTemplate jdbcTemplate,
                                                       @Qualifier("ragTransactionTemplate")
                                                       TransactionTemplate ragTransactionTemplate,
                                                       RetrievalOrchestrator retrievalOrchestrator) {
        return new JdbcTenantScopedRetrieval(jdbcTemplate, ragTransactionTemplate, retrievalOrchestrator);
    }

    /**
     * 租户作用域生成（B5）。
     *
     * <p>⚠️ 与 {@link #tenantScopedRetrieval} <b>共用同一个事务模板与连接</b>：
     * 生成期的依据取回同样必须在租户事务内执行，否则 RLS 静默过滤全部行。
     *
     * <p>⚠️ 复用 {@link #retrievalPort} 而不新建端口实现：生成只需要
     * {@code RetrievalPort.loadByIds} 一个读取能力，再开一个 JDBC 类
     * 只会多一份需要同步维护的 SQL。
     */
    @Bean
    public GroundedGenerationService groundedGenerationService(RetrievalPort retrievalPort,
                                                             ObjectProvider<GenerationModelPort> generationModelProvider) {
        return new GroundedGenerationService(retrievalPort, generationModelProvider.getIfAvailable());
    }

    @Bean
    public TenantScopedGeneration tenantScopedGeneration(JdbcTemplate jdbcTemplate,
                                                         @Qualifier("ragTransactionTemplate")
                                                         TransactionTemplate ragTransactionTemplate,
                                                         GroundedGenerationService groundedGenerationService) {
        return new JdbcTenantScopedGeneration(jdbcTemplate, ragTransactionTemplate, groundedGenerationService);
    }
}
