package cn.studykid.multiagent.core.compliance;

import java.util.Set;

import org.jspecify.annotations.NonNull;

/**
 * 已登记的模型供应商（DEC-10 / D-002）。
 *
 * <p><b>⚠️ 此处列出的供应商仅表示「已纳入登记范围」，
 * 不代表已取得零保留确认</b>。确认状态由 {@link ZeroRetentionRegistry} 持有，
 * 二者必须分开——否则「列入白名单」会被误读为「已合规确认」。
 *
 * <p>新增供应商时须同时：① 加入本常量 ② 在注册表中登记确认状态。
 * 漏做②时该供应商自动落入 fail-closed（拒绝真实数据），这是期望行为。
 */
public final class ModelProviders {

    /** DeepSeek。 */
    public static final String DEEPSEEK = "deepseek";

    /** MiniMax。 */
    public static final String MINIMAX = "minimax";

    /** WorkBuddy 内置模型。 */
    public static final String WORKBUDDY = "workbuddy";

    /** 本项目已登记的供应商集合。 */
    public static final Set<String> REGISTERED = Set.of(DEEPSEEK, MINIMAX, WORKBUDDY);

    private ModelProviders() {
    }

    /** @return 是否为已登记供应商。 */
    public static boolean isRegistered(@NonNull String providerId) {
        return REGISTERED.contains(providerId);
    }
}