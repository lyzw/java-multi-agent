package cn.studykid.multiagent.memory.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 记忆模块边界守卫（2026-11-06 收敛后新增）。
 *
 * <p>⚠️ <b>为什么需要它</b>：记忆代码此前散落在 5 个模块，靠人记「哪一层放什么」。
 * 收敛到 {@code agent-memory} 后，最大的回归风险不是编译失败，而是
 * <b>又有人把记忆类写回内层模块</b>，或<b>让内层反向依赖本模块</b> ——
 * 后者会形成 Maven 环（agent-memory → agent-infrastructure → ... → agent-memory），
 * 且「模块加了却没被组合根依赖」时症状是记忆静默关闭、<b>没有任何报错</b>。
 *
 * <p>⚠️ 本类只做静态扫描、不连库；判据刻意收窄，避免误报让守卫最终被人删掉：
 * <ul>
 *   <li>依赖方向只按 pom 里的 artifactId 判定，不猜字符串；</li>
 *   <li>「记忆类散落」只匹配<b>类声明</b>，不匹配字段/方法/注释里的 Memory 字样
 *       （{@code PromptGuard.MEMORY_OPEN}、{@code RunExecutionLoop.memoryRecallService}
 *       都是合法保留的协作点）。</li>
 * </ul>
 */
class MemoryModuleBoundaryTest {

    /** 内层模块：一律不得依赖 agent-memory（会成环或反转依赖方向）。 */
    private static final Set<String> FORBIDDEN_DEPENDENTS = Set.of(
            "agent-core", "agent-routing", "agent-ports", "agent-test-support",
            "agent-application", "agent-infrastructure", "agent-infra", "agent-api");

    /** 外层模块：必须显式依赖 agent-memory，否则记忆 Bean 不进运行时 classpath。 */
    private static final Set<String> REQUIRED_DEPENDENTS = Set.of(
            "agent-worker", "platform-bootstrap");

    /** 记忆专属类型：只允许在 agent-memory 的 src/main/java 里声明。 */
    private static final Pattern MEMORY_TYPE_DECLARATION = Pattern.compile(
            "^\\s*(?:public\\s+|final\\s+|abstract\\s+|sealed\\s+|non-sealed\\s+)*"
                    + "(?:class|record|interface|enum)\\s+"
                    + "(MemoryEntry|MemoryStrength|MemoryPort|EmbeddingPort|MemoryPolicy|"
                    + "MemoryRecallService|MemoryConsolidator|JdbcMemoryRepository|"
                    + "PgVectorAvailability|MemoryConfiguration)\\b",
            Pattern.MULTILINE);

    /**
     * 2026-11-06 数据库层二次收敛：这三类是<b>持久化实现/装配</b>，
     * 已随 JdbcMemoryRepository 一起迁入唯一数据库模块 agent-persistence。
     * 领域类型（MemoryEntry / MemoryPort / MemoryRecallService …）仍只允许在 agent-memory 声明。
     */
    private static final Set<String> PERSISTENCE_TYPES = Set.of(
            "JdbcMemoryRepository", "PgVectorAvailability", "MemoryConfiguration");

    private static final Pattern DEPENDENCY_BLOCK =
            Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL);
    private static final Pattern ARTIFACT = Pattern.compile("<artifactId>(.*?)</artifactId>");

    @Test
    @DisplayName("✅ 内层模块不得反向依赖 agent-memory（否则形成 Maven 环）")
    void innerModulesDoNotDependOnMemory() {
        List<String> violations = new ArrayList<>();
        for (String module : FORBIDDEN_DEPENDENTS) {
            if (declaresDependency(module, "agent-memory")) {
                violations.add(module + " → agent-memory");
            }
        }

        assertThat(violations)
                .as("⛔ 内层模块依赖了 agent-memory：本模块依赖 agent-infrastructure，"
                        + "内层再依赖回来就是环；即使 Maven 侥幸排开顺序，"
                        + "依赖方向也已经反转")
                .isEmpty();
    }

    @Test
    @DisplayName("✅ 组合根与执行引擎必须显式依赖 agent-memory（防「加了模块却没接线」）")
    void outerModulesWireMemoryModule() {
        List<String> missing = new ArrayList<>();
        for (String module : REQUIRED_DEPENDENTS) {
            if (!declaresDependency(module, "agent-memory")) {
                missing.add(module);
            }
        }

        assertThat(missing)
                .as("⛔ 缺依赖的模块在运行时看不到 MemoryConfiguration —— "
                        + "组件扫描扫不到就不会报错，只会表现为记忆永远是空的")
                .isEmpty();
    }

    @Test
    @DisplayName("✅ 记忆专属类型只在本模块声明（防「又写回内层模块」）")
    void memoryTypesStayInOneModule() {
        Path root = platformRoot();
        List<String> violations = new ArrayList<>();
        int[] scanned = {0, 0}; // [模块数, java 文件数]

        try (Stream<Path> modules = Files.list(root)) {
            for (Path module : modules.filter(Files::isDirectory).toList()) {
                String name = module.getFileName().toString();
                if ("agent-memory".equals(name) || "target".equals(name)) {
                    continue;
                }
                Path src = module.resolve("src/main/java");
                if (!Files.isDirectory(src)) {
                    continue;
                }
                scanned[0]++;
                try (Stream<Path> tree = Files.walk(src)) {
                    for (Path file : tree.filter(p -> p.toString().endsWith(".java")).toList()) {
                        scanned[1]++;
                        Matcher matcher = MEMORY_TYPE_DECLARATION.matcher(read(file));
                        while (matcher.find()) {
                            String type = matcher.group(1);
                            if ("agent-persistence".equals(name) && PERSISTENCE_TYPES.contains(type)) {
                                continue;
                            }
                            violations.add(name + ": " + type
                                    + " (" + root.relativize(file) + ")");
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("扫描模块源码失败: " + root, e);
        }

        // ⚠️ 防空转：路径发现失败会让空集恒绿（本项目踩过多次的假阴性形态）。
        assertThat(scanned[0]).as("扫描到的模块数必须 ≥ 8，否则是路径发现失败").isGreaterThanOrEqualTo(8);
        assertThat(scanned[1]).as("扫描到的主源码文件数必须 ≥ 100，否则是路径发现失败")
                .isGreaterThanOrEqualTo(100);

        assertThat(violations)
                .as("⛔ 记忆专属类型又散落回其它模块：后续记忆优化应当在 agent-memory 内闭环")
                .isEmpty();
    }

    // ── 辅助 ──────────────────────────────────────────────────────

    private static boolean declaresDependency(String module, String artifactId) {
        Path pom = platformRoot().resolve(module).resolve("pom.xml");
        if (!Files.exists(pom)) {
            return false;
        }
        Matcher block = DEPENDENCY_BLOCK.matcher(read(pom));
        while (block.find()) {
            Matcher artifact = ARTIFACT.matcher(block.group(1));
            if (artifact.find() && artifactId.equals(artifact.group(1).trim())) {
                return true;
            }
        }
        return false;
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

    private static String read(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取文件失败: " + file, e);
        }
    }
}
