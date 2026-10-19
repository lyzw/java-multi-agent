package cn.studykid.multiagent.worker.dispatch;

import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.NonNull;

/**
 * 租户发布器清单（P4-D05 / 关闭 D03 的 L-4）。
 *
 * <p><b>为何多租户不是「一个发布器扫全表」</b>：
 * 全租户轮询需要具备<b>跨租户读</b>权限的连接，
 * 而这样的连接一旦配置错（或实现里漏写 {@code WHERE tenant_id = ?}），
 * 就会把 A 租户的消息发到 B 租户的消费者手上——
 * 隔离在最弱的那一环被打破，且<b>不报错</b>。
 *
 * <p>本类的做法：每个租户一个独立的 {@link OutboxPublisher}，
 * 各自绑定自己的 {@code tenantId}：
 * <ul>
 *   <li>读取时 {@code WHERE tenant_id = <绑定值>}（RLS 之外再限一次）；</li>
 *   <li>发送前再断言消息归属（{@code OutboxPublisher} 内的第二道防线）。</li>
 * </ul>
 * 于是「配置错误」最坏的结果是<b>漏发或拒启</b>，
 * 而<b>不会</b>是跨租户发布——失败形态从「越权」降级为「不可用」，
 * 这是本片的核心取舍。
 *
 * <p><b>⚠️ 空清单即拒启</b>：空清单 = 消息永不发布，
 * 而系统表面完全正常（本项目已三次遇到该形态的断链）。
 */
public final class TenantOutboxPublishers {

    private final List<OutboxPublisher> publishers;

    public TenantOutboxPublishers(@NonNull List<OutboxPublisher> publishers) {
        if (publishers == null || publishers.isEmpty()) {
            throw new IllegalArgumentException(
                    "租户发布器清单不得为空（空清单 = 消息永不发布，而系统表面正常）");
        }
        if (publishers.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("租户发布器清单含 null 元素");
        }
        this.publishers = List.copyOf(publishers);
    }

    /** @return 不可变的发布器清单（顺序 = 配置中的租户顺序） */
    public @NonNull List<OutboxPublisher> publishers() {
        return publishers;
    }

    /** @return 本实例服务的租户数 */
    public int size() {
        return publishers.size();
    }
}
