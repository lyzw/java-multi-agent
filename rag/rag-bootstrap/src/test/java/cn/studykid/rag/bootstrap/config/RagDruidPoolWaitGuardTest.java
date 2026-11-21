package cn.studykid.rag.bootstrap.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Druid 连接池「取连接等待上限」守卫（2026-11-21 连接池切 Druid，与 platform 对齐）。
 *
 * <p><b>为什么必须有这类守卫</b>：Druid 的 {@code maxWait} 默认值是
 * {@code -1}，语义是「<b>无限等待</b>」；Hikari 的 {@code connectionTimeout}
 * 默认 30s。两者默认值相反，于是迁移时<b>只要漏掉一处没设 maxWait</b>，
 * 该池在「池耗尽」或「连接创建失败」时就会<b>永久挂起</b> ——
 * 不是超时失败，是线程永远停在 {@code notEmpty.await()}（无超时分支），
 * 现场停在 Druid 内部，<b>不指向任何一行本仓代码</b>。
 *
 * <p><b>可证伪命题</b>：本仓每一处 {@code new DruidDataSource()}，
 * 都必须在同一段配置里显式设置 {@code setMaxWait}。
 * 抽掉任意一处的 {@code setMaxWait(...)}，本测试变红。
 *
 * <p>⚠️ 防空转：路径发现失败会让空集合恒绿，故带「至少扫到 N 个文件 /
 * N 个池构造点」的反向断言。
 */
class RagDruidPoolWaitGuardTest {

    /** Druid 池的声明形态：{@code DruidDataSource pool = new DruidDataSource();}。 */
    private static final Pattern DECL = Pattern.compile(
            "^\\s*(?:[A-Za-z_][A-Za-z0-9_.<>]*\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*new DruidDataSource\\(\\);\\s*$");

    /** 在声明之后的多行内找 {@code var.setMaxWait(}。 */
    private static final int LOOKAHEAD_LINES = 25;

    @Test
    @DisplayName("⚠️⚠️ 每处 Druid 池都必须显式设置 maxWait（Druid 默认 -1 = 无限等待）")
    void everyDruidPoolSetsMaxWait() {
        List<String> violations = new ArrayList<>();
        int[] files = {0};
        int[] pools = {0};

        for (Path file : javaFiles()) {
            files[0]++;
            List<String> lines = read(file);
            for (int i = 0; i < lines.size(); i++) {
                Matcher decl = DECL.matcher(lines.get(i));
                if (!decl.matches()) {
                    continue;
                }
                pools[0]++;
                String var = decl.group(1);
                Pattern setter = Pattern.compile("\\b" + Pattern.quote(var) + "\\.setMaxWait\\(");
                boolean found = false;
                for (int ahead = i + 1; ahead < Math.min(lines.size(), i + LOOKAHEAD_LINES); ahead++) {
                    if (DECL.matcher(lines.get(ahead)).matches()) {
                        break;
                    }
                    if (setter.matcher(lines.get(ahead)).find()) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    violations.add(rel(file) + ":" + (i + 1) + " → " + var);
                }
            }
        }

        assertThat(files[0]).as("至少应扫到 250 个源码文件，否则是路径发现失败")
                .isGreaterThanOrEqualTo(250);
        assertThat(pools[0]).as("至少应扫到 2 个 Druid 池构造点，否则是路径发现失败")
                .isGreaterThanOrEqualTo(2);
        assertThat(violations)
                .as("⚠️⚠️ 这些 Druid 池没设 maxWait：Druid 的默认值是 -1（无限等待），"
                        + "池耗尽/连接创建失败时 getConnection() 会**永久挂起**而不是超时失败 —— "
                        + "现场停在 Druid 内部（notEmpty.await()），不指向任何一行本仓代码。"
                        + "Hikari 的 connectionTimeout 默认 30s，迁移时必须逐处补上")
                .isEmpty();
    }

    // ── 辅助 ──────────────────────────────────────────────────────

    private static List<Path> javaFiles() {
        List<Path> out = new ArrayList<>();
        Path root = ragRoot();
        try (Stream<Path> modules = Files.list(root)) {
            for (Path module : modules.toList()) {
                for (String scope : new String[]{"src/main/java", "src/test/java"}) {
                    Path dir = module.resolve(scope);
                    if (!Files.isDirectory(dir)) {
                        continue;
                    }
                    try (Stream<Path> tree = Files.walk(dir)) {
                        tree.filter(p -> p.toString().endsWith(".java")).forEach(out::add);
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("扫描源码失败: " + root, e);
        }
        return out;
    }

    private static List<String> read(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取失败: " + file, e);
        }
    }

    private static Path ragRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("rag-core")) && Files.exists(dir.resolve("pom.xml"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("找不到 rag 根目录（user.dir="
                + System.getProperty("user.dir") + "）");
    }

    private static String rel(Path file) {
        return ragRoot().relativize(file.toAbsolutePath()).toString().replace('\\', '/');
    }
}
