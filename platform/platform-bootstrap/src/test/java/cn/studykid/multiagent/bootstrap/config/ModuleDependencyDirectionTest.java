package cn.studykid.multiagent.bootstrap.config;

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
 * 模块依赖方向约束测试（NFR-03「不可反转依赖」· 02 A1 / 03 §2.3 / 07 §3）。
 *
 * <p>⚠️ <b>P6 登记的缺口</b>：NFR-03 的验证方式是<b>静态扫描</b>，
 * 但此前只有 {@code AgentCorePurityTest}（框架纯度），
 * <b>没有任何模块级约束测试</b> ——
 * 「agent-worker 不得直连 infrastructure 持久层」这类规则只存在于约定里。
 *
 * <p>⚠️ <b>为什么这类约束必须机器化</b>：
 * 依赖方向一旦被反转，<b>编译仍然通过</b>，症状要到很久以后才显现
 * （领域层被 Spring 污染、内层认识外层、Worker 直接写库绕过账本）。
 * 那正是「越权防线被悄悄拆掉」的典型形态：<b>没有报错，只有后果</b>。
 *
 * <p>⚠️ <b>本类锁的四条规则</b>：
 * <ol>
 *   <li><b>领域层不认识任何人</b>：agent-core 的 pom 不得依赖任何其他 platform 模块；</li>
 *   <li><b>端口层不认识实现</b>：agent-ports 不得依赖应用层/基础设施层；</li>
 *   <li><b>应用层不认识基础设施</b>：依赖方向<b>不可反转</b> —— 端口定义在应用层，
 *       实现在基础设施层，应用层一旦 import 基础设施，端口就退化成实现细节；</li>
 *   <li><b>外层不直连持久层</b>：agent-worker / agent-api 不得 import
 *       {@code infrastructure.persistence.**} —— 直连持久层会绕过应用层的
 *       幂等、预算、账本与审计（P6 登记的原始诉求）。</li>
 * </ol>
 *
 * <p>⚠️ <b>白名单机制</b>：确有正当理由的例外必须显式登记并说明理由，
 * 否则白名单会迅速失效、让本测试退化成噪音。
 */
class ModuleDependencyDirectionTest {

    /** 允许 agent-ports 依赖的平台模块（端口层只认识领域层与测试支撑件）。 */
    private static final Set<String> PORTS_ALLOWED_PLATFORM_DEPS = Set.of(
            "agent-core", "agent-test-support");

    /** 允许 agent-application 依赖的平台模块（应用层不认识基础设施层）。 */
    private static final Set<String> APPLICATION_ALLOWED_PLATFORM_DEPS = Set.of(
            "agent-core", "agent-ports", "agent-test-support");

    /**
     * 允许外层模块引用的 infrastructure 包前缀。
     *
     * <p>⚠️ <b>刻意只放行非持久层</b>：{@code infrastructure.security} 是
     * 认证/授权的技术实现（API 层需要它来解析令牌），
     * 而 {@code infrastructure.persistence} 是<b>持久层</b>——
     * 直连它会绕过应用层的幂等、预算、账本与审计。
     */
    private static final Set<String> OUTER_ALLOWED_INFRA_PACKAGES = Set.of(
            "cn.studykid.multiagent.infrastructure.security");

    /** 编译期依赖块（区分 scope：test 作用域不算「依赖方向」）。 */
    private static final Pattern DEPENDENCY_BLOCK =
            Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL);

    private static final Pattern ARTIFACT = Pattern.compile("<artifactId>(.*?)</artifactId>");

    private static final Pattern SCOPE = Pattern.compile("<scope>(.*?)</scope>");

    private static final Pattern IMPORT = Pattern.compile(
            "^import\\s+(?:static\\s+)?([\\w.]+);", Pattern.MULTILINE);

    // ── pom 级规则 ───────────────────────────────────────────────

    @Test
    @DisplayName("✅ 领域层（agent-core）不依赖任何其他平台模块")
    void coreDependsOnNoOtherPlatformModule() {
        List<String> violations = new ArrayList<>();
        for (String dep : compileScopePlatformDeps("agent-core")) {
            violations.add("agent-core → " + dep);
        }

        assertThat(violations)
                .as("⛔ 领域层依赖了其他平台模块："
                        + "依赖方向一旦反转，最内层就再也换不掉了，"
                        + "且症状（框架污染）要到很久以后才显现")
                .isEmpty();
    }

    @Test
    @DisplayName("✅ 端口层（agent-ports）只认识领域层与测试支撑件")
    void portsDoesNotDependOnImplementations() {
        List<String> violations = new ArrayList<>();
        for (String dep : compileScopePlatformDeps("agent-ports")) {
            if (!PORTS_ALLOWED_PLATFORM_DEPS.contains(dep)) {
                violations.add("agent-ports → " + dep);
            }
        }

        assertThat(violations)
                .as("⛔ 端口层依赖了实现：端口一旦依赖实现，"
                        + "「定义在里层、实现在外层」就名存实亡")
                .isEmpty();
    }

    @Test
    @DisplayName("✅ 应用层（agent-application）不依赖基础设施层")
    void applicationDoesNotDependOnInfrastructure() {
        List<String> violations = new ArrayList<>();
        for (String dep : compileScopePlatformDeps("agent-application")) {
            if (!APPLICATION_ALLOWED_PLATFORM_DEPS.contains(dep)) {
                violations.add("agent-application → " + dep);
            }
        }

        assertThat(violations)
                .as("⛔ 应用层依赖了基础设施层：依赖方向反转，"
                        + "端口定义会退化成实现细节，内层再也抽不出来")
                .isEmpty();
    }

    // ── 源码级规则 ───────────────────────────────────────────────

    @Test
    @DisplayName("✅ 应用层主源码不引用 infrastructure 的任何类")
    void applicationSourcesDoNotReferenceInfrastructure() {
        List<String> violations = new ArrayList<>();
        for (String imported : importsOf("agent-application")) {
            if (imported.startsWith("cn.studykid.multiagent.infrastructure.")) {
                violations.add("agent-application → " + imported);
            }
        }

        assertThat(violations)
                .as("⛔ 应用层引用了基础设施实现：这是依赖方向反转最直接的证据")
                .isEmpty();
    }

    @Test
    @DisplayName("✅ 端口层主源码不引用 application / infrastructure 的任何类")
    void portsSourcesDoNotReferenceOuterLayers() {
        List<String> violations = new ArrayList<>();
        for (String imported : importsOf("agent-ports")) {
            if (imported.startsWith("cn.studykid.multiagent.application.")
                    || imported.startsWith("cn.studykid.multiagent.infrastructure.")) {
                violations.add("agent-ports → " + imported);
            }
        }

        assertThat(violations)
                .as("⛔ 端口层引用了外层实现")
                .isEmpty();
    }

    @Test
    @DisplayName("✅ agent-worker 不直连 infrastructure 持久层（P6 登记的原始诉求）")
    void workerDoesNotTouchPersistenceLayer() {
        List<String> violations = new ArrayList<>();
        for (String imported : importsOf("agent-worker")) {
            if (imported.startsWith("cn.studykid.multiagent.infrastructure.persistence")) {
                violations.add("agent-worker → " + imported);
            }
        }

        assertThat(violations)
                .as("⛔ agent-worker 直连了持久层："
                        + "直连会绕过应用层的幂等、预算预留、工具账本与决策审计 —— "
                        + "这四条正是本体系的核心防线")
                .isEmpty();
    }

    @Test
    @DisplayName("✅ agent-api 不直连 infrastructure 持久层")
    void apiDoesNotTouchPersistenceLayer() {
        List<String> violations = new ArrayList<>();
        for (String imported : importsOf("agent-api")) {
            if (!imported.startsWith("cn.studykid.multiagent.infrastructure.")) {
                continue;
            }
            boolean allowed = OUTER_ALLOWED_INFRA_PACKAGES.stream()
                    .anyMatch(prefix -> imported.startsWith(prefix + "."));
            if (!allowed) {
                violations.add("agent-api → " + imported);
            }
        }

        assertThat(violations)
                .as("⛔ agent-api 引用了 infrastructure 的非白名单包："
                        + "允许的只有 " + OUTER_ALLOWED_INFRA_PACKAGES
                        + "（认证实现）；持久层不在其中")
                .isEmpty();
    }

    // ── 辅助 ──────────────────────────────────────────────────────

    /**
     * 取某模块的<b>编译期</b>平台模块依赖。
     *
     * <p>⚠️ <b>只看非 test 作用域</b>：test 作用域的依赖（如 agent-test-support）
     * 不构成运行期的依赖方向 —— 它们只在测试期可见，
     * 不会把实现渗进产物。
     */
    private static List<String> compileScopePlatformDeps(String module) {
        List<String> deps = new ArrayList<>();
        Path pom = platformRoot().resolve(module).resolve("pom.xml");
        String xml = read(pom);
        Matcher matcher = DEPENDENCY_BLOCK.matcher(xml);
        while (matcher.find()) {
            String block = matcher.group(1);
            Matcher artifact = ARTIFACT.matcher(block);
            if (!artifact.find()) {
                continue;
            }
            String artifactId = artifact.group(1).trim();
            if (!artifactId.startsWith("agent-") && !artifactId.startsWith("platform-")) {
                continue;
            }
            Matcher scope = SCOPE.matcher(block);
            if (scope.find() && "test".equals(scope.group(1).trim())) {
                continue;
            }
            deps.add(artifactId);
        }
        return deps;
    }

    /** 取某模块主源码的 import 全限定名集合。 */
    private static List<String> importsOf(String module) {
        List<String> imports = new ArrayList<>();
        Path src = platformRoot().resolve(module).resolve("src/main/java");
        if (!Files.isDirectory(src)) {
            return imports;
        }
        try (Stream<Path> tree = Files.walk(src)) {
            for (Path file : tree.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher matcher = IMPORT.matcher(read(file));
                while (matcher.find()) {
                    imports.add(matcher.group(1));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("扫描模块源码失败: " + src, e);
        }
        return imports;
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
