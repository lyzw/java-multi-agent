package cn.studykid.multiagent.application.cap;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.core.cap.CapAgentDef;
import cn.studykid.multiagent.core.cap.CapResultContractDef;
import cn.studykid.multiagent.core.cap.CapRoutingPolicyDef;
import cn.studykid.multiagent.core.cap.ConfigLifecycleState;
import cn.studykid.multiagent.core.cap.ConfigPointer;
import cn.studykid.multiagent.core.cap.ConfigPublishCommand;
import cn.studykid.multiagent.core.cap.ConfigPublishRequest;
import cn.studykid.multiagent.core.cap.ConfigRelease;
import cn.studykid.multiagent.core.cap.ConfigRollbackCommand;
import cn.studykid.multiagent.core.orchestration.RoutingStrategy;
import cn.studykid.multiagent.ports.persistence.CapAgentDefPort;
import cn.studykid.multiagent.ports.persistence.CapResultContractPort;
import cn.studykid.multiagent.ports.persistence.CapRoutingPolicyPort;
import cn.studykid.multiagent.ports.persistence.ConfigPointerPort;
import cn.studykid.multiagent.ports.persistence.ConfigPublishRequestPort;
import cn.studykid.multiagent.ports.persistence.ConfigPublishRequestPort.Decision;
import cn.studykid.multiagent.ports.persistence.ConfigReleasePort;
import cn.studykid.multiagent.ports.persistence.Page;

/**
 * 权威配置变更的三步工作流（P6R4 · 管理端点）。
 *
 * <p>⚠️ <b>三步不可合并</b>（与既有配置变更流程同款，但对象不同）：
 * <pre>
 *   申请（request）   → PENDING   （不写配置表）
 *   审批（decide）    → APPROVED  （仍不写配置表）
 *   执行（apply）     → APPLIED   （此步才真正写入）
 * </pre>
 * 合并「审批」与「生效」会让审批人无法区分「我批了」与「配置真的变了」。
 *
 * <p>⚠️ <b>本服务只服务租户级对象（Agent 定义）</b>：
 * <ul>
 *   <li>平台级对象（Tool / Skill / MCP Server）无 {@code tenant_id}，
 *       其发布走平台运维通道（BYPASSRLS 凭据 + 带外运维工具）；</li>
 *   <li>平台身份表 {@code principal} 是<b>租户内</b>的，没有跨租户主体 ——
 *       若把平台级发布挂到租户身份上，任一租户管理员自授角色即可改全平台配置。</li>
 * </ul>
 * 故本通道遇 PLATFORM 作用域 / 非 {@code agent} 对象类型一律<b>显式拒绝</b>，
 * 而不是「尝试一下再说」。
 *
 * <p>⚠️ <b>版本号与内容指纹由服务端决定</b>：
 * 版本号 = 该 Agent 现有最大版本 + 1；内容指纹 = {@link CapObjectFingerprint} 按内容计算。
 * 客户端提交的这两者一律不采信 —— 否则「内容没变」这个证明就是客户端自己说的。
 */
public class ConfigPublishWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(ConfigPublishWorkflowService.class);

    /** 本通道可管理的对象类型（平台级对象不在此列）。 */
    public static final String TENANT_OBJECT_TYPE = "agent";

    /**
     * 本通道可做<b>生命周期迁移</b>的租户级对象类型（第二十五批 · A5）。
     *
     * <p>⚠️ <b>为什么扩到三个类型</b>：
     * 输出契约（{@code result_contract}）与路由策略（{@code routing_policy}）此前
     * <b>没有任何生命周期迁移通道</b> —— 它们只能「随 Agent 一起发布」，
     * 于是「停用一份被引用的契约/策略」在治理层<b>无法表达</b>，
     * 而 06 §3.2 明确要求「被引用对象禁止物理删除 → 改 DEPRECATED」。
     * 现在三者共用同一条生命周期迁移通道与同一套 CAS 语义。
     *
     * <p>⚠️ <b>平台级对象（tool / skill / mcp_server）仍不在此列</b>：
     * 它们无 {@code tenant_id}，其生命周期由平台运维通道管理。
     */
    public static final Set<String> TENANT_LIFECYCLE_OBJECT_TYPES =
            Set.of(TENANT_OBJECT_TYPE, "result_contract", "routing_policy");

    private final ConfigPublishRequestPort requests;
    private final ConfigPublishService publisher;
    private final ConfigLifecycleService lifecycle;
    private final CapAgentDefPort agentDefs;
    private final ConfigPointerPort pointers;
    private final ConfigReleasePort releases;
    private final CapResultContractPort contracts;
    private final CapRoutingPolicyPort routingPolicies;
    private final Clock clock;

    public ConfigPublishWorkflowService(@NonNull ConfigPublishRequestPort requests,
                                        @NonNull ConfigPublishService publisher,
                                        @NonNull ConfigLifecycleService lifecycle,
                                        @NonNull CapAgentDefPort agentDefs,
                                        @NonNull ConfigPointerPort pointers,
                                        @NonNull ConfigReleasePort releases,
                                        @NonNull Clock clock) {
        this(requests, publisher, lifecycle, agentDefs, pointers, releases, null, clock);
    }

    /**
     * 最完整构造器（多级 Agent v1.1）：额外注入输出契约仓储。
     *
     * <p>⚠️ {@code contracts} 为 {@code null} 表示「输出契约登记能力未装配」：
     * 此时提交带契约的发布申请 → <b>直接拒绝</b>（fail-closed）。
     * 静默忽略等于让「申请里写了契约」看起来像生效了，实际根本没登记。
     */
    public ConfigPublishWorkflowService(@NonNull ConfigPublishRequestPort requests,
                                        @NonNull ConfigPublishService publisher,
                                        @NonNull ConfigLifecycleService lifecycle,
                                        @NonNull CapAgentDefPort agentDefs,
                                        @NonNull ConfigPointerPort pointers,
                                        @NonNull ConfigReleasePort releases,
                                        @Nullable CapResultContractPort contracts,
                                        @NonNull Clock clock) {
        this(requests, publisher, lifecycle, agentDefs, pointers, releases, contracts, null, clock);
    }

    /**
     * 最完整构造器（多级 Agent v1.1 第十四批）：额外注入输出契约仓储与路由策略仓储。
     *
     * <p>⚠️ 两者为 {@code null} 表示「对应登记能力未装配」：
     * 此时提交带契约 / 带策略的发布申请 → <b>直接拒绝</b>（fail-closed）。
     * 静默忽略等于让「申请里写了路由策略」看起来像生效了，实际根本没登记 ——
     * 而 ROUTER Agent 的运行期路由会因此每次 fail-closed，
     * 运维看到的却是「发布成功、Agent 已生效」。
     */
    public ConfigPublishWorkflowService(@NonNull ConfigPublishRequestPort requests,
                                        @NonNull ConfigPublishService publisher,
                                        @NonNull ConfigLifecycleService lifecycle,
                                        @NonNull CapAgentDefPort agentDefs,
                                        @NonNull ConfigPointerPort pointers,
                                        @NonNull ConfigReleasePort releases,
                                        @Nullable CapResultContractPort contracts,
                                        @Nullable CapRoutingPolicyPort routingPolicies,
                                        @NonNull Clock clock) {
        this.requests = requests;
        this.publisher = publisher;
        this.lifecycle = lifecycle;
        this.agentDefs = agentDefs;
        this.pointers = pointers;
        this.releases = releases;
        this.contracts = contracts;
        this.routingPolicies = routingPolicies;
        this.clock = clock;
    }

    // ── 第一步：申请 ───────────────────────────────────────────────

    /**
     * 提交发布申请（Agent 定义）。
     *
     * @param tenantId          租户标识
     * @param agents            Agent 定义草稿
     * @param expectedPublishSeq CAS 基线（客户端读到的当前发布序号；可空）
     * @param reason            变更理由
     * @param requesterId       申请人主体标识
     * @return 申请单（PENDING）
     */
    public @NonNull ConfigPublishRequest requestPublish(
            @NonNull String tenantId,
            ConfigRelease.@NonNull Scope scope,
            @NonNull List<ConfigPublishPayload.@NonNull AgentSpec> agents,
            Long expectedPublishSeq,
            @NonNull String reason,
            @NonNull String requesterId) {
        return requestPublish(tenantId, scope, agents, List.of(), List.of(), expectedPublishSeq,
                reason, requesterId);
    }

    /**
     * 提交发布申请（Agent 定义 + 输出契约）。
     *
     * <p>⚠️ <b>契约与 Agent 同批申请</b>：输出契约是「子 Agent 输出按什么 Schema 校验」的
     * 判定依据，若契约与引用它的 Agent 分两次审批，
     * 「批的」与「生效的」就不是同一件事了。
     *
     * @param tenantId          租户标识
     * @param scope             作用域
     * @param agents            Agent 定义草稿
     * @param resultContracts   输出契约草稿
     * @param expectedPublishSeq CAS 基线（客户端读到的当前发布序号；可空）
     * @param reason            变更理由
     * @param requesterId       申请人主体标识
     * @return 申请单（PENDING）
     */
    public @NonNull ConfigPublishRequest requestPublish(
            @NonNull String tenantId,
            ConfigRelease.@NonNull Scope scope,
            @NonNull List<ConfigPublishPayload.@NonNull AgentSpec> agents,
            @NonNull List<ConfigPublishPayload.@NonNull ResultContractSpec> resultContracts,
            Long expectedPublishSeq,
            @NonNull String reason,
            @NonNull String requesterId) {
        requireTenantScope(scope);
        if (agents.isEmpty() && resultContracts.isEmpty()) {
            throw new IllegalArgumentException("发布申请不得为空");
        }
        // ⚠️ 未装配契约仓储却申请登记契约 → 拒绝（fail-closed）：
        //    否则申请单看起来带了契约，实际执行时会被静默丢掉。
        if (!resultContracts.isEmpty() && contracts == null) {
            throw new IllegalArgumentException(
                    "输出契约登记能力未装配（契约仓储缺失），拒绝提交带契约的发布申请");
        }
        ConfigPublishPayload payload = ConfigPublishPayload.publish(agents, resultContracts,
                expectedPublishSeq);
        // ⚠️ 申请阶段就跑一次门禁预检：非法方案在**申请时**就该被拒，
        //    而不是先占住一个审批名额，等审批通过后执行才失败。
        //    （执行时仍会再跑一次权威门禁 —— 两次之间配置可能已变。）
        precheck(tenantId, payload, requesterId);
        return insert(tenantId, payload, reason, requesterId);
    }

    /**
     * 提交发布申请（Agent 定义 + 输出契约 + 路由策略）。
     *
     * <p>⚠️ <b>路由策略与 Agent 同批申请</b>：ROUTER Agent 的路由行为完全由
     * {@code routingPolicyRef} 指向的策略决定，策略与 Agent 分两次审批，
     * 「批的策略」与「生效的策略」就不是同一件事 ——
     * 而路由结果恰恰是最需要复核的那部分（MA-T05 / MA-T15）。
     *
     * @param tenantId          租户标识
     * @param scope             作用域
     * @param agents            Agent 定义草稿
     * @param resultContracts   输出契约草稿
     * @param routingPolicies   路由策略草稿
     * @param expectedPublishSeq CAS 基线
     * @param reason            变更理由
     * @param requesterId       申请人主体标识
     * @return 申请单（PENDING）
     */
    public @NonNull ConfigPublishRequest requestPublish(
            @NonNull String tenantId,
            ConfigRelease.@NonNull Scope scope,
            @NonNull List<ConfigPublishPayload.@NonNull AgentSpec> agents,
            @NonNull List<ConfigPublishPayload.@NonNull ResultContractSpec> resultContracts,
            @NonNull List<ConfigPublishPayload.@NonNull RoutingPolicySpec> routingPolicies,
            Long expectedPublishSeq,
            @NonNull String reason,
            @NonNull String requesterId) {
        requireTenantScope(scope);
        if (agents.isEmpty() && resultContracts.isEmpty() && routingPolicies.isEmpty()) {
            throw new IllegalArgumentException("发布申请不得为空");
        }
        // ⚠️ 未装配契约仓储却申请登记契约 → 拒绝（fail-closed）：
        //    否则申请单看起来带了契约，实际执行时会被静默丢掉。
        if (!resultContracts.isEmpty() && contracts == null) {
            throw new IllegalArgumentException(
                    "输出契约登记能力未装配（契约仓储缺失），拒绝提交带契约的发布申请");
        }
        // ⚠️ 路由策略同口径：未装配策略仓储却申请登记策略 → 拒绝。
        if (!routingPolicies.isEmpty() && routingPolicies == null) {
            throw new IllegalArgumentException(
                    "路由策略登记能力未装配（策略仓储缺失），拒绝提交带策略的发布申请");
        }
        ConfigPublishPayload payload = ConfigPublishPayload.publish(agents, resultContracts,
                routingPolicies, expectedPublishSeq);
        precheck(tenantId, payload, requesterId);
        return insert(tenantId, payload, reason, requesterId);
    }

    /**
     * 提交回滚申请。
     *
     * @param tenantId          租户标识
     * @param scope             作用域（必须是 {@code TENANT}）
     * @param targetReleaseId   回滚目标发布 id
     * @param expectedPublishSeq CAS 基线
     * @param reason            变更理由
     * @param requesterId       申请人主体标识
     * @return 申请单（PENDING）
     */
    public @NonNull ConfigPublishRequest requestRollback(
            @NonNull String tenantId,
            ConfigRelease.@NonNull Scope scope,
            long targetReleaseId,
            Long expectedPublishSeq,
            @NonNull String reason,
            @NonNull String requesterId) {
        requireTenantScope(scope);
        ConfigPublishPayload payload = ConfigPublishPayload.rollback(targetReleaseId, expectedPublishSeq);
        return insert(tenantId, payload, reason, requesterId);
    }

    /**
     * 提交流周期迁移申请。
     *
     * @param tenantId    租户标识
     * @param scope       作用域（必须是 {@code TENANT}）
     * @param spec        迁移参数（对象类型必须是 {@code agent}）
     * @param reason      变更理由
     * @param requesterId 申请人主体标识
     * @return 申请单（PENDING）
     */
    public @NonNull ConfigPublishRequest requestLifecycle(
            @NonNull String tenantId,
            ConfigRelease.@NonNull Scope scope,
            ConfigPublishPayload.@NonNull LifecycleSpec spec,
            @NonNull String reason,
            @NonNull String requesterId) {
        requireTenantScope(scope);
        requireTenantObjectType(spec.type());
        ConfigPublishPayload payload = ConfigPublishPayload.lifecycle(spec);
        return insert(tenantId, payload, reason, requesterId);
    }

    // ── 第二步：审批 ───────────────────────────────────────────────

    /**
     * 审批（批准或拒绝）。
     *
     * @param tenantId   租户标识
     * @param requestId  申请单标识
     * @param decision   审批决定
     * @param note       审批意见
     * @param approverId 审批人主体标识（**不得等于申请人**）
     * @return 审批后的申请单
     */
    public @NonNull ConfigPublishRequest decide(@NonNull String tenantId,
                                                @NonNull String requestId,
                                                @NonNull Decision decision,
                                                String note,
                                                @NonNull String approverId) {
        return requests.decide(tenantId, requestId, decision, note, approverId);
    }

    // ── 第三步：执行 ───────────────────────────────────────────────

    /**
     * 执行已批准的变更（<b>此步才真正写入配置表</b>）。
     *
     * @param tenantId  租户标识
     * @param requestId 申请单标识
     * @param actorId   执行人主体标识
     * @return 执行后的申请单（APPLIED）
     * @throws ConfigPublishScopeNotAllowedException 作用域/对象类型不在本通道允许范围
     * @throws ConfigPublishBaselineConflictException CAS 基线已过期
     */
    public @NonNull ConfigPublishRequest apply(@NonNull String tenantId,
                                               @NonNull String requestId,
                                               @NonNull String actorId) {
        ConfigPublishRequest request = requests.find(tenantId, requestId)
                .orElseThrow(() -> new ConfigPublishRequestPort.NotFoundException(requestId));
        if (!request.isExecutable()) {
            throw new ConfigPublishRequestPort.StateConflictException(
                    "只有 APPROVED 状态可执行，当前为 " + request.state());
        }
        ConfigPublishPayload payload = ConfigPublishPayload.fromJson(request.payload());
        requireTenantScope(payload.scope());

        try {
            switch (payload.operation()) {
                case PUBLISH -> executePublish(tenantId, request, payload);
                case ROLLBACK -> executeRollback(tenantId, request, payload);
                case LIFECYCLE_TRANSITION -> executeLifecycle(tenantId, request, payload);
            }
        } catch (RuntimeException e) {
            // ⚠️ 失败必须留痕：一份「批过但没生效」的申请不能只存在于异常栈里。
            //    FAILED 是**终态** —— 不自动重试，因为重试等于让审批人批过的东西在无人参与下变形。
            try {
                requests.markFailed(tenantId, requestId, String.valueOf(e.getMessage()));
            } catch (RuntimeException markFailure) {
                log.error("变更申请失败状态回写也失败了: request={}, err={}",
                        requestId, markFailure.getMessage());
            }
            throw e;
        }
        return requests.markApplied(tenantId, requestId, actorId, clock.instant());
    }

    // ── 读 ─────────────────────────────────────────────────────────

    /**
     * 读取本租户当前的发布指针（CAS 基线的来源）。
     *
     * @param tenantId 租户标识
     * @return 当前指针；从未发布过时为空
     */
    public @NonNull Optional<ConfigPointer> currentPointer(@NonNull String tenantId) {
        return pointers.current(ConfigRelease.Scope.TENANT, tenantId);
    }

    /**
     * 列出本租户的变更申请。
     *
     * @param tenantId 租户标识
     * @param state    状态过滤
     * @param cursor   游标
     * @param limit    页大小
     * @return 分页结果
     */
    public @NonNull Page<ConfigPublishRequest> list(
            @NonNull String tenantId,
            ConfigPublishRequest.@Nullable State state,
            String cursor,
            int limit) {
        return requests.list(tenantId, state, cursor, limit);
    }

    /**
     * 读取单条变更申请（详情深链用）。
     *
     * @param tenantId   租户标识
     * @param requestId  申请单标识
     * @return 申请单；不存在时为 {@link Optional#empty()}
     */
    public @NonNull Optional<ConfigPublishRequest> findRequest(@NonNull String tenantId,
                                                               @NonNull String requestId) {
        return requests.find(tenantId, requestId);
    }

    /**
     * 列出本租户的发布历史（发布时间线 / 回滚目标选择）。
     *
     * <p>⚠️ <b>只列 TENANT 作用域</b>：与 {@link #requestPublish} 等写入路径
     * 同口径 —— 本通道只服务租户级对象，平台级发布头不属于租户时间线。
     */
    public @NonNull Page<ConfigRelease> listReleases(@NonNull String tenantId,
                                                     @Nullable String cursor,
                                                     int limit) {
        return releases.listReleases(tenantId, cursor, limit);
    }

    // ── 内部 ───────────────────────────────────────────────────────

    private ConfigPublishRequest insert(String tenantId, ConfigPublishPayload payload,
                                        String reason, String requesterId) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("变更理由不能为空");
        }
        return requests.insert(tenantId, payload.operation(), payload.toJson(), reason, requesterId);
    }

    /** 申请阶段的门禁预检。 */
    private void precheck(String tenantId, ConfigPublishPayload payload, String requesterId) {
        // ⚠️ 预检**不解析 CAS 基线**：申请阶段拒一个「基线旧了」的提案没有意义 ——
        //    申请只是一份提案，真正的基线校验发生在执行时。
        ConfigPublishCommand command = buildPublishCommand(tenantId, payload, requesterId, "system", false);
        // ⚠️ 门禁不通过会抛 ConfigPublishRejectedException —— 申请阶段就拒，
        //    不占用审批名额，也不留下「批了才发现非法」的申请单。
        publisher.validateForReview(command);
    }

    private void executePublish(String tenantId, ConfigPublishRequest request,
                                ConfigPublishPayload payload) {
        ConfigPublishCommand command = buildPublishCommand(tenantId, payload,
                request.requesterId(), request.approverId(), true);
        publisher.publish(command);
    }

    private void executeRollback(String tenantId, ConfigPublishRequest request,
                                 ConfigPublishPayload payload) {
        if (payload.targetReleaseId() == null || payload.targetReleaseId() <= 0) {
            throw new IllegalArgumentException("回滚申请缺少 targetReleaseId");
        }
        Long baseline = resolveBaseline(tenantId, payload);
        ConfigRollbackCommand command = new ConfigRollbackCommand(
                ConfigRelease.Scope.TENANT, tenantId, payload.targetReleaseId(), baseline,
                request.requesterId(), request.approverId(), null, null);
        publisher.rollback(command);
    }

    private void executeLifecycle(String tenantId, ConfigPublishRequest request,
                                  ConfigPublishPayload payload) {
        ConfigPublishPayload.LifecycleSpec spec = payload.lifecycle();
        if (spec == null) {
            throw new IllegalArgumentException("生命周期申请缺少 lifecycle 参数");
        }
        requireTenantObjectType(spec.type());
        ConfigLifecycleState from;
        ConfigLifecycleState to;
        try {
            from = ConfigLifecycleState.valueOf(spec.expectedFrom());
            to = ConfigLifecycleState.valueOf(spec.to());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("生命周期状态非法: " + e.getMessage(), e);
        }
        boolean moved = lifecycle.transition(spec.type(), tenantId, spec.objectId(),
                spec.version(), from, to);
        if (!moved) {
            // ⚠️ 原状态不符 = 并发竞争（CAS 前置条件不满足）。
            //    这不是「迁移非法」，而是「别人先动了」—— 两者必须能分开。
            throw new ConfigPublishBaselineConflictException(
                    "生命周期迁移的原状态不符：期望 " + from + "，对象 " + spec.objectId()
                            + " v" + spec.version() + " 当前不在该状态");
        }
    }

    /**
     * 构造发布命令（版本号与内容指纹在此确定）。
     */
    private ConfigPublishCommand buildPublishCommand(String tenantId, ConfigPublishPayload payload,
                                                     String requesterId, String approverId,
                                                     boolean resolveBaseline) {
        List<CapAgentDef> defs = new ArrayList<>();
        List<CapResultContractDef> contractDefs = new ArrayList<>();
        List<CapRoutingPolicyDef> policyDefs = new ArrayList<>();
        Instant now = clock.instant();
        for (ConfigPublishPayload.AgentSpec spec : payload.agents()) {
            int version = agentDefs.findLatest(tenantId, spec.agentId())
                    .map(CapAgentDef::version).orElse(0) + 1;
            CapAgentDef draft = new CapAgentDef(tenantId, spec.agentId(), version,
                    spec.name(), spec.description(), spec.roles(), spec.enabledSkills(),
                    spec.skillBundles(), spec.capabilityTags(), spec.overrides(),
                    spec.orchestration(),
                    // ⚠️ 占位指纹：内容指纹**不包含**自身，故可先占位再算
                    "pending", 1, now, null);
            String fingerprint = CapObjectFingerprint.of(draft);
            defs.add(new CapAgentDef(tenantId, spec.agentId(), version,
                    spec.name(), spec.description(), spec.roles(), spec.enabledSkills(),
                    spec.skillBundles(), spec.capabilityTags(), spec.overrides(),
                    spec.orchestration(),
                    fingerprint, 1, now, null));
        }
        // ⚠️ 契约的版本号与内容指纹**同样由服务端决定**（与 Agent 同口径）：
        //    客户端提交这两者等于让客户端指定「内容没变」这个证明。
        for (ConfigPublishPayload.ResultContractSpec spec : payload.resultContracts()) {
            int version = contracts.findLatest(tenantId, spec.contractId())
                    .map(CapResultContractDef::version).orElse(0) + 1;
            CapResultContractDef draft = new CapResultContractDef(tenantId, spec.contractId(),
                    version, spec.schemaJson(),
                    // ⚠️ 占位指纹：内容指纹**不包含**自身，故可先占位再算
                    "pending", 1, now, null);
            String fingerprint = CapObjectFingerprint.of(draft);
            contractDefs.add(new CapResultContractDef(tenantId, spec.contractId(), version,
                    spec.schemaJson(), fingerprint, 1, now, null));
        }
        // ⚠️ 路由策略的版本号与内容指纹**同样由服务端决定**（与 Agent / 契约同口径）：
        //    客户端提交这两者等于让客户端指定「内容没变」这个证明。
        for (ConfigPublishPayload.RoutingPolicySpec spec : payload.routingPolicies()) {
            int version = routingPolicies.findLatest(tenantId, spec.policyId())
                    .map(CapRoutingPolicyDef::version).orElse(0) + 1;
            RoutingStrategy strategy = parseStrategy(spec.strategy());
            CapRoutingPolicyDef draft = new CapRoutingPolicyDef(tenantId, spec.policyId(),
                    version, strategy, spec.targets(),
                    // ⚠️ 占位指纹：内容指纹**不包含**自身，故可先占位再算
                    "pending", 1, now, null);
            String fingerprint = CapObjectFingerprint.of(draft);
            policyDefs.add(new CapRoutingPolicyDef(tenantId, spec.policyId(), version, strategy,
                    spec.targets(), fingerprint, 1, now, null));
        }
        Long baseline = resolveBaseline ? resolveBaseline(tenantId, payload) : null;
        return new ConfigPublishCommand(ConfigRelease.Scope.TENANT, tenantId, baseline,
                requesterId, approverId, null,
                List.of(), List.of(), List.of(), defs, null, contractDefs, policyDefs);
    }

    /**
     * 解析路由策略的选择策略名。
     *
     * <p>⚠️ <b>大小写不敏感但不接受别名</b>：只认
     * {@code EXPLICIT_TARGETS} / {@code ORDERED_FALLBACK} 两个枚举名。
     * 「宽容地猜一个相近的名字」会让「策略写错了」变成「策略静默换了语义」——
     * 路由行为随之改变，而作者以为自己写的是另一个。
     *
     * @param raw 策略名
     * @return 选择策略
     * @throws IllegalArgumentException 策略名非法
     */
    private static @NonNull RoutingStrategy parseStrategy(@NonNull String raw) {
        try {
            return RoutingStrategy.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知的路由策略选择策略: " + raw
                    + "（合法值: EXPLICIT_TARGETS / ORDERED_FALLBACK）", e);
        }
    }

    /**
     * 解析 CAS 基线。
     *
     * <p>⚠️ 客户端给了基线就必须与当前一致 —— 不一致说明「你看到的已经不是现在的配置了」，
     * 此时若继续发布，覆盖的就是一份**对方没看过的**配置。
     * 客户端没给基线时用服务端读到的当前值（§6.3 的变基重试仍会在 CAS 失败时兜底）。
     */
    private Long resolveBaseline(String tenantId, ConfigPublishPayload payload) {
        Long current = pointers.current(ConfigRelease.Scope.TENANT, tenantId)
                .map(ConfigPointer::currentPublishSeq).orElse(null);
        Long expected = payload.expectedPublishSeq();
        if (expected != null && !expected.equals(current)) {
            throw new ConfigPublishBaselineConflictException(
                    "CAS 基线已过期：客户端读到 " + expected + "，当前为 " + current);
        }
        return current;
    }

    /** 作用域闸门：PLATFORM 作用域一律拒绝。 */
    private void requireTenantScope(ConfigRelease.Scope scope) {
        if (scope != ConfigRelease.Scope.TENANT) {
            throw new ConfigPublishScopeNotAllowedException(
                    "本通道只接受 TENANT 作用域；平台级发布走平台运维通道（BYPASSRLS 凭据 + "
                            + "带外运维工具），不经租户身份暴露。当前作用域: " + scope);
        }
    }

    /**
     * 对象类型闸门：只有<b>租户级</b>对象可在本通道做生命周期迁移。
     *
     * <p>⚠️ <b>平台级对象（tool / skill / mcp_server）一律拒绝</b>：
     * 它们无 {@code tenant_id}，走平台运维通道（BYPASSRLS 凭据 + 带外运维工具）。
     * 把它们挂到租户身份上，等于让任一租户管理员自授角色就能改全平台配置。
     */
    private void requireTenantObjectType(String type) {
        boolean allowed = TENANT_LIFECYCLE_OBJECT_TYPES.stream()
                .anyMatch(candidate -> candidate.equalsIgnoreCase(type));
        if (!allowed) {
            throw new ConfigPublishScopeNotAllowedException(
                    "本通道只管理租户级对象（" + String.join(" / ", TENANT_LIFECYCLE_OBJECT_TYPES)
                            + "）；平台级对象类型「" + type + "」走平台运维通道");
        }
    }

    /** 作用域不在本通道允许范围。 */
    public static class ConfigPublishScopeNotAllowedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ConfigPublishScopeNotAllowedException(@NonNull String message) {
            super(message);
        }
    }

    /** CAS 基线过期 / 并发竞争。 */
    public static class ConfigPublishBaselineConflictException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ConfigPublishBaselineConflictException(@NonNull String message) {
            super(message);
        }
    }
}
