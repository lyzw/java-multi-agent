package cn.studykid.multiagent.infrastructure.persistence.cap.mapper;

import java.util.Optional;

import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import cn.studykid.multiagent.infrastructure.persistence.cap.model.ConfigPointerDO;

/**
 * 当前发布指针 Mapper（W2 迁移，对应 {@code JdbcConfigPointerRepository}）。
 *
 * <p>三条语句与旧 JDBC 一一对应：
 * <ol>
 *   <li>{@link #findByKey} —— SQL_SELECT（{@code current} 与 CAS 第二步的存在性判断共用）；</li>
 *   <li>{@link #casUpdate} —— SQL_UPDATE（{@code IS NOT DISTINCT FROM} 基线比较）；</li>
 *   <li>{@link #insertIfAbsent} —— SQL_INSERT（{@code ON CONFLICT (pointer_key) DO NOTHING}）。</li>
 * </ol>
 *
 * <p>⚠️ 本接口由租户通道 SqlSessionFactory 加载，所有方法都经过
 * {@code RlsTenantBindingInterceptor}：调用前必须由适配层先 {@code TenantScope.bind(...)}。
 */
public interface ConfigPointerMapper extends BaseMapper<ConfigPointerDO> {

    /** 按单键读取指针（含 PLATFORM 行；租户边界由 RLS + Interceptor 保证）。 */
    Optional<ConfigPointerDO> findByKey(@Param("pointerKey") String pointerKey);

    /**
     * CAS 的单行更新：仅当 {@code current_publish_seq IS NOT DISTINCT FROM expected} 时命中。
     * 返回受影响行数；0 行 = 指针不存在或基线已变，由适配层区分处理。
     */
    int casUpdate(@Param("pointerKey") String pointerKey,
                  @Param("newReleaseId") long newReleaseId,
                  @Param("newPublishSeq") long newPublishSeq,
                  @Param("expectedPublishSeq") Long expectedPublishSeq);

    /** 首次发布补插指针；并发下只有一个成功（ON CONFLICT DO NOTHING）。 */
    int insertIfAbsent(@Param("pointerKey") String pointerKey,
                       @Param("scope") String scope,
                       @Param("tenantId") String tenantId,
                       @Param("newReleaseId") long newReleaseId,
                       @Param("newPublishSeq") long newPublishSeq);
}
