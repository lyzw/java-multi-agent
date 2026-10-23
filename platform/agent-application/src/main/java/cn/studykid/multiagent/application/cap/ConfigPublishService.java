package cn.studykid.multiagent.application.cap;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.core.cap.CapAgentDef;
import cn.studykid.multiagent.core.cap.CapMcpServerDef;
import cn.studykid.multiagent.core.cap.CapSkillDef;
import cn.studykid.multiagent.core.cap.CapToolDef;
import cn.studykid.multiagent.core.cap.ConfigObjectVersion;
import cn.studykid.multiagent.core.cap.ConfigPointer;
import cn.studykid.multiagent.core.cap.ConfigPublishCommand;
import cn.studykid.multiagent.core.cap.ConfigPublishResult;
import cn.studykid.multiagent.core.cap.ConfigRelease;
import cn.studykid.multiagent.core.cap.ConfigRollbackCommand;
import cn.studykid.multiagent.core.cap.DecisionAuditEntry;
import cn.studykid.multiagent.core.cap.ReasonCode;
import cn.studykid.multiagent.ports.persistence.CapAgentDefPort;
import cn.studykid.multiagent.ports.persistence.CapMcpServerDefPort;
import cn.studykid.multiagent.ports.persistence.CapSkillDefPort;
import cn.studykid.multiagent.ports.persistence.CapToolDefPort;
import cn.studykid.multiagent.ports.persistence.ConfigPointerPort;
import cn.studykid.multiagent.ports.persistence.ConfigPublishTransaction;
import cn.studykid.multiagent.ports.persistence.ConfigReleasePort;
import cn.studykid.multiagent.ports.persistence.DecisionAuditPort;

/**
 * 原子发布与回滚服务（§6.3 · W-PERM-P5-T1/T2/T3）。
 *
 * <p>⚠️ <b>成功条件唯一（§6.3 第 1 条）</b>：
 * 版本行插入 + release 记录 + 当前指针 CAS <b>同事务</b>提交。
 * 任一步失败整事务回滚，<b>无半成品当前发布</b>（PUB-1）。
 *
 * <p>⚠️ <b>CAS 语义（§6.3 第 3/4 条）</b>：
 * <ul>
 *   <li>基于 expected 基线更新指针；基线不符 → 整事务回滚；</li>
 *   <li>同基线并发 → 仅一次成功；失败方<b>变基重试</b>；</li>
 *   <li>指针移动<b>只</b>由 CAS 决定，<b>绝不</b>以 max(seq) 选择（PUB-2）。</li>
 * </ul>
 *
 * <p>⚠️ <b>回滚不恢复撤权（§6.3 第 6 条 / 06 §3.2）</b>：
 * 回滚只做三件事 —— 新建回滚发布记录、把原发布标记 ROLLED_BACK、CAS 切指针。
 * 它<b>绝不</b>触碰 {@code cap_authz_version} 与 {@code cap_approval}：
 * 主体授权是独立于配置发布的权威源（§5.5），
 * 把两者耦合会让「回滚配置」变成「恢复权限」—— 一条直接的越权路径。
 *
 * <p>⚠️ <b>为什么发布与回滚都写审计</b>（P5-T7）：
 * 发布/回滚是「配置生效边界」上的动作，其可观测性与调用决策同级。
 */
public class ConfigPublishService {

    private static final Logger log = LoggerFactory.getLogger(ConfigPublishService.class);

    /** 默认的最大尝试次数（首次 + 变基重试）。 */
    public static final int DEFAULT_MAX_ATTEMPTS = 3;

    private final ConfigPublishTransaction tx;
    private final ConfigPointerPort pointers;
    private final ConfigReleasePort releases;
    private final CapToolDefPort toolDefs;
    private final CapSkillDefPort skillDefs;
    private final CapMcpServerDefPort serverDefs;
    private final CapAgentDefPort agentDefs;
    private final DecisionAuditPort audit;
    private final ConfigValidationGate gate;
    private final Clock clock;
    private final int maxAttempts;

    public ConfigPublishService(@NonNull ConfigPublishTransaction tx,
                                @NonNull ConfigPointerPort pointers,
                                @NonNull ConfigReleasePort releases,
                                @NonNull CapToolDefPort toolDefs,
                                @NonNull CapSkillDefPort skillDefs,
                                @NonNull CapMcpServerDefPort serverDefs,
                                @NonNull CapAgentDefPort agentDefs,
                                @NonNull DecisionAuditPort audit,
                                @NonNull ConfigValidationGate gate,
                                @NonNull Clock clock) {
        this(tx, pointers, releases, toolDefs, skillDefs, serverDefs, agentDefs, audit, gate,
                clock, DEFAULT_MAX_ATTEMPTS);
    }

    public ConfigPublishService(@NonNull ConfigPublishTransaction tx,
                                @NonNull ConfigPointerPort pointers,
                                @NonNull ConfigReleasePort releases,
                                @NonNull CapToolDefPort toolDefs,
                                @NonNull CapSkillDefPort skillDefs,
                                @NonNull CapMcpServerDefPort serverDefs,
                                @NonNull CapAgentDefPort agentDefs,
                                @NonNull DecisionAuditPort audit,
                                @NonNull ConfigValidationGate gate,
                                @NonNull Clock clock,
                                int maxAttempts) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts 必须为正数，收到: " + maxAttempts);
        }
        this.tx = tx;
        this.pointers = pointers;
        this.releases = releases;
        this.toolDefs = toolDefs;
        this.skillDefs = skillDefs;
        this.serverDefs = serverDefs;
        this.agentDefs = agentDefs;
        this.audit = audit;
        this.gate = gate;
        this.clock = clock;
        this.maxAttempts = maxAttempts;
    }

    // ── 原子发布 ────────────────────────────────────────────────

    /**
     * <b>只跑门禁，不发布</b>（P6R4 管理端点用：申请阶段预检）。
     *
     * <p>⚠️ <b>为什么要有这个方法</b>：§6.3 的发布是「版本行 + 发布头 + 指针 CAS」的原子事务，
     * <b>不可拆</b>；而双人复核要求申请与审批是两次独立调用。
     * 若只在执行时才校验配置合法性，一个非法方案会先占住一个审批名额、
     * 让另一个人去审批一份根本执行不了的东西。
     * 故申请阶段先跑一次同样的门禁，把非法方案挡在审批之前。
     *
     * <p>⚠️ <b>预检不能替代执行时的门禁</b>：两次之间配置可能已被他人改动，
     * 故 {@link #publish(ConfigPublishCommand)} 内部仍会再跑一次权威门禁。
     *
     * @param command 待校验的发布命令
     * @throws ConfigPublishRejectedException 配置期门禁不通过
     */
    public void validateForReview(@NonNull ConfigPublishCommand command) {
        gate.validate(command);
        gate.validateNoDuplicateObjects(command);
    }

    /**
     * 原子发布（§6.3 第 1/3/4 条）。
     *
     * @param command 发布命令
     * @return 发布结果
     * @throws ConfigPublishRejectedException 配置期门禁不通过
     * @throws ConfigPublishConflictException 变基重试仍失败（并发发布持续冲突）
     */
    @NonNull
    public ConfigPublishResult publish(@NonNull ConfigPublishCommand command) {
        // ⚠️ 门禁在事务<b>之外</b>先跑：配置非法时根本不该占用事务与序列号。
        //    门禁不通过 → 抛异常 → 无任何写入发生。
        gate.validate(command);
        gate.validateNoDuplicateObjects(command);

        Long expected = command.expectedPublishSeq();
        ConfigPublishConflictException lastConflict = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            final Long baseline = expected;
            // ⚠️ for 循环变量不是事实上不可变，不能被 lambda 捕获 —— 必须先复制一份 final 副本
            final int currentAttempt = attempt;
            try {
                return tx.execute(command.tenantId(),
                        () -> doPublish(command, baseline, currentAttempt));
            } catch (ConfigPublishConflictException e) {
                // ⚠️ 变基重试（§6.3 第 4 条）：基线已被他人推进 → 读新基线再试。
                //    注意：本次事务已整体回滚，版本行与发布头都不存在，
                //    重试不会撞唯一约束。
                lastConflict = e;
                expected = currentBaseline(command.scope(), command.tenantId());
                log.info("发布 CAS 冲突，变基重试（attempt={}, 新基线={}）", attempt, expected);
            }
        }
        throw lastConflict == null
                ? new ConfigPublishConflictException(expected, "发布失败：CAS 持续冲突")
                : lastConflict;
    }

    private ConfigPublishResult doPublish(ConfigPublishCommand command, Long expected, int attempt) {
        Instant now = clock.instant();

        // ── 1. 插入本次发布的不可变新版本行（幂等：已存在则跳过）──
        for (CapToolDef tool : command.tools()) {
            toolDefs.insert(withPublished(tool, now));
        }
        for (CapSkillDef skill : command.skills()) {
            skillDefs.insert(withPublished(skill, now));
        }
        for (CapMcpServerDef server : command.mcpServers()) {
            serverDefs.insert(withPublished(server, now));
        }
        for (CapAgentDef agent : command.agents()) {
            agentDefs.insert(withPublished(agent, now));
        }

        // ── 2. 插入发布头（序号由 DB 序列分配，§6.3 第 2 条）──
        List<ConfigObjectVersion> refs = command.objectVersions();
        String objectVersionsJson = ConfigObjectVersionCodec.toJson(refs);
        String fingerprint = command.aggregateFingerprint() != null
                ? command.aggregateFingerprint()
                : ConfigObjectVersionCodec.aggregateFingerprint(refs);
        Instant effectiveAt = command.effectiveAt() == null ? now : command.effectiveAt();

        ConfigRelease pending = ConfigRelease.pending(
                command.scope(), command.tenantId(), objectVersionsJson, fingerprint,
                ConfigRelease.Status.PUBLISHED, command.requesterId(), command.approverId(),
                effectiveAt, now);
        ConfigRelease saved = releases.save(pending);

        // ── 3. CAS 移动当前指针 ──
        boolean won = pointers.compareAndSet(command.scope(), command.tenantId(), expected,
                saved.releaseId(), saved.configPublishSeq());
        if (!won) {
            // ⚠️ 抛异常 → 整事务回滚 → 本次发布的版本行与发布头一并消失。
            //    这就是 PUB-1「CAS 失败事务无半成品当前发布」的实现方式。
            throw new ConfigPublishConflictException(expected,
                    "发布指针 CAS 失败（期望基线=" + expected + "，实际发布序号="
                            + saved.configPublishSeq() + "）：整事务回滚，无半成品当前发布");
        }

        // ── 4. 审计（fail-closed：写不进去则发布不生效）──
        audit.append(DecisionAuditEntry.publication(
                command.tenantId(),
                "release:" + saved.configPublishSeq(),
                DecisionAuditEntry.Kind.PUBLISH,
                saved.configPublishSeq(), saved.releaseId(),
                command.requesterId(), "发布", now));

        ConfigPointer pointer = new ConfigPointer(
                ConfigPointer.pointerKey(command.scope(), command.tenantId()),
                command.scope(), command.tenantId(),
                saved.releaseId(), saved.configPublishSeq(), now);
        return new ConfigPublishResult(saved, pointer, attempt);
    }

    // ── 回滚 ────────────────────────────────────────────────────

    /**
     * 回滚到指定的发布（§6.3 第 6 条 / 06 §3.2）。
     *
     * <p>⚠️ <b>回滚不做变基重试</b>：
     * 发布场景下「变基重试」是 §6.3 第 4 条明文要求的（重试的是「我要发布这份配置」，
     * 目标不变）；而回滚的目标是「<b>把当前这份配置换回某个历史版本</b>」——
     * 若指针在此期间被别人推进，重试就等于<b>回滚了另一份配置</b>。
     * 故回滚遇到基线冲突直接失败，由调用方重新读取当前状态后再发起。
     *
     * @param command 回滚命令
     * @return 发布结果（{@code release} 是新建的回滚发布记录）
     * @throws ConfigPublishRejectedException 回滚目标非法
     * @throws ConfigPublishConflictException 当前指针已被他人推进
     */
    @NonNull
    public ConfigPublishResult rollback(@NonNull ConfigRollbackCommand command) {
        ConfigRelease target = releases.findByReleaseId(command.tenantId(), command.targetReleaseId())
                .orElseThrow(() -> new ConfigPublishRejectedException(
                        ReasonCode.RESOURCE_UNKNOWN, "targetReleaseId",
                        "回滚目标发布记录不存在: releaseId=" + command.targetReleaseId()));
        if (target.scope() != command.scope()) {
            throw new ConfigPublishRejectedException(ReasonCode.RESOURCE_UNKNOWN, "targetReleaseId",
                    "回滚目标的作用域与当前不一致: target=" + target.scope()
                            + ", current=" + command.scope());
        }
        if (target.status() == ConfigRelease.Status.PREPARING) {
            throw new ConfigPublishRejectedException(ReasonCode.RESOURCE_UNKNOWN, "targetReleaseId",
                    "回滚目标处于 PREPARING（未提交），不可作为回滚来源");
        }
        if (ConfigObjectVersionCodec.parse(target.objectVersions()).isEmpty()) {
            throw new ConfigPublishRejectedException(ReasonCode.RESOURCE_UNKNOWN, "targetReleaseId",
                    "回滚目标的 object_versions 不可解析，拒绝回滚到一份读不出的发布");
        }

        ConfigPointer current = pointers.current(command.scope(), command.tenantId())
                .orElseThrow(() -> new ConfigPublishRejectedException(
                        ReasonCode.RESOURCE_UNKNOWN, "scope",
                        "该作用域尚无任何发布，无可回滚对象"));
        if (!current.isPublished()) {
            throw new ConfigPublishRejectedException(ReasonCode.RESOURCE_UNKNOWN, "scope",
                    "该作用域尚无任何发布，无可回滚对象");
        }
        if (current.currentReleaseId() != null
                && current.currentReleaseId() == target.releaseId()) {
            throw new ConfigPublishRejectedException(ReasonCode.RESOURCE_UNKNOWN, "targetReleaseId",
                    "回滚目标即当前发布，无需回滚");
        }

        Long baseline = command.expectedPublishSeq() != null
                ? command.expectedPublishSeq()
                : current.currentPublishSeq();
        final Long casBaseline = baseline;
        return tx.execute(command.tenantId(), () -> doRollback(command, target, current, casBaseline));
    }

    private ConfigPublishResult doRollback(ConfigRollbackCommand command,
                                           ConfigRelease target,
                                           ConfigPointer current,
                                           Long baseline) {
        Instant now = clock.instant();

        // ── 1. 新建回滚发布记录（PUBLISHED，指向目标的对象版本集合）──
        //    ⚠️ 不覆盖、不删除任何历史记录（06 §3.2）。
        String fingerprint = command.aggregateFingerprint() != null
                ? command.aggregateFingerprint()
                : target.aggregateFingerprint();
        Instant effectiveAt = command.effectiveAt() == null ? now : command.effectiveAt();
        ConfigRelease rollbackRelease = releases.save(ConfigRelease.pending(
                command.scope(), command.tenantId(), target.objectVersions(), fingerprint,
                ConfigRelease.Status.PUBLISHED, command.requesterId(), command.approverId(),
                effectiveAt, now));

        // ── 2. 原发布记录标记 ROLLED_BACK（仅审计旁路，不参与当前选择）──
        //    ⚠️ 必须在 CAS 之前：CAS 失败时整事务回滚，标记一并撤销，
        //    不会出现「指针没动但发布记录已被标记回滚」的错账。
        if (current.currentReleaseId() != null) {
            releases.markRolledBack(command.tenantId(), current.currentReleaseId());
        }

        // ── 3. CAS 切指针 ──
        boolean won = pointers.compareAndSet(command.scope(), command.tenantId(), baseline,
                rollbackRelease.releaseId(), rollbackRelease.configPublishSeq());
        if (!won) {
            throw new ConfigPublishConflictException(baseline,
                    "回滚 CAS 失败（期望基线=" + baseline + "）：整事务回滚，"
                            + "当前发布未被改动。请重新读取当前状态后再发起回滚");
        }

        // ── 4. 审计 ──
        audit.append(DecisionAuditEntry.publication(
                command.tenantId(),
                "release:" + rollbackRelease.configPublishSeq(),
                DecisionAuditEntry.Kind.ROLLBACK,
                rollbackRelease.configPublishSeq(), rollbackRelease.releaseId(),
                command.requesterId(), "发布", now));

        ConfigPointer pointer = new ConfigPointer(
                ConfigPointer.pointerKey(command.scope(), command.tenantId()),
                command.scope(), command.tenantId(),
                rollbackRelease.releaseId(), rollbackRelease.configPublishSeq(), now);
        return new ConfigPublishResult(rollbackRelease, pointer, 1);
    }

    // ── 辅助 ────────────────────────────────────────────────────

    /**
     * 读取当前发布基线（变基重试用）。
     *
     * @param scope    作用域
     * @param tenantId 租户标识；PLATFORM 作用域为 {@code null}
     * @return 当前发布序号；尚未发布过时为 {@code null}
     */
    @Nullable
    private Long currentBaseline(ConfigRelease.@NonNull Scope scope, @Nullable String tenantId) {
        Optional<ConfigPointer> pointer = pointers.current(scope, tenantId);
        return pointer.map(ConfigPointer::currentPublishSeq).orElse(null);
    }

    /**
     * 把版本行标记为「本次发布」。
     *
     * <p>⚠️ <b>为什么由发布服务统一打 published_at</b>：
     * 「这一行已发布」是<b>发布动作</b>的产物，不是调用方预先声明的属性。
     * 若由调用方决定，未发布行就可能被写进发布头 ——
     * 那样「未发布不可被 Agent 启用」（§6.6）就只剩约定，没有机制。
     */
    private static CapToolDef withPublished(CapToolDef def, Instant publishedAt) {
        if (def.publishedAt() != null) {
            return def;
        }
        return new CapToolDef(def.id(), def.version(), def.name(), def.kind(), def.riskLevel(),
                def.mcpServerId(), def.mcpToolName(), def.inputSchema(), def.paramPolicies(),
                def.configFingerprint(), def.schemaVersion(), def.createdAt(), publishedAt);
    }

    private static CapSkillDef withPublished(CapSkillDef def, Instant publishedAt) {
        if (def.publishedAt() != null) {
            return def;
        }
        return new CapSkillDef(def.id(), def.version(), def.name(), def.description(),
                def.declaredTools(), def.declaredMcp(), def.riskCeiling(), def.paramPolicies(),
                def.requiredCaps(), def.configFingerprint(), def.schemaVersion(),
                def.createdAt(), publishedAt);
    }

    private static CapMcpServerDef withPublished(CapMcpServerDef def, Instant publishedAt) {
        if (def.publishedAt() != null) {
            return def;
        }
        return new CapMcpServerDef(def.id(), def.version(), def.transport(), def.endpoint(),
                def.command(), def.authKind(), def.trustTier(), def.exposedToolAllowlist(),
                def.connectionRisk(), def.dynamicDiscovery(), def.configFingerprint(),
                def.credentialRef(), def.schemaVersion(), def.createdAt(), publishedAt);
    }

    private static CapAgentDef withPublished(CapAgentDef def, Instant publishedAt) {
        if (def.publishedAt() != null) {
            return def;
        }
        return new CapAgentDef(def.tenantId(), def.agentId(), def.version(), def.name(),
                def.description(), def.roles(), def.enabledSkills(), def.skillBundles(),
                def.capabilityTags(), def.overrides(), def.configFingerprint(),
                def.schemaVersion(), def.createdAt(), publishedAt);
    }
}
