package cn.studykid.multiagent.infrastructure.persistence.mapper;

import java.sql.Timestamp;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import cn.studykid.multiagent.infrastructure.persistence.model.ToolOperationDO;

/**
 * UNKNOWN 操作查询 / 处置 Mapper（对应 {@code UnknownOperationPort}）。
 *
 * <p>⚠️ 结果不是表实体（只取处置台需要的 10 列），故不继承 {@code BaseMapper}。
 *
 * <p>⛔ <b>本接口刻意没有「把状态改回 PENDING / IN_FLIGHT」的语句</b>：
 * UNKNOWN 的含义是「副作用是否发生未知」，重放 = 可能重复施加副作用（Sev-0）。
 *
 * <p>⚠️ 本接口由租户通道 SqlSessionFactory 扫描（见 {@code MybatisRlsConfiguration}），
 * 所有方法都会经过 {@code RlsTenantBindingInterceptor}——调用前必须由适配层先
 * {@code TenantScope.bind(...)}，否则 fail-closed。
 *
 * <p>SQL 从旧实现逐字搬迁到 {@code UnknownOperationMapper.xml}，仅 {@code ?} → {@code #{param}}。
 */
public interface UnknownOperationMapper {

    /** 游标分页列表（limit+1 探底；{@code state} 为 null 表示不限）。 */
    List<ToolOperationDO> list(@Param("tenantId") String tenantId,
                               @Param("state") String state,
                               @Param("cursorCreatedAt") Timestamp cursorCreatedAt,
                               @Param("cursorId") String cursorId,
                               @Param("limitPlusOne") int limitPlusOne);

    /** 单条查询；不存在或不属于该租户时返回 {@code null}（同源纪律）。 */
    ToolOperationDO find(@Param("tenantId") String tenantId,
                         @Param("operationId") String operationId);

    /** 处置前加行锁读取（{@code FOR UPDATE}）；调用方必须处于事务内。 */
    ToolOperationDO lockAndRead(@Param("tenantId") String tenantId,
                                @Param("operationId") String operationId);

    /** 人工处置写入（终态归档，{@code reconciliation_source='MANUAL'}）；返回受影响行数。 */
    int updateDisposition(@Param("tenantId") String tenantId,
                          @Param("operationId") String operationId,
                          @Param("state") String state,
                          @Param("now") Timestamp now,
                          @Param("disposedBy") String disposedBy);
}
