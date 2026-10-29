package cn.studykid.rag.management.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

import cn.studykid.rag.core.scope.RagScope;
import cn.studykid.rag.core.source.TrustTier;
import cn.studykid.rag.management.core.RagTenantContext;
import cn.studykid.rag.management.core.RagManagementContext;
import cn.studykid.rag.management.ports.DictionaryPort;
import cn.studykid.rag.management.ports.DictionaryPort.DictEntry;
import cn.studykid.rag.management.ports.DictionaryPort.Scope;

/**
 * 字典仓库集成测试（Point 2：枚举下拉的数据源）。
 *
 * <p><b>⚠️ 守门</b>：仅在环境变量 {@code IT_RAG_PG_URL} 设置时运行
 * （指向已应用 {@code rag} 迁移、含 {@code V6__rag_dictionary} 的 PostgreSQL 实例）。
 * 未设置则整类跳过——避免在无 DB 的 CI/本地环境报红。
 *
 * <p><b>⚠️ 覆盖重点</b>：本 IT 验证三件<b>只有真 DB 才能证明</b>的事：
 * <ol>
 *   <li><b>动态字典 CRUD 真实落库</b>：upsert 后经 RLS 隔离的租户能读回；</li>
 *   <li><b>RLS 跨租户隔离</b>：租户 T 写入的字典项，租户 U 看不到；</li>
 *   <li><b>派生字典只读</b>：{@code TRUST_TIER}/{@code VERIFICATION_METHOD}/
 *       {@code LICENSE_STATUS} 由后端枚举派生，{@code isEditable} 为 false，
 *       对其执行 upsert/delete 抛 {@code IllegalStateException}。</li>
 * </ol>
 *
 * <p><b>全局/租户双作用域（V7 引入，V8 收敛到哨兵）</b>额外需要一条<b>超级用户</b>连接
 * （{@code IT_RAG_PG_ADMIN_URL}）来播种全局行：应用角色按设计<b>写不出</b>
 * 全局桶的行（{@code rag_dictionary_write} 策略不放行哨兵租户），
 * 所以「没有管理员连接」本身就是被测性质之一，不能用应用连接凑合 seed。
 * 未提供管理员连接时，这组用例逐个跳过（不是失败）。
 *
 * <p>⚠️ 全局行的载体是 {@link RagScope#GLOBAL_TENANT_ID} 哨兵，<b>不是</b> {@code tenant_id IS NULL}：
 * V7 用过 NULL，V8 把本表迁到与语料表同一表示。任何断言再写 {@code IS NULL}
 * 都会恒真地通过或恒假地失败，取决于当时库里有没有脏行——不是被测性质。
 *
 * <p>⚠️ <b>本 IT 不是隔离性的权威证明</b>：它只覆盖「应用代码怎么读写」，
 * 覆盖不到「数据库允不允许越权语句」。后者由
 * {@code scripts/rag-dict-scope-counterproof.sh} 以直连 psql 注入的方式实测。
 */
class JdbcDictionaryRepositoryIT {

    private static final String PG_URL_ENV = "IT_RAG_PG_URL";
    private static final String PG_USER_ENV = "IT_RAG_PG_USERNAME";
    private static final String PG_PASSWORD_ENV = "IT_RAG_PG_PASSWORD";
    /** 管理员（超级用户）连接：仅用于播种/核对全局行，不参与业务断言。 */
    private static final String PG_ADMIN_URL_ENV = "IT_RAG_PG_ADMIN_URL";
    private static final String PG_ADMIN_USER_ENV = "IT_RAG_PG_ADMIN_USERNAME";
    private static final String PG_ADMIN_PASSWORD_ENV = "IT_RAG_PG_ADMIN_PASSWORD";

    private static final String T = "11111111-1111-1111-1111-111111111111";
    private static final String U = "22222222-2222-2222-2222-222222222222";

    private JdbcTemplate jdbcTemplate;
    private TransactionTemplate transactionTemplate;
    private DictionaryPort repository;

    /** 管理员连接（可为 null：未配置时全局用例跳过）。 */
    private JdbcTemplate adminJdbcTemplate;

    private final RagTenantContext ctxT = new RagTenantContext(T, TrustTier.OFFICIAL_TUTORIAL);
    private final RagTenantContext ctxU = new RagTenantContext(U, TrustTier.OFFICIAL_TUTORIAL);

    @BeforeAll
    static void guard() {
        Assumptions.assumeTrue(System.getenv(PG_URL_ENV) != null,
                "skipped: " + PG_URL_ENV + " 未设置（需要已应用 V6 迁移的 PostgreSQL）");
    }

    @BeforeEach
    void setup() {
        String url = withRagSearchPath(System.getenv(PG_URL_ENV));
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setUrl(url);
        // ⚠️ 凭证必须走**独立参数**，不能内嵌在 JDBC URL 里（见 JdbcCorpusManagementRepositoryIT）。
        String user = envOrDefault(PG_USER_ENV, extractUserFromUrl(System.getenv(PG_URL_ENV)));
        String password = envOrDefault(PG_PASSWORD_ENV, null);
        ds.setUsername(user);
        ds.setPassword(password);
        jdbcTemplate = new JdbcTemplate(ds);
        transactionTemplate = new TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));
        repository = new JdbcDictionaryRepository(jdbcTemplate, transactionTemplate);

        // 管理员连接（仅用于 seed/核对全局行）。缺配置时保持 null，由用例自行跳过。
        String adminUrl = System.getenv(PG_ADMIN_URL_ENV);
        if (adminUrl != null && !adminUrl.isBlank()) {
            DriverManagerDataSource adminDs = new DriverManagerDataSource();
            adminDs.setUrl(withRagSearchPath(adminUrl));
            adminDs.setUsername(envOrDefault(PG_ADMIN_USER_ENV,
                    extractUserFromUrl(adminUrl)));
            adminDs.setPassword(envOrDefault(PG_ADMIN_PASSWORD_ENV, null));
            adminJdbcTemplate = new JdbcTemplate(adminDs);
        }
    }

    /** 需要管理员连接的用例的前置：没有就跳过，不算失败。 */
    private void requireAdminConnection() {
        Assumptions.assumeTrue(adminJdbcTemplate != null,
                "skipped: " + PG_ADMIN_URL_ENV + " 未设置（全局行的 seed 无法经应用角色完成）");
    }

    /** 以超级用户发布一条平台全局字典项（应用角色做不到，这正是被测性质）。 */
    private void publishGlobal(String code, String label, int sortOrder) {
        adminJdbcTemplate.update("""
                INSERT INTO rag_dictionary
                    (tenant_id, dict_type, code, label, sort_order, scope)
                VALUES (rag_global_tenant_id(), 'SOURCE_TYPE', ?, ?, ?, 'PLATFORM')
                ON CONFLICT (tenant_id, dict_type, code)
                DO UPDATE SET label = EXCLUDED.label, sort_order = EXCLUDED.sort_order
                """, code, label, sortOrder);
    }

    /** 清理本类发布过的全局项（按 code 列表），避免污染后续运行。 */
    private void retireGlobal(String... codes) {
        for (String code : codes) {
            adminJdbcTemplate.update(
                    "DELETE FROM rag_dictionary WHERE tenant_id = rag_global_tenant_id()"
                            + " AND dict_type='SOURCE_TYPE' AND code = ?", code);
            adminJdbcTemplate.update(
                    "DELETE FROM rag_dictionary WHERE dict_type='SOURCE_TYPE' AND code = ?", code);
        }
    }

    /** 全局项条数（绕过 RLS 的真实值，用于验证「租户写不出全局行」）。 */
    private int globalRowCount(String code) {
        Integer n = adminJdbcTemplate.queryForObject(
                "SELECT count(*) FROM rag_dictionary"
                        + " WHERE tenant_id = rag_global_tenant_id()"
                        + " AND dict_type='SOURCE_TYPE' AND code = ?", Integer.class, code);
        return n == null ? 0 : n;
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

    private static String withRagSearchPath(String url) {
        if (url == null || url.isBlank() || url.contains("search_path=")) {
            return url;
        }
        String sep = url.contains("?") ? "&" : "?";
        return url + sep + "options=-c%20search_path%3Drag%2Cpublic";
    }

    @Test
    @DisplayName("动态字典 SOURCE_TYPE：upsert 后本租户可回读")
    void sourceTypeUpsertRoundTrip() {
        repository.upsertEntry(ctxT, new DictEntry(
                DictionaryPort.TYPE_SOURCE_TYPE, "TEXTBOOK", "教材", 0, true));
        List<DictEntry> entries = repository.entriesOf(RagManagementContext.forTenant(ctxT), DictionaryPort.TYPE_SOURCE_TYPE);
        assertTrue(entries.stream().anyMatch(e ->
                "TEXTBOOK".equals(e.code()) && "教材".equals(e.label())),
                "本租户应能读回刚写入的字典项");
    }

    @Test
    @DisplayName("RLS 隔离：租户 T 写入的字典项，租户 U 看不到")
    void crossTenantIsolation() {
        repository.upsertEntry(ctxT, new DictEntry(
                DictionaryPort.TYPE_SOURCE_TYPE, "T_ONLY", "仅T可见", 0, true));
        List<DictEntry> tenantUEntries =
                repository.entriesOf(RagManagementContext.forTenant(ctxU), DictionaryPort.TYPE_SOURCE_TYPE);
        assertTrue(tenantUEntries.stream().noneMatch(e -> "T_ONLY".equals(e.code())),
                "租户 U 不应看到租户 T 的字典项");
    }

    @Test
    @DisplayName("动态字典可编辑，派生字典只读")
    void editabilityDistinction() {
        assertTrue(repository.isEditable(DictionaryPort.TYPE_SOURCE_TYPE),
                "SOURCE_TYPE 应可维护");
        assertFalse(repository.isEditable(DictionaryPort.TYPE_TRUST_TIER),
                "TRUST_TIER 是派生字典，应只读");
        assertFalse(repository.isEditable(DictionaryPort.TYPE_VERIFICATION_METHOD));
        assertFalse(repository.isEditable(DictionaryPort.TYPE_LICENSE_STATUS));
    }

    @Test
    @DisplayName("派生字典由后端枚举派生，返回全部合法值")
    void derivedDictionariesReturned() {
        List<DictEntry> tiers = repository.entriesOf(RagManagementContext.forTenant(ctxT), DictionaryPort.TYPE_TRUST_TIER);
        // TrustTier 枚举共 5 个值
        assertEquals(5, tiers.size());
        assertTrue(tiers.stream().anyMatch(e -> "OFFICIAL_PUBLISHER".equals(e.code())));

        List<DictEntry> licenses = repository.entriesOf(RagManagementContext.forTenant(ctxT), DictionaryPort.TYPE_LICENSE_STATUS);
        assertTrue(licenses.stream().anyMatch(e -> "LICENSED".equals(e.code())));
    }

    @Test
    @DisplayName("派生字典不允许写入 → 抛 IllegalStateException")
    void derivedDictionaryWriteRejected() {
        assertThrows(IllegalStateException.class, () -> repository.upsertEntry(ctxT,
                new DictEntry(DictionaryPort.TYPE_TRUST_TIER, "X", "非法", 0, true)));
    }

    @Test
    @DisplayName("动态字典删除后本租户读不到")
    void sourceTypeDelete() {
        repository.upsertEntry(ctxT, new DictEntry(
                DictionaryPort.TYPE_SOURCE_TYPE, "TO_DELETE", "待删", 0, true));
        repository.deleteEntry(ctxT, DictionaryPort.TYPE_SOURCE_TYPE, "TO_DELETE");
        List<DictEntry> entries = repository.entriesOf(RagManagementContext.forTenant(ctxT), DictionaryPort.TYPE_SOURCE_TYPE);
        assertTrue(entries.stream().noneMatch(e -> "TO_DELETE".equals(e.code())));
    }

    // =====================================================================
    // 全局 / 租户双作用域（V7）
    // =====================================================================

    @Test
    @DisplayName("全局项对所有租户可见，且 scope 标为 PLATFORM")
    void globalEntryVisibleToEveryTenant() {
        requireAdminConnection();
        publishGlobal("G_SHARED", "全局共有", 5);
        try {
            DictEntry inT = find(repository.entriesOf(RagManagementContext.forTenant(ctxT), DictionaryPort.TYPE_SOURCE_TYPE),
                    "G_SHARED");
            DictEntry inU = find(repository.entriesOf(RagManagementContext.forTenant(ctxU), DictionaryPort.TYPE_SOURCE_TYPE),
                    "G_SHARED");
            assertNotNull(inT, "租户 T 应读到平台全局项");
            assertNotNull(inU, "租户 U 应读到同一条平台全局项");
            assertEquals(Scope.PLATFORM, inT.scope(), "全局项必须标记为 PLATFORM");
            assertEquals("全局共有", inT.label());
        } finally {
            retireGlobal("G_SHARED");
        }
    }

    @Test
    @DisplayName("同 code 覆盖：T 看到自己的标签，U 仍看到平台标签")
    void tenantOverrideIsLocalOnly() {
        requireAdminConnection();
        publishGlobal("G_OVR", "平台原文", 5);
        try {
            repository.upsertEntry(ctxT, new DictEntry(
                    DictionaryPort.TYPE_SOURCE_TYPE, "G_OVR", "租户改写", 5, true));

            DictEntry inT = find(repository.entriesOf(RagManagementContext.forTenant(ctxT), DictionaryPort.TYPE_SOURCE_TYPE),
                    "G_OVR");
            DictEntry inU = find(repository.entriesOf(RagManagementContext.forTenant(ctxU), DictionaryPort.TYPE_SOURCE_TYPE),
                    "G_OVR");
            assertNotNull(inT);
            assertNotNull(inU);
            assertEquals("租户改写", inT.label(), "T 侧应被本租户覆盖项胜出");
            assertEquals(Scope.TENANT, inT.scope());
            assertEquals("平台原文", inU.label(), "覆盖只作用于本租户，不得影响其他租户");
            assertEquals(Scope.PLATFORM, inU.scope());
            assertEquals(1, countByCode(ctxT, "G_OVR"), "覆盖后 T 侧只应看到一条");
            assertEquals(1, countByCode(ctxU, "G_OVR"), "U 侧同样一条（全局那条）");
        } finally {
            repository.deleteEntry(ctxT, DictionaryPort.TYPE_SOURCE_TYPE, "G_OVR");
            retireGlobal("G_OVR");
        }
    }

    @Test
    @DisplayName("租户删不掉全局项：deleteEntry 后其他租户仍可见")
    void tenantCannotDeleteGlobal() {
        requireAdminConnection();
        publishGlobal("G_UNDELETABLE", "谁都删不掉", 5);
        try {
            // 不带租户谓词也删不到——RLS 的 _delete 策略是严格相等。
            repository.deleteEntry(ctxT, DictionaryPort.TYPE_SOURCE_TYPE, "G_UNDELETABLE");
            assertEquals(1, globalRowCount("G_UNDELETABLE"),
                    "全局项必须存活：租户没有发布/撤回全局内容的权力");
            assertNotNull(find(repository.entriesOf(RagManagementContext.forTenant(ctxU), DictionaryPort.TYPE_SOURCE_TYPE),
                    "G_UNDELETABLE"), "其他租户仍应看到该全局项");
        } finally {
            retireGlobal("G_UNDELETABLE");
        }
    }

    @Test
    @DisplayName("租户 upsert 产不出全局行（写路径恒为租户级）")
    void tenantUpsertNeverProducesGlobalRow() {
        requireAdminConnection();
        try {
            repository.upsertEntry(ctxT, new DictEntry(
                    DictionaryPort.TYPE_SOURCE_TYPE, "G_SMUGGLE", "想冒充全局", 5, true));
            assertEquals(0, globalRowCount("G_SMUGGLE"),
                    "应用角色写出全局桶的行 = 越权发布全局配置");
            DictEntry inT = find(repository.entriesOf(RagManagementContext.forTenant(ctxT), DictionaryPort.TYPE_SOURCE_TYPE),
                    "G_SMUGGLE");
            assertNotNull(inT);
            assertEquals(Scope.TENANT, inT.scope());
        } finally {
            repository.deleteEntry(ctxT, DictionaryPort.TYPE_SOURCE_TYPE, "G_SMUGGLE");
            retireGlobal("G_SMUGGLE");
        }
    }

    @Test
    @DisplayName("全局项不会污染跨租户隔离：U 看不到 T 的私有项")
    void globalsDoNotLeakTenantPrivates() {
        requireAdminConnection();
        publishGlobal("G_SHARED2", "全局", 5);
        try {
            repository.upsertEntry(ctxT, new DictEntry(
                    DictionaryPort.TYPE_SOURCE_TYPE, "T_PRIVATE", "仅 T", 6, true));
            assertTrue(repository.entriesOf(RagManagementContext.forTenant(ctxU), DictionaryPort.TYPE_SOURCE_TYPE).stream()
                            .noneMatch(e -> "T_PRIVATE".equals(e.code())),
                    "放开全局可见性之后，租户私有行仍必须一行不漏");
        } finally {
            repository.deleteEntry(ctxT, DictionaryPort.TYPE_SOURCE_TYPE, "T_PRIVATE");
            retireGlobal("G_SHARED2");
        }
    }

    @Test
    @DisplayName("Java 侧哨兵常量与 DB 的 rag_global_tenant_id() 必须同源")
    void sentinelConstantMatchesDatabase() {
        String fromDb = jdbcTemplate.queryForObject("SELECT rag_global_tenant_id()", String.class);
        assertEquals(RagScope.GLOBAL_TENANT_ID, fromDb,
                "哨兵值写死在两侧：改一边不改另一边 = 全局行在应用眼里变成某个租户的行");
        assertTrue(RagScope.isGlobalTenant(fromDb));
    }

    @Test
    @DisplayName("V8 收敛后表里不允许再有 tenant_id IS NULL 的行")
    void noNullTenantRowsRemain() {
        requireAdminConnection();
        Integer nulls = adminJdbcTemplate.queryForObject(
                "SELECT count(*) FROM rag_dictionary WHERE tenant_id IS NULL", Integer.class);
        assertEquals(0, nulls == null ? 0 : nulls,
                "V7 的 NULL 表示已由 V8 迁到哨兵；残留 NULL 行读策略看不见，会成为永不生效的僵尸字典项");
    }

    private static DictEntry find(List<DictEntry> entries, String code) {
        return entries.stream().filter(e -> code.equals(e.code())).findFirst().orElse(null);
    }

    private int countByCode(RagTenantContext context, String code) {
        return (int) repository.entriesOf(RagManagementContext.forTenant(context), DictionaryPort.TYPE_SOURCE_TYPE).stream()
                .filter(e -> code.equals(e.code())).count();
    }
}
