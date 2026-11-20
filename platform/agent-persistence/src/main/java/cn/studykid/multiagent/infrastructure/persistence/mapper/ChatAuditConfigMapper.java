package cn.studykid.multiagent.infrastructure.persistence.mapper;

import java.time.Instant;

import org.apache.ibatis.annotations.Param;

/** public.chat_audit_config 单行配置（JSON 存储）。 */
public interface ChatAuditConfigMapper {
    ConfigRow get();
    int upsert(@Param("enabled") boolean enabled, @Param("configJson") String configJson,
                @Param("updatedBy") String updatedBy);

    record ConfigRow(String configJson, long configVersion, String updatedBy,
                     Instant updatedAt, boolean enabled) {}
}
