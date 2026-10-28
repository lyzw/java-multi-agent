package cn.studykid.multiagent.application.cap;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.cap.CapRoutingPolicyDef;
import cn.studykid.multiagent.routing.RoutingDryRun;

/**
 * 路由策略 dry-run 用例（10 号设计 §7 债 5 · 管理台 A7-1b）。
 *
 * <p><b>本类只做两件事</b>：把草稿按<b>发布路径同一个构造入口</b>拼成策略文本，
 * 再把它交给 {@link RoutingDryRun}。求值、守卫、投影都在 {@code agent-routing} 里 ——
 * 应用层不另写一份判定，否则这里就成了第三套真相。
 *
 * <p>⚠️ <b>零副作用</b>：不读库、不写库、不占版本号。
 * 版本号用占位值（{@link RoutingPolicyDrafts#DRY_RUN_VERSION}）——
 * 真实的下一版由发布申请在服务端算（客户端能指定版本，
 * 就等于能指定「内容没变」这个证明，与既有红线同口径）。
 *
 * <p>⚠️ <b>租户只从参数进来，而参数只由 API 层从已验签令牌取</b>：
 * 本用例不接受请求体里的 {@code tenantId}，探针里的 {@code ctx.tenantId}
 * 也在 {@code PolicyCodec.parseCtx} 处被拒（G-6）。
 */
public class RoutingPolicyDryRunService {

    private final Clock clock;

    public RoutingPolicyDryRunService(@NonNull Clock clock) {
        this.clock = clock;
    }

    /**
     * 试跑一份策略草稿。
     *
     * @param tenantId   已验签令牌里的租户标识
     * @param draft      策略草稿（三段 JSON 原文<b>原样透传</b>，不重新序列化）
     * @param probesJson 探针数组原文；{@code null} 或空白 = 不试跑任何输入，只出守卫报告
     * @return 结论（策略不合法时以 {@code policyIssue} 表达，不抛异常 ——
     *         「草稿写错了」正是 dry-run 要给出的<b>业务结论</b>，而不是一个 500）
     * @throws IllegalArgumentException 草稿连<b>行级</b>校验都不过（词汇表外的枚举名、空文本、
     *                                  {@code DEFAULT_TARGET} 没写兜底目标）—— 那是请求本身不合法
     */
    public RoutingDryRun.@NonNull Outcome run(@NonNull String tenantId,
                                              ConfigPublishPayload.@NonNull RoutingPolicySpec draft,
                                              @Nullable String probesJson) {
        Instant now = clock.instant();
        CapRoutingPolicyDef def = RoutingPolicyDrafts.toDef(tenantId, draft,
                RoutingPolicyDrafts.DRY_RUN_VERSION, now);
        return RoutingDryRun.run(def.definitionJson(), tenantId,
                LocalDate.ofInstant(now, ZoneOffset.UTC),
                probesJson == null || probesJson.isBlank() ? "[]" : probesJson);
    }
}
