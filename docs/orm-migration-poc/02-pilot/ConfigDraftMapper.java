package cn.studykid.multiagent.infrastructure.persistence.cap.mapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import cn.studykid.multiagent.infrastructure.persistence.cap.model.ConfigDraftDO;

/**
 * 变更草稿 Mapper（POC，对应主文档 §4.2）。
 *
 * <p>⚠️ 只放租户域方法；继承 {@code BaseMapper} 拿到通用 CRUD。
 * 复杂查询（游标分页 / JSONB / 状态迁移）走 XML，方法签名在此声明。
 *
 * <p>⚠️ 本接口所在的包由租户通道 SqlSessionFactory 扫描（见 RlsGuardConfiguration），
 * 所有方法都会经过 {@code RlsTenantBindingInterceptor}——调用前必须由适配层先
 * {@code TenantScope.bind(...)}，否则 fail-closed。
 */
public interface ConfigDraftMapper extends BaseMapper<ConfigDraftDO> {

    /** 按 scope + tenant_id（IS NOT DISTINCT FROM 兼容 PLATFORM 的 NULL）+ draft_id 查。 */
    Optional<ConfigDraftDO> findById(@Param("scope") String scope,
                                     @Param("tenantId") UUID tenantId,
                                     @Param("draftId") UUID draftId);

    /** 游标分页列表（limit+1 探底，全序排序）。入参多取 1 条由适配层裁掉。 */
    List<ConfigDraftDO> listByCursor(@Param("scope") String scope,
                                     @Param("tenantId") UUID tenantId,
                                     @Param("state") String state,
                                     @Param("cursorCreatedAt") java.time.Instant cursorCreatedAt,
                                     @Param("cursorDraftId") UUID cursorDraftId,
                                     @Param("limitPlusOne") int limitPlusOne);

    /** 状态迁移 CAS：带 expectedState 的条件 UPDATE，返回受影响行数（0 = 并发冲突）。 */
    int transitionState(@Param("scope") String scope,
                        @Param("tenantId") UUID tenantId,
                        @Param("draftId") UUID draftId,
                        @Param("expectedState") String expectedState,
                        @Param("targetState") String targetState,
                        @Param("linkedRequestId") UUID linkedRequestId,
                        @Param("operatorId") String operatorId,
                        @Param("now") java.time.Instant now);
}
