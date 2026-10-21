package cn.studykid.rag.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import cn.studykid.rag.core.chunk.Chunk;
// ⚠️ RetrievalMode 是 RetrievalRequest 的**嵌套**枚举，
//    直接 import ...core.retrieval.RetrievalMode 找不到符号（2026-10-22 编译实测）。
import cn.studykid.rag.core.retrieval.RetrievalRequest.RetrievalMode;
import cn.studykid.rag.core.retrieval.RetrievalRequest;
import cn.studykid.rag.core.retrieval.RetrievalResult.ScoredChunk;
import cn.studykid.rag.core.version.EditionCoordinate;
import cn.studykid.rag.core.version.EditionScope;
import cn.studykid.rag.core.version.EditionCoordinate.GradeBand;
import cn.studykid.rag.core.version.EditionCoordinate.Publisher;
import cn.studykid.rag.core.version.EditionCoordinate.Subject;
import cn.studykid.rag.core.version.EditionCoordinate.Term;
import cn.studykid.rag.infrastructure.embedding.LocalDeterministicEmbeddingPort;
import cn.studykid.rag.ports.RetrievalPort;

/**
 * 检索仓库集成测试（RAG-D05 §3 / RAG-D09 G2 条件⑤）。
 *
 * <p><b>⚠️ 守门</b>：仅在环境变量 {@code IT_RAG_PG_URL} 设置时运行（指向已应用
 * {@code rag} 迁移的 PostgreSQL 17 + pgvector 实例）。未设置则整类跳过——
 * 避免在无 DB 的 CI/本地环境报红。
 *
 * <p><b>⚠️ 租户上下文模拟</b>：主平台在工具执行事务内
 * {@code set_config('app.tenant_id', ...)}（RAG-D02 §1.1 单向调用约定）。
 * 本测试用 {@link TransactionTemplate} 在同一事务内 set_config 后调用
 * {@link RetrievalPort#search}，复现真实隔离路径；跨租户插入也通过切换
 * set_config 验证 RLS FORCE 真正生效。
 *
 * <p><b>⚠️ 诚实边界</b>：嵌入由 {@link LocalDeterministicEmbeddingPort}（确定性占位）
 * 生成，仅验证<b>链路与隔离</b>，不验证语义相关性（见该类的标注）。
 */
class RetrievalRepositoryIT {

    private static final String PG_URL_ENV = "IT_RAG_PG_URL";

    private static final String T = "11111111-1111-1111-1111-111111111111";
    private static final String U = "22222222-2222-2222-2222-222222222222";
    private static final String S = "33333333-3333-3333-3333-333333333333";
    private static final String S2 = "44444444-4444-4444-4444-444444444444";
    private static final String E = "55555555-5555-5555-5555-555555555555";
    private static final String E2 = "66666666-6666-6666-6666-666666666666";
    private static final String E3 = "77777777-7777-7777-7777-777777777777";
    private static final String D = "88888888-8888-8888-8888-888888888888";
    private static final String D3 = "99999999-9999-9999-9999-999999999999";
    private static final String C1 = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1";
    private static final String C2 = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2";
    private static final String C3 = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa3";
    private static final String C4 = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb4";

    private static final EditionCoordinate E_PEP = new EditionCoordinate(
            Subject.MATH, GradeBand.GRADE_8, Term.FIRST, Publisher.PEP, "2024修订");

    private JdbcTemplate jdbcTemplate;
    private TransactionTemplate transactionTemplate;
    private RetrievalPort repository;
    private final LocalDeterministicEmbeddingPort embedder = new LocalDeterministicEmbeddingPort();

    @BeforeAll
    static void guard() {
        Assumptions.assumeTrue(System.getenv(PG_URL_ENV) != null,
                "skipped: " + PG_URL_ENV + " 未设置（需要已应用 rag 迁移的 PostgreSQL 17 + pgvector）");
    }

    @BeforeEach
    void setup() {
        String url = System.getenv(PG_URL_ENV);
        DriverManagerDataSource ds = new DriverManagerDataSource(url);
        jdbcTemplate = new JdbcTemplate(ds);
        transactionTemplate = new TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));
        repository = new JdbcRetrievalRepository(jdbcTemplate, embedder);
        seedTenantT();
    }

    // ---------------------------------------------------------------------
    // 断言
    // ---------------------------------------------------------------------

    @Test
    void explanationReturnedAnswerSuppressedInExplainMode() {
        RetrievalRequest req = new RetrievalRequest(
                "二次函数的顶点", RetrievalMode.EXPLAIN, EditionScope.strict(E_PEP),
                null, null, null, 10);
        List<ScoredChunk> result = searchAs(T, req);
        List<String> ids = result.stream().map(s -> s.chunk().id()).toList();
        assertTrue(ids.contains(C1), "讲解模式下应召回讲解类片段 C1");
        assertFalse(ids.contains(C2), "讲解模式下答案类片段 C2 必须被角色过滤排除（泄题闸门）");
    }

    @Test
    void strictVersionFilterExcludesOtherPublisher() {
        RetrievalRequest req = new RetrievalRequest(
                "二次函数的顶点", RetrievalMode.EXPLAIN, EditionScope.strict(E_PEP),
                null, null, null, 10);
        List<ScoredChunk> result = searchAs(T, req);
        List<String> ids = result.stream().map(s -> s.chunk().id()).toList();
        assertTrue(ids.contains(C1), "应召回首选版本(人教版)的 C1");
        assertFalse(ids.contains(C3), "STRICT 模式下必须排除苏教版 E2 的 C3（版本串扰闸门）");
    }

    @Test
    void crossTenantIsolationEnforced() {
        seedTenantU();
        RetrievalRequest req = new RetrievalRequest(
                "二次函数的顶点", RetrievalMode.EXPLAIN, EditionScope.strict(E_PEP),
                null, null, null, 10);
        List<ScoredChunk> result = searchAs(T, req);
        List<String> ids = result.stream().map(s -> s.chunk().id()).toList();
        assertFalse(ids.contains(C4), "必须拒绝跨租户引用：C4 属租户 U，检索租户 T 不应见");
    }

    // ---------------------------------------------------------------------
    // 夹具：在同一事务内 set_config 模拟主平台传入的租户上下文
    // ---------------------------------------------------------------------

    private List<ScoredChunk> searchAs(String tenantId, RetrievalRequest req) {
        return transactionTemplate.execute(status -> {
            jdbcTemplate.update("SELECT set_config('app.tenant_id', ?, true)", tenantId);
            return repository.search(req);
        });
    }

    private void seedTenantT() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("SELECT set_config('app.tenant_id', ?, true)", T);
            insertTenant(T, "t-tenant");
            insertSource(S, T, "人教版", "ISBN-T");
            insertEdition(E, T, S, "人教版");
            insertEdition(E2, T, S, "苏教版");
            insertDocument(D, T, S, E);
            insertChunk(C1, T, D, E, "EXPLANATION",
                    "二次函数的顶点是抛物线的最高点或最低点");
            insertChunk(C2, T, D, E, "EXERCISE_ANSWER",
                    "答案：顶点坐标为 (-b/2a, (4ac-b²)/4a)");
            insertChunk(C3, T, D, E2, "EXPLANATION",
                    "苏教版二次函数的顶点坐标定义与例题");
            return null;
        });
    }

    private void seedTenantU() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("SELECT set_config('app.tenant_id', ?, true)", U);
            insertTenant(U, "u-tenant");
            insertSource(S2, U, "人教版", "ISBN-U");
            insertEdition(E3, U, S2, "人教版");
            insertDocument(D3, U, S2, E3);
            insertChunk(C4, U, D3, E3, "EXPLANATION",
                    "二次函数的顶点是抛物线的最高点或最低点");
            return null;
        });
    }

    private void insertTenant(String tenantId, String slug) {
        jdbcTemplate.update(
                "INSERT INTO rag_tenant (tenant_id, id, slug, name) VALUES (?, ?, ?, 'fixture') "
                        + "ON CONFLICT (tenant_id) DO NOTHING",
                tenantId, tenantId, slug);
    }

    private void insertSource(String sourceId, String tenantId, String publisher, String isbn) {
        jdbcTemplate.update(
                "INSERT INTO rag_source (source_id, tenant_id, source_type, title, publisher, "
                        + "trust_tier, ver_status, ver_evidence, verified_at, license_status, isbn) "
                        + "VALUES (?, ?, 'TEXTBOOK', 'fixture', ?, 'OFFICIAL_PUBLISHER', 'VERIFIED', "
                        + "'fixture', now(), 'LICENSED', ?) "
                        + "ON CONFLICT (tenant_id, source_id) DO NOTHING",
                sourceId, tenantId, publisher, isbn);
    }

    private void insertEdition(String editionId, String tenantId, String sourceId, String publisher) {
        jdbcTemplate.update(
                "INSERT INTO rag_edition (edition_id, tenant_id, source_id, subject, grade, term, "
                        + "publisher, revision_code) VALUES (?, ?, ?, '数学', '八年级', '上册', ?, '2024修订') "
                        + "ON CONFLICT (tenant_id, edition_id) DO NOTHING",
                editionId, tenantId, sourceId, publisher);
    }

    private void insertDocument(String documentId, String tenantId, String sourceId, String editionId) {
        jdbcTemplate.update(
                "INSERT INTO rag_document (document_id, tenant_id, source_id, edition_id, title, "
                        + "parse_status) VALUES (?, ?, ?, ?, 'fixture', 'CHUNKED') "
                        + "ON CONFLICT (tenant_id, document_id) DO NOTHING",
                documentId, tenantId, sourceId, editionId);
    }

    private void insertChunk(String chunkId, String tenantId, String documentId, String editionId,
                             String role, String text) {
        String vec = vectorToString(embedder.embed(List.of(text)).get(0));
        jdbcTemplate.update(
                "INSERT INTO rag_chunk (chunk_id, tenant_id, document_id, edition_id, role, text, "
                        + "page_number, section_path, char_start, char_end, kp_ids, content_hash, "
                        + "embed_model, index_generation) VALUES (?, ?, ?, ?, ?::rag_chunk_role, ?, "
                        + "45, 'chapter', 0, ?, '{}'::uuid[], 'h', 'local-deterministic-v1', 1) "
                        + "ON CONFLICT (tenant_id, chunk_id) DO NOTHING",
                chunkId, tenantId, documentId, editionId, role, text, text.length());
        jdbcTemplate.update(
                "INSERT INTO rag_chunk_embedding (chunk_id, tenant_id, index_generation, embed_model, "
                        + "embedding) VALUES (?, ?, 1, 'local-deterministic-v1', ?::vector) "
                        + "ON CONFLICT (chunk_id) DO NOTHING",
                chunkId, tenantId, vec);
    }

    /** 把 float[] 序列化为 pgvector 文本格式（与 JdbcRetrievalRepository 同算法）。 */
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
