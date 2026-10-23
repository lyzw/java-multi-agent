package cn.studykid.multiagent.core.cap;

import java.time.Instant;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 四字段决策日志条目（§5.1 / §7.4 / 08 §7 · W-PERM-P5）。
 *
 * <p><b>本类承载 08 §7 要求的「四字段 + ledger」</b>：
 * <ul>
 *   <li>{@code authzDecision}（ALLOW / DENY）；</li>
 *   <li>{@code execDecision}（ALLOW / DENY / NOT_EVALUATED）；</li>
 *   <li>{@code execStatus}（NOT_STARTED / RUNNING / SUCCEEDED / FAILED / UNKNOWN）；</li>
 *   <li>{@code ledgerStatus}（终态是否已可靠落账，§7.4 双维度结果）；</li>
 *   <li>{@code reasonCode}（§5.7 权威原因码表）。</li>
 * </ul>
 *
 * <p>⚠️ <b>「完整率 100%」由类型系统与 DB 双重保证</b>：
 * 本类的四字段 + ledger + reasonCode <b>全部 @NonNull 且构造时校验</b>，
 * DB 侧 {@code cap_decision_log} 的对应列同样是 NOT NULL。
 * 「完整率」因此不是一个需要靠自觉维持的约定，而是**写不进去就别写**。
 *
 * <p>⚠️ <b>本条目不承载任何业务参数</b>：
 * 只有 id、决策字段与上下文标识，<b>没有</b>参数、载荷或结果内容。
 * 08 §7「日志不含明文凭据与不必要的敏感参数」因此不需要额外的过滤逻辑 ——
 * 没有可泄漏的字段（NFR-06）。凭据材料在任何阶段都不入库（06 §5）。
 *
 * @param tenantId           租户标识；平台级发布事件为 {@code null}
 * @param runId              Run 标识；{@link Kind#INVOCATION} 必填
 * @param invocationId       调用标识（与账本 operationId 同值，P2 决策 2）
 * @param authzDecision      授权结论
 * @param execDecision       执行准入结论
 * @param execStatus         副作用结果状态
 * @param ledgerStatus       终态落账状态
 * @param reasonCode         原因码
 * @param authzVersion       生成时的主体授权版本
 * @param closureFingerprint 闭包指纹
 * @param configPublishSeq   发布序号（发布类事件）
 * @param releaseId          发布头 id（发布类事件）
 * @param principalId        主体标识
 * @param toolId             工具标识
 * @param decisionKind       事件种类
 * @param phase              阶段标识（{@link ReasonCode#phase()} 语义）
 * @param createdAt          记录时间
 */
public record DecisionAuditEntry(
        @Nullable String tenantId,
        @Nullable String runId,
        @NonNull String invocationId,
        AuthorizationDecision.@NonNull Authorization authzDecision,
        AuthorizationDecision.@NonNull Execution execDecision,
        AuthorizationDecision.@NonNull Status execStatus,
        AuthorizationDecision.@NonNull Ledger ledgerStatus,
        @NonNull ReasonCode reasonCode,
        @Nullable Long authzVersion,
        @Nullable String closureFingerprint,
        @Nullable Long configPublishSeq,
        @Nullable Long releaseId,
        @Nullable String principalId,
        @Nullable String toolId,
        @NonNull Kind decisionKind,
        @NonNull String phase,
        @NonNull Instant createdAt) {

    /** 事件种类。 */
    public enum Kind {
        /** 工具调用的四字段决策（08 §7 的统计口径）。 */
        INVOCATION,
        /** 配置发布。 */
        PUBLISH,
        /** 配置回滚。 */
        ROLLBACK,
        /** 生命周期状态迁移。 */
        LIFECYCLE
    }

    public DecisionAuditEntry {
        if (invocationId == null || invocationId.isBlank()) {
            throw new IllegalArgumentException("invocationId 不能为空");
        }
        if (authzDecision == null || execDecision == null || execStatus == null
                || ledgerStatus == null || reasonCode == null) {
            throw new IllegalArgumentException("四字段 + ledger + reasonCode 均不可为空");
        }
        if (decisionKind == null) {
            throw new IllegalArgumentException("decisionKind 不能为空");
        }
        if (phase == null || phase.isBlank()) {
            throw new IllegalArgumentException("phase 不能为空");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt 不能为空");
        }
        // ⚠️ INVOCATION 必须带 runId：08 §7 第 1 条要求
        //    「每次调用（允许/拒绝/失败/未知）均可通过 runId + invocationId 追踪」。
        if (decisionKind == Kind.INVOCATION && runId == null) {
            throw new IllegalArgumentException("INVOCATION 决策日志必须携带 runId");
        }
    }

    /**
     * 由四字段决策构造审计条目。
     *
     * <p>⚠️ <b>刻意不接受任意载荷</b>：参数与结果内容不进日志（NFR-06），
     * 因此这里没有 payload 参数 —— 少一个能泄漏的字段，就少一条泄漏路径。
     *
     * @param decision           四字段决策
     * @param tenantId           租户标识
     * @param runId              Run 标识
     * @param invocationId       调用标识
     * @param authzVersion       授权版本
     * @param closureFingerprint 闭包指纹
     * @param principalId        主体标识
     * @param toolId             工具标识
     * @param createdAt          记录时间
     */
    public static @NonNull DecisionAuditEntry from(
            @NonNull AuthorizationDecision decision,
            @Nullable String tenantId,
            @Nullable String runId,
            @NonNull String invocationId,
            @Nullable Long authzVersion,
            @Nullable String closureFingerprint,
            @Nullable String principalId,
            @Nullable String toolId,
            @NonNull Instant createdAt) {
        return new DecisionAuditEntry(
                tenantId, runId, invocationId,
                decision.authorization(), decision.execution(), decision.status(),
                decision.ledger(), decision.reasonCode(),
                authzVersion, closureFingerprint,
                null, null, principalId, toolId,
                Kind.INVOCATION, decision.reasonCode().phase(), createdAt);
    }

    /**
     * 构造发布/回滚类审计条目。
     *
     * @param tenantId     租户标识；平台级为 {@code null}
     * @param invocationId 调用标识（发布事件用 {@code release:<seq>} 形式）
     * @param kind         事件种类
     * @param publishSeq   发布序号
     * @param releaseId    发布头 id
     * @param principalId  操作主体
     * @param phase        阶段标识
     * @param createdAt    记录时间
     */
    public static @NonNull DecisionAuditEntry publication(
            @Nullable String tenantId,
            @NonNull String invocationId,
            @NonNull Kind kind,
            long publishSeq,
            long releaseId,
            @Nullable String principalId,
            @NonNull String phase,
            @NonNull Instant createdAt) {
        if (kind == Kind.INVOCATION || kind == Kind.LIFECYCLE) {
            throw new IllegalArgumentException("publication() 只接受 PUBLISH / ROLLBACK");
        }
        return new DecisionAuditEntry(
                tenantId, null, invocationId,
                AuthorizationDecision.Authorization.ALLOW,
                AuthorizationDecision.Execution.ALLOW,
                AuthorizationDecision.Status.SUCCEEDED,
                AuthorizationDecision.Ledger.RECORDED,
                ReasonCode.SUCCESS,
                null, null, publishSeq, releaseId, principalId, null,
                kind, phase, createdAt);
    }
}
