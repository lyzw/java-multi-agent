package cn.studykid.multiagent.infrastructure.persistence.mapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.apache.ibatis.annotations.Param;

import cn.studykid.multiagent.infrastructure.persistence.model.AgentSummaryDO;
import cn.studykid.multiagent.infrastructure.persistence.model.AgentVersionViewDO;

/**
 * Agent 查询 Mapper（对应 {@code AgentQueryPort} / {@code JdbcAgentQueryRepository}）。
 *
 * <p>⚠️ <b>为什么一条语句取全</b>：每个 Agent 都要带「最新版本摘要」，
 * 用 {@code LEFT JOIN LATERAL} 一次算出，避免 N+1 与两段式查询的并发窗口
 * （与旧实现逐字一致，LATERAL 子查询不可拆）。
 *
 * <p>⚠️ 本接口所在的包由租户通道 SqlSessionFactory 扫描（见 {@code MybatisRlsConfiguration}），
 * 所有方法都会经过 {@code RlsTenantBindingInterceptor}——调用前必须由适配层先
 * {@code TenantScope.bind(...)}，否则 fail-closed。
 *
 * <p>SQL 从旧实现逐字搬迁到 {@code AgentQueryMapper.xml}，仅 {@code ?} → {@code #{param}}。
 */
public interface AgentQueryMapper {

    /**
     * 游标分页列表：{@code limit+1} 探底，入参多取 1 条由适配层裁掉。
     *
     * <p>{@code cursorCreatedAt} / {@code cursorAgentId} 同时为 null 时走首页分支
     * （XML 的 {@code <if>} 动态过滤，与旧实现的 {@code StringBuilder} 拼接等价）。
     */
    List<AgentSummaryDO> listAgents(@Param("tenantId") String tenantId,
                                    @Param("cursorCreatedAt") Instant cursorCreatedAt,
                                    @Param("cursorAgentId") String cursorAgentId,
                                    @Param("limitPlusOne") int limitPlusOne);

    /** 单个 Agent（含最新版本摘要）；不存在或不属于该租户时返回空。 */
    Optional<AgentSummaryDO> findAgent(@Param("tenantId") String tenantId,
                                       @Param("agentId") String agentId);

    /** 该 Agent 的全部版本（版本号降序）；租户可见性由 EXISTS 的 ACTIVE 绑定收口。 */
    List<AgentVersionViewDO> listVersions(@Param("agentId") String agentId,
                                          @Param("tenantId") String tenantId);

    /**
     * 删除该 Agent 的未发布草稿版本（{@code published_at IS NULL}）。
     *
     * <p>⚠️ 仅删草稿：已发布版本不可动。返回实际删除行数。
     * 租户可见性由 XML 里 EXISTS 的 ACTIVE 绑定收口。
     */
    int deleteUnpublishedVersions(@Param("agentId") String agentId,
                                 @Param("tenantId") String tenantId);
}
