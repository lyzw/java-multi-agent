package cn.studykid.multiagent.application.cap;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.core.cap.EffectiveClosure;

/**
 * 能力闭包解析端口（P2 · §7.1 {@code PermissionResolver}）。
 *
 * <p>本接口是 {@link PermissionResolver} 的<b>抽象出口</b>，
 * 存在的唯一目的：让 {@link CachingClosureResolver} 能作为装饰器包住解析器，
 * 并被 {@link InvocationValidator} 与准入链路<b>共享同一个缓存实例</b>。
 *
 * <p>⚠️ <b>为什么必须共享同一个实例</b>：闭包解析会读多个 {@code cap_*} 表并做交集运算，
 * 是执行链上最重的一段。若校验器用无缓存实现、闸门用缓存实现，
 * 两条路径会在同一请求内读出<b>不同版本</b>的闭包 ——
 * 校验器比的是快照指纹、闸门用的是自己那份闭包，
 * 二者不一致时会出现「明明闭包没变却被判指纹失效」这类不可复现的误判。
 *
 * <p>⚠️ <b>实现必须无副作用</b>：只读，不写库、不发消息、不改状态。
 * 闭包解析是 Phase A 步骤 3 的输入来源，必须可被反复调用。
 */
public interface ClosureResolver {

    /**
     * 解析<b>固定版本</b>的闭包（创建 Run 快照时用 · §6.2）。
     *
     * @param tenantId     租户标识
     * @param agentId      Agent 标识
     * @param agentVersion Agent 定义版本
     * @return 有效能力闭包
     * @throws ClosureResolutionException 解析失败（配置缺失 / 版本行缺失 / 环）
     */
    @NonNull EffectiveClosure resolve(@NonNull String tenantId,
                                       @NonNull String agentId,
                                       int agentVersion);

    /**
     * 解析<b>当前已发布</b>的闭包（授权期新鲜度校验用 · §5.3 步骤 7 / §6.4）。
     *
     * @param tenantId 租户标识
     * @param agentId  Agent 标识
     * @return 有效能力闭包
     * @throws ClosureResolutionException 解析失败
     */
    @NonNull EffectiveClosure resolveCurrent(@NonNull String tenantId,
                                              @NonNull String agentId);
}
