package cn.studykid.multiagent.ports.persistence;

import java.util.Optional;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.core.cap.CapRoutingPolicyDef;

/**
 * 路由策略仓储端口（多级 Agent v1.1 · 第十四批）。
 *
 * <p>⚠️ <b>未发布即不可引用</b>：读取方（含发布期校验器）必须区分
 * 「版本不存在」与「版本存在但未发布」—— 前者是引用写错，后者是引用了草稿。
 * 两者都不可用，但排查方向不同。与输出契约端口同口径。
 *
 * <p>⚠️ <b>写入是「插入不可变版本行」</b>：改策略只能发新版本。
 * 若允许就地改写，在途 Run 按快照引用的旧策略会被静默改写，
 * 路由输出将无从复核（MA-T15）。
 *
 * <p>⚠️ <b>租户隔离</b>：策略是租户级对象，端口实现必须按 {@code tenantId} 过滤，
 * 不得跨租户返回（跨租户存在性也不泄漏）。
 */
public interface CapRoutingPolicyPort {

    /**
     * 插入一条路由策略定义行（<b>幂等</b>）。
     *
     * @param def 策略定义行
     * @return {@code true} 表示本次插入；{@code false} 表示已存在同版本
     */
    boolean insert(@NonNull CapRoutingPolicyDef def);

    /**
     * 按固定版本读取。
     *
     * @param tenantId 租户标识
     * @param policyId 策略标识
     * @param version  策略版本号
     * @return 策略定义行；不存在时为空
     */
    @NonNull Optional<CapRoutingPolicyDef> find(@NonNull String tenantId,
                                                @NonNull String policyId,
                                                int version);

    /**
     * 读取该策略的<b>最新已发布</b>版本。
     *
     * <p>⚠️ 仅供诊断与提示使用：<b>运行期与发布期都不得</b>用它代替固定版本引用
     * —— 那正是漂移的来源。
     *
     * @param tenantId 租户标识
     * @param policyId 策略标识
     * @return 最新已发布版本；无已发布版本时为空
     */
    @NonNull Optional<CapRoutingPolicyDef> findLatestPublished(@NonNull String tenantId,
                                                                @NonNull String policyId);

    /**
     * 读取该策略的<b>最新版本</b>（<b>含草稿</b>）。
     *
     * <p>⚠️ <b>仅用于「分配下一个版本号」</b>：与输出契约端口同口径 ——
     * 草稿版本同样占用了版本号，只看已发布版本会分配出一个<b>与草稿撞号</b>的版本，
     * 撞号后 {@code INSERT ... ON CONFLICT DO NOTHING} 会静默丢弃新策略，
     * 表现为「发布成功但策略没生效」。
     *
     * @param tenantId 租户标识
     * @param policyId 策略标识
     * @return 最新版本行（含草稿）；从未登记过时为空
     */
    @NonNull Optional<CapRoutingPolicyDef> findLatest(@NonNull String tenantId,
                                                      @NonNull String policyId);
}
