package cn.studykid.rag.infrastructure.shadow;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 影子观察期（M2）装配（D6）。
 *
 * <p>仅在 {@code rag.shadow.enabled=true} 时生效；默认关闭 → 不注册任何 Bean →
 * 各仓储的 {@link RagShadow} 采集点全部 no-op，<b>零行为变更</b>。
 *
 * <p>配置项（application.yml）：
 * <ul>
 *   <li>{@code rag.shadow.enabled}（默认 false）—— 总开关；</li>
 *   <li>{@code rag.shadow.sample-rate}（默认 1.0，全量）—— 采样率；</li>
 *   <li>{@code rag.shadow.block-on-violation}（默认 false）—— P2 才翻 true。</li>
 * </ul>
 */
@Configuration
@ConditionalOnProperty(prefix = "rag.shadow", name = "enabled", havingValue = "true")
public class RagShadowConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "rag.shadow", name = "enabled", havingValue = "true")
    public RagShadowCollector ragShadowCollector(
            RagShadowMetrics metrics,
            @Value("${rag.shadow.sample-rate:1.0}") double sampleRate,
            @Value("${rag.shadow.block-on-violation:false}") boolean blockOnViolation) {
        RagShadowSamplingPolicy sampling =
                sampleRate >= 1.0 ? null : new RagShadowSampleRatePolicy(sampleRate);
        RagShadowReporter reporter = new RagShadowReporter(metrics, blockOnViolation, sampling);
        RagShadowCollector collector = new RagShadowCollector(metrics, reporter);
        collector.attach();
        return collector;
    }

    @Bean
    public RagShadowMetrics ragShadowMetrics() {
        return new InMemoryRagShadowMetrics();
    }
}
