package cn.studykid.multiagent.application.cap;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * §4.4 操作级规范化的<b>全部组合</b>覆盖（P4-T3 · 退出门禁 2）。
 *
 * <p>⚠️ <b>为什么必须穷举三态组合</b>：
 * {@code null} 与 {@code []} 的语义差一个数量级 ——
 * {@code null} = 「本层不设限，继续下层」，{@code []} = 「本层一个都不许」。
 * 任何一处把两者混同，结果都是<b>把显式收紧读成未配置</b>（fail-open）。
 * 组合数只有 3×3=9 种 —— 全列出来比抽样更便宜，也更可信。
 */
class McpOpAllowlistNormalizerTest {

    private static final Set<String> MANIFEST = Set.of("op-a", "op-b", "op-c");

    @Nested
    @DisplayName("服务端层（exposedToolAllowlist）")
    class ServerLayer {

        @Test
        @DisplayName("✅ null → 取清单全集（未配置 = 不设限）")
        void nullMeansUnbounded() {
            assertThat(McpOpAllowlistNormalizer.serverAllowedOps(null, MANIFEST))
                    .containsExactlyInAnyOrder("op-a", "op-b", "op-c");
        }

        @Test
        @DisplayName("✅ [] → 空集（显式收紧 = 一个都不许）")
        void emptyMeansNothingAllowed() {
            assertThat(McpOpAllowlistNormalizer.serverAllowedOps(List.of(), MANIFEST))
                    .as("⛔ 显式空清单被当成「未配置」= 把收紧读成放行")
                    .isEmpty();
        }

        @Test
        @DisplayName("✅ L → L ∩ ManifestOps（清单外的操作不可达）")
        void listIsIntersectedWithManifest() {
            assertThat(McpOpAllowlistNormalizer.serverAllowedOps(
                    List.of("op-a", "op-zzz"), MANIFEST))
                    .as("⛔ 清单外的操作被放行 = 固定清单形同虚设")
                    .containsExactly("op-a");
        }
    }

    @Nested
    @DisplayName("Skill 层（toolAllowlist）")
    class SkillLayer {

        @Test
        @DisplayName("✅ null → 沿用服务端层结果")
        void nullInheritsServerLayer() {
            assertThat(McpOpAllowlistNormalizer.skillAllowedOps(null, Set.of("op-a", "op-b")))
                    .containsExactlyInAnyOrder("op-a", "op-b");
        }

        @Test
        @DisplayName("✅ [] → 空集（显式收紧 = 一个都不许）")
        void emptyMeansNothingAllowed() {
            assertThat(McpOpAllowlistNormalizer.skillAllowedOps(List.of(), Set.of("op-a")))
                    .as("⛔ 显式空清单被当成「未配置」")
                    .isEmpty();
        }

        @Test
        @DisplayName("✅ L → L ∩ ServerAllowedOps")
        void listIsIntersectedWithServerLayer() {
            assertThat(McpOpAllowlistNormalizer.skillAllowedOps(
                    List.of("op-b", "op-zzz"), Set.of("op-a", "op-b")))
                    .containsExactly("op-b");
        }
    }

    @Nested
    @DisplayName("三态 × 三态 全部 9 种组合")
    class AllCombinations {

        /** 服务端三态的取值。 */
        private List<String> serverState(int i) {
            return switch (i) {
                case 0 -> null;
                case 1 -> List.of();
                default -> List.of("op-a", "op-b");
            };
        }

        /** Skill 三态的取值。 */
        private List<String> skillState(int i) {
            return switch (i) {
                case 0 -> null;
                case 1 -> List.of();
                default -> List.of("op-b", "op-c");
            };
        }

        @Test
        @DisplayName("✅ 9 种组合逐一验证（服务端 null/[]/L × Skill null/[]/L）")
        void allNineCombinations() {
            // 期望值按 §4.4 逐格推导：
            //   Server: null → {a,b,c}；[] → {}；L{a,b} → {a,b}
            //   Skill : null → 沿用服务端；[] → {}；L{b,c} → ∩ 服务端
            String[][] expected = {
                    {"op-a", "op-b", "op-c"}, {},               {"op-b", "op-c"},
                    {},                   {},               {},
                    {"op-a", "op-b"},      {},               {"op-b"}
            };

            for (int s = 0; s < 3; s++) {
                for (int k = 0; k < 3; k++) {
                    Set<String> serverAllowed = McpOpAllowlistNormalizer.serverAllowedOps(
                            serverState(s), MANIFEST);
                    Set<String> result = McpOpAllowlistNormalizer.skillAllowedOps(
                            skillState(k), serverAllowed);

                    assertThat(result)
                            .as("服务端状态=%s，Skill 状态=%s", s, k)
                            .containsExactlyInAnyOrder(expected[s * 3 + k]);
                }
            }
        }

        @Test
        @DisplayName("⚠️ null 与 [] 绝不可混同（两态的差别就是「全放行」与「全拒绝」）")
        void nullAndEmptyAreNeverEquivalent() {
            Set<String> viaNull = McpOpAllowlistNormalizer.serverAllowedOps(null, MANIFEST);
            Set<String> viaEmpty = McpOpAllowlistNormalizer.serverAllowedOps(List.of(), MANIFEST);

            assertThat(viaNull)
                    .as("⛔ null 与 [] 归一化成了同一个结果 = 三态语义已丢失")
                    .isNotEqualTo(viaEmpty);
            assertThat(viaNull).isNotEmpty();
            assertThat(viaEmpty).isEmpty();
        }
    }

    @Nested
    @DisplayName("结果的可复现性（指纹的前提）")
    class Reproducibility {

        @Test
        @DisplayName("✅ 结果有序且不可变（同一份配置必得同一份指纹）")
        void resultIsOrderedAndImmutable() {
            Set<String> first = McpOpAllowlistNormalizer.serverAllowedOps(
                    List.of("op-c", "op-a", "op-b"), MANIFEST);
            Set<String> second = McpOpAllowlistNormalizer.serverAllowedOps(
                    List.of("op-b", "op-c", "op-a"), MANIFEST);

            assertThat(first).isEqualTo(second);
            assertThat(List.copyOf(first)).containsExactly("op-a", "op-b", "op-c");
            org.assertj.core.api.Assertions
                    .assertThatThrownBy(() -> first.add("op-x"))
                    .as("⛔ 规范化结果可被修改 = 指纹输入不稳定")
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("✅ 空清单输入也返回不可变空集")
        void emptyManifestYieldsImmutableEmpty() {
            Set<String> result = McpOpAllowlistNormalizer.serverAllowedOps(null, Set.of());
            assertThat(result).isEmpty();
            org.assertj.core.api.Assertions
                    .assertThatThrownBy(() -> result.add("op-x"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
