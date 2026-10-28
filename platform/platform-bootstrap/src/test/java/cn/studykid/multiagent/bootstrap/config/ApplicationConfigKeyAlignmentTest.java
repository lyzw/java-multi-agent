package cn.studykid.multiagent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

/**
 * 配置键对齐守卫：<b>代码读的每一个配置键，都必须在 {@code application.yml} 里真的存在</b>。
 *
 * <p>⚠️ <b>本类存在的唯一理由（一次真实的线上级故障）</b>：
 * 平台运维角色（BYPASSRLS）的配置块被误挂在 {@code config:} 之下，
 * 于是它的有效键是 {@code config.platform-ops.datasource.enabled}，
 * 而生产代码读的是 {@code agent.platform-ops.datasource.enabled}（P6R1 文档口径）。
 * <b>读取静默落回默认值 {@code false}</b> —— 不报错、不告警、不影响启动，
 * 于是运维通道恒为「未接入」，PLATFORM 作用域发布恒为 fail-closed：
 * <blockquote>
 * 现象：配了 {@code AGENT_PLATFORM_OPS_ENABLED=true}、运维角色也建好了、
 * 口令也对，发布仍然报「平台运维角色未接入」。
 * </blockquote>
 * <b>真因离现场极远</b>：现场（发布失败）指向「运维凭据缺失」，
 * 真因却是 yml 里的<b>一个缩进层级</b>。
 *
 * <p>⚠️ <b>为什么 {@code @Value} 缺失会启动失败、而 {@code getProperty} 缺失却静默</b>：
 * 前者由 Spring 在装配期解析占位符，缺键直接抛 {@code IllegalStateException}；
 * 后者带默认值，缺键<b>无声落回默认值</b>。
 * 后者才是危险的那一类 —— <b>配置写了、代码读不到，且没有任何报错</b>。
 * 本类把这一类缺口全部纳入扫描。
 *
 * <p>⚠️ <b>本类不依赖数据库与 broker</b>：只读 yml 与源码，故可在 CI 无库时运行。
 */
class ApplicationConfigKeyAlignmentTest {

    /** 配置键的合法命名空间（其余命名空间不算配置键，忽略）。 */
    private static final List<String> NAMESPACES = List.of(
            "agent.", "auth.", "config.", "dr.", "deployment.", "management.", "spring.",
            // ⚠️ platform. 是「跨域执行票证」段的命名空间（顶层 platform.security.*）。
            //    不列进来这个守卫就**看不见**它 —— 键写错父级时依旧静默落回默认值，
            //    正是本类要拦的那一类故障。
            "platform.");

    /**
     * 由环境变量提供、不在 yml 中声明的键。
     *
     * <p>⚠️ 主数据源刻意不走 yml：{@code SPRING_DATASOURCE_*} 由 Spring Boot
     * 标准绑定读取（凭据生命周期与应用配置分开，见 P6R1 §3.1）。
     */
    private static final Set<String> ENV_PROVIDED = Set.of(
            "spring.datasource.url", "spring.datasource.username", "spring.datasource.password");

    /** {@code @Value("${key}")} 与 {@code @Value("${key:默认值}")}。 */
    private static final Pattern VALUE_PLACEHOLDER =
            Pattern.compile("\\$\\{([A-Za-z0-9_.\\-]+)(:[^}]*)?\\}");

    /** {@code KEY_XXX = "..."} 形式的配置键常量。 */
    private static final Pattern KEY_CONSTANT =
            Pattern.compile("KEY_[A-Z0-9_]+\\s*=\\s*((?:\"[^\"]*\"\\s*\\+?\\s*)+);");

    /** {@code environment.getProperty("...")}。 */
    private static final Pattern GET_PROPERTY =
            Pattern.compile("getProperty\\(\\s*\"([A-Za-z0-9_.\\-]+)\"");

    // ── 用例 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("⚠️⚠️ 平台运维配置键必须挂在 agent: 下（挂在 config: 下 = 运维通道恒未接入）")
    void platformOpsKeysLiveUnderAgentNamespace() {
        Set<String> keys = yamlKeys();

        for (String key : List.of(
                "agent.platform-ops.datasource.enabled",
                "agent.platform-ops.datasource.url",
                "agent.platform-ops.datasource.username",
                "agent.platform-ops.datasource.password",
                "agent.platform-ops.datasource.max-pool-size")) {
            assertThat(keys)
                    .as("⚠️⚠️ yml 缺少 %s —— 代码读它时只会静默落回默认值", key)
                    .contains(key);
        }

        assertThat(keys)
                .as("⚠️⚠️ yml 里仍存在 config.platform-ops.* —— 这是误挂在 config: 下的残留。"
                        + "代码读的是 agent.platform-ops.*，残留只会让人误以为「已配置」")
                .noneMatch(k -> k.startsWith("config.platform-ops."));
    }

    @Test
    @DisplayName("⚠️ 告警外发键必须挂在 agent: 下（挂在 auth: 下 = webhook 通道恒关闭）")
    void alertWebhookKeyLivesUnderAgentNamespace() {
        Set<String> keys = yamlKeys();

        assertThat(keys)
                .as("⚠️ yml 缺少 agent.alerts.webhook-url —— "
                        + "ApplicationConfiguration 读它时静默落回空串（通道关闭）")
                .contains("agent.alerts.webhook-url");
        assertThat(keys)
                .as("⚠️ yml 里仍存在 auth.alerts.webhook-url —— 误挂在 auth: 下的残留")
                .noneMatch(k -> k.startsWith("auth.alerts."));
    }

    @Test
    @DisplayName("⚠️⚠️ 执行票证开关必须挂在顶层 platform: 下")
    void executionTicketKeysLiveAtTopLevel() {
        Set<String> keys = yamlKeys();

        for (String key : List.of(
                "platform.security.execution-ticket.enabled",
                "platform.security.execution-ticket.ttl-seconds")) {
            assertThat(keys)
                    .as("⚠️⚠️ yml 缺少 %s —— 该键无默认值时启动失败，"
                            + "有默认值时「配了也不生效」且无任何报错", key)
                    .contains(key);
        }

        assertThat(keys)
                .as("⚠️ 执行票证段被误挂进 auth./agent./config. —— "
                        + "代码读的是顶层 platform.security.execution-ticket.*")
                .noneMatch(k -> k.startsWith("auth.security.execution-ticket")
                        || k.startsWith("agent.security.execution-ticket")
                        || k.startsWith("config.security.execution-ticket"));
    }

    @Test
    @DisplayName("⚠️⚠️ 代码读取的每一个配置键都必须在 yml 中真实存在")
    void everyConfigKeyReadByCodeExistsInYml() {
        Set<String> ymlKeys = yamlKeys();
        Map<String, List<String>> readBy = configKeysReadByCode();

        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : readBy.entrySet()) {
            String key = entry.getKey();
            if (ENV_PROVIDED.contains(key)) {
                continue;
            }
            if (ymlKeys.contains(key)) {
                continue;
            }
            // ⚠️ 映射型配置（如 agent.tool.risk-level-map）在 yml 里没有父键，
            //    只有 map 的叶子键（agent.tool.risk-level-map.get_student_profile）。
            boolean isMapParent = ymlKeys.stream().anyMatch(k -> k.startsWith(key + "."));
            if (isMapParent) {
                continue;
            }
            missing.add(key + "  ← " + String.join(", ", entry.getValue()));
        }

        assertThat(missing)
                .as("⚠️⚠️ 代码读了这些配置键，但 yml 里没有：\n%s\n"
                        + "读取会静默落回默认值 —— 「配了也不生效」且无任何报错。"
                        + "常见成因：yml 段被挂错父键（如 platform-ops 挂到 config: 下）。",
                        String.join("\n", missing))
                .isEmpty();
    }

    @Test
    @DisplayName("⚠️ yml 不得残留与代码读取口径不一致的同族键（误挂残留）")
    void noMisnestedResidueForKnownKeys() {
        Set<String> keys = yamlKeys();

        // ⚠️ 已知的两处历史误挂：父键写错后，旧键会留在 yml 里，
        //    让人误以为「已经配过了」，而代码读的是另一个键。
        assertThat(keys)
                .as("⚠️ config.platform-ops.* 是误挂残留")
                .noneMatch(k -> k.startsWith("config.platform-ops."));
        assertThat(keys)
                .as("⚠️ auth.alerts.* 是误挂残留")
                .noneMatch(k -> k.startsWith("auth.alerts."));
    }

    // ── 辅助 ──────────────────────────────────────────────────────

    /** 读 yml 并摊平成「点分键 → 值」。 */
    private static Set<String> yamlKeys() {
        Set<String> keys = new TreeSet<>();
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources;
        try {
            sources = loader.load("app", new FileSystemResource(
                    platformRoot().resolve("platform-bootstrap/src/main/resources/application.yml")));
        } catch (IOException e) {
            throw new IllegalStateException("读取 application.yml 失败", e);
        }
        for (PropertySource<?> source : sources) {
            Object value = source.getSource();
            if (value instanceof Map<?, ?> map) {
                map.keySet().forEach(k -> keys.add(String.valueOf(k)));
            }
        }
        return keys;
    }

    /**
     * 扫描各模块主源码，得出「代码读了哪些配置键 → 在哪儿读的」。
     *
     * <p>⚠️ <b>只剥注释、不剥字符串</b>：配置键就写在字符串里。
     * 注释里的示例会被剥掉（否则 javadoc 里的键会被误当成真实读取）。
     */
    private static Map<String, List<String>> configKeysReadByCode() {
        Map<String, List<String>> readBy = new LinkedHashMap<>();

        for (Path file : mainSourceFiles()) {
            String code = stripComments(read(file));
            String rel = relative(file);

            Matcher value = VALUE_PLACEHOLDER.matcher(code);
            while (value.find()) {
                // ⚠️ 带默认值的占位符不算「硬依赖」：缺键有明确的兜底语义。
                if (value.group(2) != null) {
                    continue;
                }
                record(readBy, value.group(1), rel);
            }

            Matcher constant = KEY_CONSTANT.matcher(code);
            while (constant.find()) {
                record(readBy, joinLiterals(constant.group(1)), rel);
            }

            Matcher getter = GET_PROPERTY.matcher(code);
            while (getter.find()) {
                record(readBy, getter.group(1), rel);
            }
        }
        return readBy;
    }

    private static void record(Map<String, List<String>> readBy, String key, String source) {
        if (key == null || key.isBlank() || !isConfigKey(key)) {
            return;
        }
        readBy.computeIfAbsent(key, k -> new ArrayList<>()).add(source);
    }

    private static boolean isConfigKey(String key) {
        return NAMESPACES.stream().anyMatch(key::startsWith);
    }

    /** 把 {@code "a" + "b"} 形式的字符串常量拼接求值。 */
    private static String joinLiterals(String expression) {
        Matcher literal = Pattern.compile("\"([^\"]*)\"").matcher(expression);
        StringBuilder sb = new StringBuilder();
        while (literal.find()) {
            sb.append(literal.group(1));
        }
        return sb.toString();
    }

    /** 收集各模块的主源码文件。 */
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
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取源码失败: " + file, e);
        }
    }

    /**
     * 剥离注释（保留字符串内容）。
     *
     * <p>⚠️ <b>为什么必须剥离</b>：javadoc 里常出现 {@code @Value("${xxx}")}
     * 这样的示例；若不剥离，它们会被当成「生产代码真的读了这个键」，
     * 守卫就会误报。
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
            if (c == '"') {
                out.append(c);
                i++;
                while (i < n && src.charAt(i) != '"') {
                    if (src.charAt(i) == '\\') {
                        out.append(src.charAt(i));
                        i++;
                    }
                    if (i < n) {
                        out.append(src.charAt(i));
                        i++;
                    }
                }
                if (i < n) {
                    out.append(src.charAt(i));
                    i++;
                }
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }
}
