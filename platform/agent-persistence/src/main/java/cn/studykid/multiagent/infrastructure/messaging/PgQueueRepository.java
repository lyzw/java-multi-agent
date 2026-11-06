package cn.studykid.multiagent.infrastructure.messaging;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TenantConnectionLease;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;

/**
 * PostgreSQL 异步队列仓储（P4-D17 · RabbitMQ 的可切换替代载体）。
 *
 * <p><b>⚠️ 为何是「数据库即队列」</b>：本项目本就<b>强制依赖 PostgreSQL</b>
 * （Run、审计、预算、租户上下文都在库里），把「消息队列」也放进同一个库，
 * 换来的是：无需额外 broker、消息与业务数据同一事务边界、
 * 多实例消费靠 {@code FOR UPDATE SKIP LOCKED} 天然互斥。
 * 代价是<b>延迟略高</b>（轮询间隔）与<b>吞吐上限</b>（受库写入能力约束）——
 * 对本项目的量级（单租户并发 Run ≤ 5，见 {@code agent.tenant.max-concurrent-runs}）
 * 完全够用，且这些代价可以通过调参缓解。
 *
 * <p><b>⚠️ 语义刻意与 Rabbit 通道对齐</b>（否则「切换通道」就等于「改变行为」）：
 * <ul>
 *   <li><b>at-least-once</b>：领取后未确认的��，进程崩溃即永久滞留 →
 *       故用 {@code claimed_at} + 可见性超时回收（等价 broker 的 unacked 重投）；</li>
 *   <li><b>有限重试</b>：{@code attempts} 达上限即进死信表（等价 {@code x-delivery-limit}）；</li>
 *   <li><b>死信只进不出</b>：须人工介入（等价 DLQ）。</li>
 * </ul>
 *
 * <p><b>⚠️ 全部走租户绑定连接</b>（{@code tenantConnection}）：
 * 表上启用了 RLS + FORCE，未绑定租户上下文时**可见 0 行**——
 * 这是「默认拒绝」，不是「查不到」。
 *
 * <p><b>⚠️ 领取用 {@code FOR UPDATE SKIP LOCKED}</b>：
 * 多实例并发领取时，已被别的事务锁住的行被<b>跳过</b>而非阻塞，
 * 既避免互相等待，也避免「两个实例拿到同一条消息」。
 */
public class PgQueueRepository {

    private static final Logger log = LoggerFactory.getLogger(PgQueueRepository.class);

    private final DataSource dataSource;

    private final ObjectMapper mapper;

    public PgQueueRepository(@NonNull DataSource dataSource, @NonNull ObjectMapper mapper) {
        this.dataSource = dataSource;
        this.mapper = mapper;
    }

    /**
     * 入队（**提交后**才算「已持久化」）。
     *
     * <p>⚠️ <b>契约对齐</b>：{@code MessageSender} 要求「确认后才返回」。
     * 本实现以「事务提交成功」为确认——提交失败即抛异常，
     * 调用方（OutboxPublisher）会保留 PENDING 状态并重试，
     * <b>不会</b>出现「消息没落库却标记已发布」的丢消息路径。
     */
    public void enqueue(@NonNull String tenantId,
                        @NonNull String eventType,
                        @NonNull String runId,
                        @NonNull Map<String, Object> payload) {
        String json = mapper.writeValueAsString(payload);
        String sql = """
                INSERT INTO agent_message_queue
                    (tenant_id, run_id, event_type, payload, state, attempts, next_attempt_at)
                VALUES (?::uuid, ?::uuid, ?, ?::jsonb, 'PENDING', 0, now())
                """;
        try (TenantConnectionLease lease = tenantConnection(tenantId)) {
            Connection conn = lease.connection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, tenantId);
                ps.setString(2, runId);
                ps.setString(3, eventType);
                ps.setString(4, json);
                ps.executeUpdate();
            }
            lease.commitQuietly();
        } catch (SQLException e) {
            throw new IllegalStateException("消息入队失败（tenantId=" + tenantId + "）", e);
        }
    }

    /**
     * 领取一批待处理消息（置为 INFLIGHT）。
     *
     * <p>⚠️ <b>回收已超时的 INFLIGHT 行</b>：上一任消费者进程崩溃后，
     * 其领取的行会永远停在 INFLIGHT —— 消息**不丢**，但**不再被消费**，
     * 而系统表面完全正常（与本项目已记录的「断链」形态同族）。
     * 故按 {@code claimed_at + 可见性超时} 回收。
     *
     * @param visibilityTimeout 可见性超时（超时即认为持有者已失联）
     * @return 本次领取到的消息（可能为空）
     */
    public List<QueuedMessage> claim(@NonNull String tenantId,
                                     int limit,
                                     @NonNull Duration visibilityTimeout,
                                     @NonNull String workerId) {
        if (limit <= 0) {
            return List.of();
        }
        // ⚠️⚠️ **回收的 INFLIGHT 行必须计 attempts**（P1-13 修复）：
        //    上一任消费者进程崩溃/卡死 → 行停在 INFLIGHT → 超时被回收重投。
        //    修复前回收不加 attempts → 毒消息（消费者永远卡死）
        //    可以无限重投，**永远到不会进死信**（maxAttempts 只在
        //    「处理者正常返回/抛异常」的重试路径上检查）。
        //    另外 SELECT 返回的 attempts 也必须是「加计后」的值，
        //    否则内存里的消费计数与库里的不一致 → 重试上限偏离。
        String selectSql = """
                SELECT queue_id, tenant_id, run_id, event_type, payload,
                       attempts + CASE WHEN state = 'INFLIGHT' THEN 1 ELSE 0 END AS attempts
                FROM agent_message_queue
                WHERE tenant_id = ?::uuid
                  AND (
                        (state = 'PENDING' AND next_attempt_at <= now())
                     OR (state = 'INFLIGHT' AND claimed_at < now() - (? || ' milliseconds')::interval)
                  )
                ORDER BY next_attempt_at, created_at
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """;
        String updateSql = """
                UPDATE agent_message_queue
                SET state = 'INFLIGHT',
                    claimed_by = ?,
                    claimed_at = now(),
                    attempts = attempts + CASE WHEN state = 'INFLIGHT' THEN 1 ELSE 0 END
                WHERE queue_id = ?::uuid AND tenant_id = ?::uuid
                """;

        List<QueuedMessage> claimed = new ArrayList<>();
        try (TenantConnectionLease lease = tenantConnection(tenantId)) {
            Connection conn = lease.connection();
            try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
                ps.setString(1, tenantId);
                ps.setString(2, Long.toString(visibilityTimeout.toMillis()));
                ps.setInt(3, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        claimed.add(new QueuedMessage(
                                rs.getObject("queue_id", UUID.class),
                                rs.getObject("tenant_id", UUID.class).toString(),
                                rs.getObject("run_id", UUID.class).toString(),
                                rs.getString("event_type"),
                                readPayload(rs.getString("payload")),
                                rs.getInt("attempts")));
                    }
                }
            }
            if (!claimed.isEmpty()) {
                try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                    for (QueuedMessage m : claimed) {
                        ps.setString(1, workerId);
                        ps.setObject(2, m.queueId());
                        ps.setString(3, tenantId);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }
            lease.commitQuietly();
        } catch (SQLException e) {
            throw new IllegalStateException("消息领取失败（tenantId=" + tenantId + "）", e);
        }
        return claimed;
    }

    /**
     * 确认消费完成（删除行）。
     *
     * <p>⚠️⚠️ <b>必须带 {@code claimed_by} 围栏</b>（P1-14 修复）：
     * 本行的持有者可能已经超出可见性超时，被另一个 worker 回收并取得了新的领取。
     * 若不围栏，旧 worker 的 ack 会<b>删掉新 worker 正在处理的那行</b> ——
     * 新 worker 的副作用可能已经发生，但消息行已消失（新 worker 随后崩溃 → 消息永不重投）。
     * 围栏后旧 worker 的 ack 变成幂等的「无效操作」，行由新 worker 负责收尾。
     *
     * @param tenantId 租户
     * @param queueId  队列行 id
     * @param workerId 当前 worker 标识（必须与领取时一致）
     */
    public void ack(@NonNull String tenantId, @NonNull UUID queueId, @NonNull String workerId) {
        String sql = """
                DELETE FROM agent_message_queue
                WHERE queue_id = ?::uuid AND tenant_id = ?::uuid AND claimed_by = ?
                """;
        try (TenantConnectionLease lease = tenantConnection(tenantId)) {
            try (PreparedStatement ps = lease.connection().prepareStatement(sql)) {
                ps.setObject(1, queueId);
                ps.setString(2, tenantId);
                ps.setString(3, workerId);
                int deleted = ps.executeUpdate();
                if (deleted == 0) {
                    // ⚠️ 本行已不在本 worker 手上（被其他 worker 回收了）→ 不做任何事，
                    //    由当前持有者收尾。这里不是错误，不需要抛异常。
                    log.debug("确认被围栏拒绝（行已被其他 worker 回收）: queueId={}, worker={}",
                            queueId, workerId);
                }
            }
            lease.commitQuietly();
        } catch (SQLException e) {
            throw new IllegalStateException("消息确认失败（queueId=" + queueId + "）", e);
        }
    }

    /**
     * 可重试失败：退回 PENDING 并退避。
     *
     * <p>⚠️ <b>必须清空领取字段</b>：{@code agent_message_claim_complete} 约束
     * 要求 PENDING 行的 {@code claimed_by}/{@code claimed_at} 为空。
     */
    public void retry(@NonNull String tenantId,
                      @NonNull UUID queueId,
                      @NonNull String error,
                      @NonNull Instant nextAttemptAt,
                      @NonNull String workerId) {
        // ⚠️⚠️ <b>必须带 {@code claimed_by} 围栏</b>（P1-14 修复）：
        //    无围栏时，旧 worker 的 retry 会把<b>新 worker 正在处理的行退回 PENDING</b> ——
        //    新 worker 还在处理，行却已可被再次领取 → <b>重复执行</b>（重复副作用 = Sev-0）。
        String sql = """
                UPDATE agent_message_queue
                SET state = 'PENDING',
                    attempts = attempts + 1,
                    next_attempt_at = ?,
                    last_error = ?,
                    claimed_by = NULL,
                    claimed_at = NULL
                WHERE queue_id = ?::uuid AND tenant_id = ?::uuid AND claimed_by = ?
                """;
        try (TenantConnectionLease lease = tenantConnection(tenantId)) {
            try (PreparedStatement ps = lease.connection().prepareStatement(sql)) {
                ps.setTimestamp(1, Timestamp.from(nextAttemptAt));
                ps.setString(2, error);
                ps.setObject(3, queueId);
                ps.setString(4, tenantId);
                ps.setString(5, workerId);
                int updated = ps.executeUpdate();
                if (updated == 0) {
                    log.debug("重试登记被围栏拒绝（行已被其他 worker 回收）: queueId={}, worker={}",
                            queueId, workerId);
                }
            }
            lease.commitQuietly();
        } catch (SQLException e) {
            throw new IllegalStateException("消息重试登记失败（queueId=" + queueId + "）", e);
        }
    }

    /**
     * 不可恢复：<b>原子地</b>移入死信表并删除队列行。
     *
     * <p>⚠️ 两步必须同一事务：否则会出现「已从队列删除但没进死信」
     * （消息凭空消失）或「已进死信但队列里还在」（被重复处理）。
     */
    public void reject(@NonNull String tenantId,
                       @NonNull UUID queueId,
                       @NonNull String eventType,
                       @NonNull String runId,
                       @NonNull Map<String, Object> payload,
                       int attempts,
                       @NonNull String error,
                       @NonNull String workerId) {
        String insertSql = """
                INSERT INTO agent_message_dead_letter
                    (tenant_id, queue_id, run_id, event_type, payload, attempts, error)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?::jsonb, ?, ?)
                """;
        // ⚠️⚠️ <b>删除必须带 {@code claimed_by} 围栏</b>（P1-14 修复）：
        //    否则旧 worker 能把「新 worker 正在处理的行」拉进死信。
        //    死信表是「只进不出」的，一条被误拉的死信就是一条永久的错误记录。
        String deleteSql = """
                DELETE FROM agent_message_queue
                WHERE queue_id = ?::uuid AND tenant_id = ?::uuid AND claimed_by = ?
                """;
        try (TenantConnectionLease lease = tenantConnection(tenantId)) {
            Connection conn = lease.connection();
            try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                ps.setString(1, tenantId);
                ps.setObject(2, queueId);
                ps.setString(3, runId);
                ps.setString(4, eventType);
                ps.setString(5, mapper.writeValueAsString(payload));
                ps.setInt(6, attempts);
                ps.setString(7, error);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(deleteSql)) {
                ps.setObject(1, queueId);
                ps.setString(2, tenantId);
                ps.setString(3, workerId);
                int deleted = ps.executeUpdate();
                if (deleted == 0) {
                    // ⚠️ 围栏拒绝 → 本次死信登记也不能生效（否则「已进死信但队列里还在」——
                    //    两步必须同生共死。抛异常 → 连接回滚 → 死信登记一并回滚。
                    throw new IllegalStateException(
                            "消息死信登记被围栏拒绝（行已被其他 worker 回收）: queueId=" + queueId);
                }
            }
            lease.commitQuietly();
        } catch (SQLException e) {
            throw new IllegalStateException("消息死信登记失败（queueId=" + queueId + "）", e);
        }
    }

    /**
     * 待处理深度（仅 PENDING）。
     *
     * <p>⚠️ <b>口径与 Rabbit 一致</b>：只算 ready 态，不算 INFLIGHT
     * （in-flight 即「正在处理」，等价 broker 的 unacked）。
     * 若把 INFLIGHT 也算进来，DB 通道的水位会显著高于 Rabbit 通道，
     * 导致「同一套水位配置在两种通道下行为不同」——
     * 那会让切换变成「悄悄改了背压阈值」。
     */
    public int pendingDepth(@NonNull String tenantId) {
        String sql = """
                SELECT count(*) FROM agent_message_queue
                WHERE tenant_id = ?::uuid AND state = 'PENDING'
                """;
        try (TenantConnectionLease lease = tenantConnection(tenantId)) {
            try (PreparedStatement ps = lease.connection().prepareStatement(sql)) {
                ps.setString(1, tenantId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            // ⚠️ 探测失败一律上抛（不得回落到 0）：见 QueueDepthProbe 契约。
            throw new IllegalStateException("队列深度探测失败（tenantId=" + tenantId + "）", e);
        }
    }

    /** @return 指定租户队列中的待处理行数（切换残留检查用） */
    public int pendingDepthOf(@NonNull String tenantId) {
        return pendingDepth(tenantId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readPayload(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        return mapper.readValue(json, new TypeReference<Map<String, Object>>() {
        });
    }

    /**
     * 取得租户绑定的自持连接（RLS 前置：绑定必须与事务同生命周期）。
     */
    private TenantConnectionLease tenantConnection(String tenantId) throws SQLException {
        Connection outer = TransactionalConnectionHolder.current();
        if (outer != null) {
            return TenantConnectionLease.borrowed(outer);
        }
        return TransactionalConnectionHolder.openTenantConnection(
                dataSource, new TenantContext(tenantId, "system", Set.of(), 0L));
    }

    /**
     * 一条已领取的消息。
     *
     * @param queueId  队列行 ID
     * @param tenantId 租户
     * @param runId    Run ID
     * @param eventType 事件类型
     * @param payload  消息体
     * @param attempts 已重试次数
     */
    public record QueuedMessage(
            @NonNull UUID queueId,
            @NonNull String tenantId,
            @NonNull String runId,
            @NonNull String eventType,
            @NonNull Map<String, Object> payload,
            int attempts) {
    }
}
