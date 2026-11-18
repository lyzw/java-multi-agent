package cn.studykid.multiagent.infrastructure.agentchataudit;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditConfig;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditEnvelope;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditEnvelope.ErrorInfo;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditEnvelope.RequestInfo;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditEnvelope.Result;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditRecorderPort;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditRejected;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditWriterPort;

/**
 * 对话审计录制器实现（业务线程只做内存组装 + 非阻塞提交）。
 *
 * <p><b>⚠️ fail-open</b>：所有 public 方法内部 try/catch，任何异常只 WARN，
 * 绝不向业务调用方抛出。录制是旁路，绝不能让主请求失败（决策 6）。
 *
 * <p><b>⚠️ 采样</b>：{@link #begin} 时按 auditId 决定本次是否录制；
 * 不录制返回 NOOP 会话，业务侧无感知、不做任何字符串拼接。
 *
 * <p><b>⚠️ 截断</b>：{@code limits.maxBodyBytes} / {@code limits.maxAnswerChars}
 * 超限即截断并置标记位——记录必须<b>自称不完整</b>，而不是让使用者以为拿到的是全文。
 */
public class ChatAuditRecorder implements ChatAuditRecorderPort {

    private static final Logger log = LoggerFactory.getLogger(ChatAuditRecorder.class);

    private final ChatAuditWriterPort writer;

    public ChatAuditRecorder(@NonNull ChatAuditWriterPort writer) {
        this.writer = writer;
    }

    @Override
    public @NonNull Session begin(@NonNull BeginContext ctx) {
        try {
            ChatAuditConfig config = ctx.config();
            if (!config.enabled() || !SamplingDecider.shouldRecord(
                    config.sampling().rate(), ctx.auditId())) {
                return Session.NOOP;
            }
            return new RecordingSession(ctx, writer, config);
        } catch (RuntimeException e) {
            log.warn("对话审计 begin 失败（本次跳过录制）: auditId={}", ctx.auditId(), e);
            return Session.NOOP;
        }
    }

    @Override
    public void recordRejected(@NonNull ChatAuditRejected record,
                               @NonNull ChatAuditConfig config) {
        try {
            if (!config.enabled() || !config.scope().includePreconnectionFailures()) {
                return;
            }
            if (!SamplingDecider.shouldRecord(config.sampling().rate(), record.auditId())) {
                return;
            }
            writer.submit(record, config.writer().async());
        } catch (RuntimeException e) {
            log.warn("对话审计 recordRejected 失败（已忽略）: auditId={}", record.auditId(), e);
        }
    }

    /** 单次请求的录制会话（非线程安全：一个 SSE 流由单一虚拟线程串行写）。 */
    static final class RecordingSession implements Session {

        private final BeginContext ctx;
        private final ChatAuditConfig config;
        private final ChatAuditWriterPort writer;
        private final AuditRedactor redactor;
        private final List<ChatAuditEnvelope.Frame> frames = new ArrayList<>();
        private final List<ChatAuditEnvelope.Chunk> chunks = new ArrayList<>();
        private final Instant startedAt;
        private RequestInfo structured;
        private String rawBody;
        private String systemPrompt;
        private List<ChatAuditEnvelope.Turn> modelMessages = List.of();
        private Result result;
        private String domain;
        private String tenantId;
        private String principalId;
        private int httpStatus;
        private boolean bodyTruncated;
        private boolean answerTruncated;
        private volatile boolean finished;
        private int frameSeq;
        private int chunkSeq;

        RecordingSession(BeginContext ctx, ChatAuditWriterPort writer, ChatAuditConfig config) {
            this.ctx = ctx;
            this.writer = writer;
            this.config = config;
            this.redactor = new AuditRedactor(config.redaction());
            this.startedAt = ctx.startedAt();
            this.domain = ctx.domain();
            this.tenantId = ctx.tenantId();
            this.principalId = ctx.principalId();
        }

        @Override
        public void identify(@NonNull String domain,
                             @Nullable String tenantId,
                             @Nullable String principalId) {
            if (finished) {
                return;
            }
            try {
                if (domain != null && !domain.isBlank()) {
                    this.domain = domain;
                }
                if (tenantId != null && !tenantId.isBlank()) {
                    this.tenantId = tenantId;
                }
                if (principalId != null && !principalId.isBlank()) {
                    this.principalId = principalId;
                }
            } catch (RuntimeException e) {
                log.warn("审计 identify 失败（已忽略）", e);
            }
        }

        @Override
        public void recordRequest(@Nullable RequestInfo info, boolean truncated) {
            if (finished) {
                return;
            }
            try {
                this.structured = info;
                if (truncated) {
                    this.bodyTruncated = true;
                }
            } catch (RuntimeException e) {
                log.warn("审计 recordRequest 失败（已忽略）", e);
            }
        }

        @Override
        public void setRawRequestBody(@Nullable String body, boolean truncated) {
            if (finished) {
                return;
            }
            try {
                this.rawBody = body;
                if (truncated) {
                    this.bodyTruncated = true;
                }
            } catch (RuntimeException e) {
                log.warn("审计 setRawRequestBody 失败（已忽略）", e);
            }
        }

        @Override
        public void recordModelMessages(@Nullable String prompt,
                                        @NonNull List<ChatAuditEnvelope.Turn> messages) {
            if (finished) {
                return;
            }
            try {
                this.systemPrompt = prompt;
                this.modelMessages = messages;
            } catch (RuntimeException e) {
                log.warn("审计 recordModelMessages 失败（已忽略）", e);
            }
        }

        @Override
        public void recordFrame(@NonNull String name, @Nullable String data) {
            if (finished) {
                return;
            }
            try {
                int max = config.writer().maxChunksPerRequest();
                if (frameSeq >= max) {
                    return;
                }
                // 内容类开关必须真的生效：配了「不记」还在记，等于把配置页变成装饰品
                if ("thinking".equals(name) && !config.capture().thinking()) {
                    return;
                }
                if ("rag.hits".equals(name) && !config.capture().ragHits()) {
                    return;
                }
                if ("tool".equals(name) && !config.capture().toolArgsAndResults()) {
                    return;
                }
                frames.add(new ChatAuditEnvelope.Frame(++frameSeq, Instant.now(), name, data));
            } catch (RuntimeException e) {
                log.warn("审计 recordFrame 失败（已忽略）", e);
            }
        }

        @Override
        public void recordChunk(@NonNull String kind, @NonNull String text) {
            if (finished) {
                return;
            }
            try {
                if (!config.capture().deltaChunks()) {
                    return;
                }
                if ("thinking".equals(kind) && !config.capture().thinking()) {
                    return;
                }
                int max = config.writer().maxChunksPerRequest();
                if (chunkSeq >= max) {
                    return;
                }
                chunks.add(new ChatAuditEnvelope.Chunk(++chunkSeq, Instant.now(), kind, text));
            } catch (RuntimeException e) {
                log.warn("审计 recordChunk 失败（已忽略）", e);
            }
        }

        @Override
        public void setResult(@Nullable Result r) {
            if (finished) {
                return;
            }
            try {
                this.result = r;
            } catch (RuntimeException e) {
                log.warn("审计 setResult 失败（已忽略）", e);
            }
        }

        @Override
        public void setHttpStatus(int status) {
            if (finished) {
                return;
            }
            this.httpStatus = status;
        }

        @Override
        public void markAnswerTruncated() {
            this.answerTruncated = true;
        }

        @Override
        public void finish(@NonNull String terminalState, @Nullable ErrorInfo error) {
            if (finished) {
                return;
            }
            finished = true;
            try {
                Instant completed = Instant.now();
                long duration = Duration.between(startedAt, completed).toMillis();
                ChatAuditEnvelope.Http http = new ChatAuditEnvelope.Http(
                        ctx.httpMethod(), ctx.requestUri(),
                        redactor.redactHeaders(ctx.headers()),
                        httpStatus, startedAt);
                ChatAuditEnvelope envelope = new ChatAuditEnvelope(
                        ctx.auditId(), ctx.traceId(), ctx.endpoint(), domain,
                        tenantId, principalId, startedAt, completed, duration,
                        terminalState, ctx.configVersion(), http, assembleRequest(),
                        List.copyOf(frames), List.copyOf(chunks),
                        truncateResult(result), error, bodyTruncated, answerTruncated);
                writer.submit(envelope, config.writer().async());
            } catch (RuntimeException e) {
                log.warn("审计 finish 失败（已跳过提交）: auditId={}", ctx.auditId(), e);
            }
        }

        /**
         * 组装请求原文（三处来源合并，缺一不影响其它）。
         *
         * <p>⚠️ 开关在这里统一裁决：{@code capture.rawRequestBody} / {@code
         * capture.modelRequestMessages} 关闭时对应字段落 null，而不是「记了但前端不显示」。
         */
        private @Nullable RequestInfo assembleRequest() {
            boolean captureBody = config.capture().rawRequestBody();
            boolean captureModel = config.capture().modelRequestMessages();
            String body = captureBody ? redactAndTruncate(rawBody) : null;
            List<ChatAuditEnvelope.Turn> messages = captureModel ? modelMessages : List.of();
            String prompt = captureModel ? systemPrompt : null;
            if (structured == null && body == null && messages.isEmpty() && prompt == null) {
                return null;
            }
            RequestInfo base = structured;
            return new RequestInfo(
                    body,
                    base == null ? null : base.message(),
                    base == null ? List.of() : base.history(),
                    base == null ? null : base.agentId(),
                    base == null ? null : base.topK(),
                    base != null && base.thinkingMode(),
                    base == null ? null : base.scenario(),
                    base == null ? null : base.contextState(),
                    prompt,
                    messages);
        }

        /** 原始 body 脱敏 + 字节上限截断（超限即置 bodyTruncated）。 */
        private @Nullable String redactAndTruncate(@Nullable String body) {
            if (body == null) {
                return null;
            }
            String redacted = redactor.redactJsonText(body);
            int maxBytes = config.limits().maxBodyBytes();
            String truncated = truncateBytes(redacted, maxBytes);
            if (truncated.length() < redacted.length()) {
                bodyTruncated = true;
            }
            return truncated;
        }

        /** 回答超长即截断并打标记（保留前 N 字符，不静默）；关闭采集时整段落空。 */
        private @Nullable Result truncateResult(@Nullable Result r) {
            if (r == null) {
                return null;
            }
            if (!config.capture().rawAnswer()) {
                return new Result(null, r.finishReason(), r.sseFrameCount(),
                        r.firstTokenMs(), r.totalTokens(), r.clientDisconnected());
            }
            if (r.finalAnswer() == null) {
                return r;
            }
            int max = config.limits().maxAnswerChars();
            String answer = r.finalAnswer();
            if (answer.length() <= max) {
                return r;
            }
            answerTruncated = true;
            return new Result(answer.substring(0, max), r.finishReason(), r.sseFrameCount(),
                    r.firstTokenMs(), r.totalTokens(), r.clientDisconnected());
        }

        /** 按 UTF-8 字节上限截断（中文一字 3 字节，不能按字符数截）。 */
        private static @NonNull String truncateBytes(@NonNull String text, int maxBytes) {
            byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (bytes.length <= maxBytes) {
                return text;
            }
            int end = Math.max(0, maxBytes);
            // 不切断多字节字符：若切点落在续字节（10xxxxxx）上，退到字符起点。
            // ⚠️ 不能靠 new String(...) 抛异常来发现——它不抛，只会把半个字符变成 U+FFFD。
            while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
                end--;
            }
            return new String(bytes, 0, end, java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
