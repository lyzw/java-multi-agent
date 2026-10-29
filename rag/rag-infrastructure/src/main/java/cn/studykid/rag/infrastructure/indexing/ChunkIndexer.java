package cn.studykid.rag.infrastructure.indexing;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import cn.studykid.rag.infrastructure.ops.RagOpsSessionPort;
import cn.studykid.rag.infrastructure.ops.RagOpsUnavailableException;
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
 * <p><b>⚠️ 全局桶是另一条通道</b>（{@link #indexGlobalCorpus}，第二刀 2.5）：
 *   桶谓词换成 {@code rag_global_tenant_id()}、连接换成运维数据源、
 *   且<b>不</b>设置会话租户（设哨兵会让 {@code rag_current_tenant()} 直接 RAISE）。
 *   两档共用的只有「什么算待索引」这条口径本身。
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

    /**
     * 全局桶索引通道（可为 null = 未接入）。
     *
     * <p>⚠️ 与 {@link #indexTenant} 用的<b>不是同一条连接</b>：全局桶的扫描谓词
     * 要写 {@code rag_global_tenant_id()}，而 {@code rag_app} 的读策略在无租户会话下
     * 虽能看到全局行，写入却会被静默滤成 0 行——所以扫描与写入必须一起搬到运维池上。
     */
    @Nullable
    private final RagOpsSessionPort opsSession;

    /** 键集分页哨兵：全 0 UUID，保证首次查询包含最小 chunk_id。 */
    private static final String FIRST_CHUNK_ID = "00000000-0000-0000-0000-000000000000";

    public ChunkIndexer(JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate,
                        CorpusRepository corpusRepository, EmbeddingPort embeddingPort, int batchSize) {
        this(jdbcTemplate, transactionTemplate, corpusRepository, embeddingPort, batchSize, null);
    }

    public ChunkIndexer(JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate,
                        CorpusRepository corpusRepository, EmbeddingPort embeddingPort, int batchSize,
                        @Nullable RagOpsSessionPort opsSession) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.corpusRepository = corpusRepository;
        this.embeddingPort = embeddingPort;
        this.batchSize = batchSize > 0 ? batchSize : 200;
        this.opsSession = opsSession;
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
        return indexOn(new ScanChannel(jdbcTemplate, transactionTemplate,
                PENDING_SQL, tenantId), limit);
    }

    /**
     * 索引<b>平台全局桶</b>里至多 {@code limit} 个待索引片段（走运维数据源会话）。
     *
     * <p><b>为什么单开一个方法而不是给 {@link #indexTenant} 传哨兵</b>：
     * 租户通道在事务里做的是 {@code set_config('app.tenant_id', 哨兵)}，
     * 而那个值一进 {@code rag_current_tenant()} 就 RAISE（SQLSTATE 42501，V8 的越权封堵）。
     * 换句话说「用租户通道索引全局桶」这句话<b>在库里就不成立</b>，
     * 不是权限不够，是形态不对——所以它必须有自己的一条通道，
     * 且这个方法<b>不接收任何租户参数</b>。
     *
     * <p>⚠️ 通道未接入时抛 {@link RagOpsUnavailableException}（呈现 503）：
     * 回落应用连接的结果是「扫到行、写不进去、报告成功」，
     * 那比失败更难发现。
     *
     * @param limit 本次最多处理的片段数（必须 &gt; 0）
     * @return 索引摘要
     */
    @NonNull
    public IndexingSummary indexGlobalCorpus(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须 > 0");
        }
        if (opsSession == null) {
            throw new RagOpsUnavailableException("rag.ops.datasource",
                    "全局语料索引通道未接入：需要运维数据源（rag_ops），不回落应用角色");
        }
        // 整段（扫描 + 落库）包在同一个会话窗里：窗口外的连接请求会被 guarded 数据源拒绝，
        // 所以「忘了开窗口」的失败方式是异常，而不是静默写到别的桶。
        return opsSession.inGlobalCorpusSession(
                (template, tx) -> indexOn(new ScanChannel(template, tx, GLOBAL_PENDING_SQL, null),
                        limit));
    }

    /** 在一次扫描通道上跑「限量扫描 → 逐条索引」。 */
    private IndexingSummary indexOn(ScanChannel channel, int limit) {
        List<PendingChunk> pending = collectPending(channel, limit);
        int indexed = 0;
        for (PendingChunk p : pending) {
            writeChunk(channel, p);
            indexed++;
        }
        log.info("[index] tenant={} 完成，索引 {} 个片段（共扫描 {} 个待索引）",
                channel.label(), indexed, pending.size());
        return new IndexingSummary(indexed, pending.size());
    }

    /**
     * 收集待索引片段（键集分页，每页独立事务，最多 {@code limit} 个）。
     *
     * <p>⚠️ 分页逻辑<b>只有一份</b>：租户桶与全局桶共用本方法，
     * 差异全压在 {@link ScanChannel} 的三个字段上（连接、SQL、是否设会话租户）。
     * 复制一份分页循环的代价是「limit 语义在两处漂移」——
     * 而管理面对调用方承诺的正是这个 limit。
     */
    private List<PendingChunk> collectPending(ScanChannel channel, int limit) {
        List<PendingChunk> all = new ArrayList<>();
        String lastChunkId = FIRST_CHUNK_ID;
        while (all.size() < limit) {
            // ⚠️ lastChunkId 在循环末尾被重新赋值，不能被 lambda 捕获（非 effectively final）。
            //    故在每次迭代开始时取一个 final 游标副本供查询使用。
            final String cursor = lastChunkId;
            // ⚠️ 每页大小必须夹在「剩余额度」之内：否则最后一次翻页会多取片段，
            //    使本次处理量超过调用方给定的上限（HTTP 端点的耗时承诺就失真了）。
            final int pageSize = Math.min(batchSize, limit - all.size());
            List<PendingChunk> page = channel.tx().execute(status -> {
                channel.open();
                // ⚠️ 必须用 queryForObject() 而非 update()：set_config 是 SELECT，会返回一行，
                //    update() 期望「无结果集」，否则抛 DataIntegrityViolation。
                return channel.template().query(channel.pendingSql(),
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
     * 处理单个片段：切词 + 嵌入 + 落库（<b>租户桶</b>）。
     *
     * <p>本方法<b>不触碰扫描 SQL / 事务模板</b>，可被单元测试直接调用验证索引逻辑。
     */
    public void indexOne(@NonNull String tenantId, @NonNull String chunkId, @NonNull String text) {
        writeChunk(new ScanChannel(jdbcTemplate, transactionTemplate, PENDING_SQL, tenantId),
                new PendingChunk(chunkId, text));
    }

    /**
     * 切词 + 嵌入并按<b>通道</b>落库。
     *
     * <p>⚠️ 桶的选择走的是<b>方法名</b>（全局版没有租户参数），不是参数值：
     * 运维角色带 BYPASSRLS，库层不再替我们限制「写到哪个桶」。
     */
    private void writeChunk(ScanChannel channel, PendingChunk pending) {
        String jieba = ChineseTokenizer.tokenize(pending.text);
        float[] vector = embeddingPort.embed(List.of(pending.text)).get(0);
        if (channel.isGlobal()) {
            corpusRepository.updateJiebaTokensInGlobalBucket(pending.chunkId, jieba);
            corpusRepository.upsertEmbeddingInGlobalBucket(
                    pending.chunkId, vector, embeddingPort.modelVersion());
            return;
        }
        corpusRepository.updateJiebaTokens(channel.tenantId, pending.chunkId, jieba);
        corpusRepository.upsertEmbedding(channel.tenantId, pending.chunkId, vector,
                embeddingPort.modelVersion());
    }

    /**
     * 一次索引跑在哪条连接、扫哪一段 SQL。
     *
     * <p>与 {@code JdbcCorpusManagementRepository.WriteChannel} 同一形状：
     * <b>SQL 文本由通道决定，没有可以填「另一个桶」的问号位置</b>。
     * {@code tenantId == null} 表示全局通道，且 {@link #open()} 因此<b>不</b>设置会话租户
     * （设哨兵会让 {@code rag_current_tenant()} 直接 RAISE）。
     */
    private record ScanChannel(JdbcTemplate template, TransactionTemplate tx,
                               String pendingSql, @Nullable String tenantId) {

        boolean isGlobal() {
            return tenantId == null;
        }

        /** 日志标签：全局桶要说清是哪个桶，别打成 {@code tenant=null}。 */
        String label() {
            return tenantId != null ? tenantId : "PLATFORM-GLOBAL";
        }

        /** 事务内的先决步骤：仅租户通道建立会话租户。 */
        void open() {
            if (isGlobal()) {
                return;
            }
            template.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                    String.class, tenantId);
        }
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
     * RLS 下 {@code c.tenant_id = rag_current_tenant()} 由 set_config 满足
     * （扫描<b>只针对本租户</b>：全局桶由 {@link #GLOBAL_PENDING_SQL} 走运维通道索引，
     * 见 {@link #indexGlobalCorpus}）。
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

    /**
     * 全局桶的待索引扫描 SQL —— 由 {@link #PENDING_SQL} <b>派生</b>，不是另写一份。
     *
     * <p>⚠️ 派生而不是复制：两份 SQL 唯一的区别必须是<b>桶谓词</b>，
     * 而「哪些片段算待索引」（来源与载体都 VERIFIED、载体 retrievable、
     * 缺词法列或缺向量）是一条必须全系统一致的治理口径。
     * 复制一份的话，将来改 fail-closed 条件只会改到租户那半 ——
     * 全局语料就会带着未校验内容进索引，且<b>不报错</b>。
     * （{@code ChunkIndexerGlobalScopeTest} 把「只差桶谓词」这条钉成了断言。）
     *
     * <p>⚠️ 运维角色带 BYPASSRLS，所以这里的 {@code tenant_id = rag_global_tenant_id()}
     * <b>不是风格问题而是唯一的行范围</b>：去掉它，这条扫描会把
     * <b>所有租户</b>的待索引片段当成全局语料逐一重写。
     */
    static final String GLOBAL_PENDING_SQL = PENDING_SQL.replace(
            "WHERE c.tenant_id = rag_current_tenant()",
            "WHERE c.tenant_id = rag_global_tenant_id()");
}
