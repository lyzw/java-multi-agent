package cn.studykid.multiagent.infrastructure.messaging;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.ports.messaging.QueueDepthProbe;

/**
 * 组合深度探测（P4-D17 · 通道可切换时的背压输入）。
 *
 * <p><b>为什么需要它</b>：切换期间（或按租户灰度期间），
 * <b>两条通道可能同时有在途消息</b>。若只探测其中一条，
 * 另一条会「无限堆积而无人看见」——背压护栏（P4-D05 / L-6）形同虚设。
 * 故本类把<b>所有处于活动状态的通道</b>的深度求和。
 *
 * <p><b>⚠️ 只纳入「活动通道」</b>：
 * 若部署完全跑在 DB 通道，就**不得**把 Rabbit 探测纳入——
 * 因为纯 DB 部署（CI 即如此）根本没有 broker，
 * 而 Rabbit 探测在队列不存在/不可达时<b>按契约必须抛异常</b>。
 * 把它纳入组合 = CI 的背压恒为「超限」= 消息永远不发布。
 *
 * <p><b>⚠️ 任一探测失败即整体失败</b>（不吞异常、不回落 0）：
 * 探测不到深度却放行 = 背压在最需要它的时候失效。
 */
public class CompositeQueueDepthProbe implements QueueDepthProbe {

    private static final Logger log = LoggerFactory.getLogger(CompositeQueueDepthProbe.class);

    private final List<QueueDepthProbe> probes;

    public CompositeQueueDepthProbe(@NonNull List<QueueDepthProbe> probes) {
        if (probes == null || probes.isEmpty()) {
            // ⚠️ 空组合 = 深度恒 0 = 背压永久失效且无报错。
            //    与 PgQueueDepthProbe「空租户清单即拒绝构造」同一处置。
            throw new IllegalArgumentException(
                    "组合深度探测必须至少包含一个活动通道（空组合 = 深度恒 0 = 背压失效）");
        }
        this.probes = List.copyOf(probes);
    }

    @Override
    public int depthOf(@NonNull String queueName) {
        int total = 0;
        for (QueueDepthProbe probe : probes) {
            // ⚠️ 异常一律上抛（不得 catch 成 0）：见类注释。
            total += probe.depthOf(queueName);
        }
        if (log.isDebugEnabled()) {
            log.debug("组合队列深度: queue={}, probes={}, depth={}",
                    queueName, probes.size(), total);
        }
        return total;
    }

    /** @return 参与统计的活动通道数（可观测性） */
    public int activeProbeCount() {
        return probes.size();
    }

    /** @return 活动通道列表（供装配期打印，避免「配置了却没生效」的错觉） */
    public List<QueueDepthProbe> probes() {
        return new ArrayList<>(probes);
    }
}
