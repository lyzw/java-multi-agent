package cn.studykid.multiagent.infrastructure.messaging;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * 基于 PG {@code LISTEN/NOTIFY} 的事件订阅（P4-D02 / D-1）。
 *
 * <p><b>⚠️ 核心约束：LISTEN 需要独占连接</b>——
 * 连接进入监听态后不能再执行其他 SQL，因此<b>不能</b>从连接池取。
 * 本类为订阅者**单独开一条连接**，并在关闭时显式释放。
 *
 * <p><b>⚠️ 通知只带「坐标」不带载荷</b>（D-2）：
 * 收到 {@code (tenantId, runId, sequenceNo)} 后<b>回查 {@code run_event} 表</b>。
 * 这样通知载荷恒定极小（不超 8000 字节限制），
 * 且**事件内容不经过通知通道**——
 * 否则会绕过 D10 建立的 SSE 载荷白名单，白名单形同虚设。
 *
 * <p><b>⚠️ 通知无持久化</b>（L-2）：订阅者未监听时通知静默丢弃。
 * 故 SSE 推送采用<b>订阅 + 轮询双轨</b>（D-3）：
 * 订阅负责低延迟，轮询负责不丢事件。二者职责不同，不可互相替代。
 */
public class RunEventNotifier implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RunEventNotifier.class);

    /** 通知频道名（须与写入侧 {@code pg_notify('run_event', ...)} 一致）。 */
    public static final String CHANNEL = "run_event";

    private final String jdbcUrl;
    private final String user;
    private final String password;

    /** 已建立的监听连接（独占，不走池）。 */
    private final Set<Connection> listeners = ConcurrentHashMap.newKeySet();

    /**
     * 从<b>环境变量</b>读取连接参数。
     *
     * <p>⚠️ <b>为何不探测池中连接</b>：JDBC 的 {@code getPassword()} 多数驱动不暴露，
     * 探测必然失败；且 LISTEN 连接必须是<b>独占</b>的，不能从池里借。
     * 故直接读集成测试与生产共用的环境变量（{@code IT_PG_URL} / {@code PG_URL} 等）。
     *
     * <p>⚠️ <b>取不到不是致命错误</b>：订阅不可用时退化为纯轮询（D-3），
     * 正确性不受影响，仅延迟变高。
     */
    public RunEventNotifier() {
        this.jdbcUrl = firstNonBlank(System.getenv("IT_PG_URL"),
                System.getenv("PG_URL"), System.getenv("SPRING_DATASOURCE_URL"));
        this.user = firstNonBlank(System.getenv("IT_PG_USER"),
                System.getenv("PG_USER"), System.getenv("SPRING_DATASOURCE_USERNAME"),
                "app_agent");
        this.password = firstNonBlank(System.getenv("IT_PG_PASSWORD"),
                System.getenv("PG_PASSWORD"),
                System.getenv("SPRING_DATASOURCE_PASSWORD"));
    }

    /** 显式给定连接参数（测试与自定义部署）。 */
    public RunEventNotifier(@Nullable String jdbcUrl, @Nullable String user,
                            @Nullable String password) {
        this.jdbcUrl = jdbcUrl;
        this.user = user;
        this.password = password;
    }

    private static @Nullable String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    /**
     * 注册一个事件订阅（返回的 {@link Connection} 需调用方在结束时释放）。
     *
     * <p>⚠️ 本方法为<b>测试与内部复用</b>提供；
     * 生产使用 {@link #subscribe(String, String, long, Consumer)}。
     *
     * @return 可用于接收通知的独占连接；不可用时返回 {@code null}（退化为纯轮询）
     */
    public @Nullable Connection openListener() {
        if (jdbcUrl == null) {
            return null;
        }
        try {
            Connection conn = DriverManager.getConnection(
                    jdbcUrl, user, password == null ? "" : password);
            conn.setAutoCommit(true);
            try (Statement st = conn.createStatement()) {
                st.execute("LISTEN " + CHANNEL);
            }
            listeners.add(conn);
            return conn;
        } catch (SQLException e) {
            // ⚠️ 订阅不可用**不是致命错误**：轮询兜底保证不丢事件（D-3）
            log.warn("⚠️ 无法建立 LISTEN 连接，退化为纯轮询（不影响正确性）: {}",
                    e.getMessage());
            return null;
        }
    }

    /**
     * 订阅指定 Run 的事件。
     *
     * @param tenantId  租户（RLS 绑定依据）
     * @param runId     Run
     * @param fromSequence 已消费到的序号；通知若 ≤ 此值则忽略
     * @param onEvent   收到「坐标」后的回调（回调方负责回查事件内容）
     * @param poller    兜底轮询（订阅丢失期间的补偿，D-3）
     */
    public void subscribe(@NonNull String tenantId,
                          @NonNull String runId,
                          long fromSequence,
                          @NonNull Consumer<Notification> onEvent,
                          @NonNull Runnable poller) {
        Connection conn = openListener();
        if (conn == null) {
            // ⚠️ 无订阅能力 → 只跑轮询
            poller.run();
            return;
        }
        Thread listener = new Thread(() -> {
            long lastSeen = fromSequence;
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    // ⚠️ PG 的通知靠 socket 可读事件唤醒；
                    //    这里用短超时轮询 getNotifications()，实现简单且可靠。
                    // ⚠️ 必须走 PGConnection：JDBC Connection 无通知 API。
                    //    PG 会把通知累积在本连接上，getNotifications 取走并清空。
                    org.postgresql.PGConnection pg = conn.unwrap(org.postgresql.PGConnection.class);
                    var notifications = pg.getNotifications(500);
                    if (notifications != null) {
                        for (var n : notifications) {
                            Notification parsed = parse(n.getParameter());
                            if (parsed == null) {
                                continue;
                            }
                            if (!tenantId.equals(parsed.tenantId())
                                    || !runId.equals(parsed.runId())
                                    || parsed.sequenceNo() <= lastSeen) {
                                continue;
                            }
                            lastSeen = parsed.sequenceNo();
                            onEvent.accept(parsed);
                        }
                    }
                    // ⚠️ 无论有无通知都跑兜底轮询 —— 补齐订阅丢失期间的事件（D-3）
                    poller.run();
                }
            } catch (SQLException e) {
                log.debug("LISTEN 连接结束: runId={}, err={}", runId, e.getMessage());
            } finally {
                closeQuietly(conn);
            }
        }, "run-event-listener-" + runId);
        listener.setDaemon(true);
        listener.start();
    }

    /**
     * 解析通知载荷（JSON 坐标）。
     *
     * <p>⚠️ <b>刻意不引 JSON 库</b>：载荷结构固定且极简，
     * 用最小解析避免引入依赖；解析失败返回 {@code null} 并忽略（不抛）。
     */
    public static @Nullable Notification parse(@Nullable String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            String tenantId = extract(payload, "tenantId");
            String runId = extract(payload, "runId");
            String seq = extract(payload, "sequenceNo");
            if (tenantId == null || runId == null || seq == null) {
                return null;
            }
            return new Notification(tenantId, runId, Long.parseLong(seq));
        } catch (RuntimeException e) {
            log.debug("通知载荷解析失败（忽略）: {}", payload);
            return null;
        }
    }

    /**
     * 提取字符串字段。
     *
     * <p>⚠️ <b>必须容忍冒号两侧的空白</b>：PG 的 {@code json_build_object} 输出形如
     * {@code {"runId" : "xxx"}}（冒号<b>两侧有空格</b>），
     * 而手写 JSON 常是 {@code {"runId":"xxx"}}（无空格）。
     * 首次实现只匹配后者，导致<b>通知正常到达却解析为 null</b>——
     * 这类缺陷不会报错，只会让「真推送」静默失效。
     */
    private static @Nullable String extract(String json, String key) {
        String needle = "\"" + key + "\"";
        int i = json.indexOf(needle);
        if (i < 0) {
            return null;
        }
        // 跳过 key 后的引号、任意空白、冒号、任意空白、起始引号
        int p = i + needle.length();
        while (p < json.length() && Character.isWhitespace(json.charAt(p))) {
            p++;
        }
        if (p >= json.length() || json.charAt(p) != ':') {
            return null;
        }
        p++;
        while (p < json.length() && Character.isWhitespace(json.charAt(p))) {
            p++;
        }
        if (p >= json.length() || json.charAt(p) != '"') {
            return null;
        }
        int start = p + 1;
        int end = json.indexOf('"', start);
        return end < 0 ? null : json.substring(start, end);
    }

    private void closeQuietly(Connection conn) {
        listeners.remove(conn);
        try {
            conn.close();
        } catch (SQLException ignored) {
            // 连接即将被 GC
        }
    }

    @Override
    public void close() {
        for (Connection c : List.copyOf(listeners)) {
            closeQuietly(c);
        }
    }

    /**
     * 事件通知（仅坐标）。
     *
     * @param tenantId租户
     * @param runId    Run
     * @param sequenceNo 事件序号（订阅方据此判断是否需要回查）
     */
    public record Notification(String tenantId, String runId, long sequenceNo) {
    }
}
