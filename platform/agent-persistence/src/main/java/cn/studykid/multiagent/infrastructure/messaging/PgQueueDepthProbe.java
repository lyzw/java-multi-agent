package cn.studykid.multiagent.infrastructure.messaging;

import java.util.List;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.ports.messaging.QueueDepthProbe;

/**
 * PostgreSQL 队列深度探测（P4-D17）。
 *
 * <p><b>⚠️ 契约：探测失败必须抛异常，不得返回 0 或 -1</b>。
 * 「返回 0」会让调用方以为队列是空的从而继续全速发布——
 * 即<b>背压在最需要它的时候失效</b>（与 D8「限流组件故障反成 DoS 入口」同族）。
 * 故本实现失败一律上抛，由 {@code BackpressureController} 按 fail-closed 处理。
 *
 * <p><b>⚠️ 深度口径</b>：本实现按<b>租户逐个</b>统计并求和——
 * 与 Rabbit 的「队列 ready 深度」在<b>总量口径</b>上一致，
 * 使同一套水位配置（high/low-watermark）在两种通道下含义相同。
 * 逐租户统计而非「一条语句扫全表」是刻意的：
 * 表上启用了 RLS，未绑定租户上下文时**只能看到 0 行**——
 * 一次全表统计会恒为 0，背压随即失效（且没有任何报错）。
 */
public class PgQueueDepthProbe implements QueueDepthProbe {

    private static final Logger log = LoggerFactory.getLogger(PgQueueDepthProbe.class);

    /** 本实例负责的租户（逐个绑定上下文统计）。 */
    private final List<String> tenantIds;

    private final PgQueueRepository repository;

    public PgQueueDepthProbe(@NonNull PgQueueRepository repository,
                              @NonNull List<String> tenantIds) {
        if (tenantIds == null || tenantIds.isEmpty()) {
            // ⚠️ 空租户清单 = 深度恒为 0 = 背压永久失效（且不报错）。
            //    与 OutboxRelay「空发布器清单即拒绝构造」同一处置。
            throw new IllegalArgumentException(
                    "PG 队列深度探测必须绑定至少一个租户（空清单 = 深度恒 0 = 背压失效）");
        }
        this.repository = repository;
        this.tenantIds = List.copyOf(tenantIds);
    }

    /**
     * @param queueName 队列名（为兼容端口签名保留；DB 通道按租户分队列，
     *                  深度为各租户之和）
     * @return 待处理条数（各租户之和）
     * @throws RuntimeException 探测失败——调用方须按 fail-closed 处理
     */
    @Override
    public int depthOf(@NonNull String queueName) {
        int total = 0;
        for (String tenantId : tenantIds) {
            // ⚠️ 异常一律上抛（不得 catch 成 0）：见类注释。
            total += repository.pendingDepth(tenantId);
        }
        if (log.isDebugEnabled()) {
            log.debug("PG 队列深度: queue={}, tenants={}, depth={}",
                    queueName, tenantIds.size(), total);
        }
        return total;
    }
}
