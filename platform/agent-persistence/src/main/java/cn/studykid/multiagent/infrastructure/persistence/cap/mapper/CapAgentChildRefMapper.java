package cn.studykid.multiagent.infrastructure.persistence.cap.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import cn.studykid.multiagent.infrastructure.persistence.cap.model.CapAgentChildRefDO;

/**
 * 父子 Agent 关系投影 Mapper（对应 {@code JdbcCapAgentChildRefRepository}）。
 *
 * <p>⚠️ 本接口由租户通道 SqlSessionFactory 扫描（见 MybatisRlsConfiguration），
 * 所有方法都会经过 {@code RlsTenantBindingInterceptor}——调用前必须由适配层先
 * {@code TenantScope.bind(...)}，否则 fail-closed。
 *
 * <p>SQL 从旧实现逐字搬迁到 {@code CapAgentChildRefMapper.xml}，仅 {@code ?} → {@code #{param}}；
 * {@code ::uuid} / {@code ON CONFLICT DO NOTHING} / {@code EXISTS(tenant_agent_binding ACTIVE)} 全部保留。
 */
public interface CapAgentChildRefMapper extends BaseMapper<CapAgentChildRefDO> {

    /** SQL_INSERT：幂等插入（同父 release 重复投影同一子 release 不产生第二行）。 */
    int insert(@Param("parentAgentId") String parentAgentId,
               @Param("parentReleaseVersion") int parentReleaseVersion,
               @Param("childAgentId") String childAgentId,
               @Param("childReleaseVersion") int childReleaseVersion,
               @Param("ordinal") int ordinal,
               @Param("required") boolean required,
               @Param("createdAt") java.time.Instant createdAt);

    /** SQL_LIST_BY_PARENT：按父 release 查子引用，按 ordinal 升序。 */
    List<CapAgentChildRefDO> listByParentRelease(@Param("parentAgentId") String parentAgentId,
                                                  @Param("parentReleaseVersion") int parentReleaseVersion,
                                                  @Param("tenantId") String tenantId);

    /** SQL_LIST_BY_PARENT_AGENT：父 Agent 的全部关系边（不限父 release 版本）。 */
    List<CapAgentChildRefDO> listByParentAgent(@Param("parentAgentId") String parentAgentId,
                                                @Param("tenantId") String tenantId);

    /** SQL_LIST_BY_CHILD：反查声明了该子 Agent 的全部父 release（不限子版本）。 */
    List<CapAgentChildRefDO> listByChildAgent(@Param("childAgentId") String childAgentId,
                                               @Param("tenantId") String tenantId);

    /** SQL_LIST_BY_TENANT：租户的全部关系边（拓扑视图）。 */
    List<CapAgentChildRefDO> listByTenant(@Param("tenantId") String tenantId);
}
