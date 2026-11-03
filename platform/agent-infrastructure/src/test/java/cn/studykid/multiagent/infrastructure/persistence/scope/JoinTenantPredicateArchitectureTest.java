package cn.studykid.multiagent.infrastructure.persistence.scope;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * JOIN 从表租户谓词架构守卫（12 文 §4.5 / §7.1 R7 · 13 文 §5.2 任务 3 · D13）。
 *
 * <p>⚠️ <b>本类存在的唯一理由</b>：RLS 会对查询树里<b>每一张表</b>（含 JOIN 从表、
 * 子查询、CTE）自动注入 {@code tenant_id} 谓词；应用层 {@code ScopeFilter} 默认只渲染
 * <b>主表</b>。因此退役 RLS 后，「主表过滤正确、从表没过滤」这一种查询会静默返回
 * 他租户的关联行 —— 它是唯一一个<b>主表染色也发现不了</b>的泄漏形态
 * （返回行的主表字段仍属本租户，只有从表字段越权）。
 *
 * <p>守卫把可证伪命题钉死为两条：
 * <ol>
 *   <li><b>从表谓词</b>：每个 {@code JOIN <table> ... ON} 子句（到下一个子句关键字为止）
 *       必须出现 {@code tenant_id}；</li>
 *   <li><b>台账完整性</b>：新出现的 JOIN 从表必须登记在 {@link #REVIEWED_JOIN_TABLES}，
 *       否则「新增一条 JOIN」可以完全绕过评审。</li>
 * </ol>
 *
 * <p><b>豁免</b>：{@code platform.*} schema 是平台目录（无 RLS、无 {@code tenant_id} 列），
 * 由 {@code GuardedPlatformOpsDataSource} 的通道闸门负责；{@code pg_*} 为系统目录。
 *
 * <p><b>诚实边界</b>：本守卫只扫 {@code platform/} 模块的 Java 主源码（SQL 以文本块内嵌
 * 在 Java 里）。{@code deploy/sql} 下的运维脚本、{@code rag/} 模块的 JOIN 属 D13 清单
 * 人工登记范围（见 {@code docs/权限管控体系/authz/join-inventory.md}）。
 */
class JoinTenantPredicateArchitectureTest {

    /** JOIN 子句形态：可选连接修饰词 + JOIN + 可选 LATERAL + 表名（可带 schema 前缀）。 */
    private static final Pattern JOIN =
            Pattern.compile("(?i)\\b(?:LEFT\\s+|RIGHT\\s+|INNER\\s+|FULL\\s+|CROSS\\s+)?"
                    + "JOIN\\s+(LATERAL\\s+)?([A-Za-z_][\\w.]*)");

    /** 子句边界：JOIN 子句到此为止（之后是别的子句，不再是本 JOIN 的连接条件）。 */
    private static final Pattern CLAUSE_END =
            Pattern.compile("(?i)\\b(JOIN|WHERE|GROUP\\s+BY|ORDER\\s+BY|LIMIT|HAVING|UNION)\\b");

    /**
     * 已评审的 JOIN 从表（D13 清单的代码侧镜像）。
     *
     * <p>登记条件：该表的 JOIN 连接条件里<b>确实</b>带了 {@code tenant_id}。
     * 新增 JOIN 必须先确认这一点，再登记到这里。
     */
    private static final Set<String> REVIEWED_JOIN_TABLES = Set.of(
            "agent",
            "agent_invocation",
            "agent_version",
            "budget_reservation",
            "execution_snapshot",
            "principal_role",
            "tool_operation",
            "tool_operation_attempt",
            "LATERAL");

    // ── 静态扫描 ──────────────────────────────────────────────────

    @Nested
    @DisplayName("静态扫描")
    class StaticScan {

        @Test
        @DisplayName("⚠️⚠️ 每个 JOIN 从表都必须在连接条件里带 tenant_id（R7）")
        void everyJoinCarriesTenantPredicate() {
            List<String> violations = new ArrayList<>();

            for (Path file : mainSourceFiles()) {
                String code = stripComments(read(file));
                Matcher m = JOIN.matcher(code);
                while (m.find()) {
                    if (!m.group(0).toUpperCase().contains("LATERAL")
                            && isExemptTable(m.group(2))) {
                        continue;
                    }
                    String window = clauseWindow(code, m);
                    if (!window.toLowerCase().contains("tenant_id")) {
                        violations.add(relative(file) + " → JOIN " + m.group(2)
                                + "（连接条件内无 tenant_id）");
                    }
                }
            }

            assertThat(violations)
                    .as("⚠️⚠️ JOIN 从表缺 tenant_id = 退役 RLS 后静默返回他租户关联行；"
                            + "且这种泄漏主表染色发现不了（主表字段仍属本租户）")
                    .isEmpty();
        }

        @Test
        @DisplayName("⚠️ 新出现的 JOIN 从表必须登记（防绕过评审）")
        void joinTablesAreRegistered() {
            Set<String> discovered = new LinkedHashSet<>();
            for (Path file : mainSourceFiles()) {
                String code = stripComments(read(file));
                Matcher m = JOIN.matcher(code);
                while (m.find()) {
                    String raw = m.group(2);
                    // ⚠️ 豁免判定必须用**原始**表名（带 schema 前缀）：
                    //    normalize 会先剥掉 `platform.`，之后就再也认不出它是平台目录表了。
                    if (!isExemptTable(raw)) {
                        discovered.add(normalize(raw));
                    }
                }
            }

            Set<String> unregistered = new LinkedHashSet<>(discovered);
            unregistered.removeAll(REVIEWED_JOIN_TABLES);

            assertThat(unregistered)
                    .as("新 JOIN 从表必须先确认「连接条件带 tenant_id + 染色覆盖」，"
                            + "再登记进 REVIEWED_JOIN_TABLES 与 authz/join-inventory.md")
                    .isEmpty();
        }

        @Test
        @DisplayName("✅ 台账无漂移：登记的从表必须真的仍被 JOIN（删查询要同步删台账）")
        void registryHasNoDeadEntries() {
            Set<String> discovered = new LinkedHashSet<>();
            for (Path file : mainSourceFiles()) {
                String code = stripComments(read(file));
                Matcher m = JOIN.matcher(code);
                while (m.find()) {
                    discovered.add(normalize(m.group(2)));
                }
            }

            Set<String> dead = new LinkedHashSet<>(REVIEWED_JOIN_TABLES);
            dead.removeAll(discovered);

            assertThat(dead)
                    .as("台账里的从表已不再被 JOIN：留着会让守卫保护一张早已不查的表（静默失效）")
                    .isEmpty();
        }
    }

    // ── 扫描器自证（反证） ────────────────────────────────────────

    @Nested
    @DisplayName("扫描器自证")
    class ScannerSelfProof {

        @Test
        @DisplayName("⚠️⚠️ 注入的「从表无 tenant_id」必须被检出")
        void detectsJoinWithoutTenantPredicate() {
            String violating = """
                    class Evil {
                        String sql = \"\"\"
                            SELECT r.id FROM budget_reservation r
                            JOIN tool_operation o ON o.operation_id = r.operation_id
                            WHERE r.tenant_id = ?::uuid
                            \"\"\";
                    }
                    """;

            assertThat(violationsIn(violating))
                    .as("⚠️⚠️ 单键 JOIN（缺 tenant_id）必须被判红，否则守卫是空转")
                    .hasSize(1);
        }

        @Test
        @DisplayName("✅ 复合键 JOIN（带 tenant_id）不误报")
        void doesNotFlagCompliantJoin() {
            String compliant = """
                    class Ok {
                        String sql = \"\"\"
                            SELECT r.id FROM budget_reservation r
                            JOIN tool_operation o
                              ON o.tenant_id = r.tenant_id
                             AND o.operation_id = r.operation_id
                            WHERE r.tenant_id = ?::uuid
                            \"\"\";
                    }
                    """;

            assertThat(violationsIn(compliant))
                    .as("合规复合键 JOIN 不得被误判")
                    .isEmpty();
        }

        @Test
        @DisplayName("✅ 平台目录表（platform.*）豁免")
        void exemptsPlatformCatalogJoin() {
            String catalog = """
                    class Ok {
                        String sql = \"\"\"
                            SELECT r.name FROM platform.platform_user ur
                            JOIN platform.platform_role r ON r.id = ur.role_id
                            WHERE ur.platform_user_id = ?::uuid
                            \"\"\";
                    }
                    """;

            assertThat(violationsIn(catalog))
                    .as("platform.* 无 RLS、无 tenant_id 列，由通道闸门负责，不得误报")
                    .isEmpty();
        }

        /** 把片段喂给同一套扫描逻辑，返回违例描述（复用生产扫描代码路径）。 */
        private static List<String> violationsIn(String code) {
            List<String> violations = new ArrayList<>();
            Matcher m = JOIN.matcher(code);
            while (m.find()) {
                if (!m.group(0).toUpperCase().contains("LATERAL") && isExemptTable(m.group(2))) {
                    continue;
                }
                if (!clauseWindow(code, m).toLowerCase().contains("tenant_id")) {
                    violations.add("JOIN " + m.group(2));
                }
            }
            return violations;
        }
    }

    // ── 扫描基础设施 ──────────────────────────────────────────────

    private static boolean isExemptTable(String rawTable) {
        String table = rawTable.toLowerCase();
        return table.startsWith("platform.")
                || table.startsWith("pg_")
                || table.startsWith("information_schema.");
    }

    private static String normalize(String rawTable) {
        String table = rawTable.replace("\"", "");
        int dot = table.lastIndexOf('.');
        return dot >= 0 ? table.substring(dot + 1) : table;
    }

    /**
     * 取本 JOIN 的连接条件窗口。
     *
     * <p>⚠️ {@code JOIN LATERAL (...)} 的子查询里必然先出现 {@code WHERE}，
     * 因此 LATERAL 不能按子句边界截断，改用固定长度窗口（子查询的租户过滤就在其中）。
     */
    private static String clauseWindow(String code, Matcher m) {
        int from = m.end();
        int hardEnd = Math.min(code.length(), from + 1000);
        String rest = code.substring(from, hardEnd);
        if (m.group(0).toUpperCase().contains("LATERAL")) {
            return rest;
        }
        Matcher end = CLAUSE_END.matcher(rest);
        return end.find() ? rest.substring(0, end.start()) : rest;
    }

    private static List<Path> mainSourceFiles() {
        List<Path> files = new ArrayList<>();
        Path root = platformRoot();
        try (Stream<Path> modules = Files.list(root)) {
            for (Path module : modules.toList()) {
                Path src = module.resolve("src/main/java");
                if (!Files.isDirectory(src)) {
                    continue;
                }
                try (Stream<Path> tree = Files.walk(src)) {
                    tree.filter(p -> p.toString().endsWith(".java")).forEach(files::add);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("扫描主源码失败: " + root, e);
        }
        return files;
    }

    private static Path platformRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("agent-core")) && Files.exists(dir.resolve("pom.xml"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("未定位到 platform 源码根目录（user.dir="
                + System.getProperty("user.dir") + "）");
    }

    private static String relative(Path file) {
        return platformRoot().relativize(file.toAbsolutePath()).toString().replace('\\', '/');
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取源码失败: " + file, e);
        }
    }

    /**
     * 只去掉注释，<b>保留字符串与文本块</b>。
     *
     * <p>⚠️ 与 {@code ScopeBypassArchitectureTest#stripCommentsAndStrings} 的关键区别：
     * SQL 就写在文本块里，把字符串一起去掉等于把要检查的对象删了。
     */
    private static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(i + 2, n);
                out.append(' ');
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }
}
