package cn.studykid.multiagent.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Param;

import cn.studykid.multiagent.infrastructure.persistence.model.RateLimitBucketDO;

/**
 * 限流桶 Mapper（原子 check+count，对应 {@code JdbcRateLimiter} 的两条 SQL）。
 */
public interface RateLimitBucketMapper {

    /** 查询当前窗口计数（floor(now() / windowSeconds) * windowSeconds 对齐）。 */
    Long currentCount(@Param("tenantId") String tenantId,
                      @Param("bucketKey") String bucketKey,
                      @Param("windowSeconds") long windowSeconds);

    /**
     * CTE INSERT ON CONFLICT DO UPDATE RETURNING：
     * 「判定 + 计数」同 SQL 原子完成（唯一索引保证并发不超发）。
     */
    long checkAndIncrement(@Param("tenantId") String tenantId,
                           @Param("bucketKey") String bucketKey,
                           @Param("windowSeconds") long windowSeconds);
}
