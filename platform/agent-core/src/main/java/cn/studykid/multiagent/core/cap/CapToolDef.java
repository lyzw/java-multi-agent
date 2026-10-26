package cn.studykid.multiagent.core.cap;

import java.time.Instant;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Tool 定义版本行（§6.1 {@code cap_tool_def} · §4.2）。
 *
 * <p>platform 级对象（<b>不启用 RLS</b>）：Tool 定义全平台唯一，
 * 租户边界体现在「哪个租户的 Agent 能启用哪个 Skill」上，不在 Tool 定义本身。
 *
 * <p>⚠️ <b>不可变版本行</b>：{@code (id, version)} 为键，行内容一经写入不再改；
 * 改配置只能发新版本（{@code version + 1}）。DB 层另有触发器封禁对已发布行的 UPDATE。
 *
 * @param httpConfig 远程 HTTP 请求配置（<b>仅 kind=http 时非空</b>）——
 *                    {@code null} 表示「本 Tool 不是 HTTP 工具」。
 *                    ⚠️ <b>三态不可归一化</b>：{@code kind=http} 却 {@code httpConfig == null}
 *                    是一个「永远跑不起来」的中间态，构造器直接拒绝（见下方紧凑构造器）。
 */
public record CapToolDef(
        @NonNull String id,
        int version,
        @Nullable String name,
        @NonNull CapToolKind kind,
        @NonNull CapabilityRiskLevel riskLevel,
        @Nullable String mcpServerId,
        @Nullable String mcpToolName,
        @Nullable CapHttpToolConfig httpConfig,
        @NonNull String inputSchema,
        @NonNull String paramPolicies,
        @NonNull String configFingerprint,
        int schemaVersion,
        @NonNull Instant createdAt,
        @Nullable Instant publishedAt) {

    public CapToolDef {
        // ⚠️ **kind 与 httpConfig 必须同生共死**：
        //    · kind=http  → httpConfig 必须齐全（否则 Tool 永远跑不起来）；
        //    · kind≠http  → httpConfig 必须为空（否则「这是个 HTTP 工具」与
        //      「这行没有 HTTP 配置」互相矛盾，运行期该走哪条路取决于实现细节）。
        //    DB 层有同款 CHECK 兜底（cap_tool_def_http_binding_ck）。
        if (kind == CapToolKind.HTTP && httpConfig == null) {
            throw new IllegalArgumentException(
                    "kind=http 的 Tool 必须携带完整的 HTTP 配置（方法 / URL / 超时）");
        }
        if (kind != CapToolKind.HTTP && httpConfig != null) {
            throw new IllegalArgumentException(
                    "kind=" + kind.name() + " 的 Tool 不得携带 HTTP 配置");
        }
        // ⚠️ MCP 绑定与 HTTP 配置互斥：MCP 工具的目标由 Server 固定清单约束，
        //    HTTP 工具的目标由配置里的 URL 给出 —— 两者同时存在时，
        //    「这个 Tool 到底打哪里」就不唯一了。
        if (httpConfig != null && (mcpServerId != null || mcpToolName != null)) {
            throw new IllegalArgumentException(
                    "HTTP 工具不得同时携带 MCP 绑定（目标必须唯一）");
        }
    }

    /**
     * 兼容构造器（<b>非 HTTP 工具</b>）。
     *
     * <p>⚠️ 保留它是为了让「与 HTTP 无关」的既有构造点（测试夹具、纯 native/mcp 的行）
     * 不必逐个补参数 —— 但它<b>只能</b>造出非 HTTP 工具：
     * 传 {@code kind=HTTP} 会在紧凑构造器里被拒（{@code httpConfig == null}）。
     *
     * @param id Tool 标识
     * @param version 版本号
     * @param name 显示名
     * @param kind Tool 种类
     * @param riskLevel 风险等级
     * @param mcpServerId MCP Server 标识（仅 kind=mcp）
     * @param mcpToolName MCP 远端操作名（仅 kind=mcp）
     * @param inputSchema 入参 JSON Schema 文本
     * @param paramPolicies 参数策略 JSON 文本
     * @param configFingerprint 内容指纹
     * @param schemaVersion 行格式版本
     * @param createdAt 创建时间
     * @param publishedAt 发布时间（未发布为 null）
     */
    public CapToolDef(@NonNull String id,
                       int version,
                       @Nullable String name,
                       @NonNull CapToolKind kind,
                       @NonNull CapabilityRiskLevel riskLevel,
                       @Nullable String mcpServerId,
                       @Nullable String mcpToolName,
                       @NonNull String inputSchema,
                       @NonNull String paramPolicies,
                       @NonNull String configFingerprint,
                       int schemaVersion,
                       @NonNull Instant createdAt,
                       @Nullable Instant publishedAt) {
        this(id, version, name, kind, riskLevel, mcpServerId, mcpToolName, null,
                inputSchema, paramPolicies, configFingerprint, schemaVersion,
                createdAt, publishedAt);
    }

    /**
     * @return 是否为已发布版本行
     */
    public boolean isPublished() {
        return publishedAt != null;
    }

    /**
     * @return 是否为 MCP 工具
     */
    public boolean isMcp() {
        return kind == CapToolKind.MCP;
    }

    /**
     * @return 是否为远程 HTTP 请求工具
     */
    public boolean isHttp() {
        return kind == CapToolKind.HTTP;
    }
}
