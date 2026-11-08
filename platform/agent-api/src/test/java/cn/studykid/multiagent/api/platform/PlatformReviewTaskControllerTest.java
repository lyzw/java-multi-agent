package cn.studykid.multiagent.api.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import cn.studykid.multiagent.api.security.PlatformSecurity;
import cn.studykid.multiagent.ports.persistence.ReviewTaskPort;
import cn.studykid.multiagent.ports.persistence.ReviewTaskPort.ReviewTaskSpec;
import cn.studykid.multiagent.ports.persistence.ReviewTaskPort.ReviewTaskView;
import cn.studykid.multiagent.ports.security.AccessDeniedException;
import cn.studykid.multiagent.ports.security.AuthorizationDecision;
import cn.studykid.multiagent.ports.security.PlatformAuthorizationService;
import cn.studykid.multiagent.ports.security.PlatformPrincipal;

/**
 * 审查队列端点单测（P2，直构造不启 Spring）。
 *
 * <p><b>用真实 {@link PlatformSecurity} + 替身授权服务</b>，而不是覆写 security：
 * 覆写会把「权限码是否用对」「拒绝时抛什么」一起绕过，
 * 测出来的绿只证明控制器调了某个方法，不证明权限语义正确。
 * 这里断言的是<b>真实闸门行为</b>：授权服务按权限码放行/拒绝，
 * 拒绝时控制器必须在触达队列之前中止。
 */
class PlatformReviewTaskControllerTest {

    private static final PlatformPrincipal READER = new PlatformPrincipal(
            "user-audit", Set.of("PLATFORM_AUDITOR"), 1, false);
    private static final PlatformPrincipal MANAGER = new PlatformPrincipal(
            "user-admin", Set.of("PLATFORM_ADMIN"), 1, false);

    /** 替身授权服务：按白名单权限码放行，其余拒绝；记录被请求的权限码。 */
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

    /** 替身队列 port：记录调用。 */
    private static final class FakeReviewTasks implements ReviewTaskPort {
        final List<String> calls = new ArrayList<>();
        final List<ReviewTaskView> rows = new ArrayList<>();

        @Override
        public String create(ReviewTaskSpec spec) {
            calls.add("create");
            return "task-1";
        }

        @Override
        public void assign(String taskId, String assignee) {
            calls.add("assign:" + taskId + ":" + assignee);
        }

        @Override
        public void resolve(String taskId, String rootCause, String note) {
            calls.add("resolve:" + taskId + ":" + rootCause + ":" + note);
        }

        @Override
        public void dismiss(String taskId, String note) {
            calls.add("dismiss:" + taskId + ":" + note);
        }

        @Override
        public boolean hasOpenTask(String traceId) {
            calls.add("hasOpenTask:" + traceId);
            return false;
        }

        @Override
        public List<ReviewTaskView> list(String status, String tenantId, int limit) {
            calls.add("list:" + status + ":" + tenantId + ":" + limit);
            return rows;
        }
    }

    // ── 用例 ────────────────────────────────────────────────

    @Test
    @DisplayName("✅ P2-E1 拉队列过 read 闸门，limit/过滤参数透传")
    void listRequiresReadPermission() {
        RecordingAuthorizer auth = new RecordingAuthorizer("platform.resource.read");
        FakeReviewTasks port = new FakeReviewTasks();
        PlatformReviewTaskController c = new PlatformReviewTaskController(new PlatformSecurity(auth), port);

        c.list(MANAGER, "PENDING", "t-1", 30);

        assertThat(auth.requested).containsExactly("platform.resource.read");
        assertThat(port.calls).containsExactly("list:PENDING:t-1:30");
    }

    @Test
    @DisplayName("✅ P2-E2 无 read 权限 → 403，且不得触达队列（先闸门后业务）")
    void deniedListNeverTouchesPort() {
        RecordingAuthorizer auth = new RecordingAuthorizer(); // 无任何放行权限
        FakeReviewTasks port = new FakeReviewTasks();
        PlatformReviewTaskController c = new PlatformReviewTaskController(new PlatformSecurity(auth), port);

        assertThatThrownBy(() -> c.list(MANAGER, null, null, 50))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(port.calls).isEmpty();
    }

    @Test
    @DisplayName("✅ P2-E3 流转用 manage 权限；只有 read 权限的角色被拒")
    void mutationsRequireManagePermission() {
        RecordingAuthorizer auth = new RecordingAuthorizer("platform.resource.read");
        FakeReviewTasks port = new FakeReviewTasks();
        PlatformReviewTaskController c = new PlatformReviewTaskController(new PlatformSecurity(auth), port);

        assertThatThrownBy(() -> c.assign(MANAGER, "t-1",
                new PlatformReviewTaskController.AssignRequest(null)))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(port.calls).isEmpty();
    }

    @Test
    @DisplayName("✅ P2-E4 manage 权限下三个流转动作都生效")
    void mutationsSucceedWithManagePermission() {
        RecordingAuthorizer auth = new RecordingAuthorizer("platform.resource.manage");
        FakeReviewTasks port = new FakeReviewTasks();
        PlatformReviewTaskController c = new PlatformReviewTaskController(new PlatformSecurity(auth), port);

        c.assign(MANAGER, "t-1", new PlatformReviewTaskController.AssignRequest(null));
        c.resolve(MANAGER, "t-1", new PlatformReviewTaskController.ResolveRequest("model", "n"));
        c.dismiss(MANAGER, "t-1", new PlatformReviewTaskController.DismissRequest("误报"));

        assertThat(auth.requested).containsExactly(
                "platform.resource.manage", "platform.resource.manage", "platform.resource.manage");
        assertThat(port.calls).containsExactly(
                "assign:t-1:user-admin",
                "resolve:t-1:MODEL:n",
                "dismiss:t-1:误报");
    }

    @Test
    @DisplayName("✅ P2-E5 认领人缺省取平台主体，body.assignee 覆盖（代领）")
    void assigneeDefaultsToPrincipalThenOverridden() {
        RecordingAuthorizer auth = new RecordingAuthorizer("platform.resource.manage");
        FakeReviewTasks port = new FakeReviewTasks();
        PlatformReviewTaskController c = new PlatformReviewTaskController(new PlatformSecurity(auth), port);

        c.assign(MANAGER, "t-1", null);
        c.assign(MANAGER, "t-2", new PlatformReviewTaskController.AssignRequest("reviewer-b"));

        assertThat(port.calls).containsExactly(
                "assign:t-1:user-admin", "assign:t-2:reviewer-b");
    }

    @Test
    @DisplayName("✅ P2-E6 非法 rootCause → 400，不触达 port")
    void invalidRootCauseRejected() {
        RecordingAuthorizer auth = new RecordingAuthorizer("platform.resource.manage");
        FakeReviewTasks port = new FakeReviewTasks();
        PlatformReviewTaskController c = new PlatformReviewTaskController(new PlatformSecurity(auth), port);

        assertThatThrownBy(() -> c.resolve(MANAGER, "t-1",
                new PlatformReviewTaskController.ResolveRequest("NOT_A_CAUSE", null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
        assertThat(port.calls).isEmpty();
    }

    @Test
    @DisplayName("✅ P2-E7 rootCause 归一为大写（客户端小写不炸）")
    void rootCauseNormalized() {
        RecordingAuthorizer auth = new RecordingAuthorizer("platform.resource.manage");
        FakeReviewTasks port = new FakeReviewTasks();
        PlatformReviewTaskController c = new PlatformReviewTaskController(new PlatformSecurity(auth), port);

        c.resolve(MANAGER, "t-1", new PlatformReviewTaskController.ResolveRequest("prompt", "n"));
        assertThat(port.calls).containsExactly("resolve:t-1:PROMPT:n");
    }

    @Test
    @DisplayName("✅ P2-E8 只读角色能拉队列（读权限与管理权限分离）")
    void readerCanListButNotMutate() {
        RecordingAuthorizer auth = new RecordingAuthorizer("platform.resource.read");
        FakeReviewTasks port = new FakeReviewTasks();
        PlatformReviewTaskController c = new PlatformReviewTaskController(new PlatformSecurity(auth), port);

        c.list(READER, null, null, 10);
        assertThat(port.calls).containsExactly("list:null:null:10");

        assertThatThrownBy(() -> c.dismiss(READER, "t-1",
                new PlatformReviewTaskController.DismissRequest("x")))
                .isInstanceOf(AccessDeniedException.class);
    }

    @SuppressWarnings("unused")
    private static HttpStatus statusRef() {
        return HttpStatus.OK;
    }
}
