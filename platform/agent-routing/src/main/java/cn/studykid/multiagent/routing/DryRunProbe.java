package cn.studykid.multiagent.routing;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * dry-run 探针（管理台「按输入试跑」的一次试验，10 号设计 §7 债 5 的 A7-1b）。
 *
 * <p>⚠️ <b>与 {@link SampleSpec} 是两件事，不要合并</b>：
 * 样本是<b>发布物的一部分</b>，带着「期望出口 + 期望目标」，发布期由守卫 V8 逐条比对；
 * 探针是<b>编辑器里的临时试跑</b>，没有期望值（它的用途正是「我还没想好该期望什么，
 * 先看看会走到哪」），也<b>不进策略文本</b>。
 * 把探针塞进 {@code sampleSet} 等于用一次试跑污染发布物 —— 而发布物里的样本
 * 是要跟着策略一起被重放验证的（{@code RoutingReplayIT} 判的就是它）。
 *
 * <p>⚠️ <b>探针可以显式给 {@code modelCandidate}</b>，而样本不能用：
 * 样本的候选由 {@code via=SEMANTIC_MATCH} 隐含（多一个字段就会多一种
 * 「候选与期望目标不一致」的无从解释形态）；探针没有期望值，
 * 于是需要「模型提议 X，这份策略会不会收它」这种问法 ——
 * C38-2 的策略级收窄（{@code TARGET_NOT_ALLOWED}）只有靠它才能在管理台里被看见。
 *
 * <p>⚠️ {@code ctx} 里<b>没有租户</b>：与样本同口径，租户只来自服务端
 * （词汇表与拒绝理由见 {@link PolicyCodec#parseCtx}）。
 *
 * @param label         可读标签（回显用；缺省时由解析器按序号补）
 * @param input         路由输入（{@code in.*} 的来源；词汇表外的字段按 {@link InputSchema} 投影掉）
 * @param modelCandidate 模拟的模型候选（{@code null} = 本次试跑没有候选，
 *                       {@code RULES_ONLY} 下本就不消费候选）
 * @param ctx           要固定的上下文项（<b>不含</b>租户）
 */
public record DryRunProbe(@NonNull String label,
                          @NonNull Map<String, Object> input,
                          @Nullable String modelCandidate,
                          @NonNull Map<CtxName, Object> ctx) {

    public DryRunProbe {
        // 允许探针里显式写 null 字段（判缺失的规则要靠它），故不用 Map.copyOf。
        input = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(input));
        Map<CtxName, Object> copiedCtx = new EnumMap<>(CtxName.class);
        copiedCtx.putAll(ctx);
        ctx = java.util.Collections.unmodifiableMap(copiedCtx);
        if (label.isBlank()) {
            throw new IllegalArgumentException("探针的 label 不能为空");
        }
    }

    /**
     * 组装成引擎可用的规范化输入。
     *
     * <p>与 {@link SampleSpec#toInput} 走的是<b>同一个</b>装配入口
     * （{@link RoutingInput#projected}）：投影规则与 ctx 默认值若在这里另写一份，
     * 「面板给出的结论」与「发布期回放给出的结论」就会分叉 —— 而分叉的症状
     * 正是本仓反复出现的那一类：看起来一致，实际不一致。
     *
     * @param schema   路由输入词汇表
     * @param tenantId 服务端认证上下文里的租户标识
     * @param utcDate  默认 UTC 日（探针未固定 {@code ctx.now.utcDate} 时使用）
     * @return 规范化输入
     */
    public @NonNull RoutingInput toInput(@NonNull InputSchema schema,
                                         @NonNull String tenantId,
                                         @NonNull LocalDate utcDate) {
        return RoutingInput.projected(schema, input, ctx, tenantId, utcDate);
    }
}
