package cn.studykid.multiagent.application.cap;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.cap.ConfigObjectVersion;

/**
 * 发布头 {@code object_versions} 的编解码与聚合指纹（§6.1 / §6.4 · W-PERM-P5）。
 *
 * <p>⚠️ <b>格式固定为 {@code [{"type":"...","id":"...","version":N}]}</b>：
 * §6.2 规定对象定义之间只存逻辑 id，有效版本统一由当前发布头解析，
 * 因此发布头必须记录 id→<b>精确版本</b> 的映射。
 *
 * <p>⚠️ <b>聚合指纹必须与序列化形态同源</b>：
 * 指纹 = SHA-256(规范化 JSON)。若指纹算在另一份表示上，
 * 「存下来的集合」与「算出来的指纹」就会不一致 ——
 * 那正是 P1 闭包指纹踩过的「占位指纹」坑的同构问题。
 * 故本类只提供<b>一个</b>规范化 JSON 出口，指纹与存储都用它。
 *
 * <p>⚠️ <b>规范化 = 按 (type, id) 排序</b>：
 * 发布集合的遍历顺序不应影响指纹 ——
 * 否则同一份配置换个插入顺序就会得到不同指纹，在途 Run 会无谓失效。
 */
public final class ConfigObjectVersionCodec {

    /** 聚合指纹算法版本前缀。 */
    public static final String AGGREGATE_PREFIX = "cfg_agg_v1:";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ConfigObjectVersionCodec() {
    }

    /**
     * 序列化为发布头 {@code object_versions} 的 JSON 文本。
     *
     * @param refs 对象版本引用
     * @return JSON 数组文本
     */
    @NonNull
    public static String toJson(@NonNull List<ConfigObjectVersion> refs) {
        ArrayNode array = MAPPER.createArrayNode();
        for (ConfigObjectVersion ref : canonicalOrder(refs)) {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("type", ref.type());
            node.put("id", ref.id());
            node.put("version", ref.version());
            array.add(node);
        }
        return array.toString();
    }

    /**
     * 解析发布头 {@code object_versions} 的 JSON 文本。
     *
     * <p>⚠️ <b>解析失败返回空列表而非抛异常</b>：
     * 上层（指针读）据此「解析不出 → fail-closed 拒绝」，
     * 而不是把异常沿调用栈上抛、在没有统一封装的调用点上被误当成系统错误
     * 而跳过拒绝逻辑。
     *
     * @param json JSON 文本
     * @return 解析结果；格式非法时为 {@link List#of()}
     */
    @NonNull
    public static List<ConfigObjectVersion> parse(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            if (!root.isArray()) {
                return List.of();
            }
            List<ConfigObjectVersion> refs = new ArrayList<>();
            for (JsonNode node : root) {
                JsonNode typeNode = node.get("type");
                JsonNode idNode = node.get("id");
                JsonNode versionNode = node.get("version");
                if (typeNode == null || idNode == null || versionNode == null) {
                    return List.of();
                }
                int version = versionNode.numberValue().intValue();
                refs.add(new ConfigObjectVersion(typeNode.asString(), idNode.asString(), version));
            }
            return List.copyOf(refs);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /**
     * 计算聚合配置指纹。
     *
     * @param refs 对象版本引用
     * @return {@code cfg_agg_v1:} 前缀 + SHA-256 十六进制摘要（小写）
     */
    @NonNull
    public static String aggregateFingerprint(@NonNull List<ConfigObjectVersion> refs) {
        return AGGREGATE_PREFIX + sha256Hex(toJson(refs));
    }

    /**
     * 规范化排序（按 type → id）。
     *
     * @param refs 对象版本引用
     * @return 排序后的副本
     */
    @NonNull
    public static List<ConfigObjectVersion> canonicalOrder(@NonNull List<ConfigObjectVersion> refs) {
        List<ConfigObjectVersion> copy = new ArrayList<>(refs);
        copy.sort(Comparator.comparing(ConfigObjectVersion::type)
                .thenComparing(ConfigObjectVersion::id)
                .thenComparingInt(ConfigObjectVersion::version));
        return copy;
    }

    /**
     * 在对象版本引用中查找指定类型与 id 的版本。
     *
     * @param refs 对象版本引用
     * @param type 对象类型
     * @param id   对象逻辑 id
     * @return 版本号；未找到时为 {@link Optional#empty()}
     */
    @NonNull
    public static Optional<Integer> findVersion(@NonNull List<ConfigObjectVersion> refs,
                                                          @NonNull String type,
                                                          @NonNull String id) {
        for (ConfigObjectVersion ref : refs) {
            if (ref.type().equals(type) && ref.id().equals(id)) {
                return Optional.of(ref.version());
            }
        }
        return Optional.empty();
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
