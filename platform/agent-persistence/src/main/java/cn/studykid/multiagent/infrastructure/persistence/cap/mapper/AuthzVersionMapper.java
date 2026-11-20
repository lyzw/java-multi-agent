package cn.studykid.multiagent.infrastructure.persistence.cap.mapper;

import java.util.Optional;

import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import cn.studykid.multiagent.infrastructure.persistence.cap.model.AuthzVersionDO;
import cn.studykid.multiagent.infrastructure.persistence.cap.model.AuthzWatermarkDO;

/**
 * 授权版本 Mapper（W2 迁移，对应 {@code JdbcAuthzVersionRepository}）。
 *
 * <p>⚠️ 本接口所在的包由租户通道 SqlSessionFactory 扫描，所有方法都会经过
 * {@code RlsTenantBindingInterceptor} —— 调用前必须由适配层先
 * {@code TenantScope.bind(...)}，否则 fail-closed。
 */
public interface AuthzVersionMapper extends BaseMapper<AuthzVersionDO> {

    /** 强一致读：WHERE tenant_id = ?::uuid AND principal_id = ?。 */
    Optional<AuthzVersionDO> find(@Param("tenantId") String tenantId,
                                  @Param("principalId") String principalId);

    /**
     * 原子递增：单条 {@code INSERT ... ON CONFLICT DO UPDATE ... RETURNING}。
     *
     * <p>⚠️ 返回空视为故障（适配层 fail-closed，保留原「授权版本递增未返回结果」消息）。
     */
    Optional<AuthzVersionDO> bump(@Param("tenantId") String tenantId,
                                  @Param("principalId") String principalId);

    /** 幂等初始化：{@code ON CONFLICT DO NOTHING}，不重置既有版本。 */
    int initialize(@Param("tenantId") String tenantId,
                   @Param("principalId") String principalId);

    /** 租户级水位聚合（非表实体投影；无 GROUP BY，恒有一行）。 */
    AuthzWatermarkDO watermark(@Param("tenantId") String tenantId);
}
