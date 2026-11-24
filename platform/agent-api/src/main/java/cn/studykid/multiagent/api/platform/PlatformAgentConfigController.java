package cn.studykid.multiagent.api.platform;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import cn.studykid.multiagent.api.security.PlatformSecurity;
import cn.studykid.multiagent.application.cap.ConfigPublishWorkflowService.ConfigPublishBaselineConflictException;
import cn.studykid.multiagent.application.cap.ConfigPublishWorkflowService;
import cn.studykid.multiagent.core.cap.CapAgentDef;
import cn.studykid.multiagent.core.cap.ConfigPublishRequest;
import cn.studykid.multiagent.ports.audit.PlatformAuditPort;
import cn.studykid.multiagent.ports.audit.PlatformAuditPort.PlatformAuditEvent;
import cn.studykid.multiagent.ports.persistence.AgentManagePort;
import cn.studykid.multiagent.ports.persistence.CapAgentDefPort;
import cn.studykid.multiagent.ports.persistence.ConfigPublishRequestPort;
import cn.studykid.multiagent.ports.persistence.Page;
import cn.studykid.multiagent.ports.persistence.TenantAgentBindingPort;
import cn.studykid.multiagent.ports.security.IdentityDomains;
import cn.studykid.multiagent.ports.security.Namespaces;
import cn.studykid.multiagent.ports.security.PlatformPrincipal;

/**
 * 平台控制台 · 运营代租户配置 Agent（/api/platform/v1/capabilities/agent-config · 第五十批）。
 *
 * <p><b>为什么要新增这个端点组</b>：产品口径已改为「所有 Agent 由运营统一配置，
 * 租户不再自行维护 Agent / Skill / Tool」。原 {@code PlatformCapabilityConsoleController}
 * 的 {@code GET /agents} 只做跨租户只读盘点（{@code AgentRowDto} 仅管理元数据、
 * 定义正文不出平台边界），刻意不提供写端点（旧禁区）。
 *
 * <p><b>本端点如何既不破坏 RLS 又不越权</b>：不新造写通道 ——
 * 完全复用配置发布的 {@link ConfigPublishWorkflowService}（申请 → 审批 → 执行三步，
 * 版本号 / 内容指纹 / 归档 / 闭包校验都在其中），只是把 tenantId 从平台身份改为
 * <b>请求显式携带</b>。Agent 定义表已全局化，租户可见性由
 * {@code tenant_agent_binding} 的 ACTIVE 绑定决定；租户级对象与绑定表仍按传入的
 * tenantId 绑定上下文、走应用角色连接，不引入 BYPASSRLS 旁路。
 *
 * <p><b>授权</b>：读写各步都先经 {@link PlatformSecurity} 校验
 * {@code platform.resource.manage} / {@code platform.resource.read}
 * （矩阵驱动、默认拒绝；资源类型 agent_config，targetId = tenantId）。
 * 审批人与申请人不得相同（workflow 内强制 {@code SelfApprovalException}）。
 *
 * <p><b>读定义正文</b>：本端点提供 {@code GET /agent-config/agents/{id}/definition?tenantId=}
 * 供「修改」回填权威定义（cap_agent_def）—— 平台运营是配置方，必须能看到
 * 自己要改的关系字段；这与只读盘点页「定义正文不出平台边界」不冲突：
 * 盘点是全租户清单（无单条正文端点），这里是显式逐租户的编辑回填。
 */
@RestController
@RequestMapping("/api/platform/v1/capabilities/agent-config")
public class PlatformAgentConfigController {

    private final ConfigPublishWorkflowService workflow;
    private final cn.studykid.multiagent.application.cap.PlatformCapabilityPublishService
            platformPublisher;
    private final AgentManagePort agentManage;
    private final CapAgentDefPort agentDefs;
    private final TenantAgentBindingPort bindings;
    private final PlatformSecurity security;
    private final PlatformAuditPort audit;

    /** 测试用：未装配平台发布通道（无租户发布会 fail-closed）。 */
    public PlatformAgentConfigController(ConfigPublishWorkflowService workflow,
                                         AgentManagePort agentManage,
                                         CapAgentDefPort agentDefs,
                                         TenantAgentBindingPort bindings,
                                         PlatformSecurity security,
                                         PlatformAuditPort audit) {
        this(workflow, null, agentManage, agentDefs, bindings, security, audit);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PlatformAgentConfigController(ConfigPublishWorkflowService workflow,
                                         cn.studykid.multiagent.application.cap
                                                 .PlatformCapabilityPublishService platformPublisher,
                                         AgentManagePort agentManage,
                                         CapAgentDefPort agentDefs,
                                         TenantAgentBindingPort bindings,
                                         PlatformSecurity security,
                                         PlatformAuditPort audit) {
        this.workflow = workflow;
        this.platformPublisher = platformPublisher;
        this.agentManage = agentManage;
        this.agentDefs = agentDefs;
        this.bindings = bindings;
        this.security = security;
        this.audit = audit;
    }

    // ── ① 新建 Agent 行 ───────────────────────────────────────────

    /**
     * 创建全局 Agent 行并分配给目标租户（拿到 agentId）。
     *
     * <p>⚠️ 只建行、不写定义：cap_agent_def 对 agent 表有外键，
     * 顺序不可颠倒；定义正文仍走发布申请（三步工作流的执行步才写入）。
     * tenantId 现在只用于建立 tenant_agent_binding，不再是 Agent 的归属列。
     */
    @PostMapping("/agents")
    public ResponseEntity<AgentCreatedDto> createAgent(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @RequestBody @Nullable CreateAgentView request) {
        security.require(principal, Namespaces.Platform.RESOURCE_MANAGE,
                "agent_config", request == null ? null : request.tenantId());
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
        }
        // tenantId 可选：留空 = 只创建全局 Agent 行，不建立初始绑定；后续再「分配租户」。
        // 给了则必须是合法 UUID（防止拼写错误静默变成全局 Agent）。
        String tenantId = isBlank(request.tenantId()) ? null : request.tenantId().trim();
        if (tenantId != null && !isUuid(tenantId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tenantId 须为 UUID 或留空");
        }
        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty() || name.length() > 128
                || !name.matches("[\\u4e00-\\u9fa5a-zA-Z0-9_\\-\\s]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "name 不能为空、超长，且只能包含中文 / 英文 / 数字 / 下划线 / 连字符 / 空格");
        }
        if (request.description() != null && request.description().length() > 1024) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "description 不能超过 1024 字");
        }
        try {
            var item = agentManage.createAgent(tenantId, name, request.description(),
                    principal.userId());
            recordAudit(principal, "agent_config.create", tenantId, item.agentId());
            return ResponseEntity.ok(new AgentCreatedDto(item.agentId(), item.name(),
                    item.description()));
        } catch (AgentManagePort.DuplicateAgentNameException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已存在同名 Agent（全局唯一）");
        }
    }

    // ── ② 发布申请 / 停用申请 ─────────────────────────────────────

    /** 提交 Agent 定义发布申请（TENANT 作用域发布头；Agent 定义本身全局，不按租户分叉）。 */
    @PostMapping("/publish-requests")
    public ResponseEntity<RequestDto> requestPublish(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @RequestBody @Nullable PublishRequestView request) {
        security.require(principal, Namespaces.Platform.RESOURCE_MANAGE,
                "agent_config", request == null ? null : request.tenantId());
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
        }
        if (request.agents() == null || request.agents().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "agents 不能为空");
        }
        requireReason(request.reason());
        // ⚠️ <b>Agent 一律走平台运维通道</b>（2026-11-22）：
        //    Agent 是**全局实体**（cap_agent_def 无 tenant_id），租户只是通过
        //    tenant_agent_binding 与之多对多绑定。此前 tenantId 有值时会分流到
        //    租户通道（scope=TENANT），导致同一份 Agent 定义出现两条写入路径：
        //      · 版本行归属/可见性口径不同（一条受 RLS + 绑定过滤，一条全局）；
        //      · 「提交即建草稿版本」等能力要在两处各实现一遍，必然漂移。
        //    现在 tenantId 只作为**分配提示**保留在请求里，不再决定走哪条通道。
        String tenantId = isBlank(request.tenantId()) ? null : request.tenantId().trim();
        if (tenantId != null && !isUuid(tenantId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tenantId 须为 UUID 或留空");
        }
        try {
            ConfigPublishRequest created = requirePlatformPublisher().requestPublishAgents(
                    toAgentSpecs(request.agents()), request.expectedPublishSeq(),
                    request.reason().trim(), principal.userId());
            recordAudit(principal, "agent_config.publish.request", tenantId, created.requestId());
            return ResponseEntity.ok(RequestDto.of(created));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            // 平台运维通道未装配（fail-closed）→ 503，明确「环境缺凭据」而非业务失败。
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        }
    }

    /** 提交 Agent 停用申请（生命周期迁移 LIFECYCLE_TRANSITION；一律 DEPRECATED 终态）。 */
    @PostMapping("/lifecycle-requests")
    public ResponseEntity<RequestDto> requestLifecycle(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @RequestBody @Nullable LifecycleRequestView request) {
        security.require(principal, Namespaces.Platform.RESOURCE_MANAGE,
                "agent_config", request == null ? null : request.tenantId());
        requireReason(request == null ? null : request.reason());
        if (request == null || request.objectId() == null || request.objectId().isBlank()
                || request.version() == null || request.version() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "objectId 与正整数 version 必填");
        }
        // ⚠️ <b>Agent 一律走平台运维通道</b>（与 requestPublish 同口径，2026-11-22）：
        //    Agent 是全局实体，cap_agent_def 无 tenant_id；停用按 (agent_id, version)
        //    全局寻址。tenantId 只作**分配提示**保留在请求里（校验格式 + 进审计），
        //    不再决定走哪条通道。
        String tenantId = request.tenantId() == null || isBlank(request.tenantId())
                ? null : request.tenantId().trim();
        if (tenantId != null && !isUuid(tenantId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tenantId 须为 UUID 或留空");
        }
        // ⚠️ 只允许停用：type 固定 agent、to 固定 DEPRECATED、expectedFrom 由调用方给（CAS）。
        String from = request.expectedFrom() == null || request.expectedFrom().isBlank()
                ? "PUBLISHED" : request.expectedFrom().trim();
        try {
            ConfigPublishRequest created = requirePlatformPublisher().requestLifecycle(
                    new cn.studykid.multiagent.application.cap.ConfigPublishPayload.LifecycleSpec(
                            "agent", request.objectId().trim(), request.version(),
                            from, "DEPRECATED"),
                    request.reason().trim(), principal.userId());
            recordAudit(principal, "agent_config.deprecate.request", tenantId, created.requestId());
            return ResponseEntity.ok(RequestDto.of(created));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            // 平台运维通道未装配（fail-closed）→ 503，明确「环境缺凭据」而非业务失败。
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        }
    }

    // ── ③ 审批 / 执行 ─────────────────────────────────────────────

    /** 审批（APPROVE / REJECT；审批人不得等于申请人，workflow 内强制）。 */
    @PostMapping("/publish-requests/{requestId}/decision")
    public ResponseEntity<RequestDto> decide(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @PathVariable String requestId,
            @RequestBody @Nullable DecisionView request) {
        security.require(principal, Namespaces.Platform.RESOURCE_MANAGE,
                "agent_config", requestId);
        if (request == null || request.decision() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "decision 必填");
        }
        String tenantId = isBlank(request.tenantId()) ? null : request.tenantId().trim();
        if (tenantId != null && !isUuid(tenantId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tenantId 须为 UUID 或留空");
        }
        ConfigPublishRequestPort.@Nullable Decision decision =
                "APPROVE".equalsIgnoreCase(request.decision())
                        ? ConfigPublishRequestPort.Decision.APPROVE
                        : "REJECT".equalsIgnoreCase(request.decision())
                                ? ConfigPublishRequestPort.Decision.REJECT : null;
        if (decision == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "decision 仅支持 APPROVE / REJECT");
        }
        try {
            ConfigPublishRequest updated = tenantId == null
                    ? requirePlatformPublisher().decide(requestId, decision, request.note(),
                            principal.userId())
                    : workflow.decide(tenantId, requestId, decision, request.note(),
                            principal.userId());
            recordAudit(principal, "agent_config.publish.decision:" + decision, tenantId, requestId);
            return ResponseEntity.ok(RequestDto.of(updated));
        } catch (IllegalArgumentException | ConfigPublishRequestPort.NotFoundException
                 | ConfigPublishRequestPort.StateConflictException
                 | ConfigPublishRequestPort.SelfApprovalException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    /** 执行已批准的变更（此步才真正写入租户配置表）。 */
    @PostMapping("/publish-requests/{requestId}/apply")
    public ResponseEntity<RequestDto> apply(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @PathVariable String requestId,
            @RequestBody @Nullable TenantScopedView request) {
        security.require(principal, Namespaces.Platform.RESOURCE_MANAGE,
                "agent_config", requestId);
        String tenantId = request == null || isBlank(request.tenantId())
                ? null : request.tenantId().trim();
        if (tenantId != null && !isUuid(tenantId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tenantId 须为 UUID 或留空");
        }
        try {
            ConfigPublishRequest updated = tenantId == null
                    ? requirePlatformPublisher().apply(requestId, principal.userId())
                    : workflow.apply(tenantId, requestId, principal.userId());
            recordAudit(principal, "agent_config.publish.apply", tenantId, requestId);
            return ResponseEntity.ok(RequestDto.of(updated));
        } catch (ConfigPublishRequestPort.NotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "申请不存在");
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (ConfigPublishBaselineConflictException
                 | ConfigPublishRequestPort.StateConflictException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        }
    }

    // ── 读 ─────────────────────────────────────────────────────────

    /** 列出目标租户下的变更申请（状态过滤 + 游标分页）。 */
    @GetMapping("/publish-requests")
    public ResponseEntity<List<RequestDto>> listRequests(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @RequestParam(required = false) @Nullable String tenantId,
            @RequestParam @Nullable String state,
            @RequestParam @Nullable String cursor,
            @RequestParam @Nullable Integer limit) {
        security.require(principal, Namespaces.Platform.RESOURCE_READ,
                "agent_config", tenantId);
        ConfigPublishRequest.@Nullable State parsed = null;
        if (!isBlank(state)) {
            try {
                parsed = ConfigPublishRequest.State.valueOf(state.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知状态: " + state);
            }
        }
        int pageSize = limit == null ? 50 : Math.max(1, Math.min(limit, 200));
        if (isBlank(tenantId)) {
            // 无租户：列出全局 Agent 的 PLATFORM 作用域申请。
            return ResponseEntity.ok(requirePlatformPublisher().list(parsed, cursor, pageSize)
                    .items().stream().map(RequestDto::of).toList());
        }
        if (!isUuid(tenantId.trim())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tenantId 须为 UUID 或留空");
        }
        Page<ConfigPublishRequest> page = workflow.list(tenantId.trim(), parsed, cursor, pageSize);
        return ResponseEntity.ok(page.items().stream().map(RequestDto::of).toList());
    }

    /**
     * 读取权威定义（cap_agent_def 最新版本）供「修改」回填。
     *
     * <p>定义正文只回给平台运营，不进只读注册表清单；缺定义时 404（如实呈现）。
     */
    @GetMapping("/agents/{agentId}/definition")
    public ResponseEntity<AgentDefinitionDto> getDefinition(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @PathVariable String agentId,
            @RequestParam(required = false) @Nullable String tenantId) {
        security.require(principal, Namespaces.Platform.RESOURCE_READ,
                "agent_config", tenantId);
        CapAgentDef def;
        if (isBlank(tenantId)) {
            // 全局 Agent（未绑定租户）：不看绑定可见性，直接按 agentId 取最新定义。
            def = agentDefs.findLatestGlobal(agentId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "该 Agent 尚无权威定义"));
        } else {
            if (!isUuid(tenantId.trim())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "tenantId 须为 UUID 或留空");
            }
            def = agentDefs.findLatest(tenantId.trim(), agentId)
                    .or(() -> agentDefs.findLatestPublished(tenantId.trim(), agentId))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "该 Agent 尚无权威定义"));
        }
        return ResponseEntity.ok(AgentDefinitionDto.of(def));
    }

    // ── 租户-Agent 绑定（直接配置 CRUD，不走发布工作流）─────────────

    /** 列出目标租户已绑定的全部 Agent。 */
    @GetMapping("/bindings")
    public ResponseEntity<List<BindingDto>> listBindings(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @RequestParam String tenantId) {
        security.require(principal, Namespaces.Platform.RESOURCE_READ,
                "agent_config", tenantId);
        requireTenant(tenantId);
        return ResponseEntity.ok(bindings.listByTenant(tenantId.trim()).stream()
                .map(BindingDto::of).toList());
    }

    /** 列出某个全局 Agent 被分配到的全部租户。 */
    @GetMapping("/agents/{agentId}/bindings")
    public ResponseEntity<List<BindingDto>> listAgentBindings(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @PathVariable String agentId) {
        security.require(principal, Namespaces.Platform.RESOURCE_READ,
                "agent_config", null);
        requireUuid(agentId, "agentId");
        return ResponseEntity.ok(bindings.listByAgent(agentId).stream()
                .map(BindingDto::of).toList());
    }

    /** 建立（或重新激活）租户-Agent 绑定。 */
    @PostMapping("/bindings")
    public ResponseEntity<BindingDto> bind(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @RequestBody @Nullable BindingView request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
        }
        security.require(principal, Namespaces.Platform.RESOURCE_MANAGE,
                "agent_config", request.tenantId());
        requireTenant(request.tenantId());
        requireUuid(request.agentId(), "agentId");
        BindingDto dto = BindingDto.of(bindings.bind(request.tenantId().trim(),
                request.agentId().trim(), request.pinnedVersion(), request.config(),
                principal.userId()));
        recordAudit(principal, "agent_config.binding.bind", request.tenantId(),
                request.agentId());
        return ResponseEntity.ok(dto);
    }

    /** 更新绑定配置（pinnedVersion / config），不改变绑定状态。 */
    @PutMapping("/bindings")
    public ResponseEntity<BindingDto> updateBinding(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @RequestBody @Nullable BindingView request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
        }
        security.require(principal, Namespaces.Platform.RESOURCE_MANAGE,
                "agent_config", request.tenantId());
        requireTenant(request.tenantId());
        requireUuid(request.agentId(), "agentId");
        BindingDto dto = BindingDto.of(bindings.update(request.tenantId().trim(),
                request.agentId().trim(), request.pinnedVersion(), request.config(),
                principal.userId()));
        recordAudit(principal, "agent_config.binding.update", request.tenantId(),
                request.agentId());
        return ResponseEntity.ok(dto);
    }

    /** 停用租户-Agent 绑定（逻辑移除，保留历史）。 */
    @DeleteMapping("/bindings")
    public ResponseEntity<Void> unbind(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @RequestParam String tenantId,
            @RequestParam String agentId) {
        security.require(principal, Namespaces.Platform.RESOURCE_MANAGE,
                "agent_config", tenantId);
        requireTenant(tenantId);
        requireUuid(agentId, "agentId");
        bindings.unbind(tenantId.trim(), agentId.trim(), principal.userId());
        recordAudit(principal, "agent_config.binding.unbind", tenantId, agentId);
        return ResponseEntity.noContent().build();
    }

    // ── 内部 ───────────────────────────────────────────────────────

    private static void requireTenant(@Nullable String tenantId) {
        if (isBlank(tenantId) || !isUuid(tenantId.trim())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tenantId 须为 UUID");
        }
    }

    private static void requireUuid(@Nullable String value, String field) {
        if (isBlank(value) || !isUuid(value.trim())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " 须为 UUID");
        }
    }

    private static void requireReason(@Nullable String reason) {
        if (isBlank(reason)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "变更理由不能为空");
        }
    }

    private static boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** 无租户路径必须先确认平台发布通道已装配，否则 fail-closed（503，不给 NPE）。 */
    private cn.studykid.multiagent.application.cap.PlatformCapabilityPublishService
            requirePlatformPublisher() {
        if (platformPublisher == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "平台 Agent 发布通道未装配，无法处理无租户的全局 Agent 请求");
        }
        return platformPublisher;
    }

    private static boolean isBlank(@Nullable String s) {
        return s == null || s.isBlank();
    }

    private static List<cn.studykid.multiagent.application.cap.ConfigPublishPayload.AgentSpec>
    toAgentSpecs(@NonNull List<AgentSpecView> views) {
        List<cn.studykid.multiagent.application.cap.ConfigPublishPayload.AgentSpec> out =
                new ArrayList<>();
        for (AgentSpecView v : views) {
            out.add(new cn.studykid.multiagent.application.cap.ConfigPublishPayload.AgentSpec(
                    v.agentId(), v.name(), v.description(), nullToEmpty(v.roles()),
                    nullToEmpty(v.enabledSkills()), nullToEmpty(v.skillBundles()),
                    nullToEmpty(v.capabilityTags()),
                    isBlank(v.overrides()) ? "{}" : v.overrides(),
                    isBlank(v.orchestration()) ? "{}" : v.orchestration(),
                    isBlank(v.instructions()) ? "{}" : v.instructions(),
                    isBlank(v.modelRef()) ? null : v.modelRef().trim(),
                    isBlank(v.generationParams()) ? "{}" : v.generationParams()));
        }
        return out;
    }

    private static <T> List<T> nullToEmpty(@Nullable List<T> list) {
        return list == null ? List.of() : list;
    }

    private void recordAudit(@NonNull PlatformPrincipal actor, @NonNull String action,
                             @Nullable String tenantId, @Nullable String targetId) {
        String detail = action + (tenantId == null ? "" : " tenant=" + tenantId);
        audit.record(new PlatformAuditEvent(UUID.randomUUID(), actor.userId(),
                "PLATFORM_USER", detail, "agent_config", targetId,
                "ALLOW", action, null, null, Instant.now(), null));
    }

    // ── DTO ─────────────────────────────────────────────────────────

    /** 创建 Agent 的返回值（agentId 由服务端生成）。 */
    public record AgentCreatedDto(@NonNull String agentId, @Nullable String name,
                                  @Nullable String description) {
    }

    /** 租户-Agent 绑定视图。 */
    public record BindingDto(@NonNull String tenantId, @NonNull String agentId,
                             @NonNull String status, @Nullable Integer pinnedVersion,
                             @NonNull String config, @NonNull Instant createdAt,
                             @NonNull Instant updatedAt) {
        static BindingDto of(TenantAgentBindingPort.BindingItem item) {
            return new BindingDto(item.tenantId(), item.agentId(), item.status(),
                    item.pinnedVersion(), item.config(), item.createdAt(), item.updatedAt());
        }
    }

    /** 绑定写入请求体。 */
    public record BindingView(@NonNull String tenantId, @NonNull String agentId,
                              @Nullable Integer pinnedVersion, @Nullable String config) {
    }

    /** 申请单视图（与平台能力申请单同形状）。 */
    public record RequestDto(@NonNull String requestId, @NonNull String scope,
                             @NonNull String operation, @NonNull String state,
                             @NonNull String requesterId, @Nullable String approverId,
                             @Nullable String decisionNote, @Nullable String failureReason,
                             @NonNull Instant requestedAt, @Nullable Instant decidedAt,
                             @Nullable Instant appliedAt) {
        static RequestDto of(ConfigPublishRequest r) {
            return new RequestDto(r.requestId(),
                    r.scope() == null ? "PLATFORM" : r.scope().name(),
                    r.operation().name(), r.state().name(), r.requesterId(),
                    r.approverId(), r.decisionNote(), r.failureReason(),
                    r.requestedAt(), r.decidedAt(), r.appliedAt());
        }
    }

    /** 权威定义视图（关系字段 + 原文，供编辑回填）。 */
    public record AgentDefinitionDto(@NonNull String agentId, int version,
                                     @Nullable String name, @Nullable String description,
                                     @NonNull List<String> roles,
                                     @NonNull List<String> enabledSkills,
                                     @NonNull List<String> skillBundles,
                                     @NonNull List<String> capabilityTags,
                                     @NonNull String overrides,
                                     @NonNull String orchestration,
                                     @NonNull String instructions,
                                     @Nullable String modelRef,
                                     @NonNull String generationParams,
                                     boolean published) {
        static AgentDefinitionDto of(CapAgentDef def) {
            return new AgentDefinitionDto(def.agentId(), def.version(), def.name(),
                    def.description(), def.roles(), def.enabledSkills(), def.skillBundles(),
                    def.capabilityTags(), def.overrides(), def.orchestration(),
                    def.instructions(), def.modelRef(), def.generationParams(),
                    def.isPublished());
        }
    }

    public record CreateAgentView(@Nullable String tenantId, @Nullable String name,
                                  @Nullable String description) {
    }

    public record PublishRequestView(@Nullable String tenantId,
                                     @Nullable List<AgentSpecView> agents,
                                     @Nullable Long expectedPublishSeq,
                                     @Nullable String reason) {
    }

    public record AgentSpecView(@Nullable String agentId, @Nullable String name,
                                @Nullable String description, @Nullable List<String> roles,
                                @Nullable List<String> enabledSkills,
                                @Nullable List<String> skillBundles,
                                @Nullable List<String> capabilityTags,
                                @Nullable String overrides, @Nullable String orchestration,
                                @Nullable String instructions, @Nullable String modelRef,
                                @Nullable String generationParams) {
    }

    public record LifecycleRequestView(@Nullable String tenantId, @Nullable String objectId,
                                       @Nullable Integer version, @Nullable String expectedFrom,
                                       @Nullable String reason) {
    }

    public record TenantScopedView(@Nullable String tenantId) {
    }

    public record DecisionView(@Nullable String tenantId, @Nullable String decision,
                               @Nullable String note) {
    }
}
