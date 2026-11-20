package cn.studykid.multiagent.infrastructure.persistence.cap.mapper;

import java.time.Instant;
import java.util.Optional;

import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import cn.studykid.multiagent.infrastructure.persistence.cap.model.CapabilitySnapshotDO;

/**
 * Run 级能力闭包快照 Mapper（对应 {@code JdbcCapabilitySnapshotRepository}）。
 *
 * <p>两条语句与旧 JDBC 常量一一对应：{@code SQL_INSERT}、{@code SQL_FIND}。
 * SQL 逐字搬迁到 {@code CapabilitySnapshotMapper.xml}（仅 {@code ?} -> {@code #{param}}）。
 *
 * <p>⚠️ 本接口由租户通道 SqlSessionFactory 扫描，所有方法都经过
 * {@code RlsTenantBindingInterceptor}：调用前必须由适配层先 {@code TenantScope.bind(tenantId)}。
 */
public interface CapabilitySnapshotMapper extends BaseMapper<CapabilitySnapshotDO> {

    /**
     * SQL_INSERT：写入快照。
     *
     * <p>⚠️ {@code ON CONFLICT (run_id) DO NOTHING} 而非 DO UPDATE：同一 runId 重复保存
     * 以先写入者为准（快照一经固化不可变）。返回受影响行数（0 = 已存在快照）。
     */
    int insertIfAbsent(@Param("runId") String runId,
                       @Param("tenantId") String tenantId,
                       @Param("agentId") String agentId,
                       @Param("effectiveClosure") String effectiveClosure,
                       @Param("agentVersion") int agentVersion,
                       @Param("configVersion") long configVersion,
                       @Param("configFingerprint") String configFingerprint,
                       @Param("authzVersion") long authzVersion,
                       @Param("createdAt") Instant createdAt);

    /** SQL_FIND：按 tenant_id + run_id 读取快照。 */
    Optional<CapabilitySnapshotDO> find(@Param("tenantId") String tenantId,
                                        @Param("runId") String runId);
}
