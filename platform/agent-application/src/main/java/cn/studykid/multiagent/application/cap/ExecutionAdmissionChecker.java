package cn.studykid.multiagent.application.cap;

import java.time.Instant;
import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.core.cap.CapabilitySnapshot;
import cn.studykid.multiagent.core.cap.EffectiveClosure;
import cn.studykid.multiagent.core.cap.ReasonCode;
import cn.studykid.multiagent.ports.persistence.AuthzVersionPort;
import cn.studykid.multiagent.ports.persistence.CapabilitySnapshotPort;

/**
 * 执行准入检查（P2-T5 · §5.3 步骤 13 · §5.5 撤权与执行准入线性化）。
 *
 * <p>⚠️ <b>线性化边界 = 步骤 13 准入检查「成功通过」的时刻</b>（§5.5 方案 A 第 1 条）。
 * 本类就是那条边界的守门人：它在<b>任何外部副作用发出之前</b>，
 * 重新向权威源确认两件事仍然成立：
 * <ol>
 *   <li><b>authzVersion</b> —— 主体授权未被撤销（撤权 = 版本递增，§6.1）；</li>
 *   <li><b>闭包指纹</b> —— 本 Run 依赖的能力闭包内容未变（§6.4）。</li>
 * </ol>
 *
 * <p><b>两个契约（§5.5 第 2/3 条 · §9.1 S3b / S3f）</b>：
 * <ul>
 *   <li><b>契约 1（S3b）</b>：撤权在边界<b>前</b>提交 → 必须读到新版本 →
 *       {@code EXEC_ADMISSION_STALE}，<b>阻断新副作用</b>。</li>
 *   <li><b>契约 2（S3f）</b>：撤权在边界<b>后</b>提交 → 在途操作
 *       <b>不承诺取消</b>；本类不做任何取消动作，
 *       处置方式由调用方按「记录 executionStatus + 进对账」执行。</li>
 * </ul>
 *
 * <p>⚠️ <b>本类不做取消、不做补偿</b>：准入失败后的「cancel 审批、释放预算、PRE 标取消」
 * 是 §5.3 步骤 13 的补偿动作，由调用方（闸门）执行；
 * 本类只回答「准入是否通过」这一个二元问题，
 * 避免把「判定」与「补偿」耦合在一起而互相掩盖失败。
 *
 * <p>⚠️ <b>fail-closed</b>：读不到权威版本（{@code AUTHZ_UNAVAILABLE}）、
 * 闭包解析不出、指纹对不上 —— 一律<b>不放行</b>。
 */
public class ExecutionAdmissionChecker {

    private static final Logger log = LoggerFactory.getLogger(ExecutionAdmissionChecker.class);

    private final AuthzVersionPort authzVersions;
    private final ClosureResolver closureResolver;
    private final CapabilitySnapshotPort snapshots;

    public ExecutionAdmissionChecker(@NonNull AuthzVersionPort authzVersions,
                                     @NonNull ClosureResolver closureResolver,
                                     @NonNull CapabilitySnapshotPort snapshots) {
        this.authzVersions = authzVersions;
        this.closureResolver = closureResolver;
        this.snapshots = snapshots;
    }

    /**
     * 准入检查请求。
     *
     * @param tenantId             租户
     * @param principalId          主体（Worker 服务账号）
     * @param agentId              目标 Agent（<b>执行主体</b>：子 Agent 路径 = 子 Agent 自己）
     * @param runId                所属 Run
     * @param authzVersionAtPhaseA Phase A 判定时读到的授权版本（准入比对的基线）
     * @param fingerprintAtPhaseA  Phase A 判定时算出的闭包指纹（准入比对的基线）
     * @param agentVersion         执行主体的<b>固定 release 版本</b>（第十八批）：
     *                             {@code null} = 单 Agent 路径（当前已发布版本）；
     *                             非空 = 子 Agent 路径，闭包<b>只能</b>按这个固定版本重算。
     *                             ⚠️ 漂移到「当前已发布版本」会让「别人给子 Agent 发布新版本」
     *                             时的在途委派被判失效 —— 那是把「发布」变成了
     *                             「打断在途执行」，与「子 Agent 固定 release」直接冲突。
     */
    public record AdmissionRequest(
            @NonNull String tenantId,
            @NonNull String principalId,
            @NonNull String agentId,
            @NonNull String runId,
            long authzVersionAtPhaseA,
            @NonNull String fingerprintAtPhaseA,
            @Nullable Integer agentVersion) {

        /**
         * 兼容构造器：单 Agent 路径（闭包按当前已发布版本重算）。
         */
        public AdmissionRequest(@NonNull String tenantId,
                                @NonNull String principalId,
                                @NonNull String agentId,
                                @NonNull String runId,
                                long authzVersionAtPhaseA,
                                @NonNull String fingerprintAtPhaseA) {
            this(tenantId, principalId, agentId, runId, authzVersionAtPhaseA,
                    fingerprintAtPhaseA, null);
        }
    }

    /**
     * 准入结论。
     *
     * @param admitted         是否准入通过
     * @param reasonCode       原因码（通过时为 {@link ReasonCode#SUCCESS}）
     * @param observedAuthzVersion 准入时读到的授权版本（审计用）
     * @param observedFingerprint  准入时算出的闭包指纹（审计用）
     * @param checkedAt            准入检查时刻（§5.5 第 4 条要求记录时间）
     */
    public record AdmissionOutcome(
            boolean admitted,
            @NonNull ReasonCode reasonCode,
            long observedAuthzVersion,
            @NonNull String observedFingerprint,
            @NonNull Instant checkedAt) {

        static @NonNull AdmissionOutcome admitted(long authzVersion, @NonNull String fingerprint,
                                                   @NonNull Instant checkedAt) {
            return new AdmissionOutcome(true, ReasonCode.SUCCESS, authzVersion, fingerprint, checkedAt);
        }

        static @NonNull AdmissionOutcome rejected(@NonNull ReasonCode reasonCode, long authzVersion,
                                                   @NonNull String fingerprint,
                                                   @NonNull Instant checkedAt) {
            return new AdmissionOutcome(false, reasonCode, authzVersion, fingerprint, checkedAt);
        }
    }

    /**
     * 执行准入检查（§5.3 步骤 13）。
     *
     * @param request 检查请求
     * @return 准入结论
     */
    public @NonNull AdmissionOutcome check(@NonNull AdmissionRequest request) {
        Instant now = Instant.now();

        // ── 权威 authzVersion 强一致读 ──────────────────────────────
        long currentVersion;
        try {
            currentVersion = authzVersions
                    .require(request.tenantId(), request.principalId()).authzVersion();
        } catch (RuntimeException e) {
            // ⚠️ 版本服务不可用 → fail-closed（§5.5「缺失/超时/非法 → fail-closed」）
            log.error("准入检查：授权版本读取失败，fail-closed: tenant={}, run={}, err={}",
                    request.tenantId(), request.runId(), e.getMessage());
            return AdmissionOutcome.rejected(ReasonCode.AUTHZ_UNAVAILABLE, 0L, "", now);
        }

        // ── 契约 1：撤权在边界前提交 → 必须读到新版本 → 阻断 ──────────
        if (currentVersion > request.authzVersionAtPhaseA()) {
            log.warn("准入检查：授权版本已递增，阻断新副作用: tenant={}, run={}, phaseA={}, now={}",
                    request.tenantId(), request.runId(), request.authzVersionAtPhaseA(),
                    currentVersion);
            return AdmissionOutcome.rejected(ReasonCode.EXEC_ADMISSION_STALE,
                    currentVersion, request.fingerprintAtPhaseA(), now);
        }

        // ── 闭包指纹新鲜度 ─────────────────────────────────────────
        // ⚠️⚠️ 重算口径必须与 Phase A **一致**（第十八批）：
        //    子 Agent 路径按**固定 release** 重算（不得漂移到「当前已发布版本」——
        //    那会让「别人给子 Agent 发布新版本」把在途委派打断）。
        String currentFingerprint;
        try {
            EffectiveClosure closure = request.agentVersion() != null
                    ? closureResolver.resolve(request.tenantId(), request.agentId(),
                            request.agentVersion())
                    : closureResolver.resolveCurrent(request.tenantId(), request.agentId());
            currentFingerprint = ClosureFingerprint.of(closure);
        } catch (RuntimeException e) {
            // ⚠️ 闭包解析不出 → 无法确认指纹新鲜 → fail-closed
            log.warn("准入检查：闭包不可解析，fail-closed: tenant={}, agent={}, err={}",
                    request.tenantId(), request.agentId(), e.getMessage());
            return AdmissionOutcome.rejected(ReasonCode.EXEC_ADMISSION_STALE,
                    currentVersion, request.fingerprintAtPhaseA(), now);
        }

        if (!currentFingerprint.equals(request.fingerprintAtPhaseA())) {
            log.warn("准入检查：闭包指纹已变化，阻断新副作用: tenant={}, run={}, agent={}",
                    request.tenantId(), request.runId(), request.agentId());
            return AdmissionOutcome.rejected(ReasonCode.EXEC_ADMISSION_STALE,
                    currentVersion, currentFingerprint, now);
        }

        // ── Run 快照指纹复核 ───────────────────────────────────────
        //    快照是「本 Run 依赖的闭包」的固化证据；快照被改动同样属于失效。
        Optional<CapabilitySnapshot> snapshot =
                snapshots.find(request.tenantId(), request.runId());
        if (snapshot.isEmpty()) {
            log.warn("准入检查：Run 快照不存在，fail-closed: tenant={}, run={}",
                    request.tenantId(), request.runId());
            return AdmissionOutcome.rejected(ReasonCode.EXEC_ADMISSION_STALE,
                    currentVersion, currentFingerprint, now);
        }
        // ⚠️⚠️ 快照复核必须按「**快照自己的 Agent**」比对（第十八批）：
        //    Run 快照存的是**根 Agent** 的闭包指纹，而子 Agent 路径的 Phase A 基线
        //    是**子 Agent** 的闭包指纹 —— 两者是**不同主体**，
        //    拿它们互相比必然不等 → 子 Agent 的每一次工具调用都被判失效
        //    （EXEC_ADMISSION_STALE），多级路径在 CAP 链路上整体不可用。
        //    正确的口径：快照证明的是「**根**的依赖集没变」，
        //    就该拿**根**的当前闭包指纹去比；子 Agent 自己的新鲜性由上面那一步保证。
        String snapshotAgentId = snapshot.get().agentId();
        String snapshotFingerprint;
        if (snapshotAgentId.equals(request.agentId())) {
            // 同一执行主体（根路径的常见情形）→ 复用刚算出的当前指纹，不重复解析。
            snapshotFingerprint = currentFingerprint;
        } else {
            try {
                snapshotFingerprint = ClosureFingerprint.of(
                        closureResolver.resolveCurrent(request.tenantId(), snapshotAgentId));
            } catch (RuntimeException e) {
                log.warn("准入检查：快照 Agent 的闭包不可解析，fail-closed: tenant={}, run={}, "
                        + "snapshotAgent={}, err={}", request.tenantId(), request.runId(),
                        snapshotAgentId, e.getMessage());
                return AdmissionOutcome.rejected(ReasonCode.EXEC_ADMISSION_STALE,
                        currentVersion, currentFingerprint, now);
            }
        }
        if (!snapshot.get().configFingerprint().equals(snapshotFingerprint)) {
            log.warn("准入检查：Run 快照指纹与当前闭包不一致，阻断新副作用: tenant={}, run={}, "
                    + "snapshotAgent={}", request.tenantId(), request.runId(), snapshotAgentId);
            return AdmissionOutcome.rejected(ReasonCode.EXEC_ADMISSION_STALE,
                    currentVersion, snapshotFingerprint, now);
        }

        return AdmissionOutcome.admitted(currentVersion, currentFingerprint, now);
    }
}
