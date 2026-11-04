package cn.studykid.rag.infrastructure.indexing;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import cn.studykid.rag.ports.CorpusRepository;
import cn.studykid.rag.ports.EmbeddingPort;
import cn.studykid.rag.ports.IndexGenerationPort;

/**
 * 索引代际构建器（RAG-D13 §3.5.2 蓝绿切换的「绿」）。
 *
 * <p><b>这一层解决什么</b>：换嵌入模型时，把<b>全部合格片段</b>用新模型重新嵌入，
 * 写入一个 <b>BUILDING</b> 代际，全程<b>不影响</b>当前服务的 ACTIVE 代际。
 * 建好后再由管理面调 activate 原子切换。
 *
 * <p><b>⚠️ 与 {@link ChunkIndexer} 的分工（两者语义完全不同，不可混用）</b>：
 * <table border="1">
 *   <caption>两个索引器的差异</caption>
 *   <tr><th></th><th>{@link ChunkIndexer}</th><th>本类</th></tr>
 *   <tr><td>选哪些片段</td><td>**缺**向量或词法列的（待索引）</td><td>**全部**合格片段（不管有没有向量）</td></tr>
 *   <tr><td>写哪个代际</td><td>ACTIVE（日常入库）</td><td>指定的 BUILDING 代际</td></tr>
 *   <tr><td>何时用</td><td>补数据缺口</td><td>换模型</td></tr>
 * </table>
 * 用错会导致：拿 ChunkIndexer 换模型 → 新向量写进 ACTIVE 与旧向量混在一个空间，
 * 余弦相似度给出无意义分数且**无任何报错**。
 *
 * <p><b>⚠️ fail-closed 口径与 ChunkIndexer 同源</b>：
 * 只重建「来源与载体都 VERIFIED、载体 retrievable」的片段。
 * 未校验语料不得因为「有人点了换模型」而进索引。
 *
 * <p><b>⚠️ 进度更新策略</b>：每批写完后更新 {@code indexed_chunks}。
 * 不做逐条更新——那是 N 次额外写；也不做「只在最后更新」——
 * 那样构建中途崩溃时进度会永远停在 0，运维无法判断"跑到哪了"。
 */
public class IndexGenerationBuilder {

    private static final Logger log = LoggerFactory.getLogger(IndexGenerationBuilder.class);

    /** 每批处理的片段数。 */
    public static final int DEFAULT_BATCH_SIZE = 50;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final CorpusRepository corpusRepository;
    private final EmbeddingPort embeddingPort;
    private final IndexGenerationPort generationPort;
    private final int batchSize;

    /**
     * 全局桶写通道（可为 null = 未接入）。
     *
     * <p>⚠️ null 时 {@link #buildGlobalBatch} 抛
     * {@link cn.studykid.rag.infrastructure.ops.RagOpsUnavailableException}，
     * <b>不</b>回落应用连接——回落会让 RLS 把全局写静默滤成 0 行，
     * 而界面显示"构建完成"。
     */
    private final cn.studykid.rag.infrastructure.ops.@org.jspecify.annotations.Nullable
            RagOpsSessionPort opsSession;

    /** 键集分页哨兵（与 {@link ChunkIndexer} 同款）。 */
    private static final String FIRST_CHUNK_ID = "00000000-0000-0000-0000-000000000000";

    public IndexGenerationBuilder(JdbcTemplate jdbcTemplate,
                                  TransactionTemplate transactionTemplate,
                                  CorpusRepository corpusRepository,
                                  EmbeddingPort embeddingPort,
                                  IndexGenerationPort generationPort) {
        this(jdbcTemplate, transactionTemplate, corpusRepository, embeddingPort,
                generationPort, DEFAULT_BATCH_SIZE);
    }

    public IndexGenerationBuilder(JdbcTemplate jdbcTemplate,
                                  TransactionTemplate transactionTemplate,
                                  CorpusRepository corpusRepository,
                                  EmbeddingPort embeddingPort,
                                  IndexGenerationPort generationPort,
                                  int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize 必须为正");
        }
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.corpusRepository = corpusRepository;
        this.embeddingPort = embeddingPort;
        this.generationPort = generationPort;
        this.batchSize = batchSize;
        this.opsSession = null;
    }

    /**
     * 完整构造器：额外注入全局桶运维通道。
     *
     * @param opsSession 运维数据源会话（null = 不支持全局桶代际构建）
     */
    public IndexGenerationBuilder(JdbcTemplate jdbcTemplate,
                                  TransactionTemplate transactionTemplate,
                                  CorpusRepository corpusRepository,
                                  EmbeddingPort embeddingPort,
                                  IndexGenerationPort generationPort,
                                  int batchSize,
                                  cn.studykid.rag.infrastructure.ops.@org.jspecify.annotations.Nullable
                                          RagOpsSessionPort opsSession) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.corpusRepository = corpusRepository;
        this.embeddingPort = embeddingPort;
        this.generationPort = generationPort;
        this.batchSize = batchSize;
        this.opsSession = opsSession;
    }

    /**
     * 待重建的片段扫描 SQL。
     *
     * <p><b>⚠️ 与 {@link ChunkIndexer#PENDING_SQL} 的唯一差异就是没有「缺向量」条件</b>：
     * 换模型时**所有**片段都要重算，无论它当前有没有向量。
     * 但两条 SQL 的 **fail-closed 条件必须逐字一致**（来源/载体均 VERIFIED、载体 retrievable）——
     * 否则会出现「日常索引不进未校验语料，但换模型时进了」的口径裂缝。
     */
    static final String SCAN_SQL = """
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
            ORDER BY c.chunk_id
            LIMIT ?
            """;

    /** 计数 SQL：该租户有多少合格片段（用于 total_chunks）。 */
    static final String COUNT_SQL = """
            SELECT COUNT(*)
            FROM rag_chunk c
            JOIN rag_document doc
                 ON c.document_id = doc.document_id AND c.tenant_id = doc.tenant_id
            JOIN rag_source src
                 ON doc.source_id = src.source_id AND c.tenant_id = src.tenant_id
            WHERE c.tenant_id = rag_current_tenant()
              AND src.ver_status = 'VERIFIED'
              AND doc.ver_status = 'VERIFIED'
              AND doc.retrievable
            """;

    /**
     * 全局桶的扫描 SQL —— 由 {@link #SCAN_SQL} <b>派生</b>，不是另写一份。
     *
     * <p>⚠️ 派生而不是复制：两份 SQL 唯一的区别必须是<b>桶谓词</b>，
     * 而「哪些片段算合格」（来源与载体都 VERIFIED、载体 retrievable）是一条
     * 必须全系统一致的治理口径。复制一份的话，将来改 fail-closed 条件只会改到租户那半——
     * 全局语料就会带着未校验内容进索引，且<b>不报错</b>
     * （与 {@code ChunkIndexer.GLOBAL_PENDING_SQL} 同款纪律）。
     */
    static final String GLOBAL_SCAN_SQL = SCAN_SQL.replace(
            "WHERE c.tenant_id = rag_current_tenant()",
            "WHERE c.tenant_id = rag_global_tenant_id()");

    /** 全局桶的计数 SQL —— 同样派生自 {@link #COUNT_SQL}。 */
    static final String GLOBAL_COUNT_SQL = COUNT_SQL.replace(
            "WHERE c.tenant_id = rag_current_tenant()",
            "WHERE c.tenant_id = rag_global_tenant_id()");

    /** 扫描通道：租户桶走应用连接；全局桶走运维数据源且**不设会话租户**。 */
    private record ScanChannel(@NonNull JdbcTemplate template,
                               @NonNull TransactionTemplate tx,
                               @NonNull String scanSql,
                               @NonNull String countSql,
                               @org.jspecify.annotations.Nullable String tenantId) {

        boolean isGlobal() {
            return tenantId == null;
        }

        /** 日志标签：全局桶要说清是哪个桶，别打成 {@code tenant=null}。 */
        String label() {
            return tenantId != null ? tenantId : "PLATFORM-GLOBAL";
        }

        /** 事务内的先决步骤：仅租户通道建立会话租户。 */
        void bindSession() {
            if (tenantId != null) {
                template.queryForObject(
                        "SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId);
            }
            // ⚠️ 全局桶**刻意不设**会话租户：运维角色带 BYPASSRLS，
            //    而 set_config 填全局哨兵会让 rag_current_tenant() 直接抛错
            //    （V8 封堵「租户伪装成平台身份」的承重设计）。
        }
    }

    /** 单批处理结果。 */
    public record BatchOutcome(int processed, boolean finished) {
    }

    /**
     * 准备构建：校验状态 → 统计总数 → 清零进度与游标（异步构建的入口）。
     *
     * <p><b>⚠️ 这里只做"开场准备"，不做嵌入</b>：由 HTTP 端点调用，
     * 必须立即返回。真正的嵌入由 worker 分批推进（{@link #buildBatch}）。
     *
     * <p>⚠️ 统计总数用**与扫描同一份 fail-closed 口径**（{@code COUNT_SQL}）——
     * 若在这里另写一个计数，进度百分比会与实际处理量对不上。
     *
     * @param tenantId   租户 ID
     * @param generation 目标代际（必须是 BUILDING）
     * @return 准备后的代际快照（进度 0，状态 BUILDING）
     * @throws IllegalStateException 目标代际不处于 BUILDING
     */
    public cn.studykid.rag.core.indexing.IndexGeneration prepare(@NonNull String tenantId,
                                                                 int generation) {
        requireBuildingStatus(tenantId, generation);
        long total = countEligible(tenantId);
        // 清零游标与进度：让 worker 从第一条开始。重复调用是幂等的（等于重新开始构建）。
        generationPort.updateBuildState(tenantId, generation, 0, total, null);
        log.info("代际构建已排队 tenant={} generation={} total={}", tenantId, generation, total);
        var after = findGen(tenantId, generation);
        if (after == null) {
            throw new IllegalStateException("代际准备后读回失败：" + generation);
        }
        return after;
    }

    /**
     * 推进**一个批次**（异步构建的单轮）。
     *
     * <p><b>⚠️ 为什么是「一批」而不是「整个代际」</b>：
     * 数万片段跑完需数十分钟。若一次性跑完，会长期占用 worker 的调度线程，
     * 且进程重启后进度全丢。分批后每轮释放、下轮从游标续跑，
     * 崩溃最多丢一批。
     *
     * <p><b>⚠️ 游标必须持久化</b>：用 OFFSET 或 indexedChunks 当偏移，
     * 构建期间片段增删会让它们整体错位、**跳过部分片段**——
     * 表现为"有些片段永远没有新代际的向量"，且无任何报错。
     *
     * @param tenantId   租户 ID
     * @param generation 目标代际（必须是 BUILDING）
     * @return 本批处理数与是否已建完
     * @throws IllegalStateException 目标代际不处于 BUILDING
     */
    public BatchOutcome buildBatch(@NonNull String tenantId, int generation) {
        requireBuildingStatus(tenantId, generation);
        return advanceOn(tenantChannel(tenantId), tenantId, generation);
    }

    /**
     * 推进**全局桶**的一个批次（RAG-D22 §7.2 待办 #1）。
     *
     * <p><b>⚠️ 为什么需要单独的方法而不是给 buildBatch 传全局哨兵当租户</b>：
     * 全局桶的写通道跑在 **BYPASSRLS 的运维角色**上，
     * 且 `set_config('app.tenant_id', <哨兵>)` 会让 {@code rag_current_tenant()} 直接抛错
     * （V8 封堵「租户伪装成平台身份」）。故通道选择必须走**方法名**，
     * 而不是参数值——与 {@code ChunkIndexer#indexGlobalCorpus} 同一处理。
     *
     * <p>⚠️ 运维通道未接入时抛 {@link cn.studykid.rag.infrastructure.ops.RagOpsUnavailableException}，
     * <b>不</b>回落应用连接：回落的结果不是"写得差一点"，
     * 而是 RLS 把全局写静默滤成 0 行、界面却显示"构建完成"。
     *
     * @param generation 目标代际
     * @return 本批处理数与是否已建完
     */
    public BatchOutcome buildGlobalBatch(int generation) {
        String globalTenant = cn.studykid.rag.core.scope.RagScope.GLOBAL_TENANT_ID;
        requireBuildingStatus(globalTenant, generation);
        if (opsSession == null) {
            throw new cn.studykid.rag.infrastructure.ops.RagOpsUnavailableException(
                    "rag.ops.datasource",
                    "全局语料代际构建需要运维数据源（rag_ops），不回落应用角色");
        }
        return opsSession.inGlobalCorpusSession((template, tx) ->
                advanceOn(globalChannel(template, tx), globalTenant, generation));
    }

    /**
     * 推进一个批次（通道无关的核心流程）。
     *
     * <p>⚠️ 租户桶与全局桶共用这一段：两者的**唯一差异是"在哪条连接上跑"**。
     * 若各写一份，fail-closed 口径与游标推进逻辑必然漂移。
     */
    private BatchOutcome advanceOn(ScanChannel channel, String tenantId, int generation) {
        String label = channel.label();
        long total = currentTotal(tenantId, generation);
        String cursor = currentCursor(tenantId, generation);
        String fromCursor = cursor == null ? FIRST_CHUNK_ID : cursor;

        List<PendingChunk> page = scanBatch(channel, fromCursor);
        if (page.isEmpty()) {
            long done = currentIndexed(tenantId, generation);
            generationPort.updateBuildState(tenantId, generation, done,
                    Math.max(done, total), cursor);
            log.info("代际构建完成 bucket={} generation={} processed={}",
                    label, generation, done);
            return new BatchOutcome(0, true);
        }

        for (PendingChunk p : page) {
            indexOneOn(channel, tenantId, generation, p);
        }
        String newCursor = page.get(page.size() - 1).chunkId;
        long done = currentIndexed(tenantId, generation) + page.size();
        generationPort.updateBuildState(tenantId, generation, done,
                Math.max(done, total), newCursor);

        log.debug("代际构建推进 bucket={} generation={} batch={} done={}",
                label, generation, page.size(), done);
        return new BatchOutcome(page.size(), false);
    }

    /**
     * 构建一个代际（**同步跑完**，保留给测试与小数据量场景）。
     *
     * <p>⚠️ 生产应走 {@link #buildBatch} 由 worker 分批推进：
     * 本方法会阻塞调用线程直到全部完成。
     */
    public int build(@NonNull String tenantId, int generation) {
        requireBuildingStatus(tenantId, generation);

        long total = countEligible(tenantId);
        generationPort.updateBuildState(tenantId, generation, 0, total, null);
        log.info("代际构建开始 tenant={} generation={} total={}", tenantId, generation, total);

        int processed = 0;
        String cursor = FIRST_CHUNK_ID;
        ScanChannel channel = tenantChannel(tenantId);
        while (true) {
            List<PendingChunk> page = scanBatch(channel, cursor);
            if (page.isEmpty()) {
                break;
            }
            for (PendingChunk p : page) {
                indexOne(tenantId, generation, p);
                processed++;
            }
            cursor = page.get(page.size() - 1).chunkId;
            generationPort.updateBuildState(tenantId, generation, processed,
                    Math.max(processed, total), cursor);
            if (page.size() < batchSize) {
                break;
            }
        }

        generationPort.updateBuildState(tenantId, generation, processed,
                Math.max(processed, total), cursor);
        log.info("代际构建完成 tenant={} generation={} processed={}", tenantId, generation, processed);
        return processed;
    }

    /**
     * 处理单个片段：嵌入 → 写入指定代际。
     *
     * <p>⚠️ <b>不更新词法列</b>：{@code text_jieba} 与嵌入模型无关，
     * 换模型不需要重切词。动它只会让词法索引短暂不可用。
     *
     * <p>本方法不触碰扫描/事务模板，可被单测直接调用。
     */
    public void indexOne(@NonNull String tenantId, int generation, @NonNull PendingChunk pending) {
        indexOneOn(tenantChannel(tenantId), tenantId, generation, pending);
    }

    // ------------------------------------------------------------------

    /** 校验目标代际处于 BUILDING。 */
    private void requireBuildingStatus(@NonNull String tenantId, int generation) {
        List<String> statuses = generationPort.listGenerations(tenantId).stream()
                .filter(g -> g.generation() == generation)
                .map(g -> g.status().name())
                .toList();
        if (statuses.isEmpty()) {
            throw new IllegalStateException("代际不存在：" + generation);
        }
        if (!"BUILDING".equals(statuses.get(0))) {
            throw new IllegalStateException(
                    "代际 " + generation + " 处于 " + statuses.get(0)
                            + "，只允许构建 BUILDING 代际（ACTIVE 会破坏在线索引，RETIRED 会污染回滚目标）");
        }
    }

    /** 读取代际当前已处理数（用于累加）。 */
    private long currentIndexed(@NonNull String tenantId, int generation) {
        var g = findGen(tenantId, generation);
        return g == null ? 0 : g.indexedChunks();
    }

    private long currentTotal(@NonNull String tenantId, int generation) {
        var g = findGen(tenantId, generation);
        return g == null ? 0 : g.totalChunks();
    }

    private String currentCursor(@NonNull String tenantId, int generation) {
        var g = findGen(tenantId, generation);
        return g == null ? null : g.buildCursor();
    }

    private cn.studykid.rag.core.indexing.IndexGeneration findGen(
            @NonNull String tenantId, int generation) {
        return generationPort.listGenerations(tenantId).stream()
                .filter(g -> g.generation() == generation)
                .findFirst()
                .orElse(null);
    }

    private long countEligible(@NonNull String tenantId) {
        Long n = transactionTemplate.execute(status -> {
            jdbcTemplate.queryForObject(
                    "SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId);
            return jdbcTemplate.queryForObject(COUNT_SQL, Long.class);
        });
        return n == null ? 0 : n;
    }

    /** 全局桶的合格片段总数（供全局 prepare 使用）。 */
    private long countEligibleGlobal(@NonNull JdbcTemplate template,
                                     @NonNull TransactionTemplate tx) {
        Long n = tx.execute(status -> template.queryForObject(GLOBAL_COUNT_SQL, Long.class));
        return n == null ? 0 : n;
    }

    /**
     * 全局桶的 {@link #prepare}：统计总数并清零游标，由运维通道执行。
     *
     * @param generation 目标代际
     * @return 准备后的代际快照
     */
    public cn.studykid.rag.core.indexing.IndexGeneration prepareGlobal(int generation) {
        String globalTenant = cn.studykid.rag.core.scope.RagScope.GLOBAL_TENANT_ID;
        requireBuildingStatus(globalTenant, generation);
        if (opsSession == null) {
            throw new cn.studykid.rag.infrastructure.ops.RagOpsUnavailableException(
                    "rag.ops.datasource",
                    "全局语料代际构建需要运维数据源（rag_ops），不回落应用角色");
        }
        long total = opsSession.inGlobalCorpusSession(
                (template, tx) -> countEligibleGlobal(template, tx));
        generationPort.updateBuildState(globalTenant, generation, 0, total, null);
        log.info("全局代际构建已排队 generation={} total={}", generation, total);
        var after = findGen(globalTenant, generation);
        if (after == null) {
            throw new IllegalStateException("全局代际准备后读回失败：" + generation);
        }
        return after;
    }

    /** 租户通道（应用连接 + 本租户谓词 + 设会话租户）。 */
    private ScanChannel tenantChannel(@NonNull String tenantId) {
        return new ScanChannel(jdbcTemplate, transactionTemplate, SCAN_SQL, COUNT_SQL, tenantId);
    }

    /** 全局通道（运维连接 + 全局哨兵谓词 + **不设**会话租户）。 */
    private ScanChannel globalChannel(@NonNull JdbcTemplate template,
                                      @NonNull TransactionTemplate tx) {
        return new ScanChannel(template, tx, GLOBAL_SCAN_SQL, GLOBAL_COUNT_SQL, null);
    }

    /** 在给定通道上扫一批。 */
    private List<PendingChunk> scanBatch(@NonNull ScanChannel channel,
                                         @NonNull String afterChunkId) {
        List<PendingChunk> rows = channel.tx().execute(status -> {
            channel.bindSession();
            return channel.template().query(channel.scanSql(),
                    (rs, i) -> new PendingChunk(rs.getString(1), rs.getString(2)),
                    afterChunkId, batchSize);
        });
        return rows == null ? List.of() : rows;
    }

    /** 租户通道的单片段写入（保持既有签名，供同步 build 使用）。 */
    private void indexOneOn(@NonNull ScanChannel channel,
                            @NonNull String tenantId, int generation,
                            @NonNull PendingChunk pending) {
        float[] vector = embeddingPort.embed(List.of(pending.text)).get(0);
        if (channel.isGlobal()) {
            // ⚠️ 全局桶走专属写入方法：它在运维会话窗内执行、
            //    桶谓词写死 rag_global_tenant_id()，不接受租户参数。
            corpusRepository.upsertEmbeddingForGenerationInGlobalBucket(
                    pending.chunkId, vector, embeddingPort.modelVersion(), generation);
            return;
        }
        corpusRepository.upsertEmbeddingForGeneration(
                tenantId, pending.chunkId, vector, embeddingPort.modelVersion(), generation);
    }

    /** 待处理片段（id + 正文）。 */
    public record PendingChunk(@NonNull String chunkId, @NonNull String text) {
    }
}
