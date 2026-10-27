package cn.studykid.rag.application.observability;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;

/**
 * 日志上下文（SLF4J MDC）—— <b>traceId / 租户 / 登录主体</b>三要素的统一写入点。
 *
 * <p><b>为什么与主平台各留一份而不是共享</b>：rag 是独立 reactor，
 * 与 platform 之间只有 HTTP 契约、没有代码依赖。两份实现刻意保持
 * <b>同名键、同净化规则、同占位值</b>，这样跨进程日志能用同一个 traceId 整词检索串起来；
 * 改这边时记得同步改那边（键名一旦分叉，串联就静默失效，不会报错）。
 *
 * <p><b>MDC 是 ThreadLocal</b>，散写 {@code MDC.put} 的三种典型事故——
 * 键名拼错、忘 {@code clear()} 导致线程池<b>串号</b>（上个请求的租户出现在
 * 下个请求的日志里）、异步边界静默丢上下文——由本类一次性收口。
 *
 * <p><b>三个键始终有值</b>：未知时写 {@link #NONE}，不留空。
 * 留空会让「没有上下文」与「上下文是空串」在日志里长得一样，
 * 而后者常意味着上游把空租户塞进了链路。
 *
 * <p><b>⚠️ 值一律净化</b>：RAG 侧的租户标识来自 {@code X-Tenant-Id} 请求头，
 * 属于外部可控输入；未净化就进 MDC 等于开放<b>日志注入</b>（CR/LF 伪造日志行）。
 */
public final class LogContext {

    /** MDC 键：一次调用链的标识。 */
    public static final String TRACE_ID = "traceId";

    /** MDC 键：当前租户 id。 */
    public static final String TENANT_ID = "tenantId";

    /** MDC 键：当前登录主体 id。 */
    public static final String PRINCIPAL_ID = "principalId";

    /** 无上下文时的占位值，保证日志字段始终可读。 */
    public static final String NONE = "-";

    /**
     * 系统作业的主体标识（索引 Worker、清理任务等无登录态的线程）。
     *
     * <p>与主平台侧同名同值：{@code -} 表示「未知」，
     * {@code system} 表示「确定没有人类主体」，两者不能混用。
     */
    public static final String SYSTEM = "system";

    /** 当前 traceId；未设置返回 {@code null}。 */
    public static @Nullable String traceId() {
        return MDC.get(TRACE_ID);
    }

    /**
     * 保证当前线程有一个有效 traceId 并返回它（无则生成并写入 MDC）。
     *
     * <p>用于「本线程就是链路起点，且要把 id 交给外部载体（回调、子任务参数）」的场合：
     * 生成后立刻写回 MDC，本线程随后的日志与交出去的才是同一个 id。
     */
    public static String ensureTraceId() {
        setTraceId(null);
        String current = MDC.get(TRACE_ID);
        return current == null ? NONE : current;
    }

    /** 净化后允许保留的最大长度。 */
    private static final int MAX_VALUE_LENGTH = 64;

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private LogContext() {
    }

    /** 生成新的 traceId（32 位小写十六进制，与主平台同形态）。 */
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

    /** 只清租户与主体，保留 traceId。 */
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
     * 切换上下文并返回可关闭作用域（try-with-resources）；
     * {@code close()} 恢复进入前的快照，而不是清空——
     * 嵌套场景退出后外层上下文必须还在。
     */
    public static Scope push(Snapshot next) {
        Scope scope = new Scope(snapshot());
        next.apply();
        return scope;
    }

    /** 包装异步任务：提交线程的上下文在执行线程内生效，执行结束还原。 */
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
     * 净化单个上下文值：白名单 {@code [A-Za-z0-9._:@-]}，超长按
     * {@value #MAX_VALUE_LENGTH} 截断，空或全非法 → {@link #NONE}。
     *
     * <p>用白名单是因为要挡的形态（CR/LF、制表符、ANSI 转义）太多，黑名单迟早漏一个。
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

        /** 应用本快照（缺省补 {@link LogContext#NONE}）。 */
        public void apply() {
            MDC.put(TRACE_ID, traceId == null ? NONE : traceId);
            MDC.put(TENANT_ID, tenantId == null ? NONE : tenantId);
            MDC.put(PRINCIPAL_ID, principalId == null ? NONE : principalId);
        }

        private Snapshot applyAndReturnPrevious() {
            Snapshot previous = snapshot();
            apply();
            return previous;
        }

        /** 有序视图，便于断言与调试。 */
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
