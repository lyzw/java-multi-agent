package cn.studykid.multiagent.application.cap;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.application.orchestration.AgentOrchestrationParser;
import cn.studykid.multiagent.application.orchestration.OrchestrationConfigException;
import cn.studykid.multiagent.core.cap.CapAgentDef;
import cn.studykid.multiagent.core.cap.CapMcpServerDef;
import cn.studykid.multiagent.core.cap.CapResultContractDef;
import cn.studykid.multiagent.core.cap.CapRoutingPolicyDef;
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
import cn.studykid.multiagent.core.orchestration.AgentOrchestration;
import cn.studykid.multiagent.core.orchestration.CapAgentChildRef;
import cn.studykid.multiagent.core.orchestration.ChildAgentRef;
import cn.studykid.multiagent.ports.persistence.CapAgentChildRefPort;
import cn.studykid.multiagent.ports.persistence.CapAgentDefPort;
import cn.studykid.multiagent.ports.persistence.CapMcpServerDefPort;
import cn.studykid.multiagent.ports.persistence.CapResultContractPort;
import cn.studykid.multiagent.ports.persistence.CapRoutingPolicyPort;
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

    /**
     * 多级 Agent 发布期闭包校验器（FR-023）。
     *
     * <p>⚠️ <b>可为空 = 多级校验未装配</b>：此时只有<b>声明了编排</b>的 Agent 会被拒
     * （fail-closed，见 {@link #validateMultiAgentClosure}），
     * 单 Agent 发布路径完全不受影响。
     */
    private final AgentPublishClosureValidator closureValidator;

    /**
     * 父子关系投影写入端口（06 变更补充「关系数据」）。
     *
     * <p>⚠️ <b>可为空 = 投影未装配</b>：投影表是<b>可选的关系投影</b>，
     * 权威来源始终是 release 里的 {@code childAgentRefs}。
     */
    private final CapAgentChildRefPort childRefs;

    /**
     * 输出契约仓储（多级 Agent v1.1 · FR-027 · MA-T16）。
     *
     * <p>⚠️ <b>可为空 = 输出契约登记能力未装配</b>：
     * 此时发布命令里若带契约 → <b>直接拒绝</b>（fail-closed）。
     * 静默跳过等于「发布成功但契约没落库」——
     * Agent 引用了一个从未生效的 Schema，运行期每次委派都 fail-closed，
     * 而运维看到的是「发布成功」。
     */
    private final CapResultContractPort contracts;

    /**
     * 路由策略仓储（多级 Agent v1.1 第十四批）。
     *
     * <p>⚠️ <b>可为空 = 路由策略登记能力未装配</b>：
     * 此时发布命令里若带策略 → <b>直接拒绝</b>（fail-closed）。
     * 静默跳过等于「发布成功但策略没落库」——
     * ROUTER Agent 引用了一个从未生效的路由规则，运行期每次委派都 fail-closed，
     * 而运维看到的是「发布成功」。
     */
    private final CapRoutingPolicyPort routingPolicies;

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
        this(tx, pointers, releases, toolDefs, skillDefs, serverDefs, agentDefs, audit, gate,
                clock, maxAttempts, null, null);
    }

    /**
     * 最完整构造器（多级 Agent v1.1）：额外注入发布期闭包校验器与关系投影端口。
     *
     * <p>⚠️ 两者为 {@code null} 表示「多级能力未装配」：
     * 此时声明了编排的 Agent 会在发布时被拒（fail-closed），
     * 单 Agent 发布路径行为不变。
     */
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
                                int maxAttempts,
                                @Nullable AgentPublishClosureValidator closureValidator,
                                @Nullable CapAgentChildRefPort childRefs) {
        this(tx, pointers, releases, toolDefs, skillDefs, serverDefs, agentDefs, audit, gate,
                clock, maxAttempts, closureValidator, childRefs, null);
    }

    /**
     * 最完整构造器（多级 Agent v1.1）：额外注入发布期闭包校验器、关系投影端口与输出契约仓储。
     *
     * <p>⚠️ 三者为 {@code null} 表示「多级能力未装配」：
     * 此时声明了编排的 Agent 会在发布时被拒（fail-closed），
     * 单 Agent 发布路径行为不变。
     *
     * <p>⚠️ {@code contracts} 为 {@code null} 且发布命令带契约 → <b>拒绝发布</b>：
     * 「契约登记能力没接上」不等于「契约可以随便发」。
     */
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
                                int maxAttempts,
                                @Nullable AgentPublishClosureValidator closureValidator,
                                @Nullable CapAgentChildRefPort childRefs,
                                @Nullable CapResultContractPort contracts) {
        this(tx, pointers, releases, toolDefs, skillDefs, serverDefs, agentDefs, audit, gate,
                clock, maxAttempts, closureValidator, childRefs, contracts, null);
    }

    /**
     * 最完整构造器（多级 Agent v1.1 第十四批）：额外注入发布期闭包校验器、关系投影端口、
     * 输出契约仓储与路由策略仓储。
     *
     * <p>⚠️ 四者为 {@code null} 表示「对应能力未装配」：
     * 此时声明了编排的 Agent 会在发布时被拒（fail-closed），
     * 单 Agent 发布路径行为不变。
     *
     * <p>⚠️ {@code contracts} / {@code routingPolicies} 为 {@code null}
     * 且发布命令带对应对象 → <b>拒绝发布</b>：
     * 「登记能力没接上」不等于「对象可以随便发」。
     */
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
                                int maxAttempts,
                                @Nullable AgentPublishClosureValidator closureValidator,
                                @Nullable CapAgentChildRefPort childRefs,
                                @Nullable CapResultContractPort contracts,
                                @Nullable CapRoutingPolicyPort routingPolicies) {
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
        this.closureValidator = closureValidator;
        this.childRefs = childRefs;
        this.contracts = contracts;
        this.routingPolicies = routingPolicies;
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

        // ── 1. 插入本次发布的不可变新版本行 ──────────────────────────────
        //    ⚠️⚠️ **必须检查 insert() 的返回值**（修复前的真实缺陷）：
        //    插入是 `ON CONFLICT (…, version) DO NOTHING`（不覆盖既有行），
        //    而修复前**返回值被丢弃** → 冲突被静默吞掉。
        //    叠加「版本号在事务外、无锁计算」，两个并发发布同一对象时：
        //      · A、B 都算出 version = N+1；
        //      · A 插入成功；B 的插入 DO NOTHING → **B 的内容被丢弃，无任何报错**；
        //      · B 的指针 CAS 失败 → 变基重试 → 重试复用同一 command（含同一 version）
        //        → 插入仍是 no-op → B 新建 release，object_versions 指向
        //          agent v(N+1)（**其实是 A 的内容**）→ CAS 成功。
        //    结果：**B 申请单里被审批通过的内容从未生效，运行期跑的是 A 的内容，
        //    而 publish() 返回成功** —— 典型的「假成功」。
        //
        //    ⚠️ 现在 insert() 返回 false 时**按内容指纹判定**：
        //      · 指纹相同 → 真正的幂等重放（同一内容再次发布）→ 放行；
        //      · 指纹不同 → 有人并发写了同一版本号 → **判为冲突并失败**，
        //        绝不把别人的内容当成自己的发出去。
        for (CapToolDef tool : command.tools()) {
            CapToolDef published = withPublished(tool, now);
            if (!toolDefs.insert(published)) {
                verifySameContentOnConflict("tool", tool.id(), tool.version(),
                        published.configFingerprint(),
                        toolDefs.find(tool.id(), tool.version()).map(CapToolDef::configFingerprint));
            }
        }
        for (CapSkillDef skill : command.skills()) {
            CapSkillDef published = withPublished(skill, now);
            if (!skillDefs.insert(published)) {
                verifySameContentOnConflict("skill", skill.id(), skill.version(),
                        published.configFingerprint(),
                        skillDefs.find(skill.id(), skill.version()).map(CapSkillDef::configFingerprint));
            }
        }
        for (CapMcpServerDef server : command.mcpServers()) {
            CapMcpServerDef published = withPublished(server, now);
            if (!serverDefs.insert(published)) {
                verifySameContentOnConflict("mcp_server", server.id(), server.version(),
                        published.configFingerprint(),
                        serverDefs.find(server.id(), server.version())
                                .map(CapMcpServerDef::configFingerprint));
            }
        }
        // ⚠️ 输出契约必须**在 Agent 之前**登记（多级 Agent v1.1 · FR-027）：
        //    同一次发布里新登记的契约要能被本次发布的 Agent 引用 ——
        //    若契约排在后面，闭包校验器查不到它，
        //    「一次发布同时交付契约与引用它的 Agent」就永远做不到。
        //    ⚠️ 未装配契约仓储却带契约 → 拒绝（fail-closed），绝不静默跳过。
        if (!command.resultContracts().isEmpty() && contracts == null) {
            throw new ConfigPublishRejectedException(ReasonCode.RESOURCE_UNKNOWN,
                    "resultContracts",
                    "输出契约登记能力未装配（契约仓储缺失），拒绝发布: count="
                            + command.resultContracts().size());
        }
        for (CapResultContractDef contract : command.resultContracts()) {
            CapResultContractDef published = withPublished(contract, now);
            if (!contracts.insert(published)) {
                verifySameContentOnConflict("result_contract", contract.contractId(),
                        contract.version(), published.configFingerprint(),
                        contracts.find(command.tenantId(), contract.contractId(),
                                contract.version()).map(CapResultContractDef::configFingerprint));
            }
        }
        // ⚠️ 路由策略必须**在 Agent 之前**登记（多级 Agent v1.1 第十四批）：
        //    同一次发布里新登记的策略要能被本次发布的 ROUTER Agent 引用 ——
        //    若策略排在后面，闭包校验器查不到它，
        //    「一次发布同时交付策略与引用它的 Agent」就永远做不到。
        //    ⚠️ 未装配策略仓储却带策略 → 拒绝（fail-closed），绝不静默跳过。
        if (!command.routingPolicies().isEmpty() && routingPolicies == null) {
            throw new ConfigPublishRejectedException(ReasonCode.RESOURCE_UNKNOWN,
                    "routingPolicies",
                    "路由策略登记能力未装配（策略仓储缺失），拒绝发布: count="
                            + command.routingPolicies().size());
        }
        for (CapRoutingPolicyDef policy : command.routingPolicies()) {
            CapRoutingPolicyDef published = withPublished(policy, now);
            if (!routingPolicies.insert(published)) {
                verifySameContentOnConflict("routing_policy", policy.policyId(),
                        policy.version(), published.configFingerprint(),
                        routingPolicies.find(command.tenantId(), policy.policyId(),
                                policy.version()).map(CapRoutingPolicyDef::configFingerprint));
            }
        }
        for (CapAgentDef agent : command.agents()) {
            CapAgentDef published = withPublished(agent, now);
            if (!agentDefs.insert(published)) {
                verifySameContentOnConflict("agent", agent.agentId(), agent.version(),
                        published.configFingerprint(),
                        agentDefs.find(command.tenantId(), agent.agentId(), agent.version())
                                .map(CapAgentDef::configFingerprint));
            }
        }

        // ── 1b. 多级 Agent：发布期闭包校验 + 父子关系投影 ────────────────
        //    ⚠️ 校验与投影都在**同一事务**内：任一子 Agent 引用不合法 → 抛异常 →
        //    整事务回滚 → 不留下半发布的子图或部分更新的指针（06 发布闭包算法第 6 条）。
        validateMultiAgentClosure(command);
        projectChildRefs(command, now);

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
    /**
     * 版本行插入冲突时的<b>内容一致性</b>判定。
     *
     * <p><b>⚠️ 为什么不能只看「已存在就跳过」</b>：
     * 版本行不可变，插入是 {@code ON CONFLICT DO NOTHING}——
     * 冲突既可能是「同一份内容再次发布」（幂等重放，无害），
     * 也可能是「别人并发写了同一版本号」（<b>内容不同</b>，必须失败）。
     * 二者都表现为 {@code insert() == false}，
     * <b>只有内容指纹能区分</b>。
     *
     * <p>⚠️ 冲突且<b>查不到</b>既有行时判为失败（fail-closed）：
     * 「刚说它存在、转头又查不到」是数据不一致，不能当成「没事」。
     *
     * <p>⚠️ <b>为什么抛 {@link ConfigPublishRejectedException} 而不是
     * {@link ConfigPublishConflictException}</b>：
     * 后者会被 {@link #publish} 的<b>变基重试</b>捕获并重试<b>同一份命令</b>——
     * 而版本号冲突与 CAS 基线无关，重试必然再次撞同一个版本号，
     * 徒劳耗尽重试次数，还把「内容冲突（要重新读取状态）」误报成「并发竞争（稍后重试）」。
     * 版本号被占用是<b>配置层</b>的问题：调用方须重新读取当前状态后再发布。
     *
     * @param type        对象类型（tool / skill / mcp_server / agent）
     * @param objectId    对象逻辑 id
     * @param version     版本号
     * @param fingerprint 本次要发布的内容指纹
     * @param existingFingerprint 既有版本行的内容指纹（{@link Optional#empty()} = 查不到）
     * @throws ConfigPublishRejectedException 内容不一致 → 判定为版本冲突
     */
    private static void verifySameContentOnConflict(@NonNull String type,
                                                     @NonNull String objectId,
                                                     int version,
                                                     @NonNull String fingerprint,
                                                     @NonNull Optional<String> existingFingerprint) {
        if (existingFingerprint.isEmpty()) {
            throw new ConfigPublishRejectedException(ReasonCode.RESOURCE_VERSION_MISMATCH, type,
                    "版本行插入冲突但查不到既有行（数据不一致，拒绝发布）: id=" + objectId
                            + ", version=" + version);
        }
        String existing = existingFingerprint.get();
        if (!existing.equals(fingerprint)) {
            throw new ConfigPublishRejectedException(ReasonCode.RESOURCE_VERSION_MISMATCH, type,
                    "版本行冲突且内容不一致（拒绝发布：本次内容会被静默丢弃，"
                            + "而发布却会报成功）: id=" + objectId
                            + ", version=" + version
                            + ", existing=" + existing + ", incoming=" + fingerprint
                            + "。处置：重新读取当前状态后再发布（内容与版本号需重新分配）");
        }
        // ⚠️ 指纹一致 → 真正的幂等重放（同一份内容再次发布）→ 放行。
        log.debug("版本行已存在且内容一致，按幂等重放处理: type={}, id={}, version={}",
                type, objectId, version);
    }

    private static CapToolDef withPublished(CapToolDef def, Instant publishedAt) {
        if (def.publishedAt() != null) {
            return def;
        }
        return new CapToolDef(def.id(), def.version(), def.name(), def.kind(), def.riskLevel(),
                def.mcpServerId(), def.mcpToolName(), def.httpConfig(),
                def.inputSchema(), def.paramPolicies(),
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

    // ── 多级 Agent（v1.1）────────────────────────────────────────

    /**
     * 多级 Agent 的发布期闭包校验（FR-023 · MA-T02～T04）。
     *
     * <p>⚠️ <b>未装配校验器却要发布多级 Agent → 直接拒绝</b>（fail-closed）：
     * 「校验器没接上」不等于「校验通过」。放行等于让未校验的委派图上线，
     * 环与未发布引用会一直留到运行期才爆。
     */
    private void validateMultiAgentClosure(@NonNull ConfigPublishCommand command) {
        for (CapAgentDef agent : command.agents()) {
            AgentOrchestration orchestration = parseOrchestration(agent);
            // ⚠️ 跳过条件是「**纯 LEAF 且不声明输出契约 / 路由策略**」，
            //    不是「LEAF」：
            //    LEAF 也可以声明 resultContractRef（约束自身输出），
            //    而契约引用同样必须在发布期校验 ——
            //    否则「引用了根本不存在 / 未发布契约的 Agent」可以被发布出去，
            //    运行期每次委派都因「取不到契约」fail-closed，
            //    而运维看到的是「发布成功、Agent 已生效」。
            //    ⚠️ 路由策略同理（第十四批）：LEAF 声明 routingPolicyRef 是配置缺陷
            //    （策略对 LEAF 毫无作用），也必须在发布期被拒 ——
            //    若这里漏掉，LEAF 就能带着一条永不生效的「路由规则」发布出去。
            boolean declaresContract = orchestration.resultContractRef() != null;
            boolean declaresPolicy = orchestration.routingPolicyRef() != null;
            if (orchestration.isLeaf() && !declaresContract && !declaresPolicy) {
                continue;
            }
            if (closureValidator == null) {
                throw new ConfigPublishRejectedException(ReasonCode.ORCHESTRATION_INVALID,
                        "orchestration",
                        "多级 Agent 发布能力未装配（发布期闭包校验器缺失），拒绝发布: agent="
                                + agent.agentId() + " v" + agent.version());
            }
            closureValidator.validate(command.tenantId(), agent);
        }
    }

    /**
     * 写入父子关系投影（06 变更补充「关系数据」· 评审前置决策 4：<b>存储</b>）。
     *
     * <p>⚠️ <b>投影不是权威来源</b>：权威定义是 release 里的 {@code childAgentRefs}，
     * 本表只是把边投影出来便于查询与分析，可重建。
     *
     * <p>⚠️ 写入幂等：同一父 release 重复投影同一子 release 不产生第二行。
     *
     * <p>⚠️ <b>决策 4 落地：投影未装配即 fail-closed</b>（2026-10-25 · zhouwei 批准
     * 「{@code cap_agent_child_ref} 存储」）。此前这里是
     * {@code childRefs == null → 直接 return}（静默跳过），
     * 后果是「Agent 声明了子 Agent、发布成功、但关系边一条没落」——
     * <b>关系真相缺失</b>：影响分析与删除守卫都靠这张表回答
     * 「谁依赖我」，表是空的就等于「没人依赖我」，
     * 于是「被引用对象被抽走」的事故一路畅通。
     * ⚠️ <b>判据是「本次发布是否带了子引用」，不是「仓储有没有装配」</b>：
     * 没有子引用就没什么可投影（不该因此拒绝一次纯 Agent 发布），
     * 但**带了**子引用却落不了库 = 必须拒绝。
     */
    private void projectChildRefs(@NonNull ConfigPublishCommand command, @NonNull Instant now) {
        // ⚠️ 先把本次要投影的边**全部**算出来，再决定能不能落库 ——
        //    「有没有边」与「能不能落库」是两个问题，混在一起判就会出现
        //    「仓储没装配 → 一条边都不投影 → 看起来发布成功了」的静默失败。
        List<CapAgentChildRef> edges = new ArrayList<>();
        for (CapAgentDef agent : command.agents()) {
            AgentOrchestration orchestration = parseOrchestration(agent);
            int ordinal = 0;
            for (ChildAgentRef ref : orchestration.childAgentRefs()) {
                edges.add(new CapAgentChildRef(command.tenantId(), agent.agentId(),
                        agent.version(), ref.agentId(), ref.releaseVersion(),
                        ordinal++, ref.required(), now));
            }
        }
        if (edges.isEmpty()) {
            return;
        }
        if (childRefs == null) {
            throw new ConfigPublishRejectedException(ReasonCode.ORCHESTRATION_INVALID,
                    "orchestration",
                    "父子关系投影未装配（关系投影仓储缺失），拒绝发布带子 Agent 引用的 Agent："
                            + "关系边落不了库，「谁依赖谁」就查不出来，"
                            + "影响分析与删除守卫会因此放行「被引用对象被抽走」的事故");
        }
        for (CapAgentChildRef edge : edges) {
            childRefs.insert(edge);
        }
    }

    private static @NonNull AgentOrchestration parseOrchestration(@NonNull CapAgentDef agent) {
        try {
            return AgentOrchestrationParser.parse(agent.orchestration());
        } catch (OrchestrationConfigException e) {
            throw new ConfigPublishRejectedException(ReasonCode.ORCHESTRATION_INVALID,
                    "orchestration",
                    "Agent " + agent.agentId() + " v" + agent.version() + " 的编排声明非法: "
                            + e.getMessage());
        }
    }

    /**
     * 补上输出契约的发布时间（草稿 → 已发布）。
     *
     * <p>⚠️ <b>发布时间是发布流给的，不是登记流给的</b>：
     * 契约与 Agent 一样，走「申请 → 审批 → 执行」三步，
     * 「已发布」这个事实必须由**发布事务**授予 ——
     * 否则「先登记成已发布、再补审批」就绕过了发布流程。
     */
    private static CapResultContractDef withPublished(CapResultContractDef def,
                                                      Instant publishedAt) {
        if (def.publishedAt() != null) {
            return def;
        }
        return new CapResultContractDef(def.tenantId(), def.contractId(), def.version(),
                def.schemaJson(), def.configFingerprint(), def.schemaVersion(),
                def.createdAt(), publishedAt);
    }

    /**
     * 补上路由策略的发布时间（草稿 → 已发布）。
     *
     * <p>⚠️ <b>发布时间是发布流给的，不是登记流给的</b>：
     * 策略与 Agent、契约一样，走「申请 → 审批 → 执行」三步，
     * 「已发布」这个事实必须由<b>发布事务</b>授予 ——
     * 否则「先登记成已发布、再补审批」就绕过了发布流程，
     * 而「已发布」正是「可被引用」的前置条件。
     */
    private static CapRoutingPolicyDef withPublished(CapRoutingPolicyDef def,
                                                     Instant publishedAt) {
        if (def.publishedAt() != null) {
            return def;
        }
        return new CapRoutingPolicyDef(def.tenantId(), def.policyId(), def.version(),
                def.strategy(), def.targets(), def.configFingerprint(), def.schemaVersion(),
                def.createdAt(), publishedAt);
    }

    private static CapAgentDef withPublished(CapAgentDef def, Instant publishedAt) {        if (def.publishedAt() != null) {
            return def;
        }
        return new CapAgentDef(def.tenantId(), def.agentId(), def.version(), def.name(),
                def.description(), def.roles(), def.enabledSkills(), def.skillBundles(),
                def.capabilityTags(), def.overrides(), def.orchestration(),
                def.configFingerprint(),
                def.schemaVersion(), def.createdAt(), publishedAt);
    }
}
