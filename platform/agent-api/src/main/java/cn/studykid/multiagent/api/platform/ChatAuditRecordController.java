package cn.studykid.multiagent.api.platform;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import cn.studykid.multiagent.api.governance.GovernanceSupport;
import cn.studykid.multiagent.api.security.PlatformSecurity;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditRecordQueryPort;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditRecordQueryPort.Detail;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditRecordQueryPort.Query;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditRecordQueryPort.Source;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditRecordQueryPort.Summary;
import cn.studykid.multiagent.ports.persistence.Page;
import cn.studykid.multiagent.ports.security.IdentityDomains;
import cn.studykid.multiagent.ports.security.Namespaces;
import cn.studykid.multiagent.ports.security.PlatformPrincipal;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 对话审计记录查询端点（平台审计/效果评估角色，跨租户只读）。
 *
 * <p>URL：{@code GET /api/platform/v1/chat-audit-records}（列表）+
 * {@code GET /api/platform/v1/chat-audit-records/{source}/{auditId}}（详情）。
 *
 * <p><b>⚠️ 鉴权</b>：{@code platform.audit.read}，与
 * {@link PlatformAuditController} 同源；授予 SUPER_ADMIN / SECURITY_ADMIN / AUDITOR。
 * 页面角色守卫（admin-console AUDIT_SCOPE）只是导航显隐，真正边界在本 Controller。
 *
 * <p><b>⚠️ 与配置页分开</b>：配置页（写开关）要 PLATFORM_ADMIN；本页读的是租户<b>原文</b>，
 * 属取证/效果评估能力，用审计读权限，二者都是平台级但不互相授权。
 *
 * <p><b>⚠️ 跨租户读</b>：底层仓储走 BYPASSRLS 运维数据源（组合根装配、fail-closed）。
 * 运维凭据未接入时取连接即抛 {@code PlatformOpsUnavailableException} → 全局 503，
 * <b>不返回空列表伪装「没有记录」</b>（空态与「通道未接入」是两回事）。
 *
 * <p><b>⚠️ 详情回显完整原文</b>：{@code payload} 是信封 JSON 原样反序列化，
 * 含用户问题 / 模型回答 / thinking / 逐分片；这是审计取证的本意。列表页只给截断预览，
 * 不加载全文。该端点<b>只读</b>，无写/删入口。
 */
@RestController
@RequestMapping("/api/platform/v1/chat-audit-records")
public class ChatAuditRecordController {

    /** 终态白名单，与 DDL 的 chat_audit_record_terminal_ck 逐字一致。 */
    private static final Set<String> TERMINAL_STATES = Set.of(
            "SUCCEEDED", "FAILED", "TIMED_OUT", "CANCELLED", "CLIENT_DISCONNECTED", "REJECTED");

    private final ChatAuditRecordQueryPort queryPort;
    private final PlatformSecurity security;
    private final ObjectMapper objectMapper;

    public ChatAuditRecordController(@NonNull ChatAuditRecordQueryPort queryPort,
                                     @NonNull PlatformSecurity security) {
        this.queryPort = queryPort;
        this.security = security;
        this.objectMapper = new ObjectMapper();
    }

    /** 列表（游标分页）。 */
    @GetMapping
    public ResponseEntity<GovernanceSupport.PageResponse<AuditSummary>> list(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @RequestParam(required = false) @Nullable String source,
            @RequestParam(required = false) @Nullable String tenantId,
            @RequestParam(required = false) @Nullable String endpoint,
            @RequestParam(required = false) @Nullable String terminalState,
            @RequestParam(required = false) @Nullable String from,
            @RequestParam(required = false) @Nullable String to,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        requireAuditRead(principal);

        int lim = GovernanceSupport.limit(limit);
        Instant fromInstant = GovernanceSupport.instant(from, "from");
        Instant toInstant = GovernanceSupport.instant(to, "to");
        GovernanceSupport.checkRange(fromInstant, toInstant);
        GovernanceSupport.requireEnum(terminalState, TERMINAL_STATES, "terminalState");
        String tenant = normalizeUuid(tenantId, "tenantId");

        Query query = new Query(parseSource(source), tenant, trimToNull(endpoint),
                trimToNull(terminalState), fromInstant, toInstant, trimToNull(cursor), lim);
        Page<Summary> page = queryPort.list(query);
        List<AuditSummary> items = page.items().stream().map(AuditSummary::of).toList();
        return ResponseEntity.ok(GovernanceSupport.PageResponse.of(items, page.nextCursor()));
    }

    /** 详情：source + auditId 双键定位；payload 为信封 JSON（已反序列化为 JsonNode）。 */
    @GetMapping("/{source}/{auditId}")
    public ResponseEntity<AuditDetail> detail(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @PathVariable String source,
            @PathVariable String auditId) {
        requireAuditRead(principal);

        Source parsedSource;
        try {
            parsedSource = Source.valueOf(source.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "source 非法（允许 RECORD / UNATTRIBUTED）");
        }
        String uuid = normalizeUuid(auditId, "auditId");
        Detail detail = queryPort.detail(parsedSource, uuid);
        if (detail == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "审计记录不存在");
        }
        return ResponseEntity.ok(AuditDetail.of(detail, parsePayload(detail.payloadJson())));
    }

    private void requireAuditRead(PlatformPrincipal principal) {
        security.require(principal, Namespaces.Platform.AUDIT_READ, null, null);
    }

    /** null / 空白 / ALL = 两域合并（port 传 null）；RECORD / UNATTRIBUTED = 单域。 */
    private static @Nullable Source parseSource(@Nullable String raw) {
        if (raw == null || raw.isBlank() || "ALL".equalsIgnoreCase(raw.trim())) {
            return null;
        }
        try {
            return Source.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "source 非法（允许 ALL / RECORD / UNATTRIBUTED）");
        }
    }

    private static @Nullable String normalizeUuid(@Nullable String raw, String name) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value).toString();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + " 不是合法 UUID");
        }
    }

    private static @Nullable String trimToNull(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim();
    }

    private JsonNode parsePayload(@NonNull String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            // 信封是审计写入时序列化的合法 JSON；读不出来说明库里那条记录损坏——
            // 这是数据完整性问题，明确 500，不回退空对象伪装「没有内容」。
            throw new IllegalStateException("审计信封 JSON 解析失败", e);
        }
    }

    /** 列表摘要视图（不含原文，仅截断预览）。 */
    public record AuditSummary(
            String auditId, String traceId, String source, String tenantId, String domain,
            String principalId, String endpoint, String startedAt, String completedAt,
            long durationMs, String terminalState, String httpMethod, int httpStatus,
            String messagePreview, String rejectionReason) {

        static @NonNull AuditSummary of(@NonNull Summary s) {
            return new AuditSummary(s.auditId(), s.traceId(), s.source(), s.tenantId(), s.domain(),
                    s.principalId(), s.endpoint(), s.startedAt(), s.completedAt(), s.durationMs(),
                    s.terminalState(), s.httpMethod(), s.httpStatus(), s.messagePreview(),
                    s.rejectionReason());
        }
    }

    /** 详情视图：元数据 + 完整信封 JSON（payload）。 */
    public record AuditDetail(
            String source, String auditId, String traceId, String tenantId, String domain,
            String principalId, String endpoint, String startedAt, String completedAt,
            long durationMs, String terminalState, String httpMethod, int httpStatus,
            long configVersion, String rejectionReason, JsonNode payload) {

        static @NonNull AuditDetail of(@NonNull Detail d, @NonNull JsonNode payload) {
            return new AuditDetail(d.source(), d.auditId(), d.traceId(), d.tenantId(), d.domain(),
                    d.principalId(), d.endpoint(), d.startedAt(), d.completedAt(), d.durationMs(),
                    d.terminalState(), d.httpMethod(), d.httpStatus(), d.configVersion(),
                    d.rejectionReason(), payload);
        }
    }
}
