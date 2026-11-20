package cn.studykid.multiagent.infrastructure.persistence;

import java.time.Instant;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.infrastructure.persistence.mapper.ChatAuditRetentionMapper;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditRetentionPort;

/** ChatAuditRetentionPort MyBatis 实现（ctid 子查询批量删）。 */
@Repository
@ConditionalOnProperty(name = "persistence.mybatis.enabled", havingValue = "true")
public class MybatisChatAuditRetentionRepository implements ChatAuditRetentionPort {

    private final ChatAuditRetentionMapper mapper;

    public MybatisChatAuditRetentionRepository(@NonNull ChatAuditRetentionMapper mapper,
                                                @Qualifier("dataSource") @NonNull DataSource dataSource) {
        this.mapper = mapper;
    }

    @Override
    public int purgeExpired(@NonNull String tenantId, @NonNull Instant cutoff, int limit) {
        return mapper.purgeExpired(tenantId, cutoff, limit);
    }
}
