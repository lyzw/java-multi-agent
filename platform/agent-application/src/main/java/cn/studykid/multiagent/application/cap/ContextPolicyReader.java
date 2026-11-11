package cn.studykid.multiagent.application.cap;

import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import cn.studykid.multiagent.context.ContextPolicy;
import cn.studykid.multiagent.context.ContextPolicyParser;
import cn.studykid.multiagent.context.ContextPolicyRejectedException;

/**
 * {@code generation_params} → 压缩策略的<b>唯一</b> JSON 读取实现（ADR-013 / §3.4）。
 *
 * <p>⚠️ <b>发布期与执行期必须共用本类</b>：{@link ConfigValidationGate} 调
 * {@link #toPlainMap(JsonNode)} 拿键值后交给校验器；运行期调 {@link #read(String)}
 * 拿 {@link ContextPolicy}。两者若各写一套 Jackson 归一化（例如一处把数字转
 * {@code String}、另一处转 {@code Number}），就会出现「发布时合法、跑起来被拒」
 * 或反过来「发布被拒、旧快照却能跑」的静默漂移。
 *
 * <p>⚠️ <b>未声明 = 全局默认，不是关闭</b>；{@code generation_params} 整体非法
 * JSON 时按既有语义回落默认（S1 不扩大影响面），由运行期沿用旧行为。
 */
public final class ContextPolicyReader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ContextPolicyReader() {
    }

    /**
     * 取出 {@code generation_params.context} 节点。
     *
     * @return {@code null} = 未声明 / 根不是对象 / JSON 不可解析（调用方按「回落默认」处理）
     */
    public static @Nullable JsonNode contextNode(@Nullable String generationParamsJson) {
        if (generationParamsJson == null || generationParamsJson.isBlank()) {
            return null;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(generationParamsJson);
        } catch (RuntimeException e) {
            return null;
        }
        if (root == null || !root.isObject()) {
            return null;
        }
        JsonNode context = root.get("context");
        return context == null || context.isNull() ? null : context;
    }

    /** JsonNode → 基本类型 Map；未知结构保留文本，交由校验器判非法（fail-closed）。 */
    public static @NonNull Map<String, Object> toPlainMap(@NonNull JsonNode node) {
        Map<String, Object> plain = new LinkedHashMap<>();
        node.properties().forEach(entry ->
                plain.put(entry.getKey(), toPlain(entry.getValue())));
        return plain;
    }

    /** 运行期读取：未声明返回默认策略，非法策略由解析器 fail-closed 拒绝。 */
    public static @NonNull ContextPolicy read(@Nullable String generationParamsJson) {
        JsonNode node = contextNode(generationParamsJson);
        if (node == null) {
            return ContextPolicy.defaults();
        }
        if (!node.isObject()) {
            // ⚠️ 与发布期门禁同判据：若这里静默回落默认，就会出现
            //    「发布被拒的快照在旧 Run 里悄悄按默认策略跑」的漂移。
            throw new ContextPolicyRejectedException(
                    ContextPolicyRejectedException.Reason.CONTEXT_POLICY_INVALID,
                    "generationParams.context 必须是 JSON 对象");
        }
        return ContextPolicyParser.parse(toPlainMap(node));
    }

    private static @Nullable Object toPlain(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isTextual()) {
            return node.asText();
        }
        return node.toString();
    }
}
