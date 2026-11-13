package cn.studykid.rag.infrastructure.persistence.scope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.rag.core.scope.RagScope;
import cn.studykid.rag.core.scope.RagScopeFilter;

/**
 * 作用域 → SQL 谓词翻译器的等价性与 fail-closed 不变式（RLS 清理 · M3）。
 *
 * <p><b>⚠️ 为什么这是 M3 最该被钉死的一条</b>：
 * 应用层谓词一旦与现役 RLS 策略不等价，<b>差异不会在 RLS 还在场时暴露</b>
 * （RLS 会把两边都过滤成同一个结果集），只会在「RLS 退役那一刻」炸——
 * 那时语料可能已经按新口径写入，且<b>不会有任何报错</b>。
 *
 * <p>所以本测试把「渲染结果与 RLS 读策略逐字等价」写成断言，
 * 让它在<b>改造当下</b>就红，而不是在退役当天。
 */
class RagSqlScopeTranslatorTest {

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER = "22222222-2222-2222-2222-222222222222";

    private final RagSqlScopeTranslator translator = RagSqlScopeTranslator.withDefaultSentinel();

    @Test
    @DisplayName("租户档读侧谓词 = IN (本租户, 哨兵) —— 与迁移 V8 读策略逐字等价")
    void tenantWithGlobalRendersSameSetAsRlsReadPolicy() {
        RagSqlFragment frag = translator.render(new RagScopeFilter.TenantWithGlobal(TENANT));

        assertThat(frag.sql()).isEqualTo("tenant_id IN (?::uuid, ?::uuid)");
        assertThat(frag.params()).containsExactly(TENANT, RagScope.GLOBAL_TENANT_ID);

        // ⚠️ 与现役 RLS 读策略的集合逐项对照：
        //    rag_current_tenant() = 本租户、rag_global_tenant_id() = 哨兵。
        //    两个元素相同 ⇒ 退役后行集不变。
        assertThat(frag.params()).containsExactlyInAnyOrder(
                TENANT, "00000000-0000-0000-0000-000000000000");
    }

    @Test
    @DisplayName("仅本租户档 = tenant_id = ?（等价 tenant_id = rag_current_tenant()）")
    void tenantOnlyRendersEquality() {
        RagSqlFragment frag = translator.render(new RagScopeFilter.TenantOnly(TENANT));

        assertThat(frag.sql()).isEqualTo("tenant_id = ?::uuid");
        assertThat(frag.params()).containsExactly(TENANT);
    }

    @Test
    @DisplayName("全局档 = tenant_id = 哨兵（等价 rag_global_tenant_id()，不依赖 GUC）")
    void globalOnlyBindsSentinelConstant() {
        RagSqlFragment frag = translator.render(new RagScopeFilter.GlobalOnly());

        assertThat(frag.sql()).isEqualTo("tenant_id = ?::uuid");
        assertThat(frag.params()).containsExactly(RagScope.GLOBAL_TENANT_ID);
    }

    @Test
    @DisplayName("⚠️⚠️ None 必须渲染成恒假（0 行），绝不回落到「查全部」")
    void noneRendersAlwaysFalse() {
        RagSqlFragment frag = translator.render(RagScopeFilter.NONE);

        assertThat(frag.sql()).isEqualTo("1 = 0");
        assertThat(frag.params()).isEmpty();
        // ⚠️ 反向对照：恒真谓词是运维通道专用形态，绝不能被 None 渲染出来。
        assertThat(frag.sql()).isNotEqualTo(RagSqlFragment.TRUE.sql());
    }

    @Test
    @DisplayName("哨兵值与应用侧常量同源：改配置必须改一处")
    void sentinelMatchesApplicationConstant() {
        RagSqlFragment frag = translator.render(new RagScopeFilter.GlobalOnly());
        assertThat(frag.params()).containsExactly(RagScope.GLOBAL_TENANT_ID);
        // ⚠️ 与 DB 侧 rag_global_tenant_id() 的字面量同值（M5 删函数后仍须一致）。
        assertThat(RagScope.GLOBAL_TENANT_ID)
                .isEqualTo("00000000-0000-0000-0000-000000000000");
    }

    @Test
    @DisplayName("自定义哨兵被接受（部署可覆盖），但空值直接拒绝装配")
    void customSentinelAcceptedBlankRejected() {
        RagSqlScopeTranslator custom = new RagSqlScopeTranslator("99999999-9999-9999-9999-999999999999");
        RagSqlFragment frag = custom.render(new RagScopeFilter.GlobalOnly());
        assertThat(frag.params()).containsExactly("99999999-9999-9999-9999-999999999999");

        assertThatThrownBy(() -> new RagSqlScopeTranslator("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("globalTenantId");
    }

    @Test
    @DisplayName("列限定符：JOIN 场景必须带别名（否则 PG 报 column reference is ambiguous）")
    void columnQualifierSupportedAndValidated() {
        RagSqlFragment frag = translator.render(
                new RagScopeFilter.TenantWithGlobal(TENANT), "c.tenant_id");
        assertThat(frag.sql()).isEqualTo("c.tenant_id IN (?::uuid, ?::uuid)");
        assertThat(frag.params()).containsExactly(TENANT, RagScope.GLOBAL_TENANT_ID);

        // ⚠️ 列限定符会被拼进 SQL ⇒ 必须走白名单校验。
        assertThatThrownBy(() -> translator.render(
                new RagScopeFilter.TenantOnly(TENANT), "tenant_id; DROP TABLE x --"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("列限定符");
        assertThatThrownBy(() -> translator.render(new RagScopeFilter.TenantOnly(TENANT), " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("占位符与参数个数必须一致（错位是 JDBC 最难查的一类错）")
    void placeholderCountMustMatchParamCount() {
        assertThatThrownBy(() -> new RagSqlFragment("tenant_id = ? AND x = ?", List.of(TENANT)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("占位符数");

        // 合法形态不抛
        RagSqlFragment ok = new RagSqlFragment("tenant_id = ?::uuid", List.of(TENANT));
        assertThat(ok.params()).containsExactly(TENANT);
    }

    @Test
    @DisplayName("⚠️ 哨兵不得冒充租户：TenantOnly / TenantWithGlobal 拒绝它")
    void sentinelCannotMasqueradeAsTenant() {
        assertThatThrownBy(() -> new RagScopeFilter.TenantOnly(RagScope.GLOBAL_TENANT_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("哨兵");

        assertThatThrownBy(() -> new RagScopeFilter.TenantWithGlobal(RagScope.GLOBAL_TENANT_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("哨兵");

        // ⚠️ 空白租户同样拒绝：它会让谓词变成「永假」或「全表」，
        //    两者都不是调用方想要的语义。
        assertThatThrownBy(() -> new RagScopeFilter.TenantOnly("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("parse：普通租户归一化为 TenantWithGlobal，哨兵按 allowGlobal 分流")
    void parseNormalises() {
        assertThat(RagScopeFilter.parse(TENANT, false))
                .isInstanceOf(RagScopeFilter.TenantWithGlobal.class);
        assertThat(RagScopeFilter.parse(TENANT, false))
                .isInstanceOf(RagScopeFilter.TenantWithGlobal.class);
        assertThat(RagScopeFilter.parse("  " + TENANT.toUpperCase(java.util.Locale.ROOT) + " ", false))
                .isInstanceOf(RagScopeFilter.TenantWithGlobal.class);
        assertThat(RagScopeFilter.parse(RagScope.GLOBAL_TENANT_ID, true))
                .isInstanceOf(RagScopeFilter.GlobalOnly.class);
        assertThat(RagScopeFilter.parse(null, false))
                .isEqualTo(RagScopeFilter.NONE);

        // ⚠️ 哨兵作为会话身份 = 越权企图，必须报错而不是静默放行。
        assertThatThrownBy(() -> RagScopeFilter.parse(RagScope.GLOBAL_TENANT_ID, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("越权");
    }

    @Test
    @DisplayName("两个不同租户渲染出不同的谓词参数（隔离不被合并）")
    void distinctTenantsRenderDistinctParams() {
        List<Object> a = translator.render(new RagScopeFilter.TenantWithGlobal(TENANT)).params();
        List<Object> b = translator.render(new RagScopeFilter.TenantWithGlobal(OTHER)).params();

        assertThat(a).doesNotContain(OTHER);
        assertThat(b).doesNotContain(TENANT);
    }
}
