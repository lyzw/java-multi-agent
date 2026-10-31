package cn.studykid.multiagent.application.config;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.config.ModelPricing;

/**
 * 模型单价表（P0-3b · 成本入账）。
 *
 * <p><b>为什么必须存在</b>：{@code usage_record.cost_amount} 此前记的是
 * 「工具调用次数 × ¥0.50」的固定代理值，而 token 两列无任何写入方 ——
 * 于是「这个 Run 花了多少钱」在账上<b>不存在</b>，
 * 成本类指标（COST-1/2/3）全部失真。
 * 真实成本 = token 数 × 单价，<b>单价是必须冻结的配置</b>：
 * 没有单价表就没有真实成本，只有代理值。
 *
 * <p><b>匹配口径（按顺序）</b>：
 * <ol>
 *   <li><b>精确匹配</b>：{@code modelId} 命中某个键；</li>
 *   <li><b>最长前缀匹配</b>：{@code gpt-4o-2024-08-06} 命中 {@code gpt-4o}。
 *       模型标识常带日期后缀，没有前缀匹配就会让「配了价却用不上」；</li>
 *   <li><b>默认价</b>：{@link #DEFAULT_KEY} 指定的兜底条目（可缺省）。</li>
 * </ol>
 *
 * <p><b>⚠️ 查不到单价时返回空，而不是「按 0 元计费」</b>：
 * 调用方据此区分「这次调用确实免费」（上游不计费）与
 * 「我们不知道这次调用多少钱」（配置缺失）。
 * 前者可以放行，后者必须<b>显式可见</b>——
 * 否则「漏配单价」会静默变成「成本为 0」，账实从此对不上。
 *
 * <p><b>本类不可变</b>：装配期构造，运行期只读。
 * 可变静态配置会让「未装配」与「装配为空表」无法区分。
 */
public final class ModelPricingPolicy {

    /**
     * 默认价条目的键名。
     *
     * <p>⚠️ <b>默认价是兜底，不是默认行为</b>：它只在运维<b>明确配置</b>后生效，
     * 缺省即「查不到单价」。
     */
    public static final String DEFAULT_KEY = "default";

    /** 模型标识 → 单价规则。 */
    private final Map<String, ModelPricing> byModel;

    /**
     * 构造单价表。
     *
     * @param byModel 模型标识 → 单价规则；{@code null} 视为空表
     */
    public ModelPricingPolicy(@Nullable Map<String, ModelPricing> byModel) {
        this.byModel = byModel == null
                ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(byModel));
    }

    /**
     * 构造单价表（从原始配置值解析）。
     *
     * @param raw 模型标识 → {@code {input-per-1k-tokens, output-per-1k-tokens}}
     * @throws IllegalArgumentException 单价非法（非数值或为负）—— fail-closed
     */
    public static @NonNull ModelPricingPolicy of(
            @Nullable Map<String, Map<String, Object>> raw) {
        if (raw == null || raw.isEmpty()) {
            return new ModelPricingPolicy(Map.of());
        }
        Map<String, ModelPricing> parsed = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : raw.entrySet()) {
            String modelId = entry.getKey();
            if (modelId == null || modelId.isBlank()) {
                throw new IllegalArgumentException("模型单价表的键不能为空");
            }
            Map<String, Object> values = entry.getValue();
            if (values == null) {
                throw new IllegalArgumentException(
                        "模型 " + modelId + " 的单价配置为空");
            }
            parsed.put(modelId.trim(), new ModelPricing(
                    price(values, "input-per-1k-tokens", modelId),
                    price(values, "output-per-1k-tokens", modelId)));
        }
        return new ModelPricingPolicy(parsed);
    }

    private static BigDecimal price(Map<String, Object> values, String key, String modelId) {
        Object raw = values.get(key);
        if (raw == null) {
            throw new IllegalArgumentException(
                    "模型 " + modelId + " 缺少单价项 " + key);
        }
        BigDecimal value;
        try {
            value = raw instanceof BigDecimal b
                    ? b
                    : new BigDecimal(raw.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "模型 " + modelId + " 的 " + key + " 非数值：" + raw, e);
        }
        if (value.signum() < 0) {
            throw new IllegalArgumentException(
                    "模型 " + modelId + " 的 " + key + " 不能为负：" + value);
        }
        return value;
    }

    /**
     * 查模型单价。
     *
     * @param modelId 模型标识
     * @return 单价规则；查不到时为空（<b>不得按 0 元放行</b>）
     */
    public @NonNull Optional<ModelPricing> pricingOf(@Nullable String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return Optional.empty();
        }
        String id = modelId.trim();
        ModelPricing exact = byModel.get(id);
        if (exact != null) {
            return Optional.of(exact);
        }
        // ⚠️ 最长前缀匹配：模型标识常带日期/版本后缀
        //    （gpt-4o-2024-08-06），只做精确匹配会让「配了价却用不上」。
        String bestKey = null;
        for (String key : byModel.keySet()) {
            if (DEFAULT_KEY.equals(key)) {
                continue;
            }
            if (id.startsWith(key)
                    && (bestKey == null || key.length() > bestKey.length())) {
                bestKey = key;
            }
        }
        if (bestKey != null) {
            return Optional.of(byModel.get(bestKey));
        }
        ModelPricing fallback = byModel.get(DEFAULT_KEY);
        return fallback == null ? Optional.empty() : Optional.of(fallback);
    }

    /**
     * @return 已配置单价的模型标识集合（供启动自检报告「哪些模型还没配价」）
     */
    public @NonNull Set<String> configuredModels() {
        Set<String> out = new TreeSet<>(byModel.keySet());
        out.remove(DEFAULT_KEY);
        return out;
    }

    /**
     * @return 单价表是否为空（空表 = 一个模型都没配价）
     */
    public boolean isEmpty() {
        return byModel.isEmpty();
    }
}
