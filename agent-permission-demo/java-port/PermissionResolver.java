package cn.studykid.multiagent.core.permission;

import java.util.Set;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.security.AuthorizationService;
import cn.studykid.multiagent.ports.skill.Skill;

/**
 * 权限解析与校验（对应 PRD §3.2 调用期逐跳重验）。
 *
 * <p>复用现有地基：{@link AuthorizationService#isAuthorized}（含 authzVersion 重授权）、
 * RLS 租户隔离、{@code ToolInvocationGate}（审批/预算/账本）。本类只负责「编排层闭包校验」，
 * 不替代执行闸门。
 */
public final class PermissionResolver {

    private PermissionResolver() {}

    /**
     * 校验 Agent 经 Skill 调用某 Tool 的权限（native 路径）。
     *
     * @return 决策（fail-closed：任一跳缺失即 DENY）
     */
    public static @NonNull Decision checkNative(@NonNull TenantContext ctx,
                                                @NonNull String agentId,
                                                @NonNull Skill skill,
                                                @NonNull String toolId,
                                                @NonNull AuthorizationService authz,
                                                long currentAuthzVersion) {
        // [1] Agent → Skill：agentId 的有效 Skill 集须含 skill.skillId()
        if (!agentEnablesSkill(ctx, agentId, skill)) {
            return Decision.deny("agent_skill", "Agent 未启用该 Skill");
        }
        // [2] Skill → Tool + riskCeiling
        if (!skill.declaredTools().contains(toolId)) {
            return Decision.deny("skill_tool", "Skill 未声明该 Tool");
        }
        // [4] 主体重授权（authzVersion 重授权，红线 9）
        if (ctx.authzVersion() != currentAuthzVersion
                || !authz.isAuthorized(ctx, AuthorizationService.Actions.TOOL_INVOKE, toolId)) {
            return Decision.deny("stale_authz", "authzVersion 陈旧或主体无权限");
        }
        return Decision.ok();
    }

    // agentEnablesSkill：查询 Agent 有效 Skill 集（含 inherits 模板、overrides 移除、requiredCaps 过滤）
    private static boolean agentEnablesSkill(TenantContext ctx, String agentId, Skill skill) {
        // 实际实现读 ConfigStore / EffectiveConfigResolver（yml ⊕ DB 覆盖，收紧棘轮）
        throw new UnsupportedOperationException("见 resolver.py 的可运行实现");
    }

    /** 结构化决策（对应 PRD §3.2 决策日志）。 */
    public record Decision(boolean allowed, String stage, String reason) {
        public static Decision ok() { return new Decision(true, "allowed", "闭环校验通过"); }
        public static Decision deny(String s, String r) { return new Decision(false, s, r); }
    }
}
