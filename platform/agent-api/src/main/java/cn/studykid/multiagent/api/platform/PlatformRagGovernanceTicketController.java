package cn.studykid.multiagent.api.platform;

import java.time.Instant;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import cn.studykid.multiagent.api.platform.PlatformRagGovernanceTicketService.GovernanceTicket;
import cn.studykid.multiagent.ports.security.IdentityDomains;
import cn.studykid.multiagent.ports.security.PlatformPrincipal;

/**
 * RAG 全局语料<b>治理票据</b>端点（RAG-D11 第四刀 · 第二刀 §2.3）。
 *
 * <p><b>路径挂在平台命名空间下</b>（{@code /api/platform/**}）：只有平台安全链会认证它，
 * 租户令牌在此必然 401 —— 与 {@link PlatformExecutionTicketController} 同一隔离前提。
 *
 * <p>本控制器只做请求/响应建模，<b>四道闸门全在
 * {@link PlatformRagGovernanceTicketService}</b>：授权、开关、档位、限流与审计
 * 必须与签发同处一地，拆开就出现「先返回票据再补审计」这种顺序漏洞。
 */
@RestController
public class PlatformRagGovernanceTicketController {

    private final PlatformRagGovernanceTicketService tickets;

    public PlatformRagGovernanceTicketController(PlatformRagGovernanceTicketService tickets) {
        this.tickets = tickets;
    }

    /**
     * 签发出一枚短时治理票据。
     *
     * <p>⚠️ 响应含<b>可用凭据</b>（{@code accessToken}）：绝不写日志、绝不写审计正文
     * （只回 jti），前端只准在内存里持有——落进 {@code sessionStorage}
     * 就等于把「写全局语料」的授权交给任何一个能读浏览器存储的人
     * （XSS、共享机器、浏览器扩展都算）。
     */
    @PostMapping("/api/platform/v1/rag/governance-ticket")
    public ResponseEntity<TicketResponse> issue(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @RequestBody(required = false) TicketRequest request) {
        GovernanceTicket ticket = tickets.issue(principal,
                request == null ? null : request.scope());
        return ResponseEntity.ok(TicketResponse.of(ticket));
    }

    // ── 请求 / 响应记录 ────────────────────────────────────

    public record TicketRequest(@Nullable String scope) {
    }

    public record TicketResponse(@NonNull String accessToken,
                                 @NonNull String tokenType,
                                 @NonNull String scope,
                                 @NonNull String platformUserId,
                                 @NonNull Instant expiresAt,
                                 @NonNull String jti) {
        static TicketResponse of(GovernanceTicket t) {
            return new TicketResponse(t.accessToken(), "Bearer", t.scope(),
                    t.platformUserId(), t.expiresAt(), t.jti());
        }
    }
}
