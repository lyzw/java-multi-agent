package cn.studykid.multiagent.bootstrap.config;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 消息通道健康检查装配。
 *
 * <p><b>⚠️ Bean 名必须是 {@code rabbitHealthIndicator}，不能随便起</b>：
 * Spring Boot 的 {@code RabbitHealthContributorAutoConfiguration} 上有
 * {@code @ConditionalOnMissingBean(name = {"rabbitHealthIndicator", "rabbitHealthContributor"})}，
 * 只有<b>同名</b>才能顶掉自动装配的那个。
 * 若改用别的名字，自动装配的 {@code RabbitHealthIndicator} 会与本 Bean <b>并存</b> →
 * 健康树里出现两个 rabbit 贡献者 → 仍然 DOWN，且<b>看不出是谁在报错</b>
 * （这正是本项目最恨的「看着在查其实没查成」形态）。
 *
 * <p>详见 {@link MessagingChannelHealthIndicator} 的类注释（为何必须替换、
 * 以及为什么「Rabbit 不在用」时应当<b>不参与</b>健康判定）。
 */
@Configuration
public class MessagingHealthConfig {

    /**
     * Rabbit 通道健康指示器（顶掉 Spring Boot 自动装配的那个）。
     *
     * @param channels 通道分配（worker 未启用时不存在 → 视为 Rabbit 不在用）
     * @param template Rabbit 发送器（缺失且 Rabbit 在用 → fail-closed 报 DOWN）
     * @return 覆盖自动装配的健康指示器
     */
    @Bean("rabbitHealthIndicator")
    public HealthIndicator rabbitHealthIndicator(
            ObjectProvider<MessagingChannels> channels,
            ObjectProvider<RabbitTemplate> template) {
        return new MessagingChannelHealthIndicator(channels, template);
    }
}
