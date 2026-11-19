package cn.studykid.multiagent.api.agentchataudit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.api.agentchataudit.ChatAuditConfigController.ChatAuditConfigResponse;
import cn.studykid.multiagent.api.run.RunController.CurrentPrincipal;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditConfig;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditConfig.State;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditConfigPort;
import cn.studykid.multiagent.ports.audit.PlatformAuditPort;
import cn.studykid.multiagent.ports.audit.PlatformAuditPort.PlatformAuditEvent;
import cn.studykid.multiagent.ports.audit.PlatformAuditPort.PlatformAuditQuery;
import cn.studykid.multiagent.ports.security.AuthorizationService;

/**
 * 对话审计配置保存的平台审计回归测试。
 *
 * <p>🐞 曾经的缺陷：把单例配置行的键名 {@code "singleton"} 当作 {@code targetId} 写入
 * {@code platform.platform_audit_event.target_id}（uuid 列），导致配置已保存但接口 500。
 * 本测试用假仓储模拟 JDBC 的 UUID 绑定校验，确保写出的每个文本 id 都能被
 * {@link UUID#fromString(String)} 解析——修复前的字面量会在这里直接失败。
 */
class ChatAuditConfigControllerTest {

    private static final String ACTOR = UUID.randomUUID().toString();

    @Test
    void saveWritesAuditWithUuidCompatibleTarget() {
        UuidStrictAuditPort audit = new UuidStrictAuditPort();
        ChatAuditConfigController controller =
                new ChatAuditConfigController(new FakeConfigPort(), adminPrincipal(), audit);

        ChatAuditConfigResponse response = controller.save(ChatAuditConfig.defaults()).getBody();
        assertEquals(1L, response.version());

        assertEquals(1, audit.events.size());
        PlatformAuditEvent event = audit.events.get(0);
        assertEquals("CHAT_AUDIT_CONFIG_UPDATE", event.action());
        assertEquals("chat_audit_config", event.targetType());
        // 单例行没有 UUID 身份：target_type 已标明资源，target_id 必须留空（DDL 允许 NULL）。
        assertNull(event.targetId());
        assertEquals(ACTOR, event.actorId());
        assertEquals("PLATFORM_DIRECT", event.channel());
        assertEquals("chat.audit.config", event.capability());
    }

    private static @NonNull CurrentPrincipal adminPrincipal() {
        return new CurrentPrincipal() {
            @Override
            public @NonNull TenantContext context() {
                return new TenantContext("platform", ACTOR,
                        Set.of(AuthorizationService.Roles.PLATFORM_ADMIN), 1L);
            }

            @Override
            public @NonNull String principalId() {
                return ACTOR;
            }

            @Override
            public @NonNull String providerId() {
                return "local";
            }

            @Override
            public @NonNull String correlationId() {
                return UUID.randomUUID().toString();
            }
        };
    }

    /** 模拟 JdbcPlatformAuditRepository 的 ?::uuid 绑定：非 UUID 文本立即失败。 */
    private static final class UuidStrictAuditPort implements PlatformAuditPort {
        private final List<PlatformAuditEvent> events = new ArrayList<>();

        @Override
        public void record(@NonNull PlatformAuditEvent event) {
            bindableAsUuid(event.actorId(), "actor_id");
            bindableAsUuid(event.targetId(), "target_id");
            events.add(event);
        }

        @Override
        public @NonNull List<PlatformAuditEvent> query(@NonNull PlatformAuditQuery query) {
            return List.copyOf(events);
        }

        private static void bindableAsUuid(String value, String column) {
            try {
                if (value != null) {
                    // 与 PostgreSQL 的 ?::uuid 同一约束；"singleton" 会在这里抛异常。
                    UUID.fromString(value);
                }
            } catch (IllegalArgumentException e) {
                throw new AssertionError("审计字段 " + column + " 不是合法 UUID: " + value, e);
            }
        }
    }

    private static final class FakeConfigPort implements ChatAuditConfigPort {
        private State current = new State(ChatAuditConfig.defaults(), 0L, null, Instant.EPOCH);

        @Override
        public @NonNull State get() {
            return current;
        }

        @Override
        public @NonNull State save(@NonNull ChatAuditConfig config, @NonNull String actorId) {
            current = new State(config, current.version() + 1, actorId, Instant.now());
            return current;
        }
    }
}
