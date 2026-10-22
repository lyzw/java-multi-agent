package cn.studykid.rag.worker;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import cn.studykid.rag.infrastructure.text.ChineseTokenizer;
import cn.studykid.rag.ports.CorpusRepository;
import cn.studykid.rag.ports.EmbeddingPort;

/**
 * 片段索引器（RAG-D09 R2-1：V2 词法列补齐 + 历史片段向量缺失重索引）。
 *
 * <p><b>职责</b>：把待索引片段的 {@code text} 跑两路预处理并落库——
 * <ul>
 *   <li>词法通道：{@link ChineseTokenizer} 切分 → {@code rag_chunk.text_jieba}
 *       （供 {@code GIN(to_tsvector('simple', text_jieba))} 词法索引）；</li>
 *   <li>稠密通道：{@link EmbeddingPort} 嵌入 → {@code rag_chunk_embedding.embedding}
 *       （pgvector HNSW）。</li>
 * </ul>
 *
 * <p><b>⚠️ fail-closed</b>：只索引 {@code rag_source.ver_status = 'VERIFIED'} 的片段，
 *   未校验来源永远不进索引（与检索侧一致，见 {@code JdbcRetrievalRepository}）。
 *
 * <p><b>⚠️ 租户隔离</b>：扫描与写入各自在独立事务内，事务内
 *   {@code set_config('app.tenant_id', tenantId, true)} 使 RLS FORCE 生效。
 *   扫描事务内设置租户以满足 RLS 读取；写入经 {@link CorpusRepository}
 *   （其内部再次 set_config，幂等）。游标式键集分页避免 OFFSET 漂移。
 *
 * <p><b>⚠️ 诚实边界</b>：当前 {@link EmbeddingPort} 为本地确定性占位
 *   （{@code LocalDeterministicEmbeddingPort}），索引出的向量仅用于链路验证，
 *   非语义质量结论（生产须替换为 BGE-M3，见 RAG-D02 §3.5）。
 */
public class ChunkIndexer {

    private static final Logger log = LoggerFactory.getLogger(ChunkIndexer.class);

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final CorpusRepository corpusRepository;
    private final EmbeddingPort embeddingPort;
    private final int batchSize;

    /** 键集分页哨兵：全 0 UUID，保证首次查询包含最小 chunk_id。 */
    private static final String FIRST_CHUNK_ID = "00000000-0000-0000-0000-000000000000";

    public ChunkIndexer(JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate,
                        CorpusRepository corpusRepository, EmbeddingPort embeddingPort, int batchSize) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.corpusRepository = corpusRepository;
        this.embeddingPort = embeddingPort;
        this.batchSize = batchSize > 0 ? batchSize : 200;
    }

    /**
     * 索引单个租户的全部待索引片段（已切词且已向量化者跳过）。
     *
     * @return 本次处理的片段数。
     */
    public int indexTenant(@NonNull String tenantId) {
        List<PendingChunk> pending = collectPending(tenantId);
        int total = 0;
        for (PendingChunk p : pending) {
            indexOne(tenantId, p.chunkId, p.text);
            total++;
        }
        log.info("[index] tenant={} 完成，处理 {} 个片段（共扫描 {} 个待索引）",
                tenantId, total, pending.size());
        return total;
    }

    /**
     * 收集某租户下所有待索引片段（键集分页，每页独立事务）。
     *
     * <p>待索引判定：来源已 VERIFIED，且（{@code text_jieba=''} 或 无向量行）。
     */
    private List<PendingChunk> collectPending(@NonNull String tenantId) {
        List<PendingChunk> all = new ArrayList<>();
        String lastChunkId = FIRST_CHUNK_ID;
        while (true) {
            // ⚠️ lastChunkId 在循环末尾被重新赋值，不能被 lambda 捕获（非 effectively final）。
            //    故在每次迭代开始时取一个 final 游标副本供查询使用。
            final String cursor = lastChunkId;
            List<PendingChunk> page = transactionTemplate.execute(status -> {
                // ⚠️ 必须用 queryForObject() 而非 update()：set_config 是 SELECT，会返回一行，
                //    update() 期望「无结果集」，否则抛 DataIntegrityViolation。
                jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                        String.class, tenantId);
                return jdbcTemplate.query(PENDING_SQL,
                        (rs, i) -> new PendingChunk(rs.getString("chunk_id"), rs.getString("text")),
                        cursor, batchSize);
            });
            if (page == null || page.isEmpty()) {
                break;
            }
            all.addAll(page);
            lastChunkId = page.get(page.size() - 1).chunkId;
            if (page.size() < batchSize) {
                break;
            }
        }
        return all;
    }

    /**
     * 处理单个片段：切词 + 嵌入 + 落库。
     *
     * <p>本方法<b>不触碰扫描 SQL / 事务模板</b>，可被单元测试直接调用验证索引逻辑。
     */
    public void indexOne(@NonNull String tenantId, @NonNull String chunkId, @NonNull String text) {
        String jieba = ChineseTokenizer.tokenize(text);
        float[] vector = embeddingPort.embed(List.of(text)).get(0);
        corpusRepository.updateJiebaTokens(tenantId, chunkId, jieba);
        corpusRepository.upsertEmbedding(tenantId, chunkId, vector, embeddingPort.modelVersion());
    }

    private record PendingChunk(String chunkId, String text) {

    }

    /**
     * 待索引片段扫描 SQL（键集分页 + VERIFIED 来源 + 缺失判定）。
     * RLS 下 {@code c.tenant_id = rag_current_tenant()} 由 set_config 满足。
     */
    private static final String PENDING_SQL = """
            SELECT c.chunk_id, c.text
            FROM rag_chunk c
            JOIN rag_source src
                 ON c.source_id = src.source_id AND c.tenant_id = src.tenant_id
            WHERE c.tenant_id = rag_current_tenant()
              AND src.ver_status = 'VERIFIED'
              AND c.chunk_id > ?
              AND (c.text_jieba = '' OR NOT EXISTS (
                    SELECT 1 FROM rag_chunk_embedding e
                    WHERE e.chunk_id = c.chunk_id AND e.tenant_id = c.tenant_id))
            ORDER BY c.chunk_id
            LIMIT ?
            """;
}
