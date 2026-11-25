package cn.studykid.rag.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import cn.studykid.rag.core.chunk.Chunk;
import cn.studykid.rag.core.chunk.ChunkRole;
import cn.studykid.rag.core.version.EditionCoordinate;

/**
 * 父子块落库的真库往返（RAG-D28 D08 G1 真语料端到端时补）。
 *
 * <p><b>⚠️ 为什么必须有这个 IT（这是真库抓到的缺陷，不是假想）</b>：
 * 2026-11-24 D08 G1 真语料端到端（沪教版五四制六年级数学上册，162 页）首次在
 * <b>真实 RDS</b> 上执行 DDL 18 之后的写路径，整册在 EMBEDDING 阶段失败：
 * <pre>
 *   ERROR: column "parent_chunk_id" is of type uuid but expression is of type character varying
 *   位置：774（整册未完成）
 * </pre>
 * 根因：{@code saveChunkAndEmbedding} 的 INSERT 把 {@code parent_chunk_id}
 * 以裸 {@code ?}（JDBC 绑定为 varchar）写进 {@code uuid} 列——PG 没有
 * varchar→uuid 的<b>赋值</b>转换，必须显式 {@code ?::uuid}。
 * <p>
 * <b>为什么单测/mock 全绿</b>：mock 的 {@code JdbcTemplate} 不执行真 SQL，
 * 参数类型不参与推断，varchar→uuid 的类型错配在真库才发作
 * （与批 10 的 {@code array_agg _text} 缺陷同类，铁律 #29：
 * <b>「只在真读库时发作」的缺陷单测抓不到</b>）。
 * <p>
 * <b>本 IT 的守门作用</b>：把「带 parent_chunk_id 的子块」真写真读，
 * 一旦有人把 {@code ?::uuid} 去掉，本 IT 立刻转红（写入抛 SQLGrammarException）。
 *
 * <p>守门：仅当 {@code IT_RAG_PG_URL} 设置时运行；未设置整类跳过（不计失败）。
 */
class ParentChildRoundTripIT {

    private static final String URL_ENV = "IT_RAG_PG_URL";
    private static final String USER_ENV = "IT_RAG_PG_USERNAME";
    private static final String PW_ENV = "IT_RAG_PG_PASSWORD";
    private static final String T = "11111111-1111-1111-1111-111111111111";
    // ⚠⚠️ 夹具主键必须**本类独占**，不得与其它 IT 类共用。
    //   原因（2026-11-24 实测踩到）：failsafe 按类名字母序跑，本类名首字母 P
    //   排在 RetrievalRepositoryIT 的 R 之前。若本类与它共用 edition/document 主键，
    //   本类的 `ON CONFLICT ... DO NOTHING` 先落地 → 它的 revision_code/publisher 等
    //   版本五轴值被本类覆盖 → RetrievalRepositoryIT 的「应召回首选版本」断言整片转红
    //   （实测 5 例假红）。共享主键 = 把「夹具谁先落地」变成事实上的测试顺序依赖。
    //   故本类用 9a9a… 前缀独占主键；租户 T 共享是设计内（租户无行，仅会话上下文）。
    private static final String SRC = "9a9a9a9a-0000-4000-8000-000000000001";
    private static final String ED = "9a9a9a9a-0000-4000-8000-000000000002";
    private static final String DOC = "9a9a9a9a-0000-4000-8000-000000000003";
    private static final String PARENT = "9a9a9a9a-0000-4000-8000-000000000004";
    private static final String CHILD = "9a9a9a9a-0000-4000-8000-000000000005";

    /**
     * ⚠️ 向量列形态<b>从库里读</b>，不写死。
     * <p>理由：IT 探针库（{@code scripts/rag-it-env.sh} 的 study_rag_it）刻意是
     * {@code vector(1024)}，而活库是 {@code halfvec(4096)}——两种形态都合法。
     * 若本 IT 写死其中一种，换库就红，而那条红与本 IT 要守的 uuid 缺陷无关，
     * 只会掩盖真正的守门点（这正是既有 {@code RetrievalRepositoryIT} 在活库全红的同款病）。
     * <p>故：读 {@code format_type} → 解析类型与维度 → 按实际形态造向量。
     */
    private static VectorColumn vectorColumn;

    /** 按实际列形态造的确定性单位向量（本 IT 只关心父子字段落库往返，向量内容无关）。 */
    private static float[] vector() {
        int dim = vectorColumn.dimension();
        float[] v = new float[dim];
        double sum = 0;
        for (int i = 0; i < dim; i++) {
            v[i] = (float) Math.sin(i * 0.5);
            sum += (double) v[i] * v[i];
        }
        float norm = (float) Math.sqrt(sum);
        for (int i = 0; i < dim; i++) {
            v[i] = v[i] / norm;
        }
        return v;
    }

    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;

    @BeforeAll
    static void setup() {
        Assumptions.assumeTrue(System.getenv(URL_ENV) != null && !System.getenv(URL_ENV).isBlank(),
                "skipped: " + URL_ENV + " 未设置");
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setUrl(withRagSearchPath(System.getenv(URL_ENV)));
        ds.setUsername(System.getenv(USER_ENV));
        ds.setPassword(System.getenv(PW_ENV));
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));
        vectorColumn = readVectorColumn();
    }

    /**
     * 从库里读回 {@code rag_chunk_embedding.embedding} 的真实类型与维度。
     *
     * <p>探针库是 {@code vector(1024)}、活库是 {@code halfvec(4096)}——两种都合法，
     * 本 IT 随库走，避免「换库即红」掩盖真正要守的缺陷。
     */
    private static VectorColumn readVectorColumn() {
        String declared = jdbc.queryForObject("""
                SELECT format_type(a.atttypid, a.atttypmod)
                  FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = 'rag' AND c.relname = 'rag_chunk_embedding'
                   AND a.attname = 'embedding' AND a.attnum > 0 AND NOT a.attisdropped
                """, String.class);
        // 形如 "halfvec(4096)" / "vector(1024)"
        String type = declared.substring(0, declared.indexOf('('));
        int dim = Integer.parseInt(declared.substring(declared.indexOf('(') + 1, declared.length() - 1));
        return new VectorColumn(type, dim);
    }

    private static String withRagSearchPath(String url) {
        if (url == null || url.isBlank() || url.contains("search_path=")) {
            return url;
        }
        String sep = url.contains("?") ? "&" : "?";
        return url + sep + "options=-c%20search_path%3Drag%2Cpublic";
    }

    private static void seedForeignKeys() {
        jdbc.update("""
                INSERT INTO rag_source (source_id, tenant_id, source_type, title, publisher,
                        trust_tier, ver_status, ver_evidence, verified_at, license_status, isbn, scope)
                VALUES (?::uuid, ?::uuid, 'TEXTBOOK', 'fixture', '人教版', 'OFFICIAL_PUBLISHER',
                        'VERIFIED', 'fixture', now(), 'LICENSED', 'isbn-parentchild-it', 'TENANT')
                ON CONFLICT (tenant_id, source_id) DO NOTHING
                """, SRC, T);
        jdbc.update("""
                INSERT INTO rag_edition (edition_id, tenant_id, source_id, subject, grade, term,
                        publisher, revision_code, scope)
                VALUES (?::uuid, ?::uuid, ?::uuid, '数学', '八年级', '上册', '人教版', '2024-pcit', 'TENANT')
                ON CONFLICT (tenant_id, edition_id) DO NOTHING
                """, ED, T, SRC);
        jdbc.update("""
                INSERT INTO rag_document (document_id, tenant_id, source_id, edition_id, title,
                        parse_status, ver_status, retrievable, license_status, scope)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'fixture', 'CHUNKED',
                        'VERIFIED', TRUE, 'LICENSED', 'TENANT')
                ON CONFLICT (document_id) DO NOTHING
                """, DOC, T, SRC, ED);
    }

    private static Chunk chunk(String id, String parentId, int level, String text) {
        EditionCoordinate edition = new EditionCoordinate(
                EditionCoordinate.Subject.MATH, EditionCoordinate.GradeBand.GRADE_8,
                EditionCoordinate.Term.FIRST, EditionCoordinate.Publisher.PEP, "2024");
        return new Chunk(id, DOC, edition, ChunkRole.EXPLANATION,
                text, new Chunk.Locator(5, "第3章", null, 0, 20),
                List.of(), null, "hash-" + id, null, parentId, level);
    }

    @Test
    @DisplayName("带 parent_chunk_id 的子块真库写入成功并逐字读回（varchar→uuid 类型错配守门）")
    void parentChildRoundTrip() {
        JdbcCorpusRepository repo = new JdbcCorpusRepository(jdbc, vectorColumn);
        float[] vector = vector();

        try {
            tx.executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                        String.class, T);
                seedForeignKeys();
                repo.saveChunkAndEmbedding(T, chunk(PARENT, null, Chunk.LEVEL_PARENT, "父块正文"),
                        ED, vector, "test-model");
                repo.saveChunkAndEmbedding(T, chunk(CHILD, PARENT, Chunk.LEVEL_CHILD, "子块正文"),
                        ED, vector, "test-model");
            });

            Object[] read = tx.execute(status -> {
                jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                        String.class, T);
                return jdbc.queryForObject("""
                        SELECT parent_chunk_id::text, chunk_level
                        FROM rag_chunk WHERE chunk_id = ?::uuid AND tenant_id = ?::uuid
                        """, (rs, i) -> new Object[]{rs.getString(1), rs.getInt(2)}, CHILD, T);
            });

            assertNotNull(read, "子块必须落库可读回");
            assertEquals(PARENT, read[0], "parent_chunk_id 必须逐字往返（uuid 列写入未丢值）");
            assertEquals(Chunk.LEVEL_CHILD, ((Integer) read[1]).intValue(),
                    "chunk_level 必须往返为 1（子块）");
        } finally {
            tx.executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                        String.class, T);
                jdbc.update("DELETE FROM rag_chunk_embedding WHERE chunk_id IN (?::uuid, ?::uuid)"
                        + " AND tenant_id = ?::uuid", CHILD, PARENT, T);
                jdbc.update("DELETE FROM rag_chunk WHERE chunk_id IN (?::uuid, ?::uuid)"
                        + " AND tenant_id = ?::uuid", CHILD, PARENT, T);
            });
        }
    }

    @Test
    @DisplayName("父块本身的 parent_chunk_id 为 NULL（顶层块不被误写成子块）")
    void parentHasNullParentId() {
        JdbcCorpusRepository repo = new JdbcCorpusRepository(jdbc, vectorColumn);
        float[] vector = vector();

        try {
            tx.executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                        String.class, T);
                seedForeignKeys();
                repo.saveChunkAndEmbedding(T, chunk(PARENT, null, Chunk.LEVEL_PARENT, "父块正文"),
                        ED, vector, "test-model");
            });

            String read = tx.execute(status -> {
                jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                        String.class, T);
                return jdbc.queryForObject("""
                        SELECT parent_chunk_id::text FROM rag_chunk
                        WHERE chunk_id = ?::uuid AND tenant_id = ?::uuid
                        """, String.class, PARENT, T);
            });

            assertTrue(read == null, "顶层父块的 parent_chunk_id 必须为 NULL，实际 " + read);
        } finally {
            tx.executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                        String.class, T);
                jdbc.update("DELETE FROM rag_chunk_embedding WHERE chunk_id = ?::uuid AND tenant_id = ?::uuid",
                        PARENT, T);
                jdbc.update("DELETE FROM rag_chunk WHERE chunk_id = ?::uuid AND tenant_id = ?::uuid",
                        PARENT, T);
            });
        }
    }

    @Test
    @DisplayName("非法 parent_id 必须显式失败（不得静默存垃圾）")
    void malformedParentIdFailsLoudly() {
        JdbcCorpusRepository repo = new JdbcCorpusRepository(jdbc, vectorColumn);
        float[] vector = vector();

        // ⚠️ 不是 UUID 的 parent_id 必须被 PG 的 uuid 转换拒绝——这正是「显式失败」
        //    的证据：去掉 ?::uuid 之前，这里会报类型错配；去掉 uuid 列之后，
        //    它会静默存进 varchar 语义（脏数据进库）。
        assertThrows(RuntimeException.class, () -> tx.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                    String.class, T);
            seedForeignKeys();
            repo.saveChunkAndEmbedding(T,
                    chunk(CHILD, "not-a-uuid", Chunk.LEVEL_CHILD, "坏父 id子块"),
                    ED, vector, "test-model");
        }), "非 UUID 的 parent_id 必须写库失败（不得静默落库）");
    }
}
