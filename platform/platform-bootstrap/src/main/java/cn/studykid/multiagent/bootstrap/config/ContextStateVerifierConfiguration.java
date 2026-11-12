package cn.studykid.multiagent.bootstrap.config;

import java.time.Clock;

import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import cn.studykid.multiagent.ports.agentchat.ContextStateVerifier;

/**
 * S3-M8-a 请求侧压缩态校验的<b>唯一装配片</b>（ADR-013 D49 / G1 A.5 台账）。
 *
 * <p>⚠️ 与 M7 的 {@link ContextRequestAssemblyConfiguration} 分离：M7 装配「请求变换器」，
 * 本类只装配「请求入口不可信状态校验器」，职责单一，守卫可按文件精确升级。
 *
 * <p>⚠️ 密钥来源唯一：{@link AgentContextProperties#getStateHmacKey()} 对应
 * {@code AGENT_CONTEXT_STATE_HMAC_KEY}，⛔ 绝不传 JWT 私钥进来；空白密钥不阻止启动
 * （影子期默认空），而是由适配器返回 {@code KEY_UNAVAILABLE}，客户端 state 全部静默丢弃。
 *
 * <p>⚠️ 策略指纹来源唯一：本类<b>不</b>持有固定 {@code policyVersion}，而是要求调用方在
 * 已通过 {@code ContextPolicyResolver} 裁决后传入。启动期只校验与状态信封相关的
 * 全局配置形状，避免「配了 max-state-chars 却不生效」的静默失真。
 */
@Configuration
public class ContextStateVerifierConfiguration {

    /** 请求侧压缩态校验端口：唯一 Bean，真实算法由组合根委托 {@code agent-context}。 */
    @Bean
    @ConditionalOnMissingBean(ContextStateVerifier.class)
    public @NonNull ContextStateVerifier contextStateVerifier(
            @NonNull AgentContextProperties properties,
            @NonNull Clock clock) {
        if (properties.getMaxStateChars() <= 0) {
            throw new IllegalArgumentException(
                    "agent.context.max-state-chars 必须为正（否则所有 contextState 都会被丢弃）");
        }
        if (properties.getCheckpointTtlSeconds() <= 0) {
            throw new IllegalArgumentException(
                    "agent.context TTL 配置必须为正（checkpoint-ttl-seconds 不得 <=0，否则无法签发有界 TTL 状态）");
        }
        return new ContextStateVerifierAdapter(
                properties.getStateHmacKey(), properties.getMaxStateChars(), clock);
    }
}
