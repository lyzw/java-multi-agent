package cn.studykid.rag.infrastructure.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import cn.studykid.rag.core.chunk.Chunk;
import cn.studykid.rag.core.retrieval.RetrievalRequest;
import cn.studykid.rag.core.retrieval.RetrievalRequest.RetrievalMode;
import cn.studykid.rag.core.retrieval.RetrievalResult.ScoredChunk;
import cn.studykid.rag.core.scope.RagScope;
import cn.studykid.rag.core.scope.RagScopeFilter;
import cn.studykid.rag.core.source.TrustTier;
import cn.studykid.rag.core.version.EditionCoordinate;
import cn.studykid.rag.core.version.EditionCoordinate.GradeBand;
import cn.studykid.rag.core.version.EditionScope;
import cn.studykid.rag.core.version.EditionCoordinate.Publisher;
import cn.studykid.rag.core.version.EditionCoordinate.Subject;
import cn.studykid.rag.core.version.EditionCoordinate.Term;
import cn.studykid.rag.infrastructure.embedding.LocalDeterministicEmbeddingPort;
import cn.studykid.rag.infrastructure.persistence.JdbcRetrievalRepository;
import cn.studykid.rag.infrastructure.persistence.VectorColumn;
import cn.studykid.rag.ports.RetrievalPort;

/**
 * M2 影子观察期比对集成测试（RAG 侧）。
 *
 * <p><b>方法论（控制 / 影子双跑）</b>：
 * <ul>
 *   <li><b>控制路径</b> = 生产真实形态：应用角色 {@code rag_it_app}（NOBYPASSRLS）
 *       + {@code set_config('app.tenant_id', T)}（RLS 兼容层）+ 显式谓词 → RLS 与显式谓词双重生效；</li>
 *   <li><b>影子路径</b> = 退役 RLS 后的目标形态：{@code rag_it_admin}（BYPASSRLS，模拟 RLS 已关）
 *       + <b>无 set_config</b> + 显式谓词 → 仅显式谓词生效。</li>
 * </ul>
 * 两路径必须返回<b>完全相同</b>的可见行集合。若一致，即证明「显式谓词已能独立承担隔离」，
 * RLS 是冗余纵深 —— 这正是 M4 逐批退役 RLS 的安全前提（M2 出口门禁的对偶证据）。
 *
 * <p><b>守门</b>：需 {@code IT_RAG_PG_URL}（应用角色）与 {@code IT_RAG_PG_ADMIN_URL}
 * （BYPASSRLS 角色）同时设置；否则整类跳过。
 */
class RagShadowLiveIT {

    private static final String PG_URL_ENV = "IT_RAG_PG_URL";
    private static final String PG_USER_ENV = "IT_RAG_PG_USERNAME";
    private static final String PG_PASSWORD_ENV = "IT_RAG_PG_PASSWORD";
    private static final String PG_ADMIN_URL_ENV = "IT_RAG_PG_ADMIN_URL";
    private static final String PG_ADMIN_USER_ENV = "IT_RAG_PG_ADMIN_USERNAME";
    private static final String PG_ADMIN_PASSWORD_ENV = "IT_RAG_PG_ADMIN_PASSWORD";

    // ⚠️ 独立 UUID 命名空间（f0f0…），刻意避开 RetrievalRepositoryIT 的 a/b/c/d/e 夹具，
    // 避免共享 study_rag_it 库下的主键/唯一约束串扰。
    private static final String T = "f0f0f0f0-f0f0-f0f0-f0f0-f0f0f0f0f0f1";
    private static final String U = "f0f0f0f0-f0f0-f0f0-f0f0-f0f0f0f0f0f2";

    // 租户 T 夹具
    private static final String S = "f0f0f0f0-f0f0-f0f0-f0f0-f0f0f0f0f0f3";
    private static final String E = "f0f0f0f0-f0f0-f0f0-f0f0-f0f0f0f0f0f5";
    private static final String D = "f0f0f0f0-f0f0-f0f0-f0f0-f0f0f0f0f0f8";
    private static final String C1 = "f0f0f0f0-f0f0-f0f0-f0f0-f0f0f0f0f0a1";
    private static final String C2 = "f0f0f0f0-f0f0-f0f0-f0f0-f0f0f0f0f0a2";
    private static final String C3 = "f0f0f0f0-f0f0-f0f0-f0f0-f0f0f0f0f0a3";
    // 租户 U 夹具
    private static final String S2 = "f0f0f0f0-f0f0-f0f0-f0f0-f0f0f0f0f0b2";
    private static final String E3 = "f0f0f0f0-f0f0-f0f0-f0f0-f0f0f0f0f0c3";
    private static final String D3 = "f0f0f0f0-f0f0-f0f0-f0f0-f0f0f0f0f0d3";
    private static final String C4 = "f0f0f0f0-f0f0-f0f0-f0f0-f0f0f0f0f0e4";

    private static final EditionCoordinate E_PEP = new EditionCoordinate(
            Subject.MATH, GradeBand.GRADE_8, Term.FIRST, Publisher.PEP, "2024修订");

    private JdbcTemplate appJdbcTemplate;
    private JdbcTemplate adminJdbcTemplate;
    private TransactionTemplate transactionTemplate;
    private RetrievalPort controlRepo;   // 控制：app 角色 + set_config
    private RetrievalPort variantRepo;   // 影子：admin 角色(BYPASSRLS) + 无 set_config
    private final LocalDeterministicEmbeddingPort embedder = new LocalDeterministicEmbeddingPort();

    @BeforeAll
    static void guard() {
        Assumptions.assumeTrue(System.getenv(PG_URL_ENV) != null,
                "skipped: " + PG_URL_ENV + " 未设置（需要应用角色连接）");
        Assumptions.assumeTrue(System.getenv(PG_ADMIN_URL_ENV) != null,
                "skipped: " + PG_ADMIN_URL_ENV + " 未设置（需要 BYPASSRLS 角色做影子对照）");
    }

    @BeforeEach
    void setup() {
        DriverManagerDataSource appDs = new DriverManagerDataSource();
        appDs.setUrl(withRagSearchPath(System.getenv(PG_URL_ENV)));
        appDs.setUsername(envOrDefault(PG_USER_ENV, extractUserFromUrl(System.getenv(PG_URL_ENV))));
        appDs.setPassword(envOrDefault(PG_PASSWORD_ENV, null));
        appJdbcTemplate = new JdbcTemplate(appDs);
        transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(appDs));

        DriverManagerDataSource adminDs = new DriverManagerDataSource();
        adminDs.setUrl(withRagSearchPath(System.getenv(PG_ADMIN_URL_ENV)));
        adminDs.setUsername(envOrDefault(PG_ADMIN_USER_ENV, extractUserFromUrl(System.getenv(PG_ADMIN_URL_ENV))));
        adminDs.setPassword(envOrDefault(PG_ADMIN_PASSWORD_ENV, null));
        adminJdbcTemplate = new JdbcTemplate(adminDs);

        controlRepo = new JdbcRetrievalRepository(appJdbcTemplate, embedder,
                TrustTier.OFFICIAL_TUTORIAL,
                new VectorColumn(VectorColumn.TYPE_VECTOR, LocalDeterministicEmbeddingPort.DIM));
        variantRepo = new JdbcRetrievalRepository(adminJdbcTemplate, embedder,
                TrustTier.OFFICIAL_TUTORIAL,
                new VectorColumn(VectorColumn.TYPE_VECTOR, LocalDeterministicEmbeddingPort.DIM));

        cleanup();
        seedTenantT();
        seedTenantU();
    }

    @AfterEach
    void tearDown() {
        RagShadow.unregister();
    }

    // ── 测试 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("控制路径(RLS+GUC) 与 影子路径(显式谓词-only) 返回相同结果集")
    void controlEqualsVariant() {
        RetrievalRequest req = new RetrievalRequest(
                "二次函数的顶点", RetrievalMode.EXPLAIN,
                EditionScope.strict(E_PEP), null, null, null, 10);

        List<String> control = sortedIds(searchAs(T, req));
        List<String> variant = sortedIds(variantRepo.search(scopeFor(T), req).stream()
                .map(ScoredChunk::chunk).map(Chunk::id).toList());

        assertEquals(control, variant,
                "显式谓词(影子)应与 RLS+GUC(控制)返回完全相同的可见集合");
    }

    @Test
    @DisplayName("影子路径(显式谓词) 不泄露其它租户数据")
    void variantExcludesOtherTenant() {
        RetrievalRequest req = new RetrievalRequest(
                "二次函数的顶点", RetrievalMode.EXPLAIN,
                EditionScope.strict(E_PEP), null, null, null, 10);
        List<String> variant = variantRepo.search(scopeFor(T), req).stream()
                .map(ScoredChunk::chunk).map(Chunk::id).toList();
        assertFalse(variant.contains(C4), "影子路径按租户 T 检索，绝不应见租户 U 的 C4");
    }

    @Test
    @DisplayName("启用影子采集器后，真实读路径采集点被触发且无 mismatch")
    void collectorFiresOnRealReadPath() {
        InMemoryRagShadowMetrics metrics = new InMemoryRagShadowMetrics();
        RagShadowReporter reporter = new RagShadowReporter(metrics, false, null);
        RagShadowCollector collector = new RagShadowCollector(metrics, reporter);
        collector.attach();
        try {
            assertTrue(RagShadow.isEnabled());
            RetrievalRequest req = new RetrievalRequest(
                    "二次函数的顶点", RetrievalMode.EXPLAIN,
                    EditionScope.strict(E_PEP), null, null, null, 10);
            // 控制路径走 JdbcRetrievalRepository.search → 触发 RagShadow.collect("retrieval")
            searchAs(T, req);
            assertTrue(metrics.shadowSamples() > 0, "真实检索读路径应触发影子采集点");
            assertEquals(0, metrics.mismatches(), "控制==影子，不应有 mismatch");
            assertEquals(0, metrics.taintViolations());
        } finally {
            collector.destroy();
        }
        assertFalse(RagShadow.isEnabled());
    }

    // ── 辅助 ──────────────────────────────────────────────────────────

    private List<String> searchAs(String tenantId, RetrievalRequest req) {
        return transactionTemplate.execute(status -> {
            appJdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                    String.class, tenantId);
            return controlRepo.search(scopeFor(tenantId), req).stream()
                    .map(ScoredChunk::chunk).map(Chunk::id).toList();
        });
    }

    private static List<String> sortedIds(List<String> ids) {
        return ids.stream().sorted().toList();
    }

    private static RagScopeFilter scopeFor(String tenantId) {
        return new RagScopeFilter.TenantWithGlobal(tenantId);
    }

    private void cleanup() {
        adminJdbcTemplate.update("DELETE FROM rag_chunk_embedding");
        adminJdbcTemplate.update("DELETE FROM rag_chunk");
        adminJdbcTemplate.update("DELETE FROM rag_document");
        adminJdbcTemplate.update("DELETE FROM rag_edition");
        adminJdbcTemplate.update("DELETE FROM rag_source");
    }

    private void seedTenantT() {
        String g = RagScope.GLOBAL_TENANT_ID;
        adminJdbcTemplate.update("INSERT INTO rag_source (source_id, tenant_id, source_type, title, publisher, "
                + "trust_tier, ver_status, ver_evidence, verified_at, license_status, isbn, scope) "
                + "VALUES (?::uuid, ?::uuid, 'TEXTBOOK', 'fixture', ?, 'OFFICIAL_PUBLISHER', 'VERIFIED', "
                + "'fixture', now(), 'LICENSED', ?, 'TENANT') "
                + "ON CONFLICT (tenant_id, source_id) DO UPDATE SET scope = EXCLUDED.scope", S, T, "人教版", "ISBN-T");
        adminJdbcTemplate.update("INSERT INTO rag_edition (edition_id, tenant_id, source_id, subject, grade, term, "
                + "publisher, revision_code, scope) VALUES (?::uuid, ?::uuid, ?::uuid, '数学', '八年级', '上册', ?, '2024修订', 'TENANT') "
                + "ON CONFLICT (tenant_id, edition_id) DO UPDATE SET scope = EXCLUDED.scope", E, T, S, "人教版");
        adminJdbcTemplate.update("INSERT INTO rag_document (document_id, tenant_id, source_id, edition_id, title, "
                + "parse_status, ver_status, retrievable, license_status, scope) VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, "
                + "'fixture', 'CHUNKED', 'VERIFIED'::rag_verification_status, TRUE, 'LICENSED'::rag_license_status, 'TENANT') "
                + "ON CONFLICT (tenant_id, document_id) DO UPDATE SET scope = EXCLUDED.scope", D, T, S, E);
        insertChunk(C1, T, D, E, "EXPLANATION", "二次函数的顶点是抛物线的最高点或最低点");
        insertChunk(C2, T, D, E, "EXERCISE_ANSWER", "答案：顶点坐标为 (-b/2a, (4ac-b²)/4a)");
        insertChunk(C3, T, D, E, "EXPLANATION", "二次函数的顶点坐标定义与例题");
    }

    private void seedTenantU() {
        adminJdbcTemplate.update("INSERT INTO rag_source (source_id, tenant_id, source_type, title, publisher, "
                + "trust_tier, ver_status, ver_evidence, verified_at, license_status, isbn, scope) "
                + "VALUES (?::uuid, ?::uuid, 'TEXTBOOK', 'fixture', ?, 'OFFICIAL_PUBLISHER', 'VERIFIED', "
                + "'fixture', now(), 'LICENSED', ?, 'TENANT') "
                + "ON CONFLICT (tenant_id, source_id) DO UPDATE SET scope = EXCLUDED.scope", S2, U, "人教版", "ISBN-U");
        adminJdbcTemplate.update("INSERT INTO rag_edition (edition_id, tenant_id, source_id, subject, grade, term, "
                + "publisher, revision_code, scope) VALUES (?::uuid, ?::uuid, ?::uuid, '数学', '八年级', '上册', ?, '2024修订', 'TENANT') "
                + "ON CONFLICT (tenant_id, edition_id) DO UPDATE SET scope = EXCLUDED.scope", E3, U, S2, "人教版");
        adminJdbcTemplate.update("INSERT INTO rag_document (document_id, tenant_id, source_id, edition_id, title, "
                + "parse_status, ver_status, retrievable, license_status, scope) VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, "
                + "'fixture', 'CHUNKED', 'VERIFIED'::rag_verification_status, TRUE, 'LICENSED'::rag_license_status, 'TENANT') "
                + "ON CONFLICT (tenant_id, document_id) DO UPDATE SET scope = EXCLUDED.scope", D3, U, S2, E3);
        insertChunk(C4, U, D3, E3, "EXPLANATION", "二次函数顶点是抛物线的最高点或最低点");
    }

    private void seedGlobal() {
        // 注：RAG 全局桶（tenant_id = 全局哨兵）的影子对照已由 RetrievalRepositoryIT 等
        // 既有 IT 覆盖；本影子 IT 聚焦「租户隔离等价性」对照，刻意不重复 seed 全局桶，
        // 以避免与共享 study_rag_it 库上 RetrievalRepositoryIT 的全局夹具在
        // rag_edition_unique 等自然键上串扰。
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

    private static String withRagSearchPath(String url) {
        if (url == null || url.isBlank() || url.contains("search_path=")) {
            return url;
        }
        String sep = url.contains("?") ? "&" : "?";
        return url + sep + "options=-c%20search_path%3Drag%2Cpublic";
    }

    private static String envOrDefault(String name, String fallback) {
        String v = System.getenv(name);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    private static String extractUserFromUrl(String url) {
        if (url == null || !url.contains("@")) {
            return null;
        }
        String afterScheme = url.substring(url.indexOf("//") + 2);
        int at = afterScheme.indexOf('@');
        if (at <= 0) {
            return null;
        }
        String cred = afterScheme.substring(0, at);
        int colon = cred.indexOf(':');
        return colon > 0 ? cred.substring(0, colon) : cred;
    }
}
