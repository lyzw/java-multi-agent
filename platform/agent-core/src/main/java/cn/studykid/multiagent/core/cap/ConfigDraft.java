package cn.studykid.multiagent.core.cap;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 变更草稿 —— 独立于 {@code cap_*_def} 版本行，承载"编辑 → 提交审核/发布"工作流
 * （需求文档 §二.2 / §三 状态流转表）。
 *
 * <p>⚠️ <b>为什么需要新表</b>：现有 {@code ConfigPublishRequest} 承载的是
 * 「审批-执行」两阶段申请单（PENDING → APPROVED → APPLIED），但"用户编辑保存
 *  → 草稿"与"草稿提交审核"是<b>另一条独立工作流</b>，且与线上运行状态
 *  （由 {@code cap_config_pointer} 决定）解耦。把两者塞进同一张表会让
 *  状态机变成"六态混用"，难以维护也难以前端展示。
 *
 * <p>状态机（变更工作流状态）：
 * <pre>
 *   新建保存 ─▶ DRAFT
 *       │
 *       ├──(提交审核)──▶ PENDING_APPROVAL ──(审批通过)──▶ PENDING_PUBLISH ──(发布成功)──▶ [终态，草稿消费]
 *       │                                      │                │
 *       │                                      │(审批驳回)      │(发布失败)
 *       │                                      ▼                ▼
 *       │                              APPROVAL_REJECTED    PUBLISH_FAILED
 *       │                                      │                │
 *       │                                      └──(重新编辑)──▶ DRAFT
 *       │
 *       └──(直接发布，MANUAL 模式)──▶ PENDING_PUBLISH ──(发布成功)──▶ [消费]
 *
 *       任何时刻 ──(放弃)──▶ DISCARDED（终态）
 * </pre>
 *
 * <p>⚠️ <b>双轨独立状态</b>：本 record 仅承载"变更工作流状态"（draft_state）；
 * "线上运行状态"（已发布版本号 / 是否发布）由 {@code cap_config_pointer}
 * 指向的 {@code cap_config_release} 推断 —— 前端同时展示二者。
 *
 * @param draftId          草稿标识
 * @param scope            作用域
 * @param tenantId         租户标识；PLATFORM 作用域为 {@code null}
 * @param resourceKind     资源类型（AGENT / SKILL / TOOL / MCP_SERVER / RESULT_CONTRACT / ROUTING_POLICY）
 * @param resourceId       资源 ID
 * @param resourceName     资源展示名称（便于草稿列表快速展示）
 * @param baseVersion      编辑起点版本（首次新建为 0，基于已发布版本编辑时为线上 pointer 指向版本）
 * @param draftVersion     草稿内部递增版本（同一次编辑多次保存产生）
 * @param draftState       变更工作流状态
 * @param draftConfig      草稿完整配置（JSON 文本）
 * @param configFingerprint 配置内容指纹（用于快速判断内容是否变化）
 * @param latestRequestId  关联到的最新 {@link ConfigPublishRequest}（草稿进入待发布/待审核时创建）
 * @param failureStage     最近一次发布失败阶段
 * @param failureDetails   最近一次发布失败结构化明细（JSON 数组文本）
 * @param rejectionReason  最近一次审批驳回原因
 * @param operatorId       最后操作人
 * @param createdAt        创建时间
 * @param updatedAt        最近更新时间
 */
public record ConfigDraft(
        @NonNull UUID draftId,
        @NonNull String scope,
        @Nullable String tenantId,
        @NonNull String resourceKind,
        @NonNull String resourceId,
        @Nullable String resourceName,
        int baseVersion,
        int draftVersion,
        @NonNull String draftState,
        @NonNull String draftConfig,
        @Nullable String configFingerprint,
        @Nullable UUID latestRequestId,
        @Nullable String failureStage,
        @Nullable String failureDetails,
        @Nullable String rejectionReason,
        @NonNull String operatorId,
        @NonNull Instant createdAt,
        @NonNull Instant updatedAt
) {

    /** 变更工作流状态。 */
    public enum DraftState {
        /** 编辑中，可保存/提交/放弃。 */
        DRAFT,
        /** 已提交审核，等待审批。 */
        PENDING_APPROVAL,
        /** 已审批通过，等待发布（或等待 DIRECT 模式自动发布）。 */
        PENDING_PUBLISH,
        /** 审批驳回，可重新编辑。 */
        APPROVAL_REJECTED,
        /** 发布失败，failure_stage / failure_details 有值，可重新编辑或重发。 */
        PUBLISH_FAILED,
        /** 已放弃，终态。 */
        DISCARDED;

        /** 终态判断（终态草稿不可再迁移状态，只能重建）。 */
        public boolean isTerminal() {
            return this == DISCARDED;
        }
    }

    /** 发布失败阶段（与 {@code cap_config_publish_request.fail_stage} 同口径）。 */
    public enum FailStage {
        /** 前端预校验（基础字段完整性 / JSON 合法性）。 */
        PRE_CHECK,
        /** 后端参数强校验（Schema / 类型 / 范围）。 */
        PARAM_VALIDATE,
        /** 关联依赖检查（Skill / Tool / 子 Agent / 路由 / 契约引用）。 */
        DEP_CHECK,
        /** 发布指针 CAS 并发校验。 */
        POINTER_CAS,
        /** 运行时同步失败（发布头写入/版本行写入/指针原子切换后运行时异常）。 */
        RUNTIME_SYNC
    }

    public ConfigDraft {
        if (draftId == null) throw new IllegalArgumentException("draftId 不能为空");
        if (scope == null || scope.isBlank()) throw new IllegalArgumentException("scope 不能为空");
        if (resourceKind == null || resourceKind.isBlank()) throw new IllegalArgumentException("resourceKind 不能为空");
        if (resourceId == null || resourceId.isBlank()) throw new IllegalArgumentException("resourceId 不能为空");
        if (draftState == null || draftState.isBlank()) throw new IllegalArgumentException("draftState 不能为空");
        if (draftConfig == null || draftConfig.isBlank()) throw new IllegalArgumentException("draftConfig 不能为空");
        if (operatorId == null || operatorId.isBlank()) throw new IllegalArgumentException("operatorId 不能为空");
        if (createdAt == null) createdAt = Instant.EPOCH;
        if (updatedAt == null) updatedAt = Instant.EPOCH;
        if (baseVersion < 0) throw new IllegalArgumentException("baseVersion 不得为负数");
        if (draftVersion <= 0) throw new IllegalArgumentException("draftVersion 必须为正数");

        // scope 与 tenantId 一致性（与 DB CHECK 同款）
        if ("PLATFORM".equals(scope) && tenantId != null) {
            throw new IllegalArgumentException("PLATFORM scope 不得携带 tenantId");
        }
        if ("TENANT".equals(scope) && (tenantId == null || tenantId.isBlank())) {
            throw new IllegalArgumentException("TENANT scope 必须携带 tenantId");
        }
    }

    /** 是否仍处于活跃工作流（可继续迁移状态）。 */
    public boolean isActive() {
        return DraftState.valueOf(draftState) != DraftState.DISCARDED;
    }

    /** 构造新实例，替换 createdAt / updatedAt（持久层回填时间戳用）。 */


    /** 构造新实例，替换 createdAt / updatedAt（持久层回填时间戳用）。 */


    /** 构造新实例，替换 createdAt / updatedAt（持久层回填时间戳用）。 */
    public ConfigDraft withTimestamps(Instant created, Instant updated) {
        return new ConfigDraft(
                draftId(), scope(), tenantId(), resourceKind(), resourceId(), resourceName(),
                baseVersion(), draftVersion(),
                draftState(), draftConfig(), configFingerprint(), latestRequestId(),
                failureStage(), failureDetails(), rejectionReason(), operatorId(),
                created, updated);
    }

}
