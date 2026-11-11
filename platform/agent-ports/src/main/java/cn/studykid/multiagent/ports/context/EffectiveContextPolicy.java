package cn.studykid.multiagent.ports.context;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 已裁决压缩策略的<b>中性投影</b>（ADR-013 / M6，S2 端口契约）。
 *
 * <p>⚠️ <b>为什么存在</b>：{@code agent-context} 的 {@code ContextPolicy} 是算法层
 * record；端口层（{@code agent-ports}）按 ADR-011 / C1 <b>不得依赖 agent-context</b>。
 * 因此 {@link ContextAssemblerPort} 的入参用本 record 承载「{@code ContextPolicyResolver}
 * 已裁决完成」的全部字段，由 M7 的装配适配器<b>逐字段显式映射</b>。
 *
 * <p>⚠️ <b>铁律 #10</b>：本 record 与 {@code ContextPolicy} 的字段名必须一一对应；
 * M6-d 的 {@code platform-bootstrap} 守卫用反射锁死（端口模块不能反向依赖算法模块，
 * 故 parity 守卫放在能同时看到两侧的 bootstrap 层）。任一新增字段未同步到本处，
 * 映射就会静默丢字段（策略静默回退默认值 = 不报错、只是行为悄悄变了）。
 *
 * <p>⚠️ 纯值对象：零 Spring / 零 JDBC / 零 IO；{@code strategy} / {@code carryOver}
 * 用枚举<b>名</b>字符串（{@code OFF|TRIM_ONLY|TIERED}、{@code CLIENT|SERVER}），
 * 避免端口层依赖算法层的嵌套枚举。空串 = 非法，由下游 fail-closed 解析。
 *
 * <p>⚠️ <b>不在本类提供默认值</b>：默认口径的唯一来源是算法层 {@code ContextPolicy.defaults()}；
 * 这里若再写一份会形成「两处默认值」的静默漂移。
 *
 * @param enabled              是否启用压缩
 * @param maxInputTokens       已裁决的输入窗口（三层 MIN 的结果）
 * @param triggerRatio         W2 触发水位
 * @param targetRatio          W2 裁剪目标（严格小于 triggerRatio）
 * @param reserveRatio         输出预留比例
 * @param recentTurns          近期逐字保留轮数
 * @param pinFirstUserTurn     首轮任务锚是否 pinned
 * @param ragRatio             RAG 块 cap 占弹性预算比例
 * @param memoryRatio          记忆块 cap 比例
 * @param summaryRatio         摘要块 cap 比例
 * @param strategy             策略档位枚举名
 * @param summarizerModel      W3 摘要模型；null = 未配置（fail-closed）
 * @param summaryMaxTokens     摘要最大输出 token
 * @param summaryTimeoutMs     摘要调用超时
 * @param maxCompactionsPerRun 单 Run 最多压缩次数
 * @param carryOver            压缩态载体枚举名
 * @param checkpointTtlSeconds checkpoint TTL
 * @param policyVersion        策略指纹（不匹配即丢弃重建）
 */
public record EffectiveContextPolicy(
        boolean enabled,
        int maxInputTokens,
        double triggerRatio,
        double targetRatio,
        double reserveRatio,
        int recentTurns,
        boolean pinFirstUserTurn,
        double ragRatio,
        double memoryRatio,
        double summaryRatio,
        @NonNull String strategy,
        @Nullable String summarizerModel,
        int summaryMaxTokens,
        int summaryTimeoutMs,
        int maxCompactionsPerRun,
        @NonNull String carryOver,
        int checkpointTtlSeconds,
        @NonNull String policyVersion) {

    public EffectiveContextPolicy {
        strategy = strategy == null ? "" : strategy;
        carryOver = carryOver == null ? "" : carryOver;
        policyVersion = policyVersion == null ? "" : policyVersion;
    }
}
