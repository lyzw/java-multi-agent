package cn.studykid.multiagent.bootstrap.config;

import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import cn.studykid.multiagent.context.CompactionPipeline.Summarizer;
import cn.studykid.multiagent.context.ContextAssembler;
import cn.studykid.multiagent.context.GlobalContextPolicy;
import cn.studykid.multiagent.context.WaterLevel.Thresholds;
import cn.studykid.multiagent.ports.context.TokenCounter;
import cn.studykid.multiagent.ports.contextwiring.RequestAssemblyPort;
import cn.studykid.multiagent.ports.modelcatalog.ModelCatalogPort;
import cn.studykid.multiagent.ports.persistence.CapAgentDefPort;

/**
 * M7-b 组合根：把纯算法装配内核、冻结策略读取、模型能力读取组合成
 * 一个<b>中性端口 Bean</b> 交给 Worker 两条链路（ADR-013 D38 / G1 A.5）。
 *
 * <p>⚠️ <b>为什么单独建一个 {@code @Configuration}</b>：S1 的
 * {@link AgentContextConfiguration} 只负责「配置投影 + 计量工具」，
 * 由 G1 守卫钉死「零生产接线」。M7-b 的接线边界必须独立成片，
 * 升级守卫时能精确指向本类，不把影子期的配置投影混进来（G1 报告 A.5 台账）。
 *
 * <p>⚠️ <b>水位阈值来自真实 yml</b>：{@link Thresholds#defaults()} 只供算法单测，
 * 生产必须由 {@link AgentContextProperties} 构造，否则
 * 「改了 AGENT_CONTEXT_TIER_SNIP 却不生效」是静默失真。
 *
 * <p>⚠️ <b>W3 摘要器当前为 NoOp</b>：M12 才落真摘要器；NoOp 返回 empty 会走
 * 管线的「摘要失败回落 W2 + fallback=true」路径，⛔ 绝不伪造摘要文本。
 * 用 {@code @ConditionalOnMissingBean} 让 M12 的真实现直接覆盖，无需改本类。
 *
 * <p>⚠️ <b>多 Bean 与构造器纪律</b>：全部用 {@code @Bean} 显式 {@code new}，
 * 不依赖组件扫描猜构造器（项目铁律 #3）；{@code @ConditionalOnMissingBean}
 * 只用于「可被上层实现覆盖」的纯算法对象，不加 {@code @Primary}。
 */
@Configuration
public class ContextRequestAssemblyConfiguration {

    /** 全局水位阈值：逐字来自 {@code agent.context.tier-*}（⛔ 不用 defaults() 旁路 yml）。 */
    @Bean
    @ConditionalOnMissingBean(Thresholds.class)
    public @NonNull Thresholds contextThresholds(
            @NonNull AgentContextProperties properties) {
        return new Thresholds(
                properties.getTierSnipRatio(),
                properties.getTierSummaryRatio(),
                properties.getMinNewTokensRatio());
    }

    /**
     * W3 摘要能力占位：M12 前一律 empty → 管线回落 W2 并如实标记 {@code fallback=true}。
     */
    @Bean
    @ConditionalOnMissingBean(Summarizer.class)
    public @NonNull Summarizer contextNoOpSummarizer() {
        return (delta, oldSummary) -> Optional.empty();
    }

    /** 纯算法装配内核：由组合根持有，worker 永远看不到它。 */
    @Bean
    @ConditionalOnMissingBean(ContextAssembler.class)
    public @NonNull ContextAssembler contextAssembler(
            @NonNull TokenCounter counter, @NonNull Thresholds thresholds) {
        return new ContextAssembler(counter, thresholds);
    }

    /**
     * 唯一的统一请求装配端口。
     *
     * <p>⚠️ {@link CapAgentDefPort} / {@link ModelCatalogPort} 标为可选注入：
     * 某些裁剪过的 Spring 测试切片不带持久层 Bean，此时仍须能启动；
     * 但一旦总开关打开而依赖缺失，{@link ContextRequestAssemblyAdapter}
     * 会按稳定原因码 <b>fail-closed 拒绝装配</b>（绝不静默回退 legacy）。
     * 生产装配非 null 由 {@code ContextRequestAssemblyWiringTest} 断言。
     */
    @Bean
    public @NonNull RequestAssemblyPort requestAssemblyPort(
            @NonNull GlobalContextPolicy globalContextPolicy,
            @NonNull ContextAssembler contextAssembler,
            @NonNull Summarizer contextNoOpSummarizer,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            @Nullable CapAgentDefPort capAgentDefPort,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            @Nullable ModelCatalogPort modelCatalogPort) {
        return new ContextRequestAssemblyAdapter(
                globalContextPolicy, contextAssembler, contextNoOpSummarizer,
                capAgentDefPort, modelCatalogPort);
    }
}
