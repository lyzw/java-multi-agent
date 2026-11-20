package cn.studykid.multiagent.infrastructure.persistence;

import java.time.Instant;
import java.util.Set;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.mapper.RunStepMapper;
import cn.studykid.multiagent.infrastructure.persistence.rls.TenantScope;
import cn.studykid.multiagent.ports.persistence.RunStepPort;

/**
 * RunStepPort MyBatis-Plus 实现（W3 Run 域，tenantSqlSessionFactory + RLS）。
 *
 * <p>⚠️ <b>核心纪律</b>：步骤写入失败绝不冒泡到执行主流程 —— 与 RunMessagePort 同纪律。
 */
@Repository
public class MybatisRunStepRepository implements RunStepPort {

    private static final Logger log = LoggerFactory.getLogger(MybatisRunStepRepository.class);

    private final RunStepMapper mapper;
    private final DataSource dataSource;

    public MybatisRunStepRepository(@NonNull RunStepMapper mapper,
                                    @Qualifier("dataSource") @NonNull DataSource dataSource) {
        this.mapper = mapper;
        this.dataSource = dataSource;
    }

    @Override
    public void append(@NonNull String tenantId, @NonNull String runId, long fencingToken,
                       int stepNumber, @NonNull String stepType,
                       @NonNull Instant startedAt, @NonNull Instant completedAt) {
        try {
            TenantScope.bind(tenantId);
            try {
                TransactionalConnectionHolder.inTransaction(
                        dataSource, new TenantContext(tenantId, "system", Set.of(), 0L),
                        conn -> {
                            mapper.insertUpsert(tenantId, runId, stepNumber, stepType,
                                    fencingToken, startedAt, completedAt);
                            return null;
                        });
            } finally {
                TenantScope.clear();
            }
        } catch (Exception e) {
            log.warn("⚠️ 写入 run_step 失败（不影响本次执行结果）: runId={}, step={}, cause={}",
                    runId, stepNumber, e.toString());
        }
    }
}
