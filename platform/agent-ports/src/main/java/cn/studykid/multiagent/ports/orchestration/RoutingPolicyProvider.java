package cn.studykid.multiagent.ports.orchestration;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.cap.CapRoutingPolicyDef;

/**
 * 路由策略提供端口（多级 Agent v1.1 · 第十四批）。
 *
 * <p>⚠️ <b>「取不到策略」与「策略为空」不是一回事</b>：
 * 前者（{@code null}）表示策略引用无法解析、策略未发布、或读取失败 ——
 * 调用方必须 <b>fail-closed</b> 拒绝这次委派；
 * 后者表示策略存在但内容非法 —— 同样不可用。
 * 二者都不允许「退化成随便选一个子 Agent」。
 *
 * <p>⚠️ <b>为什么运行期需要独立端口</b>：运行期要的是「按固定版本引用取到一份
 * <b>已发布</b>的策略」，而不是「仓储里有什么」。把「草稿不可用」「引用格式非法」
 * 这些规则收敛在端口实现里，运行期调用点就只有一种失败语义
 * （取不到 → 拒绝），不会在每个调用点各写一遍、各写错一遍。
 *
 * <p>⚠️ <b>与输出契约端口同构</b>（{@link ResultContractProvider}）：
 * 二者都是「按固定版本引用取一份已发布的配置对象，取不到就 fail-closed」。
 */
public interface RoutingPolicyProvider {

    /**
     * 解析路由策略引用对应的<b>已发布</b>策略。
     *
     * @param tenantId 租户标识
     * @param policyRef 固定版本策略引用（{@code id@version}）
     * @return 路由策略；<b>无法解析时返回 {@code null}</b>（调用方 fail-closed）
     */
    @Nullable CapRoutingPolicyDef policyFor(@NonNull String tenantId,
                                            @NonNull String policyRef);
}
