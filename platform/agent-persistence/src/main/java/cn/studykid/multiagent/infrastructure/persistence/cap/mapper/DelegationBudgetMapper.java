package cn.studykid.multiagent.infrastructure.persistence.cap.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import cn.studykid.multiagent.infrastructure.persistence.cap.model.DelegationBudgetHeldDO;

/**
 * 委派预算预留反查 Mapper（对应 {@code DelegationBudgetPort}）。
 *
 * <p>⚠️ 结果不是表实体（{@code budget_reservation LEFT JOIN agent_invocation}），
 * 故不继承 {@code BaseMapper}——本 Port 只有两条只读查询，没有通用 CRUD 可复用。
 *
 * <p>⚠️ 本接口由租户通道 SqlSessionFactory 扫描（见 {@code MybatisRlsConfiguration}），
 * 所有方法都会经过 {@code RlsTenantBindingInterceptor}——调用前必须由适配层先
 * {@code TenantScope.bind(...)}，否则 fail-closed。
 *
 * <p>SQL 从旧实现逐字搬迁到 {@code DelegationBudgetMapper.xml}，仅 {@code ?} → {@code #{param}}。
 */
public interface DelegationBudgetMapper {

    /** 反查该租户仍被持有的委派侧预留（含「连 agent_invocation 行都没有」的无锚点者）。 */
    List<DelegationBudgetHeldDO> findHeld(@Param("tenantId") String tenantId);

    /** 按操作标识反查单条持有中的预留（仍须满足「工具侧无锚点」判据）。 */
    List<DelegationBudgetHeldDO> findHeldByOperation(@Param("tenantId") String tenantId,
                                                     @Param("operationId") String operationId);
}
