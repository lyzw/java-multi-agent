package cn.studykid.multiagent.api.platform;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import cn.studykid.multiagent.api.security.PlatformSecurity;
import cn.studykid.multiagent.core.tenant.TenantRegistry.PrincipalRow;
import cn.studykid.multiagent.ports.persistence.AdminTenantPort;
import cn.studykid.multiagent.ports.security.IdentityDomains;
import cn.studykid.multiagent.ports.security.Namespaces;
import cn.studykid.multiagent.ports.security.PlatformPrincipal;

/**
 * 跨域执行票证端点（admin-console AI 实验室通道）。
 *
 * <p><b>路径挂在平台命名空间下</b>（{@code /api/platform/**}）：只有平台安全链
 * 会认证它，租户令牌在此必然 401 —— 与 {@link PlatformTenantController} 同一隔离前提。
 *
 * <p>本控制器只做参数与响应建模，<b>所有闸门在
 * {@link PlatformExecutionTicketService}</b>：授权、限流、租户/主体真实性与审计
 * 必须与签发同处一地，拆开就出现「先返回票证再补审计」这种顺序漏洞。
 *
 * <p>⚠️ <b>平台管理权不等于租户业务执行权</b>是本项目的既有红线；本端点是这条红线
 * 上<b>唯一被显式打通的口子</b>，打通的方式不是放开闸门，而是<b>签发一枚受严格
 * 时效与角色约束的租户令牌</b>。租户域侧的六道前置闸门（内容合规、Prompt 防护、
 * 零保留、执行时授权、限流、预算）<b>照常全部生效</b> —— 实验室因此仍然在测真实链路。
 */
@RestController
public class PlatformExecutionTicketController {

    private final PlatformExecutionTicketService tickets;
    private final AdminTenantPort principals;
    private final PlatformSecurity security;

    public PlatformExecutionTicketController(PlatformExecutionTicketService tickets,
                                            AdminTenantPort principals,
                                            PlatformSecurity security) {
        this.tickets = tickets;
        this.principals = principals;
        this.security = security;
    }

    /**
     * 签发一枚短时执行票证。
     *
     * <p>⚠️ 响应含<b>可用的租户令牌</b>，这是全平台唯一返回租户凭据的端点：
     * 绝不写入审计正文、绝不回显于日志（只回 jti），前端只准在内存里持有。
     */
    @PostMapping("/api/platform/v1/tenants/{tenantId}/execution-ticket")
    public ResponseEntity<TicketResponse> issue(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @PathVariable String tenantId,
            @RequestBody TicketRequest request) {
        if (request == null || request.loginName() == null || request.loginName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "loginName 必填：票证必须指名一个真实的租户成员，归属不能由服务端猜");
        }
        PlatformExecutionTicketService.ExecutionTicket ticket =
                tickets.issue(principal, tenantId, request.loginName().trim());
        return ResponseEntity.ok(TicketResponse.of(ticket));
    }

    /**
     * 列出该租户下可指名的成员（登录名 / 状态 / 角色，<b>不含任何凭据</b>）。
     *
     * <p>存在的理由：签发必须显式指名主体，而控制台此前<b>没有</b>任何平台侧通道
     * 能看到租户成员（旧的 {@code /api/admin/tenants/{id}/principals} 属租户身份域，
     * 且已弃用）。不给出选择来源，「显式指名」就会退化成「靠猜」。
     *
     * <p>授权复用 {@code platform.tenant.read}：能看到租户，不等于能扮演租户。
     */
    @GetMapping("/api/platform/v1/tenants/{tenantId}/principals")
    public ResponseEntity<List<TenantPrincipalResponse>> listPrincipals(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @PathVariable String tenantId) {
        security.require(principal, Namespaces.Platform.TENANT_READ, "tenant", tenantId);
        return ResponseEntity.ok(principals.listPrincipals(tenantId).stream()
                .map(TenantPrincipalResponse::of).toList());
    }

    // ── 响应 / 请求记录 ────────────────────────────────────

    public record TicketRequest(@Nullable String loginName) {
    }

    public record TicketResponse(@NonNull String accessToken,
                                 @NonNull String tokenType,
                                 @NonNull String tenantId,
                                 @NonNull String principalId,
                                 @NonNull String loginName,
                                 @NonNull Set<String> roles,
                                 @NonNull Instant expiresAt,
                                 @NonNull String jti) {
        static TicketResponse of(PlatformExecutionTicketService.ExecutionTicket t) {
            return new TicketResponse(t.accessToken(), "Bearer", t.tenantId(),
                    t.principalId(), t.loginName(), t.roles(), t.expiresAt(), t.jti());
        }
    }

    public record TenantPrincipalResponse(@NonNull String principalId,
                                          @NonNull String loginName,
                                          @Nullable String displayName,
                                          @NonNull String status,
                                          @NonNull List<String> roles) {
        static TenantPrincipalResponse of(PrincipalRow row) {
            return new TenantPrincipalResponse(row.principalId(), row.loginName(),
                    row.displayName(), row.status(), row.roles());
        }
    }
}
