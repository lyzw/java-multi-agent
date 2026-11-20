package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.util.Optional;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.core.cap.ConfigPointer;
import cn.studykid.multiagent.core.cap.ConfigRelease;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;
import cn.studykid.multiagent.infrastructure.persistence.cap.mapper.ConfigPointerMapper;
import cn.studykid.multiagent.infrastructure.persistence.cap.model.ConfigPointerDO;
import cn.studykid.multiagent.infrastructure.persistence.rls.TenantScope;
import cn.studykid.multiagent.ports.persistence.ConfigPointerPort;

/**
 * {@link ConfigPointerPort} 的 MyBatis-Plus 实现（W2 迁移，对应 {@code JdbcConfigPointerRepository}）。
 *
 * <p>Port 签名零改动；SQL 逐字搬迁到 {@code ConfigPointerMapper.xml}，仅 {@code ?} → {@code #{param}}。
 * 旧 JDBC 的三步 CAS 流程（UPDATE → 存在性判断 → ON CONFLICT 补插）与日志、语义完全保留。
 *
 * <p>⚠️ <b>默认关闭</b>：仅当 {@code persistence.mybatis.enabled=true} 时注册为 Bean；
 * 关闭时旧 Jdbc 实现生效（旧类带互补条件，matchIfMissing=true）。
 *
 * <p><b>RLS 保证</b>：每个方法在 {@link TenantScope} 内执行（try/finally 无条件清理），
 * 并经由 {@link TransactionalConnectionHolder#inTransaction} 借用同一条事务连接；
 * PLATFORM 作用域绑定零 UUID 哨兵，与原实现一致。
 */
@Repository
@org.springframework.context.annotation.Primary
@ConditionalOnProperty(name = "persistence.mybatis.enabled", havingValue = "true")
public class MybatisConfigPointerRepository implements ConfigPointerPort {

    private static final Logger log = LoggerFactory.getLogger(MybatisConfigPointerRepository.class);

    /** PLATFORM 作用域的租户哨兵：零 UUID（与原 Jdbc 实现同值）。 */
    private static final String PLATFORM_SCOPE_TENANT = "00000000-0000-0000-0000-000000000000";

    private final ConfigPointerMapper mapper;
    private final DataSource dataSource;

    public MybatisConfigPointerRepository(@NonNull ConfigPointerMapper mapper,
                                          @Qualifier("dataSource") @NonNull DataSource dataSource) {
        this.mapper = mapper;
        this.dataSource = dataSource;
    }

    @Override
    @NonNull
    public Optional<ConfigPointer> current(ConfigRelease.@NonNull Scope scope,
                                           @Nullable String tenantId) {
        String key = ConfigPointer.pointerKey(scope, tenantId);
        return inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                dataSource, context(tenantId),
                conn -> mapper.findByKey(key).map(MybatisConfigPointerRepository::toRecord)));
    }

    @Override
    public boolean compareAndSet(ConfigRelease.@NonNull Scope scope,
                                  @Nullable String tenantId,
                                  @Nullable Long expectedPublishSeq,
                                  long newReleaseId,
                                  long newPublishSeq) {
        String key = ConfigPointer.pointerKey(scope, tenantId);
        return inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                dataSource, context(tenantId), conn -> {
                    // ── 第一步：基于 expected 基线的单行更新 ──
                    if (mapper.casUpdate(key, newReleaseId, newPublishSeq, expectedPublishSeq) == 1) {
                        return Boolean.TRUE;
                    }

                    // ── 第二步：0 行 —— 要么指针不存在，要么基线已变 ──
                    //    只有「指针确实不存在 且 expected 为 null（首次发布）」才允许补插。
                    //    若指针存在而 expected 为 null，同样是基线冲突（调用方以为还没发布过），
                    //    必须失败 —— 否则会覆盖掉别人已经发布的当前指针。
                    boolean exists = mapper.findByKey(key).isPresent();
                    if (exists || expectedPublishSeq != null) {
                        log.debug("指针 CAS 失败（基线已变）: key={}, expected={}", key, expectedPublishSeq);
                        return Boolean.FALSE;
                    }

                    // ── 第三步：首次发布 —— 补插指针行 ──
                    //    ON CONFLICT DO NOTHING：并发首次发布时只有一个成功，
                    //    另一个拿到 0 行 → CAS 失败（§6.3 第 4 条：仅一次成功）。
                    return mapper.insertIfAbsent(key, scope.name(), tenantId,
                            newReleaseId, newPublishSeq) == 1;
                }));
    }

    /** 在租户作用域内执行：绑定 → 执行 → 无条件清理。 */
    private <T> T inTenantScope(@Nullable String tenantId, java.util.function.Supplier<T> action) {
        TenantScope.bind(tenantId == null ? PLATFORM_SCOPE_TENANT : tenantId);
        try {
            return action.get();
        } finally {
            TenantScope.clear();
        }
    }

    /**
     * 构造租户上下文（与原 Jdbc 实现逐字一致）。
     *
     * <p>⚠️ PLATFORM 作用域绑定零 UUID 哨兵（而非 {@code null}）：
     * {@code current_tenant_id()} 的实现是
     * {@code NULLIF(current_setting('app.tenant_id', TRUE), '')::UUID}，
     * 绑非 UUID 文本会让类型转换直接报错。
     */
    private static TenantContext context(@Nullable String tenantId) {
        return new TenantContext(tenantId == null ? PLATFORM_SCOPE_TENANT : tenantId,
                "system", java.util.Set.of(), 0L);
    }

    private static ConfigPointer toRecord(ConfigPointerDO d) {
        // 与原 ResultSet 映射同口径：以 current_publish_seq 是否为空判定「是否已发布」，
        // 两个字段同有或同空（DB CHECK 保证，避免半填状态）。
        boolean hasRelease = d.getCurrentPublishSeq() != null;
        return new ConfigPointer(
                d.getPointerKey(),
                ConfigRelease.Scope.valueOf(d.getScope()),
                d.getTenantId(),
                hasRelease ? d.getCurrentReleaseId() : null,
                hasRelease ? d.getCurrentPublishSeq() : null,
                d.getUpdatedAt());
    }
}
