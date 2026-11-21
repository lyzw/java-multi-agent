package cn.studykid.multiagent.infrastructure.persistence;

import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.mapper.RateLimitBucketMapper;
import cn.studykid.multiagent.infrastructure.persistence.rls.TenantScope;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowBinding;
import cn.studykid.multiagent.ports.persistence.RateLimitPort;

/**
 * RateLimitPort MyBatis-Plus 实现（W2 迁移，对应 JdbcRateLimiter）。
 *
 * <p><b>核心不变量保留</b>：check + increment 由同一条 CTE SQL 原子完成
 * （唯一索引 {@code rate_limit_bucket_unique} 保证并发不超发）—— 不拆成两条 check-then-act。
 */
@org.springframework.context.annotation.Primary
@ConditionalOnProperty(name = "persistence.mybatis.enabled", havingValue = "true")
public class MybatisRateLimiter implements RateLimitPort {

    private static final Logger log = LoggerFactory.getLogger(MybatisRateLimiter.class);

    private final RateLimitBucketMapper mapper;
    private final DataSource dataSource;
    private final long limit;
    private final long windowSeconds;
    private final Map<String, Long> bucketLimits;
    private final boolean slidingWindow;

    public MybatisRateLimiter(@NonNull RateLimitBucketMapper mapper,
                              @Qualifier("dataSource") @NonNull DataSource dataSource,
                              long limit, long windowSeconds,
                              @NonNull Map<String, Long> bucketLimits,
                              boolean slidingWindow) {
        this.mapper = mapper;
        this.dataSource = dataSource;
        this.limit = limit;
        this.windowSeconds = windowSeconds;
        this.bucketLimits = Map.copyOf(bucketLimits);
        this.slidingWindow = slidingWindow;
        log.info("[MP] 限流器装配: limit={}, window={}s, slidingWindow={}, 桶覆盖={}",
                limit, windowSeconds, slidingWindow, bucketLimits);
    }

    public long limitFor(@NonNull String bucketKey) {
        return bucketLimits.getOrDefault(bucketKey, limit);
    }

    public @NonNull Decision check(@NonNull String tenantId, @NonNull String bucketKey) {
        long effectiveLimit = limitFor(bucketKey);
        TenantScope.bind(tenantId);
        try {
            boolean permitted = TransactionalConnectionHolder.inTransaction(
                    dataSource,
                    new TenantContext(tenantId, "system", Set.of(), 0L),
                    conn -> {
                        long after = mapper.checkAndIncrement(tenantId, bucketKey, windowSeconds);
                        boolean ok = after <= effectiveLimit;
                        if (!ok && log.isDebugEnabled()) {
                            log.debug("限流拒绝: tenant={}, bucket={}, after={}, limit={}",
                                    tenantId, bucketKey, after, effectiveLimit);
                        }
                        return ok;
                    });
            return permitted ? Decision.ALLOWED : Decision.REJECTED;
        } finally {
            TenantScope.clear();
        }
    }

    public long currentCount(@NonNull String tenantId, @NonNull String bucketKey) {
        TenantScope.bind(tenantId);
        try {
            Long v = TransactionalConnectionHolder.inTransaction(
                    dataSource,
                    new TenantContext(tenantId, "system", Set.of(), 0L),
                    conn -> mapper.currentCount(tenantId, bucketKey, windowSeconds));
            return v == null ? 0L : v;
        } finally {
            TenantScope.clear();
        }
    }

    /** 影子态：W2 批次暂 dormant（零行为变更），后续补齐。 */
    public void bindAuthzShadow(@Nullable AuthzShadowBinding binding) {
        log.info("[MP] RateLimiter 影子态入口已接线（当前批次 dormant）");
    }
}
