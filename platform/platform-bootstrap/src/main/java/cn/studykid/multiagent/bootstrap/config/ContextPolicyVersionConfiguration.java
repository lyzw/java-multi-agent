package cn.studykid.multiagent.bootstrap.config;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import cn.studykid.multiagent.context.GlobalContextPolicy;
import cn.studykid.multiagent.ports.agentchat.ContextPolicyVersionPort;
import cn.studykid.multiagent.ports.modelcatalog.ModelCatalogPort;
import cn.studykid.multiagent.ports.persistence.CapAgentDefPort;

/**
 * S3-M8-a 策略指纹裁决端口的<b>唯一装配片</b>（ADR-013 D49 / G1 A.5）。
 *
 * <p>⚠️ 本类只把「与 M7 装配链路共用的裁决支持」接到中性端口上，⛔ 不持有任何
 * 写死的指纹，也不读发布快照列当第二真相（D17 / D48）。
 *
 * <p>⚠️ 与 {@link ContextRequestAssemblyConfiguration} 分开成片：升级守卫时能精确指向
 * 文件与令牌，不会把影子期配置投影误算进 M8 请求侧接线面。
 */
@Configuration
public class ContextPolicyVersionConfiguration {

    @Bean
    @ConditionalOnMissingBean(ContextPolicyVersionPort.class)
    public @NonNull ContextPolicyVersionPort contextPolicyVersionPort(
            @NonNull GlobalContextPolicy globalContextPolicy,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            @Nullable CapAgentDefPort capAgentDefPort,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            @Nullable ModelCatalogPort modelCatalogPort) {
        return new ContextPolicyVersionAdapter(
                globalContextPolicy, capAgentDefPort, modelCatalogPort);
    }
}
