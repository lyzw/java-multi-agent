package cn.studykid.multiagent.application.cap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.application.cap.PublishPolicyService.ResourceRef;
import cn.studykid.multiagent.core.cap.ConfigPublishRequest;
import cn.studykid.multiagent.core.cap.PublishPolicy;
import cn.studykid.multiagent.core.cap.PublishPolicy.PublishMode;
import cn.studykid.multiagent.core.cap.ConfigRelease;
import cn.studykid.multiagent.ports.persistence.PublishPolicyPort;

/**
 * 按策略分流的判定逻辑测试。
 *
 * <p>覆盖：跨作用域 GLOBAL 回退、一批资源「最严格者胜」、
 * 自动放行主体与申请人不同（满足申请单双人约束但不冒充真人复核）。
 */
class PublishPolicyRoutingTest {

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final Instant NOW = Instant.parse("2026-11-21T00:00:00Z");

    @Test
    @DisplayName("精确作用域无策略时，回退到另一作用域的 GLOBAL 策略")
    void globalFallbackAcrossScopes() {
        StubPort port = new StubPort();
        // 策略配在 TENANT 侧；发布发生在 PLATFORM 通道（能力目录常见场景）
        port.rows.add(policy("TENANT", TENANT, "GLOBAL", null, null, PublishMode.MANUAL));
        PublishPolicyService service = new PublishPolicyService(port);

        assertThat(service.resolveWithGlobalFallback("PLATFORM", null, "TOOL", "tool-1"))
                .isEqualTo(PublishMode.MANUAL);
    }

    @Test
    @DisplayName("精确策略优先于跨作用域 GLOBAL 默认")
    void precisePolicyWinsOverFallback() {
        StubPort port = new StubPort();
        port.rows.add(policy("TENANT", TENANT, "GLOBAL", null, null, PublishMode.MANUAL));
        port.rows.add(policy("PLATFORM", null, "RESOURCE_TYPE", "AGENT", null,
                PublishMode.APPROVAL_MANUAL));
        PublishPolicyService service = new PublishPolicyService(port);

        assertThat(service.resolveWithGlobalFallback("PLATFORM", null, "AGENT", "a-1"))
                .isEqualTo(PublishMode.APPROVAL_MANUAL);
        assertThat(service.resolveWithGlobalFallback("PLATFORM", null, "TOOL", "t-1"))
                .isEqualTo(PublishMode.MANUAL); // 无精确策略 → 回退
    }

    @Test
    @DisplayName("批量资源取最严格模式：混入审批策略则整批走审批")
    void strictestModeWins() {
        StubPort port = new StubPort();
        port.rows.add(policy("PLATFORM", null, "RESOURCE_TYPE", "TOOL", null, PublishMode.DIRECT));
        port.rows.add(policy("PLATFORM", null, "RESOURCE_TYPE", "SKILL", null,
                PublishMode.APPROVAL_MANUAL));
        PublishPolicyService service = new PublishPolicyService(port);

        assertThat(service.resolveForResources("PLATFORM", null,
                List.of(ResourceRef.of("TOOL", "t-1")))).isEqualTo(PublishMode.DIRECT);
        assertThat(service.resolveForResources("PLATFORM", null, List.of(
                ResourceRef.of("TOOL", "t-1"), ResourceRef.of("SKILL", "s-1"))))
                .isEqualTo(PublishMode.APPROVAL_MANUAL);
    }

    @Test
    @DisplayName("四种模式的审批/自动发布语义")
    void modeSemantics() {
        assertThat(PublishMode.DIRECT.requiresApproval()).isFalse();
        assertThat(PublishMode.DIRECT.autoPublishOnApproval()).isTrue();
        assertThat(PublishMode.MANUAL.requiresApproval()).isFalse();
        assertThat(PublishMode.MANUAL.autoPublishOnApproval()).isFalse();
        assertThat(PublishMode.APPROVAL_MANUAL.requiresApproval()).isTrue();
        assertThat(PublishMode.APPROVAL_AUTO.requiresApproval()).isTrue();
        assertThat(PublishMode.APPROVAL_AUTO.autoPublishOnApproval()).isTrue();
    }

    @Test
    @DisplayName("系统自动放行主体与申请人不同，且留痕文案可区分")
    void autoApproverIsNotRequester() {
        String requester = "22222222-2222-2222-2222-222222222222";
        assertThat(PublishPolicyService.POLICY_AUTO_APPROVER).isNotEqualTo(requester);
        assertThat(PublishPolicyService.POLICY_AUTO_NOTE_PREFIX).contains("自动放行");

        // 申请单构造器会拒绝审批人 = 申请人；系统主体必须能顺利通过
        assertThatCode(() -> new ConfigPublishRequest(
                UUID.randomUUID().toString(), null, ConfigPublishRequest.Operation.PUBLISH,
                "{}", "reason", requester, ConfigPublishRequest.State.PENDING,
                PublishPolicyService.POLICY_AUTO_APPROVER, null, NOW, null, null, null,
                ConfigRelease.Scope.PLATFORM, null, null, null, null, null))
                .doesNotThrowAnyException();
    }

    private static PublishPolicy policy(String scope, String tenantId, String level,
                                         String kind, String resourceId, PublishMode mode) {
        return new PublishPolicy(UUID.randomUUID(), scope, tenantId, level, kind, resourceId,
                mode.name(), true, "test", NOW, NOW);
    }

    /** 内存仓储：只实现分流测试用到的读取路径。 */
    private static final class StubPort implements PublishPolicyPort {
        final List<PublishPolicy> rows = new ArrayList<>();

        @Override
        public PublishPolicy insert(PublishPolicy policy) {
            rows.add(policy);
            return policy;
        }

        @Override
        public Optional<PublishPolicy> findById(String scope, String tenantId, UUID policyId) {
            return rows.stream().filter(p -> p.policyId().equals(policyId)).findFirst();
        }

        @Override
        public Optional<PublishPolicy> update(PublishPolicy policy) {
            return Optional.of(policy);
        }

        @Override
        public boolean delete(String scope, String tenantId, UUID policyId) {
            return rows.removeIf(p -> p.policyId().equals(policyId));
        }

        @Override
        public List<PublishPolicy> listEnabled(String scope, String tenantId) {
            return rows.stream()
                    .filter(p -> p.enabled() && p.scope().equals(scope))
                    .filter(p -> tenantId == null
                            ? p.tenantId() == null
                            : p.tenantId() == null || p.tenantId().equals(tenantId))
                    .toList();
        }

        @Override
        public Optional<PublishPolicy> findByTarget(String scope, String tenantId, String level,
                                                     String kind, String resourceId) {
            return rows.stream()
                    .filter(p -> p.scope().equals(scope) && p.policyLevel().equals(level))
                    .findFirst();
        }

        @Override
        public List<PublishPolicy> listEnabledGlobals() {
            return rows.stream()
                    .filter(p -> p.enabled() && "GLOBAL".equals(p.policyLevel()))
                    .limit(1)
                    .toList();
        }
    }
}
