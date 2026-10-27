package cn.studykid.multiagent.ports.persistence;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 安全事件写入端口（P7-4 · SEC-2/SEC-3 明细事实）。
 *
 * <p><b>⚠️ 写 {@code platform.agent_risk_event}（平台域，无 RLS）</b>：
 * 安全事件是平台观测域的合规数据，需要跨租户聚合与告警，
 * 与事实表（带 FORCE RLS）刻意分离——「读在租户域、写在平台域」同纪律。
 *
 * <p><b>⚠️ 合规红线（不可妥协）</b>：{@code detailDigest} 只接受<b>摘要</b>
 * （模式类型代号 / 计数 / 哈希前 8 位），<b>绝不落原文</b>。
 * 命中敏感信息的原文一旦入库就构成二次泄露——
 * 「拦截住了」会变成「存下来了」。本端口不提供任何承载原文的通道。
 *
 * <p><b>⚠️ 事件类型固定三值</b>（{@code agent_risk_event_type_ck} 约束）：
 * {@code SAFETY_BLOCK}（安全拦截）· {@code UNAUTHORIZED_CALL}（越权调用尝试）·
 * {@code SENSITIVE_OUTPUT}（敏感信息输出命中）。
 *
 * <p><b>⚠️ 接口只认 {@code runId}</b>：Worker 执行链路只有 Run 维度；
 * {@code trace_id} 由实现按采集器同一口径解析
 * （run.trace_id → invocation(depth=1).trace_id → correlation_id → 解析不出则放弃）。
 *
 * <p><b>⚠️ 写入失败绝不冒泡</b>：安全事件是合规事实，但写入失败（DB 抖动）
 * 绝不能让一次本可成功的 Run 变成失败——实现必须吞异常记 WARN。
 */
public interface RiskEventPort {

    /** SAFETY_BLOCK：安全策略拦截（如编造出处、内容安全拒绝）。 */
    String SAFETY_BLOCK = "SAFETY_BLOCK";

    /** UNAUTHORIZED_CALL：Agent 尝试调用无权限工具 / 访问越权数据。 */
    String UNAUTHORIZED_CALL = "UNAUTHORIZED_CALL";

    /** SENSITIVE_OUTPUT：输出命中手机号 / 身份证等隐私模式。 */
    String SENSITIVE_OUTPUT = "SENSITIVE_OUTPUT";

    /**
     * 记录一条安全事件。
     *
     * @param tenantId     租户 ID（解析 trace_id 与归属）
     * @param runId        Run ID（trace_id 由此解析，口径与指标采集器一致）
     * @param eventType    三值之一：{@code SAFETY_BLOCK} / {@code UNAUTHORIZED_CALL} / {@code SENSITIVE_OUTPUT}
     * @param detailDigest 事件<b>摘要</b>——模式代号 / 计数 / 哈希前 8 位，绝不含原文片段
     */
    void record(@NonNull String tenantId,
                @NonNull String runId,
                @NonNull String eventType,
                @Nullable String detailDigest);
}
