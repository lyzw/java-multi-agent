package cn.studykid.rag.bootstrap;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import cn.studykid.rag.application.generation.GroundedGenerationService;
import cn.studykid.rag.application.retrieval.RetrievalOrchestrator;
import cn.studykid.rag.infrastructure.embedding.LocalDeterministicEmbeddingPort;
import cn.studykid.rag.infrastructure.indexing.ChunkIndexer;
import cn.studykid.rag.infrastructure.persistence.JdbcCorpusRepository;
import cn.studykid.rag.infrastructure.persistence.JdbcRetrievalRepository;
import cn.studykid.rag.infrastructure.persistence.JdbcTenantScopedGeneration;
import cn.studykid.rag.infrastructure.persistence.JdbcTenantScopedRetrieval;
import cn.studykid.rag.ports.CorpusRepository;
import cn.studykid.rag.ports.EmbeddingPort;
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
 * <p>⚠️ 诚实边界：当前 {@link EmbeddingPort} 由本地确定性占位实现
 * （{@link LocalDeterministicEmbeddingPort}）提供，<b>非</b>生产级 BGE-M3。
 * 真实嵌入模型接入前，稠密通道<b>仅</b>用于链路验证，<b>不可</b>作为语义相关性结论
 * （与生产「语义相关性判定」之间隔着 RAG-D08 评测集标定）。
 */
@Configuration
public class RagInfrastructureConfig {

    /** 分数门禁阈值：低于此值的命中判为「未找到依据」（RAG-D05 §⑦）。 */
    @Value("${rag.retrieval.min-score:0.2}")
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

    @Bean
    public EmbeddingPort embeddingPort() {
        return new LocalDeterministicEmbeddingPort();
    }

    /**
     * 语料持久化端口（写入侧）。
     *
     * <p>⚠️ 检索服务原本不托管它——因为「检索」确实不需要写库。
     * 但管理面新增的「重跑索引」端点需要它（补齐切词与向量），
     * 故此处补上装配。
     *
     * <p>⚠️ {@link JdbcCorpusRepository} 的写入方法自带 {@code @Transactional}
     * 与 {@code set_config('app.tenant_id', ...)}，租户隔离由它保证。
     *
     * @param jdbcTemplate JDBC 模板
     * @return 语料持久化端口
     */
    @Bean
    public CorpusRepository corpusRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcCorpusRepository(jdbcTemplate);
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
     * @return 片段索引器
     */
    @Bean
    public ChunkIndexer chunkIndexer(JdbcTemplate jdbcTemplate,
                                     @Qualifier("ragTransactionTemplate")
                                     TransactionTemplate transactionTemplate,
                                     CorpusRepository corpusRepository,
                                     EmbeddingPort embeddingPort) {
        return new ChunkIndexer(jdbcTemplate, transactionTemplate, corpusRepository,
                embeddingPort, indexerBatchSize);
    }

    @Bean
    public RetrievalPort retrievalPort(JdbcTemplate jdbcTemplate, EmbeddingPort embeddingPort) {
        return new JdbcRetrievalRepository(jdbcTemplate, embeddingPort);
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
    public GroundedGenerationService groundedGenerationService(RetrievalPort retrievalPort) {
        return new GroundedGenerationService(retrievalPort);
    }

    @Bean
    public TenantScopedGeneration tenantScopedGeneration(JdbcTemplate jdbcTemplate,
                                                         @Qualifier("ragTransactionTemplate")
                                                         TransactionTemplate ragTransactionTemplate,
                                                         GroundedGenerationService groundedGenerationService) {
        return new JdbcTenantScopedGeneration(jdbcTemplate, ragTransactionTemplate, groundedGenerationService);
    }
}
