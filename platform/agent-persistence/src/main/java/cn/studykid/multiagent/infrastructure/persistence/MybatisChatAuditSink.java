package cn.studykid.multiagent.infrastructure.persistence;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.ObjectMapper;

import cn.studykid.multiagent.infrastructure.persistence.mapper.ChatAuditSinkMapper;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditEnvelope;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditRecord;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditRejected;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditSinkPort;

/** ChatAuditSinkPort MyBatis 实现（sealed switch 分派两形态）。 */
@Repository
@ConditionalOnProperty(name = "persistence.mybatis.enabled", havingValue = "true")
public class MybatisChatAuditSink implements ChatAuditSinkPort {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatAuditSinkMapper mapper;

    public MybatisChatAuditSink(@NonNull ChatAuditSinkMapper mapper,
                                 @Qualifier("dataSource") @NonNull DataSource dataSource) {
        this.mapper = mapper;
    }

    @Override
    public @NonNull String name() {
        return "mybatis";
    }

    @Override
    public void write(@NonNull ChatAuditRecord record) {
        String json = MAPPER.writeValueAsString(record);
        switch (record) {
            case ChatAuditEnvelope env -> mapper.writeAttributed(
                    env.auditId(), env.traceId(), env.tenantId(), env.domain(), env.endpoint(),
                    env.principalId(), env.startedAt(), env.completedAt(), json);
            case ChatAuditRejected rej -> mapper.writeUnattributed(
                    rej.auditId(), rej.traceId(), rej.startedAt(), rej.endpoint(),
                    rej.httpMethod(), rej.httpStatus(), json);
        }
    }
}
