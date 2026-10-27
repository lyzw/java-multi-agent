package cn.studykid.multiagent.application.observability;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;

/**
 * 日志上下文（SLF4J MDC）—— <b>traceId / 租户 / 登录主体</b>三要素的统一写入点。
 *
 * <p><b>为什么要专门收口一个类</b>：MDC 是 ThreadLocal，散在各处
 * {@code MDC.put("traceId", …)} 会产生三种失败形态——
 * 键名拼错（编译器不管）、忘记 {@code clear()}（线程池复用后<b>串号</b>：
 * 上一个请求的租户出现在下一个请求的日志里）、异步边界静默丢失
 * （子线程拿到空上下文，日志看起来一切正常，只是少了字段）。
 * 本类把这三个坑收成一次性正确的实现，调用方只表达「现在是谁在跑」。
 *
 * <p><b>与指标系统的分工（刻意不同）</b>：
 * {@link AgentMetrics} 在运行期<b>拒绝</b> trace_id / principal_id 之类的高基数标签，
 * 那是时序库的正确约束；<b>明细属于日志</b>。所以同一份标识，
 * 指标里没有、日志里必须有，两边不冲突，别把它们当同一个诉求。
 *
 * <p><b>三个键始终有值</b>：未知时写 {@link #NONE}（{@code -}）而不是留空。
 * 留空会让「没有上下文」和「上下文是空串」在日志里长得一样，
 * 而后者往往意味着上游把空租户塞进了链路——那是必须看得见的异常。
 *
 * <p><b>⚠️ 值一律净化</b>：traceId 可来自请求头，属于攻击者可控输入。
 * 未净化就进 MDC 等于开放<b>日志注入</b>（CR/LF 伪造日志行、ANSI 污染终端），
 * 见 {@link #sanitize(String)}。
 */
public final class LogContext {

    /** MDC 键：一次调用链的标识。 */
    public static final String TRACE_ID = "traceId";

    /** MDC 键：当前租户 id。 */
    public static final String TENANT_ID = "tenantId";

    /** MDC 键：当前登录主体 id（平台管理链为平台用户 id）。 */
    public static final String PRINCIPAL_ID = "principalId";

    /** 无上下文时的占位值，保证日志字段始终可读。 */
    public static final String NONE = "-";

    /**
     * 系统作业的主体标识（Worker 轮询、Outbox 中继、清理任务等无登录态的线程）。
     *
     * <p>用固定字符串而不是 {@code -}：{@code -} 已经表示「未知」，
     * 而系统作业是<b>确定没有人类主体</b>，两者混在一起就无法区分
     * 「设计如此」与「上下文丢了」。
     */
    public static final String SYSTEM = "system";

    /**
     * MQ 消息头名：跨进程传递 traceId。
     *
     * <p>⚠️ 刻意放在<b>消息头</b>而不是消息体：
     * {@code OutboxMessage.ALLOWED_KEYS} 白名单约束的是进入 broker 的业务载荷，
     * 载荷最小化（P0-D05 R-015）不应因为一个链路标识被放宽。
     * 传输元数据不进业务载荷、不参与持久化删除范围，也不会被消费端当业务字段读到。
     */
    public static final String TRACE_HEADER = "x-trace-id";

    /** 净化后允许保留的最大长度；超长直接截断，日志不需要完整回显一个畸形输入。 */
    private static final int MAX_VALUE_LENGTH = 64;

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private LogContext() {
    }

    /**
     * 生成新的 traceId（32 位小写十六进制，无连字符）。
     *
     * <p>不直接用 {@code UUID#toString()} 是为了让日志行短一截且便于整词检索；
     * 唯一性与 UUID 等价。
     */
    public static String newTraceId() {
        UUID uuid = UUID.randomUUID();
        char[] out = new char[32];
        writeHex(uuid.getMostSignificantBits(), out, 0);
        writeHex(uuid.getLeastSignificantBits(), out, 16);
        return new String(out);
    }

    private static void writeHex(long value, char[] out, int offset) {
        // 一个 long = 16 个十六进制位；写成 12 起会只填 13 位，
        // 剩下 3 位是未初始化的 \u0000 —— 日志里看着像空格，检索时永远匹配不上。
        for (int i = 15; i >= 0; i--) {
            out[offset + i] = HEX[(int) (value & 0xFL)];
            value >>>= 4;
        }
    }

    /** 当前 traceId；未设置返回 {@code null}。 */
    public static @Nullable String traceId() {
        return MDC.get(TRACE_ID);
    }

    /**
     * 保证当前线程有一个有效 traceId 并返回它（无则生成并写入 MDC）。
     *
     * <p>用于「要把链路号交给外部载体（消息头、回调参数），但自己就是链路起点」的场合：
     * 生成后立刻写回 MDC，本线程随后打的日志与交出去的值才是同一个 id。
     */
    public static String ensureTraceId() {
        setTraceId(null);
        String current = MDC.get(TRACE_ID);
        return current == null ? NONE : current;
    }

    /**
     * 写入 traceId（自动净化）。传 {@code null} 或非法值：当前有效则<b>保留原有</b>，
     * 否则<b>新生成一个</b>，绝不留空。
     */
    public static void setTraceId(@Nullable String traceId) {
        String value = sanitize(traceId);
        if (NONE.equals(value)) {
            String current = MDC.get(TRACE_ID);
            if (current != null && !NONE.equals(current)) {
                return;
            }
            MDC.put(TRACE_ID, newTraceId());
            return;
        }
        MDC.put(TRACE_ID, value);
    }

    /** 写入租户与主体身份（自动净化）。 */
    public static void setIdentity(@Nullable String tenantId, @Nullable String principalId) {
        MDC.put(TENANT_ID, sanitize(tenantId));
        MDC.put(PRINCIPAL_ID, sanitize(principalId));
    }

    /** 只清租户与主体，保留 traceId（用于「链路已知、身份未知」的阶段）。 */
    public static void clearIdentity() {
        setIdentity(null, null);
    }

    /** 清空全部三个键。<b>必须在 finally 里调用</b>。 */
    public static void clear() {
        MDC.remove(TRACE_ID);
        MDC.remove(TENANT_ID);
        MDC.remove(PRINCIPAL_ID);
    }

    /** 当前上下文快照（三个键恒有值，缺省为 {@link #NONE}）。 */
    public static Snapshot snapshot() {
        return new Snapshot(MDC.get(TRACE_ID), MDC.get(TENANT_ID), MDC.get(PRINCIPAL_ID));
    }

    /**
     * 切换上下文并返回可关闭的作用域，用于 try-with-resources；
     * {@code close()} 恢复进入前的快照。
     *
     * <p>之所以是「恢复」而不是「清空」：嵌套场景（如请求线程内再起一段
     * 系统作业日志上下文）退出后，外层上下文必须还在，否则后续日志反而丢字段。
     */
    public static Scope push(Snapshot next) {
        Scope scope = new Scope(snapshot());
        next.apply();
        return scope;
    }

    /**
     * 包装异步任务：提交线程的上下文在<b>执行线程</b>内生效，执行结束即还原。
     *
     * <p>MDC 不会自动跨线程传递，裸 {@code executor.submit(task)} 会让子线程日志
     * 变成「无主」——这正是异步链路排障时最迷惑的现象。
     */
    public static Runnable wrap(Runnable task) {
        Snapshot captured = snapshot();
        return () -> {
            Snapshot previous = captured.applyAndReturnPrevious();
            try {
                task.run();
            } finally {
                previous.apply();
            }
        };
    }

    /** {@link #wrap(Runnable)} 的 {@link Callable} 版本。 */
    public static <V> Callable<V> wrap(Callable<V> task) {
        Snapshot captured = snapshot();
        return () -> {
            Snapshot previous = captured.applyAndReturnPrevious();
            try {
                return task.call();
            } finally {
                previous.apply();
            }
        };
    }

    /**
     * 净化单个上下文值：只保留 {@code [A-Za-z0-9._:@-]}，其余丢弃，超长按
     * {@link #MAX_VALUE_LENGTH} 截断；空或全非法 → {@link #NONE}。
     *
     * <p>白名单而非黑名单：CR/LF、制表符、ANSI 转义、空格等形态太多，
     * 黑名单迟早漏一个。合法标识（UUID、hex、带点的账号）全在白名单里。
     */
    public static String sanitize(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return NONE;
        }
        StringBuilder sb = new StringBuilder(Math.min(raw.length(), MAX_VALUE_LENGTH));
        for (int i = 0; i < raw.length() && sb.length() < MAX_VALUE_LENGTH; i++) {
            char c = raw.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == ':' || c == '@' || c == '-') {
                sb.append(c);
            }
        }
        return sb.isEmpty() ? NONE : sb.toString();
    }

    /** 三个键的上下文快照。 */
    public record Snapshot(@Nullable String traceId, @Nullable String tenantId,
                           @Nullable String principalId) {

        /** 应用本快照（缺省值补 {@link LogContext#NONE}）。 */
        public void apply() {
            MDC.put(TRACE_ID, traceId == null ? NONE : traceId);
            MDC.put(TENANT_ID, tenantId == null ? NONE : tenantId);
            MDC.put(PRINCIPAL_ID, principalId == null ? NONE : principalId);
        }

        /** 应用本快照，并返回应用<b>前</b>的快照（供还原用）。 */
        private Snapshot applyAndReturnPrevious() {
            Snapshot previous = snapshot();
            apply();
            return previous;
        }

        /** 便于断言/调试的有序视图。 */
        public Map<String, String> asMap() {
            Map<String, String> map = new LinkedHashMap<>(3);
            map.put(TRACE_ID, traceId == null ? NONE : traceId);
            map.put(TENANT_ID, tenantId == null ? NONE : tenantId);
            map.put(PRINCIPAL_ID, principalId == null ? NONE : principalId);
            return map;
        }
    }

    /** {@link #push(Snapshot)} 返回的作用域：关闭时恢复进入前的上下文。 */
    public static final class Scope implements AutoCloseable {

        private final Snapshot previous;
        private boolean closed;

        private Scope(Snapshot previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                previous.apply();
            }
        }
    }
}
