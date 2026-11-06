package cn.studykid.rag.management.infrastructure;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import cn.studykid.rag.infrastructure.indexing.ChunkIndexer;
import cn.studykid.rag.infrastructure.ops.RagOpsSessionPort;
import cn.studykid.rag.management.infrastructure.LocalObjectStoreAdapter;
import cn.studykid.rag.management.ports.CorpusIndexingPort;
import cn.studykid.rag.management.ports.CorpusManagementPort;
import cn.studykid.rag.management.ports.DictionaryPort;
import cn.studykid.rag.management.ports.GlobalIngestJobPort;
import cn.studykid.rag.management.ports.ObjectStorePort;
import cn.studykid.rag.infrastructure.ingest.JdbcIngestJobRepository;
import cn.studykid.rag.ports.IngestJobPort;

/**
 * 管理面装配（RAG-D07 §2 管理面入口）。
 *
 * <p>装配内容：
 * <pre>
 *   CorpusManagementPort → JdbcCorpusManagementRepository
 *   CorpusIndexingPort   → ChunkIndexerIndexingAdapter（委托给基础设施层的 ChunkIndexer）
 * </pre>
 *
 * <p><b>⚠️ 事务模板的必要性</b>：管理侧所有 SQL 都以
 * {@code rag_current_tenant()} 收口，而该函数读的是<b>会话变量</b>
 * {@code app.tenant_id}——{@code set_config(..., is_local=true)} 只在
 * <b>当前事务内</b>有效。若脱离事务（自动提交模式），变量立即失效，
 * RLS 把所有行过滤掉，表现为「管理台永远是空的」且<b>不报错</b>。
 * 因此这里复用与检索侧同构的 {@link TransactionTemplate}。
 *
 * <p><b>⚠️ 与检索侧事务模板的关系</b>：两者都从同一个
 * {@link PlatformTransactionManager} 创建，是<b>同一事务管理器</b>下的两个
 * 独立模板实例。模板本身无状态，可安全共享；此处单独定义是为了让
 * 管理面在装配上<b>自洽</b>——即使检索侧装配被裁剪，管理面仍能工作。
 */
@Configuration
public class RagManagementConfig {

    /**
     * 管理面事务模板。
     *
     * <p>⚠️ 用 {@code @Qualifier} 显式限定 bean 名：检索侧
     * （{@code RagInfrastructureConfig}）也定义了 {@link TransactionTemplate}，
     * 两个同类型 bean 并存时，按类型注入会抛
     * {@code NoUniqueBeanDefinitionException}。
     * 显式限定比依赖「参数名回退匹配」更稳——后者依赖 {@code -parameters}
     * 编译参数，一旦被移除就会静默变成歧义失败。
     *
     * @param txManager 事务管理器
     * @return 事务模板
     */
    @Bean
    @Qualifier("ragManagementTransactionTemplate")
    public TransactionTemplate ragManagementTransactionTemplate(
            PlatformTransactionManager txManager) {
        return new TransactionTemplate(txManager);
    }

    /**
     * 语料数据管理端口的 JDBC 实现。
     *
     * @param jdbcTemplate JDBC 模板
     * @param transactionTemplate 管理面事务模板
     * @return 管理端口
     */
    @Bean
    public CorpusManagementPort corpusManagementPort(
            JdbcTemplate jdbcTemplate,
            @Qualifier("ragManagementTransactionTemplate")
            TransactionTemplate transactionTemplate,
            ObjectProvider<RagOpsSessionPort> opsSessionProvider) {
        // ⚠️ 用 ObjectProvider 而不是直接注入 RagOpsSessionPort：
        //    运维通道是<b>可选装配</b>（裁剪掉 ops 配置时管理面仍要能启动、
        //    租户档仍要能写），但「缺失」绝不能变成「平台档也悄悄能跑」——
        //     repository 里对 null 的处置是抛 RagOpsUnavailableException（503）。
        //    若改成 required 注入，被裁掉 ops 的部署会在<b>启动期</b>失败，
        //    那是比「全局写不可用」大得多的影响面。
        return new JdbcCorpusManagementRepository(
                jdbcTemplate, transactionTemplate, opsSessionProvider.getIfAvailable());
    }

    /**
     * 语料重索引端口。
     *
     * <p><b>⚠️ 依赖的是基础设施层的 {@link ChunkIndexer} 实现</b>：
     * 索引逻辑只有一份（{@code rag-infrastructure}），
     * 管理面与 worker 共用——「哪些片段算待索引」这条 fail-closed 口径
     * 若有两份实现，未校验语料就可能经由其中一条路径进索引。
     *
     * <p><b>⚠️ 本类不定义 {@link ChunkIndexer} bean</b>：
     * 它需要 {@code EmbeddingPort} 与 {@code CorpusRepository}，
     * 而这两个 bean 的归属方是<b>启动装配</b>（rag-bootstrap / rag-worker），
     * 管理面只「消费」而不「生产」——
     * 否则管理面会替启动器决定用哪个嵌入模型。
     *
     * @param chunkIndexer 基础设施层的片段索引器
     * @return 重索引端口
     */
    @Bean
    public CorpusIndexingPort corpusIndexingPort(ChunkIndexer chunkIndexer) {
        return new ChunkIndexerIndexingAdapter(chunkIndexer);
    }

    /**
     * 索引代际端口的 JDBC 实现（RAG-D13 §3.5.2 蓝绿切换）。
     *
     * <p><b>⚠️ 为什么复用管理面事务模板</b>：代际表的读写同样以
     * {@code rag_current_tenant()} 收口（FORCE RLS），
     * 必须在设置了 {@code app.tenant_id} 的事务内执行。
     *
     * <p>⚠️ 本 bean 可缺席：控制器把它作为可选依赖（{@code @Autowired(required=false)}），
     * 未装配时代际端点返回 501 而非拖垮整个控制器。
     *
     * @param jdbcTemplate JDBC 模板
     * @param transactionTemplate 管理面事务模板
     * @return 索引代际端口
     */
    @Bean
    public cn.studykid.rag.ports.IndexGenerationPort indexGenerationPort(
            JdbcTemplate jdbcTemplate,
            @Qualifier("ragManagementTransactionTemplate")
            TransactionTemplate transactionTemplate) {
        return new cn.studykid.rag.infrastructure.indexing.JdbcIndexGenerationRepository(
                jdbcTemplate, transactionTemplate);
    }

    /**
     * 代际构建器（RAG-D13 §3.5.2 蓝绿切换的「绿」）。
     *
     * <p>⚠️ 依赖 {@link cn.studykid.rag.ports.EmbeddingPort} 与
     * {@link cn.studykid.rag.ports.CorpusRepository}：前者由启动装配方决定用哪个模型，
     * 管理面只消费不生产（与 {@code CorpusIndexingPort} 同一纪律）。
     *
     * <p>⚠️ {@code required = false}：只读部署或单测环境可能没有这些 bean，
     * 缺席时代际端点返回 501，而不是让整个配置类装配失败。
     *
     * @param jdbcTemplate JDBC 模板
     * @param transactionTemplate 管理面事务模板
     * @param corpusRepository 语料仓储（写向量）
     * @param embeddingPort 嵌入端口（新模型）
     * @param generationPort 代际端口（更新进度）
     * @return 构建器
     */
    @Bean
    @ConditionalOnBean({cn.studykid.rag.ports.EmbeddingPort.class,
            cn.studykid.rag.ports.CorpusRepository.class})
    public cn.studykid.rag.infrastructure.indexing.IndexGenerationBuilder indexGenerationBuilder(
            JdbcTemplate jdbcTemplate,
            @Qualifier("ragManagementTransactionTemplate")
            TransactionTemplate transactionTemplate,
            cn.studykid.rag.ports.CorpusRepository corpusRepository,
            cn.studykid.rag.ports.EmbeddingPort embeddingPort,
            cn.studykid.rag.ports.IndexGenerationPort generationPort,
            org.springframework.beans.factory.ObjectProvider<RagOpsSessionPort> opsSessionProvider) {
        // ⚠️ 运维通道可缺席（rag.ops.enabled=false）：此时全局桶代际构建会明确抛
        //    RagOpsUnavailableException，不回落应用连接——回落的失败形态是
        //    「扫到行、写 0 行、报告成功」，比报错难发现。
        return new cn.studykid.rag.infrastructure.indexing.IndexGenerationBuilder(
                jdbcTemplate, transactionTemplate, corpusRepository, embeddingPort,
                generationPort,
                cn.studykid.rag.infrastructure.indexing.IndexGenerationBuilder.DEFAULT_BATCH_SIZE,
                opsSessionProvider.getIfAvailable());
    }

    /**
     * 上传前解析预览服务（RAG-D15 §六 待办 #6）。
     *
     * <p>⚠️ 依赖 {@link cn.studykid.rag.application.ingest.IngestionPipeline}：
     * 它由 {@code LocalRagPortsConfig} 装配（需要解析器）。
     * 解析器未接入时本 bean 缺席，预览端点返回 501，**不影响上传本身**。
     *
     * <p>⚠️ 本 bean 不走 {@code JdbcIngestSourceLoader}：
     * 那个加载器需要"按 objectRef 读字节"的能力（由各模块自行注入），
     * 而预览只读版本坐标与知识点——把读字节的依赖拉进来会让 bootstrap 起不来。
     *
     * @param pipeline 入库流水线（纯函数，不落库）
     * @param jdbcTemplate JDBC 模板
     * @param transactionTemplate 管理面事务模板
     * @return 预览服务
     */
    @Bean
    @ConditionalOnBean(cn.studykid.rag.application.ingest.IngestionPipeline.class)
    public cn.studykid.rag.infrastructure.ingest.DocumentPreviewService documentPreviewService(
            cn.studykid.rag.application.ingest.IngestionPipeline pipeline,
            JdbcTemplate jdbcTemplate,
            @Qualifier("ragManagementTransactionTemplate")
            TransactionTemplate transactionTemplate) {
        return new cn.studykid.rag.infrastructure.ingest.DocumentPreviewService(
                pipeline, jdbcTemplate, transactionTemplate);
    }

    /**
     * 字典端口的 JDBC 实现（Point 2：枚举下拉的数据源）。
     *
     * <p><b>⚠️ 复用管理面事务模板</b>：动态字典的读写同样以
     * {@code rag_current_tenant()} 收口，必须在设置了 {@code app.tenant_id} 的
     * 事务内执行——与 {@link CorpusManagementPort} 同一把锁，
     * 故共用 {@code ragManagementTransactionTemplate}。
     *
     * @param jdbcTemplate JDBC 模板
     * @param transactionTemplate 管理面事务模板
     * @return 字典端口
     */
    @Bean
    public DictionaryPort dictionaryPort(
            JdbcTemplate jdbcTemplate,
            @Qualifier("ragManagementTransactionTemplate")
            TransactionTemplate transactionTemplate) {
        return new JdbcDictionaryRepository(jdbcTemplate, transactionTemplate);
    }

    /**
     * 【V1.0.2 §4 Z3】去重预检。
     *
     * <p>⚠️ 与 {@link #corpusManagement} 共用同一把事务模板：预检必须在设置了
     * {@code app.tenant_id} 的会话内执行，否则 RLS 会让查询恒返回空、
     * 预检<b>静默失效</b>（这比报错更危险：看起来在防重，实际什么都没防）。
     *
     * @param jdbcTemplate JDBC 模板
     * @return 去重预检
     */
    @Bean
    public IngestDedupPrecheck ingestDedupPrecheck(
            JdbcTemplate jdbcTemplate,
            @Qualifier("ragManagementTransactionTemplate") TransactionTemplate transactionTemplate) {
        // ⚠️ 必须传事务模板：预检内部要按作用域建立会话租户（平台档显式置空），
        //    而 set_config(..., true) 只在事务内有效。不传的话本类会自建一个
        //    与装配同一数据源、但不受此配置约束的模板——能跑，却不是同一口径。
        return new IngestDedupPrecheck(jdbcTemplate, transactionTemplate);
    }

    /**
     * 【V1.0.2 §3.1 ③】摄取任务端口（管理面侧：建单 / 列表 / 重试）。
     *
     * <p>⚠️ 实现下沉在 {@code rag-infrastructure}，与 worker 共用同一份实现——
     * 「job 怎么领、怎么回写」的口径只有一处（与 {@code ChunkIndexer} 下沉同源）。
     *
     * @param jdbcTemplate JDBC 模板
     * @param transactionTemplate job 侧事务模板
     * @return 摄取任务端口
     */
    @Bean
    public JdbcIngestJobRepository ingestJobRepository(
            JdbcTemplate jdbcTemplate,
            @Qualifier("ragManagementTransactionTemplate")
            TransactionTemplate transactionTemplate,
            // ⚠️ 平台档的 job 是全局行（tenant_id = 哨兵），而平台档会话租户按设计置空，
            //    应用连接的 WITH CHECK 必然拒绝 → 只能与来源登记/抽检一样走 rag_ops。
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            RagOpsSessionPort opsSession) {
        return new JdbcIngestJobRepository(jdbcTemplate, transactionTemplate, opsSession);
    }

    @Bean
    public IngestJobPort ingestJobPort(JdbcIngestJobRepository ingestJobRepository) {
        return ingestJobRepository;
    }

    /**
     * 平台全局桶的建单端口（与 {@link #ingestJobPort} 是两个 bean 类型，
     * 不是同一接口的两个分支）：租户列在 SQL 里写死为 {@code rag_global_tenant_id()}。
     *
     * @param ingestJobRepository 同一份仓储实现（运维通道已注入）
     * @return 全局建单端口
     */
    @Bean
    public GlobalIngestJobPort globalIngestJobPort(JdbcIngestJobRepository ingestJobRepository) {
        return ingestJobRepository::createGlobalJob;
    }

    /**
     * 【V1.0.2 §5 R2】抽检记录端口。
     *
     * <p>⚠️ 与 {@link #corpusManagement} 共用事务模板：抽检写入与
     * {@code rag_source.inspection_status} 回写必须在同一事务，
     * 否则会出现「记录是 FAILED、状态是 PASSED」的窗口期——
     * 而 VERIFIED 守卫读的正是那个状态。
     *
     * @param jdbcTemplate JDBC 模板
     * @param transactionTemplate 管理面事务模板
     * @return 抽检端口
     */
    @Bean
    public cn.studykid.rag.management.ports.InspectionPort inspectionPort(
            JdbcTemplate jdbcTemplate,
            @Qualifier("ragManagementTransactionTemplate")
            TransactionTemplate transactionTemplate,
            // ⚠️ 平台全局语料的抽检记录是全局行，rag_app 写不出来（RLS 越权闸门），
            //    必须与来源登记/流转走同一条 rag_ops 运维通道。
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            cn.studykid.rag.infrastructure.ops.RagOpsSessionPort opsSession) {
        return new JdbcInspectionRepository(jdbcTemplate, transactionTemplate, opsSession);
    }

    /**
     * 【V1.0.2 §5 R7 / S3-3】知识点别名端口。
     *
     * <p>⚠️ 别名表的角色是「为既有 {@code KnowledgePoint.aliases} 匹配机制
     * 提供持久化载体与人工维护入口」，不是新建匹配逻辑。
     *
     * @param jdbcTemplate JDBC 模板
     * @param transactionTemplate 管理面事务模板
     * @return 别名端口
     */
    @Bean
    public cn.studykid.rag.management.ports.KnowledgeAliasPort knowledgeAliasPort(
            JdbcTemplate jdbcTemplate,
            @Qualifier("ragManagementTransactionTemplate")
            TransactionTemplate transactionTemplate) {
        return new JdbcKnowledgeAliasRepository(jdbcTemplate, transactionTemplate);
    }

    /**
     * 对象存储端口的本地桩实现（语料物理载体落桶）。
     *
     * <p><b>⚠️ 占位实现 = 本地目录</b>：把上传字节写到
     * {@code rag.corpus-bucket.base-dir} 指定的目录，返回相对路径作为 objectRef。
     * 它是<b>落桶语义的最小实现</b>，真实部署应替换为 MinIO / S3 适配器
     * （实现同一 {@link ObjectStorePort}，调用方零改动）。
     *
     * <p><b>⚠️ 优先读环境变量</b>：路径由 {@code RAG_CORPUS_BUCKET_BASE_DIR} 注入，
     * 缺省回落到相对路径 {@code ./corpus-bucket}（按进程工作目录解析为绝对路径）。
     * 路径穿越防护在 {@link LocalObjectStoreAdapter} 内，非法的逻辑键会在落盘前被拒。
     *
     * @param baseDir 语料根目录
     * @return 对象存储端口
     */
    @Bean
    public ObjectStorePort objectStorePort(
            @Value("${rag.corpus-bucket.base-dir:./corpus-bucket}") String baseDir) {
        return new LocalObjectStoreAdapter(baseDir);
    }
}
