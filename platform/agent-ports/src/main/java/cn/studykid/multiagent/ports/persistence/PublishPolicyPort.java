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
}
