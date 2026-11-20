package cn.studykid.multiagent.infrastructure.persistence.mapper;

import java.util.List;
import java.util.Optional;

import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import cn.studykid.multiagent.infrastructure.persistence.model.ConfigOverrideDO;

/**
 * 租户配置覆盖 Mapper（对应 {@code JdbcConfigOverrideRepository} 的三条 SQL）。
 *
 * <p>⚠️ 本接口由租户通道 SqlSessionFactory 加载，所有方法都经过
 * {@code RlsTenantBindingInterceptor}：调用前必须由适配层先 {@code TenantScope.bind(...)}，
 * 且必须处于 {@code TransactionalConnectionHolder.inTransaction(...)} 内。
 */
public interface ConfigOverrideMapper extends BaseMapper<ConfigOverrideDO> {

    /** SELECT 全量覆盖行（ORDER BY config_key，与旧实现逐字一致）。 */
    List<ConfigOverrideDO> findAllByTenant(@Param("tenantId") String tenantId);

    /** INSERT ... ON CONFLICT DO UPDATE ... RETURNING updated_at（空结果由适配层 fail-closed）。 */
    Optional<ConfigOverrideDO> upsert(@Param("tenantId") String tenantId,
                                      @Param("key") String key,
                                      @Param("value") String value,
                                      @Param("valueType") String valueType,
                                      @Param("reason") String reason,
                                      @Param("actorId") String actorId);

    /** DELETE（tenant_id + config_key 双谓词）；返回受影响行数，旧实现不校验。 */
    int deleteByKey(@Param("tenantId") String tenantId, @Param("key") String key);
}
