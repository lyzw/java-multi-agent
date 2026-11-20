package cn.studykid.multiagent.infrastructure.persistence;

import java.util.Set;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.ObjectMapper;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.mapper.AgentChatAuditMapper;
import cn.studykid.multiagent.infrastructure.persistence.rls.TenantScope;
import cn.studykid.multiagent.ports.agentchat.AgentChatAuditPort;

/** AgentChatAuditPort MyBatis 实现（begin 建 run，finish CAS fencing 落终态）。 */
@Repository
@ConditionalOnProperty(name = "persistence.mybatis.enabled", havingValue = "true")
public class MybatisAgentChatAuditRepository implements AgentChatAuditPort {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String LEASE_OWNER = "agent-chat-audit";

    private final AgentChatAuditMapper mapper;
    private final DataSource dataSource;

    public MybatisAgentChatAuditRepository(@NonNull AgentChatAuditMapper mapper,
                                            @Qualifier("dataSource") @NonNull DataSource dataSource) {
        this.mapper = mapper; this.dataSource = dataSource;
    }

    @Override
    public @NonNull AuditHandle begin(@NonNull BeginRecord record) {
        inTenantTx(record.tenantId(), () -> {
            mapper.insertRun(record.runId(), record.tenantId(), record.snapshotId(),
                    record.runId(), record.inputDigest(), record.fencingToken(),
                    LEASE_OWNER, record.leaseExpiresAt(),
                    JSON.writeValueAsString(record.inputPayload()), record.correlationId());
            return null;
        });
        return new AuditHandle(record.runId(), record.tenantId(), record.fencingToken());
    }

    @Override
    public void finish(@NonNull AuditHandle handle, @NonNull FinishRecord record) {
        inTenantTx(handle.tenantId(), () -> {
            int updated = mapper.finishRun(handle.tenantId(), handle.runId(), record.state(),
                    record.completedAt(), record.resultPayload(),
                    record.errorCode(), null, handle.fencingToken());
            if (updated == 0) {
                // ⚠️ 非当前代/已终态：不写事件与步骤，直接返回（fencing CAS 保护）
                return null;
            }
            for (AuditEvent e : record.events()) {
                mapper.insertRunEvent(handle.tenantId(), handle.runId(), e.sequenceNo(),
                        e.eventType(), JSON.writeValueAsString(e.payload()));
            }
            for (AuditStep s : record.steps()) {
                mapper.insertRunStep(handle.tenantId(), handle.runId(), s.stepNumber(),
                        s.stepType(), handle.fencingToken(), s.startedAt(), s.completedAt());
            }
            return null;
        });
    }

    private <T> T inTenantTx(String tenantId, ThrowingSupplier<T> fn) {
        TenantScope.bind(tenantId);
        try {
            return TransactionalConnectionHolder.inTransaction(
                    dataSource, new TenantContext(tenantId, "system", Set.of(), 0L),
                    conn -> {
                        try {
                            return fn.get();
                        } catch (RuntimeException e) {
                            throw e;
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
        } finally {
            TenantScope.clear();
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
