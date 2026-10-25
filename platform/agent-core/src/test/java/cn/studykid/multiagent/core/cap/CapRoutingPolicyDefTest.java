package cn.studykid.multiagent.core.cap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.orchestration.RoutingStrategy;

/**
 * 路由策略定义行的领域约束（多级 Agent v1.1 · 第十四批）。
 *
 * <p><b>⚠️ 为什么这些约束必须钉在领域层</b>：路由策略是<b>发布期固化的配置事实</b>。
 * 若「目标集合为空」「目标重复」这类缺陷能落库，
 * 运行期就会拿到一份「允许交给谁都说不清」的策略 ——
 * 而路由失败往往表现为「委派被拒」，排查现场离真因很远。
 */
class CapRoutingPolicyDefTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private CapRoutingPolicyDef def(RoutingStrategy strategy, List<String> targets) {
        return new CapRoutingPolicyDef("t-1", "orders-router", 1, strategy, targets,
                "fp-1", 1, NOW, NOW);
    }

    @Test
    @DisplayName("策略必须显式声明允许的目标：空集合不是「允许任意」")
    void emptyTargetsRejected() {
        assertThatThrownBy(() -> def(RoutingStrategy.EXPLICIT_TARGETS, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targets 不能为空");
    }

    @Test
    @DisplayName("目标集合不得有重复项（否则有序降级的首选变得不确定）")
    void duplicateTargetsRejected() {
        assertThatThrownBy(() -> def(RoutingStrategy.ORDERED_FALLBACK, List.of("a", "b", "a")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targets 元素重复");
    }

    @Test
    @DisplayName("目标元素不得为空白")
    void blankTargetRejected() {
        assertThatThrownBy(() -> def(RoutingStrategy.EXPLICIT_TARGETS, List.of("a", "  ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targets 元素不能为空");
    }

    @Test
    @DisplayName("版本号必须为正数")
    void nonPositiveVersionRejected() {
        assertThatThrownBy(() -> new CapRoutingPolicyDef("t-1", "p", 0,
                RoutingStrategy.EXPLICIT_TARGETS, List.of("a"), "fp", 1, NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version 必须为正数");
    }

    @Test
    @DisplayName("固定版本引用形如 id@version；草稿不可被引用")
    void refAndPublishedSemantics() {
        CapRoutingPolicyDef published = def(RoutingStrategy.EXPLICIT_TARGETS, List.of("a"));
        assertThat(published.ref()).isEqualTo("orders-router@1");
        assertThat(published.isPublished()).isTrue();

        CapRoutingPolicyDef draft = new CapRoutingPolicyDef("t-1", "orders-router", 1,
                RoutingStrategy.EXPLICIT_TARGETS, List.of("a"), "fp", 1, NOW, null);
        assertThat(draft.isPublished())
                .as("草稿不可被引用（先引用再发布是绕过发布流程的后门）")
                .isFalse();
    }

    @Test
    @DisplayName("allows 只认策略声明的目标")
    void allowsOnlyDeclaredTargets() {
        CapRoutingPolicyDef policy = def(RoutingStrategy.EXPLICIT_TARGETS, List.of("a", "b"));
        assertThat(policy.allows("a")).isTrue();
        assertThat(policy.allows("b")).isTrue();
        assertThat(policy.allows("c"))
                .as("不在策略集合内的目标一律不允许")
                .isFalse();
    }
}
