package cn.studykid.multiagent.ports.messaging;

/**
 * 指标采集事件类型（P0 · G2）。
 *
 * <p><b>为什么必须是一个共享常量</b>：发送侧（{@code RunExecutionLoop} 在 Run 终态发布）
 * 与消费侧（{@code TraceCollectMessageHandler} 按类型分流）必须用<b>同一个字符串</b>。
 * 两侧各写一份字面量，一旦漂移：
 * <ul>
 *   <li>DB 通道：消费端按 eventType 分流，对不上 → 落到执行消费者 → 进死信，
 *       采集静默失效；</li>
 *   <li>Rabbit 通道：路由键与队列名同源配置，对不上 → 消息<b>被 broker 退回</b>
 *       （{@code mandatory=true} 下会打 ERROR 日志），采集同样失效。</li>
 * </ul>
 * 两种失效都<b>不报错到用户可见处</b>——看板只是空白，
 * 与「G2 缺口未闭合」的症状完全一致，极难区分。故此处单点定义。
 */
public final class TraceCollectEvent {

    /** 采集事件类型（同时用作 Rabbit 路由键与 PG 队列行的事件类型）。 */
    public static final String TYPE = "agent.trace.collect";

    private TraceCollectEvent() {
    }
}
