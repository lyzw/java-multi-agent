package cn.studykid.multiagent.infrastructure.persistence;

import java.time.Instant;
import java.util.Set;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.core.security.Action;
import cn.studykid.multiagent.core.security.ResourceRef;
import cn.studykid.multiagent.core.tenant.SubjectContext;
import cn.studykid.multiagent.infrastructure.persistence.mapper.AuthzDecisionLogMapper;
import cn.studykid.multiagent.infrastructure.persistence.rls.TenantScope;
import cn.studykid.multiagent.infrastructure.security.AuthzDecisionRecorder;

/** AuthzDecisionRecorder MyBatis 实现（fail-soft：审计写入失败不阻断授权）。 */
@Repository
// ⚠️ 必须同时尊重决策日志开关：关闭时仍应落到 NoOpAuthzDecisionRecorder，
//    不能因为切了 MyBatis 就悄悄开始写授权决策流水。
@ConditionalOnExpression("'${persistence.mybatis.enabled:false}'.equals('true') "
        + "&& '${agent.authz.decision-log.enabled:false}'.equals('true')")
public class MybatisAuthzDecisionLog implements AuthzDecisionRecorder {

    private static final Logger log = LoggerFactory.getLogger(MybatisAuthzDecisionLog.class);
    private static final String PLATFORM_SCOPE_TENANT = "00000000-0000-0000-0000-000000000000";

    private final AuthzDecisionLogMapper mapper;
    private final DataSource dataSource;

    public MybatisAuthzDecisionLog(@NonNull AuthzDecisionLogMapper mapper,
                                    @Qualifier("dataSource") @NonNull DataSource dataSource) {
        this.mapper = mapper;
        this.dataSource = dataSource;
    }

    /** 与 Jdbc 实现同语义的零行写入探测，供启动期守卫调用。 */
    public boolean probe() {
        try {
            inPlatformScope(() -> mapper.probe());
            return true;
        } catch (RuntimeException e) {
            log.error("授权决策日志零行探测失败: {}", e.toString());
            return false;
        }
    }

    @Override
    public void record(@NonNull SubjectContext subject, @NonNull Action action,
                        @NonNull ResourceRef resource, boolean permitted, @Nullable String reasonCode) {
        try {
            inPlatformScope(() -> mapper.insert(Instant.now(), subject.tenantId(), subject.principalId(),
                    subject.scope().name(), action.name(), resource.type(), permitted, reasonCode));
        } catch (RuntimeException e) {
            // ⚠️ 授权日志是旁路观测，写失败不得影响授权判定本身
            log.warn("授权决策日志写入失败: action={}, resource={}, permitted={}, cause={}",
                    action, resource.type(), permitted, e.toString());
        }
    }

    /**
     * 决策日志表在 platform schema 且没有 RLS；Mapper 却绑在租户工厂上，
     * 因此必须显式提供哨兵租户 + 外层事务，不能裸调（否则 interceptor fail-closed）。
     */
    private <T> T inPlatformScope(java.util.function.Supplier<T> action) {
        TenantScope.bind(PLATFORM_SCOPE_TENANT);
        try {
            return TransactionalConnectionHolder.inTransaction(dataSource,
                    new cn.studykid.multiagent.core.tenant.TenantContext(
                            PLATFORM_SCOPE_TENANT, "system", Set.of(), 0L),
                    conn -> action.get());
        } finally {
            TenantScope.clear();
        }
    }
}
