package cn.studykid.multiagent.memory.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;
import cn.studykid.multiagent.infrastructure.persistence.mapper.MemoryMapper;
import cn.studykid.multiagent.infrastructure.persistence.rls.TenantScope;
import cn.studykid.multiagent.memory.domain.MemoryEntry;
import cn.studykid.multiagent.memory.port.MemoryPort;

/** MemoryPort MyBatis 实现（幂等 consolidate：同 last_run_id 不重复 +1）。 */
// 由 MemoryConfiguration 显式 @Bean 注册（不挂 @Repository，避免重复）
@ConditionalOnProperty(name = "persistence.mybatis.enabled", havingValue = "true")
public class MybatisMemoryRepository implements MemoryPort {

    private final MemoryMapper mapper;
    private final DataSource dataSource;

    public MybatisMemoryRepository(@NonNull MemoryMapper mapper,
                                    @Qualifier("dataSource") @NonNull DataSource dataSource) {
        this.mapper = mapper; this.dataSource = dataSource;
    }

    @Override
    public @NonNull List<MemoryEntry> recall(@NonNull String tenantId, @NonNull String studentId,
                                               int k, int maxTokens) {
        return inTx(tenantId, () -> mapper.listMemories(tenantId, studentId).stream()
                .limit(k)
                .map(r -> new MemoryEntry(r.studentId(), r.knowledgePoint(), r.errorType(),
                        r.strength(), r.evidenceCount(), r.firstSeenAt(), r.lastSeenAt(), r.lastRunId()))
                .toList());
    }

    @Override
    public int consolidate(@NonNull String tenantId, @NonNull String studentId, @NonNull String runId) {
        return inTx(tenantId, () -> {
            List<MemoryMapper.WeaknessSeedRow> seeds = mapper.latestWeaknesses(tenantId, studentId, 50);
            int changed = 0;
            Instant now = Instant.now();
            for (MemoryMapper.WeaknessSeedRow s : seeds) {
                Optional<MemoryMapper.MemoryRow> existing =
                        Optional.ofNullable(mapper.findMemory(tenantId, studentId, s.knowledgePoint()));
                if (existing.isEmpty()) {
                    mapper.insertMemory(tenantId, studentId, s.knowledgePoint(), s.errorType(),
                            1.0, 1, s.createdAt() != null ? s.createdAt() : now, now, runId);
                    changed++;
                } else {
                    MemoryMapper.MemoryRow m = existing.get();
                    // ⚠️ 幂等：同 Run 重复整合不累加
                    if (runId.equals(m.lastRunId())) continue;
                    mapper.updateMemory(tenantId, studentId, s.knowledgePoint(), s.errorType(),
                            m.strength() + 1.0, m.evidenceCount() + 1, now, runId);
                    changed++;
                }
            }
            return changed;
        });
    }

    @Override
    public int forget(@NonNull String tenantId, @NonNull String studentId) {
        return inTx(tenantId, () -> mapper.deleteMemories(tenantId, studentId));
    }

    @Override
    public int countMemories(@NonNull String tenantId, @NonNull String studentId) {
        return inTx(tenantId, () -> (int) mapper.countMemories(tenantId, studentId));
    }

    private <T> T inTx(String tenantId, java.util.function.Supplier<T> fn) {
        TenantScope.bind(tenantId);
        try {
            return TransactionalConnectionHolder.inTransaction(
                    dataSource, new TenantContext(tenantId, "system", Set.of(), 0L), conn -> fn.get());
        } finally { TenantScope.clear(); }
    }
}
