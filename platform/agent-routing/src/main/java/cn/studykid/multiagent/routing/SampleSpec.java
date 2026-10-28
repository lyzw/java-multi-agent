package cn.studykid.multiagent.routing;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 发布期回放样本（10 号设计 §5 的 {@code sampleSet} 条目，守卫 V8/V9 的输入）。
 *
 * <p>⚠️ <b>样本走的是与运行期同一个引擎实现</b>，不是另一份 Java/TS 镜像：
 * 「样本回放通过」与「运行期命中」必须是同一句话，否则校验对象和上线对象不是一份东西
 * （§7 债 5 特意要求前端不自建引擎镜像，同一个理由）。
 *
 * <p>⚠️ <b>{@code ctx} 可固定</b>：{@code channel}、{@code run.attempt}、{@code now.utcDate}
 * 等上下文若不固定，同一条样本在不同日子会回放成不同结果，
 * V8 就退化成一个时灵时不时的检查。但 <b>{@code ctx.tenantId} 不接受样本声明</b> ——
 * 身份只来自服务端；让样本能自带租户，等于在测试装置里开一个身份注入的口子（与 G-6 同口径）。
 *
 * @param label        可读标签，失败信息里回显
 * @param input        路由输入（{@code in.*} 的来源；词汇表外的字段按 {@link InputSchema} 投影掉）
 * @param expectTarget 期望目标
 * @param via          期望的出口（默认 {@link Resolution#RULE_MATCH}）
 * @param ctx          期望固定的上下文（<b>不含</b>租户）
 */
public record SampleSpec(@NonNull String label,
                         @NonNull Map<String, Object> input,
                         @NonNull String expectTarget,
                         @NonNull Resolution via,
                         @NonNull Map<CtxName, Object> ctx) {

    /** V8 允许声明的出口码（其余码是失败态，不该由样本「期望」）。 */
    private static final Set<Resolution> EXPECTABLE = Set.of(
            Resolution.RULE_MATCH, Resolution.SEMANTIC_MATCH,
            Resolution.DEFAULT_TARGET, Resolution.NO_MATCH_REJECTED);

    public SampleSpec {
        // 允许样本里显式写 null 字段（判缺失的规则需要它），故不用 Map.copyOf。
        input = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(input));
        // ⚠️ 不能用 new EnumMap<>(ctx)：空 ctx 会抛「Specified map is empty」（无法推断键类型），
        //    而绝大多数样本本来就不固定 ctx，那样本连策略都存不进去。
        Map<CtxName, Object> copiedCtx = new EnumMap<>(CtxName.class);
        copiedCtx.putAll(ctx);
        ctx = java.util.Collections.unmodifiableMap(copiedCtx);
        if (label.isBlank() || expectTarget.isBlank()) {
            throw new IllegalArgumentException("样本的 label 与 expectTarget 不能为空");
        }
        if (!EXPECTABLE.contains(via)) {
            throw new IllegalArgumentException("样本不能期望出口码: " + via
                    + "（可期望的只有 " + EXPECTABLE + "）");
        }
        if (ctx.containsKey(CtxName.TENANT_ID)) {
            throw new IllegalArgumentException("样本不得声明 ctx.tenantId：租户只来自服务端");
        }
    }

    /**
     * 期望出口码的文本解析。
     *
     * @param wireName {@code via} 字段（{@code null} 或空 = 默认 {@link Resolution#RULE_MATCH}）
     * @return 稳定码
     * @throws IllegalArgumentException 词汇表之外，或指向一个失败态码
     */
    public static @NonNull Resolution viaOf(@Nullable String wireName) {
        if (wireName == null || wireName.isBlank()) {
            return Resolution.RULE_MATCH;
        }
        Resolution resolution;
        try {
            resolution = Resolution.valueOf(wireName.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("样本的 via 不是已知的稳定码: " + wireName, e);
        }
        if (!EXPECTABLE.contains(resolution)) {
            throw new IllegalArgumentException("样本的 via 只能是 " + EXPECTABLE + "，收到: " + wireName);
        }
        return resolution;
    }

    /**
     * 样本隐含的<b>模型候选</b>。
     *
     * <p>⚠️ 只有期望 {@link Resolution#SEMANTIC_MATCH} 时才有候选，且它就是
     * {@code expectTarget}：「期望语义引擎选中 X」等价于「模型提议 X 且引擎采纳」。
     * 另加一个 {@code candidate} 字段会造出「候选与期望目标不一致」这种
     * 无从解释的样本，而那正是 {@code on_conflict} 该管的事。
     */
    public @Nullable String modelCandidate() {
        return via == Resolution.SEMANTIC_MATCH ? expectTarget : null;
    }

    /**
     * 组装成引擎可用的规范化输入。
     *
     * <p>投影与 ctx 默认值都在 {@link RoutingInput#projected} 里 ——
     * dry-run 探针走的是<b>同一个</b>装配入口，
     * 否则「面板说会命中」与「发布期回放说会命中」就不是同一句话。
     *
     * @param schema   路由输入词汇表
     * @param tenantId 当前租户（服务端提供，样本无权覆盖）
     * @param utcDate  默认 UTC 日（样本未固定 {@code ctx.now.utcDate} 时使用）
     * @return 规范化输入
     */
    public @NonNull RoutingInput toInput(@NonNull InputSchema schema,
                                         @NonNull String tenantId,
                                         @NonNull LocalDate utcDate) {
        return RoutingInput.projected(schema, input, ctx, tenantId, utcDate);
    }
}
