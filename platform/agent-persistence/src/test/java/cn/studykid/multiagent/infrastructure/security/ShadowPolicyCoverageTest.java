package cn.studykid.multiagent.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 影子期策略<b>覆盖面</b>守卫（P1 接线的一部分）。
 *
 * <p>⚠️ <b>本类存在的唯一理由</b>：{@code RuleBasedPolicyEngine} 对「无匹配规则」返回
 * {@code Deny(NO_RULE)}（fail-closed，行为正确）。但影子期一旦某个资源类型忘了登记规则，
 * 该类型的<b>每一次</b>查询都会落到「Gate 拒绝」这条 mismatch 上 ——
 * 于是 {@code AUTHZ_MISMATCH} 恒大于 0，真实违例被恒定噪声淹没，
 * 而人看到的是「有 mismatch，可能是配置问题」，于是 P1 出口条件永远达不成，
 * 最后的结果通常是「把告警阈值调松」这种把防线拆掉的做法。
 *
 * <p>所以接线时必须把「钩子用到的资源类型 ⊆ 已登记规则」变成编译期之外的第二道硬约束：
 * 新增一个 {@code shadowTaint("新表", ...)} 而不登记规则，<b>本测试直接红</b>。
 *
 * <p>扫描目标：各仓库对 {@code shadowCompare / shadowTaint / shadowEnter} 的首参
 * （即 {@code resourceType}）。这三个是仓库侧的本地辅助方法名，
 * 它们最终都委托 {@link AuthzShadowHooks}，所以扫描它们等价于扫描钩子入口。
 *
 * <p>⚠️ <b>首参可以是「常量标识符」，不一定是字面量</b>（2026-11-09 实测踩坑）：
 * <blockquote>
 * 早期版本只用 {@code shadow(Compare|Taint|Enter)\s*\(\s*"([^"]+)"} 扫<b>字面量</b>，
 * 于是 {@code private static final String RESOURCE_TYPE = "cap_tool_def";}
 * 这类<b>常量传参</b>的仓库完全扫不到 ⇒ 漏登记无从发现 ⇒
 * 4 个 CAP 仓（cap_agent_def / cap_agent_child_ref / cap_authz_version / cap_config_release）
 * 在生产影子态下恒返 {@code Deny(NO_RULE)} ⇒ <b>AUTHZ_MISMATCH 恒非零</b>，
 * P1 出口门禁（连续 2 周 =0）永远达不成。
 * </blockquote>
 * <b>本扫描器因此必须解析常量</b>：首参是标识符时，回查同文件的 {@code String 常量 = "字面量"} 定义。
 */
class ShadowPolicyCoverageTest {

    /**
     * 仓库侧影子辅助方法的调用形态。
     *
     * <p>首参捕获到第一个 {@code ,} 或 {@code )} 为止：
     * <ul>
     *   <li>字面量：{@code shadowCompare("agent", ...)} ⇒ 首参 = {@code "agent"}；</li>
     *   <li>常量：{@code shadowCompare(RESOURCE_TYPE, ...)} ⇒ 首参 = {@code RESOURCE_TYPE}；</li>
     *   <li>方法声明：{@code void shadowCompare(@NonNull String resourceType, ...)}
     *       ⇒ 首参 = {@code @NonNull String resourceType}（既非字面量也非裸标识符 ⇒ 忽略）。</li>
     * </ul>
     */
    private static final Pattern SHADOW_CALL =
            Pattern.compile("\\bshadow(?:Compare|Taint|Enter)\\s*\\(\\s*([^,)]*)");

    /** 同文件内的 {@code String 常量 = "字面量"} 定义（用于把标识符首参解析成字面量）。 */
    private static final Pattern STRING_CONSTANT =
            Pattern.compile("\\bString\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*\"([^\"]+)\"");

    /** 裸标识符（可回查常量表）。 */
    private static final Pattern BARE_IDENTIFIER =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    @Test
    @DisplayName("⚠️ 钩子用到的每个资源类型都必须有内置策略规则（否则恒定 NO_RULE 假 mismatch）")
    void everyHookResourceTypeHasRule() {
        Set<String> used = collectUsedResourceTypes();

        assertThat(used)
                .as("至少要扫到一些调用，否则本守卫是空转（正则写错/目录变了）")
                .isNotEmpty();

        List<String> missing = used.stream()
                .filter(t -> !AuthzShadowPolicyDefaults.RESOURCE_TYPES.contains(t))
                .sorted()
                .toList();

        assertThat(missing)
                .as("⚠️ 这些资源类型没有对应策略规则：影子期每次查询都会被算成 NO_RULE mismatch。"
                        + "请在 AuthzShadowPolicyDefaults 登记（并与 authz/policy-mapping.yaml 对齐）")
                .isEmpty();
    }

    @Test
    @DisplayName("⚠️ 平台级目录类型不得被租户级影子观测（无 tenant_id ⇒ 染色无从谈起）")
    void platformLevelTypesAreNotObserved() {
        Set<String> used = collectUsedResourceTypes();

        List<String> misused = used.stream()
                .filter(AuthzShadowPolicyDefaults.PLATFORM_LEVEL_RESOURCE_TYPES::contains)
                .sorted()
                .toList();

        assertThat(misused)
                .as("⚠️ 这些是平台级对象（物理表没有 tenant_id 列、无 RLS）："
                        + "用租户级影子观测它们会制造恒定假违例。"
                        + "正确做法是走平台级运维通道并保持其仓储不持有 ScopedDataAccess")
                .isEmpty();
    }

    @Test
    @DisplayName("内置规则本身合法（R3 不变式 + 无重复）")
    void defaultsAreValid() {
        // store() 构造期会校验 UNRESTRICTED 只能配 PLATFORM
        List<PolicyRule> rules = AuthzShadowPolicyDefaults.store().load();

        assertThat(rules).isNotEmpty();
        assertThat(rules).extracting(PolicyRule::resourceType).doesNotHaveDuplicates();
        assertThat(rules).extracting(PolicyRule::effect)
                .as("P1 影子期只覆盖租户内读路径；出现 UNRESTRICTED/WITH_SHARED 需要单独评审")
                .containsOnly(ScopeEffect.TENANT_ONLY);
    }

    @Test
    @DisplayName("✅ 平台级类型与已登记类型不重叠（否则「刻意豁免」与「已接 Gate」自相矛盾）")
    void platformLevelTypesAreDisjointFromRegistered() {
        assertThat(AuthzShadowPolicyDefaults.PLATFORM_LEVEL_RESOURCE_TYPES)
                .as("平台级类型一旦同时出现在已登记列表里，就等于既声称「无租户归属」又声称「按租户收口」")
                .doesNotContainAnyElementsOf(AuthzShadowPolicyDefaults.RESOURCE_TYPES);
    }

    @Test
    @DisplayName("扫描器自证：注入未登记的资源类型必须被检出")
    void scannerDetectsUnknownResourceType() {
        String snippet = """
                void probe() {
                    shadowTaint("secret_new_table", tenantId, rs.getString("tenant_id"));
                    shadowCompare("agent", tenantId, true);
                }
                """;
        Set<String> found = collectFromSource(snippet);
        assertThat(found).containsExactly("secret_new_table", "agent");
        assertThat(found).contains("secret_new_table");
        assertThat(AuthzShadowPolicyDefaults.RESOURCE_TYPES).doesNotContain("secret_new_table");
    }

    @Test
    @DisplayName("⚠️⚠️ 扫描器自证：常量传参的资源类型必须被检出（否则守卫对常量仓库完全失明）")
    void scannerDetectsConstantResourceType() {
        // 这正是 2026-11-09 实测踩中的形态：CAP 仓用 RESOURCE_TYPE 常量传参。
        String snippet = """
                private static final String RESOURCE_TYPE = "secret_constant_table";
                void probe() {
                    shadowCompare(RESOURCE_TYPE, tenantId, true);
                    shadowTaint(RESOURCE_TYPE, tenantId, rs.getString("tenant_id"));
                }
                """;
        Set<String> found = collectFromSource(snippet);

        assertThat(found)
                .as("⚠️⚠️ 常量传参必须被解析成字面量并纳入校验，"
                        + "否则漏登记的常量类型会绕过本守卫、直到生产影子态才以恒定 NO_RULE 噪声暴露")
                .containsExactly("secret_constant_table");
        assertThat(AuthzShadowPolicyDefaults.RESOURCE_TYPES).doesNotContain("secret_constant_table");
    }

    @Test
    @DisplayName("✅ 扫描器不把方法声明误判成调用（声明首参是类型+形参名，不是资源类型）")
    void scannerIgnoresMethodDeclarations() {
        String snippet = """
                void shadowCompare(@NonNull String resourceType, @NonNull String tenantId) { }
                void shadowTaint(@NonNull String resourceType, @NonNull String subject, @Nullable String row) { }
                """;

        assertThat(collectFromSource(snippet))
                .as("方法声明不是调用点；把它算成「用到的资源类型」会凭空造出噪声条目")
                .isEmpty();
    }

    // ── 扫描基础设施 ──────────────────────────────────────────────

    /** 扫描单个源文件，返回它用到的资源类型（字面量 + 常量解析）。 */
    private static Set<String> collectFromSource(String source) {
        Set<String> out = new LinkedHashSet<>();
        Map<String, String> constants = localStringConstants(source);

        Matcher m = SHADOW_CALL.matcher(source);
        while (m.find()) {
            String resolved = resolve(m.group(1), constants);
            if (resolved != null) {
                out.add(resolved);
            }
        }
        return out;
    }

    /**
     * 解析影子钩子首参。
     *
     * @param rawArg    首参原文（未 trim 前的切片段）
     * @param constants 同文件的 {@code String 常量 → 字面量} 表
     * @return 解析出的资源类型；无法解析时返回 {@code null}（例如方法声明的形参）
     */
    private static String resolve(String rawArg, Map<String, String> constants) {
        String arg = rawArg.trim();
        if (arg.length() >= 2 && arg.startsWith("\"") && arg.endsWith("\"")) {
            return arg.substring(1, arg.length() - 1);
        }
        if (BARE_IDENTIFIER.matcher(arg).matches()) {
            return constants.get(arg);
        }
        return null;
    }

    /** 收集源文件内的 {@code String 常量 = "字面量"} 定义。 */
    private static Map<String, String> localStringConstants(String source) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = STRING_CONSTANT.matcher(source);
        while (m.find()) {
            out.putIfAbsent(m.group(1), m.group(2));
        }
        return out;
    }

    private static Set<String> collectUsedResourceTypes() {
        Set<String> out = new LinkedHashSet<>();
        Path root = platformRoot();
        try (Stream<Path> modules = Files.list(root)) {
            for (Path module : modules.toList()) {
                Path src = module.resolve("src/main/java");
                if (!Files.isDirectory(src)) {
                    continue;
                }
                try (Stream<Path> tree = Files.walk(src)) {
                    for (Path f : tree.filter(p -> p.toString().endsWith(".java")).toList()) {
                        out.addAll(collectFromSource(Files.readString(f, StandardCharsets.UTF_8)));
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("扫描影子钩子调用失败: " + root, e);
        }
        return out;
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
}
