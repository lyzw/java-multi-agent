package cn.studykid.rag.bootstrap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import cn.studykid.rag.application.retrieval.RetrievalOrchestrator;
import cn.studykid.rag.infrastructure.embedding.LocalDeterministicEmbeddingPort;
import cn.studykid.rag.infrastructure.persistence.JdbcRetrievalRepository;
import cn.studykid.rag.ports.EmbeddingPort;
import cn.studykid.rag.ports.RetrievalPort;

/**
 * RAG 检索链路装配（RAG-D09 G2：可部署检索制品）。
 *
 * <p>仅装配<b>检索侧</b>所需 bean（G2 范围是「可部署检索制品」，对应 RAG-D09 G2 条件②）：
 * {@link EmbeddingPort} → {@link RetrievalPort} → {@link RetrievalOrchestrator}。
 * 入库侧（{@code IngestionPipeline} / {@code CorpusRepository} / 文档解析器）由
 * rag-worker 负责，不在本启动器的职责内（见 RAG-D09 的 R3 阶段）。
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

    @Bean
    public EmbeddingPort embeddingPort() {
        return new LocalDeterministicEmbeddingPort();
    }

    @Bean
    public RetrievalPort retrievalPort(JdbcTemplate jdbcTemplate, EmbeddingPort embeddingPort) {
        return new JdbcRetrievalRepository(jdbcTemplate, embeddingPort);
    }

    @Bean
    public RetrievalOrchestrator retrievalOrchestrator(RetrievalPort retrievalPort) {
        return new RetrievalOrchestrator(retrievalPort, minScore);
    }
}
