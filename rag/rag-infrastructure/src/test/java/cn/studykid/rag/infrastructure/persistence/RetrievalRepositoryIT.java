package cn.studykid.rag.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

    /** 数据库账号/口令环境变量名（须与 URL 分开，见 {@link #setup()}）。 */
    private static final String PG_USER_ENV = "IT_RAG_PG_USERNAME";
    private static final String PG_PASSWORD_ENV = "IT_RAG_PG_PASSWORD";

    private static final String T = "11111111-1111-1111-1111-111111111111";
    private static final String U = "22222222-2222-2222-2222-222222222222";
    private static final String S = "33333333-3333-3333-3333-333333333333";
    private static final String S2 = "44444444-4444-4444-4444-444444444444";
    private static final String E = "55555555-5555-5555-5555-555555555555";
    private static final String E2 = "66666666-6666-6666-6666-666666666666";
    private static final String E3 = "77777777-7777-7777-7777-777777777777";
    private static final String D = "88888888-8888-8888-8888-888888888888";
    private static final String D3 = "99999999-9999-9999-9999-999999999999";
    private static final String S4 = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa4";
    private static final String E4 = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa5";
    private static final String D4 = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa6";
    private static final String C5 = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa7";
    private static final String C1 = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1";
    private static final String C2 = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2";
    private static final String C3 = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa3";
    private static final String C4 = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb4";
    // 载体级撤回夹具（来源 VERIFIED，但载体被单独下架）
    private static final String S5 = "cccccccc-cccc-cccc-cccc-ccccccccccc1";
    private static final String E5 = "cccccccc-cccc-cccc-cccc-ccccccccccc2";
    private static final String D5 = "cccccccc-cccc-cccc-cccc-ccccccccccc3";
    private static final String C6 = "cccccccc-cccc-cccc-cccc-ccccccccccc4";

    private static final EditionCoordinate E_PEP = new EditionCoordinate(
            Subject.MATH, GradeBand.GRADE_8, Term.FIRST, Publisher.PEP, "2024修订");

    /** 载体级撤回夹具所用的版本坐标（北师大版，与人教版错开以避开 rag_edition_unique）。 */
    private static final EditionCoordinate E_BSED = new EditionCoordinate(
            Subject.MATH, GradeBand.GRADE_8, Term.FIRST, Publisher.BEISHIDA, "2024修订");

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
        String url = withRagSearchPath(System.getenv(PG_URL_ENV));
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setUrl(url);
        // ⚠️ 凭证必须走**独立参数**（2026-10-22 实测修正）：
        //   URL 内嵌 "user:pass@host" 时 pgjdbc 会把整段当成主机名，
        //   报 `caused by: rag_app:rag_app%2123@host`——密码含 '!' 时必现。
        //   拆成 setUsername / setPassword 后连接正常。
        ds.setUsername(envOrDefault(PG_USER_ENV, extractUserFromUrl(System.getenv(PG_URL_ENV))));
        ds.setPassword(envOrDefault(PG_PASSWORD_ENV, null));
        jdbcTemplate = new JdbcTemplate(ds);
        transactionTemplate = new TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));
        repository = new JdbcRetrievalRepository(jdbcTemplate, embedder);
        seedTenantT();
    }

    /** 读取环境变量，未设置时回落到默认值。 */
    private static String envOrDefault(String name, String fallback) {
        String v = System.getenv(name);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    /** 从 JDBC URL 中提取用户名（形如 {@code jdbc:postgresql://user:pass@host/...}）。 */
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

    /**
     * 给 JDBC URL 补上 {@code search_path=rag, public}。
     *
     * <p>⚠️ 必须与应用侧一致：RAG 全部对象位于独立 schema {@code rag}（V1 迁移创建），
     * 而应用 SQL 用的是<b>未限定表名</b>。生产应用通过
     * {@code spring.datasource.hikari.connection-init-sql=SET search_path TO rag, public}
     * 设置（见 rag-bootstrap / rag-worker 的 application.yml）；
     * 本 IT 自建 {@link DriverManagerDataSource}，没有 init-sql 机制，
     * 因此改用 PostgreSQL 的 {@code options=-c search_path=...} 在连接建立时生效。
     */
    private static String withRagSearchPath(String url) {
        if (url == null || url.isBlank() || url.contains("search_path=")) {
            return url;
        }
        String sep = url.contains("?") ? "&" : "?";
        return url + sep + "options=-c%20search_path%3Drag%2Cpublic";
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

    /**
     * 回归：检索器返回的 {@code score} 必须是<b>相关性分</b>，不是 RRF 融合分。
     *
     * <p><b>⚠️ 2026-10-23 实测缺陷（线上 RDS 复现）</b>：JDBC 实现曾把 RRF 融合分
     * 写进 {@code score}，而分数门禁阈值（默认 0.2、设计初值 0.35）是按
     * <b>相关性量纲</b>（余弦相似度 / ts_rank）标定的。RRF 上限约 0.033
     * （双通道命中时 2/61）→ 门禁恒不通过 → <b>检索永远返回「未找到依据」</b>，
     * 语料再全也召不回任何东西。端口契约明写「门禁必须作用于检索器给出的
     * 真实相关性分数」，此测试把该契约钉死。
     *
     * <p>断言两件事：强相关命中的相关性分应<b>达到</b>默认门禁；
     * 且融合分与相关性分<b>量纲必须不同</b>（否则又混回去了）。
     */
    @Test
    void returnedScoreIsRelevanceNotRrfFusionValue() {
        RetrievalRequest req = new RetrievalRequest(
                "二次函数的顶点", RetrievalMode.EXPLAIN, EditionScope.strict(E_PEP),
                null, null, null, 10);
        List<ScoredChunk> result = searchAs(T, req);
        ScoredChunk strong = result.stream()
                .filter(s -> s.chunk().id().equals(C1))
                .findFirst()
                .orElseThrow(() -> new AssertionError("强相关片段 C1 应被召回"));
        assertTrue(strong.score() >= 0.2,
                "强相关命中的相关性分应达到默认门禁 0.2，实际=" + strong.score());
        assertTrue(strong.fusionScore() < 0.05,
                "融合排序分（RRF）量纲远小于相关性分，实际=" + strong.fusionScore());
        assertTrue(strong.score() > strong.fusionScore(),
                "两个分数量纲必须不同：相关性分=" + strong.score()
                        + "，融合排序分=" + strong.fusionScore());
    }

    // ---------------------------------------------------------------------
    // B5：生成期依据取回（loadByIds）的隔离与撤回语义
    // ---------------------------------------------------------------------

    @Test
    void loadByIdsReturnsOwnTenantChunks() {
        List<Chunk> loaded = loadByIdsAs(T, List.of(C1, C2));
        List<String> ids = loaded.stream().map(Chunk::id).toList();
        assertTrue(ids.contains(C1), "本租户的片段应能取回");
        assertTrue(ids.contains(C2), "本租户的片段应能取回（答案类角色由上层闸门滤，不在此处）");
    }

    @Test
    void loadByIdsCrossTenantReturnsNothing() {
        seedTenantU();
        List<Chunk> loaded = loadByIdsAs(T, List.of(C4));
        assertTrue(loaded.isEmpty(),
                "必须拒绝跨租户取回依据：C4 属租户 U，租户 T 取它必须得到空（RLS FORCE）");
    }

    @Test
    void loadByIdsExcludesRevokedSource() {
        seedRevokedSource();
        List<Chunk> loaded = loadByIdsAs(T, List.of(C5));
        assertTrue(loaded.isEmpty(),
                "来源已撤回（REVOKED）的片段不得作为生成依据——比不引用更糟的是引用已下架内容");
    }

    @Test
    void loadByIdsEmptyInputReturnsEmpty() {
        assertTrue(loadByIdsAs(T, List.of()).isEmpty(), "空入参应直接返回空，不发 SQL");
    }

    // ---------------------------------------------------------------------
    // 载体级撤回：检索侧必须真的过滤（修复前的核心缺口）
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("⛔ 载体已被单独撤回时，其片段不得被召回（修复前撤回动作在检索侧完全无效）")
    void withdrawnCarrierIsNotRetrieved() {
        seedWithdrawnCarrier();

        // ① 检索通道：不得召回
        RetrievalRequest req = new RetrievalRequest(
                "二次函数的顶点", RetrievalMode.EXPLAIN, EditionScope.strict(E_BSED),
                null, null, null, 10);
        List<String> searched = searchAs(T, req).stream().map(s -> s.chunk().id()).toList();
        assertFalse(searched.contains(C6),
                "⚠️⚠️ 载体已单独撤回（retrievable=false），其片段却仍被召回 —— "
                        + "「撤回某个载体」在检索侧形同虚设");

        // ② 生成期取依据通道：同样不得取回
        List<String> loaded = loadByIdsAs(T, List.of(C6)).stream().map(Chunk::id).toList();
        assertTrue(loaded.isEmpty(),
                "⚠️⚠️ 载体已撤回的内容不得作为生成依据 —— "
                        + "比不引用更糟的是引用已下架内容");
    }

    @Test
    @DisplayName("✅ 载体已校验且可检索时，其片段必须能被召回（防止修过头）")
    void retrievableCarrierIsRetrieved() {
        // 夹具文档由 insertDocument 显式置位 ver_status='VERIFIED' / retrievable=TRUE
        RetrievalRequest req = new RetrievalRequest(
                "二次函数的顶点", RetrievalMode.EXPLAIN, EditionScope.strict(E_PEP),
                null, null, null, 10);
        List<String> searched = searchAs(T, req).stream().map(s -> s.chunk().id()).toList();
        assertTrue(searched.contains(C1),
                "✅ 正常（载体已校验且可检索）的语料必须仍能召回");
    }

    // ---------------------------------------------------------------------
    // 夹具：在同一事务内 set_config 模拟主平台传入的租户上下文
    // ---------------------------------------------------------------------

    private List<Chunk> loadByIdsAs(String tenantId, List<String> chunkIds) {
        return transactionTemplate.execute(status -> {
            // ⚠️ set_config 是 SELECT（返回一行），必须用 queryForObject() 而非 update()。
            jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                    String.class, tenantId);
            return repository.loadByIds(chunkIds);
        });
    }

    private List<ScoredChunk> searchAs(String tenantId, RetrievalRequest req) {
        return transactionTemplate.execute(status -> {
            // ⚠️ set_config 是 SELECT（返回一行），必须用 queryForObject() 而非 update()。
            jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                    String.class, tenantId);
            return repository.search(req);
        });
    }

    private void seedTenantT() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                    String.class, T);
            // ⚠️ 不再插入租户行：RAG 库里**没有**租户表
            //    （V4 迁移移除了 rag_tenant 副本——租户信息的权威源是 platform，
            //    见 RAG-D00 §4「租户与身份」边界）。
            //    隔离仍由 RLS 保证：租户上下文只影响可见行。
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
            jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                    String.class, U);
            insertSource(S2, U, "人教版", "ISBN-U");
            insertEdition(E3, U, S2, "人教版");
            insertDocument(D3, U, S2, E3);
            insertChunk(C4, U, D3, E3, "EXPLANATION",
                    "二次函数的顶点是抛物线的最高点或最低点");
            return null;
        });
    }

    /**
     * 造一条「来源已撤回」的语料。
     *
     * <p>⚠️ 为什么单列一个夹具而不是复用 {@link #seedTenantT()}：
     * 撤回语义必须与「正常 VERIFIED 语料」在同一个测试里对比才有意义。
     * 若把 C1 的来源改成 REVOKED，就再也验证不了「VERIFIED 的能取到」。
     *
     * <p>⚠️ 为什么版本坐标必须与 {@link #seedTenantT()} 错开（北师大版而非人教版）：
     * {@code rag_edition_unique} 是 {@code (tenant_id, subject, grade, term, publisher,
     * revision_code)}——**不含 source_id**。同一租户内「人教版数学八年级上册2024修订」
     * 只能存在一个 edition，与它挂在哪个 source 下无关（版本坐标标识的是「那个版本」
     * 本身，不是每个来源各存一份副本）。若复用人教版，INSERT 会撞 {@code rag_edition_unique}，
     * 而 {@code ON CONFLICT (tenant_id, edition_id)} 只覆盖主键冲突，自然键冲突直接报错
     * （2026-10-23 线上 RDS 首跑实测）。
     */
    private void seedRevokedSource() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                    String.class, T);
            insertSourceWithStatus(S4, T, "北师大版", "ISBN-R", "REVOKED");
            insertEdition(E4, T, S4, "北师大版");
            insertDocument(D4, T, S4, E4);
            insertChunk(C5, T, D4, E4, "EXPLANATION", "已撤回的讲解内容");
            return null;
        });
    }

    /**
     * 造一条「载体被单独撤回、但来源仍然 VERIFIED」的语料。
     *
     * <p><b>⚠️ 这是「来源与载体是两个独立轴」（RAG-D07 §2.3.1 G-0）的核心场景</b>：
     * 同一版本下「官方电子版」可留、「第三方扫描版」可单独撤回。
     * 管理端「撤回某个载体」写的是 {@code rag_document.retrievable=FALSE}
     * 与 {@code ver_status='REVOKED'} ——
     * <b>修复前检索侧根本不读这两列</b>，撤回动作在检索侧完全无效。
     *
     * <p>⚠️ 版本坐标必须与 {@link #seedTenantT()} 错开（版次坐标含 publisher），
     * 否则撞 {@code rag_edition_unique}（见 {@link #seedRevokedSource()} 的说明）。
     */
    private void seedWithdrawnCarrier() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                    String.class, T);
            insertSource(S5, T, "北师大版", "ISBN-W");
            insertEdition(E5, T, S5, "北师大版");
            // ⚠️ 载体级撤回：来源是 VERIFIED，但这个载体被单独下架了
            jdbcTemplate.update(
                    "INSERT INTO rag_document (document_id, tenant_id, source_id, edition_id, title, "
                            + "parse_status, ver_status, retrievable, revoked_at, revoked_reason) "
                            + "VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, '已撤回载体', 'CHUNKED', "
                            + "'REVOKED'::rag_verification_status, FALSE, now(), '载体单独撤回') "
                            + "ON CONFLICT (tenant_id, document_id) DO NOTHING",
                    D5, T, S5, E5);
            insertChunk(C6, T, D5, E5, "EXPLANATION",
                    "已撤回载体里的二次函数顶点讲解内容");
            return null;
        });
    }

    private void insertSourceWithStatus(String sourceId, String tenantId, String publisher,
                                        String isbn, String verStatus) {
        // ⚠️ ver_status 必须显式转型 `?::rag_verification_status`（2026-10-23 线上 RDS 实测）：
        //    V1 起该列就是枚举类型。字面量 'VERIFIED'（insertSource）PG 会按 unknown
        //    字面量隐式转枚举；但**绑定参数**经 pgjdbc 按 varchar 发送，
        //    varchar → enum 没有隐式转换，直接报
        //    `column "ver_status" is of type rag_verification_status but expression is of type character varying`。
        //    （同文件 insertChunk 对 role 用 `?::rag_chunk_role` 正是同理。）
        jdbcTemplate.update(
                "INSERT INTO rag_source (source_id, tenant_id, source_type, title, publisher, "
                        + "trust_tier, ver_status, ver_evidence, verified_at, license_status, isbn) "
                        + "VALUES (?::uuid, ?::uuid, 'TEXTBOOK', 'fixture', ?, 'OFFICIAL_PUBLISHER', "
                        + "?::rag_verification_status, "
                        + "'fixture', now(), 'LICENSED', ?) "
                        + "ON CONFLICT (tenant_id, source_id) DO NOTHING",
                sourceId, tenantId, publisher, verStatus, isbn);
    }


    private void insertSource(String sourceId, String tenantId, String publisher, String isbn) {
        jdbcTemplate.update(
                "INSERT INTO rag_source (source_id, tenant_id, source_type, title, publisher, "
                        + "trust_tier, ver_status, ver_evidence, verified_at, license_status, isbn) "
                        + "VALUES (?::uuid, ?::uuid, 'TEXTBOOK', 'fixture', ?, 'OFFICIAL_PUBLISHER', 'VERIFIED', "
                        + "'fixture', now(), 'LICENSED', ?) "
                        + "ON CONFLICT (tenant_id, source_id) DO NOTHING",
                sourceId, tenantId, publisher, isbn);
    }

    private void insertEdition(String editionId, String tenantId, String sourceId, String publisher) {
        jdbcTemplate.update(
                "INSERT INTO rag_edition (edition_id, tenant_id, source_id, subject, grade, term, "
                        + "publisher, revision_code) VALUES (?::uuid, ?::uuid, ?::uuid, '数学', '八年级', '上册', ?, '2024修订') "
                        + "ON CONFLICT (tenant_id, edition_id) DO NOTHING",
                editionId, tenantId, sourceId, publisher);
    }

    private void insertDocument(String documentId, String tenantId, String sourceId, String editionId) {
        // ⚠️⚠️ **必须显式置位载体级准入标记**（修复前此处不写，依赖默认值）：
        //    rag_document.ver_status 默认 'PENDING'、retrievable 默认 FALSE，
        //    而检索侧现在**会**过滤这两列（载体级撤回此前在检索侧完全无效）。
        //    不置位则所有夹具文档都不可检索 —— 那正是修复要达成的效果，
        //    但会让本 IT 测不到「正常语料能召回」这件事。
        jdbcTemplate.update(
                "INSERT INTO rag_document (document_id, tenant_id, source_id, edition_id, title, "
                        + "parse_status, ver_status, retrievable) "
                        + "VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'fixture', 'CHUNKED', "
                        + "'VERIFIED'::rag_verification_status, TRUE) "
                        + "ON CONFLICT (tenant_id, document_id) DO NOTHING",
                documentId, tenantId, sourceId, editionId);
    }

    private void insertChunk(String chunkId, String tenantId, String documentId, String editionId,
                             String role, String text) {
        String vec = vectorToString(embedder.embed(List.of(text)).get(0));
        jdbcTemplate.update(
                "INSERT INTO rag_chunk (chunk_id, tenant_id, document_id, edition_id, role, text, "
                        + "page_number, section_path, char_start, char_end, kp_ids, content_hash, "
                        + "embed_model, index_generation) VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, "
                        + "?::rag_chunk_role, ?, "
                        + "45, 'chapter', 0, ?, '{}'::uuid[], 'h', 'local-deterministic-v1', 1) "
                        + "ON CONFLICT (tenant_id, chunk_id) DO NOTHING",
                chunkId, tenantId, documentId, editionId, role, text, text.length());
        jdbcTemplate.update(
                "INSERT INTO rag_chunk_embedding (chunk_id, tenant_id, index_generation, embed_model, "
                        + "embedding) VALUES (?::uuid, ?::uuid, 1, 'local-deterministic-v1', ?::vector) "
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
