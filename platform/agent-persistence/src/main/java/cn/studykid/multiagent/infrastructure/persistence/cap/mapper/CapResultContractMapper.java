package cn.studykid.multiagent.infrastructure.persistence.cap.mapper;

import java.time.Instant;
import java.util.Optional;

import org.apache.ibatis.annotations.Param;
import org.jspecify.annotations.Nullable;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import cn.studykid.multiagent.infrastructure.persistence.cap.model.CapResultContractDO;

/**
 * 输出契约 Mapper（对应 {@code JdbcCapResultContractRepository}）。
 *
 * <p>四条语句与旧 JDBC 常量一一对应：{@code SQL_INSERT}、{@code SQL_FIND}、
 * {@code SQL_FIND_LATEST_PUBLISHED}、{@code SQL_FIND_LATEST}。
 * SQL 逐字搬迁到 {@code CapResultContractMapper.xml}（仅 {@code ?} → {@code #{param}}）。
 *
 * <p>⚠️ 本接口由租户通道 SqlSessionFactory 扫描，所有方法都经过
 * {@code RlsTenantBindingInterceptor}：调用前必须由适配层先 {@code TenantScope.bind(tenantId)}
 * ——外层 SQL 的 {@code tenant_id = #{tenantId}::uuid} 与 RLS 策略双保险。
 */
public interface CapResultContractMapper extends BaseMapper<CapResultContractDO> {

    /**
     * SQL_INSERT：不可变版本行；{@code ON CONFLICT (tenant_id, contract_id, version) DO NOTHING}。
     * 返回受影响行数（0 = 已有同键版本行，适配层映射为 {@code false}）。
     *
     * <p>⚠️ {@code tenantId} 带 {@code ::uuid} cast，{@code contractId} 带 {@code ::text}
     * cast（可读字符串，非 UUID）—— 与旧 JDBC 逐字一致。
     */
    int insertVersion(@Param("tenantId") String tenantId,
                      @Param("contractId") String contractId,
                      @Param("version") int version,
                      @Param("schemaJson") String schemaJson,
                      @Param("configFingerprint") String configFingerprint,
                      @Param("schemaVersion") int schemaVersion,
                      @Param("createdAt") Instant createdAt,
                      @Param("publishedAt") @Nullable Instant publishedAt);

    /** SQL_FIND：按 tenant_id + contract_id + version 精确读取。 */
    Optional<CapResultContractDO> find(@Param("tenantId") String tenantId,
                                       @Param("contractId") String contractId,
                                       @Param("version") int version);

    /** SQL_FIND_LATEST_PUBLISHED：已发布版本（published_at 非空）中取最高版本。 */
    Optional<CapResultContractDO> findLatestPublished(@Param("tenantId") String tenantId,
                                                      @Param("contractId") String contractId);

    /** SQL_FIND_LATEST：最新版本（含草稿），仅用于分配下一个版本号。 */
    Optional<CapResultContractDO> findLatest(@Param("tenantId") String tenantId,
                                             @Param("contractId") String contractId);
}
