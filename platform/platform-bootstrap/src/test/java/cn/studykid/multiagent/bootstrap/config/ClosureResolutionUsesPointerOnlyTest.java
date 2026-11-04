package cn.studykid.multiagent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 「准入解析永远走发布指针」架构守卫（P0-1d · 2026-11-04）。
 *
 * <p>⚠️ <b>本类存在的唯一理由</b>：门禁解除的核心不变的语义是——
 * 运行期授权判定解析「Agent 当前生效版本」时，<b>主流径必须走 P5 发布指针</b>
 * （§6.3 第 2/5 条：当前指针 → 指针指向的 PUBLISHED release → object_versions → 精确版本），
 * <b>绝不</b>退化成「取该对象最新已发布版本行」—— 后者在回滚时会指向被回滚掉的版本，
 * 让租户回滚对运行期无效（P0-5 缺陷根因）。
 *
 * <p>本类用<b>静态扫描</b>钉死三条不变式（比运行时优雅，但同样<可证伪>）：
 * <ol>
 *   <li><b>准入执行器（{@code agent-worker}）零引用 {@code findLatestPublished}</b>：
 *       执行点只有一条能力链路（{@code CapExecutionFlow}→{@code CachingClosureResolver}），
 *       绝不在执行时自行选「最新行」；</li>
 *   <li><b>当前版本解析器走单一指针源</b>：{@code CachingClosureResolver} 与
 *       {@code PermissionResolver} 解析 Agent 当前版本必须委托 {@code CurrentAgentVersion}
 *       （指针优先 + 受控回退的唯一口径），<b>不得直接调用 {@code findLatestPublished}</b>；</li>
 *   <li><b>指针源内仍是「先指针后回退」</b>：{@code CurrentAgentVersion} 必须先试
 *       {@code publishedVersions.currentTenantVersion}（指针路径），
 *       回退（{@code findLatestPublished}）只在当前发布头<b>未引用该对象</b>时触发 ——
 *       只要对象在发布头里，指针口径优先生效，回滚语义不被削弱。</li>
 * </ol>
 */
class ClosureResolutionUsesPointerOnlyTest {

    /** 准入执行器（运行期每个工具调用都经过它）。 */
    private static final String WORKER_EXECUTE_DIR =
            "agent-worker/src/main/java/cn/studykid/multiagent/worker/execute";

    /** 当前版本解析器：缓存新鲜度判定 + 授权期解析。 */
    private static final String CACHING_CLOSURE_RESOLVER =
            "agent-application/src/main/java/cn/studykid/multiagent/application/cap/CachingClosureResolver.java";
    private static final String PERMISSION_RESOLVER =
            "agent-application/src/main/java/cn/studykid/multiagent/application/cap/PermissionResolver.java";

    /** 指针优先 + 受控回退的唯一口径源。 */
    private static final String CURRENT_AGENT_VERSION =
            "agent-application/src/main/java/cn/studykid/multiagent/application/cap/CurrentAgentVersion.java";

    @Test
    @DisplayName("⚠️⚠️ 准入执行器（agent-worker）不得引用 findLatestPublished：执行时绝不自选最新行")
    void admissionExecutorNeverSelectsLatestPublishedRow() {
        List<String> offenders = filesIn(WORKER_EXECUTE_DIR).stream()
                .filter(p -> stripNots(read(p)).contains("findLatestPublished"))
                .map(Path::toString)
                .toList();
        assertThat(offenders)
                .as("⚠️ agent-worker 执行路径引用了 findLatestPublished："
                        + "准入执行时自选「最新已发布行」会在回滚场景指到被回滚掉的版本 "
                        + "（P0-5 缺陷，指针读路径必须唯一）")
                .isEmpty();
    }

    @Test
    @DisplayName("⚠️⚠️ 当前版本解析器必须委托单一指针源 CurrentAgentVersion，不得直接自选最新行")
    void currentVersionResolversDelegateToPointerSource() {
        List<String> resolvers = List.of(CACHING_CLOSURE_RESOLVER, PERMISSION_RESOLVER);
        for (String file : resolvers) {
            String code = stripNots(read(root().resolve(file)));
            // ① 必须委托 CurrentAgentVersion 作为当前版本唯一口径
            assertThat(code)
                    .as("⚠️ %s 必须通过 CurrentAgentVersion 解析 Agent 当前版本（单一指针源）",
                            file)
                    .contains("CurrentAgentVersion");
            // ② 不得直接调用 findLatestPublished（那会绕过指针源，失去受控回退的口径）
            assertThat(code)
                    .as("⚠️ %s 直接调用了 findLatestPublished：当前版本解析绕过指针源",
                            file)
                    .doesNotContain("findLatestPublished");
        }
    }

    @Test
    @DisplayName("⚠️⚠️ 指针源内仍是「先指针后回退」：CurrentAgentVersion 必须先走 currentTenantVersion")
    void pointerSourceTriesPointerBeforeFallback() {
        String code = stripNots(read(root().resolve(CURRENT_AGENT_VERSION)));
        int pointer = code.indexOf("currentTenantVersion");
        int fallback = code.indexOf("findLatestPublished");
        // 指针调用必须存在，且出现在 findLatestPublished 之前（先指针后回退）
        assertThat(pointer)
                .as("⚠️ CurrentAgentVersion 必须先调用 currentTenantVersion（指针路径）")
                .isGreaterThanOrEqualTo(0);
        assertThat(fallback)
                .as("⚠️ CurrentAgentVersion 的回退 findLatestPublished 必须在指针调用之后出现")
                .isGreaterThan(pointer);
    }

    // ── 工具方法（与 ConfigPublishManagementArchitectureTest 同口径） ─────────

    private static List<Path> filesIn(String relDir) {
        Path dir = root().resolve(relDir);
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("源码目录不存在: " + dir);
        }
        try (var stream = Files.walk(dir)) {
            return stream.filter(p -> p.toString().endsWith(".java"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("遍历源码目录失败: " + dir, e);
        }
    }

    private static Path root() {
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

    private static String read(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取源码失败: " + file, e);
        }
    }

    /** 剥离注释与字符串字面量（此实现只剥注释；字符串保留以便匹配方法名）。 */
    private static String stripNots(String src) {
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