package cn.studykid.multiagent.infrastructure.security;

import cn.studykid.multiagent.core.security.Action;
import cn.studykid.multiagent.core.security.ResourceRef;
import cn.studykid.multiagent.core.security.ScopeFilter;
import cn.studykid.multiagent.core.tenant.SubjectContext;

import org.jspecify.annotations.NonNull;

/**
 * 鉴权门禁 —— 数据访问层的入口强制点（12 文 §2.4 / L2）。
 *
 * <p>仓储（Repository）与检索适配器（RetrievalAdapter）必须通过本门禁取得
 * {@link ScopeFilter} 并强制渲染进查询；<b>无 Gate 无实例</b>的编译期保证靠
 * 「Gate 是构造必填参数」实现。Deny 必须显式抛出（fail-closed）。
 */
public interface AccessGate {

    /** 单资源鉴权：详情/更新/删除路径。Deny 必须显式抛出（fail-closed）。 */
    @NonNull ScopeFilter authorize(@NonNull Action action, @NonNull ResourceRef resource);

    /** 集合鉴权：列表/检索路径。返回的 ScopeFilter 必须被查询强制携带。 */
    @NonNull ScopeFilter authorizeCollection(@NonNull Action action, @NonNull String resourceType);

    /**
     * 集合鉴权（显式主体）：仓储<b>已知租户</b>时直接传入主体，避免依赖线程局部解析器。
     *
     * <p>影子比对 / 染色校验期，仓储以方法形参持有 {@code tenantId}，用它构造
     * {@link SubjectContext#forTenant(String)} 后走本方法，使比对自包含、不依赖请求上下文。
     */
    @NonNull ScopeFilter authorizeCollection(@NonNull SubjectContext subject,
                                              @NonNull Action action,
                                              @NonNull String resourceType);
}
