package cn.studykid.multiagent.ports.agentchat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 通用 Agent 对话审计端口（落 {@code run} / {@code run_event} / {@code run_step}）。
 *
 * <p><b>为什么是两段式而不是「一次性写终态」</b>（2026-11-10 定案）：
 * <ol>
 *   <li>真实工具闸门 {@code ToolInvocationGate} 会写 {@code tool_operation}，
 *       其外键 {@code tool_operation_run_fk} 指向 {@code run(tenant_id, run_id)} —— 
 *       <b>Run 必须在工具调用之前就存在</b>；</li>
 *   <li>因此 {@link #begin} 先落一条 {@code RUNNING} 占位行（无 {@code outbox_event}），
 *       {@link #finish} 再收敛为 {@code SUCCEEDED} / {@code FAILED}；</li>
 *   <li>{@code RUNNING → SUCCEEDED/FAILED} 是 DB 触发器
 *       {@code enforce_run_state_transition()} 明确允许的迁移。</li>
 * </ol>
 *
 * <p><b>铁律：不派发 Worker</b>：
 * <ul>
 *   <li><b>绝不写 {@code outbox_event}</b>：没有唤醒信号，MQ 通道不会被触发；</li>
 *   <li>Run 的 {@code lease_owner} 固定为
 *       {@link cn.studykid.multiagent.ports.persistence.RunClaimPort#SSE_DIRECT_LEASE_OWNER}，
 *       {@code JdbcRunClaimRepository.claimTasks} 显式排除该所有者 —— 即使租约过期，
 *       {@code RunRecoverySweeper} 也捞不到，<b>DB 通道同样不会重复执行</b>；</li>
 *   <li>两层独立保证共同落实「请求级流式 + 落 run 审计，但不发 Outbox / MQ」。</li>
 * </ul>
 *
 * <p><b>RLS</b>：实现必须在事务内、任何业务 SQL 之前
 * {@code set_config('app.tenant_id', ?, true)}（TenantSessionBinder.bind），
 * 且所有 SQL 带 {@code tenant_id} 条件；用 app_agent（NOBYPASSRLS），绝不用 agent_retention。
 *
 * <p><b>不存正文</b>：{@code input_payload} / {@code result_payload} / {@code run_event.payload}
 * 只含标量事实（摘要、计数、状态码、token 数），<b>不存用户问题与模型回答原文</b>，
 * 也不存 thinking / delta 分片。
 */
public interface AgentChatAuditPort {

    /**
     * 建立 {@code RUNNING} 审计占位。必须在任何工具调用之前调用成功。
     *
     * @return 句柄（含 runId 与 fencing token），后续 {@link #finish} 必须原样带回
     */
    @NonNull AuditHandle begin(@NonNull BeginRecord record);

    /**
     * 收敛终态并补齐事件 / 步骤。幂等由「WHERE state='RUNNING' AND fencing_token=?」保证：
     * 已被收敛（如取消）时影响 0 行，实现必须抛异常而不是静默当成功。
     */
    void finish(@NonNull AuditHandle handle, @NonNull FinishRecord record);

    /** 开始记录（全部为结构化标量，禁止对话原文）。 */
    record BeginRecord(
            @NonNull String runId,
            @NonNull String tenantId,
            @NonNull String agentId,
            int agentVersion,
            /** 取自 execution_snapshot.snapshot_id，满足 run_snapshot_fk。 */
            @NonNull String snapshotId,
            /** 输入摘要（sha256 前 64 位），不是原文。 */
            @NonNull String inputDigest,
            @Nullable String scenario,
            /** 只含 channel / scenario / 摘要 / 参数标量的小对象。 */
            @NonNull Map<String, Object> inputPayload,
            /** 链路关联 id（UUID 字符串）。 */
            @NonNull String correlationId,
            long fencingToken,
            @NonNull Instant startedAt,
            /** 租约到期；实际防重靠 lease_owner 排除，本值防御性拉长。 */
            @NonNull Instant leaseExpiresAt) {
    }

    /** 两段式审计句柄。 */
    record AuditHandle(
            @NonNull String runId,
            @NonNull String tenantId,
            long fencingToken) {
    }

    /** 终态记录。 */
    record FinishRecord(
            /** SUCCEEDED / FAILED / CANCELLED / TIMED_OUT。 */
            @NonNull String state,
            /** FAILED 时非空的结构化错误码。 */
            @Nullable String errorCode,
            /** 可空；SUCCEEDED 必须非空（满足 run_result_consistent）。 */
            @Nullable String resultPayload,
            /** run_event 行（sequence_no 从 2 起；1 由 begin 写入）。 */
            @NonNull List<AuditEvent> events,
            /** run_step 行。 */
            @NonNull List<AuditStep> steps,
            @NonNull Instant completedAt) {
    }

    /**
     * run_event 行。payload 只含标量（String / Number / Boolean），
     * 与 {@code JdbcRunEventAppendRepository} 的白名单口径一致。
     */
    record AuditEvent(int sequenceNo, @NonNull String eventType,
                      @NonNull Map<String, Object> payload) {
    }

    /** run_step 行。state 用 RUNNING 表达「该步骤已发生」（与既有 run_step 口径一致）。 */
    record AuditStep(int stepNumber, @NonNull String stepType,
                     @NonNull Instant startedAt, @NonNull Instant completedAt) {
    }
}
