package cn.studykid.multiagent.application.cap;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.cap.CapAgentDef;
import cn.studykid.multiagent.core.cap.CapHttpToolConfig;
import cn.studykid.multiagent.core.cap.CapMcpServerDef;
import cn.studykid.multiagent.core.cap.CapSkillDef;
import cn.studykid.multiagent.core.cap.CapToolDef;
import cn.studykid.multiagent.core.cap.CapToolKind;
import cn.studykid.multiagent.core.cap.CapabilityRiskLevel;
import cn.studykid.multiagent.core.cap.ConfigLifecycleState;
import cn.studykid.multiagent.core.cap.ConfigPointer;
import cn.studykid.multiagent.core.cap.ConfigPublishCommand;
import cn.studykid.multiagent.core.cap.ConfigPublishRequest;
import cn.studykid.multiagent.core.cap.SkillDescription;
import cn.studykid.multiagent.core.cap.ConfigRelease;
import cn.studykid.multiagent.core.cap.McpTrustTier;
import cn.studykid.multiagent.core.cap.PublishPolicy.PublishMode;
import cn.studykid.multiagent.core.cap.ReasonCode;
import cn.studykid.multiagent.application.cap.PublishPolicyService.ResourceRef;
import cn.studykid.multiagent.ports.persistence.CapAgentDefPort;
import cn.studykid.multiagent.ports.persistence.CapMcpServerDefPort;
import cn.studykid.multiagent.ports.persistence.CapSkillDefPort;
import cn.studykid.multiagent.ports.persistence.CapToolDefPort;
import cn.studykid.multiagent.ports.persistence.ConfigPointerPort;
import cn.studykid.multiagent.ports.persistence.ConfigPublishRequestPort;
import cn.studykid.multiagent.ports.persistence.Page;
import cn.studykid.multiagent.ports.persistence.PlatformOpsSessionPort;

/**
 * 平台级能力对象（Tool / Skill / MCP Server）的登记与生命周期服务
 * （第三十四批 · C34-2 · PLATFORM 作用域）。
 *
 * <p>⚠️ <b>为什么平台级变更也走「申请 → 审批 → 执行」三步</b>：
 * 若把申请人与审批人两个 id 放进**一次**请求由调用方填写，
 * 双人复核就退化成「调用方自己声明另一个人同意了」—— 那不是复核，是自证
 * （V18 迁移注释的原话）。平台级对象影响**全平台**，比租户级更需要复核。
 *
 * <p>⚠️ <b>为什么本类与租户通道（{@link ConfigPublishWorkflowService}）彻底分开</b>：
 * <ul>
 *   <li>租户通道走<b>应用角色</b>连接，写 PLATFORM 行会被 RLS 拒写
 *       （刻意的隔离：任一租户都不能单方面替换全平台生效配置）；</li>
 *   <li>平台通道走<b>平台运维角色</b>（BYPASSRLS）连接，
 *       是唯一能写 PLATFORM 行与 PLATFORM 申请单的通道。</li>
 * </ul>
 * 两条通道<b>没有共享的凭据</b>，也不存在「一个服务对象按 scope 换池」的分叉。
 *
 * <p>⚠️ <b>fail-closed</b>：运维角色未接入时，平台级操作<b>明确失败</b>，
 * <b>绝不</b>回落到应用角色去写平台级数据 ——
 * 那样的失败现场是「RLS 策略拒写」，离「这个环境没配运维凭据」这一真因极远。
 *
 * <p>⚠️ <b>版本号与内容指纹由服务端决定</b>：
 * 版本号 = 该对象现有最大版本 + 1；内容指纹 = {@link CapObjectFingerprint} 按内容计算。
 * 客户端提交的这两者一律不采信 —— 否则「内容没变」这个证明就是客户端自己说的。
 * 且<b>内容与现有最新版本完全一致（无实际修改）时拒绝发布、不新增版本</b>：
 * 版本号只随真实内容变化递增。
 *
 * <p>⚠️ <b>生命周期迁移与发布不同源</b>：
 * 停用（{@code PUBLISHED → DEPRECATED}）走 {@link ConfigLifecycleService}，
 * 它写的是对象自己的状态列，<b>不产生发布头、不移动指针</b>；
 * 而「发布」只经由「发布新版本行 + 切换当前指针」达成。
 * 两者刻意不合并 —— 合并后「停用」会顺带移动全局指针，
 * 一次误操作就能把全平台的当前配置切走。
 */
public class PlatformCapabilityPublishService {

    private static final Logger log = LoggerFactory.getLogger(PlatformCapabilityPublishService.class);

    /**
     * 本服务可做生命周期迁移的平台级对象类型。
     *
     * <p>⚠️ {@code agent} 是<b>全局实体</b>（cap_agent_def 无 tenant_id，租户可用性由
     * tenant_agent_binding 决定），停用（DEPRECATED）一律走平台通道：
     * {@code ConfigLifecycleMapper} 的 agent 分支本就按 {@code (agent_id, version)}
     * 全局寻址、无 tenant_id 谓词 —— 传 {@code tenantId = null} 即可。
     */
    public static final Set<String> PLATFORM_LIFECYCLE_OBJECT_TYPES =
            Set.of("tool", "skill", "mcp_server", "agent");

    /** 变更前快照的序列化器（Jackson 3，与 ConfigPublishPayload 同源）。 */
    private static final tools.jackson.databind.ObjectMapper SNAPSHOT_MAPPER =
            new tools.jackson.databind.ObjectMapper();

    private final PlatformOpsSessionPort session;
    private final ConfigPublishRequestPort requests;
    private final ConfigPublishService publisher;
    private final ConfigLifecycleService lifecycle;
    private final CapToolDefPort toolDefs;
    private final CapSkillDefPort skillDefs;
    private final CapMcpServerDefPort serverDefs;
    private final ConfigPointerPort pointers;
    private final Clock clock;

    /** 全局 Agent 定义端口；仅 {@code requestPublishAgents} 使用，未装配时该方法 fail-closed。 */
    private final @Nullable CapAgentDefPort agentDefs;

    /** 发布策略服务；为 null 时沿用旧的「一律等人工审批」行为（仅单测构造使用）。 */
    private final @Nullable PublishPolicyService policies;

    /**
     * @param session    平台运维会话（提供受守卫的运维连接窗口）
     * @param requests   申请单仓储（<b>必须</b>用平台运维连接实现 ——
     *                   申请单的 RLS 读写策略是严格相等，应用连接读不到平台行）
     * @param publisher  平台版发布服务（运维事务 + 运维通道的发布端口）
     * @param lifecycle  生命周期服务（平台对象表无 RLS，但闸门在 API 层）
     * @param toolDefs   Tool 定义仓储（算版本号）
     * @param skillDefs  Skill 定义仓储（算版本号）
     * @param serverDefs MCP Server 定义仓储（算版本号）
     * @param pointers   当前发布指针（读 PLATFORM 指针的唯一通道）
     * @param clock      时钟
     */
    public PlatformCapabilityPublishService(@NonNull PlatformOpsSessionPort session,
                                            @NonNull ConfigPublishRequestPort requests,
                                            @NonNull ConfigPublishService publisher,
                                            @NonNull ConfigLifecycleService lifecycle,
                                            @NonNull CapToolDefPort toolDefs,
                                            @NonNull CapSkillDefPort skillDefs,
                                            @NonNull CapMcpServerDefPort serverDefs,
                                            @NonNull ConfigPointerPort pointers,
                                            @NonNull Clock clock) {
        this(session, requests, publisher, lifecycle, toolDefs, skillDefs, serverDefs,
                pointers, clock, null);
    }

    /**
     * 全参构造：额外注入全局 Agent 定义端口，使平台通道可发布全局 Agent。
     *
     * <p>{@code agentDefs} 为 {@code null} 时，{@link #requestPublishAgents} fail-closed，
     * 既有 Tool/Skill/MCP 发布路径行为不变。
     */
    public PlatformCapabilityPublishService(@NonNull PlatformOpsSessionPort session,
                                            @NonNull ConfigPublishRequestPort requests,
                                            @NonNull ConfigPublishService publisher,
                                            @NonNull ConfigLifecycleService lifecycle,
                                            @NonNull CapToolDefPort toolDefs,
                                            @NonNull CapSkillDefPort skillDefs,
                                            @NonNull CapMcpServerDefPort serverDefs,
                                            @NonNull ConfigPointerPort pointers,
                                            @NonNull Clock clock,
                                            @Nullable CapAgentDefPort agentDefs) {
        this(session, requests, publisher, lifecycle, toolDefs, skillDefs, serverDefs,
                pointers, clock, agentDefs, null);
    }

    /**
     * 全参构造（含发布策略分流）：生产装配使用。
     *
     * <p>策略服务为 {@code null} 时沿用旧行为（等人工审批）—— 仅既有状态机单测使用该路径。
     */
    public PlatformCapabilityPublishService(@NonNull PlatformOpsSessionPort session,
                                            @NonNull ConfigPublishRequestPort requests,
                                            @NonNull ConfigPublishService publisher,
                                            @NonNull ConfigLifecycleService lifecycle,
                                            @NonNull CapToolDefPort toolDefs,
                                            @NonNull CapSkillDefPort skillDefs,
                                            @NonNull CapMcpServerDefPort serverDefs,
                                            @NonNull ConfigPointerPort pointers,
                                            @NonNull Clock clock,
                                            @Nullable CapAgentDefPort agentDefs,
                                            @Nullable PublishPolicyService policies) {
        this.session = session;
        this.requests = requests;
        this.publisher = publisher;
        this.lifecycle = lifecycle;
        this.toolDefs = toolDefs;
        this.skillDefs = skillDefs;
        this.serverDefs = serverDefs;
        this.pointers = pointers;
        this.clock = clock;
        this.agentDefs = agentDefs;
        this.policies = policies;
    }

    // ── 第一步：申请 ───────────────────────────────────────────────

    /**
     * 提交平台级发布申请（Tool / Skill / MCP Server）。
     *
     * @param tools              Tool 定义草稿
     * @param skills             Skill 定义草稿
     * @param mcpServers         MCP Server 定义草稿
     * @param expectedPublishSeq CAS 基线（客户端读到的当前发布序号；可空）
     * @param reason             变更理由
     * @param requesterId        申请人主体标识
     * @return 申请单（PENDING）
     */
    public @NonNull ConfigPublishRequest requestPublish(
            @NonNull List<ConfigPublishPayload.@NonNull ToolSpec> tools,
            @NonNull List<ConfigPublishPayload.@NonNull SkillSpec> skills,
            @NonNull List<ConfigPublishPayload.@NonNull McpServerSpec> mcpServers,
            @Nullable Long expectedPublishSeq,
            @NonNull String reason,
            @NonNull String requesterId) {
        if (tools.isEmpty() && skills.isEmpty() && mcpServers.isEmpty()) {
            throw new IllegalArgumentException("发布申请不得为空");
        }
        ConfigPublishPayload payload = ConfigPublishPayload.publishPlatform(
                tools, skills, mcpServers, expectedPublishSeq);
        // ⚠️ 申请阶段就跑一次门禁预检：非法方案在**申请时**就该被拒，
        //    而不是先占住一个审批名额，等审批通过后执行才失败。
        publisher.validateForReview(buildCommand(payload, requesterId, "system", false));
        return autoRoute(insert(payload, reason, requesterId), refsOf(payload), requesterId);
    }

    /**
     * 提交<b>全局 Agent</b> 定义发布申请（PLATFORM 作用域，无 tenantId）。
     *
     * <p>⚠️ 用于「运营新建 Agent 且未指定初始绑定租户」的场景：Agent 是全局实体，
     * 尚未绑定租户时无法走 TENANT 作用域发布，改用平台运维通道。
     * 版本号按全局定义的最大版本 + 1、内容指纹由服务端计算（均不采信客户端）。
     *
     * @param agents              Agent 定义草稿
     * @param expectedPublishSeq  CAS 基线（可空）
     * @param reason              变更理由
     * @param requesterId         申请人主体标识
     * @return 申请单（PENDING）
     */
    public @NonNull ConfigPublishRequest requestPublishAgents(
            @NonNull List<ConfigPublishPayload.@NonNull AgentSpec> agents,
            @Nullable Long expectedPublishSeq,
            @NonNull String reason,
            @NonNull String requesterId) {
        if (agents.isEmpty()) {
            throw new IllegalArgumentException("全局 Agent 发布申请不得为空");
        }
        if (agentDefs == null) {
            throw new IllegalStateException(
                    "平台通道未装配全局 Agent 定义端口，拒绝发布全局 Agent（fail-closed）");
        }
        ConfigPublishPayload payload =
                ConfigPublishPayload.publishPlatformAgents(agents, expectedPublishSeq);
        // 申请期就跑门禁预检（形状 / 引用），非法方案在申请时即拒，不占审批名额。
        publisher.validateForReview(buildAgentCommand(payload, requesterId, "system", false));
        return autoRoute(insert(payload, reason, requesterId), refsOf(payload), requesterId);
    }

    /**
     * 提交平台级生命周期迁移申请（停用 / 回退）。
     *
     * @param spec        迁移参数（对象类型见 {@link #PLATFORM_LIFECYCLE_OBJECT_TYPES}；
     *                    {@code agent} 为全局实体，按 (agent_id, version) 全局寻址）
     * @param reason      变更理由
     * @param requesterId 申请人主体标识
     * @return 申请单（PENDING）
     */
    public @NonNull ConfigPublishRequest requestLifecycle(
            ConfigPublishPayload.@NonNull LifecycleSpec spec,
            @NonNull String reason,
            @NonNull String requesterId) {
        requirePlatformObjectType(spec.type());
        ConfigPublishPayload payload = ConfigPublishPayload.lifecycle(spec);
        return autoRoute(insert(payload, reason, requesterId), refsOf(payload), requesterId);
    }

    // ── 第二步：审批 ───────────────────────────────────────────────

    /**
     * 审批（批准或拒绝）。
     *
     * @param requestId  申请单标识
     * @param decision   审批决定
     * @param note       审批意见
     * @param approverId 审批人主体标识（**不得等于申请人**）
     * @return 审批后的申请单
     */
    public @NonNull ConfigPublishRequest decide(@NonNull String requestId,
                                                ConfigPublishRequestPort.@NonNull Decision decision,
                                                @Nullable String note,
                                                @NonNull String approverId) {
        ConfigPublishRequest updated = session.inPlatformScopeSession(
                () -> requests.decide(null, requestId, decision, note, approverId));
        // APPROVAL_AUTO：人工审批通过后自动执行。
        if (policies == null || decision != ConfigPublishRequestPort.Decision.APPROVE
                || updated.state() != ConfigPublishRequest.State.APPROVED) {
            return updated;
        }
        ConfigPublishPayload payload = ConfigPublishPayload.fromJson(updated.payload());
        PublishMode mode = policies.resolveForResources("PLATFORM", null, refsOf(payload));
        if (mode == PublishMode.APPROVAL_AUTO) {
            log.info("策略 APPROVAL_AUTO：平台申请 {} 审批通过，自动执行", requestId);
            return apply(requestId, approverId);
        }
        return updated;
    }

    // ── 第三步：执行 ───────────────────────────────────────────────

    /**
     * 执行已批准的变更（<b>此步才真正写入平台配置</b>）。
     *
     * @param requestId 申请单标识
     * @param actorId   执行人主体标识
     * @return 执行后的申请单（APPLIED）
     */
    public @NonNull ConfigPublishRequest apply(@NonNull String requestId,
                                               @NonNull String actorId) {
        return session.inPlatformScopeSession(() -> {
            ConfigPublishRequest request = requests.find(null, requestId)
                    .orElseThrow(() -> new ConfigPublishRequestPort.NotFoundException(requestId));
            if (!request.isExecutable()) {
                throw new ConfigPublishRequestPort.StateConflictException(
                        "只有 APPROVED 状态可执行，当前为 " + request.state());
            }
            ConfigPublishPayload payload = ConfigPublishPayload.fromJson(request.payload());
            try {
                switch (payload.operation()) {
                    case PUBLISH -> publisher.publish(
                            payload.scope() == ConfigRelease.Scope.PLATFORM
                                    && !payload.agents().isEmpty()
                                    ? buildAgentCommand(payload, request.requesterId(),
                                            request.approverId(), true)
                                    : buildCommand(payload, request.requesterId(),
                                            request.approverId(), true));
                    case LIFECYCLE_TRANSITION -> executeLifecycle(request, payload);
                    case ROLLBACK -> throw new ConfigPublishRejectedException(
                            ReasonCode.RESOURCE_UNKNOWN, "operation",
                            "平台作用域的回滚尚未提供（回滚目标要按发布历史定位，"
                                    + "而平台发布历史当前不可读）—— 登记为缺口，不做半实现");
                }
            } catch (RuntimeException e) {
                // ⚠️ 失败必须留痕：一份「批过但没生效」的申请不能只存在于异常栈里。
                try {
                    requests.markFailed(null, requestId, String.valueOf(e.getMessage()), null, null);
                } catch (RuntimeException markFailure) {
                    // ⚠️ 留痕失败不能掩盖原始异常：原始异常才是调用方要知道的。
                    markFailure.addSuppressed(e);
                }
                throw e;
            }
            return requests.markApplied(null, requestId, actorId, clock.instant());
        });
    }

    // ── 读 ─────────────────────────────────────────────────────────

    /**
     * 读取平台级变更申请。
     *
     * @param requestId 申请单标识
     * @return 申请单；不存在时为空
     */
    public @NonNull Optional<ConfigPublishRequest> findRequest(@NonNull String requestId) {
        return session.inPlatformScopeSession(() -> requests.find(null, requestId));
    }

    /**
     * 列出平台级变更申请。
     *
     * @param state  状态过滤；{@code null} = 不过滤
     * @param cursor 游标；{@code null} = 从头开始
     * @param limit  页大小
     * @return 分页结果
     */
    public @NonNull Page<ConfigPublishRequest> list(
            ConfigPublishRequest.@Nullable State state,
            @Nullable String cursor,
            int limit) {
        return session.inPlatformScopeSession(() -> requests.list(null, state, cursor, limit));
    }

    /**
     * 读取平台级当前发布指针（CAS 基线的来源）。
     *
     * @return 当前指针；从未发布过时为空
     */
    public @NonNull Optional<ConfigPointer> currentPointer() {
        return session.inPlatformScopeSession(
                () -> pointers.current(ConfigRelease.Scope.PLATFORM, null));
    }

    // ── 内部 ───────────────────────────────────────────────────────

    private ConfigPublishRequest insert(ConfigPublishPayload payload, String reason,
                                        String requesterId) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("变更理由不能为空");
        }
        // ⚠️ 变更前快照必须在**提交这一刻**由服务端计算并落库：
        //    事后反查权威表会因目标版本被清理 / 再次变更而失真，
        //    而客户端提交的「变更前」没有证据价值（调用方自己写的）。
        String beforePayload = snapshotBefore(payload);
        // ⚠️ 变更**立即**产生版本行（草稿态，published_at = NULL）：
        //    不等发布才建版本，否则「改了什么」发布前无处可查。
        //    版本号在这一刻定死，发布只做「转正」。
        materializeDraftVersions(payload);
        return session.inPlatformScopeSession(() -> requests.insertWithBeforeSnapshot(
                null, payload.operation(), payload.toJson(), beforePayload, reason, requesterId));
    }

    /**
     * 为本次变更<b>立即落草稿版本行</b>（{@code published_at = NULL}，平台口径）。
     *
     * <p>⚠️ <b>为什么在申请时就建版本</b>：让「改了什么」在发布前就可查
     * （变更记录 / 版本对比都依赖版本行），且版本号在提交那一刻定死 ——
     * 后续审批、发布都不会再变号，发布只做「转正」。
     *
     * <p>⚠️ <b>复用已有未发布草稿</b>：最高版本若仍是未发布草稿，
     * 就<b>就地更新它</b>而不是新增版本号 —— 否则反复提交会堆出一串内容相近的空版本。
     * 只有当前最高版本已发布（或不存在）时，才新建 {@code 最高版本 + 1} 的草稿。
     *
     * <p>⚠️ <b>失败不阻断申请</b>：写草稿是「尽早留痕」的优化；
     * 真出错时仍让申请单落地（发布路径会回落到 insert 补建），但记 warn 留痕。
     */
    private void materializeDraftVersions(@NonNull ConfigPublishPayload payload) {
        Instant now = clock.instant();
        try {
            for (ConfigPublishPayload.AgentSpec spec : payload.agents()) {
                if (agentDefs == null) {
                    continue;
                }
                CapAgentDef latest = agentDefs.findLatestGlobal(spec.agentId()).orElse(null);
                if (latest != null && latest.publishedAt() == null) {
                    // 复用未发布草稿：版本号不变，就地更新内容
                    agentDefs.updateDraftGlobal(agentDraft(spec, latest.version(), now));
                    continue;
                }
                int version = (latest == null ? 0 : latest.version()) + 1;
                agentDefs.insert(agentDraft(spec, version, now));
            }
            for (ConfigPublishPayload.ToolSpec spec : payload.tools()) {
                CapToolDef latest = toolDefs.findLatest(spec.toolId()).orElse(null);
                if (latest != null && latest.publishedAt() == null) {
                    toolDefs.updateDraft(toolDraft(spec, latest.version(), now));
                    continue;
                }
                int version = (latest == null ? 0 : latest.version()) + 1;
                toolDefs.insert(toolDraft(spec, version, now));
            }
            for (ConfigPublishPayload.SkillSpec spec : payload.skills()) {
                CapSkillDef latest = skillDefs.findLatest(spec.skillId()).orElse(null);
                if (latest != null && latest.publishedAt() == null) {
                    skillDefs.updateDraft(skillDraft(spec, latest.version(), now));
                    continue;
                }
                int version = (latest == null ? 0 : latest.version()) + 1;
                skillDefs.insert(skillDraft(spec, version, now));
            }
            for (ConfigPublishPayload.McpServerSpec spec : payload.mcpServers()) {
                CapMcpServerDef latest = serverDefs.findLatest(spec.serverId()).orElse(null);
                if (latest != null && latest.publishedAt() == null) {
                    serverDefs.updateDraft(serverDraft(spec, latest.version(), now));
                    continue;
                }
                int version = (latest == null ? 0 : latest.version()) + 1;
                serverDefs.insert(serverDraft(spec, version, now));
            }
        } catch (RuntimeException e) {
            log.warn("提交阶段落草稿版本行失败（不阻断申请，发布时会补建）：operation={}",
                    payload.operation(), e);
        }
    }

    /**
     * 计算本次变更的<b>变更前</b>配置快照。
     *
     * <p>按资源类型分别取当前权威定义（{@code cap_*_def} / {@code cap_agent_def} 的
     * 最高版本），组装成与 {@code payload} 同构的 JSON，供变更记录做前后对比。
     *
     * <p>⚠️ <b>取不到基线就返回 {@code null}</b>（新建对象、非发布类操作、读取异常）：
     * 不臆造空对象 —— 前端据此区分「无基线（首个版本）」与「基线是空配置」。
     *
     * <p>⚠️ <b>读取异常不得让申请失败</b>：快照是「辅助证据」，
     * 取不到只是对比时缺基线，不该阻断变更本身；但必须记日志留痕。
     */
    private @Nullable String snapshotBefore(@NonNull ConfigPublishPayload payload) {
        try {
            Map<String, Object> snapshot = new LinkedHashMap<>();

            if (!payload.agents().isEmpty() && agentDefs != null) {
                List<Map<String, Object>> agents = new ArrayList<>();
                for (ConfigPublishPayload.AgentSpec spec : payload.agents()) {
                    agentDefs.findLatestGlobal(spec.agentId())
                            .ifPresent(def -> agents.add(agentToMap(def)));
                }
                if (!agents.isEmpty()) {
                    snapshot.put("agents", agents);
                }
            }
            if (!payload.tools().isEmpty()) {
                List<Map<String, Object>> tools = new ArrayList<>();
                for (ConfigPublishPayload.ToolSpec spec : payload.tools()) {
                    toolDefs.findLatest(spec.toolId()).ifPresent(def -> tools.add(toolToMap(def)));
                }
                if (!tools.isEmpty()) {
                    snapshot.put("tools", tools);
                }
            }
            if (!payload.skills().isEmpty()) {
                List<Map<String, Object>> skills = new ArrayList<>();
                for (ConfigPublishPayload.SkillSpec spec : payload.skills()) {
                    skillDefs.findLatest(spec.skillId()).ifPresent(def -> skills.add(skillToMap(def)));
                }
                if (!skills.isEmpty()) {
                    snapshot.put("skills", skills);
                }
            }
            if (!payload.mcpServers().isEmpty()) {
                List<Map<String, Object>> servers = new ArrayList<>();
                for (ConfigPublishPayload.McpServerSpec spec : payload.mcpServers()) {
                    serverDefs.findLatest(spec.serverId()).ifPresent(def -> servers.add(serverToMap(def)));
                }
                if (!servers.isEmpty()) {
                    snapshot.put("mcpServers", servers);
                }
            }

            if (snapshot.isEmpty()) {
                return null;
            }
            return SNAPSHOT_MAPPER.writeValueAsString(snapshot);
        } catch (RuntimeException e) {
            // 快照是辅助证据：取不到只影响对比，不该阻断变更本身。
            log.warn("计算变更前配置快照失败（不影响本次申请）：operation={}", payload.operation(), e);
            return null;
        }
    }

    private static Map<String, Object> agentToMap(@NonNull CapAgentDef def) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("agentId", def.agentId());
        m.put("version", def.version());
        m.put("name", def.name());
        m.put("description", def.description());
        m.put("roles", def.roles());
        m.put("enabledSkills", def.enabledSkills());
        m.put("skillBundles", def.skillBundles());
        m.put("capabilityTags", def.capabilityTags());
        m.put("overrides", def.overrides());
        m.put("orchestration", def.orchestration());
        m.put("instructions", def.instructions());
        m.put("modelRef", def.modelRef());
        m.put("generationParams", def.generationParams());
        return m;
    }

    private static Map<String, Object> toolToMap(@NonNull CapToolDef def) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("toolId", def.id());
        m.put("version", def.version());
        m.put("name", def.name());
        m.put("kind", def.kind().name());
        m.put("riskLevel", def.riskLevel().name());
        m.put("mcpServerId", def.mcpServerId());
        m.put("mcpToolName", def.mcpToolName());
        m.put("inputSchema", def.inputSchema());
        m.put("paramPolicies", def.paramPolicies());
        m.put("description", def.description());
        return m;
    }

    private static Map<String, Object> skillToMap(@NonNull CapSkillDef def) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("skillId", def.id());
        m.put("version", def.version());
        m.put("name", def.name());
        m.put("declaredTools", def.declaredTools());
        m.put("riskCeiling", def.riskCeiling().name());
        m.put("paramPolicies", def.paramPolicies());
        m.put("requiredCaps", def.requiredCaps());
        return m;
    }

    private static Map<String, Object> serverToMap(@NonNull CapMcpServerDef def) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("serverId", def.id());
        m.put("version", def.version());
        m.put("transport", def.transport().name());
        m.put("endpoint", def.endpoint());
        m.put("authKind", def.authKind().name());
        m.put("trustTier", def.trustTier().name());
        m.put("exposedToolAllowlist", def.exposedToolAllowlist());
        m.put("connectionRisk", def.connectionRisk().name());
        return m;
    }

    /**
     * 平台申请后的按策略分流（服务端强制，PLATFORM 作用域）。
     *
     * <p>APPROVAL_* → 停在 PENDING 等真人双人复核；MANUAL → 系统自动放行到 APPROVED、
     * 等人工点「执行」；DIRECT → 自动放行并立即执行。自动放行写入固定系统主体，
     * 既满足「审批人 ≠ 申请人」，又在审计上与真人复核区分。
     */
    private ConfigPublishRequest autoRoute(ConfigPublishRequest created,
                                           List<ResourceRef> refs, String requesterId) {
        if (policies == null) return created;
        PublishPolicyService.ResolveResult res = policies.resolveDetailedForResources("PLATFORM", null, refs);
        PublishMode mode = res.mode();
        log.info("平台申请 {} 按发布策略 {} 自动分流（policyId={}, level={}, globalFallback={}）",
                created.requestId(), mode, res.policyId(), res.policyLevel(), res.fromGlobalFallback());
        if (mode.requiresApproval()) return created;
        // ⚠️ 2026-11-21 M4：审批意见钉死策略决策来源，见 ConfigPublishWorkflowService.autoRoute。
        String decisionNote = PublishPolicyService.POLICY_AUTO_NOTE_PREFIX + mode.name()
                + "; policyLevel=" + res.policyLevel()
                + "; policyId=" + res.policyId()
                + "; fromGlobalFallback=" + res.fromGlobalFallback();
        ConfigPublishRequest approved = session.inPlatformScopeSession(() -> requests.decide(
                null, created.requestId(), ConfigPublishRequestPort.Decision.APPROVE,
                decisionNote, PublishPolicyService.POLICY_AUTO_APPROVER));
        return mode.autoPublishOnApproval()
                ? apply(created.requestId(), requesterId)
                : approved;
    }

    /** 从载荷提取参与分流判定的资源引用；空集合回退到 GLOBAL 策略。 */
    private static List<ResourceRef> refsOf(ConfigPublishPayload payload) {
        List<ResourceRef> refs = new ArrayList<>();
        for (ConfigPublishPayload.ToolSpec t : payload.tools()) {
            refs.add(ResourceRef.of("TOOL", t.toolId()));
        }
        for (ConfigPublishPayload.SkillSpec s : payload.skills()) {
            refs.add(ResourceRef.of("SKILL", s.skillId()));
        }
        for (ConfigPublishPayload.McpServerSpec m : payload.mcpServers()) {
            refs.add(ResourceRef.of("MCP_SERVER", m.serverId()));
        }
        for (ConfigPublishPayload.AgentSpec a : payload.agents()) {
            refs.add(ResourceRef.of("AGENT", a.agentId()));
        }
        if (payload.lifecycle() != null) {
            ConfigPublishPayload.LifecycleSpec s = payload.lifecycle();
            refs.add(ResourceRef.of(s.type().toUpperCase(Locale.ROOT), s.objectId()));
        }
        if (refs.isEmpty()) refs.add(ResourceRef.of("CONFIG", null));
        return refs;
    }

    private void executeLifecycle(ConfigPublishRequest request, ConfigPublishPayload payload) {
        ConfigPublishPayload.LifecycleSpec spec = payload.lifecycle();
        if (spec == null) {
            throw new IllegalArgumentException("生命周期申请缺少 lifecycle 参数");
        }
        requirePlatformObjectType(spec.type());
        ConfigLifecycleState from;
        ConfigLifecycleState to;
        try {
            from = ConfigLifecycleState.valueOf(spec.expectedFrom());
            to = ConfigLifecycleState.valueOf(spec.to());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("生命周期状态非法: " + e.getMessage(), e);
        }
        try {
            boolean moved = lifecycle.transition(spec.type(), null, spec.objectId(),
                    spec.version(), from, to);
            if (!moved) {
                // ⚠️ 原状态不符 = 并发竞争（CAS 前置条件不满足），
                //    这不是「迁移非法」，而是「别人先动了」—— 两者必须能分开。
                throw new ConfigPublishBaselineConflictException(
                        "生命周期迁移的原状态不符：期望 " + from + "，对象 " + spec.objectId()
                                + " v" + spec.version() + " 当前不在该状态");
            }
        } catch (RuntimeException e) {
            // ⚠️ 保留原始异常信息，避免丢失堆栈
            log.error("平台级生命周期迁移失败: type={}, objectId={}, version={}",
                    spec.type(), spec.objectId(), spec.version(), e);
            throw e;
        }
    }

    /**
     * 构造平台级<b>全局 Agent</b> 发布命令（PLATFORM 作用域 + tenantId=null）。
     *
     * <p>⚠️ 版本号取「全局最大版本 + 1」（{@code findLatestGlobal}，不按绑定过滤），
     * 内容指纹由服务端按 {@link CapObjectFingerprint} 计算——都不采信客户端。
     * <p>⚠️ <b>无实际修改不加版本</b>：内容与现有最新版本行完全一致（指纹相同）时
     * 直接拒绝——版本号只应随真实内容变化递增，空转改版会污染版本历史。
     *
     * @param resolveBaseline {@code true} = 解析 CAS 基线（执行期）；{@code false} = 不解析（申请期）
     */
    private ConfigPublishCommand buildAgentCommand(ConfigPublishPayload payload, String requesterId,
                                                   String approverId, boolean resolveBaseline) {
        Instant now = clock.instant();
        List<CapAgentDef> defs = new ArrayList<>();
        for (ConfigPublishPayload.AgentSpec spec : payload.agents()) {
            CapAgentDef latest = agentDefs.findLatestGlobal(spec.agentId()).orElse(null);
            int version = (latest == null ? 0 : latest.version()) + 1;
            if (latest != null && CapObjectFingerprint.of(agentDraft(spec, latest.version(), now))
                    .equals(latest.configFingerprint())) {
                // ⚠️ 版本号含在指纹里：探针行用「与最新版本相同的版本号」重建内容，
                //    指纹一致 ⟺ 内容完全一致（时间戳不参与计算）。
                throw new ConfigPublishRejectedException(ReasonCode.RESOURCE_VERSION_MISMATCH,
                        "agents", "Agent " + spec.agentId() + " 的内容与现有版本 v"
                                + latest.version() + " 完全一致（无实际修改），不新增版本；"
                                + "如需变更请修改内容后重新提交");
            }
            CapAgentDef draft = agentDraft(spec, version, now);
            String fingerprint = CapObjectFingerprint.of(draft);
            defs.add(new CapAgentDef(spec.agentId(), version, spec.name(), spec.description(),
                    spec.roles(), spec.enabledSkills(), spec.skillBundles(),
                    spec.capabilityTags(), spec.overrides(), spec.orchestration(),
                    spec.instructions(), spec.modelRef(), spec.generationParams(),
                    fingerprint, 1, now, null));
        }
        Long baseline = resolveBaseline ? resolveBaseline() : null;
        return new ConfigPublishCommand(ConfigRelease.Scope.PLATFORM, null, baseline,
                requesterId, approverId, null,
                List.of(), List.of(), List.of(), defs, null, List.of(), List.of());
    }

    /** Agent 草稿行（指纹占位，占位值不参与指纹计算）。 */
    private static CapAgentDef agentDraft(ConfigPublishPayload.AgentSpec spec, int version,
                                          Instant now) {
        return new CapAgentDef(spec.agentId(), version, spec.name(),
                spec.description(), spec.roles(), spec.enabledSkills(), spec.skillBundles(),
                spec.capabilityTags(), spec.overrides(), spec.orchestration(),
                spec.instructions(), spec.modelRef(), spec.generationParams(),
                "pending", 1, now, null);
    }

    /**
     * 构造平台级发布命令（版本号与内容指纹在此确定）。
     *
     * <p>⚠️ <b>无实际修改不加版本</b>（版本管理机制 · 2026-11-22）：
     * 每个对象在分配「最大版本 + 1」之前，先用「与现有最新版本<b>相同版本号</b>」的
     * 探针行重算内容指纹并与库存指纹比对 —— 一致即「内容完全没变」，直接拒绝：
     * 版本号只应随<b>真实内容变化</b>递增，空转改版会污染版本历史、制造无意义的
     * 「已发布不可变行」。该守卫在申请预检与执行期都会生效（两处都调本方法）。
     *
     * @param resolveBaseline {@code true} = 解析并校验 CAS 基线（执行期）；
     *                        {@code false} = 不解析（申请期预检）
     */
    private ConfigPublishCommand buildCommand(ConfigPublishPayload payload, String requesterId,
                                              String approverId, boolean resolveBaseline) {
        Instant now = clock.instant();
        // ⚠️ 版本号要按「**现有最大版本** + 1」算，而不是「最新已发布版本 + 1」——
        //    后者会与草稿版本撞号（撞号后插入被 DO NOTHING 吞掉，
        //    而发布却报告成功 —— 那是「假成功」）。
        Map<String, LatestDef> toolLatest = latestDefs(toolDefs.listAll());
        Map<String, LatestDef> skillLatest = latestDefs(skillDefs.listAll());
        Map<String, LatestDef> serverLatest = latestDefs(serverDefs.listAll());

        List<CapToolDef> toolRows = new ArrayList<>();
        for (ConfigPublishPayload.ToolSpec spec : payload.tools()) {
            LatestDef latest = toolLatest.get(spec.toolId());
            int version = (latest == null ? 0 : latest.version()) + 1;
            if (latest != null && CapObjectFingerprint.of(toolDraft(spec, latest.version(), now))
                    .equals(latest.fingerprint())) {
                // ⚠️ 版本号含在指纹里：探针行用「与最新版本相同的版本号」重建内容，
                //    指纹一致 ⟺ 内容完全一致（时间戳不参与计算）。
                throw new ConfigPublishRejectedException(ReasonCode.RESOURCE_VERSION_MISMATCH,
                        "tools", "Tool " + spec.toolId() + " 的内容与现有版本 v"
                                + latest.version() + " 完全一致（无实际修改），不新增版本；"
                                + "如需变更请修改内容后重新提交");
            }
            CapToolDef draft = toolDraft(spec, version, now);
            String fingerprint = CapObjectFingerprint.of(draft);
            toolRows.add(new CapToolDef(spec.toolId(), version, spec.name(),
                    draft.kind(), draft.riskLevel(),
                    draft.mcpServerId(), draft.mcpToolName(), draft.httpConfig(),
                    spec.inputSchema(), spec.paramPolicies(), spec.description(),
                    fingerprint, 1, now, null));
        }

        List<CapSkillDef> skillRows = new ArrayList<>();
        for (ConfigPublishPayload.SkillSpec spec : payload.skills()) {
            LatestDef latest = skillLatest.get(spec.skillId());
            int version = (latest == null ? 0 : latest.version()) + 1;
            if (latest != null && CapObjectFingerprint.of(skillDraft(spec, latest.version(), now))
                    .equals(latest.fingerprint())) {
                throw new ConfigPublishRejectedException(ReasonCode.RESOURCE_VERSION_MISMATCH,
                        "skills", "Skill " + spec.skillId() + " 的内容与现有版本 v"
                                + latest.version() + " 完全一致（无实际修改），不新增版本；"
                                + "如需变更请修改内容后重新提交");
            }
            CapSkillDef draft = skillDraft(spec, version, now);
            String fingerprint = CapObjectFingerprint.of(draft);
            skillRows.add(new CapSkillDef(spec.skillId(), version, spec.name(),
                    draft.description(), draft.declaredTools(), draft.declaredMcp(),
                    draft.riskCeiling(), draft.paramPolicies(), draft.requiredCaps(),
                    fingerprint, 1, now, null));
        }

        List<CapMcpServerDef> serverRows = new ArrayList<>();
        for (ConfigPublishPayload.McpServerSpec spec : payload.mcpServers()) {
            LatestDef latest = serverLatest.get(spec.serverId());
            int version = (latest == null ? 0 : latest.version()) + 1;
            if (latest != null && CapObjectFingerprint.of(serverDraft(spec, latest.version(), now))
                    .equals(latest.fingerprint())) {
                throw new ConfigPublishRejectedException(ReasonCode.RESOURCE_VERSION_MISMATCH,
                        "mcpServers", "MCP Server " + spec.serverId() + " 的内容与现有版本 v"
                                + latest.version() + " 完全一致（无实际修改），不新增版本；"
                                + "如需变更请修改内容后重新提交");
            }
            CapMcpServerDef draft = serverDraft(spec, version, now);
            String fingerprint = CapObjectFingerprint.of(draft);
            serverRows.add(new CapMcpServerDef(spec.serverId(), version,
                    draft.transport(), draft.endpoint(), draft.command(),
                    draft.authKind(), draft.trustTier(),
                    draft.exposedToolAllowlist(), draft.connectionRisk(),
                    false, fingerprint, spec.credentialRef(), 1, now, null));
        }

        Long baseline = resolveBaseline ? resolveBaseline() : null;
        return new ConfigPublishCommand(ConfigRelease.Scope.PLATFORM, null, baseline,
                requesterId, approverId, null,
                toolRows, skillRows, serverRows, List.of(), null, List.of(), List.of());
    }

    // ── 草稿行构造（指纹占位；占位值不参与指纹计算，探针与正式行共用）──────

    /** Tool 草稿行。HTTP 配置只在 kind=http 时构造；其余 kind 必须传 null。 */
    private static CapToolDef toolDraft(ConfigPublishPayload.ToolSpec spec, int version,
                                        Instant now) {
        CapToolKind kind = parseKind(spec.kind());
        // ⚠️ HTTP 配置只在 kind=http 时构造；其余 kind 必须传 null ——
        //    CapToolDef 的紧凑构造器会拒绝「非 HTTP 却带 http 配置」。
        CapHttpToolConfig http = null;
        if (kind == CapToolKind.HTTP) {
            if (spec.httpMethod() == null || spec.httpUrl() == null
                    || spec.httpTimeoutMs() == null) {
                throw new IllegalArgumentException(
                        "kind=http 的 Tool 必须同时声明 httpMethod、httpUrl 与 httpTimeoutMs");
            }
            http = new CapHttpToolConfig(spec.httpMethod(), spec.httpUrl(),
                    spec.httpTimeoutMs());
        }
        // ⚠️ 全参构造：description 是模型可见的工具行为定义，
        //    且已纳入 CapObjectFingerprint.of(CapToolDef) —— 用兼容构造器
        //    既会让管理台提交的描述丢失，又会让「改了描述」算不出内容变化。
        return new CapToolDef(spec.toolId(), version, spec.name(),
                kind, parseRisk(spec.riskLevel()),
                spec.mcpServerId(), spec.mcpToolName(), http,
                spec.inputSchema(), spec.paramPolicies(), spec.description(),
                "pending", 1, now, null);
    }

    /** Skill 草稿行。 */
    private static CapSkillDef skillDraft(ConfigPublishPayload.SkillSpec spec, int version,
                                          Instant now) {
        List<CapSkillDef.McpDeclaration> declarations = new ArrayList<>();
        for (ConfigPublishPayload.McpDeclarationSpec declaration : spec.declaredMcp()) {
            declarations.add(new CapSkillDef.McpDeclaration(
                    declaration.serverId(), declaration.toolAllowlist()));
        }
        SkillDescription desc = skillDescription(spec.description());
        return new CapSkillDef(spec.skillId(), version, spec.name(),
                desc, spec.declaredTools(), declarations,
                parseRisk(spec.riskCeiling()), spec.paramPolicies(), spec.requiredCaps(),
                "pending", 1, now, null);
    }

    /** MCP Server 草稿行。 */
    private static CapMcpServerDef serverDraft(ConfigPublishPayload.McpServerSpec spec, int version,
                                               Instant now) {
        return new CapMcpServerDef(spec.serverId(), version,
                parseTransport(spec.transport()), spec.endpoint(), spec.command(),
                parseAuthKind(spec.authKind()), parseTrustTier(spec.trustTier()),
                spec.exposedToolAllowlist(), parseRisk(spec.connectionRisk()),
                false, "pending", spec.credentialRef(), 1, now, null);
    }

    /**
     * 载荷草稿两级描述 → 领域模型（形状搬运；字数/条数约束已在
     * {@link ConfigPublishPayload.SkillDescriptionSpec} 发布闸门处执行）。
     *
     * @param spec 载荷草稿（可为 null = 未配置两级描述）
     */
    private static @Nullable SkillDescription skillDescription(
            ConfigPublishPayload.SkillDescriptionSpec spec) {
        if (spec == null) {
            return null;
        }
        return new SkillDescription(spec.purpose(),
                spec.usage().stream()
                        .map(u -> new SkillDescription.SkillUsage(u.when(), u.how()))
                        .toList(),
                spec.content());
    }

    /**
     * 解析 CAS 基线。
     *
     * <p>⚠️ 客户端给了基线就必须与当前一致 —— 不一致说明「你看到的已经不是现在的配置了」，
     * 此时若继续发布，覆盖的就是一份<b>对方没看过的</b>配置。
     */
    private Long resolveBaseline() {
        Long current = pointers.current(ConfigRelease.Scope.PLATFORM, null)
                .map(ConfigPointer::currentPublishSeq).orElse(null);
        return current;
    }

    /** 对象类型闸门：只有平台级对象可在本通道做生命周期迁移。 */
    private static void requirePlatformObjectType(String type) {
        boolean allowed = PLATFORM_LIFECYCLE_OBJECT_TYPES.stream()
                .anyMatch(candidate -> candidate.equalsIgnoreCase(type));
        if (!allowed) {
            throw new ConfigPublishRejectedException(ReasonCode.RESOURCE_UNKNOWN, "type",
                    "平台通道只管理平台级对象（" + String.join(" / ", PLATFORM_LIFECYCLE_OBJECT_TYPES)
                            + "）；对象类型「" + type + "」不属于本通道");
        }
    }

    /** 每个逻辑 id 的最新版本行摘要（版本号 + 内容指纹），供「无实际修改不加版本」守卫使用。 */
    private record LatestDef(int version, @NonNull String fingerprint) {
    }

    /**
     * 取每个逻辑 id 的最新版本行（最高版本号）——
     * {@code listAll()} 的口径即「每个逻辑 id 取其最高版本行」，其指纹与版本号配套。
     */
    private static Map<String, LatestDef> latestDefs(List<? extends Object> defs) {
        Map<String, LatestDef> out = new LinkedHashMap<>();
        for (Object def : defs) {
            String id;
            int version;
            String fingerprint;
            if (def instanceof CapToolDef tool) {
                id = tool.id();
                version = tool.version();
                fingerprint = tool.configFingerprint();
            } else if (def instanceof CapSkillDef skill) {
                id = skill.id();
                version = skill.version();
                fingerprint = skill.configFingerprint();
            } else if (def instanceof CapMcpServerDef server) {
                id = server.id();
                version = server.version();
                fingerprint = server.configFingerprint();
            } else {
                continue;
            }
            LatestDef current = out.get(id);
            // ⚠️ 指纹必须与「最高版本号」的行配套：若同 id 出现多行，只留版本号最大的。
            if (current == null || version > current.version()) {
                out.put(id, new LatestDef(version, fingerprint));
            }
        }
        return out;
    }

    // ── 枚举解析（全部 fail-closed：未知值即拒，不做「相近名」猜测）──────

    private static CapToolKind parseKind(String raw) {
        try {
            return CapToolKind.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知的 Tool kind: " + raw
                    + "（合法值: NATIVE / MCP / HTTP）", e);
        }
    }

    private static CapabilityRiskLevel parseRisk(String raw) {
        try {
            return CapabilityRiskLevel.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知的风险等级: " + raw
                    + "（合法值: READ / WRITE_PROFILE / WRITE_EXTERNAL）", e);
        }
    }

    private static CapMcpServerDef.Transport parseTransport(String raw) {
        CapMcpServerDef.Transport transport = CapMcpServerDef.Transport.parse(raw);
        if (transport == null || !transport.isSupportedInV1()) {
            throw new IllegalArgumentException("未知的传输方式: " + raw
                    + "（V1 只支持 HTTP / SSE）");
        }
        return transport;
    }

    private static CapMcpServerDef.AuthKind parseAuthKind(String raw) {
        CapMcpServerDef.AuthKind kind = CapMcpServerDef.AuthKind.parse(raw);
        if (kind == null) {
            throw new IllegalArgumentException("未知的认证方式: " + raw
                    + "（合法值: TOKEN / OAUTH / NONE）");
        }
        return kind;
    }

    private static McpTrustTier parseTrustTier(String raw) {
        try {
            return McpTrustTier.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知的信任层级: " + raw
                    + "（合法值: INTERNAL / PARTNER / PUBLIC）", e);
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
