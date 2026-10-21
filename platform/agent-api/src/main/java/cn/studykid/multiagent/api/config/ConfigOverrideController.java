package cn.studykid.multiagent.api.config;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import cn.studykid.multiagent.api.governance.GovernanceSupport;
import cn.studykid.multiagent.api.run.RunController.CurrentPrincipal;
import cn.studykid.multiagent.application.config.ConfigTighteningPolicy;
import cn.studykid.multiagent.application.config.ConfigTighteningPolicy.ConfigRelaxationException;
import cn.studykid.multiagent.application.config.ConfigTighteningPolicy.ValueType;
import cn.studykid.multiagent.application.config.EffectiveConfigResolver;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.ConfigOverridePort;
import cn.studykid.multiagent.ports.persistence.ConfigOverridePort.OverrideEntry;
import cn.studykid.multiagent.ports.persistence.ConfigOverridePort.Tightening;
import cn.studykid.multiagent.ports.persistence.RateLimitPort;
import cn.studykid.multiagent.ports.security.AuthorizationService;

/**
 * 配置写入端点（P4-D15 / F6 · C3 写入端点 + C5 收紧强制 + C7 变更理由）。
 *
 * <p><b>⚠️ 与 {@link ConfigController} 的分工</b>：后者是只读投影
 * （yml 声明值 + {@code effective} 接线标注），本类是**写路径**。
 * 两者刻意分开——只读端点绝不能因为写入失败而降级。
 *
 * <p><b>⚠️ 三个端点共享 {@code GOVERNANCE_QUERY} 限流桶</b>（与审批/审计/Agent 同桶）。
 *
 * <p><b>⚠️ 操作者只来自令牌</b>（{@code currentPrincipal.principalId()}），
 * 绝不取自请求体——否则任何人都能冒充操作者留痕。
 *
 * <p><b>⚠️ 错误语义刻意区分</b>（沿用 P4-D13 治理端点约定）：
 * <ul>
 *   <li>键不在白名单 → **404**：与「不可见」同码，避免暴露「哪些键存在」</li>
 *   <li>理由过短 / 值格式非法 → **400**：输入错</li>
 *   <li>试图放松保护强度 → **409**：这是<b>安全门禁降级</b>，不是输入错误。
 *       与 400 分开是刻意的——前端要给出「这次变更被安全策略拒绝」的明确提示，
 *       而不是让用户以为是自己的输入写错了。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/config/overrides")
public class ConfigOverrideController {

    private final ConfigOverridePort overrides;
    private final EffectiveConfigResolver resolver;
    private final AuthorizationService authorization;
    private final RateLimitPort rateLimiter;
    private final CurrentPrincipal currentPrincipal;
    private final ConfigReadService readService;

    public ConfigOverrideController(ConfigOverridePort overrides,
                                    EffectiveConfigResolver resolver,
                                    AuthorizationService authorization,
                                    RateLimitPort rateLimiter,
                                    CurrentPrincipal currentPrincipal,
                                    ConfigReadService readService) {
        this.overrides = overrides;
        this.resolver = resolver;
        this.authorization = authorization;
        this.rateLimiter = rateLimiter;
        this.currentPrincipal = currentPrincipal;
        this.readService = readService;
    }

    /**
     * 列出本租户的全部配置覆盖 + 每条的生效来源。
     *
     * <p>⚠️ 只列<b>可写目录内的键</b>：不可写键不出现在写页面，
     * 免得管理员以为「怎么没有这个配置」。
     */
    @GetMapping
    public ResponseEntity<List<OverrideResponse>> listOverrides() {
        TenantContext context = currentPrincipal.context();
        authorization.requireAuthorized(context,
                AuthorizationService.Actions.AGENT_MANAGE, null);
        if (GovernanceSupport.rateLimited(rateLimiter, context,
                RateLimitPort.Buckets.GOVERNANCE_QUERY)) {
            return GovernanceSupport.tooManyRequests();
        }

        Map<String, String> declared = readService.declaredValues();
        EffectiveConfigResolver.Effective effective =
                resolver.resolve(context.tenantId(), declared);
        Map<String, OverrideEntry> dbValues = overrides.overridesFor(context.tenantId());

        List<OverrideResponse> out = ConfigOverridePort.WRITABLE.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> {
                    String key = e.getKey();
                    OverrideEntry stored = dbValues.get(key);
                    EffectiveConfigResolver.Resolved resolved = effective.values().get(key);
                    return new OverrideResponse(
                            key,
                            declared.get(key),
                            resolved == null ? null : resolved.value(),
                            resolved == null ? null : resolved.source(),
                            e.getValue(),
                            stored == null ? null : stored.reason(),
                            stored == null ? null : stored.changedBy(),
                            stored == null ? null : stored.updatedAt());
                })
                .toList();
        return ResponseEntity.ok(out);
    }

    /**
     * 写入一条配置覆盖。
     *
     * <p>⚠️ <b>收紧校验发生在写库之前</b>：被拒的写入<b>不落库、不写审计</b>——
     * 审计「谁尝试过放宽保护强度」本身有价值，但那属于安全事件日志，
     * 不应和「成功变更」混在同一张审计表里让前端无法区分。
     */
    @PutMapping("/{key}")
    public ResponseEntity<OverrideResponse> applyOverride(
            @PathVariable String key,
            @RequestBody OverrideRequest request) {

        TenantContext context = currentPrincipal.context();
        authorization.requireAuthorized(context,
                AuthorizationService.Actions.AGENT_MANAGE, null);
        if (GovernanceSupport.rateLimited(rateLimiter, context,
                RateLimitPort.Buckets.GOVERNANCE_QUERY)) {
            return GovernanceSupport.tooManyRequests();
        }

        // ⚠️ 先确认键可写（404）再校验其它，避免把「键不存在」泄露成参数错误
        if (!ConfigOverridePort.WRITABLE.containsKey(key)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "资源不存在");
        }

        Tightening direction = ConfigOverridePort.WRITABLE.get(key);
        Map<String, String> declared = readService.declaredValues();
        EffectiveConfigResolver.Effective effective =
                resolver.resolve(context.tenantId(), declared);
        EffectiveConfigResolver.Resolved current = effective.values().get(key);
        // ⚠️ 当前生效值（DB 覆盖 ⊕ yml），**不是** yml 默认——
        //    拿 yml 当基准会让人「先收紧一次再反复重写」绕过棘轮。
        String currentValue = current == null ? null : current.value();

        try {
            ConfigTighteningPolicy.validate(key, request.value(), currentValue,
                    request.reason(), valueTypeOf(key));
        } catch (ConfigRelaxationException e) {
            // ⚠️ 不在此处转 409：交给 ApiExceptionHandler#handleConfigRelaxation，
            //    以便返回专用 code CONFIG_RELAXATION_REJECTED。
            //    若走 ResponseStatusException，codeOf(CONFLICT) 会把它错标成
            //    IDEMPOTENCY_CONFLICT，前端就会建议「刷新重试」——而刷新
            //    永远不会让「只能收紧」变松。
            throw e;
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }

        OverrideEntry saved = overrides.apply(context.tenantId(), key, request.value(),
                request.reason(), currentPrincipal.principalId());
        return ResponseEntity.ok(new OverrideResponse(
                key,
                declared.get(key),
                saved.value(),
                "db_override",
                saved.tightening(),
                saved.reason(),
                saved.changedBy(),
                saved.updatedAt()));
    }

    /** 清除覆盖 → 回落到 yml 默认值。 */
    @DeleteMapping("/{key}")
    public ResponseEntity<Void> clearOverride(@PathVariable String key) {
        TenantContext context = currentPrincipal.context();
        authorization.requireAuthorized(context,
                AuthorizationService.Actions.AGENT_MANAGE, null);
        if (GovernanceSupport.rateLimited(rateLimiter, context,
                RateLimitPort.Buckets.GOVERNANCE_QUERY)) {
            return GovernanceSupport.tooManyRequests();
        }
        try {
            overrides.clear(context.tenantId(), key, currentPrincipal.principalId());
        } catch (ConfigOverridePort.UnknownConfigKeyException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "资源不存在");
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * 配置键 → 校验用类型。
     *
     * <p>⚠️ 与 {@code JdbcConfigOverrideRepository.valueTypeOf} <b>必须一致</b>：
     * 两处若漂移，会出现「校验按 DECIMAL 放行、按 INTEGER 落库」——
     * 写入成功但下次解析得到不同的值。故两处均由本切片的 IT 覆盖。
     */
    private static ValueType valueTypeOf(String key) {
        return switch (key) {
            case "agent.run.max-cost-per-run" -> ValueType.DECIMAL;
            case "agent.approval.required-by-risk" -> ValueType.ENUM;
            case "agent.approval.ttl" -> ValueType.DURATION;
            default -> ValueType.INTEGER;
        };
    }

    /** 写入请求。 */
    public record OverrideRequest(@Nullable String value, @Nullable String reason) {
    }

    /** 覆盖响应。 */
    public record OverrideResponse(
            @NonNull String key,
            /** yml 声明值（不可变参照） */
            @Nullable String declaredValue,
            /** 当前生效值（DB 覆盖优先） */
            @Nullable String effectiveValue,
            /** {@code db_override} 或 {@code yml} */
            @Nullable String source,
            @NonNull Tightening tightening,
            @Nullable String reason,
            @Nullable String changedBy,
            @Nullable Instant updatedAt) {
    }
}
