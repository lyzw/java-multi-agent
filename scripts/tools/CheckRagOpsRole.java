import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 平台全局语料运维通道（{@code rag_ops}）能力面巡检（带外、只读）。
 *
 * <p><b>为什么要有这个巡检</b>：{@code deploy/sql/rag/05_rag_ops_role.sql} 把
 * 「全局桶能写」这件事交给一个带 {@code BYPASSRLS} 的角色，代价写得很清楚
 * ——数据库层不再限制它写哪个桶，限长全靠应用侧三道结构约束。
 * 一个「靠应用侧兜底」的角色，最需要的不是再讲一遍道理，
 * 而是一条能挂 CI / 运维 cron 的<b>退出码</b>：授权面一旦漂宽
 * （多给了 DELETE、多了成员关系、或被塞进主数据源），当场红。
 *
 * <p><b>⚠️ 连接身份必须是运维角色本身</b>（不是 owner）：
 * 所有断言都走 {@code has_table_privilege(current_user, ...)} 与
 * {@code pg_roles WHERE rolname = current_user}，换身份就变成在检查别人。
 * 巡检本身**只读**（{@code setReadOnly(true)} + 结尾 {@code ROLLBACK}），
 * 一条写都不发。
 *
 * <p><b>因此这里刻意不测的事</b>：「ops 真的能写哨兵桶」。那是写动作，
 * 与只读巡检的定位冲突，由两处覆盖：
 * 容器内反证 {@code scripts/rag-corpus-scope-counterproof.sh} 的 P13 组，
 * 以及 {@code rag_ops} 真实数据源上的集成测试。
 *
 * <p>退出码：0 能力面与设计一致 / 1 漂移 / 2 连不上或不是运维身份。
 */
public final class CheckRagOpsRole {

    /** rag schema 下的普通表（视图/序列不参与权限计数）。 */
    private static final String RAG_TABLES =
            "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                    + " WHERE n.nspname = 'rag' AND c.relkind = 'r'";

    private static final String[][] CHECKS = {
        {"①连接身份不得是超级用户（超级用户会让下面的权限断言全部失真）",
            "SELECT 'is_superuser=' || current_setting('is_superuser')"
                    + " WHERE current_setting('is_superuser') <> 'off'"},
        {"②通道必须带 BYPASSRLS（没有它全局桶写不进去，G 档写是空头承诺）",
            "SELECT 'rolbypassrls=' || coalesce(rolbypassrls::text, '<角色不存在>')"
                    + " FROM pg_roles WHERE rolname = current_user"
                    + "   AND rolbypassrls IS DISTINCT FROM true"},
        // ⚠️ 写面从 6 张扩到 7 张：新增 rag_inspection_record 的 **INSERT-only**。
        //    原因：平台全局语料同样要通过「人工抽检」才能签发 VERIFIED（InspectionGuard
        //    对两个作用域一视同仁），而抽检记录是全局行，rag_app 的写策略
        //    （tenant_id = rag_current_tenant() AND scope='TENANT'）写不进去
        //    —— 实测 `new row violates row-level security policy`。只能走 ops 通道。
        //    ⚠️ 但它与另外 6 张**不是同一档授权**：抽检记录是「结论的证据」，
        //       只追加不修改，所以这里把它单列成 INSERT-only 断言（见 ③b），
        //       ③ 仍要求其余表恰好 6 张且**不含**它 —— 扩面不允许夹带 UPDATE。
        {"③写面恰好 6 张表（全局治理真正要写的，多一张都是漂移；不含只追加的抽检记录）",
            "SELECT '写面表数=' || count(*) FILTER (WHERE has_table_privilege(current_user, c.oid, 'INSERT')"
                    + "                                       OR has_table_privilege(current_user, c.oid, 'UPDATE'))"
                    + " " + RAG_TABLES
                    + "   AND c.relname <> 'rag_inspection_record'"
                    // ⚠️ FILTER 作用在 GROUP BY 之后、HAVING 之前；这里没有 GROUP BY，
                    //    故用聚合直接过滤即可（count(*) FILTER 会跳过不满足条件的行）。
                    + " HAVING count(*) FILTER (WHERE has_table_privilege(current_user, c.oid, 'INSERT')"
                    + "                           OR has_table_privilege(current_user, c.oid, 'UPDATE')) <> 6"},
        {"③b 抽检记录只追加：必须有 INSERT，且**不得**有 UPDATE / DELETE（记录是证据）",
            "SELECT '抽检记录授权=' || string_agg(p, ',' ORDER BY p)"
                    + " FROM (SELECT unnest(ARRAY['INSERT','UPDATE','DELETE','TRUNCATE']) AS p) x"
                    + " WHERE has_table_privilege(current_user, 'rag.rag_inspection_record', p)"
                    + " HAVING NOT has_table_privilege(current_user, 'rag.rag_inspection_record', 'INSERT')"
                    + "     OR has_table_privilege(current_user, 'rag.rag_inspection_record', 'UPDATE')"
                    + "     OR has_table_privilege(current_user, 'rag.rag_inspection_record', 'DELETE')"
                    + "     OR has_table_privilege(current_user, 'rag.rag_inspection_record', 'TRUNCATE')"},
        {"④删除面为 0 张表（语料治理没有删除语义：撤回是终态流转，不是删行）",
            "SELECT '删除面表数=' || count(*) FILTER (WHERE has_table_privilege(current_user, c.oid, 'DELETE'))"
                    + " " + RAG_TABLES
                    + " HAVING count(*) FILTER (WHERE has_table_privilege(current_user, c.oid, 'DELETE')) > 0"},
        {"⑤读面覆盖 rag 全部表（缺授权会让报错点落在业务代码、离真因极远）",
            "SELECT '读=' || count(*) FILTER (WHERE has_table_privilege(current_user, c.oid, 'SELECT'))"
                    + " || '/' || count(*) " + RAG_TABLES
                    // ⚠️ 空库会让「读=0/0」看起来通过：把「连表都没有」也判成违规，
                    //    否则巡检在错误的库上也能给出绿。
                    + " HAVING count(*) = 0"
                    + "     OR count(*) FILTER (WHERE has_table_privilege(current_user, c.oid, 'SELECT'))"
                    + "        <> count(*)"},
        {"⑥不得有任何角色成员关系（能力不能靠继承拿到）",
            "SELECT '继承了角色 ' || m.rolname"
                    + " FROM pg_auth_members am"
                    + " JOIN pg_roles r ON r.oid = am.member"
                    + " JOIN pg_roles m ON m.oid = am.roleid"
                    + " WHERE r.rolname = current_user"},
        {"⑦角色级默认里不得有 app.tenant_id（连接池不能把上一个持有者的作用域伪装成当前请求的）",
            "SELECT 'rolconfig=' || coalesce(array_to_string(rolconfig, ','), '<空>') "
                    + " FROM pg_roles WHERE rolname = current_user"
                    + "   AND array_to_string(rolconfig, ',') LIKE '%app.tenant_id%'"},
        {"⑧必须带 statement_timeout（一把长事务卡住全局桶要在库层就被打断）",
            "SELECT 'rolconfig=' || coalesce(array_to_string(rolconfig, ','), '<空>') "
                    + " FROM pg_roles WHERE rolname = current_user"
                    + "   AND array_to_string(rolconfig, ',') NOT LIKE '%statement_timeout=%'"},
        {"⑨必须带 idle_in_transaction_session_timeout",
            "SELECT 'rolconfig=' || coalesce(array_to_string(rolconfig, ','), '<空>') "
                    + " FROM pg_roles WHERE rolname = current_user"
                    + "   AND array_to_string(rolconfig, ',') NOT LIKE '%idle_in_transaction_session_timeout=%'"},
        {"⑩应用角色必须仍在，且仍无 BYPASSRLS（两条通道的身份不可互换）",
            "SELECT '主数据源角色不存在：' || current_setting('my.app_role')"
                    + " WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = current_setting('my.app_role'))"
                    + " UNION ALL"
                    + " SELECT current_user || ' 之外的角色仍拿到了 BYPASSRLS'"
                    + " FROM pg_roles WHERE rolname = current_setting('my.app_role')"
                    + "   AND rolbypassrls IS DISTINCT FROM false"},
        {"⑪原闸门未被挪开：rag 的写类策略（INSERT/UPDATE/DELETE）里不得出现全局哨兵",
            "SELECT tablename || ' / ' || policyname || ' / ' || cmd"
                    + " FROM pg_policies WHERE schemaname = 'rag'"
                    + "   AND cmd IN ('INSERT', 'UPDATE', 'DELETE')"
                    + "   AND (coalesce(qual, '') || coalesce(with_check, '')) LIKE '%rag_global_tenant_id%'"},
        {"⑫8 张语料表的 scope↔哨兵 CHECK 仍在（BYPASSRLS 绕不掉 CHECK，这是最后一道）",
            "SELECT '带哨兵 CHECK 的表数=' || count(DISTINCT conrelid)"
                    + " FROM pg_constraint WHERE connamespace = 'rag'::regnamespace"
                    + "   AND contype = 'c' AND pg_get_constraintdef(oid) LIKE '%rag_global_tenant_id%'"
                    + " HAVING count(DISTINCT conrelid) <> 8"},
        // ⚠️ 这条是实测踩出来的：05 早先用 format('%L') 写 search_path，
        //    角色默认值就成了**一个含逗号的标识符** "rag, public"，
        //    于是带外 psql 里所有未限定表名解析不到（无报错，只是查不到）。
        //    测法必须是行为而不是字符串：to_regclass 走当前 search_path，
        //    引号包整串时它返回 NULL，而 rolconfig 里看着「有设 search_path」。
        //    （若 DSN 自带 currentSchema，驱动会盖住角色默认值，这条就测不到——
        //     本巡检面向「带外运维连接」，DSN 不该带 schema 参数。）
        {"⑬运维会话里未限定表名解析得到（角色级 search_path 是列表语法，不是整串引号）",
            "SELECT 'search_path=' || current_setting('search_path')"
                    + " || ' → rag_source 解析不到（未加限定的名字在带外 psql 里不可用）'"
                    + " WHERE to_regclass('rag_source') IS NULL"},
    };

    private CheckRagOpsRole() {
    }

    public static void main(String[] args) throws Exception {
        String url = env("CHECK_DB_URL");
        String user = env("CHECK_DB_USER");
        String password = env("CHECK_DB_PASSWORD");
        String appRole = System.getenv().getOrDefault("RAG_APP_ROLE", "rag_app");

        int violations = 0;
        try (Connection conn = DriverManager.getConnection(url, user, password)) {
            conn.setReadOnly(true);
            conn.setAutoCommit(false);
            // ⚠️ ⑩ 需要知道应用角色的名字，但它是部署期变量：放进会话变量而不是拼进 SQL，
            //    顺带保证这条查询本身不可能被注入（角色名来自环境，仍按字面量转义）。
            try (Statement st = conn.createStatement()) {
                st.execute("SELECT set_config('my.app_role', "
                        + quoteLiteral(appRole) + ", false)");
            }
            for (String[] check : CHECKS) {
                violations += report(conn, check[0], check[1]);
            }
            conn.rollback();
        } catch (SQLException e) {
            System.out.println("❌ 巡检未能完成：" + e.getMessage());
            System.out.println("   最常见原因：连接身份不是运维角色（口令/用户名配错），"
                    + "或该库尚未执行 deploy/sql/rag/05_rag_ops_role.sql。");
            System.exit(2);
        }

        System.out.println();
        if (violations == 0) {
            System.out.println("✓ 运维通道能力面与设计一致（13 项检查、0 违规）。");
            System.out.println("  仍要记住：BYPASSRLS 意味着**跨租户读得到**（⑤ 量的就是这个），");
            System.out.println("  限长靠应用侧 GuardedRagOpsDataSource 的会话窗，本巡检测不到它。");
        } else {
            System.out.println("✗ 发现 " + violations + " 项漂移 —— 这条通道已不再是最小授权。");
            System.out.println("  修复通道：重新执行 deploy/sql/rag/05_rag_ops_role.sql"
                    + "（它先 REVOKE ALL 再 GRANT，可重复达成最小授权状态），须库 owner 执行。");
        }
        System.exit(violations == 0 ? 0 : 1);
    }

    /** SQL 字符串字面量转义（单引号翻倍）——角色名来自环境变量，不进拼接语法。 */
    private static String quoteLiteral(String raw) {
        return "'" + raw.replace("'", "''") + "'";
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
