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
 */
public record CapToolDef(
        @NonNull String id,
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
}
