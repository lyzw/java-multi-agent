package cn.studykid.rag.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;

import cn.studykid.rag.core.chunk.Chunk;
import cn.studykid.rag.core.chunk.ChunkRole;
import cn.studykid.rag.core.knowledge.KnowledgePoint;
import cn.studykid.rag.core.retrieval.RetrievalPolicy;
import cn.studykid.rag.core.retrieval.RetrievalRequest;
import cn.studykid.rag.core.retrieval.RetrievalResult.HitReason;
import cn.studykid.rag.core.retrieval.RetrievalResult.RetrievalChannel;
import cn.studykid.rag.core.retrieval.RetrievalResult.ScoredChunk;
import cn.studykid.rag.core.scope.RagScopeFilter;
import cn.studykid.rag.core.source.TrustTier;
import cn.studykid.rag.core.version.EditionCoordinate;
import cn.studykid.rag.core.version.EditionCoordinate.GradeBand;
import cn.studykid.rag.core.version.EditionCoordinate.Publisher;
import cn.studykid.rag.core.version.EditionCoordinate.Subject;
import cn.studykid.rag.core.version.EditionCoordinate.Term;
import cn.studykid.rag.core.version.EditionScope;
import cn.studykid.rag.infrastructure.persistence.scope.RagSqlFragment;
import cn.studykid.rag.infrastructure.persistence.scope.RagSqlScopeTranslator;
import cn.studykid.rag.infrastructure.shadow.RagShadow;
import cn.studykid.rag.infrastructure.text.ChineseTokenizer;
import cn.studykid.rag.management.core.CorpusSource;
import cn.studykid.rag.ports.EmbeddingPort;
import cn.studykid.rag.ports.RetrievalPort;

/**
 * 检索端口的 JDBC 实现（RAG-D05 §3 / RAG-D02 §3.2）。
 *
 * <p><b>双通道 + RRF 融合</b>：
 * <ul>
 *   <li><b>稠密通道</b>：pgvector 余弦相似度（{@code embedding <=> query}），
 *       向量由 {@link EmbeddingPort} 生成；</li>
 *   <li><b>词法通道</b>：{@code tsvector('simple', text_jieba)} + 应用层预分词，
 *       覆盖「二次函数顶点」这类<b>精确关键词</b>问题（纯稠密会把
 *       「二次函数」与「反二次函数」混淆）；</li>
 *   <li><b>RRF 融合</b>：两个通道按排名倒数融合，避免分数量纲不统一。</li>
 * </ul>
 *
 * <p><b>⚠️ 遵循端口契约（{@link RetrievalPort#search}）</b>：
 * 返回前<b>必须</b>应用 {@link EditionScope#admits} 版本过滤与
 * {@code RetrievalPolicy.allowsRole} 角色过滤——答案类内容不进应用层内存。
 * （编排层 {@code RetrievalOrchestrator} 会再做一次，幂等。）
 *
 * <p><b>⚠️ 租户隔离（双作用域，迁移 V8 起）</b>：读侧谓词是
 * {@code tenant_id IN (本租户, 全局哨兵)} —— <b>本租户私有数据 ∪ 平台公开数据</b>。
 *
 * <p><b>⚠️ 隔离权威口径（RLS 清理 · M3，2026-11-12）</b>：本类的租户谓词
 * <b>由调用方显式传入</b>的 {@link RagScopeFilter} 经 {@link RagSqlScopeTranslator}
 * 渲染成绑定参数，<b>不再</b>依赖 {@code rag_current_tenant()} 读取会话变量。
 * <ul>
 *   <li><b>为什么必须改</b>：{@code rag_current_tenant()} 读的是会话变量
 *       {@code app.tenant_id}；变量未绑定 ⇒ 返回 NULL ⇒
 *       {@code IN (NULL, 哨兵)} 恒不匹配 ⇒ <b>租户语料静默消失</b>
 *       （不报错，检索恒空，排查成本极高）；</li>
 *   <li><b>为什么 RLS 在场期行为不变</b>：显式谓词渲染出的集合与现役 RLS 读策略
 *       {@code tenant_id = ANY(ARRAY[rag_current_tenant(), rag_global_tenant_id()])}
 *       <b>逐字等价</b>（同一个本租户 + 同一个哨兵），两层同向收紧 ⇒ 结果集不变；</li>
 *   <li><b>为什么哨兵绑常量而不是函数</b>：{@code rag_global_tenant_id()} 是
 *       IMMUTABLE 常量函数（不依赖 GUC），但它随 M5 删除；
 *       应用侧已有同值 Java 常量 {@link cn.studykid.rag.core.scope.RagScope#GLOBAL_TENANT_ID}，
 *       绑常量可让 M5 删函数时 SQL 侧零改动。</li>
 * </ul>
 *
 * <p>⚠️⚠️ <b>SQL 里的显式租户谓词是功能性的，不是「优化器提示」</b>
 * （旧注释这么写过，是错的，且错得很危险）：RLS 读策略放开全局后，
 * 若这里仍写 {@code c.tenant_id = <本租户>}，
 * 全局语料会被这层 AND 条件<b>静默滤空</b>——实测同一份数据、同一个会话，
 * 老写法返回 1 行（只有自己）、新写法返回 2 行（全局 + 自己），
 * 见 {@code scripts/rag-corpus-scope-counterproof.sh} 的 P1-4/P1-1。
 * 「策略放开了但谓词没跟上」的表现是<b>全局语料召回不到且不报错</b>，
 * 与「语料库里确实没有」完全无法区分，所以两层必须同步改。
 *
 * <p><b>⚠️ JOIN 侧刻意不改</b>：全局行的 {@code tenant_id} 是哨兵值而非 NULL，
 * 于是 {@code ON c.tenant_id = ed.tenant_id} 这类等值 JOIN 天然成立
 * （若改成「tenant_id 可空」的表示法，这里会全部连不上，实测 0 行）。
 *
 * <p><b>⚠️ source 的关联路径</b>：V1 的三层模型是
 * {@code source（作品）→ document（载体）→ chunk（片段）}，
 * {@code rag_chunk} <b>没有 source_id 列</b>，来源须经 {@code rag_document} 中转：
 * {@code JOIN rag_document doc ON c.document_id = doc.document_id
 * JOIN rag_source src ON doc.source_id = src.source_id}。
 * 写 {@code c.source_id} 会得到 {@code column c.source_id does not exist}。
 *
 * <p><b>⚠️ 诚实边界</b>：本实现依赖的 {@link EmbeddingPort} 在生产中是 BGE-M3，
 * 当前由 {@code LocalDeterministicEmbeddingPort}（确定性占位）提供——
 * 见该类的标注。在真实模型接入前，稠密通道的<b>语义相关性</b>不可用于生产判定，
 * 仅能验证检索链路本身（RAG-D09 G2 条件⑤「端到端跑通合成夹具」）。
 */
public class JdbcRetrievalRepository implements RetrievalPort {

    /** RRF 常数 k（经验值，避免排名靠前者垄断融合分）。 */
    private static final double RRF_K = 60.0;

    /** 每个通道取 topK × 此倍数作为融合候选，再截断到 topK。 */
    private static final int CANDIDATE_MULTIPLIER = 3;

    /** 共用 SELECT 列（chunk 主体 + edition 五轴，用于构造 {@link Chunk}）。 */
    private static final String SELECT_COLUMNS = """
            c.chunk_id, c.document_id, c.role, c.text, c.page_number,
            c.section_path, c.exercise_no, c.char_start, c.char_end,
            c.kp_ids, c.exercise_ref, c.content_hash,
            c.parent_chunk_id, c.chunk_level,
            ed.subject, ed.grade, ed.term, ed.publisher, ed.revision_code
            """;

    /**
     * 检索侧的「<b>可准入</b>」谓词（<b>单一真源</b> · 6 处 SELECT 共用）。
     *
     * <p><b>⚠️⚠️ 修复前的真实缺陷：载体级撤回在检索侧完全无效</b>。
     * 此前 6 处检索 SELECT <b>只</b>过滤 {@code src.ver_status = 'VERIFIED'}，
     * <b>从不读</b> {@code rag_document.ver_status} 与 {@code rag_document.retrievable}
     * —— 而这正是管理端「撤回某个载体」所写的两列
     * （{@code JdbcCorpusManagementRepository} 的载体级校验与来源级撤回级联都写它们）。
     *
     * <p>后果：<b>撤回动作看起来成功，内容却照常被召回、照常被引用、照常进入答案</b>。
     * 载体级撤回在检索侧<b>形同虚设</b>——这是治理动作对运维「说谎」。
     *
     * <p>⚠️ <b>为什么必须放在 SQL 里而不是 Java 后置过滤</b>：
     * 后置过滤会让「已下架内容」先进入应用内存（再被丢掉），
     * 既没有 fail-closed 的语义，也与端口契约相悖。
     *
     * <p>⚠️ <b>来源与载体是两个独立轴</b>（RAG-D07 §2.3.1 G-0）：
     * 两者都要通过才可检索 —— 同一版本下「官方电子版」可留、
     * 「第三方扫描版」可单独撤回。
     *
     * <p>⚠️ <b>四轴齐备（评审 P0-7 · RAG-D07 §701 D2 的查询期硬过滤）</b>：
     * <ol>
     *   <li><b>G-0 两级校验</b>：来源与载体都 VERIFIED，且载体未被撤回；</li>
     *   <li><b>G-4 版权</b>：来源与载体的 {@code license_status} 都必须是
     *       {@code LICENSED} / {@code FAIR_USE_QUOTATION}（两个独立轴，任一未放行即整体不可检索）。
     *       ⚠️ 旧谓词只写 {@code <> 'RESTRICTED'}，于是登记占位态 {@code PENDING}
     *       也被当作「可用」——与管理面 {@link CorpusSource#licensePermitsRetrieval()}
     *       的「PENDING 不放行」口径直接矛盾。2026-11-10 六年级物料导入实测发现：
     *       版权轴没有任何写入路径，全库常驻 PENDING，检索 SQL 却照单全收。
     *       这里收紧为显式白名单，让「未声明版权」真正不可检索；</li>
     *   <li><b>staleness 校验有效期</b>：{@code verified_at + ver_valid_days} 过期即失效
     *       （须重新校验；VERIFIED 行必有 verified_at，DDL 约束保证）；</li>
     *   <li><b>G-3 权威门槛</b>：来源等级达到服务端配置的门槛（含等于）。
     *       ⚠️⚠️ <b>绝不能写 {@code trust_tier >= ?}</b>：PG 枚举的 {@code >=}
     *       按<b>声明顺序</b>比较，而 {@code rag_trust_tier} 声明序
     *       （{@code OFFICIAL_PUBLISHER} → UNVERIFIED）与权威语义<b>相反</b>——
     *       枚举序数上 {@code UNVERIFIED} 最大，恰恰最不权威（2026-10-27
     *       线上 RDS 实测踩坑：{@code >=} 写反导致全部正常语料被滤空，
     *       详见 {@code JdbcCorpusManagementRepository#trustTierFilterArgs}
     *       同款注释——同一个坑踩了两次）。故这里在构造期用
     *       {@link TrustTier#meets} 展开<b>达标等级 IN 列表</b>，
     *       与应用层判定语义一致，不依赖声明顺序这种隐式约定。
     *       门槛来自<b>服务端配置</b>（{@code rag.retrieval.min-trust-tier}，
     *       默认 OFFICIAL_TUTORIAL = RAG-R07 生产下限），
     *       <b>绝不读请求头</b>——调用方可伪造的 {@code X-Tenant-Min-Trust-Tier}
     *       从此只影响管理台展示口径，<b>不再参与检索准入</b>。
     *       逐租户差异化门槛待 platform 侧字段落地（RAG-R23），落地后改为随请求下发。</li>
     * </ol>
     *
     * <p>本谓词刻意<b>只含</b>检索准入轴；「未校验但审核员要看」走管理台
     * （其口径由 {@code RagTenantContext} 控制并显式标注），不与检索口径混用。
     */
    private static final String ADMISSIBLE_TEMPLATE = """
                  AND src.ver_status = 'VERIFIED'
                  AND doc.ver_status = 'VERIFIED'
                  AND doc.retrievable
                  AND src.license_status IN ('LICENSED', 'FAIR_USE_QUOTATION')
                  AND doc.license_status IN ('LICENSED', 'FAIR_USE_QUOTATION')
                  AND src.verified_at + make_interval(days => src.ver_valid_days) >= now()
                  AND src.trust_tier IN (%s)
            """;

    /** 构造期定格的可准入谓词（门槛是部署级策略，不是每查询可变的输入）。 */
    private final String admissiblePredicate;

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingPort embeddingPort;
    private final VectorColumn vectorColumn;

    /**
     * 作用域 → SQL 谓词的翻译器（M3 显式化）。
     *
     * <p>⚠️ 本类<b>不</b>自行拼租户谓词：谓词形态由 {@link RagSqlScopeTranslator}
     * 单点产出，避免「同一语义两处拼写」漂移（那正是全局语料被静默滤空的成因）。
     */
    private final RagSqlScopeTranslator scopeTranslator;

    /**
     * 完整构造器：显式指定租户权威门槛（评审 P0-7 · G-3）与向量列形态。
     *
     * <p>⚠️ 门槛是<b>部署级服务端策略</b>（RAG-R07：生产下限 OFFICIAL_TUTORIAL），
     * 构造期定格——不是调用方可传、可伪造的输入。
     *
     * <p>⚠️ {@code vectorColumn} 的维度<b>必须</b>与 {@code embeddingPort.dimension()} 一致：
     * 装配层直接用端口报告的维度构造它，这样「模型换维度」与「列迁维度」两处
     * 在启动时就是同一个数字，不会出现查询向量与列维度不符。
     *
     * @param minTrustTier 允许检索的最低权威等级（含：等于门槛的也可检索）
     * @param vectorColumn {@code rag_chunk_embedding.embedding} 的类型与维度
     */
    public JdbcRetrievalRepository(JdbcTemplate jdbcTemplate, EmbeddingPort embeddingPort,
                                   cn.studykid.rag.core.source.TrustTier minTrustTier,
                                   VectorColumn vectorColumn) {
        this(jdbcTemplate, embeddingPort, minTrustTier, vectorColumn,
                RagSqlScopeTranslator.withDefaultSentinel());
    }

    /**
     * 完整构造器（含作用域翻译器）。
     *
     * @param scopeTranslator 作用域翻译器（哨兵值须与
     *        {@link cn.studykid.rag.core.scope.RagScope#GLOBAL_TENANT_ID} 同值）
     */
    public JdbcRetrievalRepository(JdbcTemplate jdbcTemplate, EmbeddingPort embeddingPort,
                                   cn.studykid.rag.core.source.TrustTier minTrustTier,
                                   VectorColumn vectorColumn,
                                   RagSqlScopeTranslator scopeTranslator) {
        if (jdbcTemplate == null) {
            throw new IllegalArgumentException("jdbcTemplate 不能为空");
        }
        if (embeddingPort == null) {
            throw new IllegalArgumentException("embeddingPort 不能为空");
        }
        if (minTrustTier == null) {
            throw new IllegalArgumentException("minTrustTier 不能为空");
        }
        if (scopeTranslator == null) {
            throw new IllegalArgumentException("scopeTranslator 不能为空");
        }
        if (vectorColumn == null) {
            throw new IllegalArgumentException("vectorColumn 不能为空");
        }
        if (vectorColumn.dimension() != embeddingPort.dimension()) {
            throw new IllegalStateException("""
                    向量列维度 %d（%s）与嵌入模型 %s 的维度 %d 不符。
                    这会让每次稠密检索都在 PG 侧报「expected %d dimensions, not %d」，
                    故直接拒绝装配。请改 rag.embedding.dimension 或迁列，并全量重建索引。"""
                    .formatted(vectorColumn.dimension(), vectorColumn.type(),
                            embeddingPort.modelVersion(), embeddingPort.dimension(),
                            vectorColumn.dimension(), embeddingPort.dimension()));
        }
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingPort = embeddingPort;
        this.vectorColumn = vectorColumn;
        this.scopeTranslator = scopeTranslator;
        // ⚠️⚠️ 门槛展开为「达标等级 IN 列表」而非 `trust_tier >= ?`：
        //    PG 枚举按声明序比较，rag_trust_tier 声明序与权威语义**相反**
        //    （UNVERIFIED 序数最大却最不权威）——`>=` 会整体反转语义，
        //    把 UNVERIFIED 放进来、把 OFFICIAL_PUBLISHER 滤掉
        //    （2026-10-27 线上 RDS 实测踩坑；同款修复见
        //    JdbcCorpusManagementRepository#trustTierFilterArgs）。
        //    这里用 TrustTier.meets() 展开，语义与应用层判定完全一致。
        String qualifyingTiers = java.util.Arrays.stream(TrustTier.values())
                .filter(t -> t.meets(minTrustTier))
                .map(t -> "'" + t.name() + "'")
                .collect(java.util.stream.Collectors.joining(", "));
        this.admissiblePredicate = ADMISSIBLE_TEMPLATE.formatted(qualifyingTiers);
    }

    @Override
    public @NonNull List<ScoredChunk> search(@NonNull RagScopeFilter scope,
                                             @NonNull RetrievalRequest request) {
        // M2 影子观察采集点（R1 检索读面）。禁用时 RagShadow.collect 为 no-op。
        RagShadow.collect("retrieval");
        List<ScoredChunk> dense = denseSearch(scope, request);
        List<ScoredChunk> lexical = lexicalSearch(scope, request);

        // RRF 融合：按通道内排名倒数求和，排名取 1-based。
        Map<String, Double> fused = new LinkedHashMap<>();
        Map<String, ScoredChunk> byId = new HashMap<>();
        // ⚠️ 相关性分与融合分必须分开记：门禁要的是「这条到底有多相关」
        //    （稠密=余弦相似度，词法=ts_rank），排序要的是「各通道都排在前面的」。
        Map<String, Double> relevance = new HashMap<>();
        accumulate(fused, byId, relevance, dense);
        accumulate(fused, byId, relevance, lexical);

        List<ScoredChunk> merged = fused.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                .map(e -> {
                    ScoredChunk base = byId.get(e.getKey());
                    // ⚠️ 排序键是 RRF 融合分（e.getValue()），
                    //    门禁键是相关性分（relevance）——两者量纲不同，不可互换。
                    return new ScoredChunk(base.chunk(),
                            relevance.getOrDefault(e.getKey(), base.score()),
                            e.getValue(), base.channel(), base.reason());
                })
                .toList();

        // ⚠️ 端口契约：返回前应用版本 + 角色硬过滤（泄题闸门）。
        return merged.stream()
                .filter(s -> request.editionScope().admits(s.chunk().edition()))
                .filter(s -> RetrievalPolicy.allowsRole(s.chunk().role(), request.mode()))
                .limit(request.topK())
                .toList();
    }

    @Override
    public @NonNull List<Chunk> findByKnowledgePoint(@NonNull RagScopeFilter scope,
                                                     @NonNull String knowledgePointId,
                                                     @NonNull EditionScope editionScope) {
        RagSqlFragment frag = scopeTranslator.render(scope, "c.tenant_id");
        String sql = """
                SELECT %s
                FROM rag_chunk c
                JOIN rag_edition ed ON c.edition_id = ed.edition_id AND c.tenant_id = ed.tenant_id
                JOIN rag_document doc ON c.document_id = doc.document_id AND c.tenant_id = doc.tenant_id
                JOIN rag_source src ON doc.source_id = src.source_id AND c.tenant_id = src.tenant_id
                WHERE %s
%s
                  AND c.kp_ids @> CAST(ARRAY[?::uuid] AS uuid[])
                """.formatted(SELECT_COLUMNS, frag.sql(), admissiblePredicate);
        List<Chunk> chunks = jdbcTemplate.query(sql, (rs, i) -> mapChunk(rs),
                concat(frag.params(), new Object[]{}, knowledgePointId));
        // 版本过滤在 SQL 之外做：edition 坐标需 domain 对象比较。
        return chunks.stream()
                .filter(c -> editionScope.admits(c.edition()))
                .toList();
    }

    @Override
    public @NonNull List<Chunk> findPrerequisites(@NonNull RagScopeFilter scope,
                                                   @NonNull String knowledgePointId,
                                                   @NonNull EditionScope editionScope) {
        RagSqlFragment frag = scopeTranslator.render(scope, "tenant_id");
        String prereqSql = """
                SELECT prerequisites
                FROM rag_knowledge_point
                WHERE %s AND kp_id = ?::uuid
                """.formatted(frag.sql());
        UUID[] prereqs = queryForUuidArray(
                jdbcTemplate, prereqSql, concat(frag.params(), new Object[]{}, knowledgePointId));
        if (prereqs == null || prereqs.length == 0) {
            return List.of();
        }
        StringBuilder arr = new StringBuilder("{");
        for (int i = 0; i < prereqs.length; i++) {
            if (i > 0) {
                arr.append(',');
            }
            arr.append(prereqs[i]);
        }
        arr.append('}');
        String sql = """
                SELECT DISTINCT %s
                FROM rag_chunk c
                JOIN rag_edition ed ON c.edition_id = ed.edition_id AND c.tenant_id = ed.tenant_id
                JOIN rag_document doc ON c.document_id = doc.document_id AND c.tenant_id = doc.tenant_id
                JOIN rag_source src ON doc.source_id = src.source_id AND c.tenant_id = src.tenant_id
                WHERE %s
%s
                  AND c.kp_ids && ?::uuid[]
                """.formatted(SELECT_COLUMNS, frag.sql(), admissiblePredicate);
        List<Chunk> chunks = jdbcTemplate.query(sql, (rs, i) -> mapChunk(rs),
                concat(frag.params(), new Object[]{}, arr.toString()));
        return chunks.stream()
                .filter(c -> editionScope.admits(c.edition()))
                .toList();
    }

    @Override
    public @NonNull List<Chunk> findSectionNeighbors(@NonNull RagScopeFilter scope,
                                                     @NonNull Chunk chunk, int limit) {
        RagSqlFragment frag = scopeTranslator.render(scope, "c.tenant_id");
        String sql = """
                SELECT %s
                FROM rag_chunk c
                JOIN rag_edition ed ON c.edition_id = ed.edition_id AND c.tenant_id = ed.tenant_id
                JOIN rag_document doc ON c.document_id = doc.document_id AND c.tenant_id = doc.tenant_id
                JOIN rag_source src ON doc.source_id = src.source_id AND c.tenant_id = src.tenant_id
                WHERE %s
%s
                  AND c.document_id = ?::uuid
                  AND c.chunk_id <> ?::uuid
                ORDER BY abs(c.page_number - ?), c.char_start
                LIMIT ?
                """.formatted(SELECT_COLUMNS, frag.sql(), admissiblePredicate);
        return jdbcTemplate.query(sql, (rs, i) -> mapChunk(rs),
                concat(frag.params(), new Object[]{},
                        chunk.documentId(), chunk.id(), chunk.locator().pageNumber(), limit));
    }

    /**
     * 按 ID 批量取回片段（B5 生成期依据装配）。
     *
     * <p><b>⚠️ 与 {@link #search} 的三处刻意差异</b>：
     * <ul>
     *   <li><b>不调 {@link EmbeddingPort}</b>：取回不需要查询向量，
     *       少一次模型调用，也避免「取依据还要先算 embedding」的荒谬；</li>
     *   <li><b>不做版本/角色过滤</b>：那是应用层
     *       {@code GroundedGenerationService} 的职责（与检索期同源复用
     *       {@link RetrievalPolicy}）。在 SQL 里再滤一遍会产生两套规则；</li>
     *   <li><b>不排序</b>：顺序由调用方决定，数据库顺序无意义。</li>
     * </ul>
     *
     * <p><b>⚠️ {@code = ANY(?::uuid[])} 而不是 IN (...)</b>：
     * 入参数目不固定，拼 IN 列表既要手工转义又要防注入；
     * {@code = ANY} 传一个数组字面量即可，且Postgres 可走主键索引。
     * 数组字面量形如 {@code {uuid1,uuid2}}，元素已由上层校验为合法 UUID。
     *
     * <p><b>⚠️ VERIFIED 条件不能省</b>：来源被撤回（{@code REVOKED}）后，
     * 其片段虽仍在表里，但已不是「已授权语料」。若生成期不校验，
     * 就会出现「内容已下架但讲解仍引用它」——比不引用更糟。
     */
    @Override
    public @NonNull List<Chunk> loadByIds(@NonNull RagScopeFilter scope,
                                          @NonNull List<String> chunkIds) {
        // M2 影子观察采集点（R1 检索读面）。禁用时 RagShadow.collect 为 no-op。
        RagShadow.collect("retrieval");
        if (chunkIds.isEmpty()) {
            return List.of();
        }
        StringBuilder arr = new StringBuilder("{");
        for (int i = 0; i < chunkIds.size(); i++) {
            if (i > 0) {
                arr.append(',');
            }
            arr.append(chunkIds.get(i));
        }
        arr.append('}');
        RagSqlFragment frag = scopeTranslator.render(scope, "c.tenant_id");
        String sql = """
                SELECT %s
                FROM rag_chunk c
                JOIN rag_edition ed ON c.edition_id = ed.edition_id AND c.tenant_id = ed.tenant_id
                JOIN rag_document doc ON c.document_id = doc.document_id AND c.tenant_id = doc.tenant_id
                JOIN rag_source src ON doc.source_id = src.source_id AND c.tenant_id = src.tenant_id
                WHERE %s
%s
                  AND c.chunk_id = ANY(?::uuid[])
                """.formatted(SELECT_COLUMNS, frag.sql(), admissiblePredicate);
        return jdbcTemplate.query(sql, (rs, i) -> mapChunk(rs),
                concat(frag.params(), new Object[]{}, arr.toString()));
    }

    @Override
    public @NonNull List<KnowledgePoint> resolveKnowledgePoints(@NonNull RagScopeFilter scope,
                                                                 @NonNull String query,
                                                                 @Nullable String subject) {
        RagSqlFragment frag = scopeTranslator.render(scope, "tenant_id");
        String sql = """
                SELECT kp_id, subject, grade, canonical_name, aliases, prerequisites, facets
                FROM rag_knowledge_point
                WHERE %s
                """.formatted(frag.sql()) + (subject == null ? "" : " AND subject = ?");
        List<KnowledgePoint> all = jdbcTemplate.query(
                sql, (rs, i) -> mapKnowledgePoint(rs),
                subject == null ? frag.params().toArray()
                        : concat(frag.params(), new Object[]{}, subject));
        String normalized = KnowledgePoint.normalize(query);
        // ⚠️ 排序仅作「优先返回更具体（名称更长）的知识点」的启发式；
        //    真实置信度应由评测集标定（RAG-D08 §4）。
        return all.stream()
                .filter(kp -> kp.matches(normalized))
                .sorted(Comparator.comparingInt((KnowledgePoint kp) -> kp.canonicalName().length())
                        .reversed())
                .toList();
    }

    // ---------------------------------------------------------------------
    // 通道实现
    // ---------------------------------------------------------------------

    private List<ScoredChunk> denseSearch(RagScopeFilter scope, RetrievalRequest request) {
        // ⚠️ P3-1 / D29 DEC-P7 M5：query 侧拼 instruction，doc 侧不加——
        //    OpenAiCompatibleEmbeddingPort 没有 instruction 入参，只能在文本里拼。
        //    两侧模板不对称是刻意的：query 要「检索意图」语义，doc 要「知识陈述」语义。
        String queryInput = cn.studykid.rag.core.embedding.EmbeddingInputTemplate
                .renderQuery(request.query());
        float[] queryVector = embeddingPort.embed(List.of(queryInput)).get(0);
        vectorColumn.requireDimension(queryVector);
        String vec = vectorToString(queryVector);
        String cast = vectorColumn.castPlaceholder();
        int limit = request.topK() * CANDIDATE_MULTIPLIER;
        // ⚠️⚠️ 代际过滤（RAG-D13 §3.5.2 蓝绿切换）：
        //    同一片段可能有多个代际的向量（换模型时新旧并存），
        //    **只能读 ACTIVE 代际**——把两个不可比的向量空间混在一起算余弦相似度，
        //    会静默产出错误的相似度，且没有任何报错。
        //
        //    ⚠️ 用按行解析的子查询而不是单一参数：本查询作用域是
        //    「本租户 ∪ 全局哨兵」，两个租户可能在同一时刻处于不同代际
        //    （例如全局语料已切到代际 3、本租户还在代际 2）。
        //
        //    ⚠️ 无代际记录时退回「不过滤」（兼容迁移前的空库与存量数据）：
        //    子查询返回 NULL → `g.status = 'ACTIVE'` 不成立 → 用 COALESCE 兜底到「代际不限」。
        RagSqlFragment frag = scopeTranslator.render(scope, "c.tenant_id");
        String sql = """
                SELECT %s,
                       (1 - (e.embedding <=> %s)) AS score
                FROM rag_chunk c
                JOIN rag_chunk_embedding e
                     ON c.chunk_id = e.chunk_id AND c.tenant_id = e.tenant_id
                   AND (
                         -- 该租户有 ACTIVE 代际 → 只读它
                         e.index_generation = (
                           SELECT g.generation FROM rag_index_generation g
                            WHERE g.tenant_id = e.tenant_id AND g.status = 'ACTIVE'
                            LIMIT 1)
                         -- 该租户尚无代际记录（迁移前空库）→ 不过滤（兼容存量）
                         OR NOT EXISTS (
                           SELECT 1 FROM rag_index_generation g2
                            WHERE g2.tenant_id = e.tenant_id AND g2.status = 'ACTIVE')
                       )
                JOIN rag_edition ed
                     ON c.edition_id = ed.edition_id AND c.tenant_id = ed.tenant_id
                JOIN rag_document doc
                     ON c.document_id = doc.document_id AND c.tenant_id = doc.tenant_id
                JOIN rag_source src
                     ON doc.source_id = src.source_id AND c.tenant_id = src.tenant_id
                WHERE %s
%s
                ORDER BY e.embedding <=> %s
                LIMIT ?
                """.formatted(SELECT_COLUMNS, cast, frag.sql(), admissiblePredicate, cast);
        // ⚠️ 参数顺序必须与 SQL 中占位符出现顺序严格一致：
        //    ① 查询向量（SELECT 里的 cast）② 作用域参数 ③ 查询向量（ORDER BY 里的 cast）④ limit。
        //    顺序错了不会立刻报错，只会让「相似度」变成别的值——最难查的一类静默失真。
        return jdbcTemplate.query(sql, (rs, i) -> toScoredChunk(rs,
                RetrievalChannel.DENSE_VECTOR, HitReason.DIRECT_MATCH),
                concat(frag.params(), new Object[]{vec}, vec, limit));
    }

    private List<ScoredChunk> lexicalSearch(RagScopeFilter scope, RetrievalRequest request) {
        List<String> tokens = ChineseTokenizer.tokenizeToList(request.query());
        if (tokens.isEmpty()) {
            return List.of();
        }
        // ⚠️ 用 OR（|）连接词元：中文 bigram 的 AND 会过度收紧召回。
        //    ts_rank 后续排序，RRF 融合时再综合两通道。
        String tsQuery = String.join(" | ", tokens);
        int limit = request.topK() * CANDIDATE_MULTIPLIER;
        RagSqlFragment frag = scopeTranslator.render(scope, "c.tenant_id");
        String sql = """
                SELECT %s,
                       ts_rank(to_tsvector('simple', c.text_jieba),
                               to_tsquery('simple', ?)) AS score
                FROM rag_chunk c
                JOIN rag_edition ed
                     ON c.edition_id = ed.edition_id AND c.tenant_id = ed.tenant_id
                JOIN rag_document doc
                     ON c.document_id = doc.document_id AND c.tenant_id = doc.tenant_id
                JOIN rag_source src
                     ON doc.source_id = src.source_id AND c.tenant_id = src.tenant_id
                WHERE %s
%s
                  AND to_tsvector('simple', c.text_jieba) @@ to_tsquery('simple', ?)
                ORDER BY score DESC
                LIMIT ?
                """.formatted(SELECT_COLUMNS, frag.sql(), admissiblePredicate);
        // ⚠️ 参数顺序：① tsQuery（score 里的）② 作用域参数 ③ tsQuery（WHERE 里的）④ limit。
        return jdbcTemplate.query(sql, (rs, i) -> toScoredChunk(rs,
                RetrievalChannel.LEXICAL, HitReason.ALIAS_MATCH),
                concat(frag.params(), new Object[]{tsQuery}, tsQuery, limit));
    }

    /**
     * 把作用域参数与业务参数按给定顺序拼接成一个参数数组。
     *
     * <p><b>⚠️ 顺序在这里集中一次</b>，而不是在 8 个调用点各写一遍：
     * 分散写必然出现某处漏掉作用域参数（⇒ 少绑一个参数 ⇒ PG 报参数个数不符，
     * 尚属可见故障）或顺序错位（⇒ 值绑到别的占位符 ⇒ 静默失真，最难查）。
     *
     * @param scopeParams 作用域参数（由 {@link RagSqlScopeTranslator} 产出，顺序已定）
     * @param head 前置业务参数（出现在作用域参数<b>之前</b>的占位符）
     * @param rest 后置业务参数（出现在作用域参数<b>之后</b>的占位符）
     */
    private static Object[] concat(List<Object> scopeParams, Object[] head, Object... rest) {
        Object[] all = new Object[head.length + scopeParams.size() + rest.length];
        int i = 0;
        for (Object h : head) {
            all[i++] = h;
        }
        for (Object p : scopeParams) {
            all[i++] = p;
        }
        for (Object r : rest) {
            all[i++] = r;
        }
        return all;
    }

    private void accumulate(Map<String, Double> fused, Map<String, ScoredChunk> byId,
                            Map<String, Double> relevance, List<ScoredChunk> channel) {
        for (int rank = 0; rank < channel.size(); rank++) {
            ScoredChunk sc = channel.get(rank);
            String id = sc.chunk().id();
            double rrf = 1.0 / (RRF_K + (rank + 1));
            fused.merge(id, rrf, Double::sum);
            // ⚠️ 相关性分取各通道中的<b>最高值</b>：门禁判的是「这条内容有多相关」，
            //    不是「它在几个通道里排第几」。用 RRF 值当相关性分会让门禁恒不通过。
            relevance.merge(id, sc.score(), Math::max);
            // 优先保留稠密通道标记；否则用词法（merge 仅在已存在时触发 remap）。
            byId.merge(id, sc, (existing, newer) ->
                    existing.channel() == RetrievalChannel.DENSE_VECTOR ? existing : newer);
        }
    }

    private ScoredChunk toScoredChunk(ResultSet rs, RetrievalChannel channel, HitReason reason)
            throws SQLException {
        Chunk chunk = mapChunk(rs);
        double score = rs.getDouble("score");
        if (rs.wasNull()) {
            score = 0.0;
        }
        score = Math.max(0.0, Math.min(1.0, score));
        // ⚠️ fusionScore 这里是 0：本方法只产出<b>单通道</b>结果，
        //    融合分由 search() 里的 RRF 归并阶段统一写入。
        return new ScoredChunk(chunk, score, 0.0, channel, reason);
    }

    private Chunk mapChunk(ResultSet rs) throws SQLException {
        String id = rs.getString("chunk_id");
        String documentId = rs.getString("document_id");
        ChunkRole role = ChunkRole.valueOf(rs.getString("role"));
        String text = rs.getString("text");
        int page = rs.getInt("page_number");
        String sectionPath = rs.getString("section_path");
        String exerciseNo = rs.getString("exercise_no");
        int charStart = rs.getInt("char_start");
        int charEnd = rs.getInt("char_end");
        List<String> kpIds = uuidArrayToStrings(rs, "kp_ids");
        String exerciseRef = rs.getString("exercise_ref");
        String contentHash = rs.getString("content_hash");
        // ⚠️ 批 9 / P2-1：父子字段（铁律 #29——getString 后立刻取 wasNull，
        //    中间不读其他列，否则标志被冲掉）。
        String parentId = rs.getString("parent_chunk_id");
        boolean parentIsNull = rs.wasNull();
        int chunkLevel = rs.getInt("chunk_level");
        EditionCoordinate edition = toEdition(rs);
        Chunk.Locator locator = new Chunk.Locator(
                page, sectionPath, exerciseNo, charStart, charEnd);
        return new Chunk(id, documentId, edition, role, text, locator,
                kpIds, exerciseRef, contentHash, null,
                parentIsNull ? null : parentId, chunkLevel);
    }

    private KnowledgePoint mapKnowledgePoint(ResultSet rs) throws SQLException {
        String id = rs.getString("kp_id");
        String subject = rs.getString("subject");
        String grade = rs.getString("grade");
        String canonicalName = rs.getString("canonical_name");
        // ⚠️ 列类型不同（V1__rag_corpus_baseline.sql）：
        //    aliases = TEXT[]、prerequisites = UUID[]。两者都按实际类型读，
        //    统一交给 arrayToStrings(Object[]) 转字符串——
        //    若把 UUID[] 列按 String[].class 读，即使能编过，运行期也会直接抛类型错。
        List<String> aliases = arrayToStrings(rs.getObject("aliases", String[].class));
        List<String> prerequisites = uuidArrayToStrings(rs, "prerequisites");
        // ⚠️ facets 为 JSONB；本实现不引入 JSON 解析依赖，
        //    resolveKnowledgePoints 路径不消费 facets（编排层只用 canonicalName/aliases/id），
        //    故此处返回空列表。接入 Jackson 后可补全（见 RAG-D09 R3-1 评测集需求）。
        List<KnowledgePoint.Facet> facets = List.of();
        return new KnowledgePoint(id, subject, grade, canonicalName, aliases, prerequisites, facets);
    }

    private EditionCoordinate toEdition(ResultSet rs) throws SQLException {
        Subject subject = subjectFromDb(rs.getString("subject"));
        GradeBand grade = gradeFromDb(rs.getString("grade"));
        Term term = termFromDb(rs.getString("term"));
        Publisher publisher = publisherFromDb(rs.getString("publisher"));
        String revisionCode = rs.getString("revision_code");
        return new EditionCoordinate(subject, grade, term, publisher, revisionCode);
    }

    /**
     * 数组列 → 字符串列表。
     *
     * <p>⚠️ 参数用 {@code Object[]} 而非 {@code UUID[]}（2026-10-22 修复）：
     * 原实现只接 {@code UUID[]}，而 {@code rag_knowledge_point.aliases} 是 {@code TEXT[]}，
     * 于是该文件**根本无法编译**（T 的上限不兼容）；且 {@code prerequisites}
     * 原本被按 {@code String[].class} 读取，与实际 {@code UUID[]} 不符，运行期必抛。
     * 两种列统一用 {@code String.valueOf} 归一，行为对 UUID 与文本都成立。
     */
    private static List<String> arrayToStrings(Object[] arr) {
        if (arr == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>(arr.length);
        for (Object o : arr) {
            if (o != null) {
                result.add(String.valueOf(o));
            }
        }
        return result;
    }

    /**
     * 读取 PostgreSQL 的 {@code uuid[]} 列。
     *
     * <p>⚠️ <b>不能用 {@code rs.getObject(col, UUID[].class)}</b>：pgjdbc 不支持
     * {@code _uuid → UUID[]} 转换，会抛
     * {@code conversion to class [Ljava.util.UUID; from _uuid not supported}。
     * 必须经 {@link java.sql.Array#getArray()} 取原始 {@code Object[]}（元素已是 UUID）。
     *
     * @param rs     结果集
     * @param column 列名
     * @return UUID 的字符串形式列表（空数组返回空列表）
     */
    private static List<String> uuidArrayToStrings(ResultSet rs, String column) throws SQLException {
        java.sql.Array sqlArray = rs.getArray(column);
        if (sqlArray == null) {
            return List.of();
        }
        Object raw = sqlArray.getArray();
        if (!(raw instanceof Object[])) {
            return List.of();
        }
        return arrayToStrings((Object[]) raw);
    }

    /** 读取单列 {@code uuid[]} 查询结果（同样避开 pgjdbc 的 UUID[] 转换限制）。 */
    private static UUID[] queryForUuidArray(JdbcTemplate jdbcTemplate, String sql, Object... args) {
        List<UUID[]> result = jdbcTemplate.query(sql, (rs, i) -> {
            java.sql.Array arr = rs.getArray(1);
            if (arr == null) {
                return null;
            }
            Object raw = arr.getArray();
            if (!(raw instanceof Object[])) {
                return null;
            }
            Object[] items = (Object[]) raw;
            List<UUID> ids = new ArrayList<>(items.length);
            for (Object o : items) {
                if (o instanceof UUID) {
                    ids.add((UUID) o);
                } else if (o != null) {
                    ids.add(UUID.fromString(String.valueOf(o)));
                }
            }
            return ids.toArray(new UUID[0]);
        }, args);
        return result.isEmpty() ? null : result.get(0);
    }

    // ---------------------------------------------------------------------
    // 枚举反查：DB 存的是 displayName（如 '数学'/'上册'/'人教版'），
    // 需映射回 domain 枚举。各枚举在 rag-core 仅暴露 displayName()，
    // 此处按 displayName 线性查找（枚举基数很小，O(n) 可接受）。
    // ---------------------------------------------------------------------

    private static Subject subjectFromDb(String s) {
        for (Subject v : Subject.values()) {
            if (v.displayName().equals(s)) {
                return v;
            }
        }
        throw new IllegalStateException("未知学科值: " + s);
    }

    private static GradeBand gradeFromDb(String s) {
        for (GradeBand v : GradeBand.values()) {
            if (v.displayName().equals(s)) {
                return v;
            }
        }
        throw new IllegalStateException("未知年级值: " + s);
    }

    private static Term termFromDb(String s) {
        for (Term v : Term.values()) {
            if (v.displayName().equals(s)) {
                return v;
            }
        }
        throw new IllegalStateException("未知册次值: " + s);
    }

    private static Publisher publisherFromDb(String s) {
        for (Publisher v : Publisher.values()) {
            if (v.displayName().equals(s)) {
                return v;
            }
        }
        throw new IllegalStateException("未知出版社值: " + s);
    }

    /** 把 float[] 序列化为 pgvector 文本格式 {@code [v0,v1,...]}。 */
    private static String vectorToString(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(String.format("%.6f", v[i]));
        }
        sb.append(']');
        return sb.toString();
    }
}
