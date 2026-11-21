package cn.studykid.multiagent.ports.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.cap.PublishPolicy;

/**
 * 发布策略配置端口（需求文档 §二.1）。
 *
 * <p>⚠️ <b>优先级语义由应用层负责</b>：本端口只做存储 + 简单按 (scope, tenant_id) 列出，
 * 应用层的 {@code PublishPolicyService} 基于 INSTANCE > RESOURCE_TYPE > GLOBAL 做解析。
 * 把优先级塞进仓储层会让单测成本暴涨、且与"端口只负责存储"的职责冲突。
 */
public interface PublishPolicyPort {

    /** 插入新策略（scope / tenant_id / policy_level / resource_kind / resource_id 的
     * 唯一组合已由 DB 层 {@code uq_cap_publish_policy_target} 索引守护）。 */
    @NonNull PublishPolicy insert(@NonNull PublishPolicy policy);

    /** 按 policyId 查询。 */
    @NonNull Optional<PublishPolicy> findById(@NonNull String scope,
                                               @Nullable String tenantId,
                                               @NonNull UUID policyId);

    /** 更新策略（幂等：可重复调用）。 */
    @NonNull Optional<PublishPolicy> update(@NonNull PublishPolicy policy);

    /** 删除策略（单实例覆盖策略删除后，资源会继承上一层策略）。 */
    boolean delete(@NonNull String scope,
                   @Nullable String tenantId,
                   @NonNull UUID policyId);

    /** 列出指定 (scope, tenant_id) 下所有启用的策略（含 GLOBAL + RESOURCE_TYPE + INSTANCE，由应用层按优先级排序）。 */
    @NonNull List<PublishPolicy> listEnabled(@NonNull String scope,
                                              @Nullable String tenantId);

    /** 直接按完整键查找（scope + tenant_id + policy_level + resource_kind + resource_id）。 */
    @NonNull Optional<PublishPolicy> findByTarget(@NonNull String scope,
                                                  @Nullable String tenantId,
                                                  @NonNull String policyLevel,
                                                  @Nullable String resourceKind,
                                                  @Nullable String resourceId);

    /**
     * 列出所有启用的 GLOBAL 策略（<b>跨 scope</b>），供「全局默认策略」回退使用。
     *
     * <p>⚠️ 为什么需要它：能力目录同时存在 PLATFORM 与 TENANT 两条发布通道，
     * 而策略配置页只往其中一侧写 GLOBAL 策略。若精确 (scope, tenant) 解析无果，
     * 就用这里的 GLOBAL 策略作为默认值，避免「明明配了全局手动发布，
     * 另一条通道却按缺省审批走」的割裂。结果至多一条（PLATFORM 优先）。
     */
    @NonNull List<PublishPolicy> listEnabledGlobals();
}
