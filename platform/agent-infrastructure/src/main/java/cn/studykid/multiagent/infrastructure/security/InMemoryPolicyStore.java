package cn.studykid.multiagent.infrastructure.security;

import java.util.List;

import org.jspecify.annotations.NonNull;

/**
 * 内存策略存储（P0 骨架；后续可替换为配置中心/数据库实现，见 12 文 §9 Q2）。
 *
 * <p>构造期即校验全部规则，非法规则（UNRESTRICTED 配非 PLATFORM 前置）立即抛
 * {@link IllegalPolicyException}，保证「配置写错不会放大权限」。
 */
public final class InMemoryPolicyStore implements PolicyStore {

    private final List<PolicyRule> rules;

    public InMemoryPolicyStore(@NonNull List<PolicyRule> rules) {
        this.rules = List.copyOf(rules);
        validate(this.rules);
    }

    @Override
    public @NonNull List<PolicyRule> load() {
        return rules;
    }

    private static void validate(@NonNull List<PolicyRule> rs) {
        for (PolicyRule r : rs) {
            if (r.effect() == ScopeEffect.UNRESTRICTED && r.requirement() != ScopeRequirement.PLATFORM) {
                throw new IllegalPolicyException(
                        "规则 [" + r.id() + "] 产出 UNRESTRICTED 但前置要求非 PLATFORM，"
                                + "违反 R3 不变式（UNRESTRICTED 只能由 isPlatformOps 主体产出）");
            }
        }
    }
}
