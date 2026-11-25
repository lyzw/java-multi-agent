package cn.studykid.rag.infrastructure.retirement;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.rag.core.chunk.Chunk;
import cn.studykid.rag.core.retrieval.RetrievalRequest;
import cn.studykid.rag.core.retrieval.RetrievalRequest.RetrievalMode;
import cn.studykid.rag.core.retrieval.RetrievalResult.ScoredChunk;
import cn.studykid.rag.core.scope.RagScope;
import cn.studykid.rag.core.scope.RagScopeFilter;
import cn.studykid.rag.core.source.TrustTier;
import cn.studykid.rag.core.version.EditionCoordinate;
import cn.studykid.rag.core.version.EditionCoordinate.GradeBand;
import cn.studykid.rag.core.version.EditionCoordinate.Publisher;
import cn.studykid.rag.core.version.EditionCoordinate.Subject;
import cn.studykid.rag.core.version.EditionCoordinate.Term;
import cn.studykid.rag.core.version.EditionScope;
import cn.studykid.rag.infrastructure.embedding.LocalDeterministicEmbeddingPort;
import cn.studykid.rag.infrastructure.persistence.JdbcRetrievalRepository;
import cn.studykid.rag.infrastructure.persistence.VectorColumn;
import cn.studykid.rag.ports.RetrievalPort;

/**
 * M4-R1 检索读面退役反向验证守卫（骨架，默认跳过，退役后 {@code -Dm4.r1.retired=true} 激活）。
 *
 * <p>退化面 = rag_chunk / rag_chunk_embedding / rag_document / rag_source（16 策略）。
 * 反向验证：退役 RLS 后，检索隔离<b>唯一</b>由 {@code JdbcRetrievalRepository} 的显式谓词承重；
 * 撤掉谓词（或生产代码误删谓词）即跨租户泄露。
 *
 * <p>本类同时给出两种正向验证：{@link #forwardIsolationHolds()}（基类统一 SQL 镜像）与
 * {@link #forwardIsolationViaProductionRepository()}（<b>生产代码级</b>，直接调真实 Repository，
 * 这才是真正能捕获「撤掉修复点会红」的守卫，R2/R3 子类应照搬此范式接线各自的 Repository）。
 */
class M4R1RetrievalRetirementReverseGuardIT extends AbstractRetirementReverseGuardIT {

    // 独立 UUID 命名空间（f1f1…），避开 RagShadowLiveIT 的 f0f0… 与 RetrievalRepositoryIT 的 a/b/c/d/e。
    private static final String T = "f1f1f1f1-f1f1-f1f1-f1f1-f1f1f1f1f1f1";
    private static final String U = "f1f1f1f1-f1f1-f1f1-f1f1-f2f2f2f2f2f2";
    private static final String S = "f1f1f1f1-f1f1-f1f1-f1f1-f3f3f3f3f3f3";
    private static final String E = "f1f1f1f1-f1f1-f1f1-f1f1-f4f4f4f4f4f4";
    private static final String D = "f1f1f1f1-f1f1-f1f1-f1f1-f5f5f5f5f5f5";
    private static final String C1 = "f1f1f1f1-f1f1-f1f1-f1f1-f6f6f6f6f6f1";
    private static final String S2 = "f1f1f1f1-f1f1-f1f1-f1f1-f7f7f7f7f7f2";
    private static final String E3 = "f1f1f1f1-f1f1-f1f1-f1f1-f8f8f8f8f8f3";
    private static final String D3 = "f1f1f1f1-f1f1-f1f1-f1f1-f9f9f9f9f9f3";
    private static final String C4 = "f1f1f1f1-f1f1-f1f1-f1f1-fafafafafafa";

    private static final EditionCoordinate E_PEP = new EditionCoordinate(
            Subject.MATH, GradeBand.GRADE_8, Term.FIRST, Publisher.PEP, "2024修订");

    private final LocalDeterministicEmbeddingPort embedder = new LocalDeterministicEmbeddingPort();

    @Override
    protected String batchProperty() {
        return "r1";
    }

    @Override
    protected String tenantId() {
        return T;
    }

    @Override
    protected String otherTenantId() {
        return U;
    }

    @Override
    protected String[] cleanupSql() {
        return new String[] {
                "DELETE FROM rag_chunk_embedding",
                "DELETE FROM rag_chunk",
                "DELETE FROM rag_document",
                "DELETE FROM rag_edition",
                "DELETE FROM rag_source"
        };
    }

    /** 本批 cleanup 会 BYPASSRLS 全表删片段/向量；全局哨兵桶非空就跳过（G-7 同款保护）。 */
    @Override
    protected void guardRealGlobalChunks() {
        Integer count = adminJdbcTemplate.queryForObject(
                "SELECT count(*) FROM rag.rag_chunk WHERE tenant_id = rag.rag_global_tenant_id()",
                Integer.class);
        long globalChunksBefore = count == null ? 0 : count;
        Assumptions.assumeTrue(globalChunksBefore == 0,
                "skipped: 全局桶已有 " + globalChunksBefore
                        + " 个真实片段，本批 cleanup() 会全表删片段/向量，宁可不跑");
    }

    @Override
    protected void seedTenants() {
        adminJdbcTemplate.update("INSERT INTO rag_source (source_id, tenant_id, source_type, title, publisher, "
                + "trust_tier, ver_status, ver_evidence, verified_at, license_status, isbn, scope) "
                + "VALUES (?::uuid, ?::uuid, 'TEXTBOOK', 'fixture', ?, 'OFFICIAL_PUBLISHER', 'VERIFIED', "
                + "'fixture', now(), 'LICENSED', ?, 'TENANT') "
                + "ON CONFLICT (tenant_id, source_id) DO UPDATE SET scope = EXCLUDED.scope",
                S, T, "人教版", "ISBN-T");
        adminJdbcTemplate.update("INSERT INTO rag_edition (edition_id, tenant_id, source_id, subject, grade, term, "
                + "publisher, revision_code, scope) VALUES (?::uuid, ?::uuid, ?::uuid, '数学', '八年级', '上册', ?, '2024修订', 'TENANT') "
                + "ON CONFLICT (tenant_id, edition_id) DO UPDATE SET scope = EXCLUDED.scope",
                E, T, S, "人教版");
        adminJdbcTemplate.update("INSERT INTO rag_document (document_id, tenant_id, source_id, edition_id, title, "
                + "parse_status, ver_status, retrievable, license_status, scope) VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, "
                + "'fixture', 'CHUNKED', 'VERIFIED'::rag_verification_status, TRUE, 'LICENSED'::rag_license_status, 'TENANT') "
                + "ON CONFLICT (tenant_id, document_id) DO UPDATE SET scope = EXCLUDED.scope",
                D, T, S, E);
        insertChunk(C1, T, D, E, "EXPLANATION", "二次函数的顶点是抛物线的最高点或最低点");

        adminJdbcTemplate.update("INSERT INTO rag_source (source_id, tenant_id, source_type, title, publisher, "
                + "trust_tier, ver_status, ver_evidence, verified_at, license_status, isbn, scope) "
                + "VALUES (?::uuid, ?::uuid, 'TEXTBOOK', 'fixture', ?, 'OFFICIAL_PUBLISHER', 'VERIFIED', "
                + "'fixture', now(), 'LICENSED', ?, 'TENANT') "
                + "ON CONFLICT (tenant_id, source_id) DO UPDATE SET scope = EXCLUDED.scope",
                S2, U, "人教版", "ISBN-U");
        adminJdbcTemplate.update("INSERT INTO rag_edition (edition_id, tenant_id, source_id, subject, grade, term, "
                + "publisher, revision_code, scope) VALUES (?::uuid, ?::uuid, ?::uuid, '数学', '八年级', '上册', ?, '2024修订', 'TENANT') "
                + "ON CONFLICT (tenant_id, edition_id) DO UPDATE SET scope = EXCLUDED.scope",
                E3, U, S2, "人教版");
        adminJdbcTemplate.update("INSERT INTO rag_document (document_id, tenant_id, source_id, edition_id, title, "
                + "parse_status, ver_status, retrievable, license_status, scope) VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, "
                + "'fixture', 'CHUNKED', 'VERIFIED'::rag_verification_status, TRUE, 'LICENSED'::rag_license_status, 'TENANT') "
                + "ON CONFLICT (tenant_id, document_id) DO UPDATE SET scope = EXCLUDED.scope",
                D3, U, S2, E3);
        insertChunk(C4, U, D3, E3, "EXPLANATION", "二次函数顶点是抛物线的最高点或最低点");
    }

    private void insertChunk(String chunkId, String tenantId, String documentId, String editionId,
                             String role, String text) {
        String vec = vectorToString(embedder.embed(List.of(text)).get(0));
        adminJdbcTemplate.update(
                "INSERT INTO rag_chunk (chunk_id, tenant_id, document_id, edition_id, role, text, "
                        + "page_number, section_path, char_start, char_end, kp_ids, content_hash, "
                        + "embed_model, index_generation, scope) VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, "
                        + "?::rag_chunk_role, ?, 45, 'chapter', 0, ?, '{}'::uuid[], 'h', "
                        + "'local-deterministic-v1', 1, ?) "
                        + "ON CONFLICT (tenant_id, chunk_id) DO UPDATE SET role = EXCLUDED.role, "
                        + "text = EXCLUDED.text, index_generation = EXCLUDED.index_generation, scope = EXCLUDED.scope",
                chunkId, tenantId, documentId, editionId, role, text, text.length(),
                tenantId.equals(RagScope.GLOBAL_TENANT_ID) ? "PLATFORM" : "TENANT");
        adminJdbcTemplate.update(
                "INSERT INTO rag_chunk_embedding (chunk_id, tenant_id, index_generation, embed_model, "
                        + "embedding, scope) VALUES (?::uuid, ?::uuid, 1, 'local-deterministic-v1', ?::vector, ?) "
                        + "ON CONFLICT (chunk_id) DO UPDATE SET embedding = EXCLUDED.embedding, scope = EXCLUDED.scope",
                chunkId, tenantId, vec,
                tenantId.equals(RagScope.GLOBAL_TENANT_ID) ? "PLATFORM" : "TENANT");
    }

    private static String vectorToString(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(String.format("%.6f", v[i]));
        }
        return sb.append(']').toString();
    }

    @Override
    protected String scopedCountSql() {
        // 镜像 JdbcRetrievalRepository 检索读面的显式谓词（tenant_id = ?::uuid）。
        return "SELECT count(*) FROM rag_chunk WHERE tenant_id = ?::uuid";
    }

    @Override
    protected String unscopedCountSql() {
        // 撤掉谓词：模拟生产回归（RLS 已退役，无策略兜底）。
        return "SELECT count(*) FROM rag_chunk";
    }

    @Test
    @DisplayName("R1 正向（生产代码级）：JdbcRetrievalRepository.search 仅返回本租户 chunk（撤掉谓词会红）")
    void forwardIsolationViaProductionRepository() {
        RetrievalPort repo = new JdbcRetrievalRepository(appJdbcTemplate, embedder,
                TrustTier.OFFICIAL_TUTORIAL,
                new VectorColumn(VectorColumn.TYPE_VECTOR, LocalDeterministicEmbeddingPort.DIM));
        RetrievalRequest req = new RetrievalRequest(
                "二次函数的顶点", RetrievalMode.EXPLAIN,
                EditionScope.strict(E_PEP), null, null, null, 10);
        List<String> ids = repo.search(new RagScopeFilter.TenantWithGlobal(T), req).stream()
                .map(ScoredChunk::chunk).map(Chunk::id).toList();
        assertThat(ids).contains(C1);
        assertThat(ids).doesNotContain(C4);
    }
}
