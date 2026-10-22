package cn.studykid.multiagent.infrastructure.messaging;

import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.ports.messaging.MessageSender;
import cn.studykid.multiagent.ports.persistence.OutboxMessage;

/**
 * 按租户路由的消息发送（P4-D17 · 支持「小客群灰度切换」）。
 *
 * <p><b>为什么需要它</b>：若通道只能<b>全局</b>切换，
 * 试运营阶段就只能「整个部署一起换」，没有「先让几个租户试」的选项——
 * 而「小客群阶段」恰恰是风险最高、最需要灰度的阶段。
 * 故本类按 {@code tenantId} 选择实际通道：
 * <ul>
 *   <li>命中租户覆盖 → 用该租户指定的通道；</li>
 *   <li>未命中 → 用全局默认通道。</li>
 * </ul>
 *
 * <p><b>⚠️ 未登记租户不会「静默走默认」</b>：
 * 调用方（{@code OutboxPublisher}）已被约束为「只发布自己绑定租户的事件」，
 * 且本实例的发布器清单来自 {@code agent.worker.tenant-slugs}（显式清单、启动期校验）。
 * 即：能走到这里的租户必然是本实例服务的租户 ✅。
 * 若将来引入「按请求动态选通道」，<b>必须</b>在此加显式校验——
 * 否则一个未登记租户会被悄悄送到全局通道（越权投递）。
 *
 * <p><b>⚠️ 切换时的滞留问题不在本类解决</b>：本类只负责「把消息送到哪条通道」。
 * 「切换前必须排空旧通道」由装配期的残留检查强制（见
 * {@code MessagingChannelConfig}）——把「静默滞留」变成「启动即报错」。
 */
public class TenantRoutingMessageSender implements MessageSender {

    /** 全局默认通道（未被租户覆盖时使用）。 */
    private final MessageSender defaultSender;

    /** 租户覆盖：tenantId → 该租户的通道。 */
    private final Map<String, MessageSender> byTenant;

    public TenantRoutingMessageSender(@NonNull MessageSender defaultSender,
                                      @NonNull Map<String, MessageSender> byTenant) {
        this.defaultSender = defaultSender;
        this.byTenant = Map.copyOf(byTenant);
    }

    @Override
    public void send(@NonNull String eventType, @NonNull OutboxMessage message) {
        MessageSender target = byTenant.get(message.tenantId());
        if (target == null) {
            target = defaultSender;
        }
        target.send(eventType, message);
    }

    /** @return 走租户覆盖的租户数（可观测性：灰度了几户） */
    public int overriddenTenantCount() {
        return byTenant.size();
    }

    /**
     * @return 全局默认通道（未被覆盖的租户走它）
     */
    public @NonNull MessageSender defaultSender() {
        return defaultSender;
    }

    /**
     * @param tenantId 租户
     * @return 该租户实际使用的通道（覆盖优先）
     */
    public @NonNull MessageSender senderFor(@NonNull String tenantId) {
        MessageSender sender = byTenant.get(tenantId);
        return sender != null ? sender : defaultSender;
    }

    /** @return 被覆盖的租户 ID 清单（可观测性） */
    public @NonNull Set<String> overriddenTenants() {
        return byTenant.keySet();
    }
}
