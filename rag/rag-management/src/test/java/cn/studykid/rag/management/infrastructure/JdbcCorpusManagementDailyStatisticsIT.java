package cn.studykid.rag.management.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import cn.studykid.rag.management.core.RagTenantContext;
import cn.studykid.rag.management.core.SourceRegistration;
import cn.studykid.rag.management.ports.CorpusManagementPort;
import cn.studykid.rag.management.ports.CorpusManagementPort.DailyImportCount;
import cn.studykid.rag.management.ports.CorpusManagementPort.DailyImportStatistics;
import cn.studykid.rag.management.ports.CorpusManagementPort.DailySearchCount;
import cn.studykid.rag.management.ports.CorpusManagementPort.DailySearchStatistics;

/**
 * 每日统计集成测试（报表中心 · RAG 侧）。
 *
 * <p><b>⚠️ 守门</b>：仅在环境变量 {@code IT_RAG_PG_URL} 设置时运行。
 * 未设置则整类跳过——避免在无 DB 的 CI/本地环境报红。
 *
 * <p><b>⚠️ 覆盖重点</b>：本 IT 验证三件<b>只有真 DB 才能证明</b>的事：
 * <ol>
 *   <li><b>跨租户隔离</b>：租户 A 的检索次数不能算进租户 B 的报表；</li>
 *   <li><b>连续日期补零</b>：区间内没有数据的日子必须返回 0 而非缺行
 *       （缺行会让前端图表断线，看起来像「那天没数据」而不是「那天是 0」）；</li>
 *   <li><b>动作过滤</b>：只数 {@code RETRIEVE}，{@code INGEST} 等其它动作
 *       不能被算成「搜索次数」——那会让「搜索量」被采集动作污染。</li>
 * </ol>
 *
 * <p><b>⚠️ 为什么每个用例用随机租户</b>（而不是固定租户）：
 * <ul>
 *   <li><b>审计表是 append-only</b>（V1 触发器禁止 UPDATE/DELETE），
 *       故无法清理历史夹具——固定租户会让断言随运行次数漂移
 *       （第二次跑时旧数据还在，计数翻倍 → 假红）。</li>
 *   <li>随机租户保证每次运行都是干净基线，断言才有判别力。</li>
 *   <li>同时避开检索 IT（{@code RetrievalRepositoryIT}）的断言范围，两个 IT 互不污染。</li>
 * </ul>
 */
class JdbcCorpusManagementDailyStatisticsIT {

    private static final String PG_URL_ENV = "IT_RAG_PG_URL";
    private static final String PG_USER_ENV = "IT_RAG_PG_USERNAME";
    private static final String PG_PASSWORD_ENV = "IT_RAG_PG_PASSWORD";

    private static final String PRINCIPAL = "33333333-3333-3333-3333-333333333333";

    private JdbcTemplate jdbcTemplate;
    private TransactionTemplate transactionTemplate;
    private CorpusManagementPort repository;

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
        // ⚠️ 凭证必须走独立参数：pgjdbc 无法解析内嵌 user:pass@host 的 URL
        //    （密码含 '!' 时会把整段当主机名）。
        ds.setUsername(envOrDefault(PG_USER_ENV, null));
        ds.setPassword(envOrDefault(PG_PASSWORD_ENV, null));
        jdbcTemplate = new JdbcTemplate(ds);
        transactionTemplate = new TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));
        repository = new JdbcCorpusManagementRepository(jdbcTemplate, transactionTemplate);
    }

    /** 新建一个随机租户上下文（干净基线）。 */
    private static RagTenantContext newTenant() {
        return new RagTenantContext(UUID.randomUUID().toString(),
                cn.studykid.rag.core.source.TrustTier.OFFICIAL_TUTORIAL);
    }

    // -----------------------------------------------------------------
    // 每日检索次数
    // -----------------------------------------------------------------

    @Test
    @DisplayName("每日检索次数：只数 RETRIEVE，且按 UTC 日期归日")
    void dailySearchCountsOnlyRetrieveActions() {
        RagTenantContext ctx = newTenant();
        seedAudit(ctx, "RETRIEVE", 3);
        seedAudit(ctx, "RETRIEVE", 1);
        seedAudit(ctx, "INGEST", 5);   // 采集动作**不是**搜索
        seedAudit(ctx, "CITE", 7);     // 引用动作**不是**搜索

        DailySearchStatistics stats = repository.dailySearchStatistics(ctx, 7);

        long today = stats.searchesByDay().stream()
                .filter(d -> d.date().equals(LocalDate.now(ZoneOffset.UTC).toString()))
                .mapToLong(DailySearchCount::searches)
                .sum();
        assertEquals(4, today, "只应数 RETRIEVE（3+1）；INGEST / CITE 不是搜索");
        assertEquals(7, stats.generatedDays(), "必须返回连续 7 天");
    }

    @Test
    @DisplayName("每日检索次数：区间内的空白日补 0 而非缺行")
    void dailySearchFillsMissingDaysWithZero() {
        RagTenantContext ctx = newTenant();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        // 只在「今天」写审计；前 6 天应全部补 0
        seedAudit(ctx, "RETRIEVE", 2);

        DailySearchStatistics stats = repository.dailySearchStatistics(ctx, 7);

        assertEquals(7, stats.searchesByDay().size(), "连续 7 天，一天都不能少");
        for (DailySearchCount d : stats.searchesByDay()) {
            if (!d.date().equals(today.toString())) {
                assertEquals(0, d.searches(), d.date() + " 应补 0");
            }
        }
        // 日期轴必须升序且从区间起点开始
        assertEquals(today.minusDays(6).toString(), stats.searchesByDay().get(0).date());
        assertEquals(today.toString(), stats.searchesByDay().get(6).date());
    }

    @Test
    @DisplayName("⛔ 每日检索次数：跨租户隔离（他租户的审计不能算进来）")
    void dailySearchIsolatesTenants() {
        RagTenantContext ctxA = newTenant();
        RagTenantContext ctxB = newTenant();
        seedAudit(ctxA, "RETRIEVE", 4);
        seedAudit(ctxB, "RETRIEVE", 9);

        long sumA = repository.dailySearchStatistics(ctxA, 7).searchesByDay().stream()
                .mapToLong(DailySearchCount::searches).sum();
        assertEquals(4, sumA, "租户 A 只应看到自己的 4 次检索");

        long sumB = repository.dailySearchStatistics(ctxB, 7).searchesByDay().stream()
                .mapToLong(DailySearchCount::searches).sum();
        assertEquals(9, sumB, "租户 B 只应看到自己的 9 次检索");
    }

    // -----------------------------------------------------------------
    // 每日导入语料量
    // -----------------------------------------------------------------

    @Test
    @DisplayName("每日导入语料量：三个层级各查各的，不互相放大")
    void dailyImportCountsThreeLevelsIndependently() {
        RagTenantContext ctx = newTenant();
        // 1 个来源 → 3 个载体 → 1 个片段
        String sourceId = seedSource(ctx);
        String editionId = seedEdition(ctx, sourceId);
        String documentId = seedDocument(ctx, sourceId, editionId);
        seedDocument(ctx, sourceId, editionId);
        seedDocument(ctx, sourceId, editionId);
        seedChunk(ctx, documentId, editionId);

        DailyImportStatistics stats = repository.dailyImportStatistics(ctx, 7);

        String today = LocalDate.now(ZoneOffset.UTC).toString();
        DailyImportCount todayCount = stats.importsByDay().stream()
                .filter(d -> d.date().equals(today))
                .findFirst()
                .orElse(null);
        assertNotNull(todayCount, "今天必须在连续日期轴上");
        assertEquals(1, todayCount.sources(), "1 个资料来源");
        assertEquals(3, todayCount.documents(), "3 个文档载体");
        assertEquals(1, todayCount.chunks(), "1 个片段");
        assertEquals(7, stats.generatedDays());
    }

    @Test
    @DisplayName("⛔ 每日导入语料量：跨租户隔离")
    void dailyImportIsolatesTenants() {
        RagTenantContext ctxA = newTenant();
        RagTenantContext ctxB = newTenant();
        seedSource(ctxB);

        DailyImportStatistics stats = repository.dailyImportStatistics(ctxA, 7);
        long sumA = stats.importsByDay().stream().mapToLong(DailyImportCount::sources).sum();
        assertEquals(0, sumA, "租户 A 不应看到租户 B 的来源");
        assertEquals(7, stats.importsByDay().size(), "连续日期轴不能因为没数据而变短");
    }

    // -----------------------------------------------------------------
    // 夹具
    // -----------------------------------------------------------------

    /** 写一条审计（principal 可空 —— V5 已放宽为可空）。 */
    private void seedAudit(RagTenantContext ctx, String action, int count) {
        transactionTemplate.executeWithoutResult(st -> {
            jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                    String.class, ctx.tenantId());
            for (int i = 0; i < count; i++) {
                jdbcTemplate.update("""
                        INSERT INTO rag_access_audit
                            (audit_id, tenant_id, principal_id, action, source_id)
                        VALUES (gen_random_uuid(), rag_current_tenant(), NULL, ?, NULL)
                        """, action);
            }
        });
    }

    /** 种一个来源（走管理端口，状态恒为 PENDING）。 */
    private String seedSource(RagTenantContext ctx) {
        return repository.registerSource(ctx,
                new SourceRegistration(
                        "教材", "统计夹具教材-" + UUID.randomUUID(), "测试出版社",
                        "ISBN-" + UUID.randomUUID(), null, null, null),
                PRINCIPAL).sourceId();
    }

    /** 种一个版本（载体的前置依赖）。 */
    private String seedEdition(RagTenantContext ctx, String sourceId) {
        String editionId = UUID.randomUUID().toString();
        transactionTemplate.executeWithoutResult(st -> {
            jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                    String.class, ctx.tenantId());
            jdbcTemplate.update("""
                    INSERT INTO rag_edition (edition_id, tenant_id, source_id, subject, grade, term,
                                             publisher, revision_code)
                    VALUES (?::uuid, rag_current_tenant(), ?::uuid, '数学', '八年级', '上册',
                            '人教版', 'STAT-' || ?)
                    """, editionId, sourceId, editionId);
        });
        return editionId;
    }

    /** 种一个载体。 */
    private String seedDocument(RagTenantContext ctx, String sourceId, String editionId) {
        String documentId = UUID.randomUUID().toString();
        transactionTemplate.executeWithoutResult(st -> {
            jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                    String.class, ctx.tenantId());
            jdbcTemplate.update("""
                    INSERT INTO rag_document (document_id, tenant_id, source_id, edition_id, carrier,
                                              title, parse_status)
                    VALUES (?::uuid, rag_current_tenant(), ?::uuid, ?::uuid, 'PDF',
                            '统计夹具载体', 'CHUNKED')
                    """, documentId, sourceId, editionId);
        });
        return documentId;
    }

    /** 种一个片段。 */
    private void seedChunk(RagTenantContext ctx, String documentId, String editionId) {
        transactionTemplate.executeWithoutResult(st -> {
            jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                    String.class, ctx.tenantId());
            // ⚠️ PostgreSQL 没有 Java 的 `'x'.repeat(n)`，字符串重复要用 repeat('x', n)
            jdbcTemplate.update("""
                    INSERT INTO rag_chunk (chunk_id, tenant_id, document_id, edition_id, role, text,
                                           page_number, section_path, char_start, char_end, content_hash)
                    VALUES (gen_random_uuid(), rag_current_tenant(), ?::uuid, ?::uuid,
                            'EXPLANATION'::rag_chunk_role, '统计夹具片段', 1, '第一章', 0, 6,
                            repeat('e', 64))
                    """, documentId, editionId);
        });
    }

    // -----------------------------------------------------------------
    // 工具
    // -----------------------------------------------------------------

    private static String envOrDefault(String name, String fallback) {
        String v = System.getenv(name);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    private static String withRagSearchPath(String url) {
        if (url == null || url.isBlank() || url.contains("search_path=")) {
            return url;
        }
        String sep = url.contains("?") ? "&" : "?";
        return url + sep + "options=-c%20search_path%3Drag%2Cpublic";
    }
}
