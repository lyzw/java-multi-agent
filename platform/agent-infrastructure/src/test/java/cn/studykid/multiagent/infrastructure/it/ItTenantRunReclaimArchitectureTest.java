package cn.studykid.multiagent.infrastructure.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * it- 租户 Run 回收守卫。
 *
 * <p><b>为什么需要它</b>：{@code execution_snapshot} 被 {@code forbid_snapshot_mutation}
 * 保护、<b>物理删不掉</b>，于是「每轮 IT 都会往库里加东西」这件事里，快照只能接受；
 * 但 {@code run} 及其<b>全部子表</b>都是 {@code ON DELETE CASCADE}、<b>可以删</b>。
 * 一个只在 it- 租户上造 Run 却不回收的 IT，会让该租户的 Run 行按轮次线性增长：
 *
 * <ul>
 *   <li>{@code claimTasks} 有批次 {@code LIMIT}，历史 Run 多了会挤占窗口；</li>
 *   <li>「按租户取样 / 翻页 / 计数」类断言会随轮次变慢、并在某天忽然失真；</li>
 *   <li>排查时看到的「某租户 800 条 Run」无法区分是业务数据还是测试残留。</li>
 * </ul>
 *
 * <p>实测（2026-11-03，{@code agent_dev}）：治理类断言未回收时 {@code it-gov-a} 累积 114 条、
 * 队列类 {@code it-pgq-a} 196 条；加上回收后分别降到 19 / 14（即「单轮所需」）。
 *
 * <p><b>可证伪命题</b>：一个连库 IT 类若既「创建 it- 专用租户」又「造 Run」，
 * 就必须出现回收信号（{@code purgeItTenantRuns} / {@code purgeRuns} /
 * {@code purgeRunsCreatedByTests} / {@code DELETE FROM run}）。
 *
 * <p><b>诚实边界</b>（与 {@code SeedTenantWriteArchitectureTest} 同类）：
 * 这是<b>文本信号</b>，不是数据流分析。①把租户抽成常量再传、②经 HTTP 端点造 Run、
 * ③在父类里造 Run 而子类没写回收 —— 这三种写法抓不到。
 * 它拦得住的是「照现有写法新增一个只造不收的 IT」。
 */
class ItTenantRunReclaimArchitectureTest {

    /** 创建 it- 专用租户的信号（历史上有三种写法，全部纳入）。 */
    private static final Pattern PROVISION = Pattern.compile(
            "provisionItTenant\\s*\\(|FaultInjectionSupport\\.itTenant\\s*\\("
                    + "|ItTenantProvisioner\\.provision\\s*\\(|provisionTenant\\s*\\(");

    /** 造 Run 的信号（裸 SQL 与三类辅助方法）。 */
    private static final Pattern CREATES_RUN = Pattern.compile(
            "(?i)\\bINSERT\\s+INTO\\s+(?:public\\.)?run\\b"
                    + "|insertRunAtomically\\s*\\(|\\bcreateRun\\s*\\(|\\bnewRun\\s*\\(");

    /** 回收 Run 的信号。 */
    private static final Pattern RECLAIM = Pattern.compile(
            "purgeItTenantRuns\\s*\\(|purgeRuns\\s*\\(|purgeRunsCreatedByTests\\s*\\("
                    + "|(?i)\\bDELETE\\s+FROM\\s+run\\b");

    /**
     * 扫描下界的「防空转」阀值。
     *
     * <p>⚠️ 没有它，路径发现一旦坏掉（{@code user.dir} 变了、模块目录重命名），
     * 扫描结果为空集，所有断言<b>恒绿</b> —— 守卫会静默失效而没人知道。
     * 当前实际命中 17 个类，取 10 留足余量（删并模块时会先发现，而不是悄悄失效）。
     */
    private static final int MIN_SCANNED = 10;

    @Nested
    @DisplayName("静态扫描")
    class StaticScan {

        @Test
        @DisplayName("⚠️⚠️ 在 it- 租户造 Run 的 IT 必须回收，否则库按轮次线性增长")
        void noUnreclaimedItTenantRuns() {
            List<String> violations = new ArrayList<>();
            int scanned = 0;
            for (Path file : testSourceFiles()) {
                String rel = relative(file);
                if (!isIntegrationTest(rel)) {
                    continue;
                }
                String code = readCode(file);
                if (!PROVISION.matcher(code).find() || !CREATES_RUN.matcher(code).find()) {
                    continue;
                }
                scanned++;
                if (!RECLAIM.matcher(code).find()) {
                    violations.add(rel);
                }
            }

            assertThat(violations)
                    .as("⚠️⚠️ 这些 IT 在 it- 专用租户上造 Run 却不回收：Run 会按轮次累积，"
                            + "最终挤占 claimTasks 的批次窗口、让计数/翻页类断言失真。"
                            + "请在 @BeforeAll 末尾调用 FaultInjectionSupport.purgeItTenantRuns(...) "
                            + "（或在类内用 DELETE FROM run 只删本租户）")
                    .isEmpty();
            assertThat(scanned)
                    .as("⚠️ 扫描命中数异常偏少（%d < %d）：很可能是源码根目录定位失败导致空转恒绿",
                            scanned, MIN_SCANNED)
                    .isGreaterThanOrEqualTo(MIN_SCANNED);
        }
    }

    // ── 扫描器自证（反证） ────────────────────────────────────────

    @Nested
    @DisplayName("扫描器自证")
    class ScannerSelfProof {

        @Test
        @DisplayName("⚠️⚠️「建 it- 租户 + 造 Run + 不回收」必须被检出")
        void detectsUnreclaimedRuns() {
            String violating = """
                    class EvilIT {
                        @BeforeAll
                        static void setUp() {
                            tenantA = FaultInjectionSupport.provisionItTenant("it-evil-a").tenantId();
                        }
                        void t() {
                            creationRepo.insertRunAtomically(tenantA, snapshotId, "k", "d", "{}", "c", 0);
                        }
                    }
                    """;

            assertThat(needsReclaim("x/EvilIT.java", violating))
                    .as("⚠️⚠️ 只造不收必须被判红，否则守卫是空转")
                    .isTrue();
        }

        @Test
        @DisplayName("✅ 加了回收的同一段代码不误报")
        void reclaimedIsNotFlagged() {
            String ok = """
                    class OkIT {
                        @BeforeAll
                        static void setUp() {
                            tenantA = FaultInjectionSupport.itTenant(adminPool, "it-ok-a");
                            FaultInjectionSupport.purgeItTenantRuns(adminPool, "it-ok-a");
                        }
                        void t() {
                            newRun(tenantA);
                        }
                    }
                    """;

            assertThat(needsReclaim("x/OkIT.java", ok))
                    .as("已回收的 IT 是正当的，不得误报")
                    .isFalse();
        }

        @Test
        @DisplayName("✅ 不在 it- 租户造 Run 的 IT 不误报（如只读契约测试）")
        void noItTenantProvisionIsNotFlagged() {
            String readOnly = """
                    class ReadIT {
                        void t() {
                            assertThat(repo.list("t-a", null, null)).isNotNull();
                        }
                    }
                    """;

            assertThat(needsReclaim("x/ReadIT.java", readOnly))
                    .as("没有 it- 租户供给信号的类不归本守卫管")
                    .isFalse();
        }

        @Test
        @DisplayName("✅ 单元测试（*Test）不归本守卫管")
        void unitTestsAreOutOfScope() {
            String unit = """
                    class SomeTest {
                        void t() {
                            provisionTenant("it-fake");
                            newRun("x");
                        }
                    }
                    """;

            assertThat(needsReclaim("x/src/test/java/cn/studykid/SomeTest.java", unit))
                    .as("surefire 的单元测试不连库，判红只会制造误报")
                    .isFalse();
        }

        @Test
        @DisplayName("✅ 本守卫自身不被自己的反证片段判红")
        void guardItselfIsNotFlagged() {
            Path self = platformRoot().resolve(
                    "agent-infrastructure/src/test/java/cn/studykid/multiagent/infrastructure/it/"
                            + "ItTenantRunReclaimArchitectureTest.java");

            assertThat(needsReclaim(relative(self), readCode(self)))
                    .as("本类是 *Test 而非 *IT，反证片段里的代码不得把自己判红")
                    .isFalse();
        }
    }

    // ── 扫描基础设施 ──────────────────────────────────────────────

    /**
     * 判定「该文件是否需要回收 Run」。
     *
     * <p>⚠️ 三个辅助函数与 {@code SeedTenantWriteArchitectureTest} 有重复实现，
     * 这是**刻意**的：两道守卫互不依赖，删掉其中一道不会让另一道静默失效。
     */
    private static boolean needsReclaim(String relativePath, String code) {
        return isIntegrationTest(relativePath)
                && PROVISION.matcher(code).find()
                && CREATES_RUN.matcher(code).find()
                && !RECLAIM.matcher(code).find();
    }

    private static boolean isIntegrationTest(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return name.endsWith("IT.java") || name.endsWith("ITBase.java");
    }

    private static List<Path> testSourceFiles() {
        List<Path> files = new ArrayList<>();
        Path root = platformRoot();
        try (Stream<Path> modules = Files.list(root)) {
            for (Path module : modules.toList()) {
                Path src = module.resolve("src/test/java");
                if (!Files.isDirectory(src)) {
                    continue;
                }
                try (Stream<Path> tree = Files.walk(src)) {
                    tree.filter(p -> p.toString().endsWith(".java")).forEach(files::add);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("扫描测试源码失败: " + root, e);
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

    private static String readCode(Path file) {
        try {
            return stripComments(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("读取源码失败: " + file, e);
        }
    }

    /**
     * 只去注释，保留字符串与文本块 —— SQL 就写在字符串里，
     * 而注释里大量写着「不要再用 t-a」之类的说明，连注释一起扫会自己触发自己。
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
