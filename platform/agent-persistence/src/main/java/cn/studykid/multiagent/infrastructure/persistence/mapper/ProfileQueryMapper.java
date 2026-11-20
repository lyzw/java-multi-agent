package cn.studykid.multiagent.infrastructure.persistence.mapper;

import java.util.List;
import java.util.Optional;

import org.apache.ibatis.annotations.Param;

import cn.studykid.multiagent.infrastructure.persistence.model.StudentProfileDO;
import cn.studykid.multiagent.infrastructure.persistence.model.WeaknessRecordDO;

/**
 * 学习画像查询 Mapper（对应 {@code ProfileQueryPort} / {@code JdbcProfileQueryRepository}）。
 *
 * <p>⚠️ <b>{@code tenant_id} 条件不可删除</b>（即便 RLS 已隔离，应用侧条件仍是第一道防线）。
 *
 * <p>⚠️ 本接口所在的包由租户通道 SqlSessionFactory 扫描（见 {@code MybatisRlsConfiguration}），
 * 所有方法都会经过 {@code RlsTenantBindingInterceptor}——调用前必须由适配层先
 * {@code TenantScope.bind(...)}，否则 fail-closed。
 *
 * <p>SQL 从旧实现逐字搬迁到 {@code ProfileQueryMapper.xml}，仅 {@code ?} → {@code #{param}}。
 */
public interface ProfileQueryMapper {

    /** 画像主行；学生不存在时返回空（由适配层转成空画像，不抛异常 —— 防枚举）。 */
    Optional<StudentProfileDO> loadProfile(@Param("tenantId") String tenantId,
                                           @Param("studentId") String studentId);

    /** 薄弱点列表（{@code ORDER BY updated_at DESC LIMIT 50} 由 SQL 给出，与旧实现一致）。 */
    List<WeaknessRecordDO> loadWeaknesses(@Param("tenantId") String tenantId,
                                          @Param("studentId") String studentId);
}
