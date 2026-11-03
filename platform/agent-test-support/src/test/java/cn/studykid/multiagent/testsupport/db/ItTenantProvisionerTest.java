package cn.studykid.multiagent.testsupport.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ItTenantProvisioner} 的契约守卫（无 DB）。
 *
 * <p>这些断言守护的是<b>结构前提</b>而不是功能：只要哪天有人把 IT 改回真实租户
 * （{@code t-a} / {@code t-b}），本类应当立刻红，而不是等到某个 IT 在开发机上
 * 随机红一次、被当成 flaky 忽略。
 */
class ItTenantProvisionerTest {

    @Test
    @DisplayName("⛔ 真实租户 slug 一律拒绝（复用真实租户 = 与运行实例共用数据）")
    void rejectsRealTenantSlugs() {
        for (String slug : new String[] {"t-a", "t-b", "demo", "", null}) {
            assertThatThrownBy(() -> ItTenantProvisioner.requireItSlug(slug))
                    .as("slug=%s 必须被拒绝", slug)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(ItTenantProvisioner.SLUG_PREFIX);
        }
    }

    @Test
    @DisplayName("✅ it- 前缀的 slug 通过")
    void acceptsItPrefixedSlugs() {
        for (String slug : new String[] {"it-pgq-a", "it-stat-a", "it-"}) {
            ItTenantProvisioner.requireItSlug(slug);
        }
    }

    @Test
    @DisplayName("⛔ 前缀校验先于任何数据库访问（否则会在连库时以 NPE/连接异常掩盖真因）")
    void validatesPrefixBeforeTouchingDataSource() {
        // 传 null 数据源：若实现先取连接，会抛 NPE 而不是 IllegalArgument。
        assertThatThrownBy(() -> ItTenantProvisioner.provision(null, "t-a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("t-a");
    }

    @Test
    @DisplayName("✅ 前缀常量与文档一致（it- 不可悄悄改名）")
    void prefixContractIsStable() {
        assertThat(ItTenantProvisioner.SLUG_PREFIX).isEqualTo("it-");
    }
}
