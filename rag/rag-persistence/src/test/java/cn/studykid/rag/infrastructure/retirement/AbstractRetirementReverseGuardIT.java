package cn.studykid.rag.infrastructure.retirement;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * M4 逐批退役 RLS 的「反向验证守卫」基类（DB 集成测试骨架）。
 *
 * <p><b>目的</b>：退役某批表的 RLS 后，行级隔离的<b>唯一</b>权威变为应用层显式谓词。
 * 本基类用「控制 / 影子双跑」的对偶思路做<b>反向验证</b>：
 * <ul>
 *   <li><b>正向</b> {@link #forwardIsolationHolds()}：带显式谓词的查询只返回本租户行（与生产谓词绑定）。</li>
 *   <li><b>反向</b> {@link #reverseRemovalLeaks()}：撤掉谓词（模拟回归）后跨租户泄露他租户行 ——
 *       这证明显式谓词承重，也即「撤掉修复点会让隔离失效」（守卫转红的真实含义）。</li>
 * </ul>
 *
 * <p><b>⛔ 激活门禁（与 deploy/sql/rag/1[4567]_*_retirement.sql 同步）</b>：
 * 默认跳过。仅在对应批次 RLS 真正退役（脚本 ROLLBACK→COMMIT、代码移除 set_config）后，
 * 以 {@code -Dm4.<batch>.retired=true} 运行激活，作为该批的<b>最后一道路障</b>。
 * 退役前运行会因 RLS 仍在场而误报，故门禁不可省略。
 *
 * <p><b>反向验证的充要性</b>：{@link #scopedCountSql()} 默认镜像生产谓词（硬编码）。
 * 要让它真正捕获<b>生产代码</b>的回归（撤掉生产谓词的修复点会红），子类应把
 * {@link #scopedCountSql()} 替换为对生产 Repository 的调用 —— 见 R1 子类的
 * {@code forwardIsolationViaProductionRepository()} 范例（检索面用真实
 * {@code JdbcRetrievalRepository.search} 证明撤掉谓词会红）。本基类提供统一结构，子类负责接线。
 *
 * <p><b>守门</b>：需 {@code IT_RAG_PG_URL}（应用角色）与 {@code IT_RAG_PG_ADMIN_URL}
 * （BYPASSRLS 角色）同时设置；否则整类跳过。夹具使用独立 UUID 命名空间，避开既有 IT。
 */
abstract class AbstractRetirementReverseGuardIT {

    protected static final String PG_URL_ENV = "IT_RAG_PG_URL";
    protected static final String PG_USER_ENV = "IT_RAG_PG_USERNAME";
    protected static final String PG_PASSWORD_ENV = "IT_RAG_PG_PASSWORD";
    protected static final String PG_ADMIN_URL_ENV = "IT_RAG_PG_ADMIN_URL";
    protected static final String PG_ADMIN_USER_ENV = "IT_RAG_PG_ADMIN_USERNAME";
    protected static final String PG_ADMIN_PASSWORD_ENV = "IT_RAG_PG_ADMIN_PASSWORD";

    protected JdbcTemplate appJdbcTemplate;
    protected JdbcTemplate adminJdbcTemplate;

    /** 批次标识，用于 {@code -Dm4.<batch>.retired} 门禁（r1/r2/r3/central）。 */
    protected abstract String batchProperty();

    /** 本批需清理的表（admin 连接执行，绕过 RLS）。 */
    protected abstract String[] cleanupSql();

    /** 在两个租户下播种夹具行。 */
    protected abstract void seedTenants();

    /** 反向验证：带显式谓词的计数 SQL（镜像生产谓词，{@code ?} 绑定 tenant_id::uuid）。 */
    protected abstract String scopedCountSql();

    /** 反向验证：撤掉谓词的计数 SQL（无 tenant 过滤，模拟回归）。 */
    protected abstract String unscopedCountSql();

    protected abstract String tenantId();
    protected abstract String otherTenantId();

    @BeforeAll
    static void envGuard() {
        Assumptions.assumeTrue(System.getenv(PG_URL_ENV) != null,
                "skipped: 需要 " + PG_URL_ENV);
        Assumptions.assumeTrue(System.getenv(PG_ADMIN_URL_ENV) != null,
                "skipped: 需要 " + PG_ADMIN_URL_ENV);
    }

    @BeforeEach
    void retirementGateAndSetup() {
        // ⛔ 硬门禁：本守卫仅在对应批次 RLS 真正退役后激活。退役前（RLS 仍在场）运行会误报。
        //    与 deploy/sql/rag/1[4567]_*_retirement.sql 的 ROLLBACK→COMMIT 同步：
        //    脚本落实 + 代码移除 set_config 后，再 -Dm4.<batch>.retired=true 跑本类。
        Assumptions.assumeTrue(System.getProperty("m4." + batchProperty() + ".retired") != null,
                "skipped: 批次 " + batchProperty() + " 尚未退役（激活：mvn ... -Dm4."
                        + batchProperty() + ".retired=true）");

        DriverManagerDataSource appDs = new DriverManagerDataSource();
        appDs.setUrl(withRagSearchPath(System.getenv(PG_URL_ENV)));
        appDs.setUsername(envOrDefault(PG_USER_ENV, extractUserFromUrl(System.getenv(PG_URL_ENV))));
        appDs.setPassword(envOrDefault(PG_PASSWORD_ENV, null));
        appJdbcTemplate = new JdbcTemplate(appDs);

        DriverManagerDataSource adminDs = new DriverManagerDataSource();
        adminDs.setUrl(withRagSearchPath(System.getenv(PG_ADMIN_URL_ENV)));
        adminDs.setUsername(envOrDefault(PG_ADMIN_USER_ENV, extractUserFromUrl(System.getenv(PG_ADMIN_URL_ENV))));
        adminDs.setPassword(envOrDefault(PG_ADMIN_PASSWORD_ENV, null));
        adminJdbcTemplate = new JdbcTemplate(adminDs);

        for (String sql : cleanupSql()) {
            adminJdbcTemplate.update(sql);
        }
        seedTenants();
    }

    @AfterEach
    void tearDown() {
        // ⚠️ @BeforeEach 的退役门禁会先于夹具初始化跳过用例；此时 @AfterEach 仍会执行。
        //    未初始化连接就清理会把「有意跳过」变成 7 条 NPE，掩盖真实测试结果。
        if (adminJdbcTemplate == null) {
            return;
        }
        for (String sql : cleanupSql()) {
            adminJdbcTemplate.update(sql);
        }
    }

    @Test
    @DisplayName("正向：显式谓词把可见集严格收口在本租户（撤掉修复点会红）")
    void forwardIsolationHolds() {
        int scoped = appJdbcTemplate.queryForObject(scopedCountSql(), Integer.class, tenantId());
        assertTrue(scoped >= 1,
                "带显式谓词的查询应至少返回本租户 1 行；若生产谓词被误删，此查询会退化成 unscoped → 守卫转红");
    }

    @Test
    @DisplayName("反向：撤掉谓词 → 跨租户泄露（证明显式谓词是唯一隔离边界）")
    void reverseRemovalLeaks() {
        int scoped = appJdbcTemplate.queryForObject(scopedCountSql(), Integer.class, tenantId());
        int leaked = appJdbcTemplate.queryForObject(unscopedCountSql(), Integer.class);
        assertTrue(scoped >= 1, "前置：本租户应有夹具行");
        assertTrue(leaked > scoped,
                "撤掉谓词后可见行数应严格大于带谓词时（泄露了他租户行），"
                        + "证明隔离在退役后完全由显式谓词承重；若 leaked<=scoped 说明隔离未真正移交应用层");
    }

    protected static String withRagSearchPath(String url) {
        if (url == null || url.isBlank() || url.contains("search_path=")) {
            return url;
        }
        String sep = url.contains("?") ? "&" : "?";
        return url + sep + "options=-c%20search_path%3Drag%2Cpublic";
    }

    protected static String envOrDefault(String name, String fallback) {
        String v = System.getenv(name);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    protected static String extractUserFromUrl(String url) {
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
