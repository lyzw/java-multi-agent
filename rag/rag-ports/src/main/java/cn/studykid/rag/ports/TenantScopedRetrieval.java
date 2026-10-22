package cn.studykid.rag.ports;

import org.jspecify.annotations.NonNull;

import cn.studykid.rag.core.retrieval.RetrievalRequest;
import cn.studykid.rag.core.retrieval.RetrievalResult;

/**
 * 租户作用域检索端口（RAG-D02 §3.2 补充 / RAG-D00 §2.8）。
 *
 * <p><b>为什么需要这一层</b>：检索 SQL 一律以
 * {@code WHERE tenant_id = rag_current_tenant()} 收口，而
 * {@code rag_current_tenant()} 读的是会话变量 {@code app.tenant_id}。
 * 该变量<b>只能在事务内</b>用 {@code set_config('app.tenant_id', ?, true)} 设定，
 * 且必须在任何业务 SQL 之前设定。
 *
 * <p>主平台与 RAG 同进程调用时，平台的事务上下文天然携带该变量；
 * 但 RAG 独立对外提供 HTTP 端点时，事务上下文<b>跨进程丢失</b>——
 * 若不显式重建租户上下文，{@code rag_current_tenant()} 恒为 NULL，
 * RLS 会把<b>所有</b>行过滤掉，表现为「检索永远为空」，
 * 而不是报错——这是最难排查的一类静默故障。
 *
 * <p>本端口把「在事务内建立租户上下文 → 执行检索」封成一个不可绕过的动作，
 * 使实现方无法在缺少租户上下文的情况下执行检索。
 *
 * <p><b>⚠️ 信任边界</b>：租户标识由调用方（主平台）提供，
 * RAG 不自建用户体系、不解析令牌（RAG-D02 §1.1 单向调用约定）。
 * 因此本服务必须部署在平台鉴权边界之后，
 * 不应直接暴露给终端用户。
 */
public interface TenantScopedRetrieval {

    /**
     * 在指定租户作用域内执行检索。
     *
     * @param tenantId 租户 ID（UUID 字符串）
     * @param request  检索请求（含模式与版本范围）
     * @return 检索结果（按相关性倒序）
     */
    @NonNull RetrievalResult retrieve(@NonNull String tenantId, @NonNull RetrievalRequest request);
}
