package cn.studykid.multiagent.infrastructure.security;

import java.util.List;

import org.jspecify.annotations.NonNull;

/**
 * 策略规则存储 —— 加载逐条对应现役 RLS 策略的 {@link PolicyRule}。
 *
 * <p>实现类在加载期负责校验规则合法性（如 R3 不变式），非法规则须抛
 * {@link IllegalPolicyException} 阻止启动。
 */
public interface PolicyStore {

    @NonNull List<PolicyRule> load();
}
