package cn.studykid.multiagent.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
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
            service.requireAuthorized(admin, AuthorizationService.Actions.RUN_CREATE, "run-1");
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