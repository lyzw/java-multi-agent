package cn.studykid.multiagent.ports.messaging;

import java.util.List;
import java.util.Locale;

/**
 * 消息通道模式（P4-D17 / 2026-10-22）。
 *
 * <p><b>为什么需要它</b>：本项目原先把「消息走 RabbitMQ」写死在
 * {@code WorkerLoopConfig} 的装配里——交换机、quorum 队列、容器工厂、
 * {@code @RabbitListener} 端点全部无条件创建。结果：
 * <ul>
 *   <li>没有 broker 的环境（CI）启动即失败或被迫把整套 worker 关掉；</li>
 *   <li>「换一种异步载体」需要改代码、重新发版、重新验证一遍全链路；</li>
 *   <li>试运营阶段想「先小范围用新通道、稳了再全量」无路可走。</li>
 * </ul>
 *
 * <p><b>本枚举只回答一件事：消息走哪条通道</b>。
 * 两种模式共用<b>同一组端口</b>（{@link MessageSender} /
 * {@link MessageHandler} / {@link QueueDepthProbe}），
 * 故切换不触碰业务语义，也不触碰 Worker 侧代码。
 *
 * <p><b>⚠️ 解析一律 fail-closed</b>：缺失、空白、拼错都<b>拒绝启动</b>，
 * 绝不回落到某个「默认值」。理由与全项目一致——
 * 回落默认值等于「悄悄用了一个没人审过的通道」，
 * 而通道选错的症状是<b>消息静默不消费</b>（系统表面正常、Run 永远不动），
 * 属最难排查的失败形态。
 *
 * <p><b>⚠️ 通道是「可切换的」，但切换不是「随时热切」</b>：
 * 两种通道各自有自己的积压，切换前必须<b>先排空旧通道</b>，
 * 否则旧通道里的消息会滞留到无人消费。装配期会做<b>残留检查</b>并拒绝启动
 * （见 {@code MessagingChannelConfig}），把「静默滞留」变成「明确报错」。
 */
public enum MessagingMode {

    /** RabbitMQ：保留的既有通道（quorum 队列 + publisher confirms + 死信）。 */
    RABBITMQ("rabbitmq"),

    /** PostgreSQL 队列：以数据库表作异步队列（无外部 broker 依赖）。 */
    DATABASE("database");

    /** 配置键（小写 kebab-case）。 */
    private final String key;

    MessagingMode(String key) {
        this.key = key;
    }

    /** @return 配置键（如 {@code rabbitmq} / {@code database}） */
    public String key() {
        return key;
    }

    /** @return 全部合法配置键（供错误提示与自检使用） */
    public static List<String> keys() {
        return List.of(RABBITMQ.key, DATABASE.key);
    }

    /**
     * 解析配置值（fail-closed）。
     *
     * @param raw 配置原值（不得为 null）
     * @return 对应模式
     * @throws IllegalArgumentException 值为空或不在合法集合内
     */
    public static MessagingMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(
                    "消息通道模式必须显式声明（合法值: " + keys()
                            + "）。缺失即回落默认值 = 悄悄用了一个没人审过的通道，"
                            + "而通道选错的症状是消息静默不消费");
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (MessagingMode mode : values()) {
            if (mode.key.equals(normalized)) {
                return mode;
            }
        }
        throw new IllegalArgumentException(
                "消息通道模式非法: '" + raw + "'（合法值: " + keys() + "）");
    }
}
