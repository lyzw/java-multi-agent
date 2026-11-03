package cn.studykid.multiagent.infrastructure.persistence.scope;

import cn.studykid.multiagent.core.security.ScopeFilter;

import org.jspecify.annotations.NonNull;

/**
 * 结果集染色校验器 —— 逐行复核「返回的每一行是否都落在本次查询的 {@link ScopeFilter} 内」（12 文 §4.5 / D6）。
 *
 * <p><b>这是「退役后只剩应用层」纵深下降的三层补偿之一（类型系统 + 染色 + 反证）</b>。
 * 与 RLS 不同，应用层即便写错也只会静默多返回行；染色校验在返回路径上
 * 逐行断言租户归属，把「静默越权」变为「可观测的违例计数」。</p>
 *
 * <p><b>fail-closed 语义</b>：
 * <ul>
 *   <li>{@link ScopeFilter.None}（空集）→ 任何行都是违例（期望 0 行却被返回）；</li>
 *   <li>{@link ScopeFilter.Unrestricted} → 永远通过（仅 {@code isPlatformOps()} 主体可得）；</li>
 *   <li>{@link ScopeFilter.TenantOnly} → 行租户必须严格等于主体租户；</li>
 *   <li>{@link ScopeFilter.TenantWithShared} → 行租户等于主体租户，<b>或</b>该行是哨兵租户的共享行。</li>
 * </ul>
 *
 * <p>哨兵租户标识（原 PG 函数 {@code rag_global_tenant_id()}）由构造参数注入，与
 * {@link SqlScopeTranslator} 共用同一配置项（见 12 文 §7.1 R6 / D11）。
 */
public final class TaintVerifier {

    private final @NonNull ScopeFilter expected;
    private final @NonNull String sentinelTenantId;
    private long checked;
    private long violations;

    public TaintVerifier(@NonNull ScopeFilter expected, @NonNull String sentinelTenantId) {
        this.expected = expected;
        this.sentinelTenantId = sentinelTenantId;
    }

    /**
     * 核对单行。
     *
     * @return {@code true} 表示该行落在期望范围内（无违例）；{@code false} 表示越权行。
     */
    public boolean accept(@NonNull RowAttribution row) {
        checked++;
        boolean ok = switch (expected) {
            case ScopeFilter.None ignored -> false;   // 期望 0 行 → 任何行即违例
            case ScopeFilter.Unrestricted ignored -> true;
            case ScopeFilter.TenantOnly t -> t.tenantId().equals(row.tenantId());
            case ScopeFilter.TenantWithShared t ->
                    t.tenantId().equals(row.tenantId())
                            || (row.shared() && sentinelTenantId.equals(row.tenantId()));
        };
        if (!ok) {
            violations++;
        }
        return ok;
    }

    public @NonNull ScopeFilter expected() {
        return expected;
    }

    public long checked() {
        return checked;
    }

    public long violations() {
        return violations;
    }

    public @NonNull TaintReport report() {
        return new TaintReport(checked, violations);
    }
}
