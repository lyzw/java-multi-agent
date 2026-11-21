package cn.studykid.multiagent.infrastructure.persistence.cap.mapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import cn.studykid.multiagent.infrastructure.persistence.cap.model.PublishPolicyDO;

/**
 * 发布策略 Mapper（W2 迁移，对应 {@code JdbcPublishPolicyRepository}）。
 *
 * <p>⚠️ 本接口所在的包由租户通道 SqlSessionFactory 扫描，所有方法都会经过
 * {@code RlsTenantBindingInterceptor} —— 调用前必须由适配层先
 * {@code TenantScope.bind(...)}，否则 fail-closed。
 */
public interface PublishPolicyMapper extends BaseMapper<PublishPolicyDO> {

    /** 插入并 RETURNING created_at / updated_at（原 JDBC 用 rs.getTimestamp 回填）。 */
    Optional<PublishPolicyDO> insertPolicy(@Param("policyId") UUID policyId,
                                     @Param("scope") String scope,
                                     @Param("tenantId") UUID tenantId,
                                     @Param("policyLevel") String policyLevel,
                                     @Param("resourceKind") String resourceKind,
                                     @Param("resourceId") String resourceId,
                                     @Param("publishMode") String publishMode,
                                     @Param("enabled") boolean enabled,
                                     @Param("updatedBy") String updatedBy);

    /** 按 scope + tenant_id（IS NOT DISTINCT FROM 兼容 PLATFORM 的 NULL）+ policy_id 查。 */
    Optional<PublishPolicyDO> findById(@Param("scope") String scope,
                                       @Param("tenantId") UUID tenantId,
                                       @Param("policyId") UUID policyId);

    /** 条件 UPDATE + RETURNING；0 行 = 不存在（适配层返回 Optional.empty）。 */
    Optional<PublishPolicyDO> updateByKey(@Param("publishMode") String publishMode,
                                     @Param("enabled") boolean enabled,
                                     @Param("updatedBy") String updatedBy,
                                     @Param("policyId") UUID policyId,
                                     @Param("scope") String scope,
                                     @Param("tenantId") UUID tenantId);

    int deleteByKey(@Param("policyId") UUID policyId,
               @Param("scope") String scope,
               @Param("tenantId") UUID tenantId);

    /** 列出启用策略（含 GLOBAL 的 tenant_id IS NULL 行）；顺序由 SQL 固定，不做应用层筛选。 */
    List<PublishPolicyDO> listEnabled(@Param("scope") String scope,
                                      @Param("tenantId") UUID tenantId);

    /** 跨 scope 查启用的 GLOBAL 策略（PLATFORM 优先），至多一条。 */
    List<PublishPolicyDO> listEnabledGlobals();

    /** 按完整键查找；resourceKind / resourceId 的空值由适配层归一为空串（与原实现一致）。 */
    Optional<PublishPolicyDO> findByTarget(@Param("scope") String scope,
                                           @Param("tenantId") UUID tenantId,
                                           @Param("policyLevel") String policyLevel,
                                           @Param("resourceKind") String resourceKind,
                                           @Param("resourceId") String resourceId);
}
