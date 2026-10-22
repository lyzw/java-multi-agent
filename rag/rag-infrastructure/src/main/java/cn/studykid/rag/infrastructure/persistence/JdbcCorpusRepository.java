package cn.studykid.rag.infrastructure.persistence;

import java.util.List;

import org.jspecify.annotations.NonNull;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import cn.studykid.rag.core.chunk.Chunk;
import cn.studykid.rag.ports.CorpusRepository;

/**
 * 语料持久化的 JDBC 实现（RAG-D03 §1 / RAG-D02 §3.2）。
 *
 * <p><b>⚠️ 原子性</b>：{@link #saveChunkAndEmbedding} 在同事务内写入
 * {@code rag_chunk} 与 {@code rag_chunk_embedding}——两者缺一不可。
 *
 * <p><b>⚠️ 租户隔离</b>：方法在事务内 {@code set_config('app.tenant_id', tenantId, true)}，
 * 使 V1 的 RLS FORCE 策略对本次写入生效（含子资源外键的复合租户约束）。
 * SQL 中显式写 {@code rag_current_tenant()} 仅用于提示优化器，隔离由 RLS 保证。
 */
public class JdbcCorpusRepository implements CorpusRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcCorpusRepository(JdbcTemplate jdbcTemplate) {
        if (jdbcTemplate == null) {
            throw new IllegalArgumentException("jdbcTemplate 不能为空");
        }
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void saveChunkAndEmbedding(@NonNull String tenantId, @NonNull Chunk chunk,
                                     @NonNull String editionId, float[] embedding,
                                     @NonNull String embedModel) {
        // ⚠️ 必须用 queryForObject() 而非 update()：set_config 是 SELECT，会返回一行，
        //    update() 期望「无结果集」，否则抛 DataIntegrityViolation
        //    （A result was returned when none was expected）。
        jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                String.class, tenantId);
        jdbcTemplate.update("""
                INSERT INTO rag_chunk
                    (chunk_id, tenant_id, document_id, edition_id, role, text, page_number,
                     section_path, exercise_no, char_start, char_end, kp_ids, exercise_ref,
                     content_hash, embed_model, index_generation)
                VALUES (?, rag_current_tenant(), ?, ?, ?::rag_chunk_role, ?, ?, ?, ?, ?, ?,
                        ?::uuid[], ?, ?, ?, 1)
                """,
                chunk.id(), chunk.documentId(), editionId, chunk.role().name(), chunk.text(),
                chunk.locator().pageNumber(), chunk.locator().sectionPath(),
                chunk.locator().exerciseNo(), chunk.locator().charStart(),
                chunk.locator().charEnd(), toUuidArrayLiteral(chunk.knowledgePointIds()),
                chunk.exerciseRef(), chunk.contentHash(), embedModel);
        jdbcTemplate.update("""
                INSERT INTO rag_chunk_embedding
                    (chunk_id, tenant_id, index_generation, embed_model, embedding)
                VALUES (?, rag_current_tenant(), 1, ?, ?::vector)
                """,
                chunk.id(), embedModel, vectorToString(embedding));
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
        jdbcTemplate.update(
                "UPDATE rag_chunk SET text_jieba = ? WHERE chunk_id = ?", jiebaTokens, chunkId);
    }

    @Override
    @Transactional
    public void upsertEmbedding(@NonNull String tenantId, @NonNull String chunkId,
                                @NonNull float[] embedding, @NonNull String embedModel) {
        // ⚠️ 必须用 queryForObject() 而非 update()：set_config 是 SELECT，会返回一行，
        //    update() 期望「无结果集」，否则抛 DataIntegrityViolation
        //    （A result was returned when none was expected）。
        jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                String.class, tenantId);
        // ⚠️ ON CONFLICT (chunk_id)：片段已存在则更新向量与模型标识；否则插入。
        //    PK 为 chunk_id（单列），tenant_id 经 rag_current_tenant() 取值，
        //    与 V1 的 RLS WITH CHECK 一致（tenant_id = rag_current_tenant()）。
        jdbcTemplate.update("""
                INSERT INTO rag_chunk_embedding
                    (chunk_id, tenant_id, index_generation, embed_model, embedding)
                VALUES (?, rag_current_tenant(), 1, ?, ?::vector)
                ON CONFLICT (chunk_id) DO UPDATE SET
                    embedding = EXCLUDED.embedding,
                    embed_model = EXCLUDED.embed_model,
                    index_generation = 1
                """,
                chunkId, embedModel, vectorToString(embedding));
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
