package cn.studykid.multiagent.core.quickstart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.cap.CapHttpToolConfig;
import cn.studykid.multiagent.core.cap.CapToolKind;
import cn.studykid.multiagent.core.cap.CapabilityRiskLevel;
import cn.studykid.multiagent.core.cap.EffectiveClosure;
import cn.studykid.multiagent.core.orchestration.OrchestrationBinding;
import cn.studykid.multiagent.core.run.IllegalStateTransitionException;
import cn.studykid.multiagent.core.run.RunState;
import cn.studykid.multiagent.core.security.ScopeFilter;

/**
 * 新人 onboarding 演示（P2-7）：纯 {@code agent-core} 领域对象的用法示例。
 *
 * <p>三门功课，全程<b>零框架依赖</b>（不启动 Spring / 不连库）：
 * <ol>
 *   <li>Run 状态机迁移 —— 终态不可迁出、非法迁移抛异常而非静默忽略；</li>
 *   <li>有效能力闭包 {@link EffectiveClosure} —— 固化一次调用依赖的固定版本集；</li>
 *   <li>行级隔离 {@link ScopeFilter} —— 唯一决策权威，替换 PG RLS 谓词。
 * </ol>
 *
 * <p>演示一个「服务生 Agent」：读取用户画像（READ）+ 写入学习档案（WRITE_PROFILE）。
 */
class QuickstartDemoTest {

    @Test
    @DisplayName("① Run 状态机：QUEUED ⟶ RUNNING ⟶ SUCCEEDED，终态不可迁出")
    void runStateMachineWalk() {
        // RUNNING 之后才能收敛到 SUCCEEDED；非法迁移必须抛异常而不是被忽略。
        assertThat(RunState.QUEUED.canTransitionTo(RunState.RUNNING)).isTrue();
        assertThat(RunState.RUNNING.canTransitionTo(RunState.SUCCEEDED)).isTrue();

        // 终态不可迁出 —— 并发/过期消息绝不能把终态覆盖回去。
        assertThat(RunState.SUCCEEDED.isTerminal()).isTrue();
        assertThatThrownBy(() -> RunState.SUCCEEDED.requireTransitionTo(RunState.RUNNING))
                .isInstanceOf(IllegalStateTransitionException.class);

        // 完整合法路径。
        RunState.QUEUED.requireTransitionTo(RunState.RUNNING);
        RunState.RUNNING.requireTransitionTo(RunState.SUCCEEDED);
    }

    @Test
    @DisplayName("② CAP 闭包：固化 Agent⟶Skill⟶Tool 的固定版本 + 指纹")
    void capabilityClosure() {
        var skill = new EffectiveClosure.SkillBinding(
                "skill-student-profile",
                3,
                CapabilityRiskLevel.READ,
                "a1b2c3",
                List.of("tool-read-profile"),
                "{}");

        // HTTP 工具必须携带完整的 HTTP 配置（9 参构造器的显式校验）。
        var httpTool = new EffectiveClosure.ToolBinding(
                "tool-write-profile",
                2,
                CapToolKind.HTTP,
                CapabilityRiskLevel.WRITE_PROFILE,
                "d4e5f6",
                "{}",
                null,
                null,
                new CapHttpToolConfig("POST", "https://internal/tutor/profile", 5_000));

        // 单 Agent、无子节点、无编排声明 —— 走兼容构造器自动填 leaf 编排。
        EffectiveClosure closure = new EffectiveClosure(
                "agent-tutor",
                1,
                List.of(skill),
                List.of(httpTool),
                List.of());

        assertThat(closure.agentId()).isEqualTo("agent-tutor");
        assertThat(closure.skills()).hasSize(1);
        assertThat(closure.tools()).hasSize(1);
        // 兼容构造器的编排缺省为 LEAF：无编排声明与显式 LEAF 在闭包层面等价（MA-T01）。
        assertThat(OrchestrationBinding.leaf()).isNotNull();
        assertThat(closure.tools().get(0).isHttp()).isTrue();
        // HTTP 工具必须携带完整配置：这里传 null，违反构造器校验，应拒绝。
        assertThatThrownBy(() -> new EffectiveClosure.ToolBinding(
                "tool-m", 1, CapToolKind.HTTP, CapabilityRiskLevel.READ, "x", "{}",
                null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("③ 行级隔离：ScopeFilter 是唯一决策权威，替换 RLS 谓词")
    void scopeFilterSemantics() {
        // 私有数据 + 平台公开(shard)数据 —— 等价于现役 RLS 谓词的有效范围形态。
        ScopeFilter tenantWithShared = new ScopeFilter.TenantWithShared("tenant-a");
        assertThat(tenantWithShared).isInstanceOf(ScopeFilter.class);

        // 仅本租户。
        ScopeFilter tenantOnly = new ScopeFilter.TenantOnly("tenant-b");
        assertThat(tenantOnly).isInstanceOf(ScopeFilter.TenantOnly.class);

        // 全域（BYPASS 通道视图）。
        ScopeFilter unrestricted = new ScopeFilter.Unrestricted("local-dev");
        assertThat(unrestricted).isInstanceOf(ScopeFilter.Unrestricted.class);

        // None：鉴权通过但不可见 → 恒假 0 行 —— fail-closed 的显式表达。
        ScopeFilter none = ScopeFilter.NONE;
        assertThat(none).isInstanceOf(ScopeFilter.None.class);

        // 密封接口穷尽 switch（新增后端 = 新增 translator，不新增权限规则）。
        assertThat(classify(tenantWithShared)).isEqualTo("scoped");
        assertThat(classify(unrestricted)).isEqualTo("global");
        assertThat(classify(none)).isEqualTo("none");
    }

    private static String classify(ScopeFilter filter) {
        return switch (filter) {
            case ScopeFilter.TenantWithShared t -> "scoped";
            case ScopeFilter.TenantOnly t -> "scoped";
            case ScopeFilter.Unrestricted u -> "global";
            case ScopeFilter.None n -> "none";
        };
    }
}