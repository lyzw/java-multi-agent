package cn.studykid.multiagent.core.cap;

import java.time.Instant;
import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Skill 定义版本行（§6.1 {@code cap_skill_def} · §4.2）。
 *
 * <p>platform 级对象（<b>不启用 RLS</b>）。
 *
 * <p>⚠️ <b>不可变版本行</b>：{@code (id, version)} 为主键。
 *
 * <p>⚠️ <b>{@code inherits} 在 V1 写入即拒</b>（N1）：DB 层有
 * {@code CHECK (inherits IS NULL)} 兜底；本模型不承载该字段 ——
 * 能读到 {@code inherits IS NULL} 的行说明它本就不存在，
 * 无需在领域层再做一次判断。
 */
public record CapSkillDef(
        @NonNull String id,
        int version,
        @Nullable String name,
        @Nullable String description,
        @NonNull List<String> declaredTools,
        @NonNull List<McpDeclaration> declaredMcp,
        @NonNull CapabilityRiskLevel riskCeiling,
        @NonNull String paramPolicies,
        @NonNull List<String> requiredCaps,
        @NonNull String configFingerprint,
        int schemaVersion,
        @NonNull Instant createdAt,
        @Nullable Instant publishedAt) {

    /**
     * Skill 对 MCP server 的声明（{@code declaredMcp[]} 的一项 · §4.2/§4.4）。
     *
     * @param serverId      MCP server 标识
     * @param toolAllowlist 该 Skill 在此 server 上允许的操作清单；
     *                       <b>{@code null} ≠ {@code []}</b> ——
     *                       {@code null} 表示未配置（取 ServerAllowedOps 全集），
     *                       {@code []} 表示显式不允许任何操作。
     *                       两者语义不同（§4.4），不可用同一默认值混淆。
     */
    public record McpDeclaration(
            @NonNull String serverId,
            @Nullable List<String> toolAllowlist) {

        /**
         * @return 是否显式配置了操作清单（含显式空清单）
         */
        public boolean hasAllowlist() {
            return toolAllowlist != null;
        }
    }

    /**
     * @return 是否为已发布版本行
     */
    public boolean isPublished() {
        return publishedAt != null;
    }
}
