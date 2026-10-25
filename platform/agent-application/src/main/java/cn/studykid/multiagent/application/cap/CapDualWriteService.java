package cn.studykid.multiagent.application.cap;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.core.cap.CapAgentDef;
import cn.studykid.multiagent.core.cap.CapabilitySnapshot;
import cn.studykid.multiagent.core.cap.EffectiveClosure;
import cn.studykid.multiagent.ports.persistence.AuthzVersionPort;
import cn.studykid.multiagent.ports.persistence.CapAgentDefPort;
import cn.studykid.multiagent.ports.persistence.CapDualWritePort;
import cn.studykid.multiagent.ports.persistence.CapabilitySnapshotPort;

/**
 * 迁移双写实现（P0-T6 · 06 §3.3）。
 *
 * <p><b>fail-soft 是本类的第一原则</b>：双写失败<b>绝不让</b>旧路径的写入失败。
 * 旧路径是当前生产真源，让它因「顺带写一份新表」而失败，
 * 等于用迁移期的附加动作打断正在服务的业务。
 * 失败改为<b>可观测</b>：WARN 日志 + 计数器，由比对工具（P0-T7）收敛。
 *
 * <p>⚠️ <b>本类的双写是「最终一致」而非「同事务原子」</b>：
 * 旧模型写入在其自身事务内提交，双写在其后独立提交。
 * 因此存在「旧行已存在、新行未落」的窗口，
 * 这正是 06 §3.3 要求「新旧模型只读比对」的原因。
 * 原子化留待 P5 的发布事务一并解决（届时版本行插入与发布指针 CAS 本就同事务）。
 */
public class CapDualWriteService implements CapDualWritePort {

    private static final Logger log = LoggerFactory.getLogger(CapDualWriteService.class);

    private final CapDualWriteSwitch dualWriteSwitch;
    private final CapAgentDefPort agentDefs;
    private final PermissionResolver resolver;
    private final AuthzVersionPort authzVersions;
    private final CapabilitySnapshotPort snapshots;
    private final Clock clock;

    /** 双写失败计数（可观测）。 */
    private final AtomicLong failures = new AtomicLong();
    /** 因「闭包不可解析」而挂起的执行快照双写计数。 */
    private final AtomicLong deferredSnapshots = new AtomicLong();
    /** 因投影被拒（V1 硬边界）而跳过的计数。 */
    private final AtomicLong rejectedProjections = new AtomicLong();

    public CapDualWriteService(@NonNull CapDualWriteSwitch dualWriteSwitch,
                                @NonNull CapAgentDefPort agentDefs,
                                @NonNull PermissionResolver resolver,
                                @NonNull AuthzVersionPort authzVersions,
                                @NonNull CapabilitySnapshotPort snapshots,
                                @NonNull Clock clock) {
        this.dualWriteSwitch = dualWriteSwitch;
        this.agentDefs = agentDefs;
        this.resolver = resolver;
        this.authzVersions = authzVersions;
        this.snapshots = snapshots;
        this.clock = clock;
    }

    @Override
    public void onAgentVersionPublished(@NonNull String tenantId,
                                        @NonNull String agentId,
                                        int versionNumber,
                                        @Nullable String definition,
                                        @Nullable String agentName,
                                        @Nullable String agentDescription,
                                        @Nullable Instant publishedAt) {
        if (!dualWriteSwitch.isEnabled()) {
            return;
        }
        try {
            CapDefinitionProjector.Projection projection = CapDefinitionProjector.project(
                    definition, agentName, agentDescription);
            if (projection.isRejected()) {
                rejectedProjections.incrementAndGet();
                log.warn("双写跳过：定义无法投影为结构化 cap_agent_def（tenant={}, agent={}, version={}）: {}",
                        tenantId, agentId, versionNumber, projection.rejectionReason());
                return;
            }
            CapDefinitionProjector.CapAgentDefView view = projection.definition().orElseThrow();
            CapAgentDef def = new CapAgentDef(
                    tenantId, agentId, versionNumber,
                    view.name(), view.description(),
                    view.roles(), view.enabledSkills(),
                    view.skillBundles(), view.capabilityTags(),
                    view.overrides(), view.orchestration(), view.configFingerprint(),
                    1, clock.instant(), publishedAt);
            if (!agentDefs.insert(def)) {
                // ⚠️ 已存在同版本行：不可变版本行<b>不覆盖</b>（06 §1.1）。
                //    这不是错误 —— 说明该版本此前已双写过。
                log.debug("双写跳过：版本行已存在（tenant={}, agent={}, version={}）",
                        tenantId, agentId, versionNumber);
            }
        } catch (RuntimeException e) {
            // ⚠️ fail-soft：记日志 + 计数，<b>不</b>向上抛。
            failures.incrementAndGet();
            log.warn("双写失败（已降级，旧路径继续生效）: tenant={}, agent={}, version={}, 原因={}",
                    tenantId, agentId, versionNumber, e.getMessage(), e);
        }
    }

    @Override
    public void onExecutionSnapshotCreated(@NonNull String tenantId,
                                           @NonNull String runId,
                                           @NonNull String agentId,
                                           int agentVersion,
                                           @NonNull String principalId) {
        if (!dualWriteSwitch.isEnabled()) {
            return;
        }
        // ⚠️ **P1 起此处真正写 execution_capability_snapshot**。
        //
        // P0 之所以只挂起点不写，是因为三个关键字段都算不出来：
        //   · effective_closure 需要 PermissionResolver 沿 Agent→Skill→{Tool,MCP} 求交集闭包；
        //   · config_fingerprint 需要 §6.4 的真实闭包指纹算法；
        //   · authz_version 需要 DB 化版本源（强一致读）。
        // 三者在 P1 均已就位，故此处补齐。
        //
        // ⚠️ **但仍不得写「占位闭包」**：闭包算不出来时（对象无已发布版本等），
        // 一律跳过并计数，**绝不**写一条空闭包 —— 那会让「配置缺陷」被读成
        // 「该 Agent 没有任何能力」，把配置问题伪装成权限问题。
        try {
            EffectiveClosure closure = resolver.resolve(tenantId, agentId, agentVersion);
            String fingerprint = ClosureFingerprint.of(closure);
            String closureJson = ClosureFingerprint.canonicalJson(closure);
            long authzVersion = authzVersions.require(tenantId, principalId).authzVersion();

            CapabilitySnapshot snapshot = new CapabilitySnapshot(
                    runId, tenantId, agentId, closureJson, agentVersion,
                    // ⚠️ configVersion 仅作**追溯**用（P0 交付说明的决策 3.3），
                    //    不参与任何授权判定；P5 的发布流程落地后由发布序号回填。
                    0L, fingerprint, authzVersion, clock.instant());
            if (!snapshots.save(snapshot)) {
                // 幂等：同一 runId 的快照已存在 → 不覆盖（先写入者为准）
                log.debug("执行快照双写跳过：快照已存在（tenant={}, run={}）", tenantId, runId);
            }
        } catch (RuntimeException e) {
            // ⚠️ fail-soft：闭包算不出来时**不写**快照（旧路径继续生效），只计数 + 记日志。
            deferredSnapshots.incrementAndGet();
            log.debug("执行快照双写挂起（闭包不可解析或版本源不可用）: tenant={}, run={}, agent={}, 原因={}",
                    tenantId, runId, agentId, e.getMessage());
        }
    }

    /**
     * @return 双写失败累计次数（可观测指标）
     */
    public long failureCount() {
        return failures.get();
    }

    /**
     * @return 因 V1 硬边界被拒绝的投影累计次数
     */
    public long rejectedProjectionCount() {
        return rejectedProjections.get();
    }

    /**
     * @return 因闭包依赖 P1 而挂起的执行快照双写累计次数
     */
    public long deferredSnapshotCount() {
        return deferredSnapshots.get();
    }
}
