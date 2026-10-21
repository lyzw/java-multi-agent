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

        private static final Set<String> ALL = Set.of(
                RUN_CREATE, RUN_READ, RUN_CANCEL, EVENT_SUBSCRIBE, RESULT_READ,
                TOOL_INVOKE, APPROVAL_CONSUME, UNKNOWN_DISPOSE, AUDIT_READ, AGENT_MANAGE,
                PROFILE_READ, CONFIG_REQUEST, CONFIG_APPROVE);

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
    }
}