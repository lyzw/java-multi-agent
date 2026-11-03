package cn.studykid.multiagent.infrastructure.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 种子租户写入守卫（IT 隔离纪律）。
 *
 * <p>⚠️ <b>本类存在的唯一理由</b>：开发机上常驻一个运行实例，它按
 * {@code agent.worker.tenant-slugs}（默认 {@code t-a}）轮询真实租户。集成测试只要往
 * {@code t-a} <b>写业务行</b>，就多了一个<b>不受测试控制的写者</b>：
 * <ul>
 *   <li>{@code QUEUED} Run 会被 worker 领走并改写终态 → 断言变成竞态。
 *       实测已因此红过 {@code PgQueueIT}、{@code MessagingSwitchGuardIT}、
 *       {@code JdbcStatisticsRepositoryIT}、{@code RunClaimFencingIT}；</li>
 *   <li>Run 与子表会<b>永久累积</b>，涨到 {@code claimTasks} 的 {@code LIMIT} 配额之上后，
 *       连<b>别的</b> IT 的领取断言都开始假红 —— 污染跨测试类传染；</li>
 *   <li>{@code execution_snapshot} 被 {@code forbid_snapshot_mutation} 保护、
 *       <b>物理删不掉</b>，每轮多一份就是永久多一份。</li>
 * </ul>
 *
 * <p><b>可证伪命题</b>：一个<b>连库的 IT 类</b>不得同时出现「种子租户 slug 字面量」与
 * 「写业务行」两种信号，除非登记在 {@link #REVIEWED_WRITERS}。
 *
 * <p><b>为什么限定 IT 类（{@code *IT.java} / {@code *ITBase.java}）</b>：
 * 纯单元测试里把 {@code "t-a"} 当假租户 id、同时 mock 出 {@code insertRunAtomically}，
 * 是极常见的写法（如 {@code RunCreationService*Test}），它们<b>不连库</b>、不可能造成污染。
 * 若把它们一起判红，守卫会持续输出误报，最终被人删掉 —— 误报的守卫等于没有守卫。
 * 用文件名能不能区分？能，且本项目的 IT 命名是强制的（failsafe 只认 {@code *IT}）。
 *
 * <p><b>诚实边界</b>：这是<b>文本信号</b>，不是数据流分析。
 * ① 把 slug 抽到常量再拼接、② 经 HTTP 端点建 Run（{@code POST /api/runs} 后再断言）
 * 这两种写法抓不到。因此本守卫只能拦住「照着现有写法新增的泄漏」，
 * <b>不能</b>宣称「测试不写种子租户」。根治仍是 IT 跑一次性库/一次性 schema（见项目备忘件）。
 */
class SeedTenantWriteArchitectureTest {

    /** 种子租户 slug 字面量（单/双引号都算）。 */
    private static final Pattern SEED_SLUG = Pattern.compile("\"t-[ab]\"|'t-[ab]'");

    /** 造业务行的写入标记（Run / Agent / 版本 / 快照 / Outbox / 事件）。 */
    private static final Pattern WRITE = Pattern.compile(
            "(?i)\\bINSERT\\s+INTO\\s+(?:public\\.)?"
                    + "(run|outbox_event|run_event|agent|agent_version|execution_snapshot)\\b"
                    + "|insertRunAtomically");

    /**
     * 已评审的例外：相对 {@code platform/} 的路径 → <b>为什么留在种子租户 + 残留影响</b>。
     *
     * <p>登记门槛（两条都要满足，缺一不可）：
     * <ol>
     *   <li>该用例<b>必须</b>用种子租户才能测到它声称要测的东西（不是「改起来麻烦」）；</li>
     *   <li>残留影响写清楚（多出哪些行、会不会被 worker 改写、有没有自清理）。</li>
     * </ol>
     *
     * <p>⚠️ 理由不能为空且要有实质内容 —— 由 {@code exceptionsCarryReasons} 强制。
     * 条目对应的文件迁走之后必须删除，否则由 {@code noDeadExceptionEntries} 判红。
     */
    private static final Map<String, String> REVIEWED_WRITERS = new LinkedHashMap<>();

    static {
        REVIEWED_WRITERS.put(
                "agent-infrastructure/src/test/java/cn/studykid/multiagent/infrastructure/persistence/"
                        + "RetentionErasureIT.java",
                "G3 清理通道验证：需要真实审计链数据 + 两套凭据（应用池「删不了」/清理池「删得对」），"
                        + "断言与租户归属无关；残留：t-a 上累积 run 行（可删），不影响 worker 领取");
        REVIEWED_WRITERS.put(
                "platform-bootstrap/src/test/java/cn/studykid/multiagent/bootstrap/RunCreationEndpointIT.java",
                "必须用「名字不带 it- 前缀、且已被生产回填 cap_agent_def」的真实业务 Agent 才能验证回填生效，"
                        + "迁到 it- 租户等于不测该行为；残留：每轮新增 agent 行与不可删快照（已接受）");
        REVIEWED_WRITERS.put(
                "platform-bootstrap/src/test/java/cn/studykid/multiagent/bootstrap/AgentEndpointIT.java",
                "端点契约测试，JWT 由本类自签、租户取自 DB；只插 agent 行（不建版本/快照），"
                        + "残留：t-a 上累积 agent 行，不产生 QUEUED Run，不干扰 worker");
        REVIEWED_WRITERS.put(
                "platform-bootstrap/src/test/java/cn/studykid/multiagent/bootstrap/GovernanceEndpointIT.java",
                "治理端点自建夹具 Run 为 RUNNING 且 lease 为 NULL（claimTasks 不会接管），"
                        + "断言只看本 runId 的审批/操作状态；残留：t-a 上累积 run 行（可删）");
        REVIEWED_WRITERS.put(
                "platform-bootstrap/src/test/java/cn/studykid/multiagent/bootstrap/ReadEndpointIT.java",
                "读端点要对着真实种子租户验「跨租户 404 而非 403」「画像白名单」等契约；"
                        + "@AfterEach 自删本轮创建的 run/student，残留有限；"
                        + "⚠️ 其中 seedRun(\"QUEUED\") 存在被常驻 worker 领取的理论窗口（实测未红）");
        REVIEWED_WRITERS.put(
                "platform-bootstrap/src/test/java/cn/studykid/multiagent/bootstrap/ResultEndpointIT.java",
                "结果端点契约测试，需真实租户的 run 与其 result_payload 组合；"
                        + "@AfterEach 自删本轮创建的行，残留有限");
        REVIEWED_WRITERS.put(
                "platform-bootstrap/src/test/java/cn/studykid/multiagent/bootstrap/StatisticsEndpointIT.java",
                "工作台统计端点要验「库侧聚合口径」，依赖租户真实数据的分布形态；"
                        + "残留：t-a 上累积 run 行（可删），统计断言均为相对/阈值断言");
    }

    // ── 静态扫描 ──────────────────────────────────────────────────

    @Nested
    @DisplayName("静态扫描")
    class StaticScan {

        @Test
        @DisplayName("⚠️⚠️ 连库 IT 不得在种子租户上写业务行（未被评审的）")
        void noUnreviewedWriteToSeedTenant() {
            List<String> violations = new ArrayList<>();
            for (Path file : testSourceFiles()) {
                String rel = relative(file);
                if (!isSeedTenantWrite(rel, readCode(file))) {
                    continue;
                }
                if (!REVIEWED_WRITERS.containsKey(rel)) {
                    violations.add(rel);
                }
            }

            assertThat(violations)
                    .as("⚠️⚠️ 这些 IT 在种子租户（t-a/t-b）上写业务行：常驻运行实例会抢走它们造的 Run，"
                            + "断言变成竞态；Run 与不可删的快照还会永久累积。"
                            + "请改用 it- 专用租户（FaultInjectionSupport.provisionItTenant），"
                            + "确有必要留在种子租户则登记进 REVIEWED_WRITERS 并写清理由")
                    .isEmpty();
        }

        @Test
        @DisplayName("⚠️ 例外台账必须带实质理由（防「无理由豁免」）")
        void exceptionsCarryReasons() {
            List<String> blank = new ArrayList<>();
            REVIEWED_WRITERS.forEach((path, reason) -> {
                if (reason == null || reason.strip().length() < 20) {
                    blank.add(path);
                }
            });

            assertThat(blank)
                    .as("例外的理由必须写清「为什么不能用 it- 租户 + 残留影响」，空理由等于把守卫变成形式")
                    .isEmpty();
        }

        @Test
        @DisplayName("✅ 台账无漂移：已登记的例外必须仍是「种子租户写入」，迁走后要删条目")
        void noDeadExceptionEntries() {
            List<String> dead = new ArrayList<>();
            for (String rel : REVIEWED_WRITERS.keySet()) {
                Path file = platformRoot().resolve(rel);
                if (!Files.isRegularFile(file) || !isSeedTenantWrite(rel, readCode(file))) {
                    dead.add(rel);
                }
            }

            assertThat(dead)
                    .as("台账里的文件已不再是「种子租户写入」（可能已迁到 it- 租户或改名）："
                            + "留着这条豁免，将来同一路径复发泄漏会被静默放行")
                    .isEmpty();
        }
    }

    // ── 扫描器自证（反证） ────────────────────────────────────────

    @Nested
    @DisplayName("扫描器自证")
    class ScannerSelfProof {

        @Test
        @DisplayName("⚠️⚠️ IT 类「种子租户 + 写 Run」必须被检出")
        void detectsSeedTenantWrite() {
            String violating = """
                    class Evil {
                        void setUp() {
                            tenantA = tenantIdOf("t-a");
                            adminExec("INSERT INTO run (run_id, tenant_id) VALUES (?, ?)");
                        }
                    }
                    """;

            assertThat(isSeedTenantWrite("x/SomeIT.java", violating))
                    .as("⚠️⚠️ 种子租户写入必须被判红，否则守卫是空转")
                    .isTrue();
        }

        @Test
        @DisplayName("✅ 只读种子租户（不写业务行）不误报")
        void readOnlySeedLookupIsNotFlagged() {
            String readOnly = """
                    class Ok {
                        void setUp() {
                            tenantA = tenantIdOf("t-a");
                        }
                    }
                    """;

            assertThat(isSeedTenantWrite("x/SomeIT.java", readOnly))
                    .as("读种子数据的 IT 是正当的（如读真实种子的契约测试），不得误报")
                    .isFalse();
        }

        @Test
        @DisplayName("✅ 单元测试（*Test）里 mock 出写入 + 假租户 id 不误报")
        void unitTestWithMockedWriteIsNotFlagged() {
            String unit = """
                    class Ok {
                        void createsRun() {
                            when(port.insertRunAtomically(eq("t-a"), any(), any(), any(), any(), any(), any()))
                                    .thenReturn("run-1");
                        }
                    }
                    """;

            assertThat(isSeedTenantWrite(
                    "x/src/test/java/cn/studykid/multiagent/RunCreationServiceBudgetTest.java", unit))
                    .as("单元测试不连库，mock 出写入不会造成污染；把它们判红只会制造误报")
                    .isFalse();
        }

        @Test
        @DisplayName("✅ 本守卫自身（*Test）不被自己的反证片段判红")
        void guardItselfIsNotFlagged() {
            assertThat(isSeedTenantWrite(
                    "agent-infrastructure/src/test/java/cn/studykid/multiagent/infrastructure/it/"
                            + "SeedTenantWriteArchitectureTest.java",
                    readCode(platformRoot().resolve(
                            "agent-infrastructure/src/test/java/cn/studykid/multiagent/infrastructure/it/"
                                    + "SeedTenantWriteArchitectureTest.java"))))
                    .as("反证用的示例代码故意写着 t-a 与 INSERT INTO run，但它不是 IT 类")
                    .isFalse();
        }
    }

    // ── 扫描基础设施 ──────────────────────────────────────────────

    /**
     * 判定「该文件是否构成在种子租户上写业务行」。
     *
     * @param relativePath 相对 {@code platform/} 的路径（用于识别是否为连库 IT 类）
     * @param code         已去注释的源码
     */
    private static boolean isSeedTenantWrite(String relativePath, String code) {
        return isIntegrationTest(relativePath)
                && SEED_SLUG.matcher(code).find()
                && WRITE.matcher(code).find();
    }

    /**
     * 是否为连库集成测试类。
     *
     * <p>⚠️ 这是「零误报」的关键：本项目的 IT 命名是强制的（failsafe 只跑 {@code *IT}），
     * 而 {@code *Test} 是 surefire 的纯单元测试、不连库。
     */
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
     * 只去掉注释，<b>保留字符串与文本块</b>。
     *
     * <p>⚠️ SQL 就写在字符串 / 文本块里，把字符串一起去掉等于把要检查的对象删了。
     * 去注释则是必须的：本项目大量注释里写着「不要再用 t-a」这类说明，
     * 若连注释一起扫，守卫会被自己的文档触发。
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
