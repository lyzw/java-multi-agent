package cn.studykid.multiagent.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 决策日志「代码 ↔ 结构 ↔ 授权」三方对齐守卫（D6 / P2）。
 *
 * <p>⚠️ 这张表有三处会各自独立漂移，且<b>任何一处漂移都不在编译期报错</b>：
 * <ol>
 *   <li><b>表不存在</b> → 运行时每次判定写失败（且失败被限频告警吞掉）；</li>
 *   <li><b>列名/列集与写入语句不一致</b> → 同上，但 {@code has_table_privilege} 看起来完全正常；</li>
 *   <li><b>没授 INSERT</b> → 同上；<b>多授了 SELECT</b> → 应用进程可枚举全平台各租户的判定记录。</li>
 * </ol>
 * 这三条的共同点是「报错点离真因极远」，也正是本项目反复记录的
 * <i>静默失效、系统表面正常</i>。故把三方写成构建期硬门禁：
 * <ul>
 *   <li>快照 {@code deploy/sql/platform/01_platform_ddl.sql} 建出该表；</li>
 *   <li>{@code JdbcAuthzDecisionLog} 的 INSERT 列集 == 快照列集（除有默认值的 {@code id}）；</li>
 *   <li>{@code 03_platform_roles.sql} 对应用角色<b>只授 INSERT 并显式 REVOKE SELECT</b>。</li>
 * </ul>
 *
 * <p>注意本类必须读<b>源码原文</b>而不是运行期反射：SQL 是文本块里的字符串，
 * 反射只能看到字段名，看不到真实写入的列。
 */
class AuthzDecisionLogSchemaTest {

    private static final String TABLE = "platform.authz_decision_log";

    private static final Path RECORDER = repoRoot()
            .resolve("platform/agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/security/JdbcAuthzDecisionLog.java");

    private static final Path GRANTS = repoRoot().resolve("deploy/sql/platform/03_platform_roles.sql");

    @Test
    @DisplayName("⛔ 快照必须建出 platform.authz_decision_log（否则开启开关后每次判定都写失败）")
    void snapshotCreatesTable() {
        assertThat(PlatformDdl.createdTables())
                .as("结构快照里没有决策日志表：AuthzShadowConfiguration 开启 decision-log 后会写不进去")
                .contains(TABLE);
    }

    @Test
    @DisplayName("⛔ 写入语句的列集必须与快照列集一致（列漂移不会在编译期报错）")
    void insertColumnsMatchSnapshot() throws IOException {
        List<String> snapshotColumns = PlatformDdl.columnNames(TABLE);
        assertThat(snapshotColumns).as("快照未解析出该表的列，本守卫在空跑").isNotEmpty();

        Set<String> written = insertColumns();
        assertThat(written).as("未解析出写入语句的列，本守卫在空跑").isNotEmpty();

        Set<String> declared = new LinkedHashSet<>(snapshotColumns);
        // id 由 DEFAULT gen_random_uuid() 生成，写入语句不该也不需要在列集里
        declared.remove("id");

        assertThat(written)
                .as("写入列集与表结构不一致：运行期会 SQLException，而权限检查一切正常")
                .containsExactlyInAnyOrderElementsOf(declared);
    }

    @Test
    @DisplayName("⛔ 写入语句不得用 RETURNING（它要 SELECT 权限，而应用角色按设计没有）")
    void insertMustNotUseReturning() throws IOException {
        String src = Files.readString(RECORDER, StandardCharsets.UTF_8);

        // ⚠️ 这是一条实测出来的坑，不是理论洁癖：
        //    PostgreSQL 的 `INSERT ... RETURNING <col>` 需要**返回列上的 SELECT 权限**。
        //    本表按设计只给应用角色 INSERT（见 03_platform_roles.sql §2b），
        //    实测同一个角色：不带 RETURNING 的 INSERT 成功、带 RETURNING 的直接
        //    `ERROR: permission denied for table authz_decision_log`。
        //    而写入失败会被本类的「限频告警 + 不外抛」吞掉 ——
        //    <b>表面一切正常，审计流水一行不涨</b>，正是最坏的那种失效。
        //    将来有人为了「拿到插入的 id」顺手加个 RETURNING，构建期就该红。
        assertThat(src)
                .as("JdbcAuthzDecisionLog 的 SQL 里出现了 RETURNING：应用角色无 SELECT 权限，"
                        + "每次写入都会 permission denied，且失败被限频告警吞掉（静默无审计）")
                .doesNotContainIgnoringCase("RETURNING");
    }

    @Test
    @DisplayName("⛔ 启动探测必须是零行写入（否则每次启动都往审计流水里塞 SELF_CHECK 假行）")
    void probeWritesNoRows() throws IOException {
        String src = Files.readString(RECORDER, StandardCharsets.UTF_8);

        // 探测的价值在于「语法解析 + 权限检查 + 列类型推导都真跑一遍」，
        // 但又不能真的留下行 —— 审计流水是「判定发生过」的举证材料，
        // 掺进启动自检行等于稀释证据。WHERE FALSE 是这两者的唯一交集。
        assertThat(src)
                .as("探测语句缺 WHERE FALSE：启动一次就写一行 SELF_CHECK，审计流水被污染")
                .contains("WHERE FALSE");
    }

    @Test
    @DisplayName("⛔ 授权脚本必须给应用角色 INSERT，且显式 REVOKE SELECT（否则可枚举全平台判定流水）")
    void grantsAreInsertOnly() throws IOException {
        String sql = Files.readString(GRANTS, StandardCharsets.UTF_8);

        assertThat(sql)
                .as("03 未授权 INSERT：应用角色写决策日志会 permission denied，"
                        + "而报错点在业务代码里，离「没授权」极远")
                .contains("GRANT INSERT ON " + TABLE);

        assertThat(sql)
                .as("03 未显式 REVOKE SELECT：platform schema 的 ALTER DEFAULT PRIVILEGES "
                        + "会自动把新表的 SELECT 授给应用角色 —— 下次建表又漏")
                .contains("REVOKE SELECT ON " + TABLE);
    }

    // ── 解析 ──────────────────────────────────────────────────────

    /**
     * @return {@code JdbcAuthzDecisionLog} 里 {@code INSERT INTO platform.authz_decision_log (…)}
     *         的列名集合（去空白、小写）
     */
    private static Set<String> insertColumns() throws IOException {
        String src = Files.readString(RECORDER, StandardCharsets.UTF_8);
        // 只取「列清单」那一段：INSERT INTO <表> (  ...  )
        Matcher m = Pattern.compile(
                "INSERT INTO\\s+" + Pattern.quote(TABLE) + "\\s*\\(([^)]*)\\)", Pattern.DOTALL)
                .matcher(src);
        assertThat(m.find()).as("未在 JdbcAuthzDecisionLog 中找到 INSERT 语句").isTrue();

        Set<String> out = new LinkedHashSet<>();
        for (String part : m.group(1).split(",")) {
            String col = part.trim().toLowerCase(Locale.ROOT);
            if (!col.isEmpty()) {
                out.add(col);
            }
        }
        return out;
    }

    /** 仓库根：由结构快照的位置反推，避免依赖 IDE/模块的当前工作目录。 */
    private static Path repoRoot() {
        // deploy/sql/platform/01_platform_ddl.sql → 上溯四级到仓库根
        return PlatformDdl.file().getParent().getParent().getParent().getParent();
    }
}
