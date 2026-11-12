package cn.studykid.multiagent.bootstrap.config;

import java.time.Clock;

import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import cn.studykid.multiagent.ports.agentchat.ContextCheckpointEmitter;

/**
 * S3-M8-b-2 L2 轮末压缩态发射端口的<b>唯一装配片</b>（ADR-013 D49 / G1 A.5 台账）。
 *
 * <p>⚠️ 与接收侧 {@link ContextStateVerifierConfiguration} <b>共用同一份
 * {@link AgentContextProperties}</b>（独立 HMAC 密钥 / max-state-chars / TTL）与同一个
 * {@link Clock} Bean：签发与校验必须用同一把密钥、同一长度口径、同一时钟基准，
 * 否则「发出的 checkpoint 下一轮被自己静默丢弃」会变成无报错的功能空转。
 *
 * <p>⚠️ 单独成片而不是塞进 verifier 配置：升级守卫时能精确指向本文件与令牌，
 * 不把「只收不发」的 M8-a 装配面误算进「轮末发射」的 M8-b-2 面（G1 报告 A.5）。
 *
 * <p>⚠️ 配置非法（max-state-chars / TTL 非正）在 verifier 配置里已启动期 fail-fast；
 * 本类对 constructor 再校验一次，避免测试切片单独装配时绕过门禁。
 */
@Configuration
public class ContextCheckpointEmitterConfiguration {

    /** 唯一的轮末压缩态封包端口：真实算法由组合根委托 {@code agent-context}。 */
    @Bean
    @ConditionalOnMissingBean(ContextCheckpointEmitter.class)
    public @NonNull ContextCheckpointEmitter contextCheckpointEmitter(
            @NonNull AgentContextProperties properties,
            @NonNull Clock clock) {
        if (properties.getMaxStateChars() <= 0 || properties.getCheckpointTtlSeconds() <= 0) {
            throw new IllegalArgumentException(
                    "agent.context max-state-chars / checkpoint-ttl-seconds 必须为正"
                            + "（否则无法签发有界 TTL 状态）");
        }
        return new ContextCheckpointEmitterAdapter(
                properties.getStateHmacKey(),
                properties.getMaxStateChars(),
                properties.getCheckpointTtlSeconds(),
                clock);
    }
}
