package cn.studykid.multiagent.ports.security;

import java.util.Set;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.core.tenant.TenantContext;

/**
 * 授权端口（ADR-006 / FR-008 / FR-010）。
 *
 * <p><b>为什么需要「执行时重新授权」</b>（P0-D03 §4 红线 9）：
 * Run 创建时的主体可能已被撤销权限（学生退课、教师离职、账号封禁）。
 * 若只在创建时授权一次，攻击者可用一个<b>曾经合法</b>的 Run 长期越权。
 * 因此工具执行、结果读取、事件订阅等每个敏感动作前都必须重新校验<b>当前</b>授权。
 *
 * <p>实现须结合 {@link #authzVersion()}：授权撤销时版本号递增，
 * 在途请求携带陈旧版本会被识别。
 */
public interface AuthorizationService {

    /**
     * 判断主体是否有权在目标租户执行指定动作。
     *
     * @param context    认证上下文（<b>来源必须可信</b>，不得来自客户端输入）
     * @param action     动作标识，如 {@code run:read}、{@code tool:invoke}
     * @param resourceId 目标资源标识；用于资源级授权（若为 null 则仅校验动作级）
     * @return 授权通过返回 {@code true}；<b>异常与拒绝必须可区分</b>，
     *         前者表示系统故障，后者表示明确拒绝
     */
    boolean isAuthorized(@NonNull TenantContext context,
                         @NonNull String action,
                         String resourceId);

    /**
     * 要求授权，否则抛异常。默认实现便于在业务代码中直接调用。
     *
     * @throws AccessDeniedException 明确拒绝
     */
    default void requireAuthorized(@NonNull TenantContext context,
                                   @NonNull String action,
                                   String resourceId) {
        if (!isAuthorized(context, action, resourceId)) {
            throw new AccessDeniedException(context.principalId(), action, resourceId);
        }
    }

    /**
     * @return 当前授权版本号。撤销授权后应递增。
     */
    long currentAuthzVersion(@NonNull String tenantId, @NonNull String principalId);

    /**
     * @return 主体在目标租户内持有的角色集合。
     */
    @NonNull Set<String> rolesOf(@NonNull TenantContext context);

    /** 授权动作常量，避免字符串散落各处拼写错误。 */
    final class Actions {
        private Actions() {
        }

        /** 运行 Agent。 */
        public static final String RUN_CREATE = "run:create";
        /** 查询 Run 状态。 */
        public static final String RUN_READ = "run:read";
        /** 协作式取消 Run。 */
        public static final String RUN_CANCEL = "run:cancel";
        /** 订阅 Run 事件流（SSE）。 */
        public static final String EVENT_SUBSCRIBE = "event:subscribe";
        /** 读取 Run 结果。 */
        public static final String RESULT_READ = "result:read";
        /** 调用工具。 */
        public static final String TOOL_INVOKE = "tool:invoke";
        /** 消费审批。 */
        public static final String APPROVAL_CONSUME = "approval:consume";
        /** 处置 UNKNOWN 逻辑操作。 */
        public static final String UNKNOWN_DISPOSE = "unknown:dispose";
        /** 读取审计记录。 */
        public static final String AUDIT_READ = "audit:read";
        /** 管理 Agent 配置。 */
        public static final String AGENT_MANAGE = "agent:manage";
        /**
         * 读取学习画像（P4-D09 / G6）。
         *
         * <p>⚠️ <b>刻意不复用 {@link #RUN_READ}</b>：画像是<b>学生维度</b>数据，
         * Run 是<b>执行维度</b>数据，二者授权范围不同
         * （家长/教师可读画像，未必可读所有 Run）。为省事复用会让授权范围失真。
         */
        public static final String PROFILE_READ = "profile:read";
        /**
         * 提交/读取配置变更申请（P4-D16 / C7）。
         *
         * <p>⚠️ <b>与 {@link #AGENT_MANAGE} 分开</b>：P4-D15 的配置直写用的是
         * {@code AGENT_MANAGE}，而审批需要<b>更高且不同</b>的权限——
         * 若二者共用，会出现「有配置写权限的人也能审批」，
         * 双人复核就退化成「同一个人两个身份」。
         */
        public static final String CONFIG_REQUEST = "config:request";
        /**
         * 审批高危配置变更（P4-D16 / C7）。
         *
         * <p>⛔ <b>仍不能消除自我审批</b>：权限与身份是两件事——
         * 同一个 principal 同时具备两个动作时，双人复核靠
         * {@code approver_id <> requester_id} 兜底（应用层 + DB CHECK 双保险）。
         */
        public static final String CONFIG_APPROVE = "config:approve";

        /**
         * 申请/执行<b>权威配置对象</b>的发布、回滚与生命周期迁移（P6R4）。
         *
         * <p>⚠️ <b>与 {@link #CONFIG_REQUEST} 分开</b>：后者是<b>配置覆盖</b>
         * （键值对，受收紧策略约束），本项是<b>权威对象版本行</b>
         * （Agent / Tool / Skill / MCP Server）的发布申请 ——
         * 二者的风险模型、审批对象、写入路径都不同。
         * 混在一个动作里会出现「能改覆盖值的人，也能改版本行」。
         *
         * <p>⛔ <b>不覆盖平台级发布</b>：平台级对象无 {@code tenant_id}，
         * 走平台运维通道（BYPASSRLS 凭据 + 带外运维工具），
         * <b>不经租户身份暴露</b> —— 平台身份表是租户内的，没有跨租户主体。
         */
        public static final String CONFIG_PUBLISH = "config:publish";
        /**
         * 回滚<b>权威配置对象</b>的当前发布（P6R4）。
         *
         * <p>⚠️ <b>刻意与 {@link #CONFIG_PUBLISH} 分开</b>：
         * 回滚把当前生效配置换回历史版本，方向与「发布新配置」相反 ——
         * 在收紧优先的体系里，二者的审批语义不应混为一谈。
         */
        public static final String CONFIG_ROLLBACK = "config:rollback";

        /**
         * 委派到子 Agent（多级 Agent v1.1 · FR-025）。
         *
         * <p>⚠️ <b>与 {@link #TOOL_INVOKE} 分开</b>：委派是「把工作交给另一个 Agent」，
         * 调用工具是「在当前 Agent 内执行一个动作」。
         * 二者的资源粒度不同（前者是目标 Agent，后者是 Tool），
         * 风险模型也不同（委派会引入新的执行主体与新的失败面）。
         * 共用一个动作会让「能调工具的人」自动获得「能委派给任意 Agent」的能力。
         *
         * <p>⚠️ <b>每跳都要判</b>：委派关系不是授权关系，
         * 父 Agent 的委派权限不传递给子 Agent（子 Agent 的子委派要重新判）。
         */
        public static final String AGENT_DELEGATE = "agent:delegate";

        private static final Set<String> ALL = Set.of(
                RUN_CREATE, RUN_READ, RUN_CANCEL, EVENT_SUBSCRIBE, RESULT_READ,
                TOOL_INVOKE, APPROVAL_CONSUME, UNKNOWN_DISPOSE, AUDIT_READ, AGENT_MANAGE,
                PROFILE_READ, CONFIG_REQUEST, CONFIG_APPROVE, CONFIG_PUBLISH, CONFIG_ROLLBACK,
                AGENT_DELEGATE);

        /** @return 全部受控动作，供启动期自检使用。 */
        public static @NonNull Set<String> all() {
            return ALL;
        }
    }

    /** 角色常量。 */
    final class Roles {
        private Roles() {
        }

        /** 租户管理员。 */
        public static final String TENANT_ADMIN = "TENANT_ADMIN";
        /** Agent 作者。 */
        public static final String AGENT_AUTHOR = "AGENT_AUTHOR";
        /** 审批人（家长或教师，DEC-6）。 */
        public static final String APPROVER = "APPROVER";
        /** 只读观察者。 */
        public static final String VIEWER = "VIEWER";
        /** Worker 服务账号。 */
        public static final String WORKER = "WORKER";

        /**
         * 平台管理员（运营级）。
         *
         * <p>⚠️ <b>刻意不在 {@code RoleBasedAuthorizationService} 的权限矩阵中登记</b>：
         * 此角色由<b>平台运维通道（BYPASSRLS 凭据 + 带外运维工具）</b>授予，
         * 不经租户身份暴露（见 {@code CapConfigPublishController} 的平台级发布限制）。
         * 应用层租户主体默认<b>不持有</b>该角色，
         * 故任何依赖它的端点（如租户开通/启停/跨租户列）默认返回 403 ——
         * 这是「默认拒绝」而非「默认放行」的刻意结果。
         *
         * <p>⚠️ 本角色用于<b>跨租户</b>的租户生命周期与主体治理；
         * 普通租户管理员（{@link #TENANT_ADMIN}）仅能管理<b>本租户</b>资源。
         */
        public static final String PLATFORM_ADMIN = "PLATFORM_ADMIN";

        /** 超级管理员（平台级最高权限，当前预留）。 */
        public static final String SUPER_ADMIN = "SUPER_ADMIN";
    }
}