package cn.studykid.multiagent.ports.security;

/**
 * 权限命名空间冻结清单（重规划 §5.2）。
 *
 * <p>两套命名空间互不可混：平台域 {@code platform.*}
 * 与租户域 {@code tenant.*} 由不同授权服务、不同主体、不同资源范围校验。
 * 正式契约须冻结完整清单、资源类型与操作定义；此处为 P0 基线常量。
 */
public final class Namespaces {

    private Namespaces() {
    }

    /** 平台权限命名空间。 */
    public static final class Platform {
        private Platform() {
        }

        public static final String USER_READ = "platform.user.read";
        public static final String USER_MANAGE = "platform.user.manage";

        public static final String ROLE_READ = "platform.role.read";
        public static final String ROLE_ASSIGN = "platform.role.assign";
        public static final String ROLE_REVOKE = "platform.role.revoke";

        public static final String TENANT_CREATE = "platform.tenant.create";
        public static final String TENANT_READ = "platform.tenant.read";
        public static final String TENANT_UPDATE = "platform.tenant.update";
        public static final String TENANT_SUSPEND = "platform.tenant.suspend";
        public static final String TENANT_RESUME = "platform.tenant.resume";
        public static final String TENANT_DECOMMISSION = "platform.tenant.decommission";

        public static final String TENANT_QUOTA_READ = "platform.tenant.quota.read";
        public static final String TENANT_QUOTA_UPDATE = "platform.tenant.quota.update";

        public static final String TENANT_CAPABILITY_GRANT = "platform.tenant.capability.grant";
        public static final String TENANT_CAPABILITY_REVOKE = "platform.tenant.capability.revoke";
        public static final String TENANT_CAPABILITY_READ = "platform.tenant.capability.read";

        public static final String RESOURCE_READ = "platform.resource.read";
        public static final String RESOURCE_MANAGE = "platform.resource.manage";

        public static final String POLICY_READ = "platform.policy.read";
        public static final String POLICY_PUBLISH = "platform.policy.publish";

        public static final String AUDIT_READ = "platform.audit.read";
        public static final String AUDIT_EXPORT = "platform.audit.export";

        public static final String SUPPORT_DIAGNOSTICS_READ = "platform.support.diagnostics.read";
    }

    /** 租户权限命名空间。 */
    public static final class Tenant {
        private Tenant() {
        }

        public static final String MEMBER_READ = "tenant.member.read";
        public static final String MEMBER_INVITE = "tenant.member.invite";
        public static final String MEMBER_REMOVE = "tenant.member.remove";

        public static final String ROLE_READ = "tenant.role.read";
        public static final String ROLE_ASSIGN = "tenant.role.assign";

        public static final String AGENT_READ = "tenant.agent.read";
        public static final String AGENT_EDIT = "tenant.agent.edit";
        public static final String AGENT_PUBLISH = "tenant.agent.publish";

        public static final String SKILL_READ = "tenant.skill.read";
        public static final String SKILL_MANAGE = "tenant.skill.manage";

        public static final String TOOL_USE = "tenant.tool.use";
        public static final String TOOL_CONFIGURE = "tenant.tool.configure";

        public static final String MCP_READ = "tenant.mcp.read";
        public static final String MCP_CONFIGURE = "tenant.mcp.configure";

        public static final String RUN_CREATE = "tenant.run.create";
        public static final String RUN_READ = "tenant.run.read";
        public static final String RUN_CANCEL = "tenant.run.cancel";

        public static final String AUDIT_READ = "tenant.audit.read";
    }
}
