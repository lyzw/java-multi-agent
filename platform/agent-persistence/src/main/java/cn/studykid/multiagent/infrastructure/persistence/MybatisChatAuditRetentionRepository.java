package cn.studykid.multiagent.infrastructure.persistence;

import java.time.Instant;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;

import cn.studykid.multiagent.infrastructure.persistence.mapper.ChatAuditRetentionMapper;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditRetentionPort;

/**
 * ChatAuditRetentionPort MyBatis 实现（ctid 子查询批量删）。
 *
 * <p>⚠️ <b>当前刻意不装配</b>：删除审计原文必须用独立的 {@code agent_retention}
 * 凭据，且 app_agent 对原文表已被 REVOKE DELETE；当前 Mapper 绑在租户主数据源上，
 * 切过去会在真正到期执行删除时报权限错误（甚至因 RLS 静默删 0 行）。
 * 删除通道继续走 Jdbc + 隔离数据源。
 */
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
