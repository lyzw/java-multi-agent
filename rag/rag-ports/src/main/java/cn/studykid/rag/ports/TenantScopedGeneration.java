package cn.studykid.rag.ports;

import org.jspecify.annotations.NonNull;

import cn.studykid.rag.core.generation.GenerationRequest;
import cn.studykid.rag.core.generation.GenerationResult;

/**
 * 租户作用域生成端口（RAG-D00 §2.7 / B5）。
 *
 * <p><b>为什么需要这一层</b>（与 {@link TenantScopedRetrieval} 完全同构）：
 * 生成期的依据取回 SQL 同样以 {@code tenant_id = rag_current_tenant()} 收口，
 * 而该函数读的会话变量 {@code app.tenant_id} <b>只在事务内有效</b>。
 * RAG 独立对外提供 HTTP 端点时，主平台的事务上下文跨进程丢失，
 * 若不显式重建，RLS 会把全部行过滤掉——表现为「依据永远取不到」，
 * 而不是报错。
 *
 * <p>本端口把「在事务内建立租户上下文 → 取回依据 → 装配讲解」封成一个
 * 不可绕过的动作，使实现方无法在缺少租户上下文的情况下生成。
 *
 * <p><b>⚠️ 信任边界</b>：租户标识由调用方（主平台）提供，
 * RAG 不自建用户体系、不解析令牌（RAG-D02 §1.1 单向调用约定）。
 * 因此本服务必须部署在平台鉴权边界之后，不应直接暴露给终端用户。
 *
 * <p><b>⚠️ 与 {@link TenantScopedRetrieval} 的分工</b>：
 * 检索端「决定取哪些语料」，生成端「把调用方指定的语料装配成讲解」。
 * 二者都必须在租户事务内执行，但<b>不可互相替代</b>——
 * 让生成端自行检索，就等于把「依据集合」重新变成服务的黑箱决策，
 * 破坏可解释性与可审计性（见 {@link GenerationRequest}）。
 */
public interface TenantScopedGeneration {

    /**
     * 在指定租户作用域内生成讲解。
     *
     * @param tenantId 租户 ID（UUID 字符串）
     * @param request  生成请求（含调用方指定的依据片段 ID）
     * @return 生成结果（依据不足时为 {@link GenerationResult#insufficient}）
     */
    @NonNull GenerationResult generate(@NonNull String tenantId, @NonNull GenerationRequest request);
}
