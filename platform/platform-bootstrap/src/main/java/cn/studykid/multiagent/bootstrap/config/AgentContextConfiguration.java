package cn.studykid.multiagent.bootstrap.config;

import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import cn.studykid.multiagent.context.GlobalContextPolicy;
import cn.studykid.multiagent.context.HeuristicTokenCounter;
import cn.studykid.multiagent.context.TokenEstimationCalibrator;
import cn.studykid.multiagent.ports.context.TokenCounter;

/**
 * 上下文压缩组合根（ADR-013 / M2）：把 {@link AgentContextProperties} 转成
 * 纯算法层的不可变值对象并注册为 Bean，供校准桥与 S2 的 Resolver 消费。
 *
 * <p>⚠️ <b>S1 影子期的硬边界</b>：本类只注册「配置投影 + 计量工具」三类纯对象，
 * <b>绝不</b>把它们注入任何模型请求构造点（{@code RunExecutionLoop} /
 * {@code WorkerChildAgentRunner} / {@code AgentChatOrchestrator} /
 * {@code ConsoleChatController}）。「零接线」由
 * {@code ContextCompressionS1GuardTest#shadowPhaseHasNoProductionWiring} 从源码锁死。
 *
 * <p>⚠️ <b>为什么 fail-fast 而非静默夹取</b>：非法全局值（非正硬顶、比例反转、
 * reserve 越界）若被静默夹取，运维看到的是「配了但上限没变」——
 * 与项目铁律「不报错、只是数字悄悄错」同形态。启动期直接抛，
 * 让配置错误在部署第一时间暴露。
 *
 * <p><b>多构造纪律</b>：本类所有返回类型都是纯 Java 对象（无 Spring 参数注入歧义），
 * 由 {@code @Bean} 方法显式 {@code new}，不依赖组件扫描猜构造器（项目铁律 #3）。
 */
@Configuration
public class AgentContextConfiguration {

    /**
     * 全窗口 Token 计数器：影子期用启发式保守估算，S2 可经
     * {@code @ConditionalOnMissingBean} 被精确 tokenizer 实现覆盖。
     */
    @Bean
    @ConditionalOnMissingBean(TokenCounter.class)
    public @NonNull TokenCounter contextTokenCounter() {
        return new HeuristicTokenCounter();
    }

    /**
     * 估算偏差校准器：按 modelId 聚合 EWMA，n&lt;30 恒 bias=1.0（R2 / §7.1）。
     * 参数全部来自 yml，构造期校验非法值（minSamples≥2、alpha∈(0,1]、clamp 有序）。
     */
    @Bean
    @ConditionalOnMissingBean(TokenEstimationCalibrator.class)
    public @NonNull TokenEstimationCalibrator contextTokenEstimationCalibrator(
            @NonNull AgentContextProperties properties) {
        AgentContextProperties.Calibration c = properties.getCalibration();
        return new TokenEstimationCalibrator(c.getMinSamples(), c.getAlpha(),
                c.getBiasClampMin(), c.getBiasClampMax());
    }

    /**
     * 全局策略投影：{@code agent.context.*} → {@link GlobalContextPolicy}。
     *
     * <p>⚠️ 第 4 参数 {@code unknownCapabilityReserveRatio} 必须来自配置，
     * 不能写死 0 —— 否则 R10（能力未知时上调安全余量）静默失效。
     * 影子期 {@code enabled=false}，与「无压缩功能」逐位一致（[D11]）。
     */
    @Bean
    @ConditionalOnMissingBean(GlobalContextPolicy.class)
    public @NonNull GlobalContextPolicy globalContextPolicy(
            @NonNull AgentContextProperties properties) {
        validate(properties);
        return new GlobalContextPolicy(
                properties.isEnabled(),
                properties.getHardCeilingTokens(),
                properties.getDefaultMaxInputTokens(),
                properties.getUnknownCapabilityReserveRatio());
    }

    /**
     * 启动期配置门禁：把「配了但不生效」的静默形态变成启动失败。
     * 与 {@code ContextPolicyValidator}（发布期）职责不同——本方法只校验<b>全局层</b>，
     * 且范围刻意窄（只校验确实会被消费的字段），避免与 Agent 级校验重复。
     */
    private static void validate(@NonNull AgentContextProperties p) {
        require(p.getHardCeilingTokens() > 0, "agent.context.hard-ceiling-tokens 必须为正");
        require(p.getDefaultMaxInputTokens() > 0,
                "agent.context.default-max-input-tokens 必须为正");
        require(p.getDefaultMaxInputTokens() <= p.getHardCeilingTokens(),
                "agent.context.default-max-input-tokens 不得大于 hard-ceiling-tokens");
        require(p.getDefaultTriggerRatio() > 0 && p.getDefaultTriggerRatio() <= 1,
                "agent.context.default-trigger-ratio 必须在 (0,1]");
        require(p.getDefaultTargetRatio() > 0 && p.getDefaultTargetRatio() < p.getDefaultTriggerRatio(),
                "agent.context.default-target-ratio 必须 >0 且 < default-trigger-ratio"
                        + "（否则压完立刻再触发，抖动风暴）");
        require(p.getUnknownCapabilityReserveRatio() > 0
                        && p.getUnknownCapabilityReserveRatio() <= 0.25,
                "agent.context.unknown-capability-reserve-ratio 必须在 (0,0.25]"
                        + "（R10：能力未知时上调 reserve 的上限）");
        require(p.getDefaultRecentTurns() >= 2, "agent.context.default-recent-turns 必须 >=2");
        AgentContextProperties.Calibration c = p.getCalibration();
        require(c.getMinSamples() >= 2 && c.getAlpha() > 0 && c.getAlpha() <= 1
                        && c.getBiasClampMin() > 0 && c.getBiasClampMax() > c.getBiasClampMin(),
                "agent.context.calibration 参数非法（minSamples>=2, alpha∈(0,1], clampMin<clampMax）");
    }

    private static void require(boolean ok, @NonNull String message) {
        if (!ok) {
            throw new IllegalArgumentException("上下文压缩全局配置非法：" + message);
        }
    }
}
