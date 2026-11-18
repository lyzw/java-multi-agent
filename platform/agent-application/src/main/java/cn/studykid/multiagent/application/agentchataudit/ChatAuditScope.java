package cn.studykid.multiagent.application.agentchataudit;

import java.time.Instant;
import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditConfig;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditEnvelope.ErrorInfo;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditEnvelope.RequestInfo;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditEnvelope.Result;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditEnvelope.Turn;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditRecorderPort.Session;

/**
 * 一次请求的审计作用域（请求线程创建，虚拟线程继承使用）。
 *
 * <p><b>⚠️ 为什么需要这一层而不是直接用 {@link Session}</b>：
 * 录制会话只认「记什么」，而链路沿途需要的是「这次请求是谁、走的哪个端点、用哪份配置」。
 * 把这些放在作用域里，装饰器就不必各自解析端点或重复读配置——<b>同一请求用同一份配置快照</b>
 * 是决策 7 里「配置改了下一个请求生效」的另一半：同一个请求中途配置漂移会让记录自相矛盾。
 *
 * <p><b>⚠️ 线程安全</b>：由单一虚拟线程串行访问；跨线程只读字段用 {@code volatile}。
 */
public final class ChatAuditScope {

    private final Session session;
    private final ChatAuditConfig config;
    private final String auditId;
    private final String traceId;
    private final String endpoint;
    private final Instant startedAt;

    /** 是否处于「编排链」内（编排链的分片由事件帧记录，网关不再重复记分片）。 */
    private volatile boolean orchestrated;

    public ChatAuditScope(@NonNull Session session,
                          @NonNull ChatAuditConfig config,
                          @NonNull String auditId,
                          @NonNull String traceId,
                          @NonNull String endpoint,
                          @NonNull Instant startedAt) {
        this.session = session;
        this.config = config;
        this.auditId = auditId;
        this.traceId = traceId;
        this.endpoint = endpoint;
        this.startedAt = startedAt;
    }

    public @NonNull Session session() {
        return session;
    }

    public @NonNull ChatAuditConfig config() {
        return config;
    }

    public @NonNull String auditId() {
        return auditId;
    }

    public @NonNull String traceId() {
        return traceId;
    }

    public @NonNull String endpoint() {
        return endpoint;
    }

    public @NonNull Instant startedAt() {
        return startedAt;
    }

    public boolean orchestrated() {
        return orchestrated;
    }

    /** 由编排链装饰器在入口置位：此后网关不再重复记录分片。 */
    public void markOrchestrated() {
        this.orchestrated = true;
    }

    // ── 便捷转发（装饰器只面对本类）────────────────────────────────

    public void identify(@NonNull String domain, @Nullable String tenantId,
                         @Nullable String principalId) {
        session.identify(domain, tenantId, principalId);
    }

    public void recordRequest(@Nullable RequestInfo request, boolean bodyTruncated) {
        session.recordRequest(request, bodyTruncated);
    }

    public void setRawRequestBody(@Nullable String rawBody, boolean truncated) {
        session.setRawRequestBody(rawBody, truncated);
    }

    public void recordModelMessages(@Nullable String systemPrompt,
                                    @NonNull List<Turn> messages) {
        session.recordModelMessages(systemPrompt, messages);
    }

    public void recordFrame(@NonNull String name, @Nullable String data) {
        session.recordFrame(name, data);
    }

    public void recordChunk(@NonNull String kind, @NonNull String text) {
        session.recordChunk(kind, text);
    }

    public void setResult(@Nullable Result result) {
        session.setResult(result);
    }

    public void setHttpStatus(int status) {
        session.setHttpStatus(status);
    }

    /** 收敛终态（幂等，由会话实现保证）。 */
    public void finish(@NonNull String terminalState, @Nullable ErrorInfo error) {
        session.finish(terminalState, error);
    }
}
