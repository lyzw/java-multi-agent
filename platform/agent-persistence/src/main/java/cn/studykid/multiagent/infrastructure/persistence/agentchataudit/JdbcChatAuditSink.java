package cn.studykid.multiagent.infrastructure.persistence.agentchataudit;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditConfig;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditEnvelope;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditRecord;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditRejected;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditSinkPort;

import tools.jackson.databind.ObjectMapper;

/**
 * 对话审计的数据库出口（{@code public.chat_audit_record} + {@code chat_audit_unattributed}）。
 *
 * <p><b>⚠️ RLS 铁律</b>：{@code chat_audit_record} 走
 * {@link TransactionalConnectionHolder#inTransaction}，事务内、业务 SQL 前由
 * {@code TenantSessionBinder} 绑定 {@code app.tenant_id}；连接角色是
 * {@code app_agent}（NOBYPASSRLS）。语句显式带 {@code tenant_id}——
 * RLS 退役期（M4）之后功能不变靠的就是这个显式谓词。
 *
 * <p><b>⚠️ 绝不写 outbox_event</b>：本类只 INSERT 审计表。写 outbox 会唤醒 Worker，
 * 而这里记录的是「一次 HTTP 请求」，不是「一个待执行的 Run」——混进去就是
 * 让审计把业务重跑一遍（Sev-0）。
 *
 * <p><b>⚠️ 平台域无租户的记录用 nil UUID</b>（见
 * {@link ChatAuditEnvelope#PLATFORM_TENANT_ID}）：列 NOT NULL + FORCE RLS，
 * 绑 nil UUID 才能写入，而它不等于任何真实租户 ⇒ 租户侧读不到。
 *
 * <p><b>⚠️ 不吞异常</b>：写失败向上抛，由写入器统一计数。吞在里层会让
 * 「审计在丢数据」变成不可观测的静默事实。
 *
 * <p><b>装配</b>：刻意不挂 {@code @Repository}，由组合根显式注册
 * （与 {@code JdbcAgentChatAuditRepository} 同一纪律）。
 */
public class JdbcChatAuditSink implements ChatAuditSinkPort {

    private static final Logger log = LoggerFactory.getLogger(JdbcChatAuditSink.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String INSERT_RECORD = """
            INSERT INTO public.chat_audit_record
                (audit_id, trace_id, tenant_id, domain, endpoint, principal_id, started_at,
                 completed_at, duration_ms, terminal_state, http_method, http_status,
                 config_version, envelope, content_class, created_at)
            VALUES
                (?::uuid, ?, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, 'SENSITIVE_RAW', now())
            ON CONFLICT (audit_id) DO NOTHING
            """;

    private static final String INSERT_UNATTRIBUTED = """
            INSERT INTO public.chat_audit_unattributed
                (audit_id, trace_id, started_at, endpoint, http_method, http_status,
                 rejection_reason, metadata, created_at)
            VALUES
                (?::uuid, ?, ?, ?, ?, ?, ?, ?::jsonb, now())
            ON CONFLICT (audit_id) DO NOTHING
            """;

    private final DataSource dataSource;
    private final Supplier<ChatAuditConfig> configSupplier;
    private final AtomicLong skippedNoTenant = new AtomicLong();

    /**
     * @param configSupplier 当前配置（用于 {@code sinks.database.enabled} 开关）
     *
     * <p>⚠️ 开关必须每次写前读：把 enabled 缓存成字段会让「页面关了库写」变成长期静默偏差
     * （配置改了、写还在继续，且没有任何日志）。
     */
    public JdbcChatAuditSink(@NonNull DataSource dataSource,
                             @NonNull Supplier<ChatAuditConfig> configSupplier) {
        this.dataSource = dataSource;
        this.configSupplier = configSupplier;
    }

    @Override
    public @NonNull String name() {
        return "database";
    }

    @Override
    public void write(@NonNull ChatAuditRecord record) {
        if (!databaseEnabled()) {
            return;
        }
        switch (record) {
            case ChatAuditEnvelope envelope -> writeEnvelope(envelope);
            case ChatAuditRejected rejected -> writeRejected(rejected);
        }
    }

    /** 库写开关；配置读失败时按「开」处理——审计多记一条比静默漏记安全。 */
    private boolean databaseEnabled() {
        try {
            return configSupplier.get().sinks().database().enabled();
        } catch (RuntimeException e) {
            log.warn("读取对话审计配置失败，本批按默认（写库开启）处理: {}", e.toString());
            return true;
        }
    }

    /** 已归属记录：绑租户、显式带 tenant_id。 */
    private void writeEnvelope(@NonNull ChatAuditEnvelope envelope) {
        String tenantId = resolveTenant(envelope);
        if (tenantId == null) {
            // 租户链却拿不到租户：这是上游身份解析的缺陷，不是「可以跳过」的情形。
            // 记 WARN + 计数，绝不静默丢弃（否则跨租户隔离被绕过时没人知道）。
            long n = skippedNoTenant.incrementAndGet();
            log.warn("对话审计记录缺少租户，已跳过落库（累计 {} 条）: auditId={}, domain={}",
                    n, envelope.auditId(), envelope.domain());
            return;
        }
        String json = JSON.writeValueAsString(envelope);
        TransactionalConnectionHolder.inTransaction(dataSource, tenantContext(tenantId), conn -> {
            try (PreparedStatement ps = conn.prepareStatement(INSERT_RECORD)) {
                int i = 1;
                ps.setString(i++, envelope.auditId());
                ps.setString(i++, envelope.traceId());
                ps.setString(i++, tenantId);
                ps.setString(i++, envelope.domain());
                ps.setString(i++, envelope.endpoint());
                ps.setString(i++, envelope.principalId());
                ps.setTimestamp(i++, Timestamp.from(envelope.startedAt()));
                ps.setTimestamp(i++, envelope.completedAt() == null
                        ? null : Timestamp.from(envelope.completedAt()));
                ps.setLong(i++, envelope.durationMs());
                ps.setString(i++, envelope.terminalState());
                ps.setString(i++, envelope.http().method());
                // 0 表示「尚未写入状态码」，落 NULL 比落 0 诚实
                if (envelope.http().status() > 0) {
                    ps.setInt(i++, envelope.http().status());
                } else {
                    ps.setNull(i++, java.sql.Types.INTEGER);
                }
                ps.setLong(i++, envelope.configVersion());
                ps.setString(i++, json);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** 未归属记录：只写元数据表（无租户、无 RLS）。 */
    private void writeRejected(@NonNull ChatAuditRejected rejected) {
        ensureUuid(rejected.auditId(), "chat_audit_unattributed.audit_id");
        String metadata = JSON.writeValueAsString(rejected.metadata());
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(INSERT_UNATTRIBUTED)) {
            int i = 1;
            ps.setString(i++, rejected.auditId());
            ps.setString(i++, rejected.traceId());
            ps.setTimestamp(i++, Timestamp.from(rejected.startedAt()));
            ps.setString(i++, rejected.endpoint());
            ps.setString(i++, rejected.httpMethod());
            if (rejected.httpStatus() > 0) {
                ps.setInt(i++, rejected.httpStatus());
            } else {
                ps.setNull(i++, java.sql.Types.INTEGER);
            }
            ps.setString(i++, rejected.rejectionReason());
            ps.setString(i++, metadata);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("写入未归属审计记录失败: " + rejected.auditId(), e);
        }
    }

    /**
     * 归属租户：有就用；没有且是平台域则用 nil UUID 占位；租户域却没有 → {@code null}。
     */
    private static @Nullable String resolveTenant(@NonNull ChatAuditEnvelope envelope) {
        String tenantId = envelope.tenantId();
        if (tenantId != null && !tenantId.isBlank()) {
            ensureUuid(tenantId, "chat_audit_record.tenant_id");
            return tenantId;
        }
        if ("PLATFORM".equals(envelope.domain())) {
            return ChatAuditEnvelope.PLATFORM_TENANT_ID;
        }
        return null;
    }

    /**
     * 主键与租户列都是 {@code uuid}，写前必须显式校验。
     *
     * <p>⚠️ auditId 是服务端生成的 UUID（<b>不是</b> traceId：traceId 可被客户端经
     * {@code X-Trace-Id} 注入任意文本）。若这里放行非法值，PG 抛 {@code 22P02}，
     * 整条记录丢失，且只在「客户端传了怪头」时丢——排障时极难复现。
     */
    private static void ensureUuid(@NonNull String value, @NonNull String column) {
        try {
            UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    column + " 不是合法 UUID（auditId 应为 UUID 形态的 traceId）: " + value);
        }
    }

    private static @NonNull TenantContext tenantContext(@NonNull String tenantId) {
        // 审计链没有业务主体：principal 固定为组件名，权限集合为空。
        return new TenantContext(tenantId, "chat-audit", Set.of(), 0L);
    }

    /** 因缺少租户被跳过的条数（监控可读）。 */
    public long skippedNoTenantTotal() {
        return skippedNoTenant.get();
    }
}
