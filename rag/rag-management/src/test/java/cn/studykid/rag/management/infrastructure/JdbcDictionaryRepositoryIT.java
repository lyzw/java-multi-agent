package cn.studykid.rag.management.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

import cn.studykid.rag.core.source.TrustTier;
import cn.studykid.rag.management.core.RagTenantContext;
import cn.studykid.rag.management.ports.DictionaryPort;
import cn.studykid.rag.management.ports.DictionaryPort.DictEntry;

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
 */
class JdbcDictionaryRepositoryIT {

    private static final String PG_URL_ENV = "IT_RAG_PG_URL";
    private static final String PG_USER_ENV = "IT_RAG_PG_USERNAME";
    private static final String PG_PASSWORD_ENV = "IT_RAG_PG_PASSWORD";

    private static final String T = "11111111-1111-1111-1111-111111111111";
    private static final String U = "22222222-2222-2222-2222-222222222222";

    private JdbcTemplate jdbcTemplate;
    private TransactionTemplate transactionTemplate;
    private DictionaryPort repository;

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
        List<DictEntry> entries = repository.entriesOf(ctxT, DictionaryPort.TYPE_SOURCE_TYPE);
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
                repository.entriesOf(ctxU, DictionaryPort.TYPE_SOURCE_TYPE);
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
        List<DictEntry> tiers = repository.entriesOf(ctxT, DictionaryPort.TYPE_TRUST_TIER);
        // TrustTier 枚举共 5 个值
        assertEquals(5, tiers.size());
        assertTrue(tiers.stream().anyMatch(e -> "OFFICIAL_PUBLISHER".equals(e.code())));

        List<DictEntry> licenses = repository.entriesOf(ctxT, DictionaryPort.TYPE_LICENSE_STATUS);
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
        List<DictEntry> entries = repository.entriesOf(ctxT, DictionaryPort.TYPE_SOURCE_TYPE);
        assertTrue(entries.stream().noneMatch(e -> "TO_DELETE".equals(e.code())));
    }
}
