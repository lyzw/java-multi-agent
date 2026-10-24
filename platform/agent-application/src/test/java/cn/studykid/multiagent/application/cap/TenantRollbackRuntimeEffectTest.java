package cn.studykid.multiagent.application.cap;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.cap.CapabilityRiskLevel;
import cn.studykid.multiagent.core.cap.EffectiveClosure;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 租户回滚对 Agent 运行期生效性测试（P0-5）。
 *
 * <p>⚠️ <b>本类存在的唯一理由</b>：P0-5 是一个
 * <b>「回滚返回成功、审计记录完整、运行期却一点没变」</b>的缺陷。
 *
 * <p>根因在版本解析口径：
 * <ul>
 *   <li>修复前：{@code PermissionResolver.resolveCurrent} 取
 *       {@code CapAgentDefPort.findLatestPublished} ——「该 Agent 最新已发布版本行」；</li>
 *   <li>但版本行是<b>不可变</b>的（§6.1），而回滚<b>只移指针、不删版本行</b>（§6.3 第 6 条）——
 *       于是回滚之后，「最新已发布版本行」仍然是<b>被回滚掉的那个版本</b>；</li>
 *   <li>结果：回滚在治理侧生效（发布头标记 ROLLED_BACK + 指针已切），
 *       在运行期<b>完全无效</b> —— Agent 的实际生效版本一点没变，
 *       闭包指纹不变，授权照旧按「回滚前的配置」放行。</li>
 * </ul>
 *
 * <p>正确口径只有一条（§6.3 第 2 条）：
 * <pre>TENANT 指针 → 指针指向的 PUBLISHED release → object_versions → agent id → 精确版本</pre>
 *
 * <p>⚠️ <b>为什么 {@code PermissionResolverTest} 抓不到</b>：
 * 它测的是「给定版本号能否解析出闭包」（{@code resolve}），
 * 而本缺陷在<b>「当前版本」的口径</b>上（{@code resolveCurrent}）——
 * 被测单元正确，调用方取数取错，全绿照旧。
 */
class TenantRollbackRuntimeEffectTest {

    private static final String TENANT = "t-a";
    private static final String AGENT = "agent-1";

    private PermissionResolver resolver(CapFixture fixture) {
        return new PermissionResolver(fixture.agentDefs(), fixture.skillDefs(), fixture.toolDefs(),
                fixture.serverDefs(), fixture.publishedVersions(), fixture.manifest());
    }

    /**
     * 两个版本的 Agent：v1 启用 {@code skill-a}，v2 启用 {@code skill-b}。
     *
     * <p>两者启用不同的 Skill —— 这样闭包内容可区分，
     * 断言「解析到哪个版本」不必依赖内部字段。
     */
    private CapFixture fixture() {
        return new CapFixture()
                .withAgent(TENANT, AGENT, 1, List.of("skill-a"), "{}")
                .withAgent(TENANT, AGENT, 2, List.of("skill-b"), "{}")
                .withSkill("skill-a", 1, CapabilityRiskLevel.READ, List.of())
                .withSkill("skill-b", 1, CapabilityRiskLevel.READ, List.of());
    }

    @Test
    @DisplayName("✅ 指针指向 v2 → 运行期解析到 v2 的闭包")
    void currentResolvesPointerVersion() {
        CapFixture fixture = fixture()
                .withPublishedAgentVersion(TENANT, AGENT, 2);

        EffectiveClosure closure = resolver(fixture).resolveCurrent(TENANT, AGENT);

        assertThat(closure.agentVersion()).isEqualTo(2);
        assertThat(closure.skill("skill-b")).isPresent();
        assertThat(closure.skill("skill-a"))
                .as("当前发布头指向 v2 → 闭包里不应出现 v1 的 Skill")
                .isEmpty();
    }

    @Test
    @DisplayName("⚠️⚠️ 回滚后运行期必须回到回滚目标的 Agent 版本（P0-5 反证）")
    void rollbackChangesRuntimeAgentVersion() {
        CapFixture fixture = fixture();
        // 先发布 v2（指针指向 v2）
        fixture.withPublishedAgentVersion(TENANT, AGENT, 2);
        EffectiveClosure before = resolver(fixture).resolveCurrent(TENANT, AGENT);
        assertThat(before.agentVersion()).isEqualTo(2);

        // 回滚：指针切回 v1（发布头声明 agent v1）
        fixture.withPublishedAgentVersion(TENANT, AGENT, 1);
        EffectiveClosure after = resolver(fixture).resolveCurrent(TENANT, AGENT);

        assertThat(after.agentVersion())
                .as("⚠️⚠️ 回滚后运行期必须用回滚目标的 Agent 版本 —— "
                        + "修复前取「最新已发布版本行」，回滚只移指针不删版本行，"
                        + "于是这里仍是 2（回滚对运行期完全无效）")
                .isEqualTo(1);
        assertThat(after.skill("skill-a")).isPresent();
        assertThat(after.skill("skill-b"))
                .as("⚠️⚠️ 回滚后不应再暴露被回滚版本的 Skill")
                .isEmpty();
    }

    @Test
    @DisplayName("⚠️⚠️ 闭包缓存必须随回滚失效（P0-5 反证）")
    void rollbackInvalidatesCachedClosure() {
        CapFixture fixture = fixture().withPublishedAgentVersion(TENANT, AGENT, 2);
        CachingClosureResolver caching = new CachingClosureResolver(
                resolver(fixture), fixture.publishedVersions(), fixture.toolDefs(),
                fixture.agentDefs(), fixture.manifest());

        EffectiveClosure before = caching.resolveCurrent(TENANT, AGENT);
        assertThat(before.agentVersion()).isEqualTo(2);
        assertThat(caching.hitCount()).isZero();

        // 回滚：指针切回 v1
        fixture.withPublishedAgentVersion(TENANT, AGENT, 1);
        EffectiveClosure after = caching.resolveCurrent(TENANT, AGENT);

        assertThat(after.agentVersion())
                .as("⚠️⚠️ 缓存的新鲜度判定必须与解析侧同口径 —— "
                        + "否则回滚后缓存仍判定「版本没变」，陈旧闭包继续被使用")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("✅ 发布头未引用该 Agent → 回退到最新已发布版本行（向后兼容）")
    void fallsBackWhenReleaseHeadDoesNotNameAgent() {
        // 未登记 publishedAgentVersions → 模拟「该 Agent 不在当前发布集合内」
        CapFixture fixture = fixture();

        EffectiveClosure closure = resolver(fixture).resolveCurrent(TENANT, AGENT);

        assertThat(closure.agentVersion())
                .as("从未走过 CAP 发布通道的租户不得被整体打成「Agent 不可用」")
                .isEqualTo(2);
    }
}
