package cn.studykid.multiagent.bootstrap.config;

import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import cn.studykid.multiagent.context.GlobalContextPolicy;
import cn.studykid.multiagent.ports.agentchat.RagHitQuotaPort;
import cn.studykid.multiagent.ports.context.TokenCounter;

/**
 * S3-M10 L2 SSE RAG 配额端口的<b>唯一装配片</b>（ADR-013 D51 / G1 A.5 台账）。
 *
 * <p>⚠️ 与 M7 统一装配、M8-b 轮末封包同构：本类只负责「yml → 配额参数 → 中性端口」，
 * ⛔ 不把算法层 {@code RagHitQuota} / {@code Options} 泄漏给 worker；算法层只在
 * {@link RagHitQuotaAdapter} 出现。单独成片而不是塞进 {@code AgentContextConfiguration}，
 * 是为了让 A.5 升级守卫能精确指向「M10 接线面」，不把影子期的配置投影混进来。
 *
 * <p>⚠️ <b>参数唯一真相是 yml / env</b>：四项配额全部来自 {@link AgentContextProperties.RagQuota}，
 * 不给 {@code Options.defaults()} 之类旁路。配错（非正 / min>max / 单块上限>总预算 / 超过硬顶）
 * 一律启动期 fail-fast，避免「改了 AGENT_CONTEXT_RAG_QUOTA_* 却没生效」的静默失真。
 *
 * <p>⚠️ 全局总开关只由 {@link GlobalContextPolicy} 持有；本类不重复 enabled 判定。
 * 关闭态由 {@link RagHitQuotaAdapter#applyQuota} 第一行短路，编排器走 legacy 逐位等价。
 */
@Configuration
public class RagHitQuotaConfiguration {

    /** 唯一的 RAG 配额中性端口：真实算法由组合根委托 {@code agent-context} 纯函数。 */
    @Bean
    @ConditionalOnMissingBean(RagHitQuotaPort.class)
    public @NonNull RagHitQuotaPort ragHitQuotaPort(
            @NonNull AgentContextProperties properties,
            @NonNull GlobalContextPolicy globalContextPolicy,
            @NonNull TokenCounter contextTokenCounter) {
        AgentContextProperties.RagQuota q = properties.getRagQuota();
        validate(q, properties);
        return new RagHitQuotaAdapter(
                globalContextPolicy,
                contextTokenCounter,
                q.getTokenBudget(),
                q.getMaxHits(),
                q.getMaxHitTokens(),
                q.getMinKeptTokens());
    }

    /** 启动期门禁：把「配了却不生效 / 预算永远放不进一块」变成显式失败。 */
    private static void validate(AgentContextProperties.RagQuota q,
                                 @NonNull AgentContextProperties properties) {
        require(q.getTokenBudget() > 0, "agent.context.rag-quota.token-budget 必须为正");
        require(q.getMaxHits() > 0, "agent.context.rag-quota.max-hits 必须为正");
        require(q.getMaxHitTokens() > 0, "agent.context.rag-quota.max-hit-tokens 必须为正");
        require(q.getMinKeptTokens() > 0
                        && q.getMinKeptTokens() <= q.getMaxHitTokens(),
                "agent.context.rag-quota.min-kept-tokens 必须在 (0, max-hit-tokens] 区间");
        require(q.getMaxHitTokens() <= q.getTokenBudget(),
                "agent.context.rag-quota.max-hit-tokens 不得大于 token-budget"
                        + "（否则任何命中都放不进，配额形同虚设）");
        require(q.getTokenBudget() <= properties.getHardCeilingTokens(),
                "agent.context.rag-quota.token-budget 不得大于 hard-ceiling-tokens"
                        + "（RAG 命中区是全窗口的一部分，不能反过来撑爆硬顶）");
    }

    private static void require(boolean ok, @NonNull String message) {
        if (!ok) {
            throw new IllegalArgumentException("RAG 配额配置非法：" + message);
        }
    }
}
