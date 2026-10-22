package cn.studykid.multiagent.core.cap;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * MCP Server 信任层级（ForcedSafety 的判定要素之一 · §4.4）。
 *
 * <p>⚠️ <b>顶层类型而非嵌套类</b>：原因同 {@link CapToolKind} ——
 * type-use 注解不能用于限定名形式的记录组件，而本枚举需被
 * {@code EffectiveClosure} 引用。
 */
public enum McpTrustTier {

    /** 内部。 */
    INTERNAL,

    /** 合作方。 */
    PARTNER,

    /** 公开。 */
    PUBLIC;

    /**
     * @param raw 持久化文本
     * @return 解析结果；非法值返回 {@code null}
     */
    public static @Nullable McpTrustTier parse(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim().toUpperCase(java.util.Locale.ROOT);
        for (McpTrustTier candidate : values()) {
            if (candidate.name().equals(normalized)) {
                return candidate;
            }
        }
        return null;
    }
}
