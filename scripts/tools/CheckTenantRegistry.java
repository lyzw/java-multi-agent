import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 租户登记一致性巡检（带外、只读）。
 *
 * <p><b>要防的事</b>：「这个租户能不能执行」在运行期有两处读点 ——
 * 执行票证闸门读 {@code platform.tenant.lifecycle_state}，
 * worker 执行前探针读 {@code public.tenant.status}。后者本是前者的<b>同事务镜像</b>
 * （{@code JdbcPlatformTenantRepository.provisionTenant:150} 建行、
 * {@code transitionLifecycle:209} 迁移时两张表一起写），
 * 但两表之间<b>没有外键、没有触发器</b>，恒等只靠「大家都走 provisionTenant」这一条约定。
 * 镜像那一步还是 best-effort（业务行不存在时 {@code UPDATE} 影响 0 行、静默通过）。
 *
 * <p>2026-10-29 实测到的真实后果：种子用裸 {@code INSERT INTO public.tenant} 造租户，
 * 平台侧无登记 → worker 照常执行、取票证得 404 {@code TENANT_NOT_FOUND}，
 * <b>症状指向票证，根因在夹具</b>。这类漂移不会报错，只会让人查错方向，所以要主动巡检。
 *
 * <p>三条不变式（任一破坏即退出码 1）：
 * <ol>
 *   <li>业务侧有 → 平台侧必须有（否则取不到票证）；</li>
 *   <li>平台侧有 → 业务侧必须有（否则 worker 探针读空、Agent 无处挂载）；</li>
 *   <li>三列状态必须相等：{@code public.tenant.status}
 *       = {@code platform.tenant.lifecycle_state} = {@code platform.tenant.status}
 *       （控制台「停用租户」靠的就是这条；不相等则停用**不生效**）。</li>
 * </ol>
 * 另报一条不致命的：{@code platform.tenant} 有行但缺 {@code tenant_quota}
 * （建租户流程一定写配额，缺行说明是手工补录的）—— 计入退出码，因为配额缺失会让
 * 限额判断读到 null。
 *
 * <p><b>2026-11-14 扩到「五处齐」</b>（E2E 二次评估 P0）：此前只查三处登记
 * （platform.tenant / public.tenant / tenant_quota），漏了执行链真正需要的两张
 * FORCE RLS 表 —— 实测「新租户创建 Run 返回 201，随后静默 FAILED」：
 * <ul>
 *   <li>缺 {@code budget_account} ⇒ 模型预留 {@code NO_ACCOUNT} ⇒ {@code BUDGET_EXHAUSTED}；</li>
 *   <li>缺 {@code cap_authz_version(tenant_id,'worker')} ⇒ 六处
 *       {@code require()} fail-closed ⇒ {@code AUTHZ_UNAVAILABLE}。</li>
 * </ul>
 * 这两项改为巡检项后，「开通链漏写」不再要等 Run 跑失败才倒查。
 *
 * <p><b>连接身份必须是库 owner</b>（{@code platform.env} 的
 * {@code SPRING_DATASOURCE_USERNAME}）：{@code public.tenant} 开了 RLS，
 * 用 {@code app_agent} 连只会看见自己那一个租户，巡检结论直接失真
 * （owner {@code ai_agent} 带 BYPASSRLS，才是全量视图）。
 *
 * <p>用法（单文件源码启动，只需 pgjdbc 在 classpath）：
 * <pre>
 *   CHECK_DB_URL=... CHECK_DB_USER=... CHECK_DB_PASSWORD=... \
 *   java -cp "$PGJAR" scripts/tools/CheckTenantRegistry.java
 * </pre>
 * 退出码：0 一致 / 1 有漂移 / 2 连不上或权限不足。
 */
public final class CheckTenantRegistry {

    /** 每条检查的标签与 SQL；SQL 只读，返回违规行（无违规则 0 行）。 */
    private static final String[][] CHECKS = {
        {"① 业务侧有、平台侧无登记（取票证必 404 TENANT_NOT_FOUND）",
            "SELECT p.tenant_id, p.slug, p.status "
                + "FROM public.tenant p LEFT JOIN platform.tenant t ON t.id = p.tenant_id "
                + "WHERE t.id IS NULL ORDER BY p.slug"},
        {"② 平台侧有、业务侧无镜像行（worker 探针读空，Agent 无处挂载）",
            "SELECT t.id, t.slug, t.lifecycle_state "
                + "FROM platform.tenant t LEFT JOIN public.tenant p ON p.tenant_id = t.id "
                + "WHERE p.tenant_id IS NULL ORDER BY t.slug"},
        {"③ 三列状态不相等（镜像漂移；「停用租户」不会生效）",
            "SELECT p.tenant_id || ' ' || p.slug, "
                + "       'public=' || coalesce(p.status, '(无行)') || "
                + "       ' platform.lifecycle=' || coalesce(t.lifecycle_state, '(无行)') || "
                + "       ' platform.status=' || coalesce(t.status, '(无行)') "
                + "FROM public.tenant p JOIN platform.tenant t ON t.id = p.tenant_id "
                + "WHERE p.status IS DISTINCT FROM t.lifecycle_state "
                + "   OR t.status IS DISTINCT FROM t.lifecycle_state "
                + "ORDER BY p.slug"},
        {"④ 平台侧登记了但没有配额行（不是走 provisionTenant 开通的）",
            "SELECT t.id, t.slug "
                + "FROM platform.tenant t LEFT JOIN platform.tenant_quota q "
                + "  ON q.tenant_id = t.id WHERE q.tenant_id IS NULL ORDER BY t.slug"},
        // ⑤⑥ 不走裸 JOIN：budget_account / cap_authz_version 都是 FORCE RLS 表，
        // 用 app_agent（NOBYPASSRLS）连、不绑 GUC 会**静默 0 行**，
        // 于是「明明有账户」会被报成「所有租户都缺」—— 假红比不查更糟。
        // 故在 reportRuntimePrerequisites 里逐租户 set_config 绑定后再查。
    };

    private CheckTenantRegistry() {
    }

    public static void main(String[] args) throws Exception {
        String url = env("CHECK_DB_URL");
        String user = env("CHECK_DB_USER");
        String password = env("CHECK_DB_PASSWORD");

        int violations = 0;
        try (Connection conn = DriverManager.getConnection(url, user, password)) {
            conn.setReadOnly(true);
            conn.setAutoCommit(false);
            for (String[] check : CHECKS) {
                int rows = report(conn, check[0], check[1]);
                violations += rows;
            }
            violations += reportRuntimePrerequisites(conn);
            conn.rollback();
        } catch (SQLException e) {
            System.out.println("❌ 巡检未能完成：" + e.getMessage());
            System.out.println("   最常见原因：连接身份不是库 owner，platform schema 读不到，"
                    + "或 RLS 把可见租户收窄到 1 行。");
            System.exit(2);
        }

        System.out.println();
        if (violations == 0) {
            System.out.println("✓ 五处齐（6 项检查、0 违规）。");
        } else {
            System.out.println("✗ 发现 " + violations + " 行漂移。");
            System.out.println("  补治理侧登记（默认 DRY-RUN，--apply 才写）："
                    + "python3 scripts/provision_tenant_runtime.py "
                    + "--register-platform --all --slug-prefix <前缀> [--apply]");
            System.out.println("  补运行前置（幂等，--all 可批量）："
                    + "python3 scripts/provision_tenant_runtime.py --slug <slug> | --all");
            System.out.println("  根治：开通链必须同时产出 platform.tenant + tenant_quota "
                    + "+ budget_account + cap_authz_version(worker)，"
                    + "见 TenantRuntimeDefaults.insertRuntimePrerequisites。");
        }
        System.exit(violations == 0 ? 0 : 1);
    }

    /**
     * ⑤⑥ 逐租户绑定 GUC 后检查两张 FORCE RLS 表。
     *
     * <p>{@code set_config('app.tenant_id', ?, true)} 是事务局部的，
     * 必须在业务 SQL 之前执行；连接只读 + 结尾 rollback，不留任何会话状态。
     */
    private static int reportRuntimePrerequisites(Connection conn) throws SQLException {
        List<String[]> tenants = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT tenant_id::text, slug FROM public.tenant ORDER BY slug")) {
            while (rs.next()) {
                tenants.add(new String[] {rs.getString(1), rs.getString(2)});
            }
        }

        int missingBudget = 0;
        int missingWorker = 0;
        StringBuilder budgetOut = new StringBuilder();
        StringBuilder workerOut = new StringBuilder();
        for (String[] tenant : tenants) {
            try (var bind = conn.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
                bind.setString(1, tenant[0]);
                bind.executeQuery().close();
            }
            if (countForTenant(conn,
                    "SELECT count(*) FROM public.budget_account WHERE tenant_id = ?::uuid",
                    tenant[0]) == 0) {
                missingBudget++;
                budgetOut.append("      - ").append(tenant[0]).append(" | ")
                        .append(tenant[1]).append('\n');
            }
            if (countForTenant(conn,
                    "SELECT count(*) FROM public.cap_authz_version "
                            + "WHERE tenant_id = ?::uuid AND principal_id = 'worker'",
                    tenant[0]) == 0) {
                missingWorker++;
                workerOut.append("      - ").append(tenant[0]).append(" | ")
                        .append(tenant[1]).append('\n');
            }
        }

        System.out.println(missingBudget == 0
                ? "  ✓ ⑤ 业务侧租户缺 budget_account：0 行"
                : "  ✗ ⑤ 业务侧租户缺 budget_account（Run 静默 BUDGET_EXHAUSTED，"
                        + missingBudget + " 行）\n" + budgetOut);
        System.out.println(missingWorker == 0
                ? "  ✓ ⑥ 业务侧租户缺 worker 授权版本行：0 行"
                : "  ✗ ⑥ 业务侧租户缺 worker 授权版本行（Run 静默 AUTHZ_UNAVAILABLE，"
                        + missingWorker + " 行）\n" + workerOut);
        return missingBudget + missingWorker;
    }

    private static long countForTenant(Connection conn, String sql, String tenantId)
            throws SQLException {
        try (var ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    private static int report(Connection conn, String label, String sql) throws SQLException {
        StringBuilder out = new StringBuilder();
        int rows = 0;
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                rows++;
                out.append("      - ");
                int columns = rs.getMetaData().getColumnCount();
                for (int i = 1; i <= columns; i++) {
                    out.append(i > 1 ? " | " : "").append(rs.getString(i));
                }
                out.append('\n');
            }
        }
        if (rows == 0) {
            System.out.println("  ✓ " + label.split("（")[0] + "：0 行");
        } else {
            System.out.println("  ✗ " + label + "（" + rows + " 行）");
            System.out.print(out);
        }
        return rows;
    }

    private static String env(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            System.err.println(name + " 未设置");
            System.exit(2);
        }
        return value;
    }
}
