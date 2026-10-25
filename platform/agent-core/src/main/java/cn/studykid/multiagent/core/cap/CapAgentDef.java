package cn.studykid.multiagent.core.cap;

import java.time.Instant;
import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Agent 结构化定义（§6.1 / 06 §1.4）。
 *
 * <p><b>与既有 {@code agent_version} 的关系</b>（06 §1.4 评审 01-02）：
 * 二者并存但指向同一发布事实 —— 本对象是<b>结构化真源</b>，
 * {@code agent_version.definition}（自由 JSONB）保留为历史快照归档，
 * 迁移期<b>双写</b>（P0-T6），最终读路径切到本对象。
 *
 * <p>⚠️ <b>V1 硬边界</b>（04 §A1 / §3 / N1 / N2）：
 * <ul>
 *   <li>{@code enabledSkills} 是<b>唯一</b>启用途径，不允许隐式/标签传播启用；</li>
 *   <li>{@code skillBundles} / {@code capabilityTags} <b>忽略语义</b>（V2 预留），
 *       不得用于任何启用判定；</li>
 *   <li>{@code inherits} 在 V1 <b>写入即拒</b>（嵌套 Skill 易抬权）。</li>
 * </ul>
 *
 * @param tenantId         租户标识
 * @param agentId          Agent 标识
 * @param version          版本号（与 {@code (tenantId, agentId)} 组成不可变版本行）
 * @param name             名称
 * @param description      描述
 * @param roles            适用主体角色范围（<b>非授权授予</b>）
 * @param enabledSkills    显式启用的 Skill id 列表
 * @param skillBundles     V1 忽略语义（预留）
 * @param capabilityTags   V1 忽略语义（预留）
 * @param overrides        收紧覆盖（仅移除/调低）
 * @param orchestration    编排声明（JSON 文本）—— 缺省 {@code "{}"} 解析为 {@code LEAF}；
 *                         多级 Agent v1.1 新增，<b>旧行不改写</b>（兼容读）
 * @param configFingerprint 本 Agent 定义版本的内容指纹
 * @param schemaVersion    行格式版本
 * @param createdAt        创建时间
 * @param publishedAt      发布时间（{@code null} = 草稿）
 */
public record CapAgentDef(
        @NonNull String tenantId,
        @NonNull String agentId,
        int version,
        @Nullable String name,
        @Nullable String description,
        @NonNull List<String> roles,
        @NonNull List<String> enabledSkills,
        @NonNull List<String> skillBundles,
        @NonNull List<String> capabilityTags,
        @NonNull String overrides,
        @NonNull String orchestration,
        @NonNull String configFingerprint,
        int schemaVersion,
        @NonNull Instant createdAt,
        @Nullable Instant publishedAt
) {

    /**
     * 兼容构造器：<b>无编排声明</b>的 Agent 定义行（多级 Agent 之前的历史行）。
     *
     * <p>⚠️ 缺省 {@code "{}"} 表示「按 LEAF 解析」（MA-T01），
     * <b>不是</b>「解析失败」也不是「不限制」——
     * 没有编排声明的 Agent 一律不能被委派。
     */
    public CapAgentDef(@NonNull String tenantId,
                       @NonNull String agentId,
                       int version,
                       @Nullable String name,
                       @Nullable String description,
                       @NonNull List<String> roles,
                       @NonNull List<String> enabledSkills,
                       @NonNull List<String> skillBundles,
                       @NonNull List<String> capabilityTags,
                       @NonNull String overrides,
                       @NonNull String configFingerprint,
                       int schemaVersion,
                       @NonNull Instant createdAt,
                       @Nullable Instant publishedAt) {
        this(tenantId, agentId, version, name, description, roles, enabledSkills,
                skillBundles, capabilityTags, overrides, "{}", configFingerprint,
                schemaVersion, createdAt, publishedAt);
    }

    public CapAgentDef {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId 不能为空");
        }
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agentId 不能为空");
        }
        if (version <= 0) {
            throw new IllegalArgumentException("version 必须为正数，收到: " + version);
        }
        if (configFingerprint == null || configFingerprint.isBlank()) {
            throw new IllegalArgumentException("configFingerprint 不能为空");
        }
        if (overrides == null || overrides.isBlank()) {
            throw new IllegalArgumentException("overrides 不能为空");
        }
        if (orchestration == null || orchestration.isBlank()) {
            // ⚠️ 空编排声明按缺省 LEAF 处理，但不能是 null/空白 ——
            //    那说明写入路径把「没填」和「填坏了」混为一谈。
            throw new IllegalArgumentException("orchestration 不能为空（缺省用 {} 表示 LEAF）");
        }
        roles = roles == null ? List.of() : List.copyOf(roles);
        enabledSkills = enabledSkills == null ? List.of() : List.copyOf(enabledSkills);
        skillBundles = skillBundles == null ? List.of() : List.copyOf(skillBundles);
        capabilityTags = capabilityTags == null ? List.of() : List.copyOf(capabilityTags);
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt 不能为空");
        }
    }

    /**
     * @return 是否为已发布版本（已发布版本行<b>不可变</b>，§6.1）
     */
    public boolean isPublished() {
        return publishedAt != null;
    }
}
