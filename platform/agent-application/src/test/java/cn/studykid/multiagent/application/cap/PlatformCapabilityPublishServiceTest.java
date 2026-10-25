package cn.studykid.multiagent.application.cap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.cap.CapAgentDef;
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
import cn.studykid.multiagent.core.cap.ConfigPublishResult;
import cn.studykid.multiagent.ports.persistence.Page;
import cn.studykid.multiagent.ports.persistence.PlatformOpsSessionPort;
import cn.studykid.multiagent.ports.persistence.PlatformOpsUnavailableException;

/**
 * 平台级能力对象（Tool / Skill / MCP Server）的发布与生命周期服务测试
 * （第三十四批 · C34-2 · PLATFORM 作用域）。
 *
 * <p>⚠️ <b>本类钉住的是「平台通道不得退化成租户通道」这条性质</b>：
 * <ul>
 *   <li>平台的申请单<b>作用域必须是 PLATFORM</b>（{@code tenant_id IS NULL}）——
 *       它要经平台运维连接读写，应用角色连接根本看不见它；</li>
 *   <li>平台发布的<b>作用域必须是 PLATFORM</b>，且<b>不得携带任何租户级对象</b>
 *       （混装会被配置期门禁拒，且发布头的作用域会与内容自相矛盾）；</li>
 *   <li>版本号与内容指纹<b>由服务端决定</b> —— 客户端若能指定这两者，
 *       「内容没变」这个证明就是客户端自己说的；</li>
 *   <li>运维角色未接入时<b>明确失败</b>，绝不回落到应用角色。</li>
 * </ul>
 *
 * <p>⚠️ <b>「看得见」与「不该给的不给」成对断言</b>：
 * 每条「该有的」旁边都有一条「不该有的」——
 * 例如「平台申请单 tenant_id 为 null」旁边必须有
 * 「租户级对象不得出现在平台发布里」。
 */
@DisplayName("C34-2 PlatformCapabilityPublishService · 平台通道")
class PlatformCapabilityPublishServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"),
            ZoneOffset.UTC);
    private static final String REQUESTER = "platform-admin-1";
    private static final String APPROVER = "platform-admin-2";

    // ── 夹具 ──────────────────────────────────────────────────────

    /** 内存申请单仓储（记录状态迁移，便于断言「留痕」）。 */
    private static final class Requests implements ConfigPublishRequestPort {
        final Map<String, ConfigPublishRequest> rows = new LinkedHashMap<>();
        final List<String> marks = new ArrayList<>();
        String lastPayload;
        String lastTenantId = "<unset>";

        @Override
        public ConfigPublishRequest insert(String tenantId,
                                           ConfigPublishRequest.Operation operation,
                                           String payload, String reason, String requesterId) {
            lastTenantId = tenantId;
            lastPayload = payload;
            ConfigPublishRequest row = new ConfigPublishRequest("req-" + rows.size(), tenantId,
                    operation, payload, reason, requesterId,
                    ConfigPublishRequest.State.PENDING, null, null, CLOCK.instant(),
                    null, null, null,
                    tenantId == null ? ConfigRelease.Scope.PLATFORM : ConfigRelease.Scope.TENANT);
            rows.put(row.requestId(), row);
            return row;
        }

        @Override
        public Optional<ConfigPublishRequest> find(String tenantId, String requestId) {
            ConfigPublishRequest row = rows.get(requestId);
            return row == null ? Optional.empty() : Optional.of(row);
        }

        @Override
        public Page<ConfigPublishRequest> list(String tenantId,
                                                ConfigPublishRequest.State state,
                                                String cursor, int limit) {
            return Page.of(List.copyOf(rows.values()), null);
        }

        @Override
        public ConfigPublishRequest decide(String tenantId, String requestId, Decision decision,
                                           String note, String approverId) {
            ConfigPublishRequest row = rows.get(requestId);
            if (row == null) {
                throw new NotFoundException(requestId);
            }
            if (row.requesterId().equals(approverId)) {
                throw new SelfApprovalException();
            }
            ConfigPublishRequest.State target = decision == Decision.APPROVE
                    ? ConfigPublishRequest.State.APPROVED : ConfigPublishRequest.State.REJECTED;
            ConfigPublishRequest updated = new ConfigPublishRequest(row.requestId(), row.tenantId(),
                    row.operation(), row.payload(), row.reason(), row.requesterId(), target,
                    approverId, note, row.requestedAt(), CLOCK.instant(), null, null,
                    row.scope());
            rows.put(requestId, updated);
            return updated;
        }

        @Override
        public ConfigPublishRequest markApplied(String tenantId, String requestId, String actorId,
                                                 Instant appliedAt) {
            ConfigPublishRequest row = rows.get(requestId);
            ConfigPublishRequest updated = new ConfigPublishRequest(row.requestId(), row.tenantId(),
                    row.operation(), row.payload(), row.reason(), row.requesterId(),
                    ConfigPublishRequest.State.APPLIED, row.approverId(), row.decisionNote(),
                    row.requestedAt(), row.decidedAt(), appliedAt, null, row.scope());
            rows.put(requestId, updated);
            marks.add("APPLIED");
            return updated;
        }

        @Override
        public ConfigPublishRequest markFailed(String tenantId, String requestId, String reason) {
            ConfigPublishRequest row = rows.get(requestId);
            ConfigPublishRequest updated = new ConfigPublishRequest(row.requestId(), row.tenantId(),
                    row.operation(), row.payload(), row.reason(), row.requesterId(),
                    ConfigPublishRequest.State.FAILED, row.approverId(), row.decisionNote(),
                    row.requestedAt(), row.decidedAt(), null, reason, row.scope());
            rows.put(requestId, updated);
            marks.add("FAILED:" + reason);
            return updated;
        }
    }

    /** 会话端口：正常时直接执行；可切换为「运维角色未接入」。 */
    private static final class Sessions implements PlatformOpsSessionPort {
        boolean unavailable;

        @Override
        public <T> T inPlatformScopeSession(PlatformOpsAction<T> action) {
            if (unavailable) {
                throw new PlatformOpsUnavailableException("agent.platform-ops.datasource.enabled",
                        "平台运维角色未接入");
            }
            return action.run();
        }
    }

    private final Requests requests = new Requests();
    private final Sessions sessions = new Sessions();
    private final ConfigPublishService publisher = mock(ConfigPublishService.class);
    private final ConfigLifecycleService lifecycle = mock(ConfigLifecycleService.class);
    private final CapToolDefPort toolDefs = mock(CapToolDefPort.class);
    private final CapSkillDefPort skillDefs = mock(CapSkillDefPort.class);
    private final CapMcpServerDefPort serverDefs = mock(CapMcpServerDefPort.class);
    private final ConfigPointerPort pointers = mock(ConfigPointerPort.class);

    private PlatformCapabilityPublishService service() {
        return new PlatformCapabilityPublishService(sessions, requests, publisher, lifecycle,
                toolDefs, skillDefs, serverDefs, pointers, CLOCK);
    }

    private static ConfigPublishPayload.ToolSpec toolSpec(String id) {
        return new ConfigPublishPayload.ToolSpec(id, "工具 " + id, "NATIVE", "READ",
                null, null, "{}", "{}");
    }

    private static ConfigPublishPayload.SkillSpec skillSpec(String id) {
        return new ConfigPublishPayload.SkillSpec(id, "技能 " + id, "描述",
                List.of("tool-1"), List.of(), "READ", "{}", List.of());
    }

    private static ConfigPublishPayload.McpServerSpec mcpSpec(String id) {
        return new ConfigPublishPayload.McpServerSpec(id, "HTTP", "https://example.com/mcp",
                null, "TOKEN", "INTERNAL", null, "READ", null);
    }

    // ── 用例 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("✅ 申请单落库时作用域必须是 PLATFORM（tenant_id 为 null）")
    void requestPublishUsesPlatformScope() {
        service().requestPublish(List.of(toolSpec("tool-1")), List.of(), List.of(),
                null, "登记一个工具", REQUESTER);

        assertThat(requests.lastTenantId)
                .as("⛔ 平台申请单带了 tenant_id：它会落到某个租户的申请单清单里，"
                        + "而租户根本无权审批平台级变更")
                .isNull();
        assertThat(requests.rows.values())
                .allSatisfy(row -> assertThat(row.scope()).isEqualTo(ConfigRelease.Scope.PLATFORM));
    }

    @Test
    @DisplayName("✅ 版本号由服务端决定 = 现有最大版本 + 1（客户端无从指定）")
    void versionIsAssignedByServer() {
        when(toolDefs.listAll()).thenReturn(List.of(
                new CapToolDef("tool-1", 7, "旧", CapToolKind.NATIVE,
                        CapabilityRiskLevel.READ, null, null, "{}", "{}", "fp", 1,
                        CLOCK.instant(), null)));

        service().requestPublish(List.of(toolSpec("tool-1")), List.of(), List.of(),
                null, "改一个工具", REQUESTER);

        var command = captureCommand();
        assertThat(command.tools()).hasSize(1);
        assertThat(command.tools().get(0).version())
                .as("⛔ 版本号必须是「现有最大版本 + 1」—— "
                        + "客户端若能指定版本，它就能指定「内容没变」这个证明")
                .isEqualTo(8);
        assertThat(command.tools().get(0).configFingerprint())
                .as("⛔ 内容指纹必须由服务端计算，不得是占位值")
                .isNotEqualTo("pending")
                .startsWith(CapObjectFingerprint.PREFIX);
    }

    @Test
    @DisplayName("⛔ 平台发布不得携带任何租户级对象")
    void platformPublishCarriesNoTenantObjects() {
        service().requestPublish(List.of(toolSpec("tool-1")), List.of(), List.of(),
                null, "登记一个工具", REQUESTER);

        var command = captureCommand();
        assertThat(command.scope()).isEqualTo(ConfigRelease.Scope.PLATFORM);
        assertThat(command.tenantId())
                .as("⛔ PLATFORM 作用域携带 tenantId 会让发布头的作用域与内容自相矛盾")
                .isNull();
        assertThat(command.agents()).isEmpty();
        assertThat(command.resultContracts()).isEmpty();
        assertThat(command.routingPolicies()).isEmpty();
    }

    @Test
    @DisplayName("✅ 三类平台对象各自进自己的槽位")
    void allThreePlatformSlotsAreCarried() {
        service().requestPublish(List.of(toolSpec("tool-1")), List.of(skillSpec("skill-1")),
                List.of(mcpSpec("mcp-1")), null, "一次登记三类对象", REQUESTER);

        var command = captureCommand();
        assertThat(command.tools()).hasSize(1);
        assertThat(command.skills()).hasSize(1);
        assertThat(command.mcpServers()).hasSize(1);
    }

    @Test
    @DisplayName("⛔ 申请阶段就跑门禁预检：非法方案不占审批名额")
    void illegalSchemeIsRejectedAtRequestTime() {
        org.mockito.Mockito.doThrow(new ConfigPublishRejectedException(
                ReasonCode.RESOURCE_UNKNOWN, "scope", "非法"))
                .when(publisher).validateForReview(any());

        assertThatThrownBy(() -> service().requestPublish(
                List.of(toolSpec("tool-1")), List.of(), List.of(), null, "非法", REQUESTER))
                .isInstanceOf(ConfigPublishRejectedException.class);
        assertThat(requests.rows)
                .as("⛔ 门禁没过却落了申请单：等于先占住一个审批名额")
                .isEmpty();
    }

    @Test
    @DisplayName("✅ 审批：状态 PENDING → APPROVED，审批人不得等于申请人")
    void decideApproves() {
        service().requestPublish(List.of(toolSpec("tool-1")), List.of(), List.of(),
                null, "登记一个工具", REQUESTER);
        String requestId = requests.rows.keySet().iterator().next();

        var decided = service().decide(requestId, ConfigPublishRequestPort.Decision.APPROVE,
                "同意", APPROVER);
        assertThat(decided.state()).isEqualTo(ConfigPublishRequest.State.APPROVED);
        assertThat(decided.approverId()).isEqualTo(APPROVER);
    }

    @Test
    @DisplayName("⛔ 自审批被拒（双人复核）")
    void selfApprovalIsRejected() {
        service().requestPublish(List.of(toolSpec("tool-1")), List.of(), List.of(),
                null, "登记一个工具", REQUESTER);
        String requestId = requests.rows.keySet().iterator().next();

        assertThatThrownBy(() -> service().decide(requestId,
                ConfigPublishRequestPort.Decision.APPROVE, null, REQUESTER))
                .isInstanceOf(ConfigPublishRequestPort.SelfApprovalException.class);
    }

    @Test
    @DisplayName("✅ 执行：APPROVED → 发布 → APPLIED")
    void applyPublishesAndMarksApplied() {
        when(publisher.publish(any())).thenReturn(stubResult());
        service().requestPublish(List.of(toolSpec("tool-1")), List.of(), List.of(),
                null, "登记一个工具", REQUESTER);
        String requestId = requests.rows.keySet().iterator().next();
        service().decide(requestId, ConfigPublishRequestPort.Decision.APPROVE, "同意", APPROVER);

        var applied = service().apply(requestId, APPROVER);

        assertThat(applied.state()).isEqualTo(ConfigPublishRequest.State.APPLIED);
        assertThat(requests.marks).containsExactly("APPLIED");
        verify(publisher).publish(any(ConfigPublishCommand.class));
    }

    @Test
    @DisplayName("⛔ 执行失败必须留痕（批过但没生效的申请不能只存在于异常栈里）")
    void failedApplyIsRecorded() {
        when(publisher.publish(any())).thenThrow(new ConfigPublishRejectedException(
                ReasonCode.RESOURCE_UNKNOWN, "scope", "发布失败"));
        service().requestPublish(List.of(toolSpec("tool-1")), List.of(), List.of(),
                null, "登记一个工具", REQUESTER);
        String requestId = requests.rows.keySet().iterator().next();
        service().decide(requestId, ConfigPublishRequestPort.Decision.APPROVE, "同意", APPROVER);

        assertThatThrownBy(() -> service().apply(requestId, APPROVER))
                .isInstanceOf(ConfigPublishRejectedException.class);
        assertThat(requests.marks)
                .as("⛔ 失败未留痕：审批人批过的东西在没人的情况下消失了")
                .hasSize(1);
        assertThat(requests.marks.get(0)).startsWith("FAILED:");
    }

    @Test
    @DisplayName("⛔ 运维角色未接入 → 明确失败，绝不回落应用角色")
    void unavailableOpsChannelFailsClosed() {
        sessions.unavailable = true;

        assertThatThrownBy(() -> service().requestPublish(
                List.of(toolSpec("tool-1")), List.of(), List.of(), null, "登记", REQUESTER))
                .isInstanceOf(PlatformOpsUnavailableException.class);
        assertThat(requests.rows)
                .as("⛔ 运维通道不可用却落了申请单：申请单根本写不进平台表")
                .isEmpty();
    }

    @Test
    @DisplayName("⛔ 生命周期迁移的对象类型闸门：租户级对象不走平台通道")
    void lifecycleRejectsTenantScopedTypes() {
        assertThatThrownBy(() -> service().requestLifecycle(
                new ConfigPublishPayload.LifecycleSpec("agent", "agent-1", 1,
                        "PUBLISHED", "DEPRECATED"), "停用", REQUESTER))
                .isInstanceOf(ConfigPublishRejectedException.class)
                .hasMessageContaining("平台通道只管理平台级对象");
    }

    @Test
    @DisplayName("✅ 生命周期迁移：PUBLISHED → DEPRECATED（停用即「删除」的正确语义）")
    void lifecycleDeprecates() {
        when(lifecycle.transition(any(), any(), any(), anyInt(), any(), any()))
                .thenReturn(true);

        service().requestLifecycle(new ConfigPublishPayload.LifecycleSpec("tool", "tool-1", 1,
                "PUBLISHED", "DEPRECATED"), "停用", REQUESTER);
        String requestId = requests.rows.keySet().iterator().next();
        service().decide(requestId, ConfigPublishRequestPort.Decision.APPROVE, "同意", APPROVER);
        service().apply(requestId, APPROVER);

        // ⚠️ 断言的是「**执行**时才发生迁移」——
        //    申请阶段就迁移 = 「还没审批就停用了」。
        verify(lifecycle).transition(any(), any(), any(), anyInt(),
                eq(ConfigLifecycleState.PUBLISHED), eq(ConfigLifecycleState.DEPRECATED));
        assertThat(requests.marks).containsExactly("APPLIED");
    }

    @Test
    @DisplayName("⛔ 生命周期迁移的原状态不符 = 并发竞争（不是「迁移非法」）")
    void lifecycleCasMismatchIsDistinct() {
        when(lifecycle.transition(any(), any(), any(), anyInt(), any(), any()))
                .thenReturn(false);
        service().requestLifecycle(new ConfigPublishPayload.LifecycleSpec("skill", "skill-1", 1,
                "PUBLISHED", "DEPRECATED"), "停用", REQUESTER);
        String requestId = requests.rows.keySet().iterator().next();
        service().decide(requestId, ConfigPublishRequestPort.Decision.APPROVE, "同意", APPROVER);

        assertThatThrownBy(() -> service().apply(requestId, APPROVER))
                .isInstanceOf(PlatformCapabilityPublishService
                        .ConfigPublishBaselineConflictException.class)
                .hasMessageContaining("原状态不符");
    }

    @Test
    @DisplayName("⛔ 平台申请单的载荷里租户级槽位必须为空数组（不得携带租户级对象）")
    void payloadHasNoTenantSlots() {
        service().requestPublish(List.of(toolSpec("tool-1")), List.of(), List.of(),
                null, "登记", REQUESTER);

        // ⚠️ 断言的是「槽位**为空**」而不是「键不存在」——
        //    载荷结构固定带着这四个槽位（空数组），
        //    真正的判据是它们**不许有内容**（非空会被配置期门禁拒绝）。
        assertThat(requests.lastPayload)
                .as("⛔ 平台申请单的载荷里携带了租户级对象")
                .contains("\"agents\":[]")
                .contains("\"resultContracts\":[]")
                .contains("\"routingPolicies\":[]")
                .contains("\"tools\":[");
    }

    @Test
    @DisplayName("⛔ MCP 的 exposedToolAllowlist 的 null 与 [] 不可归一化")
    void mcpAllowlistNullAndEmptyAreDistinct() {
        ConfigPublishPayload.McpServerSpec unset = new ConfigPublishPayload.McpServerSpec(
                "mcp-1", "HTTP", "https://example.com/mcp", null, "TOKEN", "INTERNAL",
                null, "READ", null);
        ConfigPublishPayload.McpServerSpec empty = new ConfigPublishPayload.McpServerSpec(
                "mcp-2", "HTTP", "https://example.com/mcp", null, "TOKEN", "INTERNAL",
                List.of(), "READ", null);

        assertThat(unset.exposedToolAllowlist())
                .as("⛔ null 被归一成 []：「未配置」会变成「显式禁止一切」，"
                        + "那是可用性事故而不是保守收紧")
                .isNull();
        assertThat(empty.exposedToolAllowlist()).isEmpty();
    }

    // ── 辅助 ──────────────────────────────────────────────────────

    private ConfigPublishCommand captureCommand() {
        org.mockito.ArgumentCaptor<ConfigPublishCommand> captor =
                org.mockito.ArgumentCaptor.forClass(ConfigPublishCommand.class);
        verify(publisher).validateForReview(captor.capture());
        return captor.getValue();
    }

    private static ConfigPublishResult stubResult() {
        ConfigRelease release = new ConfigRelease(1L, 1L, ConfigRelease.Scope.PLATFORM, null,
                "[]", "fp", ConfigRelease.Status.PUBLISHED, REQUESTER, APPROVER,
                CLOCK.instant(), CLOCK.instant());
        ConfigPointer pointer = new ConfigPointer(ConfigPointer.pointerKey(
                ConfigRelease.Scope.PLATFORM, null), ConfigRelease.Scope.PLATFORM, null,
                1L, 1L, CLOCK.instant());
        return new ConfigPublishResult(release, pointer, 1);
    }
}
