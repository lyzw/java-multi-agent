package cn.studykid.multiagent.infrastructure.persistence.cap.mapper;

import java.time.Instant;
import java.util.Optional;

import org.apache.ibatis.annotations.Param;
import org.jspecify.annotations.Nullable;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import cn.studykid.multiagent.infrastructure.persistence.cap.model.CapRoutingPolicyDO;

/**
 * 路由策略 Mapper（对应 {@code JdbcCapRoutingPolicyRepository}）。
 *
 * <p>四条语句与旧 JDBC 常量一一对应：{@code SQL_INSERT}、{@code SQL_FIND}、
 * {@code SQL_FIND_LATEST_PUBLISHED}、{@code SQL_FIND_LATEST}。
 * SQL 逐字搬迁到 {@code CapRoutingPolicyMapper.xml}（仅 {@code ?} → {@code #{param}}）。
 *
 * <p>三条查询共用同一投影列（旧 JDBC 的 {@code COLUMNS} 常量），包括
 * {@code lifecycle_state::text} —— 读不到「停用」状态绝不能被降级成「没有停用」。
 *
 * <p>⚠️ 本接口由租户通道 SqlSessionFactory 扫描，所有方法都经过
 * {@code RlsTenantBindingInterceptor}：调用前必须由适配层先 {@code TenantScope.bind(tenantId)}
 * ——外层 SQL 的 {@code tenant_id = #{tenantId}::uuid} 与 RLS 策略双保险。
 */
public interface CapRoutingPolicyMapper extends BaseMapper<CapRoutingPolicyDO> {

    /**
     * SQL_INSERT：不可变版本行；{@code ON CONFLICT (tenant_id, policy_id, version) DO NOTHING}。
     * 返回受影响行数（0 = 已有同键版本行，适配层映射为 {@code false}）。
     *
     * <p>⚠️ 只有 tenantId 带 {@code ::uuid} cast；{@code policyId} 是可读字符串，不能加 cast。
     * {@code mode} / {@code noMatchAction} / {@code onConflict} 写的是枚举 {@code name()}。
     */
    int insertVersion(@Param("tenantId") String tenantId,
                      @Param("policyId") String policyId,
                      @Param("version") int version,
                      @Param("mode") String mode,
                      @Param("inputSchema") String inputSchema,
                      @Param("evaluation") String evaluation,
                      @Param("defaultTarget") @Nullable String defaultTarget,
                      @Param("noMatchAction") String noMatchAction,
                      @Param("onConflict") String onConflict,
                      @Param("sampleSet") String sampleSet,
                      @Param("configFingerprint") String configFingerprint,
                      @Param("schemaVersion") int schemaVersion,
                      @Param("createdAt") Instant createdAt,
                      @Param("publishedAt") @Nullable Instant publishedAt);

    /** SQL_FIND：按 tenant_id + policy_id + version 精确读取。 */
    Optional<CapRoutingPolicyDO> find(@Param("tenantId") String tenantId,
                                      @Param("policyId") String policyId,
                                      @Param("version") int version);

    /** SQL_FIND_LATEST_PUBLISHED：已发布版本（published_at 非空）中取最高版本。 */
    Optional<CapRoutingPolicyDO> findLatestPublished(@Param("tenantId") String tenantId,
                                                     @Param("policyId") String policyId);

    /** SQL_FIND_LATEST：最新版本（含草稿），仅用于分配下一个版本号。 */
    Optional<CapRoutingPolicyDO> findLatest(@Param("tenantId") String tenantId,
                                            @Param("policyId") String policyId);
}
