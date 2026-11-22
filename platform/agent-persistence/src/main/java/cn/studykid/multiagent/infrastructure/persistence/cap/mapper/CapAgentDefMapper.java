package cn.studykid.multiagent.infrastructure.persistence.cap.mapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.apache.ibatis.annotations.Param;
import org.jspecify.annotations.Nullable;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import cn.studykid.multiagent.infrastructure.persistence.cap.model.CapAgentDefDO;

/**
 * Agent 结构化定义 Mapper（对应 {@code JdbcCapAgentDefRepository}）。
 *
 * <p>七条语句与旧 JDBC 常量一一对应：{@code SQL_INSERT}、{@code SQL_FIND}、
 * {@code SQL_FIND_LATEST}、{@code SQL_FIND_GLOBAL}、{@code SQL_FIND_LATEST_GLOBAL}、
 * {@code SQL_FIND_LATEST_PUBLISHED}、{@code SQL_LIST_LATEST_PER_AGENT}。
 * SQL 逐字搬迁到 {@code CapAgentDefMapper.xml}（仅 {@code ?} → {@code #{param}}）。
 *
 * <p>⚠️ 本接口由租户通道 SqlSessionFactory 扫描，所有方法都经过
 * {@code RlsTenantBindingInterceptor}：调用前必须由适配层先 {@code TenantScope.bind(...)}——
 * 全局 Agent 方法绑定零 UUID 哨兵，租户方法绑定该租户（非客户端输入）。
 */
public interface CapAgentDefMapper extends BaseMapper<CapAgentDefDO> {

    /**
     * SQL_INSERT：不可变版本行；{@code ON CONFLICT (agent_id, version) DO NOTHING} 保留原行。
     * 返回受影响行数（0 = 已有同键版本行，适配层映射为 {@code false}）。
     */
    int insertVersion(@Param("agentId") String agentId,
                      @Param("version") int version,
                      @Param("name") @Nullable String name,
                      @Param("description") @Nullable String description,
                      @Param("roles") String roles,
                      @Param("enabledSkills") String enabledSkills,
                      @Param("skillBundles") String skillBundles,
                      @Param("capabilityTags") String capabilityTags,
                      @Param("overrides") String overrides,
                      @Param("orchestration") String orchestration,
                      @Param("instructions") String instructions,
                      @Param("modelRef") @Nullable String modelRef,
                      @Param("generationParams") String generationParams,
                      @Param("configFingerprint") String configFingerprint,
                      @Param("schemaVersion") int schemaVersion,
                      @Param("createdAt") Instant createdAt,
                      @Param("updatedAt") Instant updatedAt,
                      @Param("publishedAt") @Nullable Instant publishedAt);

    /** SQL_FIND：按 agentId + version 精确读取，带 ACTIVE 绑定存在性过滤。 */
    Optional<CapAgentDefDO> find(@Param("agentId") String agentId,
                                 @Param("version") int version,
                                 @Param("tenantId") String tenantId);

    /** SQL_FIND_GLOBAL：PLATFORM 作用域冲突回查，不走绑定过滤。 */
    Optional<CapAgentDefDO> findGlobal(@Param("agentId") String agentId,
                                       @Param("version") int version);

    /** SQL_FIND_LATEST：最新版本行（含草稿），带 ACTIVE 绑定存在性过滤。 */
    Optional<CapAgentDefDO> findLatest(@Param("agentId") String agentId,
                                       @Param("tenantId") String tenantId);

    /** SQL_FIND_LATEST_GLOBAL：最新版本行，无绑定过滤（全局 Agent 可尚未分配任何租户）。 */
    Optional<CapAgentDefDO> findLatestGlobal(@Param("agentId") String agentId);

    /** SQL_FIND_LATEST_PUBLISHED：最新已发布版本行（published_at 非空），带 ACTIVE 绑定过滤。 */
    Optional<CapAgentDefDO> findLatestPublished(@Param("agentId") String agentId,
                                                @Param("tenantId") String tenantId);

    /** SQL_LIST_LATEST_PER_AGENT：每个已绑定 Agent 一行的最高版本，按 agent_id 升序。 */
    List<CapAgentDefDO> listLatestPerAgent(@Param("tenantId") String tenantId);

    /** SQL_PUBLISH_DRAFT：把未发布草稿行转正；返回影响行数（0 = 无草稿可转正）。 */
    int publishDraft(@Param("agentId") String agentId,
                     @Param("version") int version,
                     @Param("publishedAt") Instant publishedAt);

    /**
     * SQL_UPDATE_DRAFT：仅更新未发布草稿行（全局口径，无绑定过滤）。
     *
     * @return 影响行数；0 = 目标行不存在或已发布
     */
    int updateDraft(@Param("agentId") String agentId,
                    @Param("version") int version,
                    @Param("name") @Nullable String name,
                    @Param("description") @Nullable String description,
                    @Param("roles") String roles,
                    @Param("enabledSkills") String enabledSkills,
                    @Param("skillBundles") String skillBundles,
                    @Param("capabilityTags") String capabilityTags,
                    @Param("overrides") String overrides,
                    @Param("orchestration") String orchestration,
                    @Param("instructions") String instructions,
                    @Param("modelRef") @Nullable String modelRef,
                    @Param("generationParams") String generationParams,
                    @Param("configFingerprint") String configFingerprint,
                    @Param("updatedAt") Instant updatedAt);

    /** SQL_LIST_VERSIONS_GLOBAL：该 Agent 的全部版本行，无绑定过滤，按版本倒序（能力目录版本回溯用）。 */
    List<CapAgentDefDO> listVersionsGlobal(@Param("agentId") String agentId);
}
