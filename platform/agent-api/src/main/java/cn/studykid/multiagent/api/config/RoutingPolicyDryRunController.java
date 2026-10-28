package cn.studykid.multiagent.api.config;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import cn.studykid.multiagent.api.config.CapConfigPublishController.RoutingPolicyRequest;
import cn.studykid.multiagent.api.governance.GovernanceSupport;
import cn.studykid.multiagent.api.run.RunController.CurrentPrincipal;
import cn.studykid.multiagent.application.cap.RoutingPolicyDryRunService;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.RateLimitPort;
import cn.studykid.multiagent.ports.security.AuthorizationService;
import cn.studykid.multiagent.routing.EvaluationTraceEntry;
import cn.studykid.multiagent.routing.PublishGuards;
import cn.studykid.multiagent.routing.RoutingDryRun;
import cn.studykid.multiagent.routing.RoutingEngine;

/**
 * 路由策略 dry-run 端点（10 号设计 §7 债 5 · 管理台 A7-1b）。
 *
 * <p><b>它解决的是哪一种缺陷</b>：规则写错字段名<b>不会报错</b>，
 * 线上只表现为「路由经常走兜底」；DNF 超预算、规则互相遮蔽同理。
 * 发布期守卫（V3~V10）拦得住这些，但只有<b>提交之后</b>才拦得住 ——
 * 而配置者需要的恰恰是「提交之前就知道」。本端点给出那个答案。
 *
 * <p>⚠️ <b>本端点不复制任何判定</b>：请求体里的草稿原样交给
 * {@link RoutingEngine}／{@link PublishGuards}（经应用层用例），
 * 前端也不得再有第二份求值器。§7 债 5 的原文是
 * 「加后端 dry-run 接口，前端不自建 TS 引擎镜像」，理由与
 * 「两份解析代码迟早漂移」是同一条 —— 漂移的症状是
 * 「界面显示会命中 A，运行期实际去了 B」。
 *
 * <p>⚠️ <b>零副作用，但不是零成本</b>：不写库、不产生申请单、不占版本号；
 * 因此与 {@code /publishes} 一样纳入限流 —— 一次试跑要把每条探针过一遍引擎与守卫，
 * 不限流的读接口在治理通道上同样是自伤面。
 *
 * <p>⚠️ <b>授权沿用 {@code config:publish}</b>：dry-run 是「发布申请的前置动作」，
 * 能申请发布的人本来就能让守卫跑过这份草稿；另开一个动作只会造出
 * 「能试跑但不能发布」这种没人用得上的缝隙，而新增动作要动的是<b>冻结的角色矩阵</b>。
 *
 * <p>⚠️ <b>租户只来自已验签令牌</b>（{@link CurrentPrincipal#context()}）：
 * 请求体与探针都无权指定 {@code tenantId}（G-6 的口径，见
 * {@code PolicyCodec.parseCtx}）。
 *
 * <p>⛔ <b>结论的边界要写给配置者</b>：这里判的是<b>选择</b>（会走到哪个目标、为什么），
 * <b>准入</b>（目标 ∈ 父声明的子 Agent、固定版本、逐跳授权、深度/次数/超时/预算）
 * 在运行期的阶段⑤ 判，dry-run 碰不到 —— 它没有父声明。
 * 「dry-run 说没问题」<b>不等于</b>「上线一定成功」。
 */
@RestController
@RequestMapping("/api/cap/config/routing-policies")
public class RoutingPolicyDryRunController {

    private final RoutingPolicyDryRunService dryRun;
    private final AuthorizationService authorization;
    private final RateLimitPort rateLimiter;
    private final CurrentPrincipal currentPrincipal;

    public RoutingPolicyDryRunController(RoutingPolicyDryRunService dryRun,
                                        AuthorizationService authorization,
                                        RateLimitPort rateLimiter,
                                        CurrentPrincipal currentPrincipal) {
        this.dryRun = dryRun;
        this.authorization = authorization;
        this.rateLimiter = rateLimiter;
        this.currentPrincipal = currentPrincipal;
    }

    /** 试跑一份草稿（不落库、不产生申请单）。 */
    @PostMapping("/dry-run")
    public ResponseEntity<DryRunResponse> dryRun(@RequestBody DryRunRequest request) {
        TenantContext context = currentPrincipal.context();
        authorization.requireAuthorized(context,
                AuthorizationService.Actions.CONFIG_PUBLISH, null);
        if (rateLimited(context)) {
            return GovernanceSupport.tooManyRequests();
        }
        if (request.policy() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "policy 必填：dry-run 试的是「这份草稿提交后会怎样」，没有草稿就没有结论");
        }
        try {
            RoutingDryRun.Outcome outcome = dryRun.run(context.tenantId(),
                    request.policy().toSpec(), request.probes());
            return ResponseEntity.ok(DryRunResponse.of(outcome));
        } catch (IllegalArgumentException e) {
            // 行级不合法（枚举名拼错、空文本、DEFAULT_TARGET 没写兜底目标）是请求问题；
            // 而「策略文本能装配、但文法/守卫不过」是业务结论，走 200 + parsed/publishable 表达。
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private boolean rateLimited(TenantContext context) {
        return GovernanceSupport.rateLimited(rateLimiter, context,
                RateLimitPort.Buckets.GOVERNANCE_QUERY);
    }

    // ── DTO ────────────────────────────────────────────────────────

    /**
     * dry-run 请求体。
     *
     * @param policy 策略草稿（与发布申请里的 {@code routingPolicies[]} 条目<b>同一个形状</b>：
     *               {@code inputSchema}/{@code evaluation}/{@code sampleSet} 是 JSON <b>原文字符串</b>，
     *               原样透传、不在入口重新序列化）
     * @param probes 探针数组原文（JSON 数组字符串，可缺省 = 只出守卫报告）。
     *               ⚠️ 与三段策略文本同口径处理：探针要按<b>提交前的原文</b>解析，
     *               在传输层就绑成对象树会把字段顺序、null 语义交给 Jackson 决定
     */
    public record DryRunRequest(@Nullable RoutingPolicyRequest policy,
                                @Nullable String probes) {
    }

    /**
     * 一条守卫结论。
     *
     * @param code    守卫编号（{@code V3}~{@code V10}）
     * @param message 人话结论
     * @param warning 是否只是警告（只有 V7 是；其余阻止发布）
     */
    public record FindingResponse(@NonNull String code,
                                  @NonNull String message,
                                  boolean warning) {

        static FindingResponse of(PublishGuards.@NonNull Finding finding) {
            return new FindingResponse(finding.code(), finding.message(), finding.warning());
        }
    }

    /**
     * 一条探针的试跑结论。
     *
     * @param label             探针标签
     * @param projectedInput    <b>真正进入求值视野</b>的字段（词汇表外的字段在这里看不到 ——
     *                          这正是「字段名写错」在界面上唯一的可见证据）
     * @param decided           是否跑出了引擎结论（{@code false} 时看 {@code issueMessage}）
     * @param mode              决策所用模式
     * @param resolution        稳定码（{@code RULE_MATCH}/{@code SEMANTIC_MATCH}/
     *                          {@code DEFAULT_TARGET}/{@code NO_MATCH_REJECTED}/
     *                          {@code TARGET_NOT_ALLOWED}/…）
     * @param finalTarget       最终目标（失败码时为 {@code null}）
     * @param matchedRuleId     命中的规则标识
     * @param candidateFromModel 模拟的模型候选
     * @param conflict          规则命中且与候选不同（已按 {@code onConflict} 裁决）
     * @param error             可读原因（不进稳定码契约）
     * @param trace             求值轨迹（按序号升序，只含实际判过的规则）
     * @param issueWhere        探针本身不合法的位置
     * @param issueMessage      探针本身不合法的原因
     */
    public record ProbeResponse(
            @NonNull String label,
            @NonNull Map<String, Object> projectedInput,
            boolean decided,
            @Nullable String mode,
            @Nullable String resolution,
            @Nullable String finalTarget,
            @Nullable String matchedRuleId,
            @Nullable String candidateFromModel,
            boolean conflict,
            @Nullable String error,
            @NonNull List<TraceResponse> trace,
            @Nullable String issueWhere,
            @Nullable String issueMessage) {

        static ProbeResponse of(RoutingDryRun.@NonNull ProbeOutcome probe) {
            var decision = probe.decision();
            if (decision == null) {
                return new ProbeResponse(probe.label(), probe.projectedInput(), false,
                        null, null, null, null, null, false, null, List.of(),
                        probe.issue() == null ? null : probe.issue().where(),
                        probe.issue() == null ? null : probe.issue().message());
            }
            return new ProbeResponse(probe.label(), probe.projectedInput(), true,
                    decision.mode().name(), decision.resolution().name(),
                    decision.finalTarget(), decision.matchedRuleId(),
                    decision.candidateFromModel(), decision.conflict(), decision.error(),
                    decision.trace().stream().map(TraceResponse::of).toList(),
                    null, null);
        }
    }

    /**
     * 求值轨迹的一条。
     *
     * @param ordinal 规则序号
     * @param ruleId  规则标识
     * @param outcome {@code MATCH} / {@code NO_MATCH} / {@code ERROR_SKIPPED} / {@code ERROR_REJECTED}
     * @param error   {@code ERROR_*} 的原因
     */
    public record TraceResponse(int ordinal,
                                @NonNull String ruleId,
                                @NonNull String outcome,
                                @Nullable String error) {

        static TraceResponse of(@NonNull EvaluationTraceEntry entry) {
            return new TraceResponse(entry.ordinal(), entry.ruleId(), entry.outcome(),
                    entry.error());
        }
    }

    /**
     * dry-run 结论。
     *
     * @param parsed        策略文本是否解析通过
     * @param errorWhere    解析失败的位置（{@code parsed=false} 时非空）
     * @param errorMessage  解析失败的原因
     * @param publishable   这份草稿<b>现在</b>提交会不会被发布期守卫放行
     *                      （策略解析不过时为 {@code false}）
     * @param violations    阻止发布的守卫结论（V3~V6、V8~V10）
     * @param warnings      不阻止发布但要提示的守卫结论（V7）
     * @param probes        逐条试跑结论（无探针时为空）
     * @param replayDate    本次回放所用的 UTC 日（引用 {@code ctx.now.utcDate} 的策略结论取决于它，
     *                      故必须回显，不能让配置者以为结论与日子无关）
     */
    public record DryRunResponse(
            boolean parsed,
            @Nullable String errorWhere,
            @Nullable String errorMessage,
            boolean publishable,
            @NonNull List<FindingResponse> violations,
            @NonNull List<FindingResponse> warnings,
            @NonNull List<ProbeResponse> probes,
            @NonNull String replayDate) {

        static DryRunResponse of(RoutingDryRun.@NonNull Outcome outcome) {
            return new DryRunResponse(outcome.parsed(),
                    outcome.policyIssue() == null ? null : outcome.policyIssue().where(),
                    outcome.policyIssue() == null ? null : outcome.policyIssue().message(),
                    outcome.publishable(),
                    outcome.guards().violations().stream().map(FindingResponse::of).toList(),
                    outcome.guards().warnings().stream().map(FindingResponse::of).toList(),
                    outcome.probes().stream().map(ProbeResponse::of).toList(),
                    outcome.replayDate().toString());
        }
    }
}
