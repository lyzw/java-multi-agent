package cn.studykid.multiagent.infrastructure.persistence.cap.mapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import cn.studykid.multiagent.infrastructure.persistence.cap.model.ConfigReleaseDO;

/**
 * 配置发布头 Mapper（W2 迁移，对应 {@code JdbcConfigReleaseRepository}）。
 *
 * <p>六条语句与旧 JDBC 常量一一对应：{@code SQL_INSERT}、{@code SQL_FIND_BY_SEQ}、
 * {@code SQL_FIND_LATEST_PUBLISHED}、{@code SQL_FIND_BY_ID}、{@code SQL_MARK_ROLLED_BACK}、
 * {@code SQL_LIST}（含动态游标与 ORDER BY / LIMIT）。
 *
 * <p>⚠️ 本接口由租户通道 SqlSessionFactory 加载，所有方法都经过
 * {@code RlsTenantBindingInterceptor}：调用前必须由适配层先 {@code TenantScope.bind(...)}。
 */
public interface ConfigReleaseMapper extends BaseMapper<ConfigReleaseDO> {

    /** SQL_INSERT：序号由 DB 序列分配，RETURNING 取回 release_id / seq / created_at。 */
    Optional<ConfigReleaseDO> insertRelease(@Param("scope") String scope,
                                            @Param("tenantId") String tenantId,
                                            @Param("objectVersions") String objectVersions,
                                            @Param("aggregateFingerprint") String aggregateFingerprint,
                                            @Param("status") String status,
                                            @Param("requesterId") String requesterId,
                                            @Param("approverId") String approverId,
                                            @Param("effectiveAt") Instant effectiveAt,
                                            @Param("createdAt") Instant createdAt);

    /** SQL_FIND_BY_SEQ：按全局单调发布序号读取（租户上下文由 Interceptor 注入）。 */
    Optional<ConfigReleaseDO> findByPublishSeq(@Param("configPublishSeq") long configPublishSeq);

    /** SQL_FIND_LATEST_PUBLISHED：先过滤 status='PUBLISHED'，再按 seq 取最新一条。 */
    Optional<ConfigReleaseDO> findLatestPublished(@Param("scope") String scope,
                                                  @Param("tenantId") String tenantId);

    /** SQL_FIND_BY_ID：按 id 读取，不带租户过滤；边界由 RLS 读策略保证。 */
    Optional<ConfigReleaseDO> findByReleaseId(@Param("releaseId") long releaseId);

    /** SQL_MARK_ROLLED_BACK：仅当仍被指针指向且当前为 PUBLISHED 才标记，返回受影响行数。 */
    int markRolledBack(@Param("releaseId") long releaseId);

    /** SQL_LIST + 动态游标：全序 (created_at, release_id) 降序，入参已含 limit+1。 */
    List<ConfigReleaseDO> listReleases(@Param("tenantId") String tenantId,
                                       @Param("cursorCreatedAt") Instant cursorCreatedAt,
                                       @Param("cursorId") String cursorId,
                                       @Param("limitPlusOne") int limitPlusOne);
}
