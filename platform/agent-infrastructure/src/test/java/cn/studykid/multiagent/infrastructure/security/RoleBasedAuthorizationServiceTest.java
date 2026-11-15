package cn.studykid.multiagent.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.security.AccessDeniedException;
import cn.studykid.multiagent.ports.security.AuthorizationService;

/**
 * 授权服务测试（P1-04 / FR-008 / FR-010）。
 *
 * <p>重点验证三条不可妥协的规则：
 * <ol>
 *   <li>未登记动作<b>默认拒绝</b>（不因忘记配置而放行）；</li>
 *   <li>授权撤销后，旧上下文在<b>执行时</b>被拒（P0-D03 §4 红线 9）；</li>
 *   <li>权限矩阵完整——每个受控动作至少有一个角色可执行。</li>
 * </ol>
 */
class RoleBasedAuthorizationServiceTest {

    private RoleBasedAuthorizationService service;

    @BeforeEach
    void setUp() {
        service = new RoleBasedAuthorizationService();
    }

    private static TenantContext ctx(String tenant, String principal, long version, String... roles) {
        return new TenantContext(tenant, principal, Set.of(roles), version);
    }

    @Nested
    @DisplayName("角色授权")
    class RolePermissions {

        @Test
        @DisplayName("租户管理员可执行全部动作")
        void adminCanDoEverything() {
            TenantContext admin = ctx("t-a", "admin", 0, AuthorizationService.Roles.TENANT_ADMIN);
            for (String action : AuthorizationService.Actions.all()) {
                assertThat(service.isAuthorized(admin, action, null))
                        .as("管理员应被允许执行 %s", action)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("只读观察者仅可读，不可创建或调用工具")
        void viewerIsReadOnly() {
            TenantContext viewer = ctx("t-a", "v", 0, AuthorizationService.Roles.VIEWER);
            assertThat(service.isAuthorized(viewer, AuthorizationService.Actions.RUN_READ, null)).isTrue();
            assertThat(service.isAuthorized(viewer, AuthorizationService.Actions.RESULT_READ, null)).isTrue();
            assertThat(service.isAuthorized(viewer, AuthorizationService.Actions.RUN_CREATE, null))
                    .as("观察者不得创建 Run")
                    .isFalse();
            assertThat(service.isAuthorized(viewer, AuthorizationService.Actions.TOOL_INVOKE, null))
                    .as("观察者不得调用工具")
                    .isFalse();
        }

        @Test
        @DisplayName("审批人可消费审批但不可创建 Run")
        void approverScope() {
            TenantContext approver = ctx("t-a", "p", 0, AuthorizationService.Roles.APPROVER);
            assertThat(service.isAuthorized(approver, AuthorizationService.Actions.APPROVAL_CONSUME, null)).isTrue();
            assertThat(service.isAuthorized(approver, AuthorizationService.Actions.RUN_CREATE, null)).isFalse();
        }

        @Test
        @DisplayName("✅ 学生可发起辅导并读本人 Run，但不可触治理动作（F1 · 2026-11-14 正规实现）")
        void studentCoreFlow() {
            TenantContext student = ctx("t-a", "s", 0, AuthorizationService.Roles.STUDENT);
            assertThat(service.isAuthorized(student, AuthorizationService.Actions.RUN_CREATE, null))
                    .as("学生自助发起辅导是核心链路").isTrue();
            assertThat(service.isAuthorized(student, AuthorizationService.Actions.RUN_READ, null)).isTrue();
            assertThat(service.isAuthorized(student, AuthorizationService.Actions.EVENT_SUBSCRIBE, null)).isTrue();
            assertThat(service.isAuthorized(student, AuthorizationService.Actions.RESULT_READ, null)).isTrue();
            // ⛔ 治理/管理动作默认拒绝
            assertThat(service.isAuthorized(student, AuthorizationService.Actions.AGENT_MANAGE, null)).isFalse();
            assertThat(service.isAuthorized(student, AuthorizationService.Actions.AUDIT_READ, null)).isFalse();
            assertThat(service.isAuthorized(student, AuthorizationService.Actions.CONFIG_PUBLISH, null)).isFalse();
            assertThat(service.isAuthorized(student, AuthorizationService.Actions.TOOL_INVOKE, null)).isFalse();
            // ⛔ 不给 PROFILE_READ：主体与匿名编号无绑定，放开即租户内画像任意读
            assertThat(service.isAuthorized(student, AuthorizationService.Actions.PROFILE_READ, null)).isFalse();
            // ✅ 只读列举：发起辅导页的下拉框要能看到「可发起什么」
            assertThat(service.isAuthorized(student, AuthorizationService.Actions.AGENT_LIST, null))
                    .as("学生必须能列举可发起辅导的已发布 Agent（否则页面误报「没有可用的 Agent」）")
                    .isTrue();
        }

        @Test
        @DisplayName("✅ 家长/老师可只读列举 Agent（AGENT_LIST），但不得触治理面")
        void audienceCanListAgentsButNotManage() {
            for (String role : new String[]{
                    AuthorizationService.Roles.PARENT, AuthorizationService.Roles.TEACHER}) {
                TenantContext audience = ctx("t-a", role.toLowerCase(), 0, role);
                assertThat(service.isAuthorized(audience, AuthorizationService.Actions.AGENT_LIST, null))
                        .as("%s 可列举可发起辅导的 Agent", role).isTrue();
                assertThat(service.isAuthorized(audience, AuthorizationService.Actions.AGENT_MANAGE, null))
                        .as("%s 不得拿到治理面（含 latestDefinition 提示词全文）", role).isFalse();
            }
        }

        @Test
        @DisplayName("✅ 家长/老师为受众只读 + 画像可读，不可创建 Run 或触治理动作")
        void parentTeacherAudienceScope() {
            for (String role : new String[]{
                    AuthorizationService.Roles.PARENT, AuthorizationService.Roles.TEACHER}) {
                TenantContext audience = ctx("t-a", role.toLowerCase(), 0, role);
                assertThat(service.isAuthorized(audience, AuthorizationService.Actions.RUN_READ, null))
                        .as("%s 可读 Run", role).isTrue();
                assertThat(service.isAuthorized(audience, AuthorizationService.Actions.RESULT_READ, null)).isTrue();
                assertThat(service.isAuthorized(audience, AuthorizationService.Actions.EVENT_SUBSCRIBE, null)).isTrue();
                assertThat(service.isAuthorized(audience, AuthorizationService.Actions.PROFILE_READ, null))
                        .as("%s 可读画像（PROFILE_READ 动作契约：家长/教师可读画像）", role).isTrue();
                assertThat(service.isAuthorized(audience, AuthorizationService.Actions.RUN_CREATE, null))
                        .as("%s 不得创建 Run（创建走运营/学生自助）", role).isFalse();
                assertThat(service.isAuthorized(audience, AuthorizationService.Actions.APPROVAL_CONSUME, null))
                        .as("治理审批走 APPROVER 角色，受众角色不带").isFalse();
            }
        }

        @Test
        @DisplayName("isAudienceOnly：纯受众角色为真；空角色与混合治理角色为假")
        void audienceOnlyDetection() {
            assertThat(AuthorizationService.Roles.isAudienceOnly(Set.of(
                    AuthorizationService.Roles.STUDENT))).isTrue();
            assertThat(AuthorizationService.Roles.isAudienceOnly(Set.of(
                    AuthorizationService.Roles.PARENT, AuthorizationService.Roles.TEACHER))).isTrue();
            assertThat(AuthorizationService.Roles.isAudienceOnly(Set.of()))
                    .as("空角色（含旧会话）必须 fail-closed 判为非受众").isFalse();
            assertThat(AuthorizationService.Roles.isAudienceOnly(Set.of(
                    AuthorizationService.Roles.STUDENT, AuthorizationService.Roles.TENANT_ADMIN)))
                    .as("持有任何治理角色即不受受众收窄").isFalse();
            assertThat(AuthorizationService.Roles.isAudienceOnly(Set.of("UNKNOWN_ROLE"))).isFalse();
        }

        @Test
        @DisplayName("Worker 可执行但不可消费人工审批（职责分离）")
        void workerCannotApprove() {
            TenantContext worker = ctx("t-a", "w", 0, AuthorizationService.Roles.WORKER);
            assertThat(service.isAuthorized(worker, AuthorizationService.Actions.TOOL_INVOKE, null)).isTrue();
            assertThat(service.isAuthorized(worker, AuthorizationService.Actions.APPROVAL_CONSUME, null))
                    .as("Worker 不得审批自己的副作用（职责分离）")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("⚠️ 默认拒绝")
    class DefaultDeny {

        @Test
        @DisplayName("未登记的动作一律拒绝")
        void unknownActionDenied() {
            TenantContext admin = ctx("t-a", "admin", 0, AuthorizationService.Roles.TENANT_ADMIN);
            assertThat(service.isAuthorized(admin, "unregistered:action", null))
                    .as("未登记动作不得因漏配置而放行")
                    .isFalse();
        }

        @Test
        @DisplayName("无任何角色的主体拒绝一切")
        void noRolesDenied() {
            TenantContext none = ctx("t-a", "x", 0);
            for (String action : AuthorizationService.Actions.all()) {
                assertThat(service.isAuthorized(none, action, null)).isFalse();
            }
        }

        @Test
        @DisplayName("未登记角色无任何权限")
        void unknownRoleDenied() {
            TenantContext bogus = ctx("t-a", "x", 0, "SOME_MADE_UP_ROLE");
            assertThat(service.isAuthorized(bogus, AuthorizationService.Actions.RUN_READ, null)).isFalse();
        }
    }

    @Nested
    @DisplayName("⚠️ 执行时重新授权（红线 9）")
    class Reauthorization {

        @Test
        @DisplayName("授权撤销后，持有旧版本的上下文被拒绝")
        void revokedContextDeniedAtExecutionTime() {
            TenantContext inFlight = ctx("t-a", "author", 0, AuthorizationService.Roles.AGENT_AUTHOR);
            assertThat(service.isAuthorized(inFlight, AuthorizationService.Actions.TOOL_INVOKE, null))
                    .as("撤销前应允许")
                    .isTrue();

            // 运行过程中权限被撤销
            service.revoke(inFlight);

            assertThat(service.isAuthorized(inFlight, AuthorizationService.Actions.TOOL_INVOKE, null))
                    .as("撤销后同一上下文必须被拒绝——这是「执行时重新授权」的核心")
                    .isFalse();
        }

        @Test
        @DisplayName("撤销后重建的上下文（新版��）可用")
        void freshContextWorksAfterRevocation() {
            TenantContext old = ctx("t-a", "author", 0, AuthorizationService.Roles.AGENT_AUTHOR);
            service.revoke(old);

            long newVersion = service.currentAuthzVersion("t-a", "author");
            TenantContext fresh = ctx("t-a", "author", newVersion, AuthorizationService.Roles.AGENT_AUTHOR);
            assertThat(service.isAuthorized(fresh, AuthorizationService.Actions.TOOL_INVOKE, null))
                    .as("持最新版本的上下文应正常工作")
                    .isTrue();
        }

        @Test
        @DisplayName("撤销仅影响目标主体，不影响同租户其他人")
        void revocationIsScoped() {
            TenantContext a = ctx("t-a", "author-a", 0, AuthorizationService.Roles.AGENT_AUTHOR);
            TenantContext b = ctx("t-a", "author-b", 0, AuthorizationService.Roles.AGENT_AUTHOR);
            service.revoke(a);

            assertThat(service.isAuthorized(a, AuthorizationService.Actions.TOOL_INVOKE, null)).isFalse();
            assertThat(service.isAuthorized(b, AuthorizationService.Actions.TOOL_INVOKE, null))
                    .as("撤销一人不应影响他人")
                    .isTrue();
        }

        @Test
        @DisplayName("撤销仅影响目标租户，不影响他租户同 ID 主体")
        void revocationIsTenantScoped() {
            TenantContext inA = ctx("t-a", "same-id", 0, AuthorizationService.Roles.AGENT_AUTHOR);
            TenantContext inB = ctx("t-b", "same-id", 0, AuthorizationService.Roles.AGENT_AUTHOR);
            service.revoke(inA);

            assertThat(service.isAuthorized(inA, AuthorizationService.Actions.TOOL_INVOKE, null)).isFalse();
            assertThat(service.isAuthorized(inB, AuthorizationService.Actions.TOOL_INVOKE, null))
                    .as("同 principalId 在不同租户应独立计算授权")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("异常语义：拒绝与故障必须可区分")
    class ExceptionSemantics {

        @Test
        @DisplayName("requireAuthorized 在被拒时抛 AccessDeniedException")
        void throwsOnDenied() {
            TenantContext viewer = ctx("t-a", "v", 0, AuthorizationService.Roles.VIEWER);
            assertThatThrownBy(() ->
                    service.requireAuthorized(viewer, AuthorizationService.Actions.TOOL_INVOKE, "op-1"))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("tool:invoke");
        }

        @Test
        @DisplayName("requireAuthorized 在通过时静默返回")
        void silentOnAllowed() {
            TenantContext admin = ctx("t-a", "admin", 0, AuthorizationService.Roles.TENANT_ADMIN);
            assertThatNoException()
                    .as("管理员应被授权，requireAuthorized 必须静默返回")
                    .isThrownBy(() ->
                            service.requireAuthorized(admin, AuthorizationService.Actions.RUN_CREATE, "run-1"));
        }
    }

    @Nested
    @DisplayName("权限矩阵完整性自检")
    class MatrixIntegrity {

        @Test
        @DisplayName("✅ 所有受控动作至少被一个角色允许")
        void matrixComplete() {
            RoleBasedAuthorizationService.verifyPermissionMatrixComplete();
        }

        @Test
        @DisplayName("每个角色的权限集合均来自受控动作集合（无拼写错误）")
        void noTyposInPermissions() {
            for (String role : new String[]{
                    AuthorizationService.Roles.TENANT_ADMIN,
                    AuthorizationService.Roles.AGENT_AUTHOR,
                    AuthorizationService.Roles.APPROVER,
                    AuthorizationService.Roles.VIEWER,
                    AuthorizationService.Roles.WORKER}) {
                assertThat(RoleBasedAuthorizationService.permissionsOf(role))
                        .as("角色 %s 含未登记动作（疑似拼写错误）", role)
                        .allSatisfy(a -> assertThat(AuthorizationService.Actions.all())
                                .as("角色 %s 的动作 %s 不在受控动作集合中", role, a)
                                .contains(a));
            }
        }
    }
}