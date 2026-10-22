package cn.studykid.multiagent.core.cap;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Tool 种类（§4.2 {@code kind=native|mcp}）。
 *
 * <p>⚠️ <b>顶层类型而非嵌套类</b>：JSpecify 的 {@code @NonNull} 是 type-use 注解，
 * 而 Java 禁止把 type-use 注解用在<b>限定名</b>形式的记录组件上
 * （{@code @NonNull Outer.Inner x} 编译不通过；{@code @NonNull Inner x} 可以）。
 * 本枚举需要被 {@code EffectiveClosure} 等多个类引用，故提升为顶层。
 */
public enum CapToolKind {

    /** 平台原生工具。 */
    NATIVE,

    /** MCP 工具（须绑定 server 与远端工具名）。 */
    MCP;

    /**
     * @param raw 持久化文本
     * @return 解析结果；非法值返回 {@code null}（由调用方 fail-closed 处理）
     */
    public static @Nullable CapToolKind parse(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim().toUpperCase(java.util.Locale.ROOT);
        for (CapToolKind candidate : values()) {
            if (candidate.name().equals(normalized)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * @return 是否为 MCP 工具
     */
    public boolean isMcp() {
        return this == MCP;
    }
}
