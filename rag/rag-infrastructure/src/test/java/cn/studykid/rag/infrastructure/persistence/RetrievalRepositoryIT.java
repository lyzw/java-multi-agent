package cn.studykid.rag.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import cn.studykid.rag.core.scope.RagScope;
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

    /**
     * 管理员（超级用户）连接：仅用于播种/清理<b>全局桶</b>的语料，不参与业务断言。
     *
     * <p>⚠️ 这不是偷懒，而是被测性质本身：应用角色的 {@code rag_*_write} 策略
     * 刻意不放行哨兵租户（V8 §2），所以「用应用连接 seed 全局语料」<b>必然失败</b>。
     * 未配置管理员连接时全局用例逐个跳过（不是失败）。
     */
    private static final String PG_ADMIN_URL_ENV = "IT_RAG_PG_ADMIN_URL";
    private static final String PG_ADMIN_USER_ENV = "IT_RAG_PG_ADMIN_USERNAME";
    private static final String PG_ADMIN_PASSWORD_ENV = "IT_RAG_PG_ADMIN_PASSWORD";

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
    // G-3 权威门槛夹具（来源低于门槛：同行评审论文等级，其余轴全部合格）
    private static final String S6 = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa8";
    private static final String E6 = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa9";
    private static final String D6 = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String C7 = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb7";

    // 平台全局语料夹具（tenant_id = 全局哨兵，scope='PLATFORM'）
    private static final String GS = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeee01";
    private static final String GE = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeee02";
    private static final String GD = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeee03";
    private static final String GC = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeee04";
    // 越权写用例的两个临时 chunk_id：chunk_id 是**全表主键**（非租户内唯一），
    // 不能复用 C1..C7 —— 那会撞别人的行而不是撞策略。
    private static final String CG_OK = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeee05";
    private static final String CG_BAD = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeee06";

    private static final EditionCoordinate E_PEP = new EditionCoordinate(
            Subject.MATH, GradeBand.GRADE_8, Term.FIRST, Publisher.PEP, "2024修订");

    /** 载体级撤回夹具所用的版本坐标（北师大版，与人教版错开以避开 rag_edition_unique）。 */
    private static final EditionCoordinate E_BSED = new EditionCoordinate(
            Subject.MATH, GradeBand.GRADE_8, Term.FIRST, Publisher.BEISHIDA, "2024修订");

    private JdbcTemplate jdbcTemplate;
    private TransactionTemplate transactionTemplate;
    private RetrievalPort repository;
    /** 管理员连接（可为 null：未配置时全局用例跳过）。 */
    private JdbcTemplate adminJdbcTemplate;
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

        // 管理员连接（仅用于全局桶的 seed / 清理；业务断言一律走应用连接）。
        String adminUrl = System.getenv(PG_ADMIN_URL_ENV);
        if (adminUrl != null && !adminUrl.isBlank()) {
            DriverManagerDataSource adminDs = new DriverManagerDataSource();
            adminDs.setUrl(withRagSearchPath(adminUrl));
            adminDs.setUsername(envOrDefault(PG_ADMIN_USER_ENV, extractUserFromUrl(adminUrl)));
            adminDs.setPassword(envOrDefault(PG_ADMIN_PASSWORD_ENV, null));
            adminJdbcTemplate = new JdbcTemplate(adminDs);
        }
    }

    /** 需要管理员连接的用例的前置：没有就跳过，不算失败。 */
    private void requireAdminConnection() {
        Assumptions.assumeTrue(adminJdbcTemplate != null,
                "skipped: " + PG_ADMIN_URL_ENV + " 未设置（全局语料的 seed 无法经应用角色完成）");
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
    // G-3 权威门槛：方向钉死（PG 枚举比较按声明序，与权威语义相反——
    // 曾把 `>=` 写反导致全部正常语料被滤空，此测试与上一条合起来双向锁定）
    // ---------------------------------------------------------------------

    /**
     * ⚠️⚠️ 本用例与 {@link #retrievableCarrierIsRetrieved()} 构成 G-3 的<b>双向钉死</b>：
     * 同一谓词下，{@code OFFICIAL_PUBLISHER}（门槛之上）的 C1 必须能取回，
     * 而 {@code PEER_REVIEWED_PAPER}（门槛之下、其余轴全部合格）的 C7 必须取不到。
     *
     * <p><b>为什么必须双向</b>：{@code rag_trust_tier} 的 PG 声明序
     * （OFFICIAL_PUBLISHER → UNVERIFIED）与权威语义<b>相反</b>，
     * 若谓词方向写反（如 {@code trust_tier >= 门槛}），所有正常语料被滤空、
     * 反而只有 UNVERIFIED 能通过——只断言「取不到」的测试抓不住这种反转。
     * 2026-10-27 线上 RDS 实测踩坑后补此回归。
     */
    @Test
    @DisplayName("⛔ 低于权威门槛的来源（同行评审论文）即使全部校验合格也不可作为依据取回")
    void belowThresholdSourceIsNotRetrieved() {
        seedBelowThresholdSource();
        List<Chunk> loaded = loadByIdsAs(T, List.of(C1, C7));
        List<String> ids = loaded.stream().map(Chunk::id).toList();
        assertTrue(ids.contains(C1),
                "门槛之上的正常语料（OFFICIAL_PUBLISHER）必须能取回——双向钉死，防谓词方向反转");
        assertFalse(ids.contains(C7),
                "PEER_REVIEWED_PAPER 低于默认门槛 OFFICIAL_TUTORIAL，"
                        + "即使 ver_status/license/新鲜度全部合格也不得作为依据取回（G-3）");
    }

    // ---------------------------------------------------------------------
    // 第三刀：平台全局语料（哨兵租户 + scope='PLATFORM'）的双作用域读路径
    //
    // 与 scripts/rag-corpus-scope-counterproof.sh 的分工：
    //   · 脚本在 psql 直连层面证明「数据库允不允许越权语句」；
    //   · 本节证明「应用代码这条路走对了没有」——尤其是最容易被漏掉的那层：
    //     RLS 放开了全局行，但 Java SQL 里还写着 `= rag_current_tenant()`，
    //     表现是全局语料召回不到且**不报错**（P 组里唯一的「功能」断言）。
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("全局语料被每个租户召回，且不带走任何租户私有语料")
    void globalChainIsRecalledByEveryTenant() {
        requireAdminConnection();
        seedGlobalChain();
        try {
            seedTenantU();
            RetrievalRequest req = new RetrievalRequest(
                    "二次函数的顶点", RetrievalMode.EXPLAIN, EditionScope.strict(E_PEP),
                    null, null, null, 10);
            List<String> asT = searchAs(T, req).stream().map(s -> s.chunk().id()).toList();
            List<String> asU = searchAs(U, req).stream().map(s -> s.chunk().id()).toList();

            assertTrue(asT.contains(GC), "租户 T 应召回到平台全局片段 GC");
            assertTrue(asU.contains(GC), "租户 U 应召回到**同一条**全局片段 GC");
            assertTrue(asT.contains(C1), "放开全局可见性不得影响本租户私有语料的召回");
            assertFalse(asU.contains(C1), "T 的私有片段不得因「全局可见」而顺带漏给 U");
            assertFalse(asT.contains(C4), "U 的私有片段同理（这是本 IT 既有的隔离底线）");
        } finally {
            retireGlobalChain();
        }
    }

    /**
     * ⚠️⚠️ 本节最关键的一条：钉住<b>两层过滤必须同步</b>。
     *
     * <p>同一个会话、同一份数据，只改 SQL 谓词的写法，行数就差 1。
     * 若只写「全局能召回」的正向断言，把谓词改回 {@code = rag_current_tenant()}
     * 后 RLS 仍然放开，但这条断言会失败——它就是那层的守门人
     * （对应反证脚本 P1-4/P1-1；脚本给出的是同一个 1 vs 2 的差值）。
     */
    @Test
    @DisplayName("两层过滤：RLS 放开而 SQL 谓词没跟上 = 全局语料静默滤空")
    void sqlPredicateLayerMustKeepPaceWithRls() {
        requireAdminConnection();
        seedGlobalChain();
        try {
            int oldShape = countChunksAs(T, "c.tenant_id = rag_current_tenant()");
            int newShape = countChunksAs(T,
                    "c.tenant_id IN (rag_current_tenant(), rag_global_tenant_id())");
            assertEquals(oldShape + 1, newShape,
                    "全局那条 chunk 只能被新谓词看到：老谓词把它静默滤空（差值必须是 1，"
                            + "老=" + oldShape + " 新=" + newShape + "）");
        } finally {
            retireGlobalChain();
        }
    }

    @Test
    @DisplayName("生成期取依据与检索通道口径一致：全局片段两个租户都能取回")
    void loadByIdsReturnsGlobalChunkForEveryTenant() {
        requireAdminConnection();
        seedGlobalChain();
        try {
            assertTrue(loadByIdsAs(T, List.of(GC)).stream().map(Chunk::id).toList().contains(GC),
                    "引用全局语料生成答案时，依据必须取得回来（否则答案带一条取不到的引用）");
            assertTrue(loadByIdsAs(U, List.of(GC)).stream().map(Chunk::id).toList().contains(GC),
                    "取回的是同一条全局行，不是每个租户各一份副本");
        } finally {
            retireGlobalChain();
        }
    }

    /**
     * 租户写不进全局桶。
     *
     * <p>⚠️ 带<b>反自证对照</b>：同一条 INSERT 语句形状，租户桶写成功、
     * 全局桶被拒——否则「写失败」可能只是 SQL 本身有问题（假绿的反面：假红）。
     */
    @Test
    @DisplayName("⛔ 应用角色写不出全局语料：RLS 写策略只放行本租户桶")
    void tenantCannotInsertGlobalChunk() {
        requireAdminConnection();
        // 全局夹具先行：让被拒的那条 INSERT 在 FK/CHECK 上都**合法**，
        // 否则报错可能来自复合外键或 scope 不变式，测的就不是 RLS 这一层了。
        seedGlobalChain();
        try {
            int before = globalChunkCount();
            String g = RagScope.GLOBAL_TENANT_ID;

            // ① 反自证对照：完全相同的语句形状，写进自己的桶必须成功。
            //    （少了这条，「写失败」可能只是 SQL 本身写错了。）
            transactionTemplate.execute(status -> {
                jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                        String.class, T);
                return jdbcTemplate.update(CHUNK_INSERT,
                        CG_OK, T, D, E, "对照", "h-control", "TENANT");
            });
            cleanChunk(T, CG_OK);

            // ② 被测：把 tenant_id 换成全局哨兵 —— 会话身份是 T，
            //    写策略 WITH CHECK (tenant_id = rag_current_tenant() AND scope='TENANT') 必须拒绝。
            Throwable rejected = catchThrowable(() -> transactionTemplate.execute(status -> {
                jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                        String.class, T);
                return jdbcTemplate.update(CHUNK_INSERT,
                        CG_BAD, g, GD, GE, "冒充全局", "h-smuggle", "PLATFORM");
            }));
            assertNotNull(rejected,
                    "写全局桶没被拒 = 任一租户可发布全局语料，影响所有租户（直接越权）");
            String message = rootMessage(rejected);
            assertTrue(message.contains("row-level security"),
                    "必须是 RLS 拒绝，不能拿 CHECK/FK/语法错误冒充「挡住了」。实际：" + message);
            assertEquals(before, globalChunkCount(),
                    "被拒的写入不得在库里留下任何全局行");
        } finally {
            adminJdbcTemplate.update("DELETE FROM rag_chunk WHERE chunk_id = ?::uuid", CG_BAD);
            retireGlobalChain();
        }
    }

    /** 一条形状合法的 chunk INSERT；租户与 scope 由参数决定，供「对照/被测」共用同一形状。 */
    private static final String CHUNK_INSERT =
            "INSERT INTO rag_chunk (chunk_id, tenant_id, document_id, edition_id, role, text,"
                    + " page_number, section_path, char_start, char_end, kp_ids, content_hash,"
                    + " embed_model, index_generation, scope)"
                    + " VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'EXPLANATION'::rag_chunk_role,"
                    + " ?, 1, 'x', 0, 2, '{}'::uuid[], ?, 'm', 1, ?::text)";

    /** 按主键清掉本用例临时造的 chunk。 */
    private void cleanChunk(String tenantId, String chunkId) {
        // ⚠️ 必须带租户上下文：删除策略是 `tenant_id = rag_current_tenant()`，
        //    不建上下文时 rag_current_tenant() 为 NULL，DELETE 匹配 0 行且**不报错**——
        //    夹具会永久残留在共享 IT 库里。
        transactionTemplate.execute(status -> {
            jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                    String.class, tenantId);
            return jdbcTemplate.update("DELETE FROM rag_chunk WHERE chunk_id = ?::uuid", chunkId);
        });
    }

    /** 全局桶里的 chunk 条数（走管理员连接，绕过 RLS 看真实值）。 */
    private int globalChunkCount() {
        Integer n = adminJdbcTemplate.queryForObject(
                "SELECT count(*) FROM rag_chunk WHERE tenant_id = rag_global_tenant_id()",
                Integer.class);
        return n == null ? 0 : n;
    }

    /** 在指定租户上下文下，按给定谓词数 rag_chunk（复刻检索仓库的三表 JOIN 形状）。 */
    private int countChunksAs(String tenantId, String predicate) {
        Integer n = transactionTemplate.execute(status -> {
            jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                    String.class, tenantId);
            return jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM rag_chunk c"
                            + " JOIN rag_document doc ON c.document_id = doc.document_id"
                            + "                              AND c.tenant_id = doc.tenant_id"
                            + " JOIN rag_source src ON doc.source_id = src.source_id"
                            + "                             AND c.tenant_id = src.tenant_id"
                            + " WHERE " + predicate,
                    Integer.class);
        });
        return n == null ? 0 : n;
    }

    private static Throwable catchThrowable(Runnable action) {
        try {
            action.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    private static String rootMessage(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null && c != c.getCause(); c = c.getCause()) {
            sb.append(c.getMessage()).append(' ');
        }
        return sb.toString();
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
     * <p>⚠️ 为什么版本坐标必须与 {@link #seedTenantT()}、{@link #seedWithdrawnCarrier()}
     * 错开（华东师大版而非人教版/北师大版）：
     * {@code rag_edition_unique} 是 {@code (tenant_id, subject, grade, term, publisher,
     * revision_code)}——**不含 source_id**。同一租户内「人教版数学八年级上册2024修订」
     * 只能存在一个 edition，与它挂在哪个 source 下无关（版本坐标标识的是「那个版本」
     * 本身，不是每个来源各存一份副本）。若复用他人坐标，INSERT 会撞 {@code rag_edition_unique}，
     * 而 {@code ON CONFLICT (tenant_id, edition_id)} 只覆盖主键冲突，自然键冲突直接报错
     * （2026-10-23 线上 RDS 首跑实测；2026-10-27 二次修正：E4 原用北师大版，
     * 与载体撤回夹具 E5 撞同一自然键——两条夹具都必须各自独占坐标）。
     */
    private void seedRevokedSource() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                    String.class, T);
            insertSourceWithStatus(S4, T, "华东师大版", "ISBN-R", "REVOKED");
            insertEdition(E4, T, S4, "华东师大版");
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

    /**
     * 造一条「来源等级低于权威门槛、其余轴全部合格」的语料（G-3 双向钉死的反向侧）。
     *
     * <p>ver_status=VERIFIED / license=LICENSED / 新鲜（verified_at=now()），
     * 只有 {@code trust_tier='PEER_REVIEWED_PAPER'} 低于部署门槛
     * {@code OFFICIAL_TUTORIAL}——据此把 G-3 从其余轴中独立出来。
     *
     * <p>⚠️ 版本坐标用沪科版：与既有夹具（人教/苏教/华东师大/北师大）全部错开，
     * 避开 {@code rag_edition_unique}（见 {@link #seedRevokedSource()} 说明）。
     */
    private void seedBelowThresholdSource() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                    String.class, T);
            // ⚠️ rag_source_identifier_chk：论文等级必须提供 DOI（isbn 为 NULL），
            //    教材/教辅等级必须提供 ISBN——权威等级与出版标识必须自洽。
            jdbcTemplate.update(
                    "INSERT INTO rag_source (source_id, tenant_id, source_type, title, publisher, "
                            + "trust_tier, ver_status, ver_evidence, verified_at, license_status, doi) "
                            + "VALUES (?::uuid, ?::uuid, 'PAPER', 'fixture-paper', '某学术期刊', "
                            + "'PEER_REVIEWED_PAPER'::rag_trust_tier, 'VERIFIED'::rag_verification_status, "
                            + "'fixture', now(), 'LICENSED', ?) "
                            + "ON CONFLICT (tenant_id, source_id) DO UPDATE SET "
                            + "trust_tier = EXCLUDED.trust_tier, ver_status = EXCLUDED.ver_status, "
                            + "license_status = EXCLUDED.license_status, verified_at = EXCLUDED.verified_at",
                    S6, T, "10.1000/fixture-g3");
            insertEdition(E6, T, S6, "沪科版");
            insertDocument(D6, T, S6, E6);
            insertChunk(C7, T, D6, E6, "EXPLANATION",
                    "低权威来源的二次函数顶点讲解内容");
            return null;
        });
    }

    /**
     * 平台全局语料夹具：一条完整的 source → edition → document → chunk(+embedding) 链，
     * 落在<b>全局桶</b>（{@code tenant_id = 哨兵}、{@code scope='PLATFORM'}）。
     *
     * <p>⚠️ 走 {@code adminJdbcTemplate} 而非租户上下文：写策略
     * （{@code WITH CHECK (tenant_id = rag_current_tenant() AND scope='TENANT')}）
     * 按设计拒绝全局桶，用应用连接 seed 会得到 RLS 报错而不是全局行。
     *
     * <p>⚠️ 版本坐标刻意<b>复用「人教版」</b>（与租户 T 的 {@code E_PEP} 同坐标）：
     * {@code rag_edition_unique} 含 {@code tenant_id}，全局桶与租户桶是两个命名空间，
     * 同坐标不冲突——这正是「全局教辅版本」能与各租户私有版本共存的结构性前提。
     * 也正因坐标相同，租户 T 检索 {@code strict(E_PEP)} 时才会<b>同时</b>命中
     * 自己的 C1 与全局的 GC，从而能测到「两层过滤里 SQL 谓词那层有没有跟上」。
     */
    private void seedGlobalChain() {
        String g = RagScope.GLOBAL_TENANT_ID;
        insertSource(adminJdbcTemplate, GS, g, "人教版", "ISBN-GLOBAL", "PLATFORM");
        insertEdition(adminJdbcTemplate, GE, g, GS, "人教版", "PLATFORM");
        insertDocument(adminJdbcTemplate, GD, g, GS, GE, "PLATFORM");
        insertChunk(adminJdbcTemplate, GC, g, GD, GE, "EXPLANATION",
                "全局讲解：二次函数的顶点是抛物线的最高点或最低点", "PLATFORM");
    }

    /**
     * 清理全局夹具（按 FK 反序）。
     *
     * <p>⚠️ 按<b>夹具主键</b>删，不按 {@code tenant_id = 哨兵} 删：
     * 后者会连带删掉库里真实发布的全局语料（共享 IT 库上跑过 seed 脚本时就是数据事故）。
     */
    private void retireGlobalChain() {
        adminJdbcTemplate.update("DELETE FROM rag_chunk_embedding WHERE chunk_id = ?::uuid", GC);
        adminJdbcTemplate.update("DELETE FROM rag_chunk WHERE chunk_id = ?::uuid", GC);
        adminJdbcTemplate.update("DELETE FROM rag_document WHERE document_id = ?::uuid", GD);
        adminJdbcTemplate.update("DELETE FROM rag_edition WHERE edition_id = ?::uuid", GE);
        adminJdbcTemplate.update("DELETE FROM rag_source WHERE source_id = ?::uuid", GS);
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
                        + "trust_tier, ver_status, ver_evidence, verified_at, license_status, isbn, scope) "
                        + "VALUES (?::uuid, ?::uuid, 'TEXTBOOK', 'fixture', ?, 'OFFICIAL_PUBLISHER', "
                        + "?::rag_verification_status, "
                        + "'fixture', now(), 'LICENSED', ?, 'TENANT') "
                        + "ON CONFLICT (tenant_id, source_id) DO NOTHING",
                sourceId, tenantId, publisher, verStatus, isbn);
    }


    private void insertSource(String sourceId, String tenantId, String publisher, String isbn) {
        insertSource(jdbcTemplate, sourceId, tenantId, publisher, isbn, "TENANT");
    }

    /**
     * 同一份来源夹具，可按<b>作用域</b>落到不同桶。
     *
     * <p>⚠️ {@code scope} 必须显式给：V8 的 CHECK 要求「{@code scope='PLATFORM'} 当且仅当
     * {@code tenant_id} 是全局哨兵」，而列默认值是 {@code 'TENANT'}——
     * 只改租户不改 scope 会得到一条 CHECK 违规，看起来像「哨兵值不能用」的错误结论。
     */
    private void insertSource(JdbcTemplate jdbc, String sourceId, String tenantId,
                              String publisher, String isbn, String scope) {
        jdbc.update(
                "INSERT INTO rag_source (source_id, tenant_id, source_type, title, publisher, "
                        + "trust_tier, ver_status, ver_evidence, verified_at, license_status, isbn, scope) "
                        + "VALUES (?::uuid, ?::uuid, 'TEXTBOOK', 'fixture', ?, 'OFFICIAL_PUBLISHER', 'VERIFIED', "
                        + "'fixture', now(), 'LICENSED', ?, ?) "
                        + "ON CONFLICT (tenant_id, source_id) DO UPDATE SET "
                        + "ver_status = EXCLUDED.ver_status, license_status = EXCLUDED.license_status, "
                        + "verified_at = EXCLUDED.verified_at, scope = EXCLUDED.scope",
                sourceId, tenantId, publisher, isbn, scope);
    }

    private void insertEdition(String editionId, String tenantId, String sourceId, String publisher) {
        insertEdition(jdbcTemplate, editionId, tenantId, sourceId, publisher, "TENANT");
    }

    private void insertEdition(JdbcTemplate jdbc, String editionId, String tenantId,
                               String sourceId, String publisher, String scope) {
        jdbc.update(
                "INSERT INTO rag_edition (edition_id, tenant_id, source_id, subject, grade, term, "
                        + "publisher, revision_code, scope) "
                        + "VALUES (?::uuid, ?::uuid, ?::uuid, '数学', '八年级', '上册', ?, '2024修订', ?) "
                        + "ON CONFLICT (tenant_id, edition_id) DO UPDATE SET scope = EXCLUDED.scope",
                editionId, tenantId, sourceId, publisher, scope);
    }

    private void insertDocument(String documentId, String tenantId, String sourceId, String editionId) {
        insertDocument(jdbcTemplate, documentId, tenantId, sourceId, editionId, "TENANT");
    }

    private void insertDocument(JdbcTemplate jdbc, String documentId, String tenantId,
                                String sourceId, String editionId, String scope) {
        // ⚠️⚠️ **必须显式置位载体级准入标记**（修复前此处不写，依赖默认值）：
        //    rag_document.ver_status 默认 'PENDING'、retrievable 默认 FALSE，
        //    而检索侧现在**会**过滤这两列（载体级撤回此前在检索侧完全无效）。
        //    不置位则所有夹具文档都不可检索 —— 那正是修复要达成的效果，
        //    但会让本 IT 测不到「正常语料能召回」。
        jdbc.update(
                "INSERT INTO rag_document (document_id, tenant_id, source_id, edition_id, title, "
                        + "parse_status, ver_status, retrievable, scope) "
                        + "VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'fixture', 'CHUNKED', "
                        + "'VERIFIED'::rag_verification_status, TRUE, ?) "
                        // ⚠️ DO UPDATE 而非 DO NOTHING（评审 P0-7 期间实测）：
                        //    共享 IT 库可能残留旧版本夹具的行（当年不写准入标记，
                        //    ver_status=PENDING / retrievable=FALSE）——DO NOTHING 会被
                        //    旧行压住，检索谓词（现在真的读这两列）把它们全滤掉，
                        //    表现为「正常语料召回不到」。更新才是幂等语义。
                        + "ON CONFLICT (tenant_id, document_id) DO UPDATE SET "
                        + "parse_status = EXCLUDED.parse_status, "
                        + "ver_status = EXCLUDED.ver_status, "
                        + "retrievable = EXCLUDED.retrievable, "
                        + "scope = EXCLUDED.scope",
                documentId, tenantId, sourceId, editionId, scope);
    }

    private void insertChunk(String chunkId, String tenantId, String documentId, String editionId,
                             String role, String text) {
        insertChunk(jdbcTemplate, chunkId, tenantId, documentId, editionId, role, text, "TENANT");
    }

    private void insertChunk(JdbcTemplate jdbc, String chunkId, String tenantId, String documentId,
                             String editionId, String role, String text, String scope) {
        String vec = vectorToString(embedder.embed(List.of(text)).get(0));
        jdbc.update(
                "INSERT INTO rag_chunk (chunk_id, tenant_id, document_id, edition_id, role, text, "
                        + "page_number, section_path, char_start, char_end, kp_ids, content_hash, "
                        + "embed_model, index_generation, scope) VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, "
                        + "?::rag_chunk_role, ?, "
                        + "45, 'chapter', 0, ?, '{}'::uuid[], 'h', 'local-deterministic-v1', 1, ?) "
                        + "ON CONFLICT (tenant_id, chunk_id) DO UPDATE SET "
                        + "role = EXCLUDED.role, text = EXCLUDED.text, "
                        + "index_generation = EXCLUDED.index_generation, scope = EXCLUDED.scope",
                chunkId, tenantId, documentId, editionId, role, text, text.length(), scope);
        jdbc.update(
                "INSERT INTO rag_chunk_embedding (chunk_id, tenant_id, index_generation, embed_model, "
                        + "embedding, scope) VALUES (?::uuid, ?::uuid, 1, 'local-deterministic-v1', ?::vector, ?) "
                        + "ON CONFLICT (chunk_id) DO UPDATE SET embedding = EXCLUDED.embedding, "
                        + "scope = EXCLUDED.scope",
                chunkId, tenantId, vec, scope);
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
