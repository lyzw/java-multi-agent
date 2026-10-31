package cn.studykid.multiagent.api.run;

import java.math.BigDecimal;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.compliance.DataClassification;

/**
 * Run 创建请求。
 *
 * <p><b>⚠️ 刻意不包含 tenantId</b>：租户标识一律取自认证上下文，
 * <b>永不信赖客户端输入</b>（P0-D02 NFR-001 / 威胁模型 S-1）。
 * 请求体中出现 {@code tenantId}（或任何未声明字段）时，由
 * {@link #fromJson} 结合 {@link #KNOWN_FIELDS} 直接拒绝 ——
 * 这比「悄悄忽略」更好，后者会让人误以为参数生效了。
 *
 * <p>⚠️ <b>拒收靠的是 {@link #fromJson}，不是注解开关</b>：本项目的 JSON 栈是 Jackson 3
 * （Spring Boot 4.1.1），实测 {@code @JsonIgnoreProperties(ignoreUnknown = false)}
 * 在 {@code FAIL_ON_UNKNOWN_PROPERTIES} 关闭（Jackson 3 默认即关闭）时<b>不会</b>报错，
 * 只有带 {@code @JsonAnySetter} 的显式 Creator 能把「多出来的字段」真正看见并拒掉。
 *
 * @param idempotencyKey 幂等键
 * @param agentId        目标 Agent 标识
 * @param input          输入载荷（不含可识别学生信息）
 * @param requestedBy    请求发起方（可空，由认证上下文推导）
 * @param timeoutMs      <b>本次 Run 的时间预算</b>（毫秒，可空 = 不限时）
 * @param budgetAmount   <b>本次 Run 的预算额度</b>（可空 = 不额外限制）
 * @param dataClassification 数据分级声明（可空）；为空时由内容推导（见
 *                          {@link DataClassification#fromContent}）。声明仅用于<b>升级</b>
 *                          为 {@link DataClassification#REAL_STUDENT}，不得用于降级绕过——
 *                          含可识别信息的内容一律按真实数据处置。
 */
public record CreateRunRequest(
        @NonNull String idempotencyKey,
        @NonNull String agentId,
        @NonNull Map<String, Object> input,
        @Nullable String requestedBy,
        @Nullable Long timeoutMs,
        @Nullable BigDecimal budgetAmount,
        @Nullable DataClassification dataClassification
) {

    /**
     * 请求体<b>允许</b>出现的字段名 —— 未知字段拒收的<b>唯一权威清单</b>。
     *
     * <p><b>为何严格</b>：若宽松忽略，客户端传 {@code tenantId=别人的租户}
     * 会被静默丢弃，看似「安全」实则<b>掩盖了攻击尝试</b>。
     * 明确报错才能让越权尝试留下痕迹。
     *
     * <p>⚠️ 新增组件时必须同步本清单，否则新字段会被 {@link #fromJson} 自己拒收
     * （失败方向是「拒绝」而非「放行」，不会造成静默放行）。
     */
    public static final java.util.Set<String> KNOWN_FIELDS = java.util.Set.of(
            "idempotencyKey", "agentId", "input", "requestedBy", "timeoutMs", "budgetAmount",
            "dataClassification");

    /**
     * JSON 绑定入口：先拒收未知字段，再走紧凑构造器的取值校验。
     *
     * <p>{@code unknown} 由 Jackson 收集所有<b>不在参数列表里</b>的字段；
     * 非空即拒 —— 抛出的 {@link IllegalArgumentException} 会被 Jackson 包成
     * {@code ValueInstantiationException}，Spring 再转成
     * {@code HttpMessageNotReadableException}，最终由
     * {@code ApiExceptionHandler} 归一为 <b>400 + INVALID_REQUEST</b>。
     */
    @JsonCreator
    static @NonNull CreateRunRequest fromJson(
            @JsonProperty("idempotencyKey") String idempotencyKey,
            @JsonProperty("agentId") String agentId,
            @JsonProperty("input") Map<String, Object> input,
            @JsonProperty("requestedBy") String requestedBy,
            @JsonProperty("timeoutMs") Long timeoutMs,
            @JsonProperty("budgetAmount") BigDecimal budgetAmount,
            @JsonProperty("dataClassification") DataClassification dataClassification,
            @JsonAnySetter Map<String, Object> unknown) {
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(
                    "请求包含不被接受的字段: " + unknown.keySet() + "（允许的字段: "
                            + KNOWN_FIELDS + "）");
        }
        return new CreateRunRequest(idempotencyKey, agentId, input, requestedBy,
                timeoutMs, budgetAmount, dataClassification);
    }

    /**
     * 时间预算的硬上限（毫秒 = 24 小时）。
     *
     * <p>⚠️ <b>为什么必须有硬上限</b>：这个值决定「根 Run 最多跑多久」，
     * 进而决定整棵调用树的时间上界。若不设上限，客户端可以要一个
     * 「几乎无限」的 Run —— 那与「不声明预算」没有区别，
     * 却多了一个「看起来有约束」的假象。
     */
    public static final long MAX_TIMEOUT_MS = 24L * 60L * 60L * 1000L;

    /**
     * 预算额度的硬上限（元）。
     *
     * <p>⚠️ <b>为什么必须有硬上限</b>：这个值是「这一棵调用树最多花多少」，
     * 也是多层委派共享预算的判定基准。上限的话，客户端可以要一个
     * 「几乎无限」的额度 —— 那与不声明没有区别，却多了一个「看起来有约束」的假象。
     */
    public static final BigDecimal MAX_BUDGET_AMOUNT = new BigDecimal("1000000");

    public CreateRunRequest {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey 不能为空");
        }
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agentId 不能为空");
        }
        if (input == null || input.isEmpty()) {
            throw new IllegalArgumentException("input 不能为空");
        }
        if (idempotencyKey.length() > 128) {
            throw new IllegalArgumentException("idempotencyKey 长度超限");
        }
        // ⚠️ timeoutMs 必须为正：0 或负数意味着「一创建就已超时」，
        //    那是配置错误而非业务语义，放行只会产生一个立刻失败的 Run。
        if (timeoutMs != null && (timeoutMs <= 0 || timeoutMs > MAX_TIMEOUT_MS)) {
            throw new IllegalArgumentException(
                    "timeoutMs 必须为正数且不超过 " + MAX_TIMEOUT_MS + " 毫秒，收到：" + timeoutMs);
        }
        // ⚠️ 额度同理：0 或负数 = 「明确禁止花钱」，不是「未声明」，
        //    语义不同，不能混为一谈（放行会让「一分钱不许花」的意图失效）。
        if (budgetAmount != null
                && (budgetAmount.signum() <= 0 || budgetAmount.compareTo(MAX_BUDGET_AMOUNT) > 0)) {
            throw new IllegalArgumentException(
                    "budgetAmount 必须为正数且不超过 " + MAX_BUDGET_AMOUNT + "，收到：" + budgetAmount);
        }
        input = Map.copyOf(input);
    }

    /** 便捷构造：省略 requestedBy（由认证上下文推导）与所有资源上限。 */
    public CreateRunRequest(@NonNull String idempotencyKey,
                            @NonNull String agentId,
                            @NonNull Map<String, Object> input) {
        this(idempotencyKey, agentId, input, null, null, null, null);
    }

    /** 便捷构造：省略所有资源上限（不限时不限额）。 */
    public CreateRunRequest(@NonNull String idempotencyKey,
                            @NonNull String agentId,
                            @NonNull Map<String, Object> input,
                            @Nullable String requestedBy) {
        this(idempotencyKey, agentId, input, requestedBy, null, null, null);
    }

    /** 便捷构造：只声明时间预算。 */
    public CreateRunRequest(@NonNull String idempotencyKey,
                            @NonNull String agentId,
                            @NonNull Map<String, Object> input,
                            @Nullable String requestedBy,
                            @Nullable Long timeoutMs) {
        this(idempotencyKey, agentId, input, requestedBy, timeoutMs, null, null);
    }


    /**
     * 内容合规检查。
     *
     * <p>⚠️ 这是<b>纵深防御</b>：即便零保留闸门已通过，
     * 也不允许请求体携带明显的可识别学生信息。
     * 主防线仍是 {@code ZeroRetentionGate}，此处仅作早期拦截。
     *
     * @throws IllegalArgumentException 含可识别信息时
     */
    public void assertNoIdentifyingContent() {
        for (cn.studykid.multiagent.core.compliance.DataClassification.IdentifyingPattern pattern
                : cn.studykid.multiagent.core.compliance.DataClassification.IdentifyingPattern.values()) {
            for (String value : flatten(input)) {
                if (pattern.detect(value)) {
                    throw new IllegalArgumentException(
                            "请求包含可识别学生信息（命中模式: " + pattern.label() + "）");
                }
            }
        }
    }

    /** 取出所有字符串值（递归）。 */
    private static java.util.List<String> flatten(Map<String, Object> map) {
        java.util.List<String> out = new java.util.ArrayList<>();
        map.forEach((k, v) -> collect(v, out, 0, new java.util.concurrent.atomic.AtomicInteger()));
        return out;
    }

    /** 最大递归深度（防 StackOverflow）。 */
    private static final int MAX_FLATTEN_DEPTH = 50;
    /** 最大收集节点数（防 OOM）。 */
    private static final int MAX_FLATTEN_VALUES = 10000;

    @SuppressWarnings("unchecked")
    private static void collect(Object value, java.util.List<String> out,
                                int depth, java.util.concurrent.atomic.AtomicInteger count) {
        if (depth > MAX_FLATTEN_DEPTH) {
            throw new IllegalArgumentException("输入结构嵌套过深（超过 " + MAX_FLATTEN_DEPTH + " 层），请扁平化后重试");
        }
        if (value instanceof Map<?, ?> m) {
            // ⚠️ 同时检查 key 和 value：key 本身可能是可识别信息（如手机号）
            m.forEach((k, v) -> {
                if (k != null) {
                    if (count.incrementAndGet() > MAX_FLATTEN_VALUES) {
                        throw new IllegalArgumentException("输入结构包含过多节点（超过 " + MAX_FLATTEN_VALUES + " 个），请精简后重试");
                    }
                    out.add(String.valueOf(k));
                }
                collect(v, out, depth + 1, count);
            });
        } else if (value instanceof Iterable<?> it) {
            it.forEach(v -> collect(v, out, depth + 1, count));
        } else if (value != null) {
            if (count.incrementAndGet() > MAX_FLATTEN_VALUES) {
                throw new IllegalArgumentException("输入结构包含过多节点（超过 " + MAX_FLATTEN_VALUES + " 个），请精简后重试");
            }
            out.add(String.valueOf(value));
        }
    }
}