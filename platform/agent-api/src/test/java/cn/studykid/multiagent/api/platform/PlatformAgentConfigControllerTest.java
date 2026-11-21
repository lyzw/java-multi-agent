package cn.studykid.multiagent.api.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.web.server.ResponseStatusException;

import cn.studykid.multiagent.api.security.PlatformSecurity;
import cn.studykid.multiagent.application.cap.ConfigPublishPayload;
import cn.studykid.multiagent.application.cap.ConfigPublishWorkflowService;
import cn.studykid.multiagent.core.cap.ConfigPublishRequest;
import cn.studykid.multiagent.core.cap.ConfigRelease;
import cn.studykid.multiagent.ports.audit.PlatformAuditPort;
import cn.studykid.multiagent.ports.audit.PlatformAuditPort.PlatformAuditEvent;
import cn.studykid.multiagent.ports.audit.PlatformAuditPort.PlatformAuditQuery;
import cn.studykid.multiagent.ports.persistence.AgentManagePort;
import cn.studykid.multiagent.ports.persistence.AgentQueryPort.AgentItem;
import cn.studykid.multiagent.ports.persistence.AgentQueryPort.AgentVersionItem;
import cn.studykid.multiagent.ports.persistence.CapAgentDefPort;
import cn.studykid.multiagent.ports.persistence.ConfigPublishRequestPort;
import cn.studykid.multiagent.ports.persistence.TenantAgentBindingPort;
import cn.studykid.multiagent.ports.security.AccessDeniedException;
import cn.studykid.multiagent.ports.security.AuthorizationDecision;
import cn.studykid.multiagent.ports.security.PlatformAuthorizationService;
import cn.studykid.multiagent.ports.security.PlatformPrincipal;

/**
 * 平台控制台 · 运营代租户配置 Agent 端点单测（直构造，不启 Spring）。
 *
 * <p>断言三件真实语义（撤掉对应修复点会红）：
 * <ol>
 *   <li><b>闸门先行</b>：无 platform.resource.manage 时在触达 workflow 之前 403；</li>
 *   <li><b>tenantId 显式且合法</b>：非 UUID tenantId 在触达 workflow 之前 400
 *       （不能让原始 tenantId 进 SQL）；</li>
 *   <li><b>复用租户链工作流</b>：合法请求把显式 tenantId 传给
 *       {@link ConfigPublishWorkflowService#requestPublish} / {@code requestLifecycle}。</li>
 * </ol>
 */
class PlatformAgentConfigControllerTest {

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String TENANT_B = "33333333-3333-3333-3333-333333333333";
    private static final String AGENT = "22222222-2222-2222-2222-222222222222";
    private static final PlatformPrincipal MANAGER = new PlatformPrincipal(
            "ops-user", Set.of("PLATFORM_ADMIN"), 1, false);

    private static final class RecordingAuthorizer implements PlatformAuthorizationService {
        final List<String> requested = new ArrayList<>();
        final Set<String> allowed;

        RecordingAuthorizer(String... allowed) {
            this.allowed = Set.of(allowed);
        }

        @Override
        public AuthorizationDecision authorize(PlatformPrincipal principal, String action,
                                               String targetType, String targetId) {
            requested.add(action);
            return allowed.contains(action)
                    ? AuthorizationDecision.allow(1)
                    : AuthorizationDecision.deny("NO_PERMISSION", 1);
        }
    }

    /** 工作流替身：记录 tenantId，返回最小申请单。 */
    private static final class FakeWorkflow extends ConfigPublishWorkflowService {
        final List<String> publishTenants = new ArrayList<>();
        final List<String> lifecycleTenants = new ArrayList<>();

        FakeWorkflow() {
            super(null, null, null, null, null, null, java.time.Clock.systemUTC());
        }

        @Override
        public ConfigPublishRequest requestPublish(String tenantId, ConfigRelease.Scope scope,
                                                   List<ConfigPublishPayload.AgentSpec> agents,
                                                   @Nullable Long expectedPublishSeq,
                                                   String reason, String requesterId) {
            publishTenants.add(tenantId);
            return request(tenantId, ConfigPublishRequest.Operation.PUBLISH);
        }

        @Override
        public ConfigPublishRequest requestLifecycle(String tenantId, ConfigRelease.Scope scope,
                                                     ConfigPublishPayload.LifecycleSpec spec,
                                                     String reason, String requesterId) {
            lifecycleTenants.add(tenantId);
            return request(tenantId, ConfigPublishRequest.Operation.LIFECYCLE_TRANSITION);
        }

        private static ConfigPublishRequest request(String tenantId, ConfigPublishRequest.Operation op) {
            return new ConfigPublishRequest("req-1", tenantId, op, "{}", "reason",
                    "ops-user", ConfigPublishRequest.State.PENDING, null, null,
                    Instant.now(), null, null, null, ConfigRelease.Scope.TENANT,
                    null, null, null, null, null);
        }
    }

    /**
     * 平台发布通道替身：记录无租户发布是否被路由过来。
     * 用 9 参构造（agentDefs=null），仅覆写 {@code requestPublishAgents}。
     */
    private static final class FakePlatformPublisher
            extends cn.studykid.multiagent.application.cap.PlatformCapabilityPublishService {
        int agentPublishCalls;
        List<ConfigPublishPayload.AgentSpec> lastAgents;

        FakePlatformPublisher() {
            super(null, null, null, null, null, null, null, null, java.time.Clock.systemUTC());
        }

        @Override
        public ConfigPublishRequest requestPublishAgents(
                List<ConfigPublishPayload.AgentSpec> agents,
                @Nullable Long expectedPublishSeq, String reason, String requesterId) {
            agentPublishCalls++;
            lastAgents = agents;
            return new ConfigPublishRequest("req-global", null,
                    ConfigPublishRequest.Operation.PUBLISH, "{}", reason, requesterId,
                    ConfigPublishRequest.State.PENDING, null, null,
                    Instant.now(), null, null, null, ConfigRelease.Scope.PLATFORM,
                    null, null, null, null, null);
        }
    }

    /** 只用于构造控制器；本测的用例虽触达 record，但审计写入无需外部依赖。 */
    private static final PlatformAuditPort NOOP_AUDIT = new PlatformAuditPort() {
        @Override
        public void record(PlatformAuditEvent event) {
            // 审计写入由真实 Bean 落库；本单测只关心闸门与委派语义。
        }

        @Override
        public List<PlatformAuditEvent> query(PlatformAuditQuery query) {
            return List.of();
        }
    };

    /** 绑定替身：验证「分配租户」是直接配置 CRUD，不经过三步发布工作流。 */
    private static final class FakeBindings implements TenantAgentBindingPort {
        final Map<String, BindingItem> rows = new LinkedHashMap<>();
        final List<String> calls = new ArrayList<>();

        private static String key(String tenantId, String agentId) {
            return tenantId + "/" + agentId;
        }

        @Override
        public List<BindingItem> listByTenant(String tenantId) {
            calls.add("listByTenant:" + tenantId);
            return rows.values().stream()
                    .filter(i -> tenantId.equals(i.tenantId()))
                    .toList();
        }

        @Override
        public List<BindingItem> listByAgent(String agentId) {
            calls.add("listByAgent:" + agentId);
            return rows.values().stream()
                    .filter(i -> agentId.equals(i.agentId()))
                    .toList();
        }

        @Override
        public Optional<BindingItem> find(String tenantId, String agentId) {
            return Optional.ofNullable(rows.get(key(tenantId, agentId)));
        }

        @Override
        public BindingItem bind(String tenantId, String agentId, Integer pinnedVersion,
                                String config, String actorId) {
            calls.add("bind:" + tenantId + ":" + agentId);
            BindingItem item = new BindingItem(tenantId, agentId, "ACTIVE", pinnedVersion,
                    config == null ? "{}" : config, Instant.EPOCH, Instant.EPOCH);
            rows.put(key(tenantId, agentId), item);
            return item;
        }

        @Override
        public BindingItem update(String tenantId, String agentId, Integer pinnedVersion,
                                  String config, String actorId) {
            calls.add("update:" + tenantId + ":" + agentId);
            BindingItem old = find(tenantId, agentId)
                    .orElseThrow(() -> new BindingNotFoundException(tenantId, agentId));
            BindingItem item = new BindingItem(tenantId, agentId, old.status(), pinnedVersion,
                    config == null ? old.config() : config, old.createdAt(), Instant.EPOCH);
            rows.put(key(tenantId, agentId), item);
            return item;
        }

        @Override
        public void unbind(String tenantId, String agentId, String actorId) {
            calls.add("unbind:" + tenantId + ":" + agentId);
            BindingItem old = find(tenantId, agentId)
                    .orElseThrow(() -> new BindingNotFoundException(tenantId, agentId));
            rows.put(key(tenantId, agentId), new BindingItem(tenantId, agentId, "SUSPENDED",
                    old.pinnedVersion(), old.config(), old.createdAt(), Instant.EPOCH));
        }
    }

    @Test
    @DisplayName("✅ 发布申请：过 manage 闸门 + 合法 tenantId → 复用工作流并透传 tenantId")
    void publishDelegatesWithTenantId() {
        RecordingAuthorizer auth = new RecordingAuthorizer("platform.resource.manage");
        FakeWorkflow workflow = new FakeWorkflow();
        PlatformAgentConfigController controller = new PlatformAgentConfigController(
                workflow, (AgentManagePort) null, (CapAgentDefPort) null, new FakeBindings(),
                new PlatformSecurity(auth), NOOP_AUDIT);

        controller.requestPublish(MANAGER, new PlatformAgentConfigController.PublishRequestView(
                TENANT,
                List.of(new PlatformAgentConfigController.AgentSpecView(
                        "agent-1", "助手", null, List.of(), List.of(), List.of(), List.of(),
                        "{}", "{}", "{}", null, "{}")),
                null, "新增助手"));

        assertThat(auth.requested).containsExactly("platform.resource.manage");
        assertThat(workflow.publishTenants).containsExactly(TENANT);
    }

    @Test
    @DisplayName("✅ 停用申请：type 固定 agent / to 固定 DEPRECATED，透传显式 tenantId")
    void lifecycleDelegatesWithTenantId() {
        RecordingAuthorizer auth = new RecordingAuthorizer("platform.resource.manage");
        FakeWorkflow workflow = new FakeWorkflow();
        PlatformAgentConfigController controller = new PlatformAgentConfigController(
                workflow, null, null, new FakeBindings(), new PlatformSecurity(auth), NOOP_AUDIT);

        controller.requestLifecycle(MANAGER, new PlatformAgentConfigController.LifecycleRequestView(
                TENANT, "agent-1", 3, "PUBLISHED", "下线"));

        assertThat(workflow.lifecycleTenants).containsExactly(TENANT);
    }

    @Test
    @DisplayName("⛔ 无 manage 权限 → AccessDeniedException，工作流零触达（闸门先行）")
    void deniedNeverTouchesWorkflow() {
        RecordingAuthorizer auth = new RecordingAuthorizer("platform.resource.read");
        FakeWorkflow workflow = new FakeWorkflow();
        PlatformAgentConfigController controller = new PlatformAgentConfigController(
                workflow, null, null, new FakeBindings(), new PlatformSecurity(auth), NOOP_AUDIT);

        assertThatThrownBy(() ->
                controller.requestPublish(MANAGER, new PlatformAgentConfigController.PublishRequestView(
                        TENANT,
                        List.of(new PlatformAgentConfigController.AgentSpecView(
                                "agent-1", "助手", null, List.of(), List.of(), List.of(), List.of(),
                                "{}", "{}", "{}", null, "{}")),
                        null, "新增助手")))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(workflow.publishTenants).isEmpty();
    }

    @Test
    @DisplayName("⛔ tenantId 非 UUID → 400，工作流零触达（原始 tenantId 不进 SQL）")
    void nonUuidTenantRejected() {
        RecordingAuthorizer auth = new RecordingAuthorizer("platform.resource.manage");
        FakeWorkflow workflow = new FakeWorkflow();
        PlatformAgentConfigController controller = new PlatformAgentConfigController(
                workflow, null, null, new FakeBindings(), new PlatformSecurity(auth), NOOP_AUDIT);

        assertThatThrownBy(() ->
                controller.requestLifecycle(MANAGER, new PlatformAgentConfigController.LifecycleRequestView(
                        "tenant-alpha-not-uuid", "agent-1", 3, "PUBLISHED", "下线")))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(workflow.lifecycleTenants).isEmpty();
    }

    @Test
    @DisplayName("✅ 分配租户：全局 Agent 可绑定多个租户，直接写配置、不走发布工作流")
    void bindSupportsManyToManyAssignments() {
        RecordingAuthorizer auth = new RecordingAuthorizer(
                "platform.resource.manage", "platform.resource.read");
        FakeWorkflow workflow = new FakeWorkflow();
        FakeBindings bindings = new FakeBindings();
        PlatformAgentConfigController controller = new PlatformAgentConfigController(
                workflow, null, null, bindings, new PlatformSecurity(auth), NOOP_AUDIT);

        var first = controller.bind(MANAGER,
                new PlatformAgentConfigController.BindingView(TENANT, AGENT, 2, "{\"k\":1}"))
                .getBody();
        var second = controller.bind(MANAGER,
                new PlatformAgentConfigController.BindingView(TENANT_B, AGENT, null, null))
                .getBody();

        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(first.status()).isEqualTo("ACTIVE");
        assertThat(first.pinnedVersion()).isEqualTo(2);
        assertThat(second.status()).isEqualTo("ACTIVE");
        assertThat(controller.listAgentBindings(MANAGER, AGENT).getBody()).hasSize(2);
        assertThat(workflow.publishTenants).isEmpty();
        assertThat(workflow.lifecycleTenants).isEmpty();
    }

    @Test
    @DisplayName("✅ 解除分配：逻辑停用绑定（SUSPENDED），保留历史且不触达发布工作流")
    void unbindSuspendsBindingInsteadOfDeleting() {
        RecordingAuthorizer auth = new RecordingAuthorizer("platform.resource.manage");
        FakeWorkflow workflow = new FakeWorkflow();
        FakeBindings bindings = new FakeBindings();
        PlatformAgentConfigController controller = new PlatformAgentConfigController(
                workflow, null, null, bindings, new PlatformSecurity(auth), NOOP_AUDIT);

        controller.bind(MANAGER,
                new PlatformAgentConfigController.BindingView(TENANT, AGENT, null, null));
        controller.unbind(MANAGER, TENANT, AGENT);

        assertThat(bindings.find(TENANT, AGENT)).isPresent();
        assertThat(bindings.find(TENANT, AGENT).orElseThrow().status()).isEqualTo("SUSPENDED");
        assertThat(workflow.publishTenants).isEmpty();
        assertThat(workflow.lifecycleTenants).isEmpty();
    }

    @Test
    @DisplayName("✅ 无租户发布申请：路由到 PLATFORM 通道，租户工作流零触达")
    void publishWithoutTenantRoutesToPlatformChannel() {
        RecordingAuthorizer auth = new RecordingAuthorizer("platform.resource.manage");
        FakeWorkflow workflow = new FakeWorkflow();
        FakePlatformPublisher platform = new FakePlatformPublisher();
        PlatformAgentConfigController controller = new PlatformAgentConfigController(
                workflow, platform, null, null, new FakeBindings(),
                new PlatformSecurity(auth), NOOP_AUDIT);

        controller.requestPublish(MANAGER, new PlatformAgentConfigController.PublishRequestView(
                null,
                List.of(new PlatformAgentConfigController.AgentSpecView(
                        AGENT, "助手", null, List.of(), List.of(), List.of(), List.of(),
                        "{}", "{}", "{}", null, "{}")),
                null, "新增全局助手"));

        assertThat(platform.agentPublishCalls).isEqualTo(1);
        assertThat(platform.lastAgents).hasSize(1);
        assertThat(workflow.publishTenants).isEmpty();
    }

    @Test
    @DisplayName("✅ 新建 Agent 无 tenantId：透传 null，只建全局行不建绑定（仓储语义由 IT 覆盖）")
    void createAgentWithoutTenantPassesNull() {
        RecordingAuthorizer auth = new RecordingAuthorizer("platform.resource.manage");
        final String[] captured = new String[1];
        AgentManagePort manage = new AgentManagePort() {
            @Override
            public @NonNull AgentItem
                    createAgent(@Nullable String tenantId, @NonNull String name,
                                @Nullable String description, @NonNull String creatorId) {
                captured[0] = tenantId; // 故意写 null 以验证透传（铁律 29：List.of 拒绝 null）
                return new AgentItem(
                        AGENT, name, description, 0, 0, null, null);
            }

            @Override
            public @NonNull AgentVersionItem
                    publishVersion(@NonNull String tenantId, @NonNull String agentId,
                                   @NonNull String definition, @NonNull String publisherId) {
                throw new UnsupportedOperationException("本用例不触达 publishVersion");
            }
        };
        PlatformAgentConfigController controller = new PlatformAgentConfigController(
                new FakeWorkflow(), null, manage, null, new FakeBindings(),
                new PlatformSecurity(auth), NOOP_AUDIT);

        controller.createAgent(MANAGER,
                new PlatformAgentConfigController.CreateAgentView(null, "全局助手", "desc"));

        assertThat(captured[0]).isNull();
    }

    @Test
    @DisplayName("⛔ 平台发布通道未装配 + 无租户 → 503，不给 NPE（fail-closed）")
    void publishWithoutTenantNoChannelGives503() {
        RecordingAuthorizer auth = new RecordingAuthorizer("platform.resource.manage");
        PlatformAgentConfigController controller = new PlatformAgentConfigController(
                new FakeWorkflow(), null, null, new FakeBindings(),
                new PlatformSecurity(auth), NOOP_AUDIT);

        assertThatThrownBy(() ->
                controller.requestPublish(MANAGER, new PlatformAgentConfigController.PublishRequestView(
                        null,
                        List.of(new PlatformAgentConfigController.AgentSpecView(
                                AGENT, "助手", null, List.of(), List.of(), List.of(), List.of(),
                                "{}", "{}", "{}", null, "{}")),
                        null, "新增全局助手")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("503");
    }
}
