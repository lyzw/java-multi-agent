package cn.studykid.multiagent.infrastructure.mcp;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.jspecify.annotations.NonNull;

/**
 * 按 MCP Server 维度的熔断器（§6.5「超时/限流/熔断」）。
 *
 * <p>⚠️ <b>熔断必须按 server 维度</b>，不能全局：
 * 一个坏掉的 MCP Server 不应让其它 server 全部不可用。
 *
 * <p>⚠️ <b>熔断打开时必须快速失败</b>：
 * 熔断的意义就是「已知坏了就别再试」——
 * 每次调用都去等一次超时，是把熔断做成了限流。
 */
public class McpCircuitBreaker {

    /** 熔断状态。 */
    public enum State {
        /** 正常放行。 */
        CLOSED,
        /** 已熔断，快速失败。 */
        OPEN,
        /** 半开，放行一次探测。 */
        HALF_OPEN
    }

    private final int failureThreshold;
    private final Duration openDuration;
    private final Map<String, AtomicInteger> failures = new ConcurrentHashMap<>();
    private final Map<String, Instant> openedAt = new ConcurrentHashMap<>();

    public McpCircuitBreaker(int failureThreshold, @NonNull Duration openDuration) {
        if (failureThreshold <= 0) {
            throw new IllegalArgumentException("failureThreshold 必须为正数");
        }
        if (openDuration == null || openDuration.isNegative()) {
            throw new IllegalArgumentException("openDuration 不得为负数");
        }
        this.failureThreshold = failureThreshold;
        this.openDuration = openDuration;
    }

    /**
     * 判定当前是否允许调用。
     *
     * @param serverId MCP Server 标识
     * @return 是否允许
     */
    public boolean allowRequest(@NonNull String serverId) {
        Instant opened = openedAt.get(serverId);
        if (opened == null) {
            return true;
        }
        if (Instant.now().isBefore(opened.plus(openDuration))) {
            return false;
        }
        // 熔断窗口已过 → 半开：放行一次探测
        openedAt.remove(serverId);
        failures.remove(serverId);
        return true;
    }

    /**
     * @param serverId MCP Server 标识
     * @return 当前状态
     */
    public @NonNull State state(@NonNull String serverId) {
        Instant opened = openedAt.get(serverId);
        if (opened == null) {
            return State.CLOSED;
        }
        return Instant.now().isBefore(opened.plus(openDuration)) ? State.OPEN : State.HALF_OPEN;
    }

    /**
     * 记录一次失败。
     *
     * @param serverId MCP Server 标识
     */
    public void recordFailure(@NonNull String serverId) {
        int count = failures.computeIfAbsent(serverId, k -> new AtomicInteger()).incrementAndGet();
        if (count >= failureThreshold) {
            openedAt.put(serverId, Instant.now());
        }
    }

    /**
     * 记录一次成功（清零失败计数）。
     *
     * @param serverId MCP Server 标识
     */
    public void recordSuccess(@NonNull String serverId) {
        failures.remove(serverId);
        openedAt.remove(serverId);
    }
}
