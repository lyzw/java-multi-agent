package cn.studykid.rag.worker;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.rag.infrastructure.LocalRagPortsConfig;
import cn.studykid.rag.infrastructure.embedding.EmbeddingPorts;
import cn.studykid.rag.infrastructure.indexing.ChunkIndexer;
import cn.studykid.rag.infrastructure.modelcatalog.ModelCatalogClient;
import cn.studykid.rag.infrastructure.ops.RagOpsConfiguration;
import cn.studykid.rag.infrastructure.ops.RagOpsSessionPort;
import cn.studykid.rag.infrastructure.indexing.IndexGenerationBuilder;
import cn.studykid.rag.infrastructure.indexing.JdbcIndexGenerationRepository;
import cn.studykid.rag.infrastructure.persistence.JdbcCorpusRepository;
import cn.studykid.rag.infrastructure.persistence.VectorColumn;
import cn.studykid.rag.ports.CorpusRepository;
import cn.studykid.rag.ports.EmbeddingPort;
import cn.studykid.rag.ports.IndexGenerationPort;

import org.springframework.context.annotation.Import;

/**
 * Worker 装配（RAG-D09 R2-1：可部署索引制品）。
 *
 * <p>仅装配<b>入库侧</b>所需 bean（与 rag-bootstrap 的检索侧装配分离）：
 * {@link EmbeddingPort} → {@link CorpusRepository} → {@link ChunkIndexer}。
 * 扫描基包为 {@code cn.studykid.rag.worker}，<b>不</b>扫描 rag-api / rag-bootstrap，
 * 故本 Worker 不会误托管检索 REST 端点，rag-bootstrap 也不会误托管索引 bean。
 *
 * <p>⚠️ {@link ChunkIndexer} 的<b>实现</b>已下沉到 {@code rag-infrastructure}
 *   （{@code cn.studykid.rag.infrastructure.indexing}），因为管理面也需要触发重索引，
 *   而 {@code rag-management} <b>不依赖</b> {@code rag-worker}。
 *   下沉而非复制，是为了保证「哪些片段算待索引」这条 fail-closed 口径<b>只有一处</b>；
 *   本类只负责<b>装配</b>（决定 batchSize 与用哪个事务模板），不承载任何口径逻辑。
 *
 * <p>通过 {@link Import} 引入 {@link LocalRagPortsConfig}，使 Worker 上下文也能托管
 * G1/G3 的本地占位切片（解析/蕴含/入库用例/接地用例），为未来「完整入库执行器」
 * 预留装配基础——但占位实现<b>不代表</b> G1/G3 已生产就绪（仍受 A-008 阻断）。
 *
 * <p><b>⚠️ 运维通道（{@link RagOpsConfiguration}）在此显式导入</b>：
 * 本应用只扫描 {@code cn.studykid.rag.worker}，而该配置住在
 * {@code cn.studykid.rag.infrastructure.ops}——不导入就没有 {@link RagOpsSessionPort} bean，
 * 全局桶重索引会永久 503（应用照常启动、租户档照常索引，故障形态完全静默）。
 * 它同样是<b>可选</b>消费：{@code rag.ops.enabled=false} 时通道为 fail-closed 占位，
 * 只有全局档受影响。
 *
 * <p>⚠️ 诚实边界：{@link EmbeddingPort} 当前为本地确定性占位
 *   （{@code LocalDeterministicEmbeddingPort}），非生产级 BGE-M3。
 *   真实嵌入模型接入前，索引出的向量仅用于链路验证（见端口类标注）。
 */
@Configuration
@Import({LocalRagPortsConfig.class, RagOpsConfiguration.class})
public class WorkerInfraConfig {

    private static final Logger log = LoggerFactory.getLogger(WorkerInfraConfig.class);

    /**
     * 占位端口显式启用开关（评审 P0-6，缺省 {@code false} = fail-closed）。
     * 与 {@link LocalRagPortsConfig} 共用同一属性：嵌入/解析/蕴含三处占位
     * 要么全部显式放行，要么全部拒绝装配，不存在半开状态。
     */
    @org.springframework.beans.factory.annotation.Value("${rag.local-ports.enabled:false}")
    private boolean localPortsEnabled;

    @org.springframework.beans.factory.annotation.Value("${rag.embedding.base-url:}")
    private String embeddingBaseUrl;

    @org.springframework.beans.factory.annotation.Value("${rag.embedding.api-key:}")
    private String embeddingApiKey;

    @org.springframework.beans.factory.annotation.Value("${rag.embedding.model:}")
    private String embeddingModel;

    @org.springframework.beans.factory.annotation.Value("${rag.embedding.dimension:1024}")
    private int embeddingDimension;

    @org.springframework.beans.factory.annotation.Value("${rag.embedding.batch-size:16}")
    private int embeddingBatchSize;

    @org.springframework.beans.factory.annotation.Value("${rag.embedding.timeout-seconds:120}")
    private int embeddingTimeoutSeconds;

    /**
     * 统一模型目录（平台侧）的 base-url；配置后 RAG worker 优先从目录解析 embedding 模型，
     * 否则回落到下方 yml 配置。留空 = 不启用目录（沿用历史 yml 行为，保证存量部署不变）。
     *
     * <p>⚠️ 必须与 rag-bootstrap 同源：索引向量与查询向量来自不同 embedding 模型时，
     * 检索会恒低分且不报错。两侧共用同一 {@link ModelCatalogClient} 解析逻辑。
     */
    @org.springframework.beans.factory.annotation.Value("${rag.model-catalog.platform-base-url:}")
    private String modelCatalogBaseUrl;

    /** 访问平台内部解析端点的共享令牌（与平台 {@code model-catalog.internal-token} 对应）。 */
    @org.springframework.beans.factory.annotation.Value("${rag.model-catalog.token:}")
    private String modelCatalogToken;

    /** 目录解析结果缓存秒数（默认 5 分钟，避免每条索引批都打平台）。 */
    @org.springframework.beans.factory.annotation.Value("${rag.model-catalog.cache-ttl-seconds:300}")
    private long modelCatalogCacheTtlSeconds;

    /**
     * 嵌入端口。
     *
     * <p>与 rag-bootstrap <b>共用同一套解析口径</b>：优先从统一模型目录（平台侧）解析
     * embedding 场景模型；未配置目录或解析失败时回落 {@code rag.embedding.*} yml。
     * 两处必须同源——索引向量与查询向量来自不同模型时，检索会恒低分且不报错。
     */
    @Bean
    public EmbeddingPort embeddingPort() {
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
                    log.info("[RAG worker] 嵌入模型取自统一模型目录: provider={}, model={}, baseUrl={}",
                            r.provider(), r.modelName(), r.baseUrl());
                    baseUrl = r.baseUrl();
                } else {
                    log.info("[RAG worker] 嵌入模型名/维度取自统一模型目录（基址沿用 yml）: provider={}, model={}",
                            r.provider(), r.modelName());
                }
            }
        }
        return EmbeddingPorts.create(localPortsEnabled, baseUrl, embeddingApiKey,
                model, dimension, embeddingBatchSize, embeddingTimeoutSeconds,
                "rag-worker");
    }

    /**
     * 向量列形态：维度取自嵌入端口，类型由 {@code rag.embedding.vector-type} 决定。
     * 与 rag-bootstrap 同口径（同一属性名），保证索引写入与检索读取按同一列形态。
     */
    @Bean
    public VectorColumn vectorColumn(
            EmbeddingPort embeddingPort,
            @org.springframework.beans.factory.annotation.Value("${rag.embedding.vector-type:vector}")
            String type) {
        return new VectorColumn(type.trim(), embeddingPort.dimension());
    }

    /**
     * 语料持久化端口。
     *
     * <p>⚠️ {@link RagOpsSessionPort} 用 {@link ObjectProvider} 而非直接注入：
     * 未接入运维通道的部署（{@code rag.ops.enabled=false}）仍要能启动并索引租户桶，
     * 缺口的处置是全局桶写入抛 503，<b>不</b>回落到应用角色——回落的失败形态是
     * 「扫到行、写 0 行、报告成功」，比报错难发现。
     */
    @Bean
    public CorpusRepository corpusRepository(JdbcTemplate jdbcTemplate, VectorColumn vectorColumn,
                                             ObjectProvider<RagOpsSessionPort> opsSessionProvider) {
        return new JdbcCorpusRepository(jdbcTemplate, vectorColumn,
                opsSessionProvider.getIfAvailable());
    }

    @Bean
    public TransactionTemplate indexingTransactionTemplate(PlatformTransactionManager txManager) {
        return new TransactionTemplate(txManager);
    }

    /**
     * 索引代际端口（RAG-D13 §3.5.2）。
     *
     * <p>⚠️ worker 的组件扫描仅限 {@code cn.studykid.rag.worker}，
     * 而本实现位于 {@code rag-management}，故必须在此显式装配。
     *
     * @param jdbcTemplate JDBC 模板
     * @param transactionTemplate 索引事务模板
     * @return 代际端口
     */
    @Bean
    public IndexGenerationPort indexGenerationPort(
            JdbcTemplate jdbcTemplate,
            @org.springframework.beans.factory.annotation.Qualifier("indexingTransactionTemplate")
            TransactionTemplate transactionTemplate) {
        return new JdbcIndexGenerationRepository(jdbcTemplate, transactionTemplate);
    }

    /**
     * 代际构建器（异步分批推进的执行体）。
     *
     * <p>⚠️ 与 {@code ChunkIndexer} 语义不同：后者补缺向量（写 ACTIVE），
     * 前者换模型重算全部片段（写指定 BUILDING 代际）。不可混用。
     *
     * @param jdbcTemplate JDBC 模板
     * @param transactionTemplate 索引事务模板
     * @param corpusRepository 语料仓储（写向量）
     * @param embeddingPort 嵌入端口
     * @param generationPort 代际端口
     * @return 构建器
     */
    @Bean
    public IndexGenerationBuilder indexGenerationBuilder(
            JdbcTemplate jdbcTemplate,
            @org.springframework.beans.factory.annotation.Qualifier("indexingTransactionTemplate")
            TransactionTemplate transactionTemplate,
            CorpusRepository corpusRepository,
            EmbeddingPort embeddingPort,
            IndexGenerationPort generationPort,
            ObjectProvider<RagOpsSessionPort> opsSessionProvider) {
        // ⚠️ opsSession 可空：未接入运维数据源时全局桶代际构建会明确抛
        //    RagOpsUnavailableException，而不是回落应用连接（那会静默写 0 行）。
        return new IndexGenerationBuilder(
                jdbcTemplate, transactionTemplate, corpusRepository, embeddingPort,
                generationPort, IndexGenerationBuilder.DEFAULT_BATCH_SIZE,
                opsSessionProvider.getIfAvailable());
    }

    /**
     * 片段索引器。
     *
     * <p>⚠️ 这里的 {@link RagOpsSessionPort} 必须与 {@link #corpusRepository} 拿到的是
     * <b>同一个 bean</b>：扫描窗与落库窗由同一线程的会话深度计数保护，
     * 两个实例会让「在窗内」的判断与实际取连接的池脱节。
     */
    /**
     * 【V1.0.2 §3.1 ③】摄取任务端口（worker 侧：领取 / 回写 / Sweeper）。
     *
     * <p>⚠️ 与管理面共用同一实现（{@code JdbcIngestJobRepository}），
     * 口径只有一处——「怎么领、怎么回写」不复制。
     */
    @Bean
    public cn.studykid.rag.ports.IngestJobPort ingestJobPort(
            JdbcTemplate jdbcTemplate,
            @org.springframework.beans.factory.annotation.Qualifier("indexingTransactionTemplate")
            TransactionTemplate transactionTemplate,
            ObjectProvider<RagOpsSessionPort> opsSessionProvider) {
        // ⚠️ 必须传入 opsSession：平台档全局 job 的领取 / 回写 / 建单都走 BYPASSRLS 运维通道，
        //    缺它会导致这些全局方法抛 RagOpsUnavailableException（503，不回落应用角色）。
        //    未接入运维通道时传 null，全局档功能 fail-closed，不影响租户档。
        return new cn.studykid.rag.infrastructure.ingest.JdbcIngestJobRepository(
                jdbcTemplate, transactionTemplate, opsSessionProvider.getIfAvailable());
    }

    /**
     * 【V1.0.2 §3.2】摄取任务编排器。
     *
     * <p>⚠️ 解析器由 {@link LocalRagPortsConfig} 装配（S1 为占位实现，
     * S2 换 Docling 适配器时本方法<b>无需改动</b>——这正是端口隔离的意义）。
     *
     * <p>⚠️ S1 的 {@code IngestSourceView} 装配是<b>暂存实现</b>：
     * 从对象存储读字节、读版本坐标、读知识点（S2-3 已替换为真实实现
     * {@code JdbcIngestSourceLoader}）。
     * 仍待 S2-1（T-4）接入 Docling 解析器。
     */
    @Bean
    public cn.studykid.rag.application.ingest.IngestJobOrchestrator ingestJobOrchestrator(
            cn.studykid.rag.ports.IngestJobPort jobPort,
            cn.studykid.rag.application.ingest.IngestionPipeline pipeline,
            CorpusRepository corpusRepository,
            EmbeddingPort embeddingPort,
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager txManager) {
        // ⚠️ V1.0.2 S2-3：真实的来源加载器替换 S1 的 Optional.empty() 占位。
        //    它需要 JDBC（读载体/版本/知识点）与**对象读取能力**。
        //    ⚠️ 对象存储端口住在 rag-management，而 worker **不依赖**管理面
        //    （依赖方向：management → infrastructure，worker 只消费 infrastructure）。
        //    故这里以「函数」注入读取能力，由 worker 自己的桶配置构造——
        //    生产切 S3 时只换这里的读取实现，编排器与加载器零改动。
        cn.studykid.rag.infrastructure.ingest.JdbcIngestSourceLoader loader =
                new cn.studykid.rag.infrastructure.ingest.JdbcIngestSourceLoader(
                        jdbcTemplate, bucketReader(),
                        // ⚠️ 加载器读 rag_document/rag_edition 等受 RLS 约束的表，
                        //    必须在事务内设租户会话（set_config 是事务局部），
                        //    否则读 0 行 → 编排器误报「来源不存在或不可见」。
                        indexingTransactionTemplate(txManager));
        return new cn.studykid.rag.application.ingest.IngestJobOrchestrator(
                jobPort, pipeline, corpusRepository, embeddingPort, loader,
                // ⚠️ 分词实现与检索期同源（ChineseTokenizer），保证入库分词与查询分词一致。
                cn.studykid.rag.infrastructure.text.ChineseTokenizer::tokenize);
    }

    /**
     * 【G1 / RAG-D11 §1.3】平台档（全局桶）来源加载器。
     *
     * <p>镜像租户侧 {@code JdbcIngestSourceLoader}，但读取落在运维会话（{@code rag_ops}）上，
     * 谓词写死 {@code tenant_id = rag_global_tenant_id()}——应用角色对全局行静默 0 行，
     * 设租户会话也救不了（且设哨兵会被越权封堵）。
     */
    @Bean
    public cn.studykid.rag.infrastructure.ingest.JdbcGlobalIngestSourceLoader globalIngestSourceLoader(
            ObjectProvider<RagOpsSessionPort> opsSessionProvider) {
        // ⚠️ 与租户侧同源的桶读取器（同一 base-dir）：全局载体字节同样落在本地桶。
        return new cn.studykid.rag.infrastructure.ingest.JdbcGlobalIngestSourceLoader(
                bucketReader(), opsSessionProvider.getIfAvailable());
    }

    /**
     * 【G1 / RAG-D11 §1.3】平台档（全局桶）摄取编排器。
     *
     * <p>与租户侧 {@code IngestJobOrchestrator} 完全镜像，但所有落库 / 回写都走
     * <b>不带租户参数</b>的全局方法（{@code *InGlobalBucket} / {@code *Global}），
     * 由实现在运维会话上钉死 {@code rag_global_tenant_id()}。
     */
    @Bean
    public cn.studykid.rag.application.ingest.GlobalIngestJobOrchestrator globalIngestJobOrchestrator(
            cn.studykid.rag.ports.IngestJobPort jobPort,
            cn.studykid.rag.application.ingest.IngestionPipeline pipeline,
            CorpusRepository corpusRepository,
            EmbeddingPort embeddingPort,
            cn.studykid.rag.infrastructure.ingest.JdbcGlobalIngestSourceLoader globalSourceLoader) {
        return new cn.studykid.rag.application.ingest.GlobalIngestJobOrchestrator(
                jobPort, pipeline, corpusRepository, embeddingPort, globalSourceLoader,
                // ⚠️ 分词实现与租户侧 / 检索期同源（ChineseTokenizer）。
                cn.studykid.rag.infrastructure.text.ChineseTokenizer::tokenize);
    }

    /**
     * 【G1 / RAG-D11 §1.3】平台档（全局桶）摄取消费循环。
     *
     * <p>由 {@link GlobalIngestJobConsumer} 自带的 {@code @ConditionalOnProperty}
     * 控制装配（仅 {@code rag.ops.enabled=true} 时存在）。本方法只需声明 bean。
     */
    @Bean
    public cn.studykid.rag.worker.GlobalIngestJobConsumer globalIngestJobConsumer(
            cn.studykid.rag.ports.IngestJobPort jobPort,
            cn.studykid.rag.application.ingest.GlobalIngestJobOrchestrator globalOrchestrator) {
        return new cn.studykid.rag.worker.GlobalIngestJobConsumer(jobPort, globalOrchestrator);
    }

    @Bean
    public ChunkIndexer chunkIndexer(JdbcTemplate jdbcTemplate,
                                     TransactionTemplate transactionTemplate,
                                     CorpusRepository corpusRepository,
                                     EmbeddingPort embeddingPort,
                                     RagIndexerProperties properties,
                                     ObjectProvider<RagOpsSessionPort> opsSessionProvider) {
        return new ChunkIndexer(jdbcTemplate, transactionTemplate, corpusRepository,
                embeddingPort, properties.getBatchSize(), opsSessionProvider.getIfAvailable());
    }

    /**
     * 本地桶读取器（与 {@code LocalObjectStoreAdapter} 同口径的落盘根目录）。
     *
     * <p>⚠️ 为什么在 worker 侧单独实现而不复用 LocalObjectStoreAdapter：
     * 那个类在 {@code rag-management}，worker 依赖它会造成
     * 「入库执行器反向依赖管理面」的层级倒置。
     * 这里只实现「按相对键读字节」这一个动作，语义与适配器一致
     * （同样的 {@code safeResolve} 路径穿越防护），不复制整个适配器。
     *
     * <p>⚠️ 配置项与 rag-management 同名（{@code rag.corpus-bucket.base-dir} /
     * {@code RAG_CORPUS_BUCKET_BASE_DIR}），保证两边指向同一个桶——
     * 指向不同目录会表现为「上传成功但解析报对象不存在」。
     */
    private java.util.function.Function<String, byte[]> bucketReader() {
        java.nio.file.Path base = java.nio.file.Paths.get(bucketBaseDir).toAbsolutePath().normalize();
        return objectRef -> {
            if (objectRef == null || objectRef.isBlank()) {
                throw new IllegalArgumentException("objectRef 不能为空");
            }
            // ⚠️ 路径穿越防护：与 LocalObjectStoreAdapter.safeResolve 同口径。
            java.nio.file.Path target = base.resolve(objectRef).normalize();
            if (!target.startsWith(base)) {
                throw new IllegalArgumentException("非法对象定位符（路径穿越）：" + objectRef);
            }
            if (!java.nio.file.Files.isRegularFile(target)) {
                throw new IllegalStateException("对象不存在：" + objectRef);
            }
            try {
                return java.nio.file.Files.readAllBytes(target);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("读取对象失败：" + objectRef, e);
            }
        };
    }

    /** 本地桶根目录（与 rag-management 同名配置，保证指向同一个桶）。 */
    @org.springframework.beans.factory.annotation.Value("${rag.corpus-bucket.base-dir:./corpus-bucket}")
    private String bucketBaseDir;
}
