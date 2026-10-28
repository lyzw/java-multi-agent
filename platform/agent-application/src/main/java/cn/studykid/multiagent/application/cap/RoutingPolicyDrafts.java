package cn.studykid.multiagent.application.cap;

import java.time.Instant;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.core.cap.CapRoutingPolicyDef;
import cn.studykid.multiagent.core.orchestration.ConflictPolicy;
import cn.studykid.multiagent.core.orchestration.NoMatchAction;
import cn.studykid.multiagent.core.orchestration.RoutingMode;
import cn.studykid.multiagent.routing.PolicySpec;
import cn.studykid.multiagent.routing.PolicyCodec;

/**
 * 路由策略草稿 → 定义行 → 策略对象（发布申请与 dry-run <b>共用的唯一构造入口</b>）。
 *
 * <p>⚠️ <b>为什么要有这个类</b>：dry-run 要给配置者看的必须是
 * 「这份草稿<b>真提交后</b>会跑成什么样」，而不是「按另一套拼装方式拼出来的东西跑成什么样」。
 * 若两处各写一遍「行字段 → 策略文本」的拼装，
 * 早晚有一处漏字段（例如 {@code onConflict} 取了默认值而草稿里写的是另一个），
 * 症状正是本仓反复出现的那一类：<b>dry-run 说没问题，发布后行为不同</b>。
 *
 * <p>⚠️ 本类<b>不做守卫</b>：V3~V10 只在 {@link ConfigPublishWorkflowService}
 * （发布申请）与 {@link RoutingPolicyDryRunService}（试跑）各自的收口点调用，
 * 语义一个是「拒收」，一个是「如实回报」。
 */
final class RoutingPolicyDrafts {

    /** dry-run 草稿的占位版本号（策略文本里有 {@code version} 键，但结论与它无关）。 */
    static final int DRY_RUN_VERSION = 1;

    private RoutingPolicyDrafts() {
    }

    /**
     * 草稿 → 定义行（只做<b>行级</b>一致性：非空、兜底目标与处置动作同时存在、
     * {@code RULES_ONLY/RULE_FIRST} 不许空规则数组）。
     *
     * <p>{@code configFingerprint} 填占位值：内容指纹不包含自身，
     * 故可先占位再由调用方替换（与发布路径同一手法）。
     *
     * @param tenantId 租户（只来自服务端认证上下文）
     * @param spec     客户端提交的草稿
     * @param version  版本号（发布路径为服务端算出的下一版；dry-run 用占位版）
     * @param now      服务端时间
     * @return 定义行
     * @throws IllegalArgumentException 行级校验不过（枚举词汇表外、空文本、兜底目标缺失等）
     */
    static @NonNull CapRoutingPolicyDef toDef(@NonNull String tenantId,
                                              ConfigPublishPayload.@NonNull RoutingPolicySpec spec,
                                              int version, @NonNull Instant now) {
        return new CapRoutingPolicyDef(tenantId, spec.policyId(), version,
                RoutingMode.from(spec.mode()), spec.inputSchema(), spec.evaluation(),
                spec.defaultTarget(), NoMatchAction.from(spec.noMatchAction()),
                ConflictPolicy.from(spec.onConflict()), spec.sampleSet(),
                // ⚠️ 占位指纹：内容指纹**不包含**自身，故可先占位再算
                "pending", 1, now, null, null);
    }

    /**
     * 定义行 → 策略对象（{@link PolicyCodec} 是<b>唯一</b>解析入口）。
     *
     * @param draft 定义行
     * @return 结构合法的策略对象
     * @throws cn.studykid.multiagent.routing.RoutingConfigException 文法、上限或结构不合法
     */
    static @NonNull PolicySpec parse(@NonNull CapRoutingPolicyDef draft) {
        return PolicyCodec.parseStandalone(draft.definitionJson());
    }
}
