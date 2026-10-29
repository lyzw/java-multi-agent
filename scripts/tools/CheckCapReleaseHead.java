import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 能力目录「发布头 + 当前指针」一致性巡检（带外、只读）。
 *
 * <p><b>要防的事</b>：定义表上的 {@code lifecycle_state='PUBLISHED'}
 * <b>不等于运行期可用</b>。运行期解析精确版本只有一个口径：
 * {@code cap_config_pointer} → {@code cap_config_release.object_versions} →
 * {@code id@精确版本}（{@code JdbcPublishedVersionPort.resolveFromPointer}），
 * <b>没有任何一条 SQL 读 lifecycle_state</b>。
 *
 * <p>2026-10-29 实测到的真实后果：种子只灌定义表、从不产出发布头，
 * 开发库里 PLATFORM 头只有 2 个对象（一条为跑 E2E 手写的行）、TENANT 指针一条都没有，
 * 而目录里有 14 个已发布对象。于是除 {@code e2e-minimax} 之外的每个 Agent
 * 在双写打开后都撞上「对象当前无已发布版本」→ 闭包算不出 → 该 Run 没有能力快照
 * → 执行准入判 {@code CONFIG_UNAVAILABLE}。
 * <b>症状在配置读取，根因在发布链</b> —— 而且「空库复放 0 错误」和「结构等价比对」
 * 两道校验都拦不住它：它们比对的是<b>一致性</b>，不是<b>可执行性</b>。
 *
 * <p>五条不变式（任一破坏即退出码 1）：
 * <ol>
 *   <li>PLATFORM 指针存在，且目录里每个已发布平台对象都在它指向的发布头中；</li>
 *   <li>有已发布 Agent 的租户必须有 TENANT 指针；</li>
 *   <li>该租户每个已发布 Agent（取最新版本）都在其 TENANT 头中；</li>
 *   <li>Agent 的传递依赖（enabled_skills → declared_tools / declared_mcp、
 *       子 Agent 引用）都能在<b>当前头</b>解析到精确版本 —— 这条直接对应闭包能否算出；</li>
 *   <li>序列 {@code cap_config_publish_seq} 不低于已有发布头的最大序号
 *       （低于则下次 {@code nextval()} 必撞 unique 约束；手写发布头不走序列，实测撞到）。</li>
 * </ol>
 *
 * <p><b>连接身份必须能读 public 全部 cap_* 表</b>（{@code platform.env} 的
 * {@code SPRING_DATASOURCE_USERNAME}，owner 带 BYPASSRLS）；RLS 会把可见行收窄，
 * 巡检结论随之失真。
 *
 * <p>修复通道（幂等，按 {@code aggregate_fingerprint} 判等）：
 * {@code deploy/sql/platform/patch_cap_release_head.sql}。
 * 退出码：0 一致 / 1 有漂移 / 2 连不上或权限不足。
 */
public final class CheckCapReleaseHead {

    /** 当前 PLATFORM 发布头里的对象集合（指针缺失时为空集 → 第①条全量报违规）。 */
    private static final String PLATFORM_HEAD =
            "WITH head AS (SELECT jsonb_array_elements(r.object_versions) e"
                    + " FROM public.cap_config_pointer p"
                    + " JOIN public.cap_config_release r ON r.release_id = p.current_release_id"
                    + " WHERE p.pointer_key = 'PLATFORM')";

    /** 目录里当前已发布的平台对象（与 patch 同一推导口径：每 id 取 max(version)）。 */
    private static final String PLATFORM_CATALOG =
            "  SELECT 'mcp_server' AS t, id, max(version) AS v"
                    + "    FROM public.cap_mcp_server_def"
                    + "   WHERE lifecycle_state = 'PUBLISHED' GROUP BY id"
                    + "  UNION ALL"
                    + "  SELECT 'skill', id, max(version)"
                    + "    FROM public.cap_skill_def"
                    + "   WHERE lifecycle_state = 'PUBLISHED' GROUP BY id"
                    + "  UNION ALL"
                    + "  SELECT 'tool', id, max(version)"
                    + "    FROM public.cap_tool_def"
                    + "   WHERE lifecycle_state = 'PUBLISHED' GROUP BY id";

    /** 每个租户的 TENANT 头对象集合（含租户 id）。 */
    private static final String TENANT_HEAD =
            "WITH th AS (SELECT split_part(p.pointer_key, ':', 2) AS tid,"
                    + " jsonb_array_elements(r.object_versions) e"
                    + " FROM public.cap_config_pointer p"
                    + " JOIN public.cap_config_release r ON r.release_id = p.current_release_id"
                    + " WHERE p.scope = 'TENANT')";

    private static final String[][] CHECKS = {
        {"① 目录已发布对象不在当前 PLATFORM 发布头中（闭包必报 RESOURCE_UNKNOWN）",
            PLATFORM_HEAD + ", dir AS (" + PLATFORM_CATALOG + ")"
                    + " SELECT d.t, d.id, d.v FROM dir d LEFT JOIN head h"
                    + "   ON h.e->>'type' = d.t AND h.e->>'id' = d.id"
                    + " WHERE h.e IS NULL ORDER BY 1, 2"},
        {"② 有已发布 Agent 的租户没有 TENANT 指针（resolveCurrent 恒失败）",
            "SELECT DISTINCT t.tenant_id::text FROM public.cap_agent_def t"
                    + " WHERE t.lifecycle_state = 'PUBLISHED'"
                    + "   AND NOT EXISTS (SELECT 1 FROM public.cap_config_pointer p"
                    + "     WHERE p.pointer_key = 'TENANT:' || t.tenant_id::text)"
                    + " ORDER BY 1"},
        {"③ 租户已发布 Agent 不在其 TENANT 发布头中",
            TENANT_HEAD + ", latest AS (SELECT tenant_id, agent_id, max(version) AS v"
                    + "   FROM public.cap_agent_def WHERE lifecycle_state = 'PUBLISHED'"
                    + "  GROUP BY tenant_id, agent_id)"
                    + " SELECT l.tenant_id::text, d.name, l.v,"
                    + "   CASE WHEN x.e IS NULL THEN '不在头中/无指针' ELSE '' END"
                    + " FROM latest l JOIN public.cap_agent_def d"
                    + "   ON d.agent_id = l.agent_id AND d.version = l.v"
                    + "   AND d.tenant_id = l.tenant_id AND d.lifecycle_state = 'PUBLISHED'"
                    + " LEFT JOIN th x ON x.tid = l.tenant_id::text"
                    + "  AND x.e->>'type' = 'agent' AND x.e->>'id' = d.agent_id::text"
                    + " WHERE x.e IS NULL ORDER BY 1, 2"},
        {"④ Agent 的传递依赖无法在当前头解析（= 该 Agent 现在跑不了）",
            PLATFORM_HEAD + ", th AS (SELECT p.pointer_key, jsonb_array_elements(r.object_versions) e"
                    + "   FROM public.cap_config_pointer p"
                    + "   JOIN public.cap_config_release r ON r.release_id = p.current_release_id"
                    + "   WHERE p.scope = 'TENANT'),"
                    + " need AS (SELECT a.name AS agent, 'skill' AS t,"
                    + "     jsonb_array_elements_text(a.enabled_skills) AS i"
                    + "     FROM public.cap_agent_def a WHERE a.lifecycle_state = 'PUBLISHED'"
                    + "   UNION ALL"
                    + "   SELECT a.name, 'agent', c.child_agent_id::text"
                    + "     FROM public.cap_agent_def a"
                    + "     JOIN public.cap_agent_child_ref c"
                    + "       ON c.tenant_id = a.tenant_id AND c.parent_agent_id = a.agent_id"
                    + "      AND c.parent_release_version = a.version"
                    + "     WHERE a.lifecycle_state = 'PUBLISHED'"
                    + "   UNION ALL"
                    + "   SELECT a.name, 'tool', jsonb_array_elements_text(s.declared_tools)"
                    + "     FROM public.cap_agent_def a"
                    + "     JOIN public.cap_skill_def s"
                    + "       ON s.id IN (SELECT jsonb_array_elements_text(a.enabled_skills))"
                    + "     WHERE a.lifecycle_state = 'PUBLISHED'"
                    + "       AND s.lifecycle_state = 'PUBLISHED'"
                    + "   UNION ALL"
                    + "   SELECT a.name, 'mcp_server', m.x->>'serverId'"
                    + "     FROM public.cap_agent_def a"
                    + "     JOIN public.cap_skill_def s"
                    + "       ON s.id IN (SELECT jsonb_array_elements_text(a.enabled_skills))"
                    + "     CROSS JOIN LATERAL jsonb_array_elements(s.declared_mcp) m(x)"
                    + "     WHERE a.lifecycle_state = 'PUBLISHED'"
                    + "       AND s.lifecycle_state = 'PUBLISHED' AND s.declared_mcp <> '[]'::jsonb),"
                    + " tenant_of AS (SELECT DISTINCT ON (name) name, tenant_id::text AS tid"
                    + "     FROM public.cap_agent_def WHERE lifecycle_state = 'PUBLISHED'"
                    + "     ORDER BY name, version DESC)"
                    + " SELECT n.agent, n.t, n.i FROM need n"
                    + " JOIN tenant_of o ON o.name = n.agent"
                    + " LEFT JOIN head h ON h.e->>'type' = n.t AND h.e->>'id' = n.i"
                    + " LEFT JOIN th x ON x.pointer_key = 'TENANT:' || o.tid"
                    + "  AND x.e->>'type' = n.t AND x.e->>'id' = n.i"
                    + " WHERE n.i IS NOT NULL AND h.e IS NULL AND x.e IS NULL"
                    + " ORDER BY 1, 2, 3"},
        {"⑤ 发布序号序列落后于已有发布头行（下次 nextval 必撞 unique）",
            "SELECT 'last_value=' || s.last_value || '，但已有发布头最大 seq=' || m.mx"
                    + " FROM (SELECT last_value FROM public.cap_config_publish_seq) s,"
                    + " (SELECT coalesce(max(config_publish_seq), 0) AS mx"
                    + "    FROM public.cap_config_release) m"
                    + " WHERE s.last_value < m.mx"},
    };

    private CheckCapReleaseHead() {
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
                violations += report(conn, check[0], check[1]);
            }
            conn.rollback();
        } catch (SQLException e) {
            System.out.println("❌ 巡检未能完成：" + e.getMessage());
            System.out.println("   最常见原因：连接身份读不到 cap_config_pointer / cap_config_release，"
                    + "或 RLS 把可见行收窄。");
            System.exit(2);
        }

        System.out.println();
        if (violations == 0) {
            System.out.println("✓ 发布头覆盖目录，全部 Agent 的传递依赖可解析（5 项检查、0 违规）。");
        } else {
            System.out.println("✗ 发现 " + violations + " 行漂移。");
            System.out.println("  补齐（幂等，默认按 aggregate_fingerprint 判等）："
                    + "deploy/sql/platform/patch_cap_release_head.sql");
            System.out.println("  ⚠️ 必须用库 owner 执行：platform_ops 缺序列 UPDATE 权限，"
                    + "nextval() 会报 permission denied for sequence。");
            System.out.println("  根治：种子复放即产出发布头 + 指针，"
                    + "见 02_platform_seed.sql 末尾的附录。");
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
