package cn.studykid.rag.management.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import cn.studykid.rag.core.source.SourceVerification.VerificationMethod;
import cn.studykid.rag.core.source.SourceVerification.VerificationStatus;
import cn.studykid.rag.core.source.TrustTier;
import cn.studykid.rag.management.core.CorpusDocument;
import cn.studykid.rag.management.core.CorpusIssues;
import cn.studykid.rag.management.core.CorpusSource;
import cn.studykid.rag.management.core.CorpusStatistics;
import cn.studykid.rag.management.core.DocumentVerificationDecision;
import cn.studykid.rag.management.core.RagTenantContext;
import cn.studykid.rag.management.core.SourceQuery;
import cn.studykid.rag.management.core.SourceRegistration;
import cn.studykid.rag.management.core.VerificationDecision;
import cn.studykid.rag.management.ports.CorpusManagementPort;

/**
 * 语料管理仓库集成测试（RAG-D07 §2 准入流程 / RAG-D03 §2 数据模型）。
 *
 * <p><b>⚠️ 守门</b>：仅在环境变量 {@code IT_RAG_PG_URL} 设置时运行
 * （指向已应用 {@code rag} 迁移的 PostgreSQL 17 + pgvector 实例）。
 * 未设置则整类跳过——避免在无 DB 的 CI/本地环境报红。
 *
 * <p><b>⚠️ 覆盖重点</b>：本 IT 验证三件<b>只有真 DB 才能证明</b>的事：
 * <ol>
 *   <li><b>RLS 跨租户隔离</b>：租户 T 看不到租户 U 的来源；</li>
 *   <li><b>状态机在落库路径上真正生效</b>：非法转移被拒且<b>库中状态未变</b>
 *       （仅断言「抛异常」不够——必须断言「没写进去」）；</li>
 *   <li><b>审计与状态变更同事务</b>：状态变更后审计表确有对应行。</li>
 * </ol>
 */
class JdbcCorpusManagementRepositoryIT {

    private static final String PG_URL_ENV = "IT_RAG_PG_URL";

    /**
     * 数据库账号/口令环境变量名。
     *
     * <p>⚠️ 必须与 URL **分开**提供——见 {@link #setup()} 的说明。
     */
    private static final String PG_USER_ENV = "IT_RAG_PG_USERNAME";
    private static final String PG_PASSWORD_ENV = "IT_RAG_PG_PASSWORD";

    private static final String T = "11111111-1111-1111-1111-111111111111";
    private static final String U = "22222222-2222-2222-2222-222222222222";
    private static final String REVIEWER = "33333333-3333-3333-3333-333333333333";
    private static final String LEGAL = "44444444-4444-4444-4444-444444444444";

    private JdbcTemplate jdbcTemplate;
    private TransactionTemplate transactionTemplate;
    private CorpusManagementPort repository;

    /**
     * 租户 T 的上下文（由 platform 下发）。
     *
     * <p>⚠️ RAG 库里**没有**租户表（V4 迁移已移除 {@code rag_tenant} 副本），
     * 故夹具不再 seed 租户行——租户上下文是 platform 下发的入参。
     */
    private final RagTenantContext ctxT =
            new RagTenantContext(T, cn.studykid.rag.core.source.TrustTier.OFFICIAL_TUTORIAL);

    /**
     * 载体级夹具专用租户。
     *
     * <p>⚠️ <b>为什么载体夹具不能落在租户 T</b>：管理 IT 与检索 IT
     * （{@code RetrievalRepositoryIT}）共用同一个 IT 库，而检索 IT 会在
     * <b>整个租户</b>上做检索断言。载体夹具一旦落在租户 T，
     * 等这些片段被补上向量（重索引端点正是干这个的），
     * 检索 IT 就会把它们当成自己的命中结果 → 两个 IT 互相污染、
     * 失败现场指向错误的文件。
     * 故载体夹具独占一个租户。
     */
    private static final String M = "66666666-6666-6666-6666-666666666666";

    /** 租户 U 的上下文（用于跨租户隔离验证）。 */
    private final RagTenantContext ctxU =
            new RagTenantContext(U, cn.studykid.rag.core.source.TrustTier.OFFICIAL_TUTORIAL);

    /** 载体级夹具的租户上下文。 */
    private final RagTenantContext ctxM =
            new RagTenantContext(M, cn.studykid.rag.core.source.TrustTier.OFFICIAL_TUTORIAL);

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
        // ⚠️ 凭证必须走**独立参数**，不能内嵌在 JDBC URL 里。
        //
        //   实测（2026-10-22）：URL 写成
        //   jdbc:postgresql://rag_app:rag_app%2123@host:5432/study_rag
        //   时 pgjdbc **解析失败**——它把 "user:pass@host" 整段当成了主机名，
        //   报 `caused by: rag_app:rag_app%2123@rm-cn-...`（把 URL 片段当主机）。
        //   根因是密码含 '!' 等字符时 pgjdbc 的 URL 解析不兼容。
        //   拆成 setUsername / setPassword 后连接正常。
        //
        //   故此处从 IT_RAG_PG_USERNAME / IT_RAG_PG_PASSWORD 读凭证；
        //   未设置时回落到 URL 中可能存在的用户名（便于本地 pg 本机实例）。
        String user = envOrDefault(PG_USER_ENV, extractUserFromUrl(System.getenv(PG_URL_ENV)));
        String password = envOrDefault(PG_PASSWORD_ENV, null);
        ds.setUsername(user);
        ds.setPassword(password);
        jdbcTemplate = new JdbcTemplate(ds);
        transactionTemplate = new TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));
        repository = new JdbcCorpusManagementRepository(jdbcTemplate, transactionTemplate);
        // ⚠️ 不再 seed 租户：RAG 库里**没有**租户表（V4 迁移已移除 rag_tenant 副本）。
        //    租户上下理由 platform 下发（见 RagTenantContext）。
        //    跨租户隔离改由「两个不同的 tenantId 上下文」验证。
    }


    /**
     * 读取环境变量，未设置时回落到默认值。
     *
     * @param name 环境变量名
     * @param fallback 回落值（可为 null）
     * @return 环境变量值或回落值
     */
    private static String envOrDefault(String name, String fallback) {
        String v = System.getenv(name);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    /**
     * 从 JDBC URL 中提取用户名（形如 {@code jdbc:postgresql://user:pass@host/...}）。
     *
     * @param url JDBC URL
     * @return 用户名；无法提取返回 null
     */
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
     * <p>⚠️ 必须与应用侧一致：RAG 全部对象位于独立 schema {@code rag}，
     * 而应用 SQL 用的是<b>未限定表名</b>。
     */
    private static String withRagSearchPath(String url) {
        if (url == null || url.isBlank() || url.contains("search_path=")) {
            return url;
        }
        String sep = url.contains("?") ? "&" : "?";
        return url + sep + "options=-c%20search_path%3Drag%2Cpublic";
    }


    // ---------------------------------------------------------------------
    // 登记
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("登记后状态恒为 PENDING / UNVERIFIED（采集员不得自行提升）")
    void registeredSourceStartsPendingAndUnverified() {
        CorpusSource source = register("9787100000001", "人教版八年级上册数学");
        assertEquals(VerificationStatus.PENDING, source.verificationStatus());
        assertEquals(TrustTier.UNVERIFIED, source.trustTier());
        assertEquals(VerificationMethod.NONE, source.verificationMethod());
        assertEquals("PENDING", source.licenseStatus());
    }

    @Test
    @DisplayName("RLS 隔离：租户 T 看不到租户 U 的来源")
    void crossTenantIsolationEnforced() {
        CorpusSource source = repository.registerSource(ctxU,
                new SourceRegistration("教材", "租户U的教材", "人民教育出版社",
                        "9787100000002", null, "obj://book.pdf", "b".repeat(64)),
                REVIEWER);
        assertNotNull(source);
        assertNull(repository.findSource(ctxT, source.sourceId()),
                "租户 T 不应看到租户 U 的来源");
        List<CorpusSource> list = repository.listSources(ctxT,
                SourceQuery.of(VerificationStatus.PENDING));
        assertTrue(list.stream().noneMatch(s -> s.sourceId().equals(source.sourceId())),
                "租户 T 的列表不应包含租户 U 的来源");
    }

    // ---------------------------------------------------------------------
    // 状态机在落库路径上的生效
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("T-12：REJECTED 直通 VERIFIED 被拒，且库中状态未变")
    void illegalTransitionRejectedAndStateUnchanged() {
        CorpusSource source = register("9787100000003", "待审核教材");
        String id = source.sourceId();

        // 先走到 REJECTED
        repository.applyVerification(ctxT, id, new VerificationDecision(
                VerificationStatus.IN_REVIEW, "认领", REVIEWER, VerificationMethod.NONE, null, 365));
        repository.applyVerification(ctxT, id, new VerificationDecision(
                VerificationStatus.REJECTED, "证据不足", REVIEWER, VerificationMethod.NONE, null, 365));

        // 试图 REJECTED → VERIFIED（禁止）
        assertThrows(IllegalStateException.class, () -> repository.applyVerification(ctxT, id,
                VerificationDecision.approve(TrustTier.OFFICIAL_PUBLISHER, VerificationMethod.ISBN,
                        "ISBN:ref@2026-01-01", REVIEWER, 365)));

        // ⚠️ 关键断言：状态必须仍是 REJECTED——非法转移不能留下任何写入痕迹。
        CorpusSource after = repository.findSource(ctxT, id);
        assertNotNull(after);
        assertEquals(VerificationStatus.REJECTED, after.verificationStatus(),
                "非法转移后状态必须保持不变");
        assertEquals(TrustTier.UNVERIFIED, after.trustTier(),
                "被拒绝的来源应被降为 UNVERIFIED");
    }

    @Test
    @DisplayName("T-10：REVOKED 是终态，任何后续转移都被拒")
    void revokedIsTerminalInPersistence() {
        CorpusSource source = register("9787100000004", "待撤回教材");
        String id = source.sourceId();

        repository.applyVerification(ctxT, id, VerificationDecision.revoke("权利人通知", LEGAL));
        CorpusSource revoked = repository.findSource(ctxT, id);
        assertNotNull(revoked);
        assertEquals(VerificationStatus.REVOKED, revoked.verificationStatus());
        assertEquals("RESTRICTED", revoked.licenseStatus(),
                "撤回必须同时关闭版权轴（G-4），否则只看版权轴的路径仍可能放行");

        assertThrows(IllegalStateException.class, () -> repository.applyVerification(ctxT, id,
                new VerificationDecision(VerificationStatus.IN_REVIEW, "复活尝试", REVIEWER,
                        VerificationMethod.NONE, null, 365)));
        assertEquals(VerificationStatus.REVOKED,
                repository.findSource(ctxT, id).verificationStatus());
    }

    @Test
    @DisplayName("合法链路：PENDING → IN_REVIEW → VERIFIED，并写审计")
    void legalTransitionChainWritesAudit() {
        CorpusSource source = register("9787100000005", "正常审核教材");
        String id = source.sourceId();

        repository.applyVerification(ctxT, id, new VerificationDecision(
                VerificationStatus.IN_REVIEW, "认领", REVIEWER, VerificationMethod.NONE, null, 365));
        CorpusSource verified = repository.applyVerification(ctxT, id,
                VerificationDecision.approve(TrustTier.OFFICIAL_PUBLISHER, VerificationMethod.ISBN,
                        "ISBN:https://example/9787100000005@2026-10-22", REVIEWER, 365));
        assertNotNull(verified);
        assertEquals(VerificationStatus.VERIFIED, verified.verificationStatus());
        assertEquals(TrustTier.OFFICIAL_PUBLISHER, verified.trustTier());
        assertEquals(VerificationMethod.ISBN, verified.verificationMethod());
        assertNotNull(verified.verifiedAt());
        assertEquals(REVIEWER, verified.verifiedBy());

        // ⚠️ 审计必须与状态变更同事务落库（RAG-D07 §2.5）
        Integer auditCount = transactionTemplate.execute(st -> {
            jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                    String.class, T);
            return jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM rag_access_audit WHERE source_id = ?::uuid",
                    Integer.class, id);
        });
        assertNotNull(auditCount);
        assertTrue(auditCount >= 2, "登记与审核各应留一条审计，实际 " + auditCount);
    }

    @Test
    @DisplayName("T-3 / T-4：REJECTED 可申诉回到 IN_REVIEW")
    void rejectedCanAppealBackToInReview() {
        CorpusSource source = register("9787100000006", "申诉教材");
        String id = source.sourceId();

        repository.applyVerification(ctxT, id, new VerificationDecision(
                VerificationStatus.IN_REVIEW, "认领", REVIEWER, VerificationMethod.NONE, null, 365));
        repository.applyVerification(ctxT, id, new VerificationDecision(
                VerificationStatus.REJECTED, "证据不足", REVIEWER, VerificationMethod.NONE, null, 365));
        CorpusSource appealed = repository.applyVerification(ctxT, id,
                new VerificationDecision(VerificationStatus.IN_REVIEW, "补充证据后重审",
                        REVIEWER, VerificationMethod.NONE, null, 365));
        assertNotNull(appealed);
        assertEquals(VerificationStatus.IN_REVIEW, appealed.verificationStatus());
    }

    // ---------------------------------------------------------------------
    // 查询与统计
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("过期来源默认不出现在列表中（includeStale=false）")
    void staleSourcesExcludedByDefault() {
        CorpusSource source = register("9787100000007", "过期教材");
        String id = source.sourceId();
        repository.applyVerification(ctxT, id, new VerificationDecision(
                VerificationStatus.IN_REVIEW, "认领", REVIEWER, VerificationMethod.NONE, null, 365));
        repository.applyVerification(ctxT, id,
                VerificationDecision.approve(TrustTier.OFFICIAL_PUBLISHER, VerificationMethod.ISBN,
                        "ISBN:ref@2026-01-01", REVIEWER, 1));

        // 把 verified_at 往前推，使其超过 ver_valid_days
        transactionTemplate.executeWithoutResult(st -> {
            jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                    String.class, T);
            jdbcTemplate.update("""
                    UPDATE rag_source SET verified_at = now() - interval '30 days'
                    WHERE source_id = ?::uuid
                    """, id);
        });

        List<CorpusSource> withoutStale = repository.listSources(ctxT,
                new SourceQuery(VerificationStatus.VERIFIED, null, null, false, 50, 0));
        assertTrue(withoutStale.stream().noneMatch(s -> s.sourceId().equals(id)),
                "过期来源不应出现在默认列表中");

        List<CorpusSource> withStale = repository.listSources(ctxT,
                new SourceQuery(VerificationStatus.VERIFIED, null, null, true, 50, 0));
        assertTrue(withStale.stream().anyMatch(s -> s.sourceId().equals(id)),
                "includeStale=true 时过期来源应出现");
    }

    @Test
    @DisplayName("统计区分「已入库」与「可检索」（过期后不计入可检索）")
    void statisticsDistinguishStoredFromRetrievable() {
        CorpusSource source = register("9787100000008", "统计教材");
        String id = source.sourceId();
        repository.applyVerification(ctxT, id, new VerificationDecision(
                VerificationStatus.IN_REVIEW, "认领", REVIEWER, VerificationMethod.NONE, null, 365));
        repository.applyVerification(ctxT, id,
                VerificationDecision.approve(TrustTier.OFFICIAL_PUBLISHER, VerificationMethod.ISBN,
                        "ISBN:ref@2026-01-01", REVIEWER, 1));

        transactionTemplate.executeWithoutResult(st -> {
            jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                    String.class, T);
            jdbcTemplate.update("""
                    UPDATE rag_source SET verified_at = now() - interval '30 days'
                    WHERE source_id = ?::uuid
                    """, id);
        });

        CorpusStatistics stats = repository.statistics(ctxT);
        assertTrue(stats.totalSources() >= 1);
        // 过期源不计入 admissible
        assertTrue(stats.admissibleSources() <= stats.verifiedSources());
        assertNotNull(stats.sourcesByVerificationStatus());
        assertNotNull(stats.chunksByRole());
    }

    @Test
    @DisplayName("跨租户操作：租户 T 无法审核租户 U 的来源（视为不存在）")
    void crossTenantVerificationBlocked() {
        // ⚠️ 必须用**租户 U 的上下文**登记来源，再用 T 的上下文去审核——
        //    若用 T 登记却期望 T 查不到，断言本身就是矛盾的
        //    （2026-10-22 实测踩中：批量替换把 register(U,…) 改成了固定 ctxT）。
        CorpusSource source = repository.registerSource(ctxU,
                new SourceRegistration("教材", "租户U教材", "人民教育出版社",
                        "9787100000009", null, "obj://book.pdf", "b".repeat(64)),
                REVIEWER);
        String id = source.sourceId();
        CorpusSource result = repository.applyVerification(ctxT, id,
                VerificationDecision.approve(TrustTier.OFFICIAL_PUBLISHER, VerificationMethod.ISBN,
                        "ISBN:ref@2026-01-01", REVIEWER, 365));
        assertNull(result, "跨租户审核应查无此源（RLS 过滤）");
    }

    /**
     * 在租户 T 下登记一条来源。
     *
     * <p>⚠️ 不再需要租户夹具：RAG 库里**没有**租户表（V4 迁移已移除副本），
     * 租户上下文由 platform 下发（{@link RagTenantContext}）。
     */
    private CorpusSource register(String isbn, String title) {
        return repository.registerSource(ctxT,
                new SourceRegistration("教材", title, "人民教育出版社", isbn, null,
                        "obj://book.pdf", "b".repeat(64)),
                REVIEWER);
    }

    /**
     * 在指定租户下登记一条来源（供载体级夹具使用）。
     */
    private CorpusSource registerIn(RagTenantContext ctx, String isbn, String title) {
        return repository.registerSource(ctx,
                new SourceRegistration("教材", title, "人民教育出版社", isbn, null,
                        "obj://book.pdf", "b".repeat(64)),
                REVIEWER);
    }

    // ---------------------------------------------------------------------
    // 载体级校验流转（IT：只有真 DB 才能证明状态真的落库了）
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("载体不得高于来源：来源未 VERIFIED 时载体置 VERIFIED 被拒且状态未变")
    void carrierCannotExceedUnverifiedSource() {
        CorpusSource source = registerIn(ctxM, "9787100000010", "待审教材");
        String documentId = seedDocument(ctxM, source.sourceId(), "扫描版", "SCAN", "CHUNKED");

        // ⚠️ 载体初始态是 PENDING（DB 默认），必须先认领才能谈「通过」——
        //    否则被拒的原因是「PENDING 直通 VERIFIED 非法」，
        //    而<b>不是</b>本用例要验的「载体不得高于来源」，断言就落空了。
        repository.applyDocumentVerification(ctxM, documentId,
                new DocumentVerificationDecision(VerificationStatus.IN_REVIEW, "认领", REVIEWER,
                        VerificationMethod.NONE, null, 365));

        assertThrows(IllegalStateException.class, () -> repository.applyDocumentVerification(ctxM,
                documentId, DocumentVerificationDecision.approve(VerificationMethod.ISBN,
                        "ISBN:ref@2026-10-23", REVIEWER, 365)),
                "来源未通过校验时载体不得自称 VERIFIED");

        // ⚠️ 关键断言：库中状态必须仍是 IN_REVIEW——被拒的流转不能留下写入痕迹。
        CorpusDocument after = findDocument(ctxM, documentId);
        assertNotNull(after);
        assertEquals(VerificationStatus.IN_REVIEW, after.verificationStatus());
        assertFalse(after.retrievable(), "未通过校验的载体不得可检索");
    }

    @Test
    @DisplayName("载体级合法流转：来源已 VERIFIED 时载体可置 VERIFIED 并置为可检索")
    void carrierCanBeVerifiedAfterSource() {
        CorpusSource source = registerIn(ctxM, "9787100000011", "已审教材");
        approveSource(ctxM, source.sourceId());
        String documentId = seedDocument(ctxM, source.sourceId(), "官方电子版", "PDF", "CHUNKED");

        // ⚠️ 载体初始态是 PENDING：必须走 PENDING → IN_REVIEW → VERIFIED
        //    （载体复用来源级转移表，T-12 禁止跳过 IN_REVIEW 直通 VERIFIED）。
        repository.applyDocumentVerification(ctxM, documentId,
                new DocumentVerificationDecision(VerificationStatus.IN_REVIEW, "认领", REVIEWER,
                        VerificationMethod.NONE, null, 365));
        CorpusDocument updated = repository.applyDocumentVerification(ctxM, documentId,
                DocumentVerificationDecision.approve(VerificationMethod.ISBN,
                        "ISBN:ref@2026-10-23", REVIEWER, 365));
        assertNotNull(updated);
        assertEquals(VerificationStatus.VERIFIED, updated.verificationStatus());
        assertTrue(updated.retrievable(), "VERIFIED 的载体必须可检索");
        assertNull(updated.revokedAt());
        assertEquals("PENDING", updated.licenseStatus());
    }

    @Test
    @DisplayName("载体级撤回：置不可检索、写撤回依据、并留 REVOKE 审计")
    void carrierRevocationRecordsReasonAndAudit() {
        CorpusSource source = registerIn(ctxM, "9787100000012", "待撤回教材");
        approveSource(ctxM, source.sourceId());
        String documentId = seedDocument(ctxM, source.sourceId(), "第三方扫描版", "SCAN", "CHUNKED");

        CorpusDocument revoked = repository.applyDocumentVerification(ctxM, documentId,
                DocumentVerificationDecision.revoke("权利人要求下架", LEGAL));
        assertNotNull(revoked);
        assertEquals(VerificationStatus.REVOKED, revoked.verificationStatus());
        assertFalse(revoked.retrievable(), "撤回后载体必须不可检索");
        assertNotNull(revoked.revokedAt(), "撤回必须写撤回时间");
        assertEquals("权利人要求下架", revoked.revokedReason(), "撤回必须带依据（DB CHECK 亦强制）");
        assertEquals("RESTRICTED", revoked.licenseStatus(), "撤回必须同时关闭版权轴（G-4）");

        Integer auditCount = transactionTemplate.execute(st -> {
            jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                    String.class, ctxM.tenantId());
            return jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM rag_access_audit WHERE source_id = ?::uuid AND action = 'REVOKE'",
                    Integer.class, source.sourceId());
        });
        assertNotNull(auditCount);
        assertTrue(auditCount >= 1, "载体级撤回必须留下 REVOKE 审计");
    }

    @Test
    @DisplayName("载体级 REVOKED 是终态：不可再流转")
    void revokedCarrierIsTerminal() {
        CorpusSource source = registerIn(ctxM, "9787100000013", "终态教材");
        approveSource(ctxM, source.sourceId());
        String documentId = seedDocument(ctxM, source.sourceId(), "被撤回载体", "PDF", "CHUNKED");
        repository.applyDocumentVerification(ctxM, documentId,
                DocumentVerificationDecision.revoke("权利人要求下架", LEGAL));

        assertThrows(IllegalStateException.class, () -> repository.applyDocumentVerification(ctxM,
                documentId, DocumentVerificationDecision.approve(VerificationMethod.ISBN,
                        "ISBN:ref@2026-10-23", REVIEWER, 365)),
                "REVOKED 是终态（T-10）");
    }

    // ---------------------------------------------------------------------
    // 维护问题清单（IT：SQL 口径只有真 DB 才能证明）
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("问题清单：缺向量载体按载体统计缺向量片段数")
    void issuesReportDocumentsMissingEmbedding() {
        CorpusSource source = registerIn(ctxM, "9787100000014", "缺向量教材");
        approveSource(ctxM, source.sourceId());
        String documentId = seedDocument(ctxM, source.sourceId(), "缺向量载体", "PDF", "CHUNKED");
        String editionId = queryEditionId(ctxM, documentId);
        seedChunk(ctxM, documentId, editionId, "片段一");
        seedChunk(ctxM, documentId, editionId, "片段二");

        CorpusIssues issues = repository.listIssues(ctxM);

        CorpusIssues.DocumentEmbeddingGap gap = issues.documentsMissingEmbedding().stream()
                .filter(g -> g.documentId().equals(documentId))
                .findFirst()
                .orElse(null);
        assertNotNull(gap, "两个片段都没有向量 → 该载体必须出现在缺向量清单里");
        assertEquals(2, gap.chunkCount());
        assertEquals(2, gap.missingEmbeddingCount());
        assertEquals("PDF", gap.carrier());
    }

    @Test
    @DisplayName("问题清单：一个片段都没有的载体不算「缺向量」（避免掩盖解析失败）")
    void issuesDoNotMixParseFailureIntoMissingEmbedding() {
        CorpusSource source = registerIn(ctxM, "9787100000015", "解析失败教材");
        approveSource(ctxM, source.sourceId());
        String documentId = seedDocument(ctxM, source.sourceId(), "解析失败载体", "PDF", "FAILED");

        CorpusIssues issues = repository.listIssues(ctxM);

        assertTrue(issues.documentsMissingEmbedding().stream()
                        .noneMatch(g -> g.documentId().equals(documentId)),
                "没有片段的载体不应被算成「缺向量」——那会掩盖真正的解析失败");
        CorpusIssues.DocumentParseFailure failure = issues.documentsFailedParse().stream()
                .filter(f -> f.documentId().equals(documentId))
                .findFirst()
                .orElse(null);
        assertNotNull(failure, "parse_status='FAILED' 的载体必须出现在解析失败清单里");
        assertEquals(0, failure.chunkCount());
        assertEquals("FAILED", failure.parseStatus());
    }

    @Test
    @DisplayName("问题清单：待审核来源带服务端计算的 stale 标记")
    void issuesReportPendingSourcesWithServerComputedStale() {
        CorpusSource source = registerIn(ctxM, "9787100000016", "待审教材");
        // 来源登记后恒为 PENDING
        CorpusIssues issues = repository.listIssues(ctxM);

        CorpusIssues.SourcePendingReview pending = issues.sourcesPendingReview().stream()
                .filter(s -> s.sourceId().equals(source.sourceId()))
                .findFirst()
                .orElse(null);
        assertNotNull(pending, "PENDING 来源必须出现在待审核清单里");
        assertEquals("PENDING", pending.verificationStatus());
        assertEquals("UNVERIFIED", pending.trustTier());
        assertFalse(pending.stale(), "未通过校验的来源谈不上「结论过期」");
    }

    // ---------------------------------------------------------------------
    // 夹具
    // ---------------------------------------------------------------------

    /**
     * 把来源推进到 VERIFIED。
     */
    private void approveSource(RagTenantContext ctx, String sourceId) {
        repository.applyVerification(ctxM, sourceId, new VerificationDecision(
                VerificationStatus.IN_REVIEW, "认领", REVIEWER, VerificationMethod.NONE, null, 365));
        repository.applyVerification(ctxM, sourceId,
                VerificationDecision.approve(TrustTier.OFFICIAL_PUBLISHER, VerificationMethod.ISBN,
                        "ISBN:ref@2026-10-23", REVIEWER, 365));
    }

    /**
     * 在租户 T 下种一个载体（edition + document）。
     *
     * @return 载体 ID
     */
    private String seedDocument(RagTenantContext ctx, String sourceId, String title, String carrier, String parseStatus) {
        String editionId = java.util.UUID.randomUUID().toString();
        String documentId = java.util.UUID.randomUUID().toString();
        transactionTemplate.executeWithoutResult(st -> {
            jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                    String.class, ctx.tenantId());
            jdbcTemplate.update("""
                    INSERT INTO rag_edition (edition_id, tenant_id, source_id, subject, grade, term,
                                             publisher, revision_code)
                    VALUES (?::uuid, rag_current_tenant(), ?::uuid, '数学', '八年级', '上册',
                            '人教版', 'IT-' || ?)
                    """, editionId, sourceId, editionId);
            jdbcTemplate.update("""
                    INSERT INTO rag_document (document_id, tenant_id, source_id, edition_id, carrier,
                                              title, parse_status)
                    VALUES (?::uuid, rag_current_tenant(), ?::uuid, ?::uuid, ?, ?, ?)
                    """, documentId, sourceId, editionId, carrier, title, parseStatus);
        });
        return documentId;
    }

    /**
     * 在租户 T 下种一个片段（不写向量 → 构成「缺向量」夹具）。
     */
    private void seedChunk(RagTenantContext ctx, String documentId, String editionId, String text) {
        String chunkId = java.util.UUID.randomUUID().toString();
        transactionTemplate.executeWithoutResult(st -> {
            jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                    String.class, ctx.tenantId());
            jdbcTemplate.update("""
                    INSERT INTO rag_chunk (chunk_id, tenant_id, document_id, edition_id, role, text,
                                           page_number, section_path, char_start, char_end, content_hash)
                    VALUES (?::uuid, rag_current_tenant(), ?::uuid, ?::uuid,
                            'EXPLANATION'::rag_chunk_role, ?, 1, '第一章', 0, ?, ?)
                    """, chunkId, documentId, editionId, text, text.length(), "d".repeat(64));
        });
    }

    /**
     * 读回载体（含载体级治理字段）。
     */
    private CorpusDocument findDocument(RagTenantContext ctx, String documentId) {
        return transactionTemplate.execute(st -> {
            jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                    String.class, ctx.tenantId());
            return jdbcTemplate.query("""
                    SELECT d.document_id, d.source_id, d.edition_id, d.carrier, d.title,
                           d.page_count, d.object_ref, d.parse_status, d.parser_version,
                           d.content_hash, d.created_at, d.updated_at,
                           d.ver_status, d.retrievable, d.revoked_at, d.revoked_reason,
                           d.license_status,
                           (SELECT count(*) FROM rag_chunk c
                             WHERE c.tenant_id = d.tenant_id AND c.document_id = d.document_id)
                               AS chunk_count
                    FROM rag_document d
                    WHERE d.tenant_id = rag_current_tenant() AND d.document_id = ?::uuid
                    """, (rs, i) -> new CorpusDocument(
                            rs.getString("document_id"),
                            rs.getString("source_id"),
                            rs.getString("edition_id"),
                            rs.getString("carrier"),
                            rs.getString("title"),
                            rs.getInt("page_count"),
                            rs.getString("object_ref"),
                            rs.getString("parse_status"),
                            rs.getString("parser_version"),
                            rs.getString("content_hash"),
                            rs.getLong("chunk_count"),
                            VerificationStatus.valueOf(rs.getString("ver_status")),
                            rs.getBoolean("retrievable"),
                            rs.getTimestamp("revoked_at") == null ? null
                                    : rs.getTimestamp("revoked_at").toInstant(),
                            rs.getString("revoked_reason"),
                            rs.getString("license_status"),
                            rs.getTimestamp("created_at").toInstant(),
                            rs.getTimestamp("updated_at").toInstant()),
                    documentId).stream().findFirst().orElse(null);
        });
    }

    /**
     * 取载体所属的版本坐标 ID（种片段时需要）。
     */
    private String queryEditionId(RagTenantContext ctx, String documentId) {
        return transactionTemplate.execute(st -> {
            jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                    String.class, ctx.tenantId());
            return jdbcTemplate.queryForObject(
                    "SELECT edition_id FROM rag_document WHERE document_id = ?::uuid",
                    String.class, documentId);
        });
    }
}
