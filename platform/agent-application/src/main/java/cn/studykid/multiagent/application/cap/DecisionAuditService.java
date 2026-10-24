package cn.studykid.multiagent.application.cap;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.core.cap.AuthorizationDecision;
import cn.studykid.multiagent.core.cap.DecisionAuditCoverage;
import cn.studykid.multiagent.core.cap.DecisionAuditEntry;
import cn.studykid.multiagent.core.cap.ReasonCode;
import cn.studykid.multiagent.ports.persistence.DecisionAuditPort;

/**
 * 四字段决策日志服务（§5.1 / §7.4 / 08 §7 · W-PERM-P5-T6/T7）。
 *
 * <p>⚠️ <b>本服务是「审计 100%」的执行点</b>：
 * 每次调用（允许 / 拒绝 / 失败 / 未知）都必须留下一条四字段完整的日志，
 * 且可经 {@code runId + invocationId} 追踪（08 §7 审计双重验收第 1 条）。
 *
 * <p>⚠️ <b>写入失败的分级处置</b>：
 * <ul>
 *   <li><b>未执行（NOT_STARTED）</b>的决策写不进去 → <b>抛异常</b>（fail-closed）。
 *       副作用尚未发生，此刻拒绝本次调用是安全的；</li>
 *   <li><b>已执行</b>的决策写不进去 → <b>只告警不抛</b>（fail-open）。
 *       副作用<b>已经发生</b>，此时抛异常会把「已完成」变成「失败」，
 *       上层重试就会造成<b>重复副作用</b> —— 那比缺一条审计严重得多。
 *       此时记 ERROR 日志 + 计数器，由覆盖率断言（{@link #coverage}）把缺口暴露出来。</li>
 * </ul>
 *
 * <p>⚠️ <b>本服务不接收任何业务参数</b>：
 * 日志条目里没有参数、载荷或结果内容（NFR-06 / 08 §7 第 2 条）。
 * 少一个能泄漏的字段，就少一条泄漏路径。
 */
public class DecisionAuditService {

    private static final Logger log = LoggerFactory.getLogger(DecisionAuditService.class);

    private final DecisionAuditPort port;
    private final Clock clock;

    /** 写入失败累计次数（可观测指标）。 */
    private final AtomicLong writeFailures = new AtomicLong();

    public DecisionAuditService(@NonNull DecisionAuditPort port, @NonNull Clock clock) {
        this.port = port;
        this.clock = clock;
    }

    /**
     * 记录一次调用决策。
     *
     * @param decision           四字段决策
     * @param tenantId           租户标识
     * @param runId              Run 标识
     * @param invocationId       调用标识
     * @param authzVersion       授权版本
     * @param closureFingerprint 闭包指纹
     * @param principalId        主体标识
     * @param toolId             工具标识
     */
    public void recordDecision(@NonNull AuthorizationDecision decision,
                                @NonNull String tenantId,
                                @Nullable String runId,
                                @NonNull String invocationId,
                                @Nullable Long authzVersion,
                                @Nullable String closureFingerprint,
                                @Nullable String principalId,
                                @Nullable String toolId) {
        DecisionAuditEntry entry = DecisionAuditEntry.from(
                decision, tenantId, runId, invocationId, authzVersion, closureFingerprint,
                principalId, toolId, clock.instant());
        append(entry, decision.status() == AuthorizationDecision.Status.NOT_STARTED);
    }

    /**
     * 记录一次配置发布。
     *
     * @param tenantId   租户标识；平台级为 {@code null}
     * @param publishSeq 发布序号
     * @param releaseId  发布头 id
     * @param principalId 操作主体
     */
    public void recordPublish(@Nullable String tenantId, long publishSeq, long releaseId,
                               @Nullable String principalId) {
        append(DecisionAuditEntry.publication(tenantId, "release:" + publishSeq,
                DecisionAuditEntry.Kind.PUBLISH, publishSeq, releaseId, principalId,
                "发布", clock.instant()), false);
    }

    /**
     * 记录一次配置回滚。
     *
     * @param tenantId   租户标识；平台级为 {@code null}
     * @param publishSeq 回滚发布的序号
     * @param releaseId  回滚发布头 id
     * @param principalId 操作主体
     */
    public void recordRollback(@Nullable String tenantId, long publishSeq, long releaseId,
                                @Nullable String principalId) {
        append(DecisionAuditEntry.publication(tenantId, "release:" + publishSeq,
                DecisionAuditEntry.Kind.ROLLBACK, publishSeq, releaseId, principalId,
                "发布", clock.instant()), false);
    }

    /**
     * 统计审计覆盖率（08 §7 的两条指标）。
     *
     * @param tenantId 租户标识
     * @return 覆盖率统计
     */
    @NonNull
    public DecisionAuditCoverage coverage(@NonNull String tenantId) {
        return port.coverage(tenantId);
    }

    /**
     * @return 写入失败累计次数
     */
    public long writeFailureCount() {
        return writeFailures.get();
    }

    /**
     * 追加日志条目并按决策状态分级处置失败。
     *
     * @param entry         日志条目
     * @param failClosed    写不进去时是否抛异常（未执行的决策为 {@code true}）
     */
    private void append(DecisionAuditEntry entry, boolean failClosed) {
        try {
            port.append(entry);
        } catch (RuntimeException e) {
            writeFailures.incrementAndGet();
            if (failClosed) {
                // ⚠️ 副作用尚未发生：此刻拒绝本次调用是安全的（fail-closed）。
                throw e;
            }
            // ⚠️ 副作用已发生：不能把「已完成」变成「失败」——
            //    上层重试会造成重复副作用。记 ERROR 并计数，
            //    由覆盖率断言把缺口暴露出来。
            log.error("决策日志写入失败（副作用已发生，不中断执行）: invocation={}, reason={}, err={}",
                    entry.invocationId(), entry.reasonCode().name(), e.getMessage(), e);
        }
    }

    /**
     * 判定一个原因码是否「笼统」。
     *
     * <p>⚠️ <b>只有成功码算笼统</b>：DENY 却报 SUCCESS 说明决策与原因码不自洽 ——
     * 那是一条<b>无用</b>的拒绝记录，08 §7「非笼统 DENY 占比 100%」正是要排除它。
     *
     * @param reasonCode 原因码
     * @return 是否笼统
     */
    public static boolean isNonSpecific(@NonNull ReasonCode reasonCode) {
        return reasonCode == ReasonCode.SUCCESS;
    }

    /**
     * @return 当前时间（测试可注入固定时钟）
     */
    @NonNull
    Instant now() {
        return clock.instant();
    }

    /**
     * 构造一个<b>不写任何日志</b>的实例。
     *
     * <p>⚠️ <b>仅供单元测试与尚未接入审计的旧构造器使用</b>：
     * 生产装配必须传入真实的 {@link DecisionAuditPort}
     * （{@code ApplicationConfiguration} 中的 {@code decisionAuditPort} Bean），
     * 否则「审计 100% 覆盖」将名存实亡。
     */
    @NonNull
    public static DecisionAuditService noop() {
        return new DecisionAuditService(new NoopDecisionAuditPort(), Clock.systemUTC());
    }

    /** 不写日志的端口替身。 */
    private static final class NoopDecisionAuditPort implements DecisionAuditPort {

        @Override
        public void append(@NonNull DecisionAuditEntry entry) {
            // 故意不落库：仅供单元测试使用
        }

        @Override
        @NonNull
        public List<DecisionAuditEntry> findByInvocation(@NonNull String tenantId,
                                                          @NonNull String invocationId) {
            return List.of();
        }

        @Override
        @NonNull
        public List<DecisionAuditEntry> findByRun(@NonNull String tenantId, @NonNull String runId) {
            return List.of();
        }

        @Override
        @NonNull
        public DecisionAuditCoverage coverage(@NonNull String tenantId) {
            return new DecisionAuditCoverage(0, 0, 0, 0, 0);
        }

        @Override
        public @NonNull List<DecisionAuditEntry> findRecentDenies(@NonNull String tenantId,
                                                                  @NonNull Instant since,
                                                                  int limit) {
            // 故意不返回任何记录：仅供单元测试使用
            return List.of();
        }
    }
}
