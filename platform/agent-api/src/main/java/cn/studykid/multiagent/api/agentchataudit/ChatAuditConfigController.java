package cn.studykid.multiagent.api.agentchataudit;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import cn.studykid.multiagent.api.run.RunController.CurrentPrincipal;
import cn.studykid.multiagent.application.agentchataudit.ChatAuditConfigInvalidException;
import cn.studykid.multiagent.application.agentchataudit.ChatAuditConfigValidator;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditConfig;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditConfigPort;
import cn.studykid.multiagent.ports.audit.PlatformAuditPort;
import cn.studykid.multiagent.ports.audit.PlatformAuditPort.PlatformAuditEvent;
import cn.studykid.multiagent.ports.security.AuthorizationService;

/**
 * 平台级「对话审计配置」端点（前端「系统配置 → 对话审计配置」页面）。
 *
 * <p><b>⚠️ 平台级资源，仅 PLATFORM_ADMIN 可写</b>：配置是全局单例（无租户维度），
 * 沿用 {@code AdminModelConfigController#requireAdmin} 的 {@code hasRole} 直判范式——
 * 不能走 {@code requireAuthorized(ctx, PLATFORM_ADMIN)}（后者把角色当动作映射，恒 false）。
 *
 * <p><b>⚠️ 写入即生效、无缓存</b>：保存后 {@code ChatAuditConfigPort.get()} 下一次读取
 * 即返回新值，无需重启 / 清缓存。
 *
 * <p><b>⚠️ 写库前校验 + 平台审计留痕</b>：非法配置不落库、不写审计；
 * 成功写入追加一条 {@code platform_audit_event}（仅追加）。
 *
 * <p><b>⚠️ 不回显任何密钥</b>：配置体只含开关 / 路径 / 阈值，不含凭据。
 */
@RestController
@RequestMapping("/api/platform/v1/chat-audit-config")
public class ChatAuditConfigController {

    private final ChatAuditConfigPort configPort;
    private final CurrentPrincipal currentPrincipal;
    private final PlatformAuditPort audit;

    public ChatAuditConfigController(@NonNull ChatAuditConfigPort configPort,
                                     @NonNull CurrentPrincipal currentPrincipal,
                                     @NonNull PlatformAuditPort audit) {
        this.configPort = configPort;
        this.currentPrincipal = currentPrincipal;
        this.audit = audit;
    }

    /** 读取当前配置（前端回显）。 */
    @GetMapping
    public ResponseEntity<ChatAuditConfigResponse> get() {
        requireAdmin();
        ChatAuditConfig.State state = configPort.get();
        return ResponseEntity.ok(new ChatAuditConfigResponse(
                state.config(), state.version(), state.updatedBy(), state.updatedAt()));
    }

    /** 整体覆盖写（前端整表提交）。 */
    @PutMapping
    public ResponseEntity<ChatAuditConfigResponse> save(@RequestBody ChatAuditConfig raw) {
        requireAdmin();
        ChatAuditConfig normalized = ChatAuditConfigValidator.normalize(raw);
        try {
            ChatAuditConfigValidator.validate(normalized);
        } catch (ChatAuditConfigInvalidException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        String actor = currentPrincipal.principalId();
        ChatAuditConfig.State state = configPort.save(normalized, actor);
        recordAudit(actor, state.version());
        return ResponseEntity.ok(new ChatAuditConfigResponse(
                state.config(), state.version(), state.updatedBy(), state.updatedAt()));
    }

    private void requireAdmin() {
        TenantContext context = currentPrincipal.context();
        if (!context.hasRole(AuthorizationService.Roles.PLATFORM_ADMIN)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "对话审计配置仅限 PLATFORM_ADMIN（须经平台运维通道授权）");
        }
    }

    private void recordAudit(@NonNull String actor, long version) {
        audit.record(new PlatformAuditEvent(
                UUID.randomUUID(), actor, "PLATFORM_USER",
                "CHAT_AUDIT_CONFIG_UPDATE", "chat_audit_config", "singleton",
                "ALLOWED", null, null, null, Instant.now(), null,
                "chat.audit.config", "PLATFORM_DIRECT"));
    }

    /** 响应体：配置 + 版本 + 更新者/时间（无密钥）。 */
    public record ChatAuditConfigResponse(
            @NonNull ChatAuditConfig config,
            long version,
            String updatedBy,
            Instant updatedAt) {
    }
}
