package cn.studykid.multiagent.ports.persistence.cap;

import java.time.Instant;
import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 平台控制台 · 跨租户 Agent 注册表（只读 · 第三十九批 · Agent 视角扩展）。
 *
 * <p>⚠️ <b>仅管理元数据</b>：平台控制台呈现「各租户登记了哪些 Agent、
 * 版本/发布/启用技能/角色清单/模型绑定/编排模式」这类**结构性信息**，
 * 用于资源盘点与运维画像；Agent 的业务指令（definition 正文 / 运行输出 / 会话内容）
 * <b>绝不出现在任何字段</b> —— 那是禁区红线。
 *
 * <p>⚠️ <b>数据源必须走平台运维角色（BYPASSRLS）</b>：
 * {@code agent} / {@code cap_agent_def} 带 RLS（租户隔离策略），
 * 应用角色连接在无租户上下文时读任何行都会被拒 —— 这是刻意的。
 * 本端口只有平台管理作用域的调用方使用，且调用必须包在
 * {@code PlatformOpsSessionPort#inPlatformScopeSession} 会话窗内。
 *
 * <p>⚠️ <b>只读</b>：平台不修改租户私有 Agent 配置（禁区）；
 * 生命周期/内容变更由租户侧发布工作流执行。
 */
public interface PlatformAgentDirectoryPort {

    /**
     * 列出全部（或指定租户的）Agent 注册表条目，条目 = agent 行 + 最新权威定义摘要。
     *
     * @param tenantIdFilter 租户过滤（UUID）；null = 全部租户
     */
    List<PlatformAgentRow> listAgents(@Nullable String tenantIdFilter);

    /**
     * 列出某个 Agent 的变更记录（跨租户，平台运营视角）。
     *
     * <p>只回变更**元数据**：申请单动作 / 审批状态 / 理由 / 申请人 / 时间 / 目标版本，
     * 不回定义正文 —— 与注册表清单同一条禁区红线。
     *
     * @param agentId Agent 标识（UUID 文本）
     * @param limit   最多返回条数（按申请时间倒序）
     */
    @NonNull
    List<PlatformAgentChange> listAgentChanges(@NonNull String agentId, int limit);

    /**
     * 一条 Agent 注册表条目（管理元数据，绝无业务指令正文）。
     *
     * <p>⚠️ <b>Agent 视角扩展（2026-11-19）</b>：原字段只覆盖「租户绑定 + 能力清单」，
     * 不足以让运营看清 Agent 的**完整身份画像**。新增：
     * <ul>
     *   <li>{@code modelRef} — Agent 绑定的模型引用（必填；null = 未绑模型，fail-closed）；</li>
     *   <li>{@code skillBundles} — Agent 启用的技能包清单（bundled 能力组）；</li>
     *   <li>{@code hasInstructions} — 是否有非空提示词（instructions ≠ {}）；</li>
     *   <li>{@code hasOrchestration} — 是否有非空编排声明（orchestration ≠ {}）；</li>
     *   <li>{@code orchestrationMode} — 编排模式（LEAF / ROUTER / SEQUENTIAL / null 未声明）；</li>
     *   <li>{@code tenantCount} — 该 Agent 被多少个租户 ACTIVE 绑定（复用度指标）。</li>
     * </ul>
     */
    record PlatformAgentRow(
            @NonNull String tenantId,
            @NonNull String agentId,
            @Nullable String name,
            @Nullable String description,
            int versionCount,
            @Nullable Integer latestVersionNumber,
            @Nullable Instant latestPublishedAt,
            /** 权威定义（cap_agent_def）版本；无定义时为 null */
            @Nullable Integer defVersion,
            /** ⚠️ 生命周期状态（PUBLISHED / DEPRECATED / …）；无定义时为 null */
            @Nullable String lifecycleState,
            @NonNull List<String> roles,
            @NonNull List<String> enabledSkills,
            @NonNull List<String> capabilityTags,
            /** 收紧覆盖（overrides）是否非空（true = 该 Agent 有字段级收紧覆盖） */
            boolean hasOverrides,
            /** Agent 绑定的模型引用（cap_agent_def.model_ref）；null = 未绑模型 */
            @Nullable String modelRef,
            /** Agent 启用的技能包清单（cap_agent_def.skill_bundles） */
            @NonNull List<String> skillBundles,
            /** 是否有非空提示词（instructions ≠ {}） */
            boolean hasInstructions,
            /** 是否有非空编排声明（orchestration ≠ {}） */
            boolean hasOrchestration,
            /** 编排模式（LEAF / ROUTER / SEQUENTIAL）；null = 未声明（视为 LEAF） */
            @Nullable String orchestrationMode,
            /** 该 Agent 被多少个租户 ACTIVE 绑定（复用度指标，跨 tenant_agent_binding 聚合） */
            int tenantCount,
            /** 存在未执行的变更申请时为该申请状态（PENDING / APPROVED）；否则 null */
            @Nullable String pendingState,
            /** 未执行申请的时间；否则 null */
            @Nullable Instant pendingAt) {
    }

    /**
     * Agent 变更记录（定义发布 / 生命周期 / 绑定调整等管理动作的元数据）。
     *
     * <p>⚠️ reason 只承载人工填写的变更理由或审计动作摘要，不含定义正文。
     */
    record PlatformAgentChange(
            @NonNull String changeId,
            /** CONFIG = 发布申请单；AUDIT = 平台审计事件（建行 / 分配租户） */
            @NonNull String category,
            @Nullable String tenantId,
            @NonNull String action,
            @NonNull String state,
            @Nullable Integer targetVersion,
            @NonNull String reason,
            @NonNull String actorId,
            @NonNull Instant requestedAt,
            @Nullable Instant appliedAt,
            /**
             * <b>变更前</b>的完整配置（JSON 文本）。
             *
             * <p>⚠️ 由后端在生成变更记录时从权威定义（{@code cap_agent_def}）
             * 的<b>基线版本</b>解析得出并随身携带 —— 前端不再「按版本号 -1 反推」，
             * 版本号不连续（清理草稿后）时反推会取错基准。
             *
             * <p>为 {@code null} 的情形：首个版本（无更早版本）、
             * 以及建行 / 分配租户等非发布类审计动作。
             */
            @Nullable String beforeConfig,
            /**
             * <b>变更后</b>的完整配置（JSON 文本），取自本次申请单载荷。
             *
             * <p>为 {@code null} 的情形：非发布类审计动作（没有配置变更）。
             */
            @Nullable String afterConfig) {
    }
}
