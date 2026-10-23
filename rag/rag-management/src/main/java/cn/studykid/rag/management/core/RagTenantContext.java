package cn.studykid.rag.management.core;

import org.jspecify.annotations.NonNull;

import cn.studykid.rag.core.source.TrustTier;

/**
 * 由 platform 下发的租户上下文（RAG-D00 §4「租户与身份」边界）。
 *
 * <p><b>⚠️ 为什么 RAG 需要这个类型，而 RAG 库里没有 {@code rag_tenant} 表</b>：
 * 租户信息（身份、角色、权威门槛）的<b>权威源始终是 platform</b>
 * （RAG-D00 §4：「RAG 只消费 TenantContext，不自建认证」）。
 * RAG 库里若存一份租户副本，就会出现两份权威源互相漂移——
 * 实际后果是「platform 新建租户，RAG 侧查不到任何数据」
 * （因为子表的 {@code tenant_id} 外键要求该租户在 RAG 清单里存在）。
 *
 * <p>故 RAG 侧改为<b>消费</b> platform 下发的上下文：
 * <ul>
 *   <li>{@code tenantId} —— 权威租户 ID，用于重建 RLS 会话变量；</li>
 *   <li>{@code minTrustTier} —— 租户权威门槛（G-3），
 *       由 platform 判定后下发，RAG <b>不自行推断、不自行存储</b>。</li>
 * </ul>
 *
 * <p><b>⚠️ 门槛下发的信任边界</b>：该值由 platform 在<b>鉴权之后</b>下发，
 * RAG 信任它——因为 RAG 部署在 platform 的鉴权边界之后，
 * 且自己不做认证（RAG-D02 §1.1 单向调用约定）。
 * 若 RAG 被暴露到鉴权边界之外，调用方即可伪造门槛值放行低等级语料。
 */
public record RagTenantContext(
        @NonNull String tenantId,
        @NonNull TrustTier minTrustTier
) {

    public RagTenantContext {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId 不能为空");
        }
        if (minTrustTier == null) {
            throw new IllegalArgumentException("minTrustTier 不能为空");
        }
    }
}
