package cn.studykid.multiagent.routing;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 一次路由决策的<b>规范化输入</b>（10 号设计 §6 阶段①）。
 *
 * <p>两半，来源严格分离：
 * <ul>
 *   <li>{@code in} —— 从委派 {@code input} 里<b>按 {@code input_schema} 取的字段</b>。
 *       ⚠️ <b>schema 之外的字段不进求值视野</b>：否则「规则可见但 Schema 未声明」
 *       的输入会出现（G-6 点名的正是这个），取值边界就失去唯一真相；</li>
 *   <li>{@code ctx} —— 服务端注入，模型无法伪造（见 {@link CtxName}）。</li>
 * </ul>
 *
 * <p>⚠️ <b>不可变 + 规范化</b：本对象是决策快照里 {@code canonicalInput} 的来源，
 * 也是 {@code canonicalInputSha256} 的哈希输入。可变或顺序不定的话，
 * 「同一输入重放两次得到同一目标」根本无从谈起。
 *
 * <p>⚠️ <b>时间只到日</b>：{@link LocalDate} 而不是 {@code Instant} ——
 * 这是 {@link CtxName#NOW_UTC_DATE} 的口径落地点，不是顺手写成这样。
 */
public record RoutingInput(@NonNull Map<String, Object> in,
                           @NonNull Map<CtxName, Object> ctx) {

    /** 取值缺失的哨兵（与「值为 JSON null」在 {@link Predicate.Null} 上同判，其它算子一律 ERROR）。 */
    public static final @NonNull Object ABSENT = new Object() {
        @Override
        public String toString() {
            return "<absent>";
        }
    };

    public RoutingInput {
        in = deepImmutable(in);
        ctx = Map.copyOf(ctx);
    }

    /**
     * 构造输入。
     *
     * @param schema   路由输入词汇表；<b>只有它声明过的字段</b>会进入求值视野
     * @param rawInput 模型产出的委派 {@code input}（原样，可能含多余字段）
     * @param tenantId 认证上下文里的租户标识（<b>不得</b>取自请求体）
     * @param channel  接入渠道（可空 = 未提供）
     * @param locale   请求语言（可空 = 未提供）
     * @param attempt  本次 Run 的尝试序号
     * @param depth    当前委派深度（根 Run = 0）
     * @param utcDate  UTC 自然日（由调用方注入时钟算出，<b>引擎不读系统时钟</b>）
     * @return 规范化输入
     */
    public static @NonNull RoutingInput of(@NonNull InputSchema schema,
                                           @NonNull Map<String, Object> rawInput,
                                           @NonNull String tenantId,
                                           @Nullable String channel,
                                           @Nullable String locale,
                                           int attempt,
                                           int depth,
                                           @NonNull LocalDate utcDate) {
        Map<CtxName, Object> ctx = new LinkedHashMap<>();
        ctx.put(CtxName.TENANT_ID, tenantId);
        putIfPresent(ctx, CtxName.CHANNEL, channel);
        putIfPresent(ctx, CtxName.LOCALE, locale);
        ctx.put(CtxName.RUN_ATTEMPT, attempt);
        ctx.put(CtxName.RUN_DEPTH, depth);
        ctx.put(CtxName.NOW_UTC_DATE, utcDate.toString());
        return new RoutingInput(projectOnto(schema, rawInput), ctx);
    }

    /**
     * 按词汇表投影输入，并合并<b>声明式上下文</b>（发布样本与 dry-run 探针共用这一处）。
     *
     * <p>⚠️ <b>为什么是共用而不是各写一份</b>：「样本回放看到的输入」与「dry-run 面板看到的输入」
     * 若在某些 ctx 默认值上不一致，面板给出的结论就不是运行期的结论
     * —— 与 §7 债 5 禁止前端自建引擎镜像是同一条理由。
     *
     * <p>缺省上下文为 {@code attempt=1 / depth=0}：依赖重试次数或深度的规则
     * 必须在样本/探针里显式固定它，而不是让结果取决于调用装配顺序。
     *
     * @param schema       路由输入词汇表；只有它声明过的路径进入求值视野
     * @param input        {@code in.*} 的原始来源（多余字段投影掉）
     * @param overrides    显式固定的上下文项（<b>不含</b>租户，见 {@link PolicyCodec#parseCtx}）
     * @param tenantId     服务端认证上下文里的租户标识
     * @param utcDate      默认 UTC 日（未被 {@code overrides} 覆盖时使用）
     * @return 规范化输入
     */
    public static @NonNull RoutingInput projected(@NonNull InputSchema schema,
                                                  @NonNull Map<String, Object> input,
                                                  @NonNull Map<CtxName, Object> overrides,
                                                  @NonNull String tenantId,
                                                  @NonNull LocalDate utcDate) {
        Map<CtxName, Object> merged = new LinkedHashMap<>();
        merged.put(CtxName.TENANT_ID, tenantId);
        merged.put(CtxName.RUN_ATTEMPT, 1);
        merged.put(CtxName.RUN_DEPTH, 0);
        merged.put(CtxName.NOW_UTC_DATE, utcDate.toString());
        overrides.forEach((name, value) -> merged.put(name, value));
        return new RoutingInput(projectOnto(schema, input), merged);
    }

    /** 按词汇表投影：schema 之外的字段不进求值视野（与运行期同一条规则）。 */
    private static @NonNull Map<String, Object> projectOnto(@NonNull InputSchema schema,
                                                            @NonNull Map<String, Object> raw) {
        Map<String, Object> projected = new LinkedHashMap<>();
        for (String path : schema.paths()) {
            Object value = readPath(raw, path);
            if (value != ABSENT) {
                projected.put(path, value);
            }
        }
        return Map.copyOf(projected);
    }

    /**
     * 解析一个取值引用。
     *
     * @param value 引用
     * @return 标量 / 标量列表 / 字面量；路径不存在时为 {@link #ABSENT}
     */
    public @NonNull Object resolve(Predicate.@NonNull Value value) {
        if (value instanceof Predicate.LiteralValue literal) {
            return literal.value();
        }
        if (value instanceof Predicate.InputPath path) {
            return in.getOrDefault(path.path(), ABSENT);
        }
        if (value instanceof Predicate.CtxValue ctxValue) {
            return ctx.getOrDefault(ctxValue.name(), ABSENT);
        }
        // ⚠️ 不可达：Value 是 sealed 的，新增取法会在**编译期**撞车而不是在这里漏判。
        throw new IllegalStateException("未知的取值来源: " + value);
    }

    /**
     * 解析 {@code in} 路径的<b>原始</b>值（不做 schema 投影）。
     *
     * <p>守卫层 V3 用它判断「策略引用的路径在词汇表里有没有、类型对不对」，
     * 那时还没有运行期输入，故需要一个独立的读路径工具。
     */
    public static @NonNull Object readPath(@NonNull Map<String, Object> root,
                                           @NonNull String dottedPath) {
        Object current = root;
        for (String segment : dottedPath.split("\\.")) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(segment)) {
                return ABSENT;
            }
            current = map.get(segment);
        }
        return current == null ? ABSENT : current;
    }

    private static void putIfPresent(@NonNull Map<CtxName, Object> target,
                                     @NonNull CtxName name,
                                     @Nullable Object value) {
        if (value != null) {
            target.put(name, value);
        }
    }

    /**
     * 递归收深为<b>不可变</b> JDK 结构（决策快照要按固定文本哈希，嵌套 Map 可变就不稳定）。
     *
     * <p>⚠️ 有意用 {@link java.util.Collections#unmodifiableMap} 而不是 {@code Map.copyOf}：
     * 后者禁止 null 值，而「字段显式传 JSON null」是合法输入，
     * 且 {@link Predicate.Null} 必须能把它与「没传字段」同判 ——
     * 用 copyOf 会让这类输入在构造期抛 NPE，把业务输入变成崩溃。
     */
    private static @NonNull Map<String, Object> deepImmutable(@NonNull Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, deepImmutableValue(value)));
        return java.util.Collections.unmodifiableMap(copy);
    }

    private static @Nullable Object deepImmutableValue(@Nullable Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> copy.put(String.valueOf(k), deepImmutableValue(v)));
            return Map.copyOf(copy);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(RoutingInput::deepImmutableValue).toList();
        }
        return value;
    }
}
