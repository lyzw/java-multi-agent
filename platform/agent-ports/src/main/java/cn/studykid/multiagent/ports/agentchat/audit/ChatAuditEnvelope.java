package cn.studykid.multiagent.ports.agentchat.audit;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 对话审计记录信封（一次请求一条，完整还原从头到尾）。
 *
 * <p><b>⚠️ 纯数据契约</b>：放 ports 层，不依赖 Jackson / Spring。
 * 序列化由基础设施层的 Jackson 3 完成（写入 DB 的 jsonb 与目录的 JSON 文件共用同一信封）。
 *
 * <p><b>⚠️ 逐分片</b>：{@link Chunk} 按到达顺序带序号与时间戳单独记录（决策 4）。
 * 每条 event 记录 SSE 帧名 + data 原文（14 类事件全覆盖，含原文）。
 */
public record ChatAuditEnvelope(
        @NonNull String auditId,
        @NonNull String traceId,
        @NonNull String endpoint,
        @NonNull String domain,
        @Nullable String tenantId,
        @Nullable String principalId,
        @NonNull Instant startedAt,
        @Nullable Instant completedAt,
        long durationMs,
        @NonNull String terminalState,
        long configVersion,
        @NonNull Http http,
        @Nullable RequestInfo request,
        @NonNull List<Frame> events,
        @NonNull List<Chunk> chunks,
        @Nullable Result result,
        @Nullable ErrorInfo error,
        boolean bodyTruncated,
        boolean answerTruncated) implements ChatAuditRecord {

    /**
     * 平台主体直连对话（无目标租户）的租户占位值。
     *
     * <p><b>⚠️ 刻意用 nil UUID 而不是 NULL</b>：{@code chat_audit_record.tenant_id}
     * 是 NOT NULL + FORCE RLS，谓词写成 {@code tenant_id = current_tenant_id()}；
     * 若允许 NULL，该谓词恒为 NULL ⇒ 记录既写不进也读不出，且<b>不报错</b>。
     * nil UUID 不等于任何真实租户，因此任何租户都读不到平台直连的原文。
     */
    public static final String PLATFORM_TENANT_ID = "00000000-0000-0000-0000-000000000000";

    /** HTTP 层元数据（脱敏后）。 */
    public record Http(
            @NonNull String method,
            @NonNull String uri,
            @NonNull Map<String, String> headers,
            int status,
            @NonNull Instant startedAt) {
    }

    /** 请求原文（脱敏后；用户问题 / 历史 / 送模型 messages）。 */
    public record RequestInfo(
            @Nullable String rawBody,
            @Nullable String message,
            @NonNull List<Turn> history,
            @Nullable String agentId,
            @Nullable Integer topK,
            boolean thinkingMode,
            @Nullable String scenario,
            @Nullable String contextState,
            @Nullable String systemPrompt,
            @NonNull List<Turn> modelMessages) {
    }

    /** 单轮对话（role + content，原文）。 */
    public record Turn(@NonNull String role, @NonNull String content) {
    }

    /** SSE 事件帧（14 类全覆盖；data 是 JSON 原文，thinking/delta 为裸文本）。 */
    public record Frame(
            int seq,
            @NonNull Instant at,
            @NonNull String name,
            @Nullable String data) {
    }

    /** 逐分片记录（delta / thinking 原文片段）。 */
    public record Chunk(
            int seq,
            @NonNull Instant at,
            @NonNull String kind,
            @NonNull String text) {
    }

    /** 最终结果（完整回答 + 统计）。 */
    public record Result(
            @Nullable String finalAnswer,
            @Nullable String finishReason,
            int sseFrameCount,
            long firstTokenMs,
            long totalTokens,
            boolean clientDisconnected) {
    }

    /** 错误（阶段 + 码 + 消息）。 */
    public record ErrorInfo(
            @Nullable String phase,
            @NonNull String code,
            @NonNull String message) {
    }
}
