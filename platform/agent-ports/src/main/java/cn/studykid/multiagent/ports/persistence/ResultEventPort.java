package cn.studykid.multiagent.ports.persistence;

import java.util.Optional;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.core.cap.ResultEvent;

/**
 * 确定结果事件仓储端口（§6.1 · W-PERM-P0-T5）。
 *
 * <p>⚠️ <b>本端口是「只重试落账、不重执行」的地基</b>（§7.4 双维度结果）：
 * 同一 invocation <b>最多一条</b>结果事件。写入方在 P3（副作用之后），
 * 恢复方在 P3/P2（崩溃后重启）。
 *
 * <p>若没有这条唯一性约束，「确定成功但 POST 失败 → 重试落账」随时可能
 * 退化成「重新执行副作用」——重复扣款、重复写外部系统这类 Sev-0 事故的
 * 典型成因。
 */
public interface ResultEventPort {

    /**
     * <b>幂等</b>写入确定结果事件。
     *
     * <p>语义：同一 {@code (tenantId, invocationId)} 已存在结果事件时，
     * <b>不覆盖</b>既有结果（先写入者为准），返回 {@code false}。
     *
     * @return {@code true} 表示本次写入；{@code false} 表示已存在（幂等命中）
     */
    boolean recordIfAbsent(@NonNull ResultEvent event);

    /**
     * 按调用标识读取结果事件。
     */
    @NonNull Optional<ResultEvent> findByInvocation(@NonNull String tenantId,
                                                     @NonNull String invocationId);
}
