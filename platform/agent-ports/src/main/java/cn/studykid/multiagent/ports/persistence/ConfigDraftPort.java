package cn.studykid.multiagent.ports.persistence;

import java.util.List;
import java.util.UUID;
import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.cap.ConfigDraft;

/**
 * 变更草稿端口。
 *
 * <p>⚠️ <b>单资源同时只有一条活跃草稿</b>：DB 层 {@code uq_cap_config_draft_active}
 * 索引已强制，应用层调用 {@link #insert} 前应先 {@link #findActiveByResource}
 * 检查，否则会被 DB 唯一索引拒（应用层先查是为了给调用方更友好的错误信息）。
 *
 * <p>⚠️ <b>状态迁移守卫在 DB 层 CHECK 索引之外</b>：仓储层 update 方法
 * 应先加载现有行、校验 current_state → target_state 是否合法，再执行更新。
 * 非法迁移（如 DISCARDED → PENDING_PUBLISH）必须 fail-closed。
 */
public interface ConfigDraftPort {

    /** 插入新草稿（状态为 DRAFT）。 */
    @NonNull ConfigDraft insert(@NonNull ConfigDraft draft);

    /** 按 draftId 查询（必须带 scope + tenant_id 以命中 RLS）。 */
    @NonNull Optional<ConfigDraft> findById(@NonNull String scope,
                                             @Nullable String tenantId,
                                             @NonNull UUID draftId);

    /** 找指定资源当前活跃草稿（DB 层唯一索引保证最多一条）。 */
    @NonNull Optional<ConfigDraft> findActiveByResource(@NonNull String scope,
                                                        @Nullable String tenantId,
                                                        @NonNull String resourceKind,
                                                        @NonNull String resourceId);

    /** 列出 (scope, tenant_id) 下的草稿，可按 state 过滤（cursor 分页）。 */
    @NonNull Page<ConfigDraft> list(@NonNull String scope,
                                     @Nullable String tenantId,
                                     @Nullable String state,
                                     @Nullable String cursor,
                                     int limit);

    /** 保存草稿内容（DRAFT 或 APPROVAL_REJECTED 或 PUBLISH_FAILED 状态下更新配置、draft_version++）。 */
    @NonNull ConfigDraft saveContent(@NonNull String scope,
                                      @Nullable String tenantId,
                                      @NonNull UUID draftId,
                                      @NonNull String newConfig,
                                      @Nullable String newFingerprint,
                                      @NonNull String operatorId);

    /**
     * 状态迁移（应用层必须传入期望的 current_state，仓储层做校验 + 迁移）。
     * 迁移规则：
     *   DRAFT → PENDING_APPROVAL / PENDING_PUBLISH / DISCARDED
     *   PENDING_APPROVAL → PENDING_PUBLISH / APPROVAL_REJECTED / DISCARDED
     *   PENDING_PUBLISH → PUBLISH_FAILED / DISCARDED （发布成功后草稿应被置终态）
     *   APPROVAL_REJECTED → DRAFT / DISCARDED
     *   PUBLISH_FAILED → DRAFT / PENDING_PUBLISH / DISCARDED
     */
    @NonNull ConfigDraft transitionState(@NonNull String scope,
                                          @Nullable String tenantId,
                                          @NonNull UUID draftId,
                                          @NonNull String expectedCurrentState,
                                          @NonNull String targetState,
                                          @Nullable UUID linkedRequestId,
                                          @Nullable String operatorId);

    /** 标记发布失败（写 failure_stage / failure_details + 迁移到 PUBLISH_FAILED）。 */
    @NonNull ConfigDraft markFailed(@NonNull String scope,
                                     @Nullable String tenantId,
                                     @NonNull UUID draftId,
                                     @NonNull String failStage,
                                     @NonNull String failDetailsJson,
                                     @NonNull String operatorId);

    /** 审批驳回（写 rejection_reason + 迁移到 APPROVAL_REJECTED）。 */
    @NonNull ConfigDraft markRejected(@NonNull String scope,
                                       @Nullable String tenantId,
                                       @NonNull UUID draftId,
                                       @Nullable UUID linkedRequestId,
                                       @NonNull String rejectionReason,
                                       @NonNull String operatorId);
}
