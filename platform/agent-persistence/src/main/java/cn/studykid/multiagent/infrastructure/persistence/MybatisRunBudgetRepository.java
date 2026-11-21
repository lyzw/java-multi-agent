package cn.studykid.multiagent.infrastructure.persistence;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.mapper.RunBudgetMapper;
import cn.studykid.multiagent.infrastructure.persistence.rls.TenantScope;
import cn.studykid.multiagent.ports.persistence.RunBudgetPort;
import cn.studykid.multiagent.ports.persistence.RunBudgetPort.RunBudget;

@org.springframework.context.annotation.Primary
@ConditionalOnProperty(name = "persistence.mybatis.enabled", havingValue = "true")
public class MybatisRunBudgetRepository implements RunBudgetPort {

    private final RunBudgetMapper mapper;
    private final DataSource dataSource;

    public MybatisRunBudgetRepository(@NonNull RunBudgetMapper mapper,
                                      @Qualifier("dataSource") @NonNull DataSource dataSource) {
        this.mapper = mapper;
        this.dataSource = dataSource;
    }

    @Override
    public @NonNull Optional<RunBudget> find(@NonNull String tenantId, @NonNull String runId) {
        TenantScope.bind(tenantId);
        try {
            return TransactionalConnectionHolder.inTransaction(
                    dataSource, new TenantContext(tenantId, "system", Set.of(), 0L),
                    conn -> mapper.find(tenantId, runId).map(r -> new RunBudget(
                            runId, orZero(r.getBudgetAmount()),
                            orZero(r.getReservedAmount()), orZero(r.getSettledAmount()))));
        } finally {
            TenantScope.clear();
        }
    }

    private static BigDecimal orZero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
