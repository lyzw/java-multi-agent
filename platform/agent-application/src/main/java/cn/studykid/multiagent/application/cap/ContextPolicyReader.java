package cn.studykid.multiagent.application.cap;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

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
 * <p>⚠️ <b>发布期、执行期与快照派生必须共用本类</b>：{@link ConfigValidationGate} 调
 * {@link #toPlainMap(JsonNode)} 拿键值后交给校验器；运行期调 {@link #read(String)}
 * 拿 {@link ContextPolicy}；发布归档调 {@link #snapshotJson(String)} 写
 * {@code execution_snapshot.context_policy}。三者若各写一套 Jackson 归一化
 * （例如一处把数字转 {@code String}、另一处转 {@code Number}），就会出现
 * 「发布时合法、跑起来被拒」或反过来「发布被拒、旧快照却能跑」的静默漂移。
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
        JsonNode root = rootObject(generationParamsJson);
        if (root == null) {
            return null;
        }
        JsonNode context = root.get("context");
        return context == null || context.isNull() ? null : context;
    }

    /**
     * 发布期规范快照：把 {@code generation_params.context} 归一化为稳定 JSON 文本。
     *
     * <p>⚠️ <b>只保留显式声明的键</b>（键名排序、标量类型由 Jackson 归一化），
     * 未声明 / 空对象一律 {@code {}}，表示「回落运行期默认」，<b>不是关闭</b>。
     * 绝不把当前默认值整包写进快照 —— 否则日后调整默认档会改变历史 Run 的解释，
     * 违背 [D4]「在途 Run 沿用原快照」。
     *
     * <p>⚠️ 非法策略与 {@link #read(String)} 同判据 fail-closed：
     * 发布期已拒的配置，绝不允许在快照派生处静默变成 {@code {}}。
     *
     * @param generationParamsJson {@code generationParams} 的 JSON 原文（可空）
     * @return 规范化 {@code context} 对象；{@code {}} = 未声明 / 回落默认
     */
    public static @NonNull String snapshotJson(@Nullable String generationParamsJson) {
        JsonNode node = contextNode(generationParamsJson);
        if (node == null) {
            return "{}";
        }
        if (!node.isObject()) {
            // 与 read() 逐字同判据：非对象不能静默回落，否则发布门禁形同虚设。
            throw new ContextPolicyRejectedException(
                    ContextPolicyRejectedException.Reason.CONTEXT_POLICY_INVALID,
                    "generationParams.context 必须是 JSON 对象");
        }
        Map<String, Object> plain = toPlainMap(node);
        // 与 read() 共用解析器：校验失败直接抛，绝不静默降级。
        ContextPolicyParser.parse(plain);
        return MAPPER.writeValueAsString(new TreeMap<>(plain));
    }

    /**
     * 从完整 Agent 配置 / 旧版自由 definition 中提取 {@code generationParams} 后派生快照。
     *
     * <p>旧直发通道的 {@code definition} 是自由 schema，可能没有 {@code generationParams}；
     * 缺失 / 根非对象 / 不可解析一律 {@code {}}，与 DDL 默认值和
     * {@link #snapshotJson(String)} 的「未声明」语义完全一致。
     *
     * <p>⚠️ 本方法只是<b>取容器键</b>，策略校验与序列化仍全部委托
     * {@link #snapshotJson(String)}；禁止调用方自己再写一套 Jackson 归一化。
     *
     * @param configJson 完整配置 JSON 文本（可空）
     * @return 规范化 {@code context} 对象；{@code {}} = 未声明 / 回落默认
     */
    public static @NonNull String snapshotFromAgentConfig(@Nullable String configJson) {
        JsonNode root = rootObject(configJson);
        if (root == null) {
            return "{}";
        }
        JsonNode generationParams = root.get("generationParams");
        if (generationParams == null || generationParams.isNull()) {
            return "{}";
        }
        // 容忍「generationParams 是承载 JSON 文本的字符串」这一历史形态；
        // 非对象 / 不可解析值由 snapshotJson 按未声明处理（根非对象 → {}）。
        return snapshotJson(generationParams.isTextual()
                ? generationParams.asText()
                : generationParams.toString());
    }

    /** 解析为 JSON 对象根；非对象 / 不可解析一律 null（沿用既有「回落默认」语义）。 */
    private static @Nullable JsonNode rootObject(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            return root != null && root.isObject() ? root : null;
        } catch (RuntimeException e) {
            return null;
        }
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
