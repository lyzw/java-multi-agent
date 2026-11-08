package cn.studykid.multiagent.api.platform;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import cn.studykid.multiagent.api.security.PlatformSecurity;
import cn.studykid.multiagent.ports.persistence.ReviewTaskPort;
import cn.studykid.multiagent.ports.security.IdentityDomains;
import cn.studykid.multiagent.ports.security.Namespaces;
import cn.studykid.multiagent.ports.security.PlatformPrincipal;

/**
 * 平台侧人工审查队列端点（P2 · G3「人审」半边）。
 *
 * <p><b>权限语义（刻意读写分离，复用既有权限码、不新增）</b>：
 * <ul>
 *   <li>读队列：{@code platform.resource.read}（与指标 / 回放端点同码）；</li>
 *   <li>流转（认领 / 关闭 / 驳回）：{@code platform.resource.manage} ——
 *       会改变队列事实（谁认领、归因结论），不能让只读审计角色能改状态。</li>
 * </ul>
 *
 * <p><b>⚠️ rootCause 白名单</b>：resolve 的归因值在控制器侧先行校验
 * （枚举 valueOf），非法值 400——不等到库侧 CHECK 才炸出 500。
 */
@RestController
@RequestMapping("/api/platform/v1/review-tasks")
public class PlatformReviewTaskController {

    private final PlatformSecurity security;
    private final ReviewTaskPort reviewTasks;

    public PlatformReviewTaskController(PlatformSecurity security,
                                        ReviewTaskPort reviewTasks) {
        this.security = security;
        this.reviewTasks = reviewTasks;
    }

    /**
     * 拉取审查队列（平台级跨租户）。
     *
     * @param status   精确状态；缺省 = 待处理视图（PENDING + IN_REVIEW）
     * @param tenantId 按租户过滤；缺省 = 全量
     * @param limit    页大小（服务端钳制到 ≤200）
     */
    @GetMapping
    public ListResponse list(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String tenantId,
            @RequestParam(defaultValue = "50") int limit) {
        security.require(principal, Namespaces.Platform.RESOURCE_READ, null, null);
        return new ListResponse(reviewTasks.list(status, tenantId, limit));
    }

    /** 认领：置 IN_REVIEW 并登记认领人（取自平台主体，可被 body.assignee 覆盖为代领）。 */
    @PostMapping("/{taskId}/assign")
    public ResponseEntity<Void> assign(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @PathVariable String taskId,
            @RequestBody(required = false) AssignRequest body) {
        security.require(principal, Namespaces.Platform.RESOURCE_MANAGE, null, null);
        String assignee = body == null || body.assignee() == null || body.assignee().isBlank()
                ? principal.userId()
                : body.assignee();
        reviewTasks.assign(taskId, assignee);
        return ResponseEntity.noContent().build();
    }

    /** 归因关闭：置 RESOLVED。rootCause 必须在 CHECK 清单内（库侧兜底校验）。 */
    @PostMapping("/{taskId}/resolve")
    public ResponseEntity<Void> resolve(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @PathVariable String taskId,
            @RequestBody ResolveRequest body) {
        security.require(principal, Namespaces.Platform.RESOURCE_MANAGE, null, null);
        String rootCause = body.rootCause() == null || body.rootCause().isBlank()
                ? cn.studykid.multiagent.ports.persistence.ReviewTaskPort.RootCause.UNKNOWN.name()
                : body.rootCause().trim().toUpperCase();
        try {
            cn.studykid.multiagent.ports.persistence.ReviewTaskPort.RootCause.valueOf(rootCause);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "rootCause 必须是 MODEL/PROMPT/TOOL/DATA/CONFIG/UNKNOWN 之一");
        }
        reviewTasks.resolve(taskId, rootCause, body.note());
        return ResponseEntity.noContent().build();
    }

    /** 驳回：置 DISMISSED（误报 / 无需跟进）。 */
    @PostMapping("/{taskId}/dismiss")
    public ResponseEntity<Void> dismiss(
            @RequestAttribute(IdentityDomains.PLATFORM_PRINCIPAL_REQUEST_ATTRIBUTE)
            PlatformPrincipal principal,
            @PathVariable String taskId,
            @RequestBody(required = false) DismissRequest body) {
        security.require(principal, Namespaces.Platform.RESOURCE_MANAGE, null, null);
        reviewTasks.dismiss(taskId, body == null ? null : body.note());
        return ResponseEntity.noContent().build();
    }

    // ── 契约 ────────────────────────────────────────────────

    public record ListResponse(List<ReviewTaskPort.ReviewTaskView> tasks) {
    }

    /** 认领人缺省取平台主体名；body.assignee 仅用于代领场景。 */
    public record AssignRequest(String assignee) {
    }

    public record ResolveRequest(String rootCause, String note) {
    }

    public record DismissRequest(String note) {
    }
}
