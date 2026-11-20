package cn.studykid.multiagent.infrastructure.persistence;

import java.util.Set;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.mapper.RiskEventMapper;
import cn.studykid.multiagent.infrastructure.persistence.rls.TenantScope;
import cn.studykid.multiagent.ports.persistence.RiskEventPort;

/**
 * RiskEventPort MyBatis-Plus 实现（W3 Run 域，tenantSqlSessionFactory + RLS）。
 *
 * <p>⚠️ agent_risk_event 是 platform schema 但带 tenant_id 列 ——
 * 走主数据源 RLS，由 RlsTenantBindingInterceptor 保证租户隔离。
 *
 * <p>⚠️ <b>核心纪律</b>：安全事件写入失败绝不冒泡到执行主流程 ——
 * 一次本可成功的辅导不能因「风险事件没记下」而变成失败。
 */
@Repository
public class MybatisRiskEventRepository implements RiskEventPort {

    private static final Logger log = LoggerFactory.getLogger(MybatisRiskEventRepository.class);

    private final RiskEventMapper mapper;
    private final DataSource dataSource;

    public MybatisRiskEventRepository(@NonNull RiskEventMapper mapper,
                                      @Qualifier("dataSource") @NonNull DataSource dataSource) {
        this.mapper = mapper;
        this.dataSource = dataSource;
    }

    @Override
    public void record(@NonNull String tenantId, @NonNull String runId,
                       @NonNull String eventType, @Nullable String detailDigest) {
        try {
            TenantScope.bind(tenantId);
            try {
                TransactionalConnectionHolder.inTransaction(
                        dataSource, new TenantContext(tenantId, "system", Set.of(), 0L),
                        conn -> {
                            mapper.insert(runId, tenantId, eventType, detailDigest);
                            return null;
                        });
            } finally {
                TenantScope.clear();
            }
        } catch (Exception e) {
            log.warn("⚠️ 写入安全事件失败（不影响本次执行结果）: runId={}, type={}, cause={}",
                    runId, eventType, e.toString());
        }
    }
}
