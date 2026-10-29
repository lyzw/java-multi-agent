package cn.studykid.rag.worker;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import cn.studykid.rag.infrastructure.LocalRagPortsConfig;
import cn.studykid.rag.infrastructure.embedding.EmbeddingPorts;
import cn.studykid.rag.infrastructure.indexing.ChunkIndexer;
import cn.studykid.rag.infrastructure.ops.RagOpsConfiguration;
import cn.studykid.rag.infrastructure.ops.RagOpsSessionPort;
import cn.studykid.rag.infrastructure.persistence.JdbcCorpusRepository;
import cn.studykid.rag.infrastructure.persistence.VectorColumn;
import cn.studykid.rag.ports.CorpusRepository;
import cn.studykid.rag.ports.EmbeddingPort;

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
     * 嵌入端口。
     *
     * <p>与 rag-bootstrap 共用 {@link EmbeddingPorts}：配置了 {@code rag.embedding.base-url}
     * 走真实模型，否则退回本地确定性占位并受 fail-closed 闸门约束。
     * 两处必须同源——索引向量与查询向量来自不同模型时，检索会恒低分且不报错。
     */
    @Bean
    public EmbeddingPort embeddingPort() {
        return EmbeddingPorts.create(localPortsEnabled, embeddingBaseUrl, embeddingApiKey,
                embeddingModel, embeddingDimension, embeddingBatchSize, embeddingTimeoutSeconds,
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
     * 片段索引器。
     *
     * <p>⚠️ 这里的 {@link RagOpsSessionPort} 必须与 {@link #corpusRepository} 拿到的是
     * <b>同一个 bean</b>：扫描窗与落库窗由同一线程的会话深度计数保护，
     * 两个实例会让「在窗内」的判断与实际取连接的池脱节。
     */
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
}
