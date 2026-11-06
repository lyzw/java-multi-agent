package cn.studykid.multiagent.infrastructure.security;

import cn.studykid.multiagent.core.security.Action;
import cn.studykid.multiagent.core.security.ResourceRef;
import cn.studykid.multiagent.core.security.ScopeFilter;
import cn.studykid.multiagent.core.tenant.SubjectContext;

import org.jspecify.annotations.NonNull;

/**
 * 仓储取 {@link ScopeFilter} 的统一接缝（12 文 §2.4 / D5）。
 *
 * <p>方案把「Gate 执行期取 filter」作为类型系统防线的落点：<b>每个查询方法都必须通过本接缝
 * 取得 ScopeFilter 并渲染进 SQL</b>（P2 双写期才会真正注入；P1 影子期只用于比对/染色）。
 * 该接缝同时是 ArchUnit 的钉死目标（D7）：凡走租户数据的 Repository，其查询方法
 * 必须存在一次 {@code ScopedDataAccess} 调用。
 *
 * <p>{@code AccessGate} 已在内部通过 {@link SubjectContextResolver} 解析主体，
 * 本类仅作语义明确的门面，便于仓储注入与单测打桩。Deny 由 Gate 抛
 * {@link AccessDeniedException}（fail-closed）。
 */
public final class ScopedDataAccess {

    private final @NonNull AccessGate gate;

    public ScopedDataAccess(@NonNull AccessGate gate) {
        this.gate = gate;
    }

    /** 集合检索路径（列表/聚合/检索）。返回的 ScopeFilter 必须被查询强制携带。 */
    public @NonNull ScopeFilter scopeForCollection(@NonNull Action action, @NonNull String resourceType) {
        return gate.authorizeCollection(action, resourceType);
    }

    /**
     * 集合检索路径（显式主体）：仓储已知租户时直接传入，避免依赖线程局部解析器。
     *
     * @see AccessGate#authorizeCollection(SubjectContext, Action, String)
     */
    public @NonNull ScopeFilter scopeForCollection(@NonNull SubjectContext subject,
                                                    @NonNull Action action,
                                                    @NonNull String resourceType) {
        return gate.authorizeCollection(subject, action, resourceType);
    }

    /** 单资源路径（详情/更新/删除）。 */
    public @NonNull ScopeFilter authorize(@NonNull Action action, @NonNull ResourceRef resource) {
        return gate.authorize(action, resource);
    }
}
