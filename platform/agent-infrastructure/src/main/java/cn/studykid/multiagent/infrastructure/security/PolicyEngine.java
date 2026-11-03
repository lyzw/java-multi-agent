package cn.studykid.multiagent.infrastructure.security;

import cn.studykid.multiagent.core.security.Action;
import cn.studykid.multiagent.core.security.Decision;
import cn.studykid.multiagent.core.security.ResourceRef;
import cn.studykid.multiagent.core.tenant.SubjectContext;

import org.jspecify.annotations.NonNull;

/**
 * 权限决策引擎 —— 数据层行级隔离的决策核心（12 文 §2.3 / D3）。
 *
 * <p>与 05 文能力层（Agent/Skill/Tool/MCP）授权决策因子互不重叠：本文只管
 * 「能碰到哪些行」。决策可解释、可缓存、可版本化。
 */
public interface PolicyEngine {

    @NonNull Decision decide(@NonNull SubjectContext subject,
                             @NonNull Action action,
                             @NonNull ResourceRef resource);
}
