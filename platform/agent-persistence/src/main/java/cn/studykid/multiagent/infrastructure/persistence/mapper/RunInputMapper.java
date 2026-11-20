package cn.studykid.multiagent.infrastructure.persistence.mapper;

import java.util.Optional;

import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import cn.studykid.multiagent.infrastructure.persistence.model.RunInputDO;

/**
 * Run 输入读取 Mapper（对应 {@code JdbcRunInputRepository.SQL_FIND_INPUT}）。
 *
 * <p>⚠️ 本接口由租户通道 SqlSessionFactory 加载，所有方法都经过
 * {@code RlsTenantBindingInterceptor}：调用前必须由适配层先 {@code TenantScope.bind(...)}，
 * 且必须处于 {@code TransactionalConnectionHolder.inTransaction(...)} 内。
 */
public interface RunInputMapper extends BaseMapper<RunInputDO> {

    /** SQL_FIND_INPUT：租户 + run_id 双谓词（tenant_id 条件不可删，第一道防线）。 */
    Optional<RunInputDO> findInput(@Param("tenantId") String tenantId,
                                   @Param("runId") String runId);
}
