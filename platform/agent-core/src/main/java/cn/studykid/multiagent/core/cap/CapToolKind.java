package cn.studykid.multiagent.core.cap;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Tool 种类（§4.2 {@code kind=native|mcp|http}）。
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
    MCP,

    /**
     * 远程 HTTP 请求工具（须配置请求方法与目标 URL）。
     *
     * <p>⚠️ <b>与 {@link #MCP} 的关键差别</b>：MCP 工具的目标由
     * <b>MCP Server 的固定清单</b>约束（远端操作名必须在清单内），
     * 而 HTTP 工具的目标就是<b>配置里的那个 URL</b> ——
     * 它绕开了 MCP 的固定清单，因此<b>自带 SSRF 防线是它的固有义务</b>
     * （设计文档 §13.1「安全与外部 HTTP 工具」）。
     *
     * <p>⚠️ <b>不得降级成 NATIVE</b>：那会让「目标是一个 URL」的语义
     * 消失，从而绕开运行期的出站防护。
     */
    HTTP;

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

    /**
     * @return 是否为远程 HTTP 请求工具
     */
    public boolean isHttp() {
        return this == HTTP;
    }
}
