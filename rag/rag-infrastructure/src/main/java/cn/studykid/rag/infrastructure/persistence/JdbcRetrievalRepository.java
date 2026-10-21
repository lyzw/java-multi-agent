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
import cn.studykid.rag.core.version.EditionCoordinate;
import cn.studykid.rag.core.version.EditionCoordinate.GradeBand;
import cn.studykid.rag.core.version.EditionCoordinate.Publisher;
import cn.studykid.rag.core.version.EditionCoordinate.Subject;
import cn.studykid.rag.core.version.EditionCoordinate.Term;
import cn.studykid.rag.core.version.EditionScope;
import cn.studykid.rag.infrastructure.text.ChineseTokenizer;
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
 * <p><b>⚠️ 租户隔离</b>：依赖 V1 的 RLS 策略，由调用方在事务内
 * {@code set_config('app.tenant_id', ..., true)} 提供当前租户；
 * 本类不自行解析令牌、不硬编码租户。SQL 中显式写 {@code c.tenant_id = rag_current_tenant()}
 * 仅用于提示优化器，<b>真正隔离由 RLS FORCE 保证</b>。
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
            ed.subject, ed.grade, ed.term, ed.publisher, ed.revision_code
            """;

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingPort embeddingPort;

    public JdbcRetrievalRepository(JdbcTemplate jdbcTemplate, EmbeddingPort embeddingPort) {
        if (jdbcTemplate == null) {
            throw new IllegalArgumentException("jdbcTemplate 不能为空");
        }
        if (embeddingPort == null) {
            throw new IllegalArgumentException("embeddingPort 不能为空");
        }
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingPort = embeddingPort;
    }

    @Override
    public @NonNull List<ScoredChunk> search(@NonNull RetrievalRequest request) {
        List<ScoredChunk> dense = denseSearch(request);
        List<ScoredChunk> lexical = lexicalSearch(request);

        // RRF 融合：按通道内排名倒数求和，排名取 1-based。
        Map<String, Double> fused = new LinkedHashMap<>();
        Map<String, ScoredChunk> byId = new HashMap<>();
        accumulate(fused, byId, dense);
        accumulate(fused, byId, lexical);

        List<ScoredChunk> merged = fused.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                .map(e -> {
                    ScoredChunk base = byId.get(e.getKey());
                    return new ScoredChunk(base.chunk(), e.getValue(),
                            base.channel(), base.reason());
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
    public @NonNull List<Chunk> findByKnowledgePoint(@NonNull String knowledgePointId,
                                                    @NonNull EditionScope editionScope) {
        String sql = """
                SELECT %s
                FROM rag_chunk c
                JOIN rag_edition ed ON c.edition_id = ed.edition_id AND c.tenant_id = ed.tenant_id
                JOIN rag_source src ON c.source_id = src.source_id AND c.tenant_id = src.tenant_id
                WHERE c.tenant_id = rag_current_tenant()
                  AND src.ver_status = 'VERIFIED'
                  AND c.kp_ids @> CAST(ARRAY[?::uuid] AS uuid[])
                """.formatted(SELECT_COLUMNS);
        List<Chunk> chunks = jdbcTemplate.query(sql, (rs, i) -> mapChunk(rs), knowledgePointId);
        // 版本过滤在 SQL 之外做：edition 坐标需 domain 对象比较。
        return chunks.stream()
                .filter(c -> editionScope.admits(c.edition()))
                .toList();
    }

    @Override
    public @NonNull List<Chunk> findPrerequisites(@NonNull String knowledgePointId,
                                                 @NonNull EditionScope editionScope) {
        String prereqSql = """
                SELECT prerequisites
                FROM rag_knowledge_point
                WHERE tenant_id = rag_current_tenant() AND kp_id = ?::uuid
                """;
        UUID[] prereqs = jdbcTemplate.queryForObject(
                prereqSql, (rs, i) -> rs.getObject("prerequisites", UUID[].class), knowledgePointId);
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
                JOIN rag_source src ON c.source_id = src.source_id AND c.tenant_id = src.tenant_id
                WHERE c.tenant_id = rag_current_tenant()
                  AND src.ver_status = 'VERIFIED'
                  AND c.kp_ids && ?::uuid[]
                """.formatted(SELECT_COLUMNS);
        List<Chunk> chunks = jdbcTemplate.query(sql, (rs, i) -> mapChunk(rs), arr.toString());
        return chunks.stream()
                .filter(c -> editionScope.admits(c.edition()))
                .toList();
    }

    @Override
    public @NonNull List<Chunk> findSectionNeighbors(@NonNull Chunk chunk, int limit) {
        String sql = """
                SELECT %s
                FROM rag_chunk c
                JOIN rag_edition ed ON c.edition_id = ed.edition_id AND c.tenant_id = ed.tenant_id
                JOIN rag_source src ON c.source_id = src.source_id AND c.tenant_id = src.tenant_id
                WHERE c.tenant_id = rag_current_tenant()
                  AND src.ver_status = 'VERIFIED'
                  AND c.document_id = ?
                  AND c.chunk_id <> ?
                ORDER BY abs(c.page_number - ?), c.char_start
                LIMIT ?
                """.formatted(SELECT_COLUMNS);
        return jdbcTemplate.query(sql, (rs, i) -> mapChunk(rs),
                chunk.documentId(), chunk.id(), chunk.locator().pageNumber(), limit);
    }

    @Override
    public @NonNull List<KnowledgePoint> resolveKnowledgePoints(@NonNull String query,
                                                              @Nullable String subject) {
        String sql = """
                SELECT kp_id, subject, grade, canonical_name, aliases, prerequisites, facets
                FROM rag_knowledge_point
                WHERE tenant_id = rag_current_tenant()
                """ + (subject == null ? "" : " AND subject = ?");
        List<KnowledgePoint> all = jdbcTemplate.query(
                sql, (rs, i) -> mapKnowledgePoint(rs),
                subject == null ? new Object[0] : new Object[]{subject});
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

    private List<ScoredChunk> denseSearch(RetrievalRequest request) {
        float[] queryVector = embeddingPort.embed(List.of(request.query())).get(0);
        String vec = vectorToString(queryVector);
        int limit = request.topK() * CANDIDATE_MULTIPLIER;
        String sql = """
                SELECT %s,
                       (1 - (e.embedding <=> ?::vector)) AS score
                FROM rag_chunk c
                JOIN rag_chunk_embedding e
                     ON c.chunk_id = e.chunk_id AND c.tenant_id = e.tenant_id
                JOIN rag_edition ed
                     ON c.edition_id = ed.edition_id AND c.tenant_id = ed.tenant_id
                JOIN rag_source src
                     ON c.source_id = src.source_id AND c.tenant_id = src.tenant_id
                WHERE c.tenant_id = rag_current_tenant()
                  AND src.ver_status = 'VERIFIED'
                ORDER BY e.embedding <=> ?::vector
                LIMIT ?
                """.formatted(SELECT_COLUMNS);
        return jdbcTemplate.query(sql, (rs, i) -> toScoredChunk(rs,
                RetrievalChannel.DENSE_VECTOR, HitReason.DIRECT_MATCH), vec, vec, limit);
    }

    private List<ScoredChunk> lexicalSearch(RetrievalRequest request) {
        List<String> tokens = ChineseTokenizer.tokenizeToList(request.query());
        if (tokens.isEmpty()) {
            return List.of();
        }
        // ⚠️ 用 OR（|）连接词元：中文 bigram 的 AND 会过度收紧召回。
        //    ts_rank 后续排序，RRF 融合时再综合两通道。
        String tsQuery = String.join(" | ", tokens);
        int limit = request.topK() * CANDIDATE_MULTIPLIER;
        String sql = """
                SELECT %s,
                       ts_rank(to_tsvector('simple', c.text_jieba),
                               to_tsquery('simple', ?)) AS score
                FROM rag_chunk c
                JOIN rag_edition ed
                     ON c.edition_id = ed.edition_id AND c.tenant_id = ed.tenant_id
                JOIN rag_source src
                     ON c.source_id = src.source_id AND c.tenant_id = src.tenant_id
                WHERE c.tenant_id = rag_current_tenant()
                  AND src.ver_status = 'VERIFIED'
                  AND to_tsvector('simple', c.text_jieba) @@ to_tsquery('simple', ?)
                ORDER BY score DESC
                LIMIT ?
                """.formatted(SELECT_COLUMNS);
        return jdbcTemplate.query(sql, (rs, i) -> toScoredChunk(rs,
                RetrievalChannel.LEXICAL, HitReason.ALIAS_MATCH), tsQuery, tsQuery, limit);
    }

    private void accumulate(Map<String, Double> fused, Map<String, ScoredChunk> byId,
                            List<ScoredChunk> channel) {
        for (int rank = 0; rank < channel.size(); rank++) {
            ScoredChunk sc = channel.get(rank);
            String id = sc.chunk().id();
            double rrf = 1.0 / (RRF_K + (rank + 1));
            fused.merge(id, rrf, Double::sum);
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
        return new ScoredChunk(chunk, score, channel, reason);
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
        List<String> kpIds = arrayToStrings(rs.getObject("kp_ids", UUID[].class));
        String exerciseRef = rs.getString("exercise_ref");
        String contentHash = rs.getString("content_hash");
        EditionCoordinate edition = toEdition(rs);
        Chunk.Locator locator = new Chunk.Locator(
                page, sectionPath, exerciseNo, charStart, charEnd);
        return new Chunk(id, documentId, edition, role, text, locator,
                kpIds, exerciseRef, contentHash);
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
        List<String> prerequisites = arrayToStrings(rs.getObject("prerequisites", UUID[].class));
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
