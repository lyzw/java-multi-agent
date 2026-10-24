package cn.studykid.rag.infrastructure.indexing;

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
 * <p><b>⚠️ 为什么下沉到 rag-infrastructure</b>：
 * 本类原先位于 {@code rag-worker}，而管理面（{@code rag-management}）
 * <b>不依赖</b> {@code rag-worker}——于是「管理台点一下重跑索引」这条需求
 * 只有两条路：复制一份逻辑，或把实现下沉。
 * 复制逻辑的代价是<b>两处 SQL 口径必然漂移</b>
 * （worker 扫一批、管理面扫另一批），而「待索引」的定义恰恰是
 * 一条必须全系统一致的<b>治理口径</b>（哪些片段允许进索引）。
 * 故本类下沉到基础设施层，{@code rag-worker} 与 {@code rag-bootstrap}
 * <b>共用同一份实现</b>，口径只有一处。
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
        return indexTenant(tenantId, Integer.MAX_VALUE).indexedChunks();
    }

    /**
     * 索引某租户下<b>至多</b> {@code limit} 个待索引片段。
     *
     * <p><b>⚠️ 为什么要有上限</b>：管理面把重索引暴露为 <b>HTTP 同步端点</b>，
     * 而一次全量重索引可能耗时数十秒乃至数分钟——HTTP 请求线程被长期占用，
     * 在高并发下会拖垮整个管理面（甚至占满 Web 容器工作线程）。
     * 故管理面路径必须<b>限量</b>，把「剩下的」留给下一次调用或后台 worker；
     * 上限本身由调用方（HTTP 层）再压一道（2000）。
     *
     * @param tenantId 租户 ID
     * @param limit 本次最多处理的片段数（必须 > 0）
     * @return 索引摘要（已索引数 / 扫描到的待索引数）
     */
    @NonNull
    public IndexingSummary indexTenant(@NonNull String tenantId, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须 > 0");
        }
        List<PendingChunk> pending = collectPending(tenantId, limit);
        int indexed = 0;
        for (PendingChunk p : pending) {
            indexOne(tenantId, p.chunkId, p.text);
            indexed++;
        }
        log.info("[index] tenant={} 完成，索引 {} 个片段（共扫描 {} 个待索引）",
                tenantId, indexed, pending.size());
        return new IndexingSummary(indexed, pending.size());
    }

    /**
     * 收集某租户下待索引片段（键集分页，每页独立事务，最多 {@code limit} 个）。
     *
     * <p>待索引判定：来源已 VERIFIED，且（{@code text_jieba=''} 或 无向量行）。
     */
    private List<PendingChunk> collectPending(@NonNull String tenantId, int limit) {
        List<PendingChunk> all = new ArrayList<>();
        String lastChunkId = FIRST_CHUNK_ID;
        while (all.size() < limit) {
            // ⚠️ lastChunkId 在循环末尾被重新赋值，不能被 lambda 捕获（非 effectively final）。
            //    故在每次迭代开始时取一个 final 游标副本供查询使用。
            final String cursor = lastChunkId;
            // ⚠️ 每页大小必须夹在「剩余额度」之内：否则最后一次翻页会多取片段，
            //    使本次处理量超过调用方给定的上限（HTTP 端点的耗时承诺就失真了）。
            final int pageSize = Math.min(batchSize, limit - all.size());
            List<PendingChunk> page = transactionTemplate.execute(status -> {
                // ⚠️ 必须用 queryForObject() 而非 update()：set_config 是 SELECT，会返回一行，
                //    update() 期望「无结果集」，否则抛 DataIntegrityViolation。
                jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                        String.class, tenantId);
                return jdbcTemplate.query(PENDING_SQL,
                        (rs, i) -> new PendingChunk(rs.getString("chunk_id"), rs.getString("text")),
                        cursor, pageSize);
            });
            if (page == null || page.isEmpty()) {
                break;
            }
            // ⚠️ 额度必须在**应用层**再卡一次，不能只依赖 SQL 的 LIMIT ?：
            //    管理面把「最多处理 N 个」作为对调用方的承诺，
            //    承诺不能建立在「数据库一定按 LIMIT 返回」这一假设上。
            //    这里逐条装填，装满即止——超出部分留给下一次调用。
            for (PendingChunk p : page) {
                if (all.size() >= limit) {
                    break;
                }
                all.add(p);
            }
            // ⚠️ 游标取**实际装入的最后一条**：若按 page 的最后一条取，
            //    多出来的那些会被跳过（键集分页的游标一旦跳过就再也回不来）。
            lastChunkId = all.get(all.size() - 1).chunkId;
            if (page.size() < pageSize) {
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

    /**
     * 索引结果摘要。
     *
     * <p>⚠️ {@code scannedChunks} 是「本次扫描到并交给处理流程的待索引片段数」，
     * <b>不是</b>「库里待索引片段的总数」——扫描在达到 limit 时即停止，
     * 库里还剩多少是未知的（要知道就得再查一次 COUNT，而那会让一次
     * HTTP 同步调用多付一次全表扫描的代价）。
     * {@code indexedChunks} 是「实际完成索引的片段数」。
     * 两者不等说明中途有片段失败——这个差值是运维判断
     * 「重索引是否彻底」的唯一依据，故两个数都要给。
     *
     * @param indexedChunks 实际完成索引的片段数
     * @param scannedChunks 本次扫描并交给处理的待索引片段数
     */
    public record IndexingSummary(int indexedChunks, int scannedChunks) {
    }

    /**
     * 待索引片段（扫描结果行）。
     *
     * <p>⚠️ 包可见而非 private：单元测试需要构造它以验证「限量扫描」语义，
     * 但它<b>不是</b>对外 API——公开成 public 会让调用方误以为可以自己造行喂进来。
     */
    record PendingChunk(String chunkId, String text) {

    }

    /**
     * 待索引片段扫描 SQL（键集分页 + VERIFIED 来源 + 缺失判定）。
     * RLS 下 {@code c.tenant_id = rag_current_tenant()} 由 set_config 满足。
     *
     * <p>⚠️ <b>2026-10-22 修复</b>：原实现写的是 {@code JOIN rag_source src
     * ON c.source_id = src.source_id}，但 {@code rag_chunk} 表<b>没有 source_id 列</b>
     * （V1 的三层模型是 source → document → chunk，来源须经 rag_document 中转），
     * 运行期必然抛 {@code column c.source_id does not exist}——
     * 即重索引功能<b>从未真正跑通过</b>。
     * 现改为经 {@code rag_document} 中转，与
     * {@code JdbcRetrievalRepository} 的关联路径保持一致。
     *
     * <p>⚠️ <b>口径只有这一处</b>：管理面「重跑索引」端点与 worker 启动重索引
     * 共用本 SQL。若两处各写一份，「哪些片段算待索引」就会漂移——
     * 而这条口径直接决定「未校验语料会不会进索引」（fail-closed 的底线）。
     *
     * <p>⚠️ <b>2026-10-23 修复：键集分页游标必须显式转 uuid</b>。
     * {@code c.chunk_id > ?} 中 {@code chunk_id} 是 uuid 列，
     * 而游标是以字符串传入的参数（pgjdbc 送的类型是 varchar）——
     * PG 无 {@code uuid > varchar} 运算符，运行期直接报
     * {@code operator does not exist: uuid > character varying}。
     * 这条 SQL 在被管理端点真正调用之前<b>从未跑过</b>
     * （worker 的启动重索引需要显式配置租户才会触发），
     * 故此前一直潜伏。写 {@code ?::uuid} 让游标按 uuid 参与比较。
     */
    static final String PENDING_SQL = """
            SELECT c.chunk_id, c.text
            FROM rag_chunk c
            JOIN rag_document doc
                 ON c.document_id = doc.document_id AND c.tenant_id = doc.tenant_id
            JOIN rag_source src
                 ON doc.source_id = src.source_id AND c.tenant_id = src.tenant_id
            WHERE c.tenant_id = rag_current_tenant()
              AND src.ver_status = 'VERIFIED'
              AND doc.ver_status = 'VERIFIED'
              AND doc.retrievable
              AND c.chunk_id > ?::uuid
              AND (c.text_jieba = '' OR NOT EXISTS (
                    SELECT 1 FROM rag_chunk_embedding e
                    WHERE e.chunk_id = c.chunk_id AND e.tenant_id = c.tenant_id))
            ORDER BY c.chunk_id
            LIMIT ?
            """;
}
