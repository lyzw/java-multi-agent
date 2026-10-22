package cn.studykid.rag.worker;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import cn.studykid.rag.infrastructure.LocalRagPortsConfig;
import cn.studykid.rag.infrastructure.embedding.LocalDeterministicEmbeddingPort;
import cn.studykid.rag.infrastructure.persistence.JdbcCorpusRepository;
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
 * <p>通过 {@link Import} 引入 {@link LocalRagPortsConfig}，使 Worker 上下文也能托管
 * G1/G3 的本地占位切片（解析/蕴含/入库用例/接地用例），为未来「完整入库执行器」
 * 预留装配基础——但占位实现<b>不代表</b> G1/G3 已生产就绪（仍受 A-008 阻断）。
 *
 * <p>⚠️ 诚实边界：{@link EmbeddingPort} 当前为本地确定性占位
 *   （{@code LocalDeterministicEmbeddingPort}），非生产级 BGE-M3。
 *   真实嵌入模型接入前，索引出的向量仅用于链路验证（见端口类标注）。
 */
@Configuration
@Import(LocalRagPortsConfig.class)
public class WorkerInfraConfig {

    @Bean
    public EmbeddingPort embeddingPort() {
        return new LocalDeterministicEmbeddingPort();
    }

    @Bean
    public CorpusRepository corpusRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcCorpusRepository(jdbcTemplate);
    }

    @Bean
    public TransactionTemplate indexingTransactionTemplate(PlatformTransactionManager txManager) {
        return new TransactionTemplate(txManager);
    }

    @Bean
    public ChunkIndexer chunkIndexer(JdbcTemplate jdbcTemplate,
                                     TransactionTemplate transactionTemplate,
                                     CorpusRepository corpusRepository,
                                     EmbeddingPort embeddingPort,
                                     RagIndexerProperties properties) {
        return new ChunkIndexer(jdbcTemplate, transactionTemplate, corpusRepository,
                embeddingPort, properties.getBatchSize());
    }
}
