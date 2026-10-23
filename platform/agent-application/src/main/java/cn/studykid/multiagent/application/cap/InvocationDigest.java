package cn.studykid.multiagent.application.cap;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import org.jspecify.annotations.NonNull;

/**
 * 调用参数摘要（P2 · §5.3 步骤 8 / §5.3 步骤 13a）。
 *
 * <p>用途有二，二者都要求<b>同一份参数永远得到同一个摘要</b>：
 * <ol>
 *   <li><b>幂等冲突判定</b>（§5.3 步骤 8）：同键不同参数 → {@code IDEMPOTENCY_CONFLICT}；</li>
 *   <li><b>执行意图的参数指纹</b>（§5.3 步骤 13a / {@code ExecutionIntent.argsFingerprint}）：
 *       崩溃恢复时据此判断「执行的到底是不是这批参数」。</li>
 * </ol>
 *
 * <p>⚠️ <b>不能用 {@code String.valueOf(map)} 当摘要</b>：
 * 那是 {@code Map.toString()}，键序取决于 Map 实现与哈希分布，
 * 同一批参数在不同进程/不同 Map 实现下可能产出不同文本 ——
 * 于是「同键同参数」会被误判成「同键不同参数」，把正常重试打成冲突。
 *
 * <p>⚠️ <b>缺失字段与显式 {@code null} 必须区分</b>（§5.3.2）：
 * {@code {"a": null}} 与 {@code {}} 是两份不同的参数集，
 * 前者表示「调用方显式要求 a 为空」，后者表示「调用方没提 a」。
 *
 * <p>⚠️ <b>本实现是 P2 的过渡口径</b>：§5.3.2 规定的 {@code args_norm_v1}
 * 还包含「Schema default 先展开」「数字十进制规范形式」等规则，
 * 完整实现属 <b>P3</b>（审批双哈希依赖它）。本类已实现其中可独立成立的部分：
 * 键序固定、缺失与 null 区分、数组保持调用序、UTF-8 字节序、SHA-256 小写 hex。
 */
public final class InvocationDigest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private InvocationDigest() {
    }

    /**
     * 计算参数摘要。
     *
     * @param arguments 调用参数（可为 {@code null}，等价于空参数集）
     * @return 小写 hex 的 SHA-256 摘要
     */
    public static @NonNull String of(Map<String, Object> arguments) {
        return sha256Hex(canonicalJson(arguments));
    }

    /**
     * 生成参数的<b>规范化 JSON</b>（人类可读，便于审计与排障）。
     *
     * @param arguments 调用参数
     * @return 规范化 JSON 文本
     */
    public static @NonNull String canonicalJson(Map<String, Object> arguments) {
        ObjectNode root = MAPPER.createObjectNode();
        Map<String, Object> sorted = arguments == null
                ? Map.of()
                : new TreeMap<>(arguments);
        for (Map.Entry<String, Object> entry : sorted.entrySet()) {
            // ⚠️ 显式 null 必须落成 JSON null，与「键缺失」区分（§5.3.2）
            root.set(entry.getKey(), toNode(entry.getValue()));
        }
        return MAPPER.writeValueAsString(root);
    }

    private static @NonNull JsonNode toNode(Object value) {
        if (value == null) {
            return MAPPER.nullNode();
        }
        if (value instanceof Map<?, ?> map) {
            ObjectNode node = MAPPER.createObjectNode();
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((k, v) -> sorted.put(String.valueOf(k), v));
            sorted.forEach((k, v) -> node.set(k, toNode(v)));
            return node;
        }
        if (value instanceof Iterable<?> iterable) {
            ArrayNode array = MAPPER.createArrayNode();
            List<Object> items = new ArrayList<>();
            iterable.forEach(items::add);
            // ⚠️ 数组保持调用序（§5.3.2：默认保持调用序）
            items.forEach(item -> array.add(toNode(item)));
            return array;
        }
        if (value instanceof Number number) {
            return numberNode(number);
        }
        if (value instanceof Boolean bool) {
            return MAPPER.getNodeFactory().booleanNode(bool);
        }
        return MAPPER.getNodeFactory().textNode(String.valueOf(value));
    }

    private static @NonNull JsonNode numberNode(Number number) {
        // ⚠️ Jackson 3 的 numberNode 没有 Number 重载，必须逐类型分派。
        //    未知子类退回 textNode(number.toString())：
        //    它是<b>无损且确定</b>的，而 doubleValue() 会丢精度 ——
        //    两个不同的大数可能塌缩成同一个 double，摘要就失去区分能力。
        if (number instanceof Integer value) {
            return MAPPER.getNodeFactory().numberNode(value);
        }
        if (number instanceof Long value) {
            return MAPPER.getNodeFactory().numberNode(value);
        }
        if (number instanceof Double value) {
            return MAPPER.getNodeFactory().numberNode(value);
        }
        if (number instanceof Float value) {
            return MAPPER.getNodeFactory().numberNode(value);
        }
        if (number instanceof Short value) {
            return MAPPER.getNodeFactory().numberNode(value);
        }
        if (number instanceof Byte value) {
            return MAPPER.getNodeFactory().numberNode(value);
        }
        if (number instanceof java.math.BigDecimal value) {
            return MAPPER.getNodeFactory().numberNode(value);
        }
        if (number instanceof java.math.BigInteger value) {
            return MAPPER.getNodeFactory().numberNode(value);
        }
        return MAPPER.getNodeFactory().textNode(number.toString());
    }

    private static @NonNull String sha256Hex(@NonNull String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // ⚠️ SHA-256 是 JDK 强制支持的算法；返回固定值会让所有摘要相同，
            //    「摘要相同即参数相同」的判定将永远成立 —— 那比抛异常更危险。
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
