package cn.studykid.multiagent.infrastructure.persistence;

import java.util.List;
import java.util.Set;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.mapper.ResultQueryMapper;
import cn.studykid.multiagent.infrastructure.persistence.rls.TenantScope;
import cn.studykid.multiagent.ports.persistence.ResultQueryPort;
import cn.studykid.multiagent.ports.persistence.ResultQueryPort.RunResult;
import cn.studykid.multiagent.ports.persistence.ResultQueryPort.Step;
import cn.studykid.multiagent.ports.persistence.ResultQueryPort.Weakness;

/** ResultQueryPort MyBatis 实现（run + weakness + step + tool_operation 四表聚合）。 */
@Repository
@ConditionalOnProperty(name = "persistence.mybatis.enabled", havingValue = "true")
public class MybatisResultQueryRepository implements ResultQueryPort {

    private final ResultQueryMapper mapper;
    private final DataSource dataSource;

    public MybatisResultQueryRepository(@NonNull ResultQueryMapper mapper,
                                         @Qualifier("dataSource") @NonNull DataSource dataSource) {
        this.mapper = mapper; this.dataSource = dataSource;
    }

    @Override
    public @Nullable RunResult findResult(@NonNull String tenantId, @NonNull String runId) {
        TenantScope.bind(tenantId);
        try {
            return TransactionalConnectionHolder.inTransaction(
                    dataSource, new TenantContext(tenantId, "system", Set.of(), 0L),
                    conn -> {
                        ResultQueryMapper.RunRow run = mapper.readRun(tenantId, runId);
                        if (run == null) return null;
                        List<Weakness> weaknesses = mapper.weaknesses(tenantId, runId).stream()
                                .map(w -> new Weakness(w.knowledgePoint(), w.errorType(), java.math.BigDecimal.valueOf(w.confidence()))).toList();
                        List<Step> steps = mapper.steps(tenantId, runId).stream()
                                .map(s -> new Step(s.stepNumber(), s.state(), s.stepType(), s.output())).toList();
                        return new RunResult(run.runId(), run.state(), run.completedAt(),
                                run.errorCode(), run.explanation(), weaknesses, steps,
                                new ResultQueryPort.Reconciliation(false, List.of()));
                    });
        } finally { TenantScope.clear(); }
    }
}
