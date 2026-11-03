package cn.studykid.multiagent.infrastructure.security;

import java.util.List;

import cn.studykid.multiagent.core.security.Action;
import cn.studykid.multiagent.core.security.Decision;
import cn.studykid.multiagent.core.security.ResourceRef;
import cn.studykid.multiagent.core.security.Scope;
import cn.studykid.multiagent.core.security.ScopeFilter;
import cn.studykid.multiagent.core.tenant.SubjectContext;

import org.jspecify.annotations.NonNull;

/**
 * 基于声明式 {@link PolicyRule} 的决策引擎（12 文 §2.3 / D3）。
 *
 * <p><b>fail-closed 纪律</b>：
 * <ol>
 *   <li>无主体上下文 → Deny(NO_SUBJECT)；</li>
 *   <li>无匹配规则 → Deny(NO_RULE)（默认拒绝，绝不放行）；</li>
 *   <li>{@code UNRESTRICTED} 只能由 {@code isPlatformOps()} 主体产出（R3 不变式，双保险）；</li>
 *   <li>构造期从 {@link PolicyStore} 加载，非法策略直接抛 {@link IllegalPolicyException}（拒绝启动）。</li>
 * </ol>
 */
public final class RuleBasedPolicyEngine implements PolicyEngine {

    private final List<PolicyRule> rules;

    public RuleBasedPolicyEngine(@NonNull PolicyStore store) {
        // 非法策略 → IllegalPolicyException（fail-closed，拒绝启动）
        this.rules = store.load();
    }

    @Override
    public @NonNull Decision decide(@NonNull SubjectContext subject,
                                   @NonNull Action action,
                                   @NonNull ResourceRef resource) {
        if (subject == null) {
            return new Decision.Deny("NO_SUBJECT", "缺少主体上下文");
        }

        PolicyRule matched = null;
        for (PolicyRule r : rules) {
            if (r.resourceType().equals(resource.type()) && r.action() == action) {
                matched = r;
                break;
            }
        }
        if (matched == null) {
            return new Decision.Deny("NO_RULE",
                    "无匹配策略规则: " + resource.type() + "/" + action);
        }

        boolean requirementMet = switch (matched.requirement()) {
            case TENANT -> subject.scope() == Scope.TENANT;
            case PLATFORM -> subject.isPlatformOps();
        };
        if (!requirementMet) {
            return new Decision.Deny("REQUIREMENT_NOT_MET",
                    "主体不满足前置要求: " + matched.requirement());
        }

        ScopeFilter filter = switch (matched.effect()) {
            case TENANT_ONLY -> new ScopeFilter.TenantOnly(subject.tenantId());
            case TENANT_WITH_SHARED -> new ScopeFilter.TenantWithShared(subject.tenantId());
            case UNRESTRICTED -> subject.isPlatformOps()
                    ? new ScopeFilter.Unrestricted(matched.id())
                    : new ScopeFilter.None();
        };
        return new Decision.Permit(filter);
    }
}
