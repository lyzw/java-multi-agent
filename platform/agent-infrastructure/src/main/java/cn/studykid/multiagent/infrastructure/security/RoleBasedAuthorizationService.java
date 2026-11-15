package cn.studykid.multiagent.infrastructure.security;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.security.AuthorizationService;

/**
 * 基于角色的授权实现（P1-04 / FR-008 / FR-010）。
 *
 * <p><b>设计要点</b>：
 * <ol>
 *   <li><b>角色到动作的映射是数据而非代码分支</b>——新增动作只需扩充映射表，
 *       不会遗漏某条 if 判断。</li>
 *   <li><b>authzVersion 校验</b>：授权被撤销时版本号递增，持有旧版本的在途
 *       上下文会被拒绝（P0-D03 §4 红线 9：旧 Run 恢复时须重新验证当前授权）。</li>
 *   <li><b>默认拒绝</b>：未在映射表中登记的动作<b>一律拒绝</b>，
 *       不会因「忘记配置」而放行。</li>
 * </ol>
 *
 * <p><b>本类是 P1 的内存实现</b>，用于跑通闭环。生产实现须替换为
 * IAM/OIDC + 数据库授权表（D-001），接口保持不变。
 */
public class RoleBasedAuthorizationService implements AuthorizationService {

    /** 角色 → 允许的动作集合。未登记的动作对所有角色默认拒绝。 */
    private static final Map<String, Set<String>> ROLE_PERMISSIONS = Map.of(
            Roles.TENANT_ADMIN, Set.of(
                    Actions.RUN_CREATE, Actions.RUN_READ, Actions.RUN_CANCEL,
                    Actions.EVENT_SUBSCRIBE, Actions.RESULT_READ,
                    Actions.TOOL_INVOKE, Actions.APPROVAL_CONSUME,
                    Actions.UNKNOWN_DISPOSE, Actions.AUDIT_READ, Actions.AGENT_MANAGE,
                    // ⚠️ 画像读取（P4-D09 / G6）：租户管理员是画像数据的管理方
                    Actions.PROFILE_READ,
                    // ⚠️ **配置审批（P4-D16 / C7）**：
                    //    TENANT_ADMIN 同时具备申请与审批两个动作——这是**刻意的**：
                    //    「管理员什么都能做」是既有不变式，而双人复核靠的是
                    //    **身份比对**（approver_id <> requester_id，应用层 + DB CHECK 双保险），
                    //    不是靠权限拆分。
                    //    若把审批权从管理员手里拿走，反而会导致「无人可批」：
                    //    试点期往往只有一个管理员。
                    Actions.CONFIG_REQUEST, Actions.CONFIG_APPROVE,
                    // ⚠️ **权威配置对象的发布 / 回滚（P6R4）**：
                    //    租户管理员可发布**本租户**的 Agent 定义（发布 / 回滚 / 生命周期迁移）。
                    //    ⚠️ 平台级对象（Tool / Skill / MCP Server）**不在此列** ——
                    //    平台身份表是租户内的，没有跨租户主体；
                    //    若把平台级发布挂到租户角色上，任一租户管理员自授角色即可改全平台配置。
                    Actions.CONFIG_PUBLISH, Actions.CONFIG_ROLLBACK,
                    // ⚠️ **委派到子 Agent（多级 Agent v1.1 · FR-025）**：
                    //    授予集合与 TOOL_INVOKE 对齐（管理员 / 作者 / Worker）——
                    //    能驱动 Agent 执行的人，才允许把工作交给子 Agent。
                    //    ⚠️ 刻意不给 VIEWER / APPROVER：只读与审批角色不产生委派。
                    Actions.AGENT_DELEGATE,
                    // ⚠️ **只读列举 Agent（F1 · E2E 2026-11-14）**：
                    //    管理员的「什么都能做」是既有不变式，新动作必须同步登记，
                    //    否则自检会判定「动作存在但无人可执行」。
                    Actions.AGENT_LIST),
            Roles.AGENT_AUTHOR, Set.of(
                    Actions.RUN_CREATE, Actions.RUN_READ, Actions.RUN_CANCEL,
                    Actions.EVENT_SUBSCRIBE, Actions.RESULT_READ,
                    Actions.TOOL_INVOKE, Actions.AGENT_MANAGE,
                    // ⚠️ 委派权限（同 TOOL_INVOKE 口径）：作者可以驱动自己的 Agent 编排子 Agent
                    Actions.AGENT_DELEGATE,
                    // ⚠️ **只能申请、不能审批**（P4-D16 / C7）：
                    //    作者负责准备变更，审批权在管理员。
                    //    这使双人复核在**权限层面**就有落点，
                    //    而不只是靠同一管理员的自律。
                    Actions.CONFIG_REQUEST,
                    // ⚠️ 作者本就在管理自己的 Agent，列举是天然读面
                    Actions.AGENT_LIST),
            Roles.APPROVER, Set.of(
                    Actions.RUN_READ, Actions.APPROVAL_CONSUME,
                    Actions.UNKNOWN_DISPOSE, Actions.EVENT_SUBSCRIBE),
            Roles.VIEWER, Set.of(
                    Actions.RUN_READ, Actions.RESULT_READ, Actions.EVENT_SUBSCRIBE,
                    // ⚠️ 画像读取（P4-D09 / G6）：只读角色读只读数据，语义一致
                    Actions.PROFILE_READ
                    // ⚠️ **刻意不给 AGENT_LIST**：VIEWER 是**运维侧**只读观察者，
                    //    而 Agent 列表端点在治理面（G2-2 契约：无 AGENT_MANAGE → 403）。
                    //    需要「列举可发起辅导的 Agent」的是**受众**（STUDENT/PARENT/TEACHER），
                    //    两者定位不同：受众要自助发起，观察者只看自己的 Run。
                    //    给了 VIEWER 会与既有治理契约冲突（AgentEndpointIT 锁定 403）。
                    ),
            // Worker 服务账号：需要执行与处置能力，但不需人工审批
            // ⚠️ **刻意不含 PROFILE_READ**（P4-D09 / G6）：
            //    Worker 是执行侧服务账号，读学生画像不属于其职责；
            //    且画像是学生维度数据，给服务账号开这个口子会扩大数据可见面。
            Roles.WORKER, Set.of(
                    Actions.RUN_READ, Actions.RUN_CANCEL, Actions.TOOL_INVOKE,
                    Actions.EVENT_SUBSCRIBE, Actions.RESULT_READ,
                    // ⚠️ **委派权限**：委派动作由执行侧发起，
                    //    没有它则多级 Agent 在 Worker 链路上无法落地。
                    //    ⚠️ 注意这只是「执行侧」的动作许可，
                    //    真正的<b>逐跳授权</b>还要过 DelegationGuard 的
                    //    目标声明 / 深度 / 次数 / 预算 / authzVersion 判定。
                    Actions.AGENT_DELEGATE),
            // ⚠️ **受众角色（STUDENT/PARENT/TEACHER）**（F1 · E2E 2026-11-14 正规实现）：
            //    面向「错题归因辅导」核心受众的最小能力集——**只读 + 本人 Run 自助**，
            //    不含任何治理/管理动作（fail-closed）：
            //    · STUDENT：可发起辅导（RUN_CREATE）、查看**本人** Run 的状态/事件/结果。
            //      ⛔ 不给 PROFILE_READ：主体与「学生匿名编号」之间**没有绑定关系**
            //      （principal 表刻意不采集，PIPL/DEC-6），放开即等于租户内画像任意读。
            //    · PARENT/TEACHER：与 STUDENT 相同的 Run 读能力 + PROFILE_READ
            //      （PROFILE_READ 的动作契约本就写明「家长/教师可读画像」；
            //      与 VIEWER 的租户级画像只读同口径——画像按匿名编号组织，无真实身份）。
            //    · ⛔ 不给 RUN_CANCEL / TOOL_INVOKE / AUDIT_READ / AGENT_MANAGE /
            //      CONFIG_*：受众不是治理主体；取消与处置由运营角色执行。
            //    · AGENT_LIST（只读）：「发起辅导」页的 Agent 下拉框所需。
            //      ⛔ 只给**列举**这一个只读面，且控制器会裁剪治理元数据
            //      （不下发 latestDefinition 提示词全文、只列已发布版本）。
            //      不给则会出现「能发起却看不见可发起什么」的**能力—依赖不匹配**：
            //      接口 403 → 前端空列表 → 页面误报「没有可用的 Agent」。
            //    · ⚠️ Run 数据隔离不靠权限矩阵（动作级）实现，而靠
            //      {@code AuthorizationService.Roles#isAudienceOnly} 的资源级收窄
            //      （run.requester_id = principalId）——动作级授权回答「能不能读 Run」，
            //      资源级收窄回答「能读**哪些** Run」，二者缺一不可。
            Roles.STUDENT, Set.of(
                    Actions.RUN_CREATE, Actions.RUN_READ,
                    Actions.EVENT_SUBSCRIBE, Actions.RESULT_READ, Actions.AGENT_LIST),
            Roles.PARENT, Set.of(
                    Actions.RUN_READ, Actions.EVENT_SUBSCRIBE, Actions.RESULT_READ,
                    // ⚠️ 画像读取（P4-D09 / G6）：家长是画像数据的合法读方（受众契约）
                    Actions.PROFILE_READ,
                    Actions.AGENT_LIST),
            Roles.TEACHER, Set.of(
                    Actions.RUN_READ, Actions.EVENT_SUBSCRIBE, Actions.RESULT_READ,
                    Actions.PROFILE_READ, Actions.AGENT_LIST),
            // ⚠️ **平台级管理员（PLATFORM_ADMIN）**：
            //    平台/运维级治理角色，可覆盖全部动作（含平台级端点）。
            //    默认不授予任何主体 —— 仅在 out-of-band 授权通道（bootstrap）授予后才生效，
            //    应用层平台级端点仍以 {@code hasRole("PLATFORM_ADMIN")} 直判（见 AdminTenantController）。
            Roles.PLATFORM_ADMIN, Actions.all()
    );

    /** 租户+主体 → 当前授权版本。撤销时递增。 */
    private final Map<String, Long> authzVersions = new ConcurrentHashMap<>();

    @Override
    public boolean isAuthorized(@NonNull TenantContext context,
                                @NonNull String action,
                                @Nullable String resourceId) {
        // 1) 授权版本校验：拒绝持有陈旧版本的上下文
        long current = currentAuthzVersion(context.tenantId(), context.principalId());
        if (context.authzVersion() < current) {
            // 授权已在该上下文创建后被撤销
            return false;
        }
        // 2) 角色授权判断：未登记动作默认拒绝
        return context.roles().stream()
                .map(ROLE_PERMISSIONS::get)
                .filter(java.util.Objects::nonNull)
                .anyMatch(actions -> actions.contains(action));
    }

    @Override
    public long currentAuthzVersion(@NonNull String tenantId, @NonNull String principalId) {
        return authzVersions.getOrDefault(tenantId + "/" + principalId, 0L);
    }

    @Override
    public @NonNull Set<String> rolesOf(@NonNull TenantContext context) {
        return context.roles();
    }

    /**
     * 撤销主体在租户内的全部授权。
     *
     * <p>递增版本号后，所有持有旧 {@code authzVersion} 的在途上下文在下一次
     * 授权检查时将被拒绝——这是「执行时重新授权」的技术基础。
     */
    public void revoke(@NonNull TenantContext context) {
        authzVersions.merge(context.tenantId() + "/" + context.principalId(), 1L, Long::sum);
    }

    /** @return 某角色被允许的动作集合（只读）。 */
    public static @NonNull Set<String> permissionsOf(@NonNull String role) {
        return ROLE_PERMISSIONS.getOrDefault(role, Set.of());
    }

    /**
     * 启动期自检：确认所有受控动作至少被一个角色允许。
     *
     * <p>若某个动作没有任何角色可执行，说明权限矩阵存在缺口。
     * 该检查在应用启动时执行，失败则拒绝启动——避免上线后才发现动作无人可执行。
     */
    public static void verifyPermissionMatrixComplete() {
        Set<String> granted = ROLE_PERMISSIONS.values().stream()
                .flatMap(Set::stream)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> missing = Actions.all().stream()
                .filter(a -> !granted.contains(a))
                .collect(java.util.stream.Collectors.toSet());
        if (!missing.isEmpty()) {
            throw new IllegalStateException("权限矩阵存在缺口，以下动作无任何角色可执行: " + missing);
        }
    }
}