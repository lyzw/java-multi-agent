package cn.studykid.multiagent.api.platform;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import cn.studykid.multiagent.api.security.PlatformSecurity;
import cn.studykid.multiagent.ports.audit.PlatformAuditPort;
import cn.studykid.multiagent.ports.audit.PlatformAuditPort.PlatformAuditEvent;
import cn.studykid.multiagent.ports.audit.PlatformAuditPort.PlatformAuditQuery;
import cn.studykid.multiagent.ports.security.IdentityDomains;
import cn.studykid.multiagent.ports.security.Namespaces;
import cn.studykid.multiagent.ports.security.PlatformPrincipal;

/**
 * 平台审计端点（重规划 §5.3 / §12）。
 *
 * <p>仅读 {@code platform.platform_audit_event}（与租户业务审计分域）。
 * 需 {@code platform.audit.read}；敏感字段已按设计脱敏（无租户业务内容）。
 */
@RestController
@RequestMapping("/api/platform/v1/audit-events")
public class PlatformAuditController {

    private final PlatformAuditPort audit;
    private final PlatformSecurity security;

    public PlatformAuditController(PlatformAuditPort audit, PlatformSecurity security) {
        this.audit = audit;
        this.security = security;
    }

    @GetMapping
    public ResponseEntity<List<AuditSummary>> list(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @RequestParam(required = false) @Nullable String actorId,
            @RequestParam(required = false) @Nullable String action,
            @RequestParam(required = false) @Nullable String targetType,
            @RequestParam(required = false) @Nullable String targetId,
            @RequestParam(required = false) @Nullable String from,
            @RequestParam(required = false) @Nullable String to,
            @RequestParam(required = false) @Nullable Integer limit) {
        security.require(principal, Namespaces.Platform.AUDIT_READ, null, null);

        int lim = limit == null ? 50 : Math.max(1, Math.min(limit, 200));
        Instant fromInstant = parseInstant(from);
        Instant toInstant = parseInstant(to);
        if (fromInstant != null && toInstant != null && fromInstant.isAfter(toInstant)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from 不能晚于 to");
        }

        PlatformAuditQuery query = new PlatformAuditQuery(
                actorId, action, targetType, targetId, fromInstant, toInstant, lim, null);
        return ResponseEntity.ok(audit.query(query).stream()
                .map(AuditSummary::of).toList());
    }

    private static Instant parseInstant(@Nullable String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "时间格式须为 ISO-8601（如 2026-10-26T00:00:00Z）");
        }
    }

    public record AuditSummary(String id, String actorId, String actorType, String action,
                              String targetType, String targetId, String decision,
                              String reasonCode, String correlationId, String ip, String occurredAt) {
        static AuditSummary of(PlatformAuditEvent e) {
            return new AuditSummary(
                    e.id().toString(), e.actorId(), e.actorType(), e.action(),
                    e.targetType(), e.targetId(), e.decision(), e.reasonCode(),
                    e.correlationId() == null ? null : e.correlationId().toString(),
                    e.ip(), e.occurredAt().toString());
        }
    }
}
