package cn.studykid.multiagent.infrastructure.persistence;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;

import tools.jackson.databind.ObjectMapper;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.mapper.RunMessageMapper;
import cn.studykid.multiagent.infrastructure.persistence.rls.TenantScope;
import cn.studykid.multiagent.ports.persistence.RunMessagePort;

/**
 * RunMessagePort MyBatis-Plus 实现（W3 Run 域，tenantSqlSessionFactory + RLS）。
 *
 * <p>⚠️ <b>核心纪律</b>：写入失败绝不冒泡到执行主流程 —— 对话消息是复盘素材，
 * 一次本可成功的辅导不能因「消息没记下」而变成失败。
 *
 * <p>⚠️ <b>幂等键</b>：由 (runId, stepNumber, role, content.size + toolOpId) 拼接 ——
 * 同一轮可能有多条 TOOL 消息（多工具并行），额外含工具标识。
 */
public class MybatisRunMessageRepository implements RunMessagePort {

    private static final Logger log = LoggerFactory.getLogger(MybatisRunMessageRepository.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RunMessageMapper mapper;
    private final DataSource dataSource;

    public MybatisRunMessageRepository(@NonNull RunMessageMapper mapper,
                                       @Qualifier("dataSource") @NonNull DataSource dataSource) {
        this.mapper = mapper;
        this.dataSource = dataSource;
    }

    @Override
    public void append(@NonNull String tenantId, @NonNull String runId, long fencingToken,
                       int stepNumber, @NonNull String role, @NonNull Map<String, Object> content,
                       @Nullable String toolOperationId, @Nullable String modelId,
                       @Nullable Long inputTokens, @Nullable Long outputTokens,
                       @Nullable Long latencyMs) {
        try {
            TenantScope.bind(tenantId);
            try {
                String contentJson = JSON.writeValueAsString(content);
                String idemKey = UUID.nameUUIDFromBytes(
                        (runId + "|" + stepNumber + "|" + role + "|"
                                + (toolOperationId != null ? toolOperationId : "")
                                + "|" + content.size()).getBytes()).toString();
                TransactionalConnectionHolder.inTransaction(
                        dataSource, new TenantContext(tenantId, "system", Set.of(), 0L),
                        conn -> {
                            mapper.insertUpsert(tenantId, runId, stepNumber, role, contentJson,
                                    toolOperationId, modelId, inputTokens, outputTokens,
                                    latencyMs, fencingToken, idemKey);
                            return null;
                        });
            } finally {
                TenantScope.clear();
            }
        } catch (Exception e) {
            log.warn("⚠️ 写入 run_message 失败（不影响本次执行结果）: runId={}, step={}, role={}, cause={}",
                    runId, stepNumber, role, e.toString());
        }
    }
}
