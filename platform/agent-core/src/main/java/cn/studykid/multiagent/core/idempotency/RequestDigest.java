package cn.studykid.multiagent.core.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

import org.jspecify.annotations.NonNull;

/**
 * 请求摘要 —— 幂等协议的判别依据（ADR-003）。
 *
 * <p><b>核心语义</b>：
 * <blockquote>
 * 同一幂等键：<br>
 *   · 摘要<b>相同</b> → 视为同一请求的重复提交，<b>返回首次结果</b>；<br>
 *   · 摘要<b>不同</b> → 视为不同请求复用了同一键，<b>返回冲突</b>（非 409 的静默成功）。
 * </blockquote>
 *
 * <p>若不比对摘要，客户端在网络超时后换参数重试会被误判为重复，导致
 * 「用户以为改了参数，系统却返回旧结果」——比报错更难排查。
 *
 * <p><b>摘要必须对参数顺序不敏感</b>：同一逻辑请求的 JSON 字段顺序可能不同
 * （不同语言/库的序列化顺序不一致），若直接哈希原文会产生假冲突。
 * 故先按键排序归一化再计算。
 *
 * <p>使用 SHA-256：碰撞概率在本场景（单租户键空间）可忽略。
 */
public record RequestDigest(@NonNull String value) {

    public static final String ALGORITHM = "SHA-256";

    public RequestDigest {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("摘要不能为空");
        }
    }

    /**
     * 由工具参数计算摘要。
     *
     * <p>采用 {@link TreeMap} 归一化键顺序，保证同参数不同书写顺序得到同一摘要。
     *
     * @param arguments 工具参数；不得包含 Prompt 原文或学生可识别信息
     */
    public static @NonNull RequestDigest of(@NonNull Map<String, Object> arguments) {
        return new RequestDigest(sha256(canonicalize(arguments)));
    }

    /** 由任意字符串计算摘要（如已规范化的请求体）。 */
    public static @NonNull RequestDigest ofText(@NonNull String text) {
        return new RequestDigest(sha256(text));
    }

    /**
     * 归一化：递归按键排序，消除书写顺序差异。
     *
     * <p>只做<b>结构归一化</b>，不改变值本身 —— 改值就意味着不同请求，
     * 那正是摘要要检出的情况。
     */
    private static String canonicalize(@NonNull Map<String, Object> arguments) {
        StringBuilder sb = new StringBuilder();
        appendMap(sb, new TreeMap<>(arguments));
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void appendMap(StringBuilder sb, Map<String, Object> sorted) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> e : sorted.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(e.getKey()).append("\":");
            Object v = e.getValue();
            if (v instanceof Map<?, ?> m) {
                appendMap(sb, new TreeMap<>((Map<String, Object>) m));
            } else if (v instanceof Iterable<?> it) {
                sb.append('[');
                boolean f2 = true;
                for (Object o : it) {
                    if (!f2) {
                        sb.append(',');
                    }
                    f2 = false;
                    sb.append(o == null ? "null" : escape(String.valueOf(o)));
                }
                sb.append(']');
            } else if (v == null) {
                sb.append("null");
            } else {
                sb.append(escape(String.valueOf(v)));
            }
        }
        sb.append('}');
    }

    private static String escape(String s) {
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance(ALGORITHM);
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                out.append(Character.forDigit((b >> 4) & 0xF, 16));
                out.append(Character.forDigit(b & 0xF, 16));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用，JVM 异常", e);
        }
    }

    /** @return 是否与另一摘要相同（同一请求）。 */
    public boolean matches(@NonNull RequestDigest other) {
        return value.equals(other.value);
    }
}