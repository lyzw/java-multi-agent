package cn.studykid.multiagent.infrastructure.persistence.scope;

import org.jspecify.annotations.NonNull;

/**
 * 单行结果的租户归属 —— 染色校验的基本单元（12 文 §4.5 / D6）。
 *
 * <p>应用层在映射每行时把「该行属于哪个租户 / 是否平台共享行」登记为一个
 * {@code RowAttribution}，交由 {@link TaintVerifier} 与本次查询的
 * {@link cn.studykid.multiagent.core.security.ScopeFilter} 比对，从而在不依赖
 * RLS 的前提下复现「越权即红」的语义。
 *
 * <p>{@code shared} 仅对 RAG 语料等带 {@code scope} 列的表有意义（哨兵租户行）；
 * platform 业务表的行 {@code shared} 恒为 {@code false}。
 */
public record RowAttribution(@NonNull String tenantId, boolean shared) {
}
