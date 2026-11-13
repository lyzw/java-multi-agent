package cn.studykid.rag.core.scope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link RagScopeFilter} 的纯逻辑不变式（RLS 清理 · M3）。
 *
 * <p><b>钉住什么</b>：作用域决策的<b>fail-closed 方向</b>与<b>哨兵不可冒充租户</b>。
 * 这两条错了都不会报错，只会让隔离静默失真：
 * <ul>
 *   <li>{@code None} 若被渲染成「查全部」⇒ 越权泄漏；</li>
 *   <li>哨兵若能冒充租户 ⇒ 「以平台身份查某个租户」无人拦截。</li>
 * </ul>
 * 与 SQL 侧的对应关系由 {@code RagSqlScopeTranslatorTest} 核对。
 */
class RagScopeFilterTest {

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";

    @Test
    @DisplayName("None 是显式空集，不是 null，也不是「查全部」")
    void noneIsExplicitEmptySet() {
        assertThat(RagScopeFilter.NONE).isInstanceOf(RagScopeFilter.None.class);
        assertThat(RagScopeFilter.NONE).isNotNull();
    }

    @Test
    @DisplayName("⛔ 哨兵不得作为会话身份（TenantOnly / TenantWithGlobal 都拒绝）")
    void sentinelCannotBeSessionIdentity() {
        assertThatThrownBy(() -> new RagScopeFilter.TenantOnly(RagScope.GLOBAL_TENANT_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("哨兵");

        assertThatThrownBy(() -> new RagScopeFilter.TenantWithGlobal(RagScope.GLOBAL_TENANT_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("哨兵");
    }

    @Test
    @DisplayName("⛔ 空白租户直接拒绝：它会让谓词退化成「永假」或「全表」")
    void blankTenantRejected() {
        assertThatThrownBy(() -> new RagScopeFilter.TenantOnly(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RagScopeFilter.TenantOnly(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RagScopeFilter.TenantWithGlobal("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("GlobalOnly 是「可见行范围」而非会话身份：不带任何租户参数")
    void globalOnlyCarriesNoTenant() {
        RagScopeFilter.GlobalOnly g = new RagScopeFilter.GlobalOnly();
        assertThat(g).isNotInstanceOf(RagScopeFilter.TenantOnly.class);
        assertThat(g).isNotInstanceOf(RagScopeFilter.TenantWithGlobal.class);
    }

    @Test
    @DisplayName("parse：缺省（null/空白）⇒ None（fail-closed），不是「查全部」")
    void parseMissingTenantYieldsNone() {
        assertThat(RagScopeFilter.parse(null, false)).isEqualTo(RagScopeFilter.NONE);
        assertThat(RagScopeFilter.parse("   ", false)).isEqualTo(RagScopeFilter.NONE);
    }

    @Test
    @DisplayName("parse：大小写与空白归一（UUID 来自请求头，任何写法都不该漏网）")
    void parseNormalisesCaseAndWhitespace() {
        RagScopeFilter f = RagScopeFilter.parse(
                "  " + TENANT.toUpperCase(java.util.Locale.ROOT) + " ", false);
        assertThat(f).isInstanceOf(RagScopeFilter.TenantWithGlobal.class);
        assertThat(((RagScopeFilter.TenantWithGlobal) f).tenantId()).isEqualTo(TENANT);
    }

    @Test
    @DisplayName("parse：哨兵在 allowGlobal=true 时归为 GlobalOnly，而非 TenantWithGlobal")
    void parseSentinelRoutesToGlobalOnly() {
        RagScopeFilter f = RagScopeFilter.parse(RagScope.GLOBAL_TENANT_ID, true);
        assertThat(f).isInstanceOf(RagScopeFilter.GlobalOnly.class);
    }
}
