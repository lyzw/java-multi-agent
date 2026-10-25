package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 多级 Agent 三张表的<b>实际库结构</b>断言（多级 Agent v1.1 · 第二十七批 · A8）。
 *
 * <p><b>⚠️ 与 {@code MultiAgentSchemaStructureTest} 是两层，不是一层</b>：
 * <ul>
 *   <li>构建期那一层读<b>迁移脚本</b>，断言「脚本说的结构」；</li>
 *   <li>本类读<b>真实 PG 的 catalog</b>，断言「库里长出来的结构」。</li>
 * </ul>
 *
 * <p>两层缺一不可：只测脚本，测不到「脚本写了但迁移没跑到那一步」
 * （例如 V26 的回填在 FORCE RLS 下静默影响 0 行 ——
 * 表现是「迁移报成功，状态全是 DRAFT」，最难查的一类假成功）；
 * 只测库，测不到「脚本被改坏但已部署环境察觉不到」。
 *
 * <p><b>⚠️ 本类的断言全部走 {@code pg_catalog}，不靠「写入试试看」</b>：
 * 写入试探法只能证明「某个行为成立」，证不了「约束/触发器/RLS 的<b>声明</b>还在」。
 */
class MultiAgentSchemaIT extends CapITBase {

    /** 三张被门禁覆盖的表。 */
    private static final List<String> GUARDED_TABLES =
            List.of("agent_invocation", "cap_agent_child_ref", "cap_result_contract");

    /** 覆盖这三张表的迁移脚本（按版本顺序）。 */
    private static final List<String> RELEVANT_MIGRATIONS = List.of(
            "V19__multi_agent_orchestration.sql",
            "V20__result_contract.sql",
            "V23__result_contract_text_id.sql",
            "V25__agent_invocation_requested_target.sql",
            "V26__contract_policy_lifecycle_state.sql");

    // ------------------------------------------------------------------
    // 1. 表与列的实际类型
    // ------------------------------------------------------------------

    @Test
    @DisplayName("⚠️ 库里的 cap_result_contract.contract_id 必须是 text（V23 修过的真实缺陷）")
    void contractIdIsTextInDatabase() {
        String type = columnType("cap_result_contract", "contract_id");
        assertThat(type)
                .as("契约 id 必须是可读字符串（text）。"
                        + "曾是 uuid —— 非 UUID 形式的契约在发布链路整笔回滚、"
                        + "运行期查询恒抛异常 → 委派恒 fail-closed（V23 已修）")
                .isEqualTo("text");
    }

    @Test
    @DisplayName("⚠️ 三张表的关键列在库里都存在，且类型与脚本一致")
    void keyColumnsExistInDatabase() {
        assertThat(columnType("agent_invocation", "invocation_id")).isEqualTo("uuid");
        assertThat(columnType("agent_invocation", "status")).isEqualTo("text");
        assertThat(columnType("agent_invocation", "depth")).isEqualTo("integer");
        assertThat(columnType("agent_invocation", "fencing_token")).isEqualTo("bigint");
        assertThat(columnType("agent_invocation", "requested_target_agent_id"))
                .as("第二十三批 A3 的「模型原始候选」列必须在库里")
                .isEqualTo("uuid");

        assertThat(columnType("cap_agent_child_ref", "parent_release_version"))
                .isEqualTo("integer");
        assertThat(columnType("cap_agent_child_ref", "child_release_version"))
                .isEqualTo("integer");
        assertThat(columnType("cap_agent_child_ref", "ordinal")).isEqualTo("integer");
        assertThat(columnType("cap_agent_child_ref", "required")).isEqualTo("boolean");

        assertThat(columnType("cap_result_contract", "version")).isEqualTo("integer");
        assertThat(columnType("cap_result_contract", "schema_json")).isEqualTo("jsonb");
        assertThat(columnType("cap_result_contract", "lifecycle_state"))
                .as("第二十五批 A5 的 lifecycle_state 列必须在库里")
                .isEqualTo("cap_lifecycle_state");
    }

    // ------------------------------------------------------------------
    // 2. 约束的实际状态
    // ------------------------------------------------------------------

    @Test
    @DisplayName("⚠️ 库里的状态 CHECK 约束必须仍然是封闭的七态")
    void statusCheckIsStillClosedInDatabase() {
        String expr = checkExpression("agent_invocation", "agent_invocation_status_ck");
        assertThat(expr).as("状态 CHECK 约束缺失").isNotNull();
        assertThat(expr)
                .as("状态集合必须恰好是七态")
                .contains("'QUEUED'", "'RUNNING'", "'SUCCEEDED'", "'FAILED'",
                        "'CANCELLED'", "'TIMED_OUT'", "'UNKNOWN'");
    }

    @Test
    @DisplayName("⚠️ 库里的主键必须以 tenant_id 打头")
    void primaryKeysAreTenantScopedInDatabase() {
        for (String table : GUARDED_TABLES) {
            List<String> cols = primaryKeyColumns(table);
            assertThat(cols)
                    .as("%s 的主键必须包含 tenant_id", table)
                    .isNotEmpty()
                    .startsWith("tenant_id");
        }
        assertThat(primaryKeyColumns("agent_invocation"))
                .containsExactly("tenant_id", "invocation_id");
    }

    @Test
    @DisplayName("⚠️ 库里的 cap_agent_child_ref 必须有两条指向 cap_agent_def 的复合外键")
    void childRefForeignKeysExistInDatabase() {
        List<String> fkTargets = foreignKeyTargets("cap_agent_child_ref");
        assertThat(fkTargets)
                .as("父子两侧都必须有外键")
                .hasSize(2)
                .allMatch(t -> "cap_agent_def".equals(t));

        List<String> fkColumns = foreignKeyColumns("cap_agent_child_ref");
        assertThat(fkColumns)
                .as("复合外键必须以 tenant_id 打头（顺带保证租户一致）")
                .allMatch(c -> "tenant_id".equals(c));
    }

    // ------------------------------------------------------------------
    // 3. RLS 的实际状态
    // ------------------------------------------------------------------

    @Test
    @DisplayName("⚠️ 三张表在库里都必须 RLS enabled **且 FORCE**")
    void allThreeTablesAreRlsForcedInDatabase() {
        for (String table : GUARDED_TABLES) {
            // ⚠️ 布尔列必须 ::int —— 驱动不允许把 boolean 直接读成 long。
            assertThat(adminQueryLong("SELECT relrowsecurity::int FROM pg_class"
                    + " WHERE relname = '" + table + "'"
                    + " AND relnamespace = current_schema()::regnamespace"))
                    .as("%s 必须 ENABLE ROW LEVEL SECURITY（未启用 = 跨租户泄漏）", table)
                    .isEqualTo(1L);
            assertThat(adminQueryLong("SELECT relforcerowsecurity::int FROM pg_class"
                    + " WHERE relname = '" + table + "'"
                    + " AND relnamespace = current_schema()::regnamespace"))
                    .as("%s 必须 FORCE ROW LEVEL SECURITY（未 FORCE = 表 owner 仍能跨租户读）",
                            table)
                    .isEqualTo(1L);
        }
    }

    @Test
    @DisplayName("⚠️ 三张表的 RLS 策略必须读写双向收口")
    void rlsPoliciesGuardReadsAndWritesInDatabase() {
        for (String table : GUARDED_TABLES) {
            List<String> qual = adminQueryList("SELECT coalesce(qual, '') FROM pg_policies"
                    + " WHERE schemaname = current_schema() AND tablename = '" + table + "'");
            List<String> withCheck = adminQueryList(
                    "SELECT coalesce(with_check, '') FROM pg_policies"
                            + " WHERE schemaname = current_schema() AND tablename = '"
                            + table + "'");
            assertThat(qual)
                    .as("%s 的策略必须约束读（USING）", table)
                    .isNotEmpty()
                    .allMatch(q -> q.contains("tenant_id = current_tenant_id()"));
            assertThat(withCheck)
                    .as("%s 的策略必须约束写（WITH CHECK）—— 只挡读等于没隔离写路径", table)
                    .isNotEmpty()
                    .allMatch(q -> q.contains("tenant_id = current_tenant_id()"));
        }
    }

    // ------------------------------------------------------------------
    // 4. 触发器的实际状态
    // ------------------------------------------------------------------

    @Test
    @DisplayName("⚠️ cap_result_contract 在库里必须挂着不可变与对齐两个触发器")
    void contractTriggersExistInDatabase() {
        Set<String> triggers = triggerNames("cap_result_contract");

        assertThat(triggers)
                .as("已发布契约行不可变（V26 补挂）")
                .contains("trg_cap_result_contract_immutable");
        assertThat(triggers)
                .as("published_at ⇄ lifecycle_state 对齐（V26 补挂）")
                .contains("trg_cap_result_contract_align_state");
    }

    // ------------------------------------------------------------------
    // 5. 迁移脚本可重放（幂等）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("⚠️ 覆盖三张表的迁移脚本必须可**重复执行**而不报错")
    void migrationsAreReplayable() {
        for (String migration : RELEVANT_MIGRATIONS) {
            String sql = readMigration(migration);
            assertThatCode(() -> replay(sql))
                    .as("迁移脚本 %s 重复执行时报错 → 新环境重放会失败", migration)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("⚠️ 重放迁移脚本后，三张表的结构必须**不变**")
    void replayDoesNotAlterStructure() {
        String before = structureFingerprint();
        for (String migration : RELEVANT_MIGRATIONS) {
            replay(readMigration(migration));
        }
        assertThat(structureFingerprint())
                .as("重放迁移改变了三张表的结构 → 脚本不是幂等的")
                .isEqualTo(before);
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /**
     * 在一个<b>事务</b>里执行迁移脚本，然后回滚。
     *
     * <p>⚠️ <b>必须回滚</b>：PostgreSQL 的 DDL 是事务性的，
     * 所以重放既验证了「能执行」，又不会把共享的 IT 库留在被改动的状态。
     * 万一脚本执行到一半报错，回滚同样把现场复原。
     */
    private static void replay(String sql) {
        try (Connection c = adminPool.getConnection()) {
            boolean autoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute(sql);
            } finally {
                c.rollback();
                c.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("重放迁移脚本失败: " + e.getMessage(), e);
        }
    }

    /** 三张表的结构指纹（列 + 约束 + 触发器 + 索引 + RLS），用于「重放前后是否变化」。 */
    private String structureFingerprint() {
        StringBuilder sb = new StringBuilder();
        for (String table : GUARDED_TABLES) {
            sb.append(table).append("|").append(adminQueryList(
                    "SELECT a.attname || ':' || format_type(a.atttypid, a.atttypmod)"
                            + " || ':' || a.attnotnull"
                            + " FROM pg_attribute a WHERE a.attrelid = '" + table + "'::regclass"
                            + " AND a.attnum > 0 AND NOT a.attisdropped ORDER BY a.attname"))
                    .append("|");
            sb.append(adminQueryList(
                    "SELECT conname FROM pg_constraint WHERE conrelid = '" + table
                            + "'::regclass ORDER BY conname")).append("|");
            sb.append(triggerNames(table)).append("|");
            sb.append(adminQueryList(
                    "SELECT indexname FROM pg_indexes WHERE tablename = '"
                            + table + "' ORDER BY indexname")).append("|");
            sb.append(adminQueryLong("SELECT relrowsecurity::int + relforcerowsecurity::int"
                    + " FROM pg_class WHERE relname = '" + table + "'"
                    + " AND relnamespace = current_schema()::regnamespace"));
        }
        return sb.toString();
    }

    private static Set<String> triggerNames(String table) {
        return new LinkedHashSet<>(adminQueryList(
                "SELECT tgname FROM pg_trigger WHERE tgrelid = '" + table
                        + "'::regclass AND NOT tgisinternal ORDER BY tgname"));
    }

    private static String columnType(String table, String column) {
        // ⚠️ 用 format_type 而不是 information_schema：
        //    · data_type 对枚举返回 'USER-DEFINED' —— 那是个**分类**不是类型名；
        //    · udt_name 返回内部名（int4 / int8 / bpchar）—— 不是人能读的 SQL 类型名。
        //    format_type 给的是规范写法：integer / bigint / uuid / cap_lifecycle_state。
        List<String> rows = adminQueryList(
                "SELECT format_type(a.atttypid, a.atttypmod) FROM pg_attribute a"
                        + " WHERE a.attrelid = '" + table + "'::regclass"
                        + " AND a.attname = '" + column + "'"
                        + " AND a.attnum > 0 AND NOT a.attisdropped");
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static String checkExpression(String table, String constraint) {
        List<String> rows = adminQueryList("SELECT pg_get_constraintdef(oid) FROM pg_constraint"
                + " WHERE conrelid = '" + table + "'::regclass AND conname = '" + constraint + "'");
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static List<String> primaryKeyColumns(String table) {
        List<String> rows = adminQueryList(
                "SELECT a.attname FROM pg_index i"
                        + " JOIN pg_attribute a ON a.attrelid = i.indrelid"
                        + " AND a.attnum = ANY(i.indkey)"
                        + " WHERE i.indrelid = '" + table + "'::regclass AND i.indisprimary"
                        + " ORDER BY array_position(i.indkey, a.attnum)");
        return rows;
    }

    private static List<String> foreignKeyTargets(String table) {
        return adminQueryList("SELECT c.confrelid::regclass::text FROM pg_constraint c"
                + " WHERE c.conrelid = '" + table + "'::regclass AND c.contype = 'f'");
    }

    private static List<String> foreignKeyColumns(String table) {
        // ⚠️ 取每条外键的<b>第一列</b>：复合外键的第一列必须是 tenant_id。
        List<String> rows = adminQueryList(
                "SELECT a.attname FROM pg_constraint c"
                        + " JOIN pg_attribute a ON a.attrelid = c.conrelid"
                        + " AND a.attnum = c.conkey[1]"
                        + " WHERE c.conrelid = '" + table + "'::regclass AND c.contype = 'f'"
                        + " ORDER BY c.conname");
        return rows;
    }

    /** 读取迁移脚本原文（classpath 上：{@code db/migration/<name>}）。 */
    private static String readMigration(String name) {
        String path = "db/migration/" + name;
        try (InputStream in = MultiAgentSchemaIT.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(in).as("迁移脚本不在 classpath 上: %s", path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取迁移脚本失败: " + path, e);
        }
    }

    /** 查一列 SQL，返回所有结果行（每行一个字符串）。 */
    private static List<String> adminQueryList(String sql) {
        List<String> out = new ArrayList<>();
        try (Connection c = adminPool.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询失败: " + sql, e);
        }
        return out;
    }
}
