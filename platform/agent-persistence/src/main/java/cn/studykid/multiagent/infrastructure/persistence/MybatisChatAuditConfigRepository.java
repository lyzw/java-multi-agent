package cn.studykid.multiagent.infrastructure.persistence;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.ObjectMapper;

import cn.studykid.multiagent.infrastructure.persistence.mapper.ChatAuditConfigMapper;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditConfig;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditConfig.State;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditConfigPort;

/** ChatAuditConfigPort MyBatis 实现（config::text JSON 存取 + 版本递增）。 */
@Repository
@ConditionalOnProperty(name = "persistence.mybatis.enabled", havingValue = "true")
public class MybatisChatAuditConfigRepository implements ChatAuditConfigPort {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatAuditConfigMapper mapper;

    public MybatisChatAuditConfigRepository(@NonNull ChatAuditConfigMapper mapper,
                                             @Qualifier("dataSource") @NonNull DataSource dataSource) {
        this.mapper = mapper;
    }

    @Override
    public @NonNull State get() {
        ChatAuditConfigMapper.ConfigRow row = mapper.get();
        if (row == null) {
            return new State(ChatAuditConfig.defaults(), 0L, null, null);
        }
        ChatAuditConfig config = parse(row.configJson());
        return new State(config, row.configVersion(), row.updatedBy(), row.updatedAt());
    }

    @Override
    public @NonNull State save(@NonNull ChatAuditConfig config, @NonNull String actorId) {
        String json = MAPPER.writeValueAsString(config);
        mapper.upsert(config.enabled(), json, actorId);
        return get();
    }

    private static ChatAuditConfig parse(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return ChatAuditConfig.defaults();
        }
        try {
            return MAPPER.readValue(json, ChatAuditConfig.class);
        } catch (RuntimeException e) {
            // ⚠️ 配置损坏时 fail-closed 到默认（默认 disabled），不放大为 500
            return ChatAuditConfig.defaults();
        }
    }
}
