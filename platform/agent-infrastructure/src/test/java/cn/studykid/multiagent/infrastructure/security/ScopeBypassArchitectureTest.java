package cn.studykid.multiagent.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 数据层越权「防绕过」架构守卫（12 文 §4.5 / D7 · 13 文 §3 红线 1）。
 *
 * <p>⚠️ <b>本类存在的唯一理由</b>：退役 RLS 后，数据库层不再兜底，
 * 「应用层漏写过滤」直接等于越权。类型系统（`ScopeFilter` 无 null 路径）只能保证
 * <b>拿到 filter 之后</b>不会退化，<b>拿不到 filter</b> 的那条路径它管不住。
 * 因此必须由代码扫描把三条可证伪命题钉死：
 *
 * <ol>
 *   <li><b>R3 不变式</b>：`ScopeFilter.Unrestricted` 的产出点<b>唯一</b>，
 *       且只可能是策略引擎在确认「主体是平台运维」之后才产出。
 *       任何业务代码直接 new 一个 `Unrestricted` 就是对隔离的彻底绕过。</li>
 *   <li><b>接线诚实性</b>：一个仓库如果声明自己「已接 Gate」（持有
 *       {@code ScopedDataAccess}），那它必须<b>真的</b>调用了影子钩子 ——
 *       只注入不调用的接线是装饰，影子期会给出「0 mismatch」的假绿。</li>
 *   <li><b>台账完整性</b>：新增的 Gate 持有者必须显式登记。
 *       未登记的持有者既不会被扫描，也不会被评审，是漏过滤的标准入口。</li>
 * </ol>
 *
 * <p>每条断言都配一个<b>反证用例</b>：把违规代码喂给扫描器，必须变红。
 * 若扫描器恒返回 false（正则写错 / 剥离逻辑吃掉全部代码），
 * 这些用例会先红 —— 避免「看起来在防守、实际什么都没检查」的假绿。
 */
class ScopeBypassArchitectureTest {

    /** `Unrestricted` 的唯一合法产出点（策略引擎，且前置 isPlatformOps 校验）。 */
    private static final Set<String> UNRESTRICTED_PRODUCERS = Set.of(
            "agent-infrastructure/src/main/java/cn/studykid/multiagent/infrastructure/security/RuleBasedPolicyEngine.java");

    /**
     * 已接 Gate 的仓库台账（D5 逐域改造进度）。
     *
     * <p>登记条件：持有 {@code ScopedDataAccess} 字段。
     * 一旦登记，必须<b>真的</b>在查询路径调用 {@code AuthzShadowHooks.*}；
     * 否则影子比对永远不跑，P1 的 `AUTHZ_MISMATCH=0` 毫无信息量。
     */
    private static final Set<String> INTEGRATED = Set.of(
            "agent-infrastructure/src/main/java/cn/studykid/multiagent/infrastructure/persistence/JdbcAgentMetricRepository.java",
            "agent-infrastructure/src/main/java/cn/studykid/multiagent/infrastructure/persistence/JdbcAgentQueryRepository.java",
            "agent-infrastructure/src/main/java/cn/studykid/multiagent/infrastructure/persistence/JdbcBudgetRepository.java",
            "agent-infrastructure/src/main/java/cn/studykid/multiagent/infrastructure/persistence/JdbcResultQueryRepository.java",
            "agent-infrastructure/src/main/java/cn/studykid/multiagent/infrastructure/persistence/cap/JdbcAuthzVersionRepository.java",
            "agent-infrastructure/src/main/java/cn/studykid/multiagent/infrastructure/persistence/cap/JdbcCapAgentChildRefRepository.java",
            "agent-infrastructure/src/main/java/cn/studykid/multiagent/infrastructure/persistence/cap/JdbcCapAgentDefRepository.java",
            "agent-infrastructure/src/main/java/cn/studykid/multiagent/infrastructure/persistence/cap/JdbcCapMcpServerDefRepository.java",
            "agent-infrastructure/src/main/java/cn/studykid/multiagent/infrastructure/persistence/cap/JdbcCapSkillDefRepository.java",
            "agent-infrastructure/src/main/java/cn/studykid/multiagent/infrastructure/persistence/cap/JdbcCapToolDefRepository.java",
            "agent-infrastructure/src/main/java/cn/studykid/multiagent/infrastructure/persistence/cap/JdbcConfigReleaseRepository.java",
            "agent-infrastructure/src/main/java/cn/studykid/multiagent/infrastructure/persistence/cap/JdbcDelegationBudgetRepository.java");

    /** 影子钩子的调用形态（静态入口，避免逐仓重复实现）。 */
    private static final Pattern HOOK_CALL =
            Pattern.compile("AuthzShadowHooks\\s*\\.\\s*(compare|taint)\\s*\\(");

    /** `Unrestricted` 构造形态。 */
    private static final Pattern UNRESTRICTED_NEW =
            Pattern.compile("new\\s+ScopeFilter\\s*\\.\\s*Unrestricted\\s*\\(");

    /** 持有 `ScopedDataAccess` 的形态（字段 / 参数 / 局部变量声明）。 */
    private static final Pattern SCOPED_HOLDER =
            Pattern.compile("\\bScopedDataAccess\\b\\s+[A-Za-z_]");

    // ── 静态扫描 ──────────────────────────────────────────────────

    @Nested
    @DisplayName("静态扫描")
    class StaticScan {

        @Test
        @DisplayName("⚠️⚠️ Unrestricted 的唯一产出点是策略引擎（R3 不变式）")
        void unrestrictedIsOnlyProducedByPolicyEngine() {
            List<String> violations = new ArrayList<>();

            for (Path file : mainSourceFiles()) {
                String code = stripCommentsAndStrings(read(file));
                if (!UNRESTRICTED_NEW.matcher(code).find()) {
                    continue;
                }
                String rel = relative(file);
                if (!UNRESTRICTED_PRODUCERS.contains(rel)) {
                    violations.add(rel);
                }
            }

            assertThat(violations)
                    .as("⚠️⚠️ 主源码中除策略引擎外不得构造 Unrestricted："
                            + "它等价于「不受任何租户约束」，业务代码一旦拿到就是全量越权")
                    .isEmpty();
        }

        @Test
        @DisplayName("⚠️⚠️ 已接 Gate 的仓库必须真的调用影子钩子（接线诚实性）")
        void integratedRepositoriesActuallyInvokeHooks() {
            List<String> violations = new ArrayList<>();

            for (String rel : INTEGRATED) {
                Path file = platformRoot().resolve(rel);
                if (!Files.exists(file)) {
                    violations.add(rel + "（文件不存在，台账漂移）");
                    continue;
                }
                String code = stripCommentsAndStrings(read(file));
                if (!HOOK_CALL.matcher(code).find()) {
                    violations.add(rel + "（持有 ScopedDataAccess 但从不调用 AuthzShadowHooks）");
                }
            }

            assertThat(violations)
                    .as("⚠️⚠️ 「注入但不调用」的接线是装饰：影子期会给出 0 违规的假绿，"
                            + "而 P1 出口条件正是依赖这个 0")
                    .isEmpty();
        }

        @Test
        @DisplayName("⚠️ 持有 ScopedDataAccess 的类必须登记在台账内（防漏过滤入口）")
        void scopedHoldersAreRegistered() {
            List<String> unknown = new ArrayList<>();

            for (Path file : mainSourceFiles()) {
                String code = stripCommentsAndStrings(read(file));
                if (!SCOPED_HOLDER.matcher(code).find()) {
                    continue;
                }
                String rel = relative(file);
                if (INTEGRATED.contains(rel) || NON_REPO_SURFACES.contains(rel)
                        || isScopeInfrastructure(rel)) {
                    continue;
                }
                unknown.add(rel);
            }

            assertThat(unknown)
                    .as("⚠️ 新增的 Gate 持有者必须显式登记：要么进 INTEGRATED 台账并真的调用钩子，"
                            + "要么进 NON_REPO_SURFACES 并说明豁免理由")
                    .isEmpty();
        }

        @Test
        @DisplayName("✅ 台账无漂移：登记的每个文件都真实存在")
        void inventoryHasNoDrift() {
            List<String> missing = new ArrayList<>();
            for (String rel : INTEGRATED) {
                if (!Files.exists(platformRoot().resolve(rel))) {
                    missing.add(rel);
                }
            }
            for (String rel : UNRESTRICTED_PRODUCERS) {
                if (!Files.exists(platformRoot().resolve(rel))) {
                    missing.add(rel);
                }
            }

            assertThat(missing)
                    .as("台账里的路径必须真实存在，否则守卫在保护一个已经不存在的文件（静默失效）")
                    .isEmpty();
        }
    }

    // ── 扫描器自证（反证） ────────────────────────────────────────

    @Nested
    @DisplayName("扫描器自证")
    class ScannerSelfProof {

        @Test
        @DisplayName("⚠️⚠️ 注入的业务侧 Unrestricted 必须被检出")
        void detectsInjectedUnrestricted() {
            String violating = """
                    package evil;
                    import cn.studykid.multiagent.core.security.ScopeFilter;
                    class Evil {
                        ScopeFilter leak() {
                            return new ScopeFilter.Unrestricted("方便一下");
                        }
                    }
                    """;

            assertThat(UNRESTRICTED_NEW.matcher(stripCommentsAndStrings(violating)).find())
                    .as("⚠️⚠️ 注入的越权构造必须被检出，否则 R3 守卫是空转")
                    .isTrue();
        }

        @Test
        @DisplayName("⚠️⚠️ 注入的「持有 Gate 但从不调用钩子」必须被检出")
        void detectsSilentIntegration() {
            String violating = """
                    package evil;
                    class EvilRepository {
                        private final ScopedDataAccess scoped;
                        EvilRepository(ScopedDataAccess scoped) { this.scoped = scoped; }
                        void read() { /* 只注入，从不调用 AuthzShadowHooks */ }
                    }
                    """;

            String code = stripCommentsAndStrings(violating);
            assertThat(SCOPED_HOLDER.matcher(code).find())
                    .as("必须识别出 Gate 持有者")
                    .isTrue();
            assertThat(HOOK_CALL.matcher(code).find())
                    .as("⚠️⚠️ 未调用钩子的接线必须被判为未接线")
                    .isFalse();
        }

        @Test
        @DisplayName("✅ 合规接线不误报（负样本）")
        void doesNotFlagCompliantIntegration() {
            String compliant = """
                    package ok;
                    class OkRepository {
                        private final ScopedDataAccess scoped;
                        void read() {
                            AuthzShadowHooks.compare(scoped, reporter, true, "agent",
                                    subject, tenantId, true);
                        }
                    }
                    """;

            String code = stripCommentsAndStrings(compliant);
            assertThat(HOOK_CALL.matcher(code).find())
                    .as("正常调用钩子不得被误判")
                    .isTrue();
            assertThat(UNRESTRICTED_NEW.matcher(code).find())
                    .as("正常代码不得被误判为越权构造")
                    .isFalse();
        }
    }

    // ── 扫描基础设施 ──────────────────────────────────────────────

    /**
     * 豁免名单：持有 `ScopedDataAccess` 但<b>不是「仓库」</b>的基础设施 / 装配类。
     *
     * <p>这些类提供接缝本身（被仓库注入），不直接查询业务表。
     */
    private static final Set<String> NON_REPO_SURFACES = Set.of(
            "agent-infrastructure/src/main/java/cn/studykid/multiagent/infrastructure/security/DefaultAccessGate.java",
            "agent-infrastructure/src/main/java/cn/studykid/multiagent/infrastructure/security/AuthzShadowHooks.java",
            // P1 影子的装配层：构造 ScopedDataAccess 并交给各仓库，
            // 自身不查询任何业务表（装配 ≠ 数据访问面）。
            "platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/config/AuthzShadowConfiguration.java");

    /** 鉴权层自身（接缝与门禁实现），不属于数据访问面。 */
    private static boolean isScopeInfrastructure(String rel) {
        return rel.startsWith(
                "agent-infrastructure/src/main/java/cn/studykid/multiagent/infrastructure/security/")
                && !rel.endsWith("Test.java")
                && (rel.endsWith("AccessGate.java")
                    || rel.endsWith("ScopedDataAccess.java")
                    || rel.endsWith("AuthzShadowReporter.java")
                    || rel.endsWith("AuthzShadowBinding.java")
                    || rel.endsWith("AuthzSamplingPolicy.java")
                    || rel.endsWith("AuthzShadowPolicyDefaults.java")
                    || rel.endsWith("AuthzMismatchMetrics.java")
                    || rel.endsWith("InMemoryAuthzMismatchMetrics.java")
                    || rel.endsWith("Slf4jAuthzMismatchMetrics.java")
                    || rel.endsWith("AuthzDecisionRecorder.java")
                    || rel.endsWith("NoOpAuthzDecisionRecorder.java")
                    || rel.endsWith("InMemoryAuthzDecisionRecorder.java")
                    || rel.endsWith("JdbcAuthzDecisionLog.java")
                    || rel.endsWith("PolicyStore.java")
                    || rel.endsWith("InMemoryPolicyStore.java")
                    || rel.endsWith("PolicyRule.java")
                    || rel.endsWith("RuleBasedPolicyEngine.java")
                    || rel.endsWith("SubjectContextResolver.java")
                    || rel.endsWith("AccessDeniedException.java")
                    || rel.endsWith("IllegalPolicyException.java")
                    || rel.endsWith("ScopeRequirement.java")
                    || rel.endsWith("ScopeEffect.java"));
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

    /** 定位 platform 源码根目录（从当前工作目录向上找含 {@code agent-core} 的目录）。 */
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
        return platformRoot().relativize(file.toAbsolutePath()).toString()
                .replace('\\', '/');
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取源码失败: " + file, e);
        }
    }

    /**
     * 去掉注释与字符串字面量，只留<b>可执行代码</b>。
     *
     * <p>⚠️ 必须连字符串一起去掉：本仓大量 Javadoc 与错误提示里写着
     * {@code Unrestricted} / {@code ScopedDataAccess}，只去注释会把这些
     * 「只是提到这个词」误判成「真的构造 / 真的持有」。
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
