package cn.studykid.multiagent.ports.messaging;

import org.jspecify.annotations.NonNull;

/**
 * 队列深度探测端口（P4-D05 / 背压护栏）。
 *
 * <p><b>为什么需要</b>：D03 的 Worker 只有「消费者线程数」这一个上限，
 * 它限制的是<b>本机同时在跑几个 Run</b>，而不是<b>队列里堆了多少</b>。
 * 一旦生产快于消费（模型服务变慢、批量导入作业），
 * 队列会无限增长：内存/磁盘被吃光、重启恢复时间不可控，
 * 而系统<b>无任何异常信号</b>（L-6）。
 *
 * <p><b>⚠️ 契约：探测失败必须抛异常，不得返回 0 或 -1</b>。
 * 「返回 0」会让调用方以为队列是空的从而继续全速发布——
 * 即<b>背压在最需要它的时候失效</b>。这与 D8「限流组件故障反成 DoS 入口」
 * 是同一类陷阱。因此本接口没有「默认值」这一说，
 * 失败一律以异常上抛，由 {@code BackpressureController} 按 fail-closed 处理。
 *
 * <p><b>为何做成端口而非直接用 {@code RabbitTemplate}</b>：
 * 背压策略属于应用语义，必须在无 broker 的环境下可被单测
 * （注入一个返回固定深度的探测即可）。换 MQ 供应商时也只需换实现。
 */
@FunctionalInterface
public interface QueueDepthProbe {

    /**
     * 返回队列当前堆积的消息数（仅统计 ready 态）。
     *
     * @param queueName 队列名
     * @return 堆积条数（≥0）
     * @throws RuntimeException 探测失败（含队列不存在）——调用方须按 fail-closed 处理
     */
    int depthOf(@NonNull String queueName);
}
