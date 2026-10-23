package cn.studykid.multiagent.bootstrap.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 平台运维角色（BYPASSRLS）接线的架构守卫测试（P6R1）。
 *
 * <p>⚠️ <b>本类存在的唯一理由</b>：运维数据源带 {@code BYPASSRLS}，
 * 它的连接<b>不受任何 RLS 策略约束</b>。
 * 一旦这个数据源被接到业务读路径上（哪怕只是多注入一个 {@code DataSource} 参数），
 * 租户隔离就<b>整体失效</b> —— 而这种误用在代码审查时极难一眼看出。
 *
 * <p>⚠️ <b>本类锁的是「可达性」，不是「实现细节」</b>：
 * <ol>
 *   <li><b>运维数据源只有一个消费者</b>：{@code JdbcPlatformOpsConfigPublishTransaction}。
 *       新增第二个消费者 = 多一条能拿到 BYPASSRLS 连接的路径，必须显式登记并说明理由；</li>
 *   <li><b>运维数据源只有一个生产者</b>：{@code PlatformOpsDataSourceConfiguration}。
 *       原始连接池不得在别处被构造；</li>
 *   <li><b>应用角色的发布通道不得引用运维凭据</b>：
 *       {@code JdbcConfigPublishTransaction} 一旦拿到运维连接，
 *       「按 scope 自动选池」就成了「调用方自选凭据」；</li>
 *   <li><b>运维发布入口只有一个装配点</b>：
 *       {@code PlatformOpsPublishService} 是运维凭据在应用内的<b>唯一可达路径</b>，
 *       新增装配点必须显式登记。</li>
 * </ol>
 */
class PlatformOpsDataSourceArchitectureTest {

    /**
     * 运维数据源持有者的合法引用方。
     *
     * <p>⚠️ 每一条都要能说清「为什么它拿到运维数据源是安全的」：
     * <ul>
     *   <li><b>声明本身</b> —— 它就是持有者；</li>
     *   <li><b>{@code JdbcPlatformOpsConfigPublishTransaction}</b> ——
     *       唯一消费者。它拿到的不是原始池，而是<b>受会话守卫的包装</b>，
     *       连接只在平台发布事务内可取；</li>
     *   <li><b>{@code PlatformOpsDataSourceConfiguration}</b> ——
     *       唯一生产者。它是唯一创建原始池的地方，且创建后立刻交给持有者包装，
     *       原始池不逃逸；</li>
     *   <li><b>{@code ApplicationConfiguration}</b> —— 装配点。
     *       它把持有者交给唯一消费者，<b>不接触</b>连接本身。</li>
     * </ul>
     */
    private static final Set<String> PROVIDER_HOLDERS = Set.of(
            "agent-infrastructure/src/main/java/cn/studykid/multiagent/infrastructure/persistence/cap/PlatformOpsDataSourceProvider.java",
            "agent-infrastructure/src/main/java/cn/studykid/multiagent/infrastructure/persistence/cap/JdbcPlatformOpsConfigPublishTransaction.java",
            "platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/config/PlatformOpsDataSourceConfiguration.java",
            "platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/config/ApplicationConfiguration.java");

    /** 受会话守卫的数据源的合法引用方（只有持有者能构造它）。 */
    private static final Set<String> GUARD_HOLDERS = Set.of(
            "agent-infrastructure/src/main/java/cn/studykid/multiagent/infrastructure/persistence/cap/PlatformOpsDataSourceProvider.java",
            "agent-infrastructure/src/main/java/cn/studykid/multiagent/infrastructure/persistence/cap/GuardedPlatformOpsDataSource.java");

    /** 运维发布入口的合法引用方（声明 + 唯一装配点）。 */
    private static final Set<String> SERVICE_HOLDERS = Set.of(
            "agent-application/src/main/java/cn/studykid/multiagent/application/cap/PlatformOpsPublishService.java",
            "platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/config/ApplicationConfiguration.java");

    /** 应用角色的发布事务：<b>不得</b>引用运维凭据。 */
    private static final String APP_TX = "agent-infrastructure/src/main/java/cn/studykid/multiagent/infrastructure/persistence/cap/JdbcConfigPublishTransaction.java";

    // ── 用例 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("⚠️⚠️ 运维数据源只有一个消费者：BYPASSRLS 不得有第二条可达路径")
    void platformOpsProviderHasExactlyOneConsumer() {
        List<String> violations = new ArrayList<>();
        for (Path file : mainSourceFiles()) {
            String code = stripCommentsAndStrings(read(file));
            if (!code.contains("PlatformOpsDataSourceProvider")) {
                continue;
            }
            String rel = relative(file);
            if (!PROVIDER_HOLDERS.contains(rel)) {
                violations.add(rel);
            }
        }

        assertThat(violations)
                .as("⚠️⚠️ 新增了运维数据源的引用方：运维连接带 BYPASSRLS，"
                        + "每多一个消费者就多一条能绕过 RLS 的路径。"
                        + "确有必要时必须显式登记到 PROVIDER_HOLDERS 并说明理由")
                .isEmpty();
    }

    @Test
    @DisplayName("⚠️⚠️ 受守卫的数据源只能由持有者构造")
    void guardedDataSourceIsOnlyConstructedByItsOwner() {
        List<String> violations = new ArrayList<>();
        for (Path file : mainSourceFiles()) {
            String code = stripCommentsAndStrings(read(file));
            if (!code.contains("GuardedPlatformOpsDataSource")) {
                continue;
            }
            String rel = relative(file);
            if (!GUARD_HOLDERS.contains(rel)) {
                violations.add(rel);
            }
        }

        assertThat(violations)
                .as("⚠️⚠️ 受守卫的数据源被别处构造：守卫可被绕过")
                .isEmpty();
    }

    @Test
    @DisplayName("⛔ 应用角色的发布事务不得引用运维凭据")
    void appRoleTransactionMustNotTouchPlatformOps() {
        Path appTx = platformRoot().resolve(APP_TX);
        String code = stripCommentsAndStrings(read(appTx));

        assertThat(code)
                .as("⛔ 应用角色的发布事务引用了运维数据源："
                        + "一旦它能按 scope 自动选池，调用方只需把 scope 写成 PLATFORM "
                        + "就拿到了运维凭据，DB 层的越权防线被降级成应用层的一行判断")
                .doesNotContain("PlatformOps");
    }

    @Test
    @DisplayName("⚠️⚠️ 运维发布入口只有一个装配点")
    void platformOpsPublishServiceHasExactlyOneWiringSite() {
        List<String> violations = new ArrayList<>();
        for (Path file : mainSourceFiles()) {
            String code = stripCommentsAndStrings(read(file));
            if (!code.contains("PlatformOpsPublishService")) {
                continue;
            }
            String rel = relative(file);
            if (!SERVICE_HOLDERS.contains(rel)) {
                violations.add(rel);
            }
        }

        assertThat(violations)
                .as("⚠️⚠️ 新增了运维发布入口的装配点："
                        + "它是运维凭据在应用内的唯一可达路径，"
                        + "多一个装配点就多一条未经平台运维认证的调用路径")
                .isEmpty();
    }

    @Test
    @DisplayName("⚠️ 运维连接池只能在装配层构造（原始池不得在别处诞生）")
    void rawOpsPoolIsOnlyBuiltInBootstrap() {
        List<String> violations = new ArrayList<>();
        for (Path file : mainSourceFiles()) {
            String code = stripCommentsAndStrings(read(file));
            if (!code.contains("new HikariDataSource")) {
                continue;
            }
            String rel = relative(file);
            // ⚠️ 装配层是唯一允许自建连接池的地方（运维池）；
            //    其余业务模块不得自建池，以免绕过运维通道的会话守卫。
            if (!rel.startsWith("platform-bootstrap/")) {
                violations.add(rel);
            }
        }

        assertThat(violations)
                .as("⚠️ 业务模块自建了连接池：运维连接池若在别处诞生，"
                        + "会话守卫（只在平台发布事务内可取连接）就形同虚设")
                .isEmpty();
    }

    // ── 辅助 ──────────────────────────────────────────────────────

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
        Path root = platformRoot();
        return root.relativize(file.toAbsolutePath()).toString().replace('\\', '/');
    }

    private static String read(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取源码失败: " + file, e);
        }
    }

    /**
     * 剥离注释与字符串字面量。
     *
     * <p>⚠️ <b>必须剥离</b>：否则「在注释里提到运维数据源」会被算成引用，
     * 白名单会迅速失效并让本测试变成噪音。
     */
    private static String stripCommentsAndStrings(String src) {
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
            if (c == '"' && i + 2 < n && src.charAt(i + 1) == '"' && src.charAt(i + 2) == '"') {
                i += 3;
                while (i + 2 < n && !(src.charAt(i) == '"' && src.charAt(i + 1) == '"'
                        && src.charAt(i + 2) == '"')) {
                    i++;
                }
                i = Math.min(i + 3, n);
                out.append(' ');
                continue;
            }
            if (c == '"') {
                i++;
                while (i < n && src.charAt(i) != '"') {
                    if (src.charAt(i) == '\\') {
                        i++;
                    }
                    i++;
                }
                i = Math.min(i + 1, n);
                out.append('"');
                continue;
            }
            if (c == '\'') {
                i++;
                while (i < n && src.charAt(i) != '\'') {
                    if (src.charAt(i) == '\\') {
                        i++;
                    }
                    i++;
                }
                i = Math.min(i + 1, n);
                out.append('\'');
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }
}
