package cn.studykid.multiagent.core.cap;

import java.time.Instant;
import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Agent 结构化定义（§6.1 / 06 §1.4）。
 *
 * <p><b>全局实体</b>：Agent 不归属任何租户。租户通过
 * {@code public.tenant_agent_binding} 与 Agent 建立多对多分配关系；
 * 本对象只描述 Agent 本身不可变的配置版本。
 *
 * <p><b>与既有 {@code agent_version} 的关系</b>（06 §1.4 评审 01-02）：
 * 二者并存但指向同一发布事实 —— <b>本对象是权威真源</b>：
 * 运行期的<b>提示词 / 模型 / 生成参数</b>一律读本对象，
 * {@code agent_version.definition}（自由 JSONB）仅保留为历史快照归档
 * 与 {@code run.snapshot_id} 外键桥接，不再是配置真源。
 *
 * <p>⚠️ <b>V1 硬边界</b>（04 §A1 / §3 / N1 / N2）：
 * <ul>
 *   <li>{@code enabledSkills} 是<b>唯一</b>启用途径，不允许隐式/标签传播启用；</li>
 *   <li>{@code skillBundles} / {@code capabilityTags} <b>忽略语义</b>（V2 预留），
 *       不得用于任何启用判定；</li>
 *   <li>{@code inherits} 在 V1 <b>写入即拒</b>（嵌套 Skill 易抬权）。</li>
 * </ul>
 *
 * @param agentId          Agent 全局标识
 * @param version          版本号（与 {@code agentId} 组成不可变版本行）
 * @param name             名称（全局唯一）
 * @param description      描述
 * @param roles            适用主体角色范围（<b>非授权授予</b>）
 * @param enabledSkills    显式启用的 Skill id 列表
 * @param skillBundles     V1 忽略语义（预留）
 * @param capabilityTags   V1 忽略语义（预留）
 * @param overrides        收紧覆盖（仅移除/调低）
 * @param orchestration    编排声明（JSON 文本）—— 缺省 {@code "{}"} 解析为 {@code LEAF}
 * @param instructions     提示词声明（JSON 文本）—— 缺省 {@code "{}"} = 未声明提示词
 * @param modelRef         绑定的模型引用；{@code null} = 未绑定模型，运行期 fail-closed
 * @param generationParams 生成参数（JSON 文本）
 * @param configFingerprint 本 Agent 定义版本的内容指纹
 * @param schemaVersion    行格式版本
 * @param createdAt        创建时间
 * @param publishedAt      发布时间（{@code null} = 草稿）
 */
public record CapAgentDef(
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
        @NonNull String instructions,
        @Nullable String modelRef,
        @NonNull String generationParams,
        @NonNull String configFingerprint,
        int schemaVersion,
        @NonNull Instant createdAt,
        @Nullable Instant publishedAt
) {

    /** 兼容构造器：调用方传来的旧 tenantId 被忽略；Agent 已是全局实体。 */
    @Deprecated(forRemoval = true)
    public CapAgentDef(@NonNull String ignoredTenantId,
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
                       @NonNull String instructions,
                       @Nullable String modelRef,
                       @NonNull String generationParams,
                       @NonNull String configFingerprint,
                       int schemaVersion,
                       @NonNull Instant createdAt,
                       @Nullable Instant publishedAt) {
        this(agentId, version, name, description, roles, enabledSkills, skillBundles,
                capabilityTags, overrides, orchestration, instructions, modelRef,
                generationParams, configFingerprint, schemaVersion, createdAt, publishedAt);
    }

    /** 兼容构造器：无编排声明的历史形态，tenantId 被忽略。 */
    @Deprecated(forRemoval = true)
    public CapAgentDef(@NonNull String ignoredTenantId,
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
        this(agentId, version, name, description, roles, enabledSkills, skillBundles,
                capabilityTags, overrides, "{}", "{}", null, "{}",
                configFingerprint, schemaVersion, createdAt, publishedAt);
    }

    /** 兼容构造器：有编排声明但未声明提示词/模型，tenantId 被忽略。 */
    @Deprecated(forRemoval = true)
    public CapAgentDef(@NonNull String ignoredTenantId,
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
                       @Nullable Instant publishedAt) {
        this(agentId, version, name, description, roles, enabledSkills, skillBundles,
                capabilityTags, overrides, orchestration, "{}", null, "{}",
                configFingerprint, schemaVersion, createdAt, publishedAt);
    }

    /** 全局形态便捷构造器：无编排声明。 */
    public CapAgentDef(@NonNull String agentId,
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
        this(agentId, version, name, description, roles, enabledSkills, skillBundles,
                capabilityTags, overrides, "{}", "{}", null, "{}",
                configFingerprint, schemaVersion, createdAt, publishedAt);
    }

    public CapAgentDef {
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
            throw new IllegalArgumentException("orchestration 不能为空（缺省用 {} 表示 LEAF）");
        }
        if (instructions == null || instructions.isBlank()) {
            throw new IllegalArgumentException("instructions 不能为空（缺省用 {} 表示未声明提示词）");
        }
        if (generationParams == null || generationParams.isBlank()) {
            throw new IllegalArgumentException(
                    "generationParams 不能为空（缺省用 {} 表示走运行期默认值）");
        }
        roles = roles == null ? List.of() : List.copyOf(roles);
        enabledSkills = enabledSkills == null ? List.of() : List.copyOf(enabledSkills);
        skillBundles = skillBundles == null ? List.of() : List.copyOf(skillBundles);
        capabilityTags = capabilityTags == null ? List.of() : List.copyOf(capabilityTags);
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt 不能为空");
        }
    }

    /** @return 是否为已发布版本（已发布版本行不可变）。 */
    public boolean isPublished() {
        return publishedAt != null;
    }
}
