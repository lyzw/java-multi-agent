package cn.studykid.multiagent.routing;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * dry-run：把「这份草稿现在提交会怎样」一次算给配置者看（10 号设计 §7 债 5 · A7-1b）。
 *
 * <p>⚠️ <b>本类存在的唯一理由是那一句「同一份实现」</b>：
 * 发布校验、运行期决策、管理台试跑三处必须共用
 * {@link PolicyCodec} + {@link PublishGuards} + {@link RoutingEngine}，
 * 否则会出现「面板说会命中 A，线上跑去了 B」——
 * 那正是本仓反复出事的形态：<b>看起来一致，实际不一致</b>，
 * 而且配置者会拿面板的结论去说服别人。
 * 因此：{@code agent-routing} 之外不得再写一份求值器（含前端 TS 镜像）。
 *
 * <p>⚠️ <b>零副作用</b>：不写库、不占版本、不产生申请单。
 * 探针与草稿都不落库 —— 试跑不是发布，把试跑写成申请单会让「改了三版草稿」
 * 变成「提了三次发布申请」，审批人看到的队列就不再是真实待办。
 *
 * <p>⚠️ <b>dry-run 的结论不等于运行期的全部结论</b>：
 * 这里判的是<b>选择</b>（这份策略会把输入送到哪个目标、为什么）；
 * <b>准入</b>（目标 ∈ 父声明的 {@code childAgentRefs}、固定版本、逐跳授权、
 * 深度/次数/超时/预算）由运行期的阶段⑤ 判，dry-run 碰不到 ——
 * 它没有父声明（见 {@link PolicyCodec#parseStandalone} 的同一条口径）。
 * 面板必须把这条边界写给配置者，否则「dry-run 说没问题」会被读成「上线一定成功」。
 *
 * @see DryRunProbe 探针与发布样本的区别
 */
public final class RoutingDryRun {

    /** 一次试跑的探针条数上限（每条都要过一遍引擎，无上限的试跑就是一次自伤）。 */
    public static final int MAX_PROBES = 20;

    /** 一个可读的失败位置与原因（配置期问题，不是运行期失败码）。 */
    public record Issue(@NonNull String where, @NonNull String message) {
    }

    /**
     * 一条探针的试跑结果。
     *
     * @param label           探针标签
     * @param projectedInput  <b>投影后</b>真正进入求值视野的字段：词汇表外的字段在这里看不到。
     *                        面板必须回显它 —— 字段名写错<b>不会报错</b>，
     *                        只会让规则静默改走别条（V3 治的就是这个），
     *                        而「写到别人身上看不出来」的配置缺陷没人会去查
     * @param decision        引擎结论（探针自身不合法时为 {@code null}）
     * @param issue           探针不合法的原因（与 {@code decision} 互斥）
     */
    public record ProbeOutcome(@NonNull String label,
                               @NonNull Map<String, Object> projectedInput,
                               @Nullable RoutingDecision decision,
                               @Nullable Issue issue) {

        /** 本条探针是否跑出了引擎结论。 */
        public boolean decided() {
            return decision != null;
        }
    }

    /**
     * 一次 dry-run 的完整结论。
     *
     * @param policyIssue 策略文本本身解析不过（{@code null} = 解析通过）。
     *                    此时 {@code guards}/{@code probes} 均为空 —— 没有词汇表就没有守卫可跑
     * @param guards      发布期守卫报告（V3~V10，含 V8 对 {@code sampleSet} 的回放比对）
     * @param probes      逐条探针的试跑结果
     * @param replayDate  本次试跑使用的 UTC 日（引用 {@code ctx.now.utcDate} 的策略结果取决于它，
     *                    故必须回显，不能让配置者以为结论与日子无关）
     */
    public record Outcome(@Nullable Issue policyIssue,
                          PublishGuards.@NonNull Report guards,
                          @NonNull List<ProbeOutcome> probes,
                          @NonNull LocalDate replayDate) {

        /** 策略文本是否解析通过。 */
        public boolean parsed() {
            return policyIssue == null;
        }

        /** 这份草稿现在提交会不会被发布期守卫拒绝。 */
        public boolean publishable() {
            return parsed() && guards.publishable();
        }
    }

    private RoutingDryRun() {
    }

    /**
     * 跑一次 dry-run。
     *
     * @param definitionJson 策略文本（与发布时要落库的那段<b>同一个构造入口</b>产出，
     *                       见 {@code CapRoutingPolicyDef#definitionJson()}）
     * @param tenantId       服务端认证上下文里的租户标识（<b>不得</b>取自请求体或探针）
     * @param today          回放用的 UTC 日（由调用方注入时钟，引擎不读系统时钟）
     * @param probesJson     探针数组原文（可空数组；无探针时只出守卫报告）
     * @return 结论（永不为 {@code null}；策略不合法时以 {@code policyIssue} 表达）
     */
    public static @NonNull Outcome run(@NonNull String definitionJson,
                                       @NonNull String tenantId,
                                       @NonNull LocalDate today,
                                       @NonNull String probesJson) {
        PolicySpec policy;
        try {
            policy = PolicyCodec.parseStandalone(definitionJson);
        } catch (RoutingConfigException e) {
            // 解析不过就没有词汇表，守卫与探针都无从跑起 —— 此处返回空报告，
            // 而不是「跳过解析继续试」：那会让面板对一份根本不合法的草稿给出命中结论。
            return new Outcome(new Issue(e.where(), e.getMessage()),
                    new PublishGuards.Report(List.of()), List.of(), today);
        }
        List<DryRunProbe> probes;
        try {
            probes = PolicyCodec.parseProbes(probesJson, MAX_PROBES);
        } catch (RoutingConfigException e) {
            // 探针列表整体不合法只影响试跑这一半，守卫报告照常给出 ——
            // 配置者要的「这份策略能不能发布」与「这条输入会走到哪」是两个问题。
            return new Outcome(null, PublishGuards.check(policy, tenantId, today),
                    List.of(new ProbeOutcome("(探针列表)", Map.of(), null,
                            new Issue(e.where(), e.getMessage()))), today);
        }
        List<ProbeOutcome> outcomes = new ArrayList<>();
        for (DryRunProbe probe : probes) {
            outcomes.add(runProbe(policy, probe, tenantId, today));
        }
        return new Outcome(null, PublishGuards.check(policy, tenantId, today),
                List.copyOf(outcomes), today);
    }

    /** 单条探针：装配输入 → 过引擎 → 连投影结果一起回显。 */
    private static @NonNull ProbeOutcome runProbe(@NonNull PolicySpec policy,
                                                  @NonNull DryRunProbe probe,
                                                  @NonNull String tenantId,
                                                  @NonNull LocalDate today) {
        RoutingInput input = probe.toInput(policy.inputSchema(), tenantId, today);
        RoutingDecision decision = RoutingEngine.decide(policy, input, probe.modelCandidate());
        return new ProbeOutcome(probe.label(), input.in(), decision, null);
    }
}
