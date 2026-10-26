package cn.studykid.multiagent.ports.persistence.cap;

import java.time.Instant;
import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 平台控制台 · 跨租户 Agent 注册表（只读 · 第三十九批）。
 *
 * <p>⚠️ <b>仅管理元数据</b>：平台控制台呈现「各租户登记了哪些 Agent、
 * 版本/发布/启用技能/角色清单」这类**结构性信息**，用于资源盘点与运维画像；
 * Agent 的业务指令（definition 正文 / 运行输出 / 会话内容）<b>绝不出现在任何字段</b> ——
 * 那是重规划 §10 的禁区红线（平台不浏览租户私有业务内容）。
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

    /** 一条 Agent 注册表条目（管理元数据，绝无业务指令正文）。 */
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
            boolean hasOverrides) {
    }
}
