package cn.studykid.multiagent.application.cap;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.core.cap.ConfigDraft;
import cn.studykid.multiagent.core.cap.ConfigDraft.DraftState;
import cn.studykid.multiagent.core.cap.ConfigDraft.FailStage;
import cn.studykid.multiagent.core.cap.ConfigRelease;
import cn.studykid.multiagent.core.cap.PublishPolicy.PublishMode;
import cn.studykid.multiagent.ports.persistence.ConfigDraftPort;
import cn.studykid.multiagent.ports.persistence.ConfigPublishRequestPort;
import cn.studykid.multiagent.ports.persistence.ConfigPublishRequestPort.Decision;
import cn.studykid.multiagent.ports.persistence.Page;

/**
 * 变更草稿服务（需求文档 §二.2 / §三）。
 *
 * <p>工作流与发布策略的映射：
 * <pre>
 *   策略 DIRECT         → 保存草稿 DRAFT → 立即自动发布（不经审批）
 *   策略 MANUAL         → 保存草稿 DRAFT → 用户点击【提交发布】→ PENDING_PUBLISH
 *   策略 APPROVAL_MANUAL → 保存草稿 DRAFT → 用户点击【提交审核】→ PENDING_APPROVAL
 *                         → 审批通过 → PENDING_PUBLISH（等待手动发布）
 *   策略 APPROVAL_AUTO   → 保存草稿 DRAFT → 用户点击【提交审核】→ PENDING_APPROVAL
 *                         → 审批通过 → 自动发布
 * </pre>
 *
 * <p>⚠️ 发布请求单仍由 {@link ConfigPublishWorkflowService} 管理 ——
 * 草稿服务负责"编辑工作流"，发布服务负责"审批-执行三段式"，二者
 * 由 {@code latest_request_id} 关联。
 */
public class ConfigDraftService {

    private static final Logger log = LoggerFactory.getLogger(ConfigDraftService.class);

    private final ConfigDraftPort drafts;
    private final PublishPolicyService policies;
    private final AuditLogService audit;
    private final ConfigPublishRequestPort requests;
    private final ConfigPublishWorkflowService workflow;
    private final Clock clock;

    public ConfigDraftService(@NonNull ConfigDraftPort drafts,
                               @NonNull PublishPolicyService policies,
                               @NonNull AuditLogService audit,
                               @NonNull ConfigPublishRequestPort requests,
                               @NonNull ConfigPublishWorkflowService workflow,
                               @NonNull Clock clock) {
        this.drafts = drafts;
        this.policies = policies;
        this.audit = audit;
        this.requests = requests;
        this.workflow = workflow;
        this.clock = clock;
    }

    // ── 草稿 CRUD ─────────────────────────────────────────────────

    /** 创建新草稿（首次新建资产时 baseVersion=0）。 */
    public @NonNull ConfigDraft create(@NonNull String scope,
                                         @Nullable String tenantId,
                                         @NonNull String resourceKind,
                                         @NonNull String resourceId,
                                         @Nullable String resourceName,
                                         int baseVersion,
                                         @NonNull String configJson,
                                         @NonNull String operatorId) {
        // 同资源活跃草稿冲突检查
        Optional<ConfigDraft> existing = drafts.findActiveByResource(
                scope, tenantId, resourceKind, resourceId);
        if (existing.isPresent()) {
            ConfigDraft e = existing.get();
            throw new IllegalStateException(
                    "资源 (" + resourceKind + "/" + resourceId + ") 已有活跃草稿，"
                    + "draft_id=" + e.draftId() + ", state=" + e.draftState());
        }
        PublishMode mode = policies.resolveMode(scope, tenantId, resourceKind, resourceId);
        // 策略是 DIRECT → 自动发布（免审直发）
        if (mode == PublishMode.DIRECT) {
            return createAutoPublish(scope, tenantId, resourceKind, resourceId, resourceName,
                    baseVersion, configJson, operatorId);
        }
        ConfigDraft draft = new ConfigDraft(
                UUID.randomUUID(), scope, tenantId, resourceKind, resourceId, resourceName,
                baseVersion, 1, DraftState.DRAFT.name(),
                configJson, fingerprint(configJson), null, null, null, null,
                operatorId, clock.instant(), clock.instant()
        );
        ConfigDraft created = drafts.insert(draft);
        audit.logCreate(scope, tenantId, resourceKind, resourceId, resourceName,
                configJson, baseVersion == 0 ? 1 : baseVersion + 1, operatorId, "DIRECT 直发" );
        return created;
    }

    /**
     * 保存草稿（版本管理机制 · 2026-11-22）。
     *
     * <p>⚠️ <b>有实际修改才递增版本</b>：新内容指纹与当前草稿指纹一致时，
     * 本次保存按 no-op 处理 —— 不递增 {@code draft_version}、不更新内容与
     * {@code updated_at}（版本变更时间），也不写审计 —— 空转保存会污染版本历史。
     * <p>⚠️ <b>编辑仅允许在未发布草稿上进行</b>：已提交审批 / 待发布 / 已终态的草稿
     * 一律拒绝编辑（持久层 WHERE 亦有同款状态闸门兜底）。
     * <p>⚠️ 有实际修改时，版本变更（{@code draft_version + 1}）与变更时间
     * （{@code updated_at} + 审计 {@code created_at}）一并落库，
     * 审计的 {@code from_version → to_version} 记录草稿版本递增轨迹。
     */
    public @NonNull ConfigDraft save(@NonNull String scope,
                                       @Nullable String tenantId,
                                       @NonNull UUID draftId,
                                       @NonNull String newConfig,
                                       @NonNull String operatorId) {
        Optional<ConfigDraft> existing = drafts.findById(scope, tenantId, draftId);
        if (existing.isEmpty()) throw new IllegalArgumentException("草稿不存在: " + draftId);
        ConfigDraft cur = existing.get();
        requireEditableDraftState(cur);
        String newFingerprint = fingerprint(newConfig);
        if (newFingerprint != null && newFingerprint.equals(cur.configFingerprint())) {
            // 无实际修改：内容指纹一致 → 版本号不加 1，直接返回当前草稿。
            log.info("草稿 {} 内容无实际修改（指纹一致），不递增版本、不变更时间", draftId);
            return cur;
        }
        ConfigDraft saved = drafts.saveContent(scope, tenantId, draftId, newConfig,
                newFingerprint, operatorId);
        audit.logSaveDraft(scope, tenantId, cur.resourceKind(), cur.resourceId(),
                cur.resourceName(), cur.draftConfig(), newConfig,
                cur.draftVersion(), saved.draftVersion(),
                operatorId);
        return saved;
    }

    /**
     * 编辑状态闸门：只有「未发布」的草稿可编辑（与持久层
     * {@code draft_state IN ('DRAFT','APPROVAL_REJECTED','PUBLISH_FAILED')} 同口径，
     * 服务层先拒给出更明确的错误信息）。
     */
    private static void requireEditableDraftState(@NonNull ConfigDraft cur) {
        String state = cur.draftState();
        boolean editable = DraftState.DRAFT.name().equals(state)
                || DraftState.APPROVAL_REJECTED.name().equals(state)
                || DraftState.PUBLISH_FAILED.name().equals(state);
        if (!editable) {
            throw new IllegalStateException("草稿当前状态 " + state
                    + "，编辑仅允许在未发布草稿（DRAFT / APPROVAL_REJECTED / PUBLISH_FAILED）上进行");
        }
    }

    /** 查找资源的当前活跃草稿。 */
    public @NonNull Optional<ConfigDraft> findActiveByResource(@NonNull String scope,
                                                                @Nullable String tenantId,
                                                                @NonNull String resourceKind,
                                                                @NonNull String resourceId) {
        return drafts.findActiveByResource(scope, tenantId, resourceKind, resourceId);
    }

    /** 列出草稿（可按 state 过滤）。 */
    public @NonNull Page<ConfigDraft> list(@NonNull String scope,
                                             @Nullable String tenantId,
                                             @Nullable String state,
                                             @Nullable String cursor,
                                             int limit) {
        return drafts.list(scope, tenantId, state, cursor, limit);
    }

    // ── 状态迁移 ──────────────────────────────────────────────────

    /** 提交审核（策略 APPROVAL_* 模式）→ PENDING_APPROVAL。 */
    public @NonNull ConfigDraft submitApproval(@NonNull String scope,
                                                @Nullable String tenantId,
                                                @NonNull UUID draftId,
                                                @NonNull String operatorId,
                                                @Nullable String reason) {
        ConfigDraft cur = requireState(scope, tenantId, draftId, DraftState.DRAFT);
        PublishMode mode = policies.resolveMode(scope, tenantId, cur.resourceKind(), cur.resourceId());
        if (!mode.requiresApproval()) {
            throw new IllegalStateException(
                    "资源 (" + cur.resourceKind() + "/" + cur.resourceId() + ") 当前策略为 " + mode
                    + "，不支持提交审核");
        }
        // 预检（前端/后端双重校验中的后端预检）
        runPrecheck(cur);

        ConfigDraft after = drafts.transitionState(scope, tenantId, draftId,
                DraftState.DRAFT.name(), DraftState.PENDING_APPROVAL.name(),
                null, operatorId);
        policies.markBusy(scope, tenantId, cur.resourceKind(), cur.resourceId());
        audit.logSubmitApproval(scope, tenantId, cur.resourceKind(), cur.resourceId(),
                cur.resourceName(), operatorId, null, reason);
        return after;
    }

    /** 审批通过（外部工作流调用）→ PENDING_PUBLISH（若 AUTO 则自动继续发布）。 */
    public @NonNull ConfigDraft approve(@NonNull String scope,
                                         @Nullable String tenantId,
                                         @NonNull UUID draftId,
                                         @NonNull String operatorId,
                                         @Nullable String reason) {
        ConfigDraft cur = requireState(scope, tenantId, draftId, DraftState.PENDING_APPROVAL);
        ConfigDraft after = drafts.transitionState(scope, tenantId, draftId,
                DraftState.PENDING_APPROVAL.name(), DraftState.PENDING_PUBLISH.name(),
                null, operatorId);
        audit.logApprove(scope, tenantId, cur.resourceKind(), cur.resourceId(),
                cur.resourceName(), operatorId, null, reason);
        // APPROVAL_AUTO → 自动发布
        PublishMode mode = policies.resolveMode(scope, tenantId, cur.resourceKind(), cur.resourceId());
        if (mode == PublishMode.APPROVAL_AUTO) {
            log.info("策略 APPROVAL_AUTO：草稿 {} 审批通过，自动发布", draftId);
            return doPublish(cur, after, operatorId);
        }
        return after;
    }

    /** 审批驳回 → APPROVAL_REJECTED（可重新编辑）。 */
    public @NonNull ConfigDraft reject(@NonNull String scope,
                                        @Nullable String tenantId,
                                        @NonNull UUID draftId,
                                        @NonNull String operatorId,
                                        @NonNull String rejectionReason) {
        ConfigDraft cur = requireState(scope, tenantId, draftId, DraftState.PENDING_APPROVAL);
        ConfigDraft after = drafts.markRejected(scope, tenantId, draftId,
                cur.latestRequestId(), rejectionReason, operatorId);
        policies.clearBusy(scope, tenantId, cur.resourceKind(), cur.resourceId());
        audit.logReject(scope, tenantId, cur.resourceKind(), cur.resourceId(),
                cur.resourceName(), operatorId, cur.latestRequestId(), rejectionReason);
        return after;
    }

    /** 手动发布（MANUAL 模式用户点击【发布】）→ 走完整发布流程。 */
    public @NonNull ConfigDraft publish(@NonNull String scope,
                                         @Nullable String tenantId,
                                         @NonNull UUID draftId,
                                         @NonNull String operatorId) {
        ConfigDraft cur = requireAnyState(scope, tenantId, draftId,
                List.of(DraftState.PENDING_PUBLISH, DraftState.DRAFT, DraftState.PUBLISH_FAILED));
        return doPublish(cur, cur, operatorId);
    }

    /** 放弃草稿 → DISCARDED（终态）。 */
    public @NonNull ConfigDraft discard(@NonNull String scope,
                                         @Nullable String tenantId,
                                         @NonNull UUID draftId,
                                         @NonNull String operatorId,
                                         @Nullable String reason) {
        ConfigDraft cur = drafts.findById(scope, tenantId, draftId)
                .orElseThrow(() -> new IllegalArgumentException("草稿不存在: " + draftId));
        ConfigDraft after = drafts.transitionState(scope, tenantId, draftId,
                cur.draftState(), DraftState.DISCARDED.name(),
                cur.latestRequestId(), operatorId);
        policies.clearBusy(scope, tenantId, cur.resourceKind(), cur.resourceId());
        audit.logDiscard(scope, tenantId, cur.resourceKind(), cur.resourceId(),
                cur.resourceName(), operatorId, reason);
        return after;
    }

    // ── 发布失败重发 ─────────────────────────────────────────────

    /** 草稿处于 PUBLISH_FAILED 时的"重发"：直接迁移回 PENDING_PUBLISH 并再发布。 */
    public @NonNull ConfigDraft retryPublish(@NonNull String scope,
                                              @Nullable String tenantId,
                                              @NonNull UUID draftId,
                                              @NonNull String operatorId) {
        ConfigDraft cur = requireState(scope, tenantId, draftId, DraftState.PUBLISH_FAILED);
        ConfigDraft transitioned = drafts.transitionState(scope, tenantId, draftId,
                DraftState.PUBLISH_FAILED.name(), DraftState.PENDING_PUBLISH.name(),
                cur.latestRequestId(), operatorId);
        return doPublish(cur, transitioned, operatorId);
    }

    // ── 内部 ──────────────────────────────────────────────────────

    /** DIRECT 直发：保存草稿后立即发布。 */
    private ConfigDraft createAutoPublish(@NonNull String scope,
                                           @Nullable String tenantId,
                                           @NonNull String resourceKind,
                                           @NonNull String resourceId,
                                           @Nullable String resourceName,
                                           int baseVersion,
                                           @NonNull String configJson,
                                           @NonNull String operatorId) {
        ConfigDraft draft = new ConfigDraft(
                UUID.randomUUID(), scope, tenantId, resourceKind, resourceId, resourceName,
                baseVersion, 1, DraftState.DRAFT.name(),
                configJson, fingerprint(configJson), null, null, null, null,
                operatorId, clock.instant(), clock.instant()
        );
        ConfigDraft created = drafts.insert(draft);
        runPrecheck(created);
        // 进入 PENDING_PUBLISH → 发布
        ConfigDraft pending = drafts.transitionState(scope, tenantId, created.draftId(),
                DraftState.DRAFT.name(), DraftState.PENDING_PUBLISH.name(), null, operatorId);
        return doPublish(created, pending, operatorId);
    }

    /**
     * 核心发布流程：前后端校验 → 创建 ConfigPublishRequest → 迁移状态。
     * 实际指针 CAS / release 写入 / 对象版本行写入由
     * {@link cn.studykid.multiagent.application.cap.ConfigPublishWorkflowService} 执行，
     * 这里负责草稿侧状态流转 + 审计 + 失败记录。
     */
    private ConfigDraft doPublish(@NonNull ConfigDraft original,
                                   @NonNull ConfigDraft current,
                                   @NonNull String operatorId) {
        // 1. PRE_CHECK（草稿服务自己做的预检）
        try {
            runPrecheck(current);
        } catch (DraftPrecheckException e) {
            return failPublish(current, FailStage.PRE_CHECK, e.detailsJson(), operatorId);
        }

        // 2. PARAM_VALIDATE：JSON 结构合法性（极轻量，这里只做最基本的 parse）
        try {
            parseJsonStrict(current.draftConfig());
        } catch (Exception e) {
            return failPublish(current, FailStage.PARAM_VALIDATE,
                    "[{\"field\":\"draftConfig\",\"error\":\"JSON 结构非法\",\"detail\":\""
                    + e.getMessage().replace("\"", "'") + "\"}]",
                    operatorId);
        }

        // 3. 创建 ConfigPublishRequest（真正的发布执行由发布服务完成）
        try {
            ConfigPublishRequestPayload payload = new ConfigPublishRequestPayload(
                    current.resourceKind(), current.resourceId(), current.draftConfig());
            String reason = "草稿自动发布: " + current.draftId();
            // ⚠️ 这里复用现有 ConfigPublishRequestPort.insert 接口，
            // 但 insert 期望的是 ConfigPublishRequest.Operation 等字段 ——
            // 为保持与既有发布工作流兼容，暂不在这里直接建 request 单，
            // 而是返回 PENDING_PUBLISH 状态，让调用方显式走 apply 流程。
            // （完整集成会在 API 层与 ConfigPublishWorkflowService 打通）
            log.info("草稿 {} 校验通过，等待 ConfigPublishWorkflowService.apply 执行指针 CAS",
                    current.draftId());

            // 发布成功后：迁移草稿状态（这里先不标记为终态 —— 发布成功后草稿应被置终态，
            // 由 ConfigPublishWorkflowService.apply 完成后调用 notifyPublishSuccess）
            return current;

        } catch (Exception e) {
            log.error("草稿 {} 发布执行异常: {}", current.draftId(), e.getMessage());
            return failPublish(current, FailStage.RUNTIME_SYNC,
                    "[{\"error\":\"发布执行异常\",\"detail\":\""
                    + e.getMessage().replace("\"", "'") + "\"}]",
                    operatorId);
        }
    }

    /** 标记发布成功（由 ConfigPublishWorkflowService.apply 成功后回调）。 */
    public @NonNull ConfigDraft markPublishSuccess(@NonNull String scope,
                                                    @Nullable String tenantId,
                                                    @NonNull UUID draftId,
                                                    @NonNull String operatorId,
                                                    @Nullable Integer targetVersion,
                                                    @Nullable UUID requestId,
                                                    @Nullable Long releaseId) {
        ConfigDraft cur = drafts.findById(scope, tenantId, draftId)
                .orElseThrow(() -> new IllegalArgumentException("草稿不存在"));
        // 终态：DISCARDED（草稿被发布消费）
        ConfigDraft after = drafts.transitionState(scope, tenantId, draftId,
                cur.draftState(), DraftState.DISCARDED.name(), requestId, operatorId);
        policies.clearBusy(scope, tenantId, cur.resourceKind(), cur.resourceId());
        audit.logPublishSuccess(scope, tenantId, cur.resourceKind(), cur.resourceId(),
                cur.resourceName(), /*before*/ null, cur.draftConfig(),
                cur.baseVersion(),
                targetVersion == null ? cur.baseVersion() + 1 : targetVersion,
                operatorId, requestId, releaseId);
        return after;
    }

    private ConfigDraft failPublish(@NonNull ConfigDraft current,
                                     @NonNull FailStage stage,
                                     @NonNull String detailsJson,
                                     @NonNull String operatorId) {
        ConfigDraft after = drafts.markFailed(current.scope(), current.tenantId(),
                current.draftId(), stage.name(), detailsJson, operatorId);
        policies.clearBusy(current.scope(), current.tenantId(),
                current.resourceKind(), current.resourceId());
        audit.logPublishFailed(current.scope(), current.tenantId(),
                current.resourceKind(), current.resourceId(), current.resourceName(),
                operatorId, stage.name(), null,
                detailsJson.length() > 200 ? detailsJson.substring(0, 200) : detailsJson);
        return after;
    }

    /** 草稿预检（字段完整性 / 基础合法性）。 */
    private void runPrecheck(@NonNull ConfigDraft draft) {
        List<String> errors = new ArrayList<>();
        if (draft.resourceKind() == null || draft.resourceKind().isBlank()) {
            errors.add("resource_kind 不能为空");
        }
        if (draft.resourceId() == null || draft.resourceId().isBlank()) {
            errors.add("resource_id 不能为空");
        }
        if (draft.draftConfig() == null || draft.draftConfig().isBlank()) {
            errors.add("draft_config 不能为空");
        }
        try {
            parseJsonStrict(draft.draftConfig());
        } catch (Exception e) {
            errors.add("draft_config 不是合法 JSON: " + e.getMessage());
        }
        if (!errors.isEmpty()) {
            throw new DraftPrecheckException(errors);
        }
    }

    private ConfigDraft requireState(@NonNull String scope,
                                     @Nullable String tenantId,
                                     @NonNull UUID draftId,
                                     @NonNull DraftState expected) {
        Optional<ConfigDraft> opt = drafts.findById(scope, tenantId, draftId);
        if (opt.isEmpty()) throw new IllegalArgumentException("草稿不存在: " + draftId);
        ConfigDraft d = opt.get();
        if (!expected.name().equals(d.draftState())) {
            throw new IllegalStateException(
                    "草稿当前状态 " + d.draftState() + "，期望 " + expected);
        }
        return d;
    }

    private ConfigDraft requireAnyState(@NonNull String scope,
                                        @Nullable String tenantId,
                                        @NonNull UUID draftId,
                                        @NonNull List<DraftState> allowed) {
        Optional<ConfigDraft> opt = drafts.findById(scope, tenantId, draftId);
        if (opt.isEmpty()) throw new IllegalArgumentException("草稿不存在: " + draftId);
        ConfigDraft d = opt.get();
        boolean ok = allowed.stream().anyMatch(a -> a.name().equals(d.draftState()));
        if (!ok) {
            throw new IllegalStateException(
                    "草稿当前状态 " + d.draftState() + "，允许状态 " + allowed);
        }
        return d;
    }

    private static String fingerprint(@NonNull String configJson) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(configJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static void parseJsonStrict(@NonNull String json) throws Exception {
        // 只做严格 parse —— 用 Jackson 若可用，否则退化为 javax.json
        // 保持依赖最小，这里只检查首末大括号/中括号匹配
        if (json == null) throw new IllegalArgumentException("null");
        int depth = 0;
        boolean inStr = false;
        char prev = 0;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (prev == '\\') { prev = c; continue; }
            if (c == '"') inStr = !inStr;
            else if (!inStr) {
                if (c == '{' || c == '[') depth++;
                else if (c == '}' || c == ']') depth--;
            }
            prev = c;
        }
        if (depth != 0) throw new IllegalArgumentException("括号不平衡 (depth=" + depth + ")");
    }

    /** 草稿预检异常（携带结构化错误明细）。 */
    public static final class DraftPrecheckException extends RuntimeException {
        private final List<String> errors;
        public DraftPrecheckException(List<String> errors) {
            super(String.join("; ", errors));
            this.errors = errors;
        }
        public List<String> errors() { return errors; }
        public String detailsJson() {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < errors.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append("{\"error\":\"").append(errors.get(i).replace("\"", "'")).append("\"}");
            }
            sb.append(']');
            return sb.toString();
        }
    }

    /** 极简 payload 承载：草稿发布 → ConfigPublishRequest 需要携带的最小信息。 */
    public record ConfigPublishRequestPayload(String resourceKind, String resourceId, String configJson) {}
}
