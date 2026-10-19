package cn.studykid.multiagent.ports.persistence;

import org.jspecify.annotations.NonNull;

/**
 * 请求限流端口（P3-D08 / D-2 预算击穿）。
 *
 * <p><b>为什么预算约束不够</b>（本端口存在的理由）：
 * <blockquote>
 * D4 的 {@code budget_no_overspend} 管的是<b>总量</b>，限流管的是<b>速率</b>。
 * 攻击者以 1000 QPS 发起调用、每笔都远低于单次预留额时，
 * 预算约束<b>一次都不会触发</b>——但预算会在几分钟内被打穿。
 * 二者不可互相替代。
 * </blockquote>
 *
 * <p><b>⚠️ 必须用枚举而非 boolean</b>（与 D4 {@code ReserveOutcome} 同源）：
 * 「被限流拒绝」与「限流组件故障」在运维处置上<b>完全不同</b>——
 * 前者等窗口重置即可，后者要修组件。折叠成 false 会让排查方向全错。
 *
 * <p><b>⚠️ 失败必须 {@link Decision#FAIL_CLOSED}</b>：
 * 限流组件故障时放行 = 限流形同虚设。
 */
public interface RateLimitPort {

    /**
     * 限流桶键（按「动作」维度，避免单键全局限让一种动作挤占另一种）。
     */
    final class Buckets {

        private Buckets() {
        }

        /** 创建 Run。 */
        public static final String RUN_CREATE = "RUN_CREATE";

        /** 建立 SSE 长连接（P2-D05 §6 L7）。 */
        public static final String SSE_CONNECT = "SSE_CONNECT";

        /** 事件查询（SSE 续传轮询）。 */
        public static final String EVENT_QUERY = "EVENT_QUERY";
    }

    /**
     * 检查并占用一个令牌。
     *
     * <p><b>⚠️ 被拒时计数不退还</b>：已消耗的令牌不应退还，
     * 否则攻击者可用「持续发被拒请求」维持连接，限流退化为仅计数不设防。
     *
     * @param tenantId  租户（RLS 绑定依据）
     * @param bucketKey 桶键（见 {@link Buckets}）
     * @return {@link Decision#ALLOWED} 表示可继续
     */
    @NonNull Decision check(@NonNull String tenantId, @NonNull String bucketKey);

    /**
     * 当前窗口内的计数（可观测 / 运维排查）。
     *
     * @return 计数；无桶记录时为 0
     */
    long currentCount(@NonNull String tenantId, @NonNull String bucketKey);

    /** 限流判定。 */
    enum Decision {
        /** 允许（本次占用了一个令牌）。 */
        ALLOWED,
        /** 超过限额，拒绝。 */
        REJECTED,
        /** ⚠️ 限流组件故障：<b>fail-closed</b>，调用方须拒绝。 */
        FAIL_CLOSED
    }
}