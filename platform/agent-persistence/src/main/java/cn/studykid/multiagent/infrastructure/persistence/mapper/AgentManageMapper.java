package cn.studykid.multiagent.infrastructure.persistence.mapper;

import java.util.Optional;

import org.apache.ibatis.annotations.Param;

import cn.studykid.multiagent.infrastructure.persistence.model.AgentIdDO;
import cn.studykid.multiagent.infrastructure.persistence.model.AgentVersionCreatedDO;

/**
 * Agent 管理写路径 Mapper（对应 {@code AgentManagePort} / {@code JdbcAgentManageRepository}）。
 *
 * <p>⚠️ <b>写主导仓</b>：五条语句里四条是写（三条 {@code INSERT ... RETURNING} / 一条
 * {@code INSERT}），仅 {@code agentExists} 是 {@code SELECT 1} 存在性检查。
 * 原实现显式管理连接与提交，这里把提交权交给
 * {@code TransactionalConnectionHolder.inTransaction(...)}，SQL 语义不变。
 *
 * <p>⚠️ 本接口所在的包由租户通道 SqlSessionFactory 扫描（见 {@code MybatisRlsConfiguration}），
 * 所有方法都会经过 {@code RlsTenantBindingInterceptor}——调用前必须由适配层先
 * {@code TenantScope.bind(...)}（{@code tenantId == null} 的全局 Agent 用零 UUID 哨兵），
 * 否则 fail-closed。
 *
 * <p>SQL 从旧实现逐字搬迁到 {@code AgentManageMapper.xml}，仅 {@code ?} → {@code #{param}}。
 */
public interface AgentManageMapper {

    /** 创建全局 Agent 行，回读新主键（{@code INSERT ... RETURNING agent_id::text}）。 */
    Optional<AgentIdDO> insertAgent(@Param("name") String name,
                                    @Param("description") String description);

    /** 建立租户绑定（{@code status = 'ACTIVE'} 由 SQL 常量给出，与旧实现一致）。 */
    int insertBinding(@Param("tenantId") String tenantId,
                      @Param("agentId") String agentId);

    /**
     * 存在性检查：该 Agent 是否已 ACTIVE 绑定到该租户。
     *
     * @return 命中返回 {@code 1}，否则 {@code null}（原实现 {@code rs.next()}）
     */
    Integer agentExists(@Param("tenantId") String tenantId,
                        @Param("agentId") String agentId);

    /**
     * 插入下一版本：版本号 = 该 Agent 当前最大版本号 + 1（全局命名空间），
     * 回读主键、版本号、时间戳与落库后的 definition 文本。
     */
    Optional<AgentVersionCreatedDO> insertVersion(@Param("agentId") String agentId,
                                                  @Param("definition") String definition);

    /** 写入执行快照（{@code prompt_config} / {@code tool_contracts} 恒为 {@code '{}'::jsonb}）。 */
    int insertSnapshot(@Param("versionId") String versionId,
                       @Param("agentConfig") String agentConfig,
                       @Param("runConfig") String runConfig,
                       @Param("contextPolicy") String contextPolicy);
}
