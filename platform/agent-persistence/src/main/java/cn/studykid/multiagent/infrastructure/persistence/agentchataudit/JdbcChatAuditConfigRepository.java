package cn.studykid.multiagent.infrastructure.persistence.agentchataudit;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditConfig;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditConfig.State;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditConfigPort;

import tools.jackson.databind.ObjectMapper;

/**
 * 对话审计配置 JDBC 仓储（单例行，落 {@code public.chat_audit_config} 全局表）。
 *
 * <p><b>⚠️ 全局表无 RLS</b>：与 {@code model_catalog} 同口径——平台级配置，
 * 用主数据源 {@code dataSource.getConnection()} 直接读写，<b>不绑租户上下文</b>。
 * 该表不含任何租户数据，仅一个 JSONB 配置文档。
 *
 * <p><b>⚠️ 不挂 {@code @Repository}</b>：由组合根显式注册（与
 * {@code JdbcAgentChatAuditRepository} 同一纪律），避免测试切片组件扫描意外拾取
 * 后出现"两个同类型 Bean"或未绑租户连接的问题。
 *
 * <p><b>⚠️ 无缓存</b>：每次 {@link #get()} 读一次 DB；前端保存后下一请求即生效。
 *
 * <p><b>⚠️ 缺省回落</b>：表无行 / 读异常时返回 {@link ChatAuditConfig#defaults()}（fail-safe）；
 * 配置是"要不要多记一份审计"，读失败回落默认不应影响主请求——但读异常会 WARN 暴露。
 */
public class JdbcChatAuditConfigRepository implements ChatAuditConfigPort {

    private static final Logger log = LoggerFactory.getLogger(JdbcChatAuditConfigRepository.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DataSource dataSource;

    public JdbcChatAuditConfigRepository(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public @NonNull State get() {
        String sql = """
                SELECT config::text, config_version, updated_by, updated_at, enabled
                FROM public.chat_audit_config WHERE singleton
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                return new State(ChatAuditConfig.defaults(), 0L, "defaults", null);
            }
            String json = rs.getString(1);
            long version = rs.getLong(2);
            String updatedBy = rs.getString(3);
            Timestamp ts = rs.getTimestamp(4);
            ChatAuditConfig config = MAPPER.readValue(json, ChatAuditConfig.class);
            // ⚠️ enabled 列是「运维硬闸」：SQL 里 UPDATE enabled=false 必须能立刻停掉审计，
            //    即使 JSON 文档里写着 true。两处取「与」——任何一处说停就停。
            boolean columnEnabled = rs.getBoolean(5);
            if (!columnEnabled && config.enabled()) {
                log.warn("对话审计已被 enabled 列硬关闭（config JSON 仍为 true）：以列为准");
                config = new ChatAuditConfig(false, config.scope(), config.sinks(),
                        config.capture(), config.redaction(), config.sampling(),
                        config.writer(), config.retention(), config.limits());
            }
            return new State(config, version, updatedBy,
                    ts == null ? null : ts.toInstant());
        } catch (SQLException | RuntimeException e) {
            // fail-safe：配置读失败回落默认，但必须 WARN（静默回落会掩盖 DB 故障）
            log.warn("读取对话审计配置失败，回落默认值", e);
            return new State(ChatAuditConfig.defaults(), 0L, "defaults(fallback)", null);
        }
    }

    @Override
    public @NonNull State save(@NonNull ChatAuditConfig config, @NonNull String actorId) {
        String json;
        try {
            json = MAPPER.writeValueAsString(config);
        } catch (RuntimeException e) {
            throw new IllegalStateException("序列化对话审计配置失败", e);
        }
        String sql = """
                INSERT INTO public.chat_audit_config
                    (singleton, enabled, config, config_version, updated_by)
                VALUES (true, ?, ?::jsonb, 1, ?)
                ON CONFLICT (singleton) DO UPDATE
                    SET enabled = EXCLUDED.enabled,
                        config = EXCLUDED.config,
                        config_version = public.chat_audit_config.config_version + 1,
                        updated_by = EXCLUDED.updated_by,
                        updated_at = now()
                RETURNING config_version, updated_at
                """;
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                long version;
                Instant updatedAt;
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    // 列与 JSON 同源：前端保存时一并镜像，避免「页面说开、列说关」的长期分歧
                    ps.setBoolean(1, config.enabled());
                    ps.setString(2, json);
                    ps.setString(3, actorId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new IllegalStateException("配置写入未返回版本号");
                        }
                        version = rs.getLong(1);
                        updatedAt = rs.getTimestamp(2).toInstant();
                    }
                }
                conn.commit();
                return new State(config, version, actorId, updatedAt);
            } catch (SQLException | RuntimeException e) {
                safeRollback(conn);
                throw e instanceof RuntimeException re
                        ? re : new IllegalStateException("写入对话审计配置失败", e);
            } finally {
                restoreAutoCommit(conn);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("写入对话审计配置失败", e);
        }
    }

    private static void safeRollback(Connection conn) {
        try {
            conn.rollback();
        } catch (SQLException ignored) {
            // rollback 失败不掩盖原始异常
        }
    }

    private static void restoreAutoCommit(Connection conn) {
        try {
            conn.setAutoCommit(true);
        } catch (SQLException ignored) {
            // 同上
        }
    }
}
