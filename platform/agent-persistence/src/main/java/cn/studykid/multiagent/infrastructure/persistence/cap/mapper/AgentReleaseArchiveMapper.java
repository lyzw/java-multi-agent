package cn.studykid.multiagent.infrastructure.persistence.cap.mapper;

import java.util.Optional;

import org.apache.ibatis.annotations.Param;

import cn.studykid.multiagent.infrastructure.persistence.cap.model.AgentVersionDO;

/**
 * Agent 发布归档 Mapper（对应 {@code AgentReleaseArchivePort}）。
 *
 * <p>⚠️ 写的是<b>归档</b>不是真源：{@code agent_version} / {@code execution_snapshot}
 * 只为维持 {@code run → execution_snapshot → agent_version} 外键链而写，
 * 运行期不读 {@code agent_version.definition}。
 *
 * <p>⚠️ 本接口由租户通道 SqlSessionFactory 扫描（见 {@code MybatisRlsConfiguration}），
 * 所有方法都会经过 {@code RlsTenantBindingInterceptor}——调用前必须由适配层先
 * {@code TenantScope.bind(...)}，否则 fail-closed。
 *
 * <p>SQL 从旧实现逐字搬迁到 {@code AgentReleaseArchiveMapper.xml}，仅 {@code ?} → {@code #{param}}。
 */
public interface AgentReleaseArchiveMapper {

    /**
     * 幂等插入版本行：{@code ON CONFLICT DO NOTHING RETURNING version_id::text}。
     *
     * @return 插入成功时返回版本主键；冲突被跳过时 {@link Optional#empty()}
     */
    Optional<AgentVersionDO> insertVersion(@Param("agentId") String agentId,
                                           @Param("version") int version,
                                           @Param("definition") String definition);

    /** 回查既有版本行主键（{@code ON CONFLICT DO NOTHING} 没返回行时用）。 */
    Optional<AgentVersionDO> findVersionId(@Param("agentId") String agentId,
                                           @Param("version") int version);

    /** 全局 Agent 主体是否存在（{@code agent_version} 外键指向 {@code agent(agent_id)}）。 */
    Integer findAgent(@Param("agentId") String agentId);

    /** 该版本行是否已有快照（幂等补齐前的前置判定）。 */
    Integer findSnapshot(@Param("versionId") String versionId);

    /** 写入执行快照（{@code prompt_config} / {@code tool_contracts} 恒为 {@code '{}'::jsonb}）。 */
    int insertSnapshot(@Param("versionId") String versionId,
                       @Param("agentConfig") String agentConfig,
                       @Param("runConfig") String runConfig,
                       @Param("contextPolicy") String contextPolicy);
}
