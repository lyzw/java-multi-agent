package cn.studykid.multiagent.api.config;

import java.time.Instant;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import cn.studykid.multiagent.api.governance.GovernanceSupport;
import cn.studykid.multiagent.api.run.RunController.CurrentPrincipal;
import cn.studykid.multiagent.application.config.ConfigTighteningPolicy.ConfigRelaxationException;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.ConfigChangePort;
import cn.studykid.multiagent.ports.persistence.ConfigChangePort.ChangeItem;
import cn.studykid.multiagent.ports.persistence.ConfigChangePort.Decision;
import cn.studykid.multiagent.ports.persistence.ConfigOverridePort;
import cn.studykid.multiagent.ports.persistence.RateLimitPort;
import cn.studykid.multiagent.ports.security.AuthorizationService;

/**
 * 高危配置变更审批端点（P4-D16 / **C7**）。
 *
 * <p><b>⚠️ 四步流程刻意分开，不可合并</b>：
 * <pre>
 *   POST /api/config/changes                    → PENDING   （提交申请，不写配置表）
 *   POST /api/config/changes/{id}/decision      → APPROVED  （批准，仍不写配置表）
 *   POST /api/config/changes/{id}/apply         → APPLIED   （此步才真正写入）
 * </pre>
 * 合并「批准」与「生效」会让审批人无法区分
 * 「我批了」与「配置真的变了」——正是 P4-D15 D15-B3 的形态。
 *
 * <p><b>⚠️ 双人复核</b>：{@code decide} 由 {@code SelfApprovalException}
 * 强制「申请人 ≠ 审批人」，DB 侧另有 CHECK 兜底。
 *
 * <p><b>⚠️ 鉴权分离</b>：提交需 {@code config:request}，
 * 审批需 {@code config:approve}。二者刻意不同动作，
 * 但**仍不能替代双人复核**——同一主体可同时具备两个权限，
 * 最终靠身份比对拦截。
 *
 * <p><b>⚠️ 限流</b>：四端点同用 {@code GOVERNANCE_QUERY} 桶。
 */
@RestController
@RequestMapping("/api/config/changes")
public class ConfigChangeController {

    private final ConfigChangePort changes;
    private final AuthorizationService authorization;
    private final RateLimitPort rateLimiter;
    private final CurrentPrincipal currentPrincipal;

    public ConfigChangeController(ConfigChangePort changes,
                                  AuthorizationService authorization,
                                  RateLimitPort rateLimiter,
                                  CurrentPrincipal currentPrincipal) {
        this.changes = changes;
        this.authorization = authorization;
        this.rateLimiter = rateLimiter;
        this.currentPrincipal = currentPrincipal;
    }

    /** 提交变更申请（<b>不写入配置表</b>）。 */
    @PostMapping
    public ResponseEntity<ChangeResponse> request(@RequestBody ChangeRequest request) {
        TenantContext context = currentPrincipal.context();
        authorization.requireAuthorized(context,
                AuthorizationService.Actions.CONFIG_REQUEST, null);
        if (rateLimited(context)) {
            return GovernanceSupport.tooManyRequests();
        }

        try {
            ChangeItem item = changes.request(context.tenantId(),
                    request.key(), request.value(), request.reason(),
                    currentPrincipal.principalId());
            return ResponseEntity.ok(ChangeResponse.of(item));
        } catch (ConfigOverridePort.UnknownConfigKeyException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "资源不存在");
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /** 列出变更申请（游标分页）。 */
    @GetMapping
    public ResponseEntity<GovernanceSupport.PageResponse<ChangeResponse>> list(
            @RequestParam(required = false) @Nullable String state,
            @RequestParam(required = false) @Nullable Integer limit,
            @RequestParam(required = false) @Nullable String cursor) {

        TenantContext context = currentPrincipal.context();
        authorization.requireAuthorized(context,
                AuthorizationService.Actions.CONFIG_REQUEST, null);
        if (rateLimited(context)) {
            return GovernanceSupport.tooManyRequests();
        }

        ConfigChangePort.ChangeState parsed = null;
        if (state != null && !state.isBlank()) {
            try {
                parsed = ConfigChangePort.ChangeState.valueOf(state.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                // ⚠️ 非法状态必须 400，**不得**静默返回空列表：
                //    前端传 state=APPROVED 而它不存在时，
                //    返回空列表会让人以为「没有待审批的」，真相是「状态名错了」。
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "state 取值非法: " + state);
            }
        }

        int pageSize = GovernanceSupport.limit(limit);
        var page = changes.list(context.tenantId(), parsed, cursor, pageSize);
        return ResponseEntity.ok(GovernanceSupport.PageResponse.of(
                page.items().stream().map(ChangeResponse::of).toList(),
                page.nextCursor()));
    }

    /** 审批决定（批准或拒绝；<b>不写入配置表</b>）。 */
    @PostMapping("/{changeId}/decision")
    public ResponseEntity<ChangeResponse> decide(
            @PathVariable String changeId,
            @RequestBody DecisionRequest request) {

        TenantContext context = currentPrincipal.context();
        authorization.requireAuthorized(context,
                AuthorizationService.Actions.CONFIG_APPROVE, null);
        if (rateLimited(context)) {
            return GovernanceSupport.tooManyRequests();
        }

        Decision decision;
        try {
            decision = Decision.valueOf(request.decision().trim().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "decision 仅接受 APPROVE / REJECT");
        }

        try {
            ChangeItem item = changes.decide(context.tenantId(), changeId,
                    decision, request.note(), currentPrincipal.principalId());
            return ResponseEntity.ok(ChangeResponse.of(item));
        } catch (ConfigChangePort.SelfApprovalException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "不得审批自己提交的变更申请");
        } catch (ConfigChangePort.IllegalStateConflictException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "资源与当前状态冲突");
        } catch (ConfigChangePort.ChangeNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "资源不存在");
        }
    }

    /** 应用已批准的变更（<b>此步才真正写入配置表</b>）。 */
    @PostMapping("/{changeId}/apply")
    public ResponseEntity<ChangeResponse> apply(@PathVariable String changeId) {
        TenantContext context = currentPrincipal.context();
        authorization.requireAuthorized(context,
                AuthorizationService.Actions.CONFIG_APPROVE, null);
        if (rateLimited(context)) {
            return GovernanceSupport.tooManyRequests();
        }

        try {
            ChangeItem item = changes.apply(context.tenantId(), changeId,
                    currentPrincipal.principalId());
            return ResponseEntity.ok(ChangeResponse.of(item));
        } catch (ConfigChangePort.IllegalStateConflictException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "资源与当前状态冲突");
        } catch (ConfigRelaxationException e) {
            // ⚠️ apply 时重做的收紧校验可能拒绝：
            //    申请提交后配置已被他人改过，此时的「收紧」相对新基线可能是放松。
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "配置变更被安全策略拒绝：配置只允许收紧，不允许放松");
        } catch (ConfigChangePort.ChangeNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "资源不存在");
        }
    }

    private boolean rateLimited(TenantContext context) {
        return GovernanceSupport.rateLimited(rateLimiter, context,
                RateLimitPort.Buckets.GOVERNANCE_QUERY);
    }

    /** 提交申请请求。 */
    public record ChangeRequest(@Nullable String key,
                                @Nullable String value,
                                @Nullable String reason) {
    }

    /** 审批决定请求。 */
    public record DecisionRequest(@Nullable String decision, @Nullable String note) {
    }

    /** 变更申请响应。 */
    public record ChangeResponse(
            @NonNull String changeId,
            @NonNull String key,
            @NonNull String proposedValue,
            @NonNull String valueType,
            @NonNull String reason,
            @NonNull String requesterId,
            @NonNull Instant requestedAt,
            @NonNull String state,
            @Nullable String approverId,
            @Nullable Instant decidedAt,
            @Nullable String decisionNote,
            @Nullable Instant appliedAt) {

        public static ChangeResponse of(ChangeItem item) {
            return new ChangeResponse(
                    item.changeId(), item.key(), item.proposedValue(), item.valueType(),
                    item.reason(), item.requesterId(), item.requestedAt(),
                    item.state().name(), item.approverId(), item.decidedAt(),
                    item.decisionNote(), item.appliedAt());
        }
    }
}