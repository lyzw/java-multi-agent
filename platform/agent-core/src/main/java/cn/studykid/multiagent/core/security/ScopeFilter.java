package cn.studykid.multiagent.core.security;

import org.jspecify.annotations.NonNull;

/**
 * 行级隔离唯一决策权威的枢纽对象 —— 替换 PG RLS 策略谓词，且不含任何 SQL 方言。
 *
 * <p>这是整套迁移方案的枢纽（12 文 §2.3.1）。现役 RLS 谓词被逐条机械翻译为以下 4 种形态之一；
 * 新增一种存储后端 = 实现一个 {@code ScopeTranslator}，不需要新增一条权限规则。
 *
 * <p><b>评审批注落实</b>：{@code None} 必须是 permitted 子类型（密封接口不能由匿名类实现，
 * 否则无法编译）；{@code switch} 须用类型模式 {@code case ScopeFilter.None n} 满足密封穷尽性。
 */
public sealed interface ScopeFilter
        permits ScopeFilter.TenantWithShared,
                ScopeFilter.TenantOnly,
                ScopeFilter.Unrestricted,
                ScopeFilter.None {

    /** 本租户私有数据 + 平台公开数据。等价于现役 SQL: tenant_id IN (rag_current_tenant(), rag_global_tenant_id())。 */
    record TenantWithShared(@NonNull String tenantId) implements ScopeFilter {}

    /** 仅本租户。等价于现役 SQL: tenant_id = current_tenant_id()。 */
    record TenantOnly(@NonNull String tenantId) implements ScopeFilter {}

    /** 平台全域。等价于现役 {@code BYPASSRLS} 通道的「不受策略约束」视图。 */
    record Unrestricted(@NonNull String reason) implements ScopeFilter {}

    /** 空集：鉴权通过但无可见范围 → 恒假 → 0 行（fail-closed 的显式表达）。 */
    record None() implements ScopeFilter {}

    /** 便捷常量。switch 中须用类型模式 {@code case ScopeFilter.None n} 满足密封穷尽性。 */
    ScopeFilter NONE = new None();
}
