package cn.studykid.multiagent.infrastructure.persistence.scope;

import java.util.Map;

import cn.studykid.multiagent.core.security.ScopeFilter;

import org.jspecify.annotations.NonNull;

/**
 * SQL 方言翻译器 —— 将 {@link ScopeFilter} 渲染为关系型（JDBC/SQL）谓词（12 文 §4.1）。
 *
 * <p>哨兵租户标识（原 PG 函数 {@code rag_global_tenant_id()}）改为<b>配置项</b>注入，
 * 随 RLS 退役一并删除 PG 函数依赖（见 12 文 §7.1 R6 / D11）。
 *
 * <p>渲染结果与现役 RLS 注入的谓词在优化器眼中完全等价（单表等值场景，12 文 §7.2 P1）。
 */
public final class SqlScopeTranslator implements ScopeTranslator<SqlFragment> {

    private final String sharedTenantId;

    public SqlScopeTranslator(@NonNull String sharedTenantId) {
        if (sharedTenantId == null || sharedTenantId.isBlank()) {
            throw new IllegalArgumentException("sharedTenantId 不能为空（哨兵租户标识，须为配置项）");
        }
        this.sharedTenantId = sharedTenantId;
    }

    @Override
    public @NonNull SqlFragment render(@NonNull ScopeFilter f) {
        return switch (f) {
            case ScopeFilter.TenantOnly t ->
                    new SqlFragment("tenant_id = :__scopeTenant",
                            Map.of("__scopeTenant", t.tenantId()));
            case ScopeFilter.TenantWithShared t ->
                    // 等价于现役 IN (rag_current_tenant(), rag_global_tenant_id())
                    new SqlFragment("tenant_id IN (:__scopeTenant, :__scopeShared)",
                            Map.of("__scopeTenant", t.tenantId(), "__scopeShared", sharedTenantId));
            case ScopeFilter.Unrestricted u -> SqlFragment.TRUE;
            case ScopeFilter.None n -> SqlFragment.FALSE;   // 恒假 → 0 行，fail-closed
        };
    }
}
