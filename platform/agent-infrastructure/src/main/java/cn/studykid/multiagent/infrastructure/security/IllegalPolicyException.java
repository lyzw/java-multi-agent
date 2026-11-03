package cn.studykid.multiagent.infrastructure.security;

/**
 * 非法策略规则异常 —— 配置级别错误，必须在应用启动期抛出（fail-closed，拒绝启动）。
 *
 * <p>典型触发：一条产出 {@code UNRESTRICTED} 的规则其前置要求非 {@code PLATFORM}
 * （违反 12 文 §7.1 R3 不变式：{@code UNRESTRICTED} 只能由 {@code isPlatformOps()} 主体产出）。
 */
public final class IllegalPolicyException extends RuntimeException {

    public IllegalPolicyException(String message) {
        super(message);
    }
}
