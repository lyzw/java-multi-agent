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

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

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
import cn.studykid.multiagent.core.cap.ConfigRelease;
import cn.studykid.multiagent.core.cap.McpTrustTier;
import cn.studykid.multiagent.core.cap.ReasonCode;
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
 *
 * <p>⚠️ <b>生命周期迁移与发布不同源</b>：
 * 停用（{@code PUBLISHED → DEPRECATED}）走 {@link ConfigLifecycleService}，
 * 它写的是对象自己的状态列，<b>不产生发布头、不移动指针</b>；
 * 而「发布」只经由「发布新版本行 + 切换当前指针」达成。
 * 两者刻意不合并 —— 合并后「停用」会顺带移动全局指针，
 * 一次误操作就能把全平台的当前配置切走。
 */
public class PlatformCapabilityPublishService {

    /** 本服务可做生命周期迁移的平台级对象类型。 */
    public static final Set<String> PLATFORM_LIFECYCLE_OBJECT_TYPES =
            Set.of("tool", "skill", "mcp_server");

    private final PlatformOpsSessionPort session;
    private final ConfigPublishRequestPort requests;
    private final ConfigPublishService publisher;
    private final ConfigLifecycleService lifecycle;
    private final CapToolDefPort toolDefs;
    private final CapSkillDefPort skillDefs;
    private final CapMcpServerDefPort serverDefs;
    private final ConfigPointerPort pointers;
    private final Clock clock;

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
        this.session = session;
        this.requests = requests;
        this.publisher = publisher;
        this.lifecycle = lifecycle;
        this.toolDefs = toolDefs;
        this.skillDefs = skillDefs;
        this.serverDefs = serverDefs;
        this.pointers = pointers;
        this.clock = clock;
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
        return insert(payload, reason, requesterId);
    }

    /**
     * 提交平台级生命周期迁移申请（停用 / 回退）。
     *
     * @param spec        迁移参数（对象类型必须是 tool / skill / mcp_server）
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
        return insert(payload, reason, requesterId);
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
        return session.inPlatformScopeSession(
                () -> requests.decide(null, requestId, decision, note, approverId));
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
                    case PUBLISH -> publisher.publish(buildCommand(payload,
                            request.requesterId(), request.approverId(), true));
                    case LIFECYCLE_TRANSITION -> executeLifecycle(request, payload);
                    case ROLLBACK -> throw new ConfigPublishRejectedException(
                            ReasonCode.RESOURCE_UNKNOWN, "operation",
                            "平台作用域的回滚尚未提供（回滚目标要按发布历史定位，"
                                    + "而平台发布历史当前不可读）—— 登记为缺口，不做半实现");
                }
            } catch (RuntimeException e) {
                // ⚠️ 失败必须留痕：一份「批过但没生效」的申请不能只存在于异常栈里。
                try {
                    requests.markFailed(null, requestId, String.valueOf(e.getMessage()));
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
        return session.inPlatformScopeSession(() -> requests.insert(
                null, payload.operation(), payload.toJson(), reason, requesterId));
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
        boolean moved = lifecycle.transition(spec.type(), null, spec.objectId(),
                spec.version(), from, to);
        if (!moved) {
            // ⚠️ 原状态不符 = 并发竞争（CAS 前置条件不满足），
            //    这不是「迁移非法」，而是「别人先动了」—— 两者必须能分开。
            throw new ConfigPublishBaselineConflictException(
                    "生命周期迁移的原状态不符：期望 " + from + "，对象 " + spec.objectId()
                            + " v" + spec.version() + " 当前不在该状态");
        }
    }

    /**
     * 构造平台级发布命令（版本号与内容指纹在此确定）。
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
        Map<String, Integer> toolVersions = maxVersions(toolDefs.listAll());
        Map<String, Integer> skillVersions = maxVersions(skillDefs.listAll());
        Map<String, Integer> serverVersions = maxVersions(serverDefs.listAll());

        List<CapToolDef> toolRows = new ArrayList<>();
        for (ConfigPublishPayload.ToolSpec spec : payload.tools()) {
            int version = toolVersions.getOrDefault(spec.toolId(), 0) + 1;
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
            CapToolDef draft = new CapToolDef(spec.toolId(), version, spec.name(),
                    kind, parseRisk(spec.riskLevel()),
                    spec.mcpServerId(), spec.mcpToolName(), http,
                    spec.inputSchema(), spec.paramPolicies(),
                    // ⚠️ 占位指纹：内容指纹**不包含**自身，故可先占位再算
                    "pending", 1, now, null);
            String fingerprint = CapObjectFingerprint.of(draft);
            toolRows.add(new CapToolDef(spec.toolId(), version, spec.name(),
                    kind, parseRisk(spec.riskLevel()),
                    spec.mcpServerId(), spec.mcpToolName(), http,
                    spec.inputSchema(), spec.paramPolicies(),
                    fingerprint, 1, now, null));
        }

        List<CapSkillDef> skillRows = new ArrayList<>();
        for (ConfigPublishPayload.SkillSpec spec : payload.skills()) {
            int version = skillVersions.getOrDefault(spec.skillId(), 0) + 1;
            List<CapSkillDef.McpDeclaration> declarations = new ArrayList<>();
            for (ConfigPublishPayload.McpDeclarationSpec declaration : spec.declaredMcp()) {
                declarations.add(new CapSkillDef.McpDeclaration(
                        declaration.serverId(), declaration.toolAllowlist()));
            }
            CapSkillDef draft = new CapSkillDef(spec.skillId(), version, spec.name(),
                    spec.description(), spec.declaredTools(), declarations,
                    parseRisk(spec.riskCeiling()), spec.paramPolicies(), spec.requiredCaps(),
                    "pending", 1, now, null);
            String fingerprint = CapObjectFingerprint.of(draft);
            skillRows.add(new CapSkillDef(spec.skillId(), version, spec.name(),
                    spec.description(), spec.declaredTools(), declarations,
                    parseRisk(spec.riskCeiling()), spec.paramPolicies(), spec.requiredCaps(),
                    fingerprint, 1, now, null));
        }

        List<CapMcpServerDef> serverRows = new ArrayList<>();
        for (ConfigPublishPayload.McpServerSpec spec : payload.mcpServers()) {
            int version = serverVersions.getOrDefault(spec.serverId(), 0) + 1;
            CapMcpServerDef draft = new CapMcpServerDef(spec.serverId(), version,
                    parseTransport(spec.transport()), spec.endpoint(), spec.command(),
                    parseAuthKind(spec.authKind()), parseTrustTier(spec.trustTier()),
                    spec.exposedToolAllowlist(), parseRisk(spec.connectionRisk()),
                    false, "pending", spec.credentialRef(), 1, now, null);
            String fingerprint = CapObjectFingerprint.of(draft);
            serverRows.add(new CapMcpServerDef(spec.serverId(), version,
                    parseTransport(spec.transport()), spec.endpoint(), spec.command(),
                    parseAuthKind(spec.authKind()), parseTrustTier(spec.trustTier()),
                    spec.exposedToolAllowlist(), parseRisk(spec.connectionRisk()),
                    false, fingerprint, spec.credentialRef(), 1, now, null));
        }

        Long baseline = resolveBaseline ? resolveBaseline() : null;
        return new ConfigPublishCommand(ConfigRelease.Scope.PLATFORM, null, baseline,
                requesterId, approverId, null,
                toolRows, skillRows, serverRows, List.of(), null, List.of(), List.of());
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

    /** 取每个逻辑 id 的最大版本号。 */
    private static Map<String, Integer> maxVersions(List<? extends Object> defs) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Object def : defs) {
            String id;
            int version;
            if (def instanceof CapToolDef tool) {
                id = tool.id();
                version = tool.version();
            } else if (def instanceof CapSkillDef skill) {
                id = skill.id();
                version = skill.version();
            } else if (def instanceof CapMcpServerDef server) {
                id = server.id();
                version = server.version();
            } else {
                continue;
            }
            out.merge(id, version, Math::max);
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
