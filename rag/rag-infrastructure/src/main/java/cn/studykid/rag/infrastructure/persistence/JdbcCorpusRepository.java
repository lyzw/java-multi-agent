package cn.studykid.rag.infrastructure.persistence;

import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import cn.studykid.rag.core.chunk.Chunk;
import cn.studykid.rag.infrastructure.ops.RagOpsSessionPort;
import cn.studykid.rag.infrastructure.ops.RagOpsUnavailableException;
import cn.studykid.rag.ports.CorpusRepository;

/**
 * 语料持久化的 JDBC 实现（RAG-D03 §1 / RAG-D02 §3.2）。
 *
 * <p><b>⚠️ 原子性</b>：{@link #saveChunkAndEmbedding} 在同事务内写入
 * {@code rag_chunk} 与 {@code rag_chunk_embedding}——两者缺一不可。
 *
 * <p><b>⚠️ 向量列形态不写死</b>：cast 与维度校验都来自 {@link VectorColumn}
 * （由装配层用 {@code EmbeddingPort.dimension()} 构造）——
 * 换嵌入模型时不会出现「SQL 还按 1024 维写、模型已返回 4096 维」。
 *
 * <p><b>⚠️ 租户隔离</b>：方法在事务内 {@code set_config('app.tenant_id', tenantId, true)}，
 * 使 V1 的 RLS FORCE 策略对本次写入生效（含子资源外键的复合租户约束）。
 * SQL 中显式取 {@code rag_current_tenant()} 做写入值是**功能性的**（不是优化器提示）：
 * 它把写入牢牢钉在「本租户」这一档，全局哨兵租户因此永远写不出来
 * （即便有人直连改 {@code app.tenant_id}，也会先被 {@code rag_current_tenant()} 抛错拦住）。
 */
public class JdbcCorpusRepository implements CorpusRepository {

    private final JdbcTemplate jdbcTemplate;
    private final VectorColumn vectorColumn;

    /**
     * 全局桶写通道（可为 null = 未接入）。
     *
     * <p>⚠️ null 时全局写方法抛 {@link RagOpsUnavailableException}（呈现 503），
     * <b>不</b>回落应用连接：回落的结果不是「写得差一点」，而是
     * RLS 对跨桶写入静默滤成 0 行、重索引却报告成功。
     */
    @Nullable
    private final RagOpsSessionPort opsSession;

    public JdbcCorpusRepository(JdbcTemplate jdbcTemplate, VectorColumn vectorColumn) {
        this(jdbcTemplate, vectorColumn, null);
    }

    public JdbcCorpusRepository(JdbcTemplate jdbcTemplate, VectorColumn vectorColumn,
                                @Nullable RagOpsSessionPort opsSession) {
        if (jdbcTemplate == null) {
            throw new IllegalArgumentException("jdbcTemplate 不能为空");
        }
        if (vectorColumn == null) {
            throw new IllegalArgumentException("vectorColumn 不能为空");
        }
        this.jdbcTemplate = jdbcTemplate;
        this.vectorColumn = vectorColumn;
        this.opsSession = opsSession;
    }

    @Override
    @Transactional
    public void saveChunkAndEmbedding(@NonNull String tenantId, @NonNull Chunk chunk,
                                     @NonNull String editionId, float[] embedding,
                                     @NonNull String embedModel) {
        vectorColumn.requireDimension(embedding);
        // ⚠️ 必须用 queryForObject() 而非 update()：set_config 是 SELECT，会返回一行，
        //    update() 期望「无结果集」，否则抛 DataIntegrityViolation
        //    （A result was returned when none was expected）。
        jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                String.class, tenantId);
        // ⚠️⚠️ V1.0.2 §4 Z11 / 不变式 3「陈旧向量不可能」——**重灌纪律内置化**：
        //    必须是 ON CONFLICT DO UPDATE 且**把 text_jieba 清成空串**。
        //    理由：ChunkIndexer 的待索引判据是 `text_jieba='' OR 无向量行`。
        //    若重灌只更新正文而不清分词列，该片段「看起来已索引」（分词列非空、
        //    向量行存在），于是永远不会被重新嵌入 → 改了切分规则但检索结果不变，
        //    这种「静默陈旧」在评测里表现为召回不全，极易被误归因为检索算法问题。
        //    ⚠️ 同理 content_hash 一并更新：它是「正文变没变」的判据。
        jdbcTemplate.update("""
                INSERT INTO rag_chunk
                    (chunk_id, tenant_id, document_id, edition_id, role, text, text_jieba,
                     page_number, section_path, exercise_no, char_start, char_end, kp_ids,
                     exercise_ref, content_hash, embed_model, index_generation)
                VALUES (?::uuid, rag_current_tenant(), ?::uuid, ?::uuid, ?::rag_chunk_role, ?,
                        '', ?, ?, ?, ?, ?, ?::uuid[], ?, ?, ?, 1)
                ON CONFLICT (chunk_id) DO UPDATE SET
                    role = EXCLUDED.role,
                    text = EXCLUDED.text,
                    text_jieba = '',
                    page_number = EXCLUDED.page_number,
                    section_path = EXCLUDED.section_path,
                    exercise_no = EXCLUDED.exercise_no,
                    char_start = EXCLUDED.char_start,
                    char_end = EXCLUDED.char_end,
                    kp_ids = EXCLUDED.kp_ids,
                    exercise_ref = EXCLUDED.exercise_ref,
                    content_hash = EXCLUDED.content_hash,
                    embed_model = EXCLUDED.embed_model,
                    index_generation = 1
                """,
                chunk.id(), chunk.documentId(), editionId, chunk.role().name(), chunk.text(),
                chunk.locator().pageNumber(), chunk.locator().sectionPath(),
                chunk.locator().exerciseNo(), chunk.locator().charStart(),
                chunk.locator().charEnd(), toUuidArrayLiteral(chunk.knowledgePointIds()),
                chunk.exerciseRef(), chunk.contentHash(), embedModel);
        jdbcTemplate.update("""
                INSERT INTO rag_chunk_embedding
                    (chunk_id, tenant_id, index_generation, embed_model, embedding)
                VALUES (?::uuid, rag_current_tenant(), 1, ?, %s)
                ON CONFLICT (chunk_id) DO UPDATE SET
                    index_generation = EXCLUDED.index_generation,
                    embed_model = EXCLUDED.embed_model,
                    embedding = EXCLUDED.embedding
                """.formatted(vectorColumn.castPlaceholder()),
                chunk.id(), embedModel, vectorToString(embedding));
    }

    @Override
    @Transactional
    public void markDocumentParseStatus(@NonNull String tenantId, @NonNull String documentId,
                                        @NonNull String parseStatus) {
        // ⚠️ 必须先声明租户会话：rag_document 受 RLS 约束，无租户上下文时
        //    UPDATE 被静默变成 0 行（不报错）——载体状态会「写不进去也不报错」。
        jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                String.class, tenantId);
        // ⚠️ document_id 必须显式转 uuid（列是 uuid，参数以字符串传入时
        //    PG 无 uuid = varchar 运算符）。
        jdbcTemplate.update(
                "UPDATE rag_document SET parse_status = ? WHERE document_id = ?::uuid",
                parseStatus, documentId);
    }

    @Override
    @Transactional
    public void updateJiebaTokens(@NonNull String tenantId, @NonNull String chunkId,
                                  @NonNull String jiebaTokens) {
        // ⚠️ 必须用 queryForObject() 而非 update()：set_config 是 SELECT，会返回一行，
        //    update() 期望「无结果集」，否则抛 DataIntegrityViolation
        //    （A result was returned when none was expected）。
        jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                String.class, tenantId);
        // ⚠️ chunk_id 必须显式转 uuid：列是 uuid，参数以字符串传入时
        //    PG 无 {@code uuid = varchar} 运算符，运行期直接报
        //    「operator does not exist: uuid = character varying」。
        //    这条路径此前从未被真正调用过（重索引从未跑通），故一直潜伏。
        jdbcTemplate.update(
                "UPDATE rag_chunk SET text_jieba = ? WHERE chunk_id = ?::uuid", jiebaTokens, chunkId);
    }

    @Override
    @Transactional
    public void upsertEmbedding(@NonNull String tenantId, @NonNull String chunkId,
                                @NonNull float[] embedding, @NonNull String embedModel) {
        vectorColumn.requireDimension(embedding);
        // ⚠️ 必须用 queryForObject() 而非 update()：set_config 是 SELECT，会返回一行，
        //    update() 期望「无结果集」，否则抛 DataIntegrityViolation
        //    （A result was returned when none was expected）。
        jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                String.class, tenantId);
        // ⚠️ ON CONFLICT (chunk_id)：片段已存在则更新向量与模型标识；否则插入。
        //    PK 为 chunk_id（单列），tenant_id 经 rag_current_tenant() 取值，
        //    与 V1 的 RLS WITH CHECK 一致（tenant_id = rag_current_tenant()）。
        // ⚠️ chunk_id 必须显式转 uuid：列是 uuid、参数以字符串传入时，
        //    PG 报「column chunk_id is of type uuid but expression is of type character varying」。
        //    2026-10-23 由管理面「重跑索引」端点首次真实触发并暴露（此前该路径从未跑通）。
        jdbcTemplate.update("""
                INSERT INTO rag_chunk_embedding
                    (chunk_id, tenant_id, index_generation, embed_model, embedding)
                VALUES (?::uuid, rag_current_tenant(), 1, ?, %s)
                ON CONFLICT (chunk_id) DO UPDATE SET
                    embedding = EXCLUDED.embedding,
                    embed_model = EXCLUDED.embed_model,
                    index_generation = 1
                """.formatted(vectorColumn.castPlaceholder()),
                chunkId, embedModel, vectorToString(embedding));
    }

    // ---------------------------------------------------------------------
    // 全局桶写入（第二刀 2.5）
    // ---------------------------------------------------------------------

    /**
     * 回填全局桶片段的词法分词列。
     *
     * <p>⚠️ <b>不标 {@code @Transactional}</b>：那个注解绑定的是<b>主</b>事务管理器，
     * 而本方法跑在运维连接上——注解只会白借一条应用连接，
     * 对运维连接上的原子性毫无作用（还会在窗口外触发 guarded 数据源的拒绝）。
     *
     * <p>⚠️ {@code AND tenant_id = rag_global_tenant_id()} <b>不是冗余</b>：
     * 租户版那条 UPDATE 靠 RLS 的 USING 谓词把行范围限在本租户，
     * 而运维角色带 BYPASSRLS、策略对它不设防 —— 去掉这个谓词，
     * 一个传错的 chunk_id 就会改到<b>别的租户</b>的词法列且无人拦。
     */
    @Override
    public void updateJiebaTokensInGlobalBucket(@NonNull String chunkId,
                                                @NonNull String jiebaTokens) {
        requireOpsSession().inGlobalCorpusSession((template, tx) -> template.update(
                "UPDATE rag_chunk SET text_jieba = ?"
                        + " WHERE chunk_id = ?::uuid AND tenant_id = rag_global_tenant_id()",
                jiebaTokens, chunkId));
    }

    /**
     * 写入或更新全局桶片段的向量。
     *
     * <p>⚠️ {@code ON CONFLICT ... DO UPDATE ... WHERE} 里那句租户判据同上是承重的：
     * {@code chunk_id} 是<b>全表</b>主键，冲突可能来自任意租户的行；
     * 不加 WHERE，冲突分支就成了一条「经 chunk_id 写进别人桶」的旁路。
     *
     * <p>⚠️ {@code scope} 必须<b>显式</b>写 {@code 'PLATFORM'}：该列 NOT NULL 且默认
     * {@code 'TENANT'}，而 {@code rag_chunk_embedding_scope_ck} 要求
     * 「{@code scope='PLATFORM' ⇔ tenant_id=哨兵}」——照租户版那样省略这一列，
     * 全局插入会因为「租户是哨兵、作用域却是 TENANT」被 CHECK <b>恒拒</b>
     * （2026-10-29 对空 DDL 逐列核对时发现；租户路径不踩这条，因为它的
     * {@code rag_current_tenant()} 永远不是哨兵，默认值恰好自洽）。
     */
    @Override
    public void upsertEmbeddingInGlobalBucket(@NonNull String chunkId,
                                              @NonNull float[] embedding,
                                              @NonNull String embedModel) {
        vectorColumn.requireDimension(embedding);
        requireOpsSession().inGlobalCorpusSession((template, tx) -> template.update("""
                INSERT INTO rag_chunk_embedding
                    (chunk_id, tenant_id, scope, index_generation, embed_model, embedding)
                VALUES (?::uuid, rag_global_tenant_id(), 'PLATFORM', 1, ?, %s)
                ON CONFLICT (chunk_id) DO UPDATE SET
                    embedding = EXCLUDED.embedding,
                    embed_model = EXCLUDED.embed_model,
                    index_generation = 1
                WHERE rag_chunk_embedding.tenant_id = rag_global_tenant_id()
                """.formatted(vectorColumn.castPlaceholder()),
                chunkId, embedModel, vectorToString(embedding)));
    }

    /** 全局写的前置：通道未接入就拒绝服务，绝不回落到应用角色。 */
    private RagOpsSessionPort requireOpsSession() {
        if (opsSession == null) {
            throw new RagOpsUnavailableException("rag.ops.datasource",
                    "全局语料写通道未接入：索引全局桶需要运维数据源（rag_ops），不回落应用角色");
        }
        return opsSession;
    }

    /** 把 UUID 列表格式化为 PostgreSQL 数组字面量 {@code {uuid,...}}。 */
    private static String toUuidArrayLiteral(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(ids.get(i));
        }
        sb.append('}');
        return sb.toString();
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
