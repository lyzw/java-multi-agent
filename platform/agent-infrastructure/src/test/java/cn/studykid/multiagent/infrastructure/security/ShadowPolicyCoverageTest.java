package cn.studykid.multiagent.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
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
 * <p>扫描目标：各仓库对 {@code shadowCompare / shadowTaint / shadowEnter} 的字面量首参
 * （即 {@code resourceType}）。这三个是仓库侧的本地辅助方法名，
 * 它们最终都委托 {@link AuthzShadowHooks}，所以扫描它们等价于扫描钩子入口。
 */
class ShadowPolicyCoverageTest {

    /** 仓库侧影子辅助方法的调用形态（字面量资源类型）。 */
    private static final Pattern SHADOW_CALL =
            Pattern.compile("\\bshadow(?:Compare|Taint|Enter)\\s*\\(\\s*\"([^\"]+)\"");

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
    @DisplayName("扫描器自证：注入未登记的资源类型必须被检出")
    void scannerDetectsUnknownResourceType() {
        String snippet = """
                void probe() {
                    shadowTaint("secret_new_table", tenantId, rs.getString("tenant_id"));
                    shadowCompare("agent", tenantId, true);
                }
                """;
        Set<String> found = new LinkedHashSet<>();
        Matcher m = SHADOW_CALL.matcher(snippet);
        while (m.find()) {
            found.add(m.group(1));
        }
        assertThat(found).containsExactly("secret_new_table", "agent");
        assertThat(found).contains("secret_new_table");
        assertThat(AuthzShadowPolicyDefaults.RESOURCE_TYPES).doesNotContain("secret_new_table");
    }

    // ── 扫描基础设施 ──────────────────────────────────────────────

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
                        Matcher m = SHADOW_CALL.matcher(Files.readString(f, StandardCharsets.UTF_8));
                        while (m.find()) {
                            out.add(m.group(1));
                        }
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
