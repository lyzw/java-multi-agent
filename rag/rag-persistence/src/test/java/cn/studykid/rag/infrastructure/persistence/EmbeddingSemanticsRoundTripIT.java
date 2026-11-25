package cn.studykid.rag.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * P3-1 语义槽位的真库往返（RAG-D28 批 11）。
 *
 * <p><b>⚠️ 为什么必须有真库 IT</b>：{@code saveChunkAndEmbedding} 的 INSERT 新增两列/两参，
 * 参数个数错位、列名拼错、ON CONFLICT 漏更新——mock 全部掩盖（单测不执行真 SQL）。
 * 上一批的 {@code array_agg _text} 缺陷正是这类「只在真读库时发作」（铁律 #29）。
 * 本类在真库写一条带语义的片段再读回，钉死列与值逐字一致。
 *
 * <p>守门：仅当 {@code IT_RAG_PG_URL} 设置时运行；未设置整类跳过（不计失败）。
 */
class EmbeddingSemanticsRoundTripIT {

    private static final String URL_ENV = "IT_RAG_PG_URL";
    private static final String USER_ENV = "IT_RAG_PG_USERNAME";
    private static final String PW_ENV = "IT_RAG_PG_PASSWORD";
    private static final String T = "11111111-1111-1111-1111-111111111111";
    // ⚠⚠️ 夹具主键必须**本类独占**（2026-11-24 修正）。
    //   原先本类复用 RetrievalRepositoryIT 的 edition/document 主键，靠
    //   `ON CONFLICT ... DO NOTHING` 打算「幂等共存」——实测不成立：
    //   failsafe 的类执行顺序是**文件系统扫描序**（不是字母序，也不是稳定序），
    //   谁先落地谁说了算。本类先跑时，它的 revision_code/publisher 等版本五轴值
    //   会覆盖 RetrievalRepositoryIT 的同名 edition（后者只 DO UPDATE scope，
    //   不更新五轴），于是对方「应召回首选版本」断言整片转红（实测 5 例假红）。
    //   ⇒ 共享主键 = 把「夹具谁先落地」变成事实上的测试顺序依赖。
    //   故本类改用 7b7b… 前缀独占主键；租户 T 共享是设计内（租户无行，仅会话上下文）。
    private static final String SRC = "7b7b7b7b-0000-4000-8000-000000000001";
    private static final String ED = "7b7b7b7b-0000-4000-8000-000000000002";
    private static final String DOC = "7b7b7b7b-0000-4000-8000-000000000003";
    private static final String CID = "7b7b7b7b-0000-4000-8000-000000000004";

    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    /** ⚠️ 从库读实际形态：IT 探针库是 vector(1024)、活库是 halfvec(4096)，两种都合法。 */
    private static VectorColumn vectorColumn;

    private static VectorColumn readVectorColumn() {
        String declared = jdbc.queryForObject("""
                SELECT format_type(a.atttypid, a.atttypmod)
                  FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = 'rag' AND c.relname = 'rag_chunk_embedding'
                   AND a.attname = 'embedding' AND a.attnum > 0 AND NOT a.attisdropped
                """, String.class);
        String type = declared.substring(0, declared.indexOf('('));
        int dim = Integer.parseInt(declared.substring(declared.indexOf('(') + 1, declared.length() - 1));
        return new VectorColumn(type, dim);
    }

    /** 按真实列维度造确定性单位向量（本 IT 只守语义列往返，向量内容无关）。 */
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

    @BeforeAll
    static void setup() {
        Assumptions.assumeTrue(System.getenv(URL_ENV) != null && !System.getenv(URL_ENV).isBlank(),
                "skipped: " + URL_ENV + " 未设置");
        DriverManagerDataSource ds = new DriverManagerDataSource();
        // ⚠️ 应用 SQL 用未限定表名（rag_chunk / rag_chunk_embedding）——
        //    自建 DriverManagerDataSource 无 init-sql，须用 options 参数设 search_path，
        //    与 RetrievalRepositoryIT.withRagSearchPath 同口径，否则清理 DELETE 找不到表。
        ds.setUrl(withRagSearchPath(System.getenv(URL_ENV)));
        ds.setUsername(System.getenv(USER_ENV));
        ds.setPassword(System.getenv(PW_ENV));
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));
        vectorColumn = readVectorColumn();
    }

    /** 幂等播种 rag_chunk 的 source/edition/document 外键链（与其他 IT 夹具同主键）。 */
    private static void seedForeignKeys() {
        jdbc.update("""
                INSERT INTO rag_source (source_id, tenant_id, source_type, title, publisher,
                        trust_tier, ver_status, ver_evidence, verified_at, license_status, isbn, scope)
                VALUES (?::uuid, ?::uuid, 'TEXTBOOK', 'fixture', '人教版', 'OFFICIAL_PUBLISHER',
                        'VERIFIED', 'fixture', now(), 'LICENSED', 'isbn-semantics-v2', 'TENANT')
                ON CONFLICT (tenant_id, source_id) DO NOTHING
                """, SRC, T);
        jdbc.update("""
                INSERT INTO rag_edition (edition_id, tenant_id, source_id, subject, grade, term,
                        publisher, revision_code, scope)
                -- ⚠️ revision_code 也必须本类独占：rag_edition_unique 是
                -- (tenant_id, subject, grade, term, publisher, revision_code)，
                -- 只把 edition_id/source_id 换成独占键仍会与其它 IT / 历史残留撞五轴唯一约束
                -- （2026-11-25 真库 IT 实测：4 例中 1 例 DuplicateKey）。
                VALUES (?::uuid, ?::uuid, ?::uuid, '数学', '八年级', '上册', '人教版', '2024-esem-v2', 'TENANT')
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

    /** 与 RetrievalRepositoryIT 同口径：RAG 对象在 rag schema，连接时经 options 设 search_path。 */
    private static String withRagSearchPath(String url) {
        if (url == null || url.isBlank() || url.contains("search_path=")) {
            return url;
        }
        String sep = url.contains("?") ? "&" : "?";
        return url + sep + "options=-c%20search_path%3Drag%2Cpublic";
    }

    @Test
    @DisplayName("带公式/摘要的片段落库后逐字读回（INSERT 列/参与 SELECT 映射不错位）")
    void semanticsRoundTrip() {
        String formula = "F = ma。牛顿第二定律，F 为合外力";
        String caption = "表格（2 行 2 列）；表头：知识点 难度";
        EditionCoordinate edition = new EditionCoordinate(
                EditionCoordinate.Subject.MATH, EditionCoordinate.GradeBand.GRADE_8,
                EditionCoordinate.Term.FIRST, EditionCoordinate.Publisher.PEP, "2024");
        Chunk chunk = new Chunk(CID, DOC, edition, ChunkRole.EXPLANATION,
                "F = ma 是牛顿第二定律。",
                new Chunk.Locator(5, "第3章", null, 0, 20),
                List.of(), null, "hash", null, null, Chunk.LEVEL_PARENT,
                formula, caption);

        JdbcCorpusRepository repo = new JdbcCorpusRepository(jdbc, vectorColumn);
        float[] vector = vector();

        try {
            tx.executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                        String.class, T);
                seedForeignKeys();
                repo.saveChunkAndEmbedding(T, chunk, ED, vector, "test-model");
            });

            String[] read = tx.execute(status -> {
                jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                        String.class, T);
                return jdbc.queryForObject("""
                        SELECT formula_natural_language, caption_or_table_summary
                        FROM rag_chunk WHERE chunk_id = ?::uuid AND tenant_id = ?::uuid
                        """, (rs, i) -> new String[]{
                        rs.getString(1), rs.getString(2)}, CID, T);
            });

            assertTrue(read != null, "片段必须落库可读回");
            assertEquals(formula, read[0], "公式语义必须逐字往返");
            assertEquals(caption, read[1], "表格摘要必须逐字往返");
        } finally {
            tx.executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                        String.class, T);
                jdbc.update("DELETE FROM rag_chunk_embedding WHERE chunk_id = ?::uuid AND tenant_id = ?::uuid",
                        CID, T);
                jdbc.update("DELETE FROM rag_chunk WHERE chunk_id = ?::uuid AND tenant_id = ?::uuid", CID, T);
            });
        }
    }
}
