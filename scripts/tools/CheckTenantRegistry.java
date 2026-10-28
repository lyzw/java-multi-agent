import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

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
            conn.rollback();
        } catch (SQLException e) {
            System.out.println("❌ 巡检未能完成：" + e.getMessage());
            System.out.println("   最常见原因：连接身份不是库 owner，platform schema 读不到，"
                    + "或 RLS 把可见租户收窄到 1 行。");
            System.exit(2);
        }

        System.out.println();
        if (violations == 0) {
            System.out.println("✓ 两份租户登记一致（4 项检查、0 违规）。");
        } else {
            System.out.println("✗ 发现 " + violations + " 行漂移。");
            System.out.println("  补登记（幂等，默认 dry-run）："
                    + "deploy/sql/platform/patch_platform_tenant_registration.sql");
            System.out.println("  根治：夹具造租户必须同时产出 platform.tenant + tenant_quota，"
                    + "见 02_platform_seed.sql 的 platform.tenant 段。");
        }
        System.exit(violations == 0 ? 0 : 1);
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
