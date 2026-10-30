package cn.studykid.multiagent.api.platform;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import cn.studykid.multiagent.api.security.PlatformSecurity;
import cn.studykid.multiagent.ports.audit.PlatformAuditPort;
import cn.studykid.multiagent.ports.audit.PlatformAuditPort.PlatformAuditEvent;
import cn.studykid.multiagent.ports.persistence.PlatformTenantPort;
import cn.studykid.multiagent.ports.persistence.PlatformTenantPort.CapabilityGrantRow;
import cn.studykid.multiagent.ports.persistence.PlatformTenantPort.PlatformTenantRow;
import cn.studykid.multiagent.ports.persistence.PlatformTenantPort.QuotaRow;
import cn.studykid.multiagent.ports.security.Namespaces;
import cn.studykid.multiagent.ports.security.PlatformPrincipal;
import cn.studykid.multiagent.ports.tenant.TenantLifecycle;

/**
 * 平台租户管理服务（重规划 §6 / §8 / §9）。
 *
 * <p><b>授权在执行入口强制</b>：每个写操作先经 {@link PlatformSecurity#require}
 * （默认拒绝、矩阵驱动），再调仓储，最后写 {@code platform_audit_event}。
 * 控制台有权限不代表绕过本服务的授权闸门。
 *
 * <p>⚠️ <b>创建租户不自动建租户管理员</b>；生命周期迁移须通过状态机校验，
 * 非法迁移返回 409（重规划 §6.1 / §6.3）。
 */
@Component
public class PlatformTenantService {

    /**
     * 审计动作：退役收尾（{@code DECOMMISSIONING → DECOMMISSIONED}）。
     *
     * <p>⚠️ 与授权命名空间<b>刻意不同</b>：授权用 {@code platform.tenant.decommission}
     * （冻结矩阵里的既有条目），审计动作则要能区分「启动退役」与「退役收尾」——
     * 否则事后追溯分不清租户是「被退役」还是「退役已完结」。
     */
    public static final String ACTION_DECOMMISSION_FINALIZE = "platform.tenant.decommission.finalize";

    /** 审计动作：取消未完成初始化的租户（{@code PENDING_SETUP → CANCELLED}）。 */
    public static final String ACTION_DECOMMISSION_CANCEL = "platform.tenant.decommission.cancel";

    private final PlatformTenantPort tenants;
    private final PlatformSecurity security;
    private final PlatformAuditPort audit;

    public PlatformTenantService(PlatformTenantPort tenants,
                                 PlatformSecurity security,
                                 PlatformAuditPort audit) {
        this.tenants = tenants;
        this.security = security;
        this.audit = audit;
    }

    // ── 读 ─────────────────────────────────────────────────

    public List<PlatformTenantRow> listTenants(@NonNull PlatformPrincipal principal,
                                               @Nullable String statusFilter, int limit) {
        security.require(principal, Namespaces.Platform.TENANT_READ, null, null);
        return tenants.listTenants(statusFilter, limit);
    }

    public PlatformTenantRow getTenant(@NonNull PlatformPrincipal principal, @NonNull String id) {
        security.require(principal, Namespaces.Platform.TENANT_READ, "tenant", id);
        PlatformTenantRow row = tenants.findTenant(id);
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "租户不存在");
        }
        return row;
    }

    public QuotaRow getQuota(@NonNull PlatformPrincipal principal, @NonNull String id) {
        security.require(principal, Namespaces.Platform.TENANT_QUOTA_READ, "tenant", id);
        requireTenant(id);
        QuotaRow quota = tenants.findQuota(id);
        if (quota == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "租户配额不存在");
        }
        return quota;
    }

    public List<CapabilityGrantRow> listCapabilities(@NonNull PlatformPrincipal principal,
                                                     @NonNull String id) {
        security.require(principal, Namespaces.Platform.TENANT_CAPABILITY_READ, "tenant", id);
        requireTenant(id);
        return tenants.listCapabilities(id);
    }

    // ── 写 ─────────────────────────────────────────────────

    public PlatformTenantRow createTenant(@NonNull PlatformPrincipal principal,
                                          @NonNull String slug, @NonNull String name,
                                          @Nullable String plan) {
        security.require(principal, Namespaces.Platform.TENANT_CREATE, "tenant", null);
        PlatformTenantRow row = tenants.provisionTenant(slug, name, plan);
        record(principal, Namespaces.Platform.TENANT_CREATE, "tenant", row.tenantId(), "ALLOW", null);
        return row;
    }

    public PlatformTenantRow suspendTenant(@NonNull PlatformPrincipal principal, @NonNull String id) {
        security.require(principal, Namespaces.Platform.TENANT_SUSPEND, "tenant", id);
        PlatformTenantRow row = transition(principal, id, TenantLifecycle.SUSPENDED,
                Namespaces.Platform.TENANT_SUSPEND);
        record(principal, Namespaces.Platform.TENANT_SUSPEND, "tenant", id, "ALLOW", null);
        return row;
    }

    public PlatformTenantRow resumeTenant(@NonNull PlatformPrincipal principal, @NonNull String id) {
        security.require(principal, Namespaces.Platform.TENANT_RESUME, "tenant", id);
        PlatformTenantRow row = transition(principal, id, TenantLifecycle.ACTIVE,
                Namespaces.Platform.TENANT_RESUME);
        record(principal, Namespaces.Platform.TENANT_RESUME, "tenant", id, "ALLOW", null);
        return row;
    }

    public PlatformTenantRow decommissionTenant(@NonNull PlatformPrincipal principal,
                                               @NonNull String id) {
        security.require(principal, Namespaces.Platform.TENANT_DECOMMISSION, "tenant", id);
        PlatformTenantRow row = transition(principal, id, TenantLifecycle.DECOMMISSIONING,
                Namespaces.Platform.TENANT_DECOMMISSION);
        record(principal, Namespaces.Platform.TENANT_DECOMMISSION, "tenant", id, "ALLOW", null);
        return row;
    }

    /**
     * 退役收尾：{@code DECOMMISSIONING → DECOMMISSIONED}（终态）。
     *
     * <p>⚠️ <b>为什么必须有这一步</b>：状态机（{@link TenantLifecycle}）与 DB 触发器
     * （{@code enforce_tenant_lifecycle_transition}）都允许 {@code DECOMMISSIONING → DECOMMISSIONED}，
     * 但此前 API 只暴露了 suspend/resume/decommission —— 没有端点能执行这条迁移。
     * 结果：租户一旦进入 {@code DECOMMISSIONING} 就**停在半路**，控制台既无法把它推到终态，
     * 也无法回收（终态不可删是设计，终态不可达是缺陷）。
     *
     * <p>⚠️ <b>授权复用</b> {@link Namespaces.Platform#TENANT_DECOMMISSION} 而不新增命名空间：
     * 角色权限矩阵是<b>代码冻结</b>的（运行期不可改），「退役收尾」与「退役启动」属同一类
     * 终止操作，复用既有命名空间可让超管与租户生命周期管理员立即获得该能力，且不触碰冻结矩阵。
     */
    public PlatformTenantRow finalizeDecommissionTenant(@NonNull PlatformPrincipal principal,
                                                        @NonNull String id) {
        security.require(principal, Namespaces.Platform.TENANT_DECOMMISSION, "tenant", id);
        PlatformTenantRow row = transition(principal, id, TenantLifecycle.DECOMMISSIONED,
                Namespaces.Platform.TENANT_DECOMMISSION);
        record(principal, ACTION_DECOMMISSION_FINALIZE, "tenant", id, "ALLOW", null);
        return row;
    }

    /**
     * 取消未完成初始化的租户：{@code PENDING_SETUP → CANCELLED}（终态）。
     *
     * <p>与 {@link #finalizeDecommissionTenant} 同理——状态机与 DB 触发器都允许这条迁移，
     * 但此前没有端点能执行它，「从未启用过的租户」在控制台里既不能取消也不能删除。
     *
     * <p>⚠️ 仅允许从 {@code PENDING_SETUP} 出发：已启用过的租户必须走退役流程
     * （{@code ACTIVE → DECOMMISSIONING → DECOMMISSIONED}），否则会绕过计费/审计的处置语义。
     */
    public PlatformTenantRow cancelTenant(@NonNull PlatformPrincipal principal,
                                          @NonNull String id) {
        security.require(principal, Namespaces.Platform.TENANT_DECOMMISSION, "tenant", id);
        PlatformTenantRow row = transition(principal, id, TenantLifecycle.CANCELLED,
                Namespaces.Platform.TENANT_DECOMMISSION);
        record(principal, ACTION_DECOMMISSION_CANCEL, "tenant", id, "ALLOW", null);
        return row;
    }

    public QuotaRow updateQuota(@NonNull PlatformPrincipal principal, @NonNull String id,
                               int agentLimit, int runConcurrencyLimit,
                               @Nullable BigDecimal budgetLimit) {
        security.require(principal, Namespaces.Platform.TENANT_QUOTA_UPDATE, "tenant", id);
        requireTenant(id);
        QuotaRow row = tenants.updateQuota(id,
                new QuotaRow(agentLimit, runConcurrencyLimit, budgetLimit));
        record(principal, Namespaces.Platform.TENANT_QUOTA_UPDATE, "tenant", id, "ALLOW", null);
        return row;
    }

    public CapabilityGrantRow grantCapability(@NonNull PlatformPrincipal principal,
                                             @NonNull String id, @NonNull String resourceCode,
                                             @NonNull String scope) {
        security.require(principal, Namespaces.Platform.TENANT_CAPABILITY_GRANT, "tenant", id);
        requireTenant(id);
        CapabilityGrantRow row = tenants.grantCapability(id, resourceCode, scope);
        record(principal, Namespaces.Platform.TENANT_CAPABILITY_GRANT, "tenant", id, "ALLOW", null);
        return row;
    }

    public void revokeCapability(@NonNull PlatformPrincipal principal, @NonNull String id,
                                @NonNull String resourceCode) {
        security.require(principal, Namespaces.Platform.TENANT_CAPABILITY_REVOKE, "tenant", id);
        requireTenant(id);
        boolean ok = tenants.revokeCapability(id, resourceCode);
        if (!ok) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "能力授权不存在或已撤销");
        }
        record(principal, Namespaces.Platform.TENANT_CAPABILITY_REVOKE, "tenant", id, "ALLOW", null);
    }

    // ── 内部 ───────────────────────────────────────────────

    private PlatformTenantRow transition(PlatformPrincipal principal, String id, String target,
                                        String action) {
        PlatformTenantRow current = tenants.findTenant(id);
        if (current == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "租户不存在");
        }
        if (!TenantLifecycle.canTransition(current.lifecycleState(), target)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "非法状态迁移：" + current.lifecycleState() + " → " + target);
        }
        // ⚠️ CAS（评审 P0-4）：expectedCurrentState 把「校验依据」与「写入前提」绑定。
        //    读/写间隙他人先行迁移时返回 null —— 这是**并发冲突（409）**而非不存在（404）。
        PlatformTenantRow row = tenants.transitionLifecycle(
                id, current.lifecycleState(), target);
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "租户生命周期已被并发变更，请等待 1 秒后刷新重试（活锁防护）");
        }
        return row;
    }

    private void requireTenant(@NonNull String id) {
        if (tenants.findTenant(id) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "租户不存在");
        }
    }

    private void record(PlatformPrincipal principal, String action, String targetType,
                       String targetId, String decision, String reason) {
        audit.record(new PlatformAuditEvent(
                UUID.randomUUID(), principal.userId(), "PLATFORM_USER",
                action, targetType, targetId, decision, reason, null, null, Instant.now(), null));
    }
}
