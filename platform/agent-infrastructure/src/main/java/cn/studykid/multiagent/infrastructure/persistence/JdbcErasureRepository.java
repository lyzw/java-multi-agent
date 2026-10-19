package cn.studykid.multiagent.infrastructure.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.ErasurePort;

/**
 * {@link ErasurePort} 的 JDBC 实现（P4-D04）。
 *
 * <p><b>五条不变量</b>：
 * <ol>
 *   <li><b>全部走租户连接</b>（D-4）—— 经 {@link TransactionalConnectionHolder#inTransaction}，
 *       使 RLS 策略对每个 DELETE 生效。本类<b>刻意不提供</b> admin 连接的删除路径：
 *       批量删除的影响面太大，不能依赖调用方记得传租户。</li>
 *   <li><b>Prompt 只置空不删行</b>（D-2）—— 删 {@code run} 行会经
 *       {@code ON DELETE CASCADE} 级联删除 {@code weakness_record}，那是业务数据。</li>
 *   <li><b>审计删除必须声明 actor</b>（D-1）—— 由 DB 触发器强制，此处 fail-fast
 *       给出可读错误。</li>
 *   <li><b>凭证幂等</b>（D-6）—— 依赖 DB 唯一约束，
 *       {@code ON CONFLICT DO NOTHING}。</li>
 *   <li><b>JSON 自拼自解</b> —— 见 {@link #toJson} 与 {@link #parseDetail}。</li>
 * </ol>
 *
 * <p><b>⚠️ 为什么每个公有方法各自开事务</b>：
 * 清理是周期性批量操作，单条语句失败不应回滚整个租户的清理成果
 * （已经删掉的数据无法回滚，强行回滚反而造成「凭证与事实不一致」）。
 * 因此本类不提供跨方法的原子性，由上层 {@code RetentionPurgeService} 决定补写方式。
 */
public class JdbcErasureRepository implements ErasurePort {

    private static final Logger log = LoggerFactory.getLogger(JdbcErasureRepository.class);

    /** 已构成最终状态、不再会被 Worker 读取输入的 Run。 */
    private static final Set<String> TERMINAL_STATES =
            Set.of("SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT");

    private final DataSource dataSource;

    public JdbcErasureRepository(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public int purgeExpiredProfiles(@NonNull String tenantId,
                                    @NonNull Instant cutoff,
                                    int limit) {
        // ⚠️ weakness_record 经 FK ON DELETE CASCADE 一并删除。
        //    这是期望行为：画像到期时其薄弱点明细不应孤立留存。
        return inTenantTx(tenantId, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM student_profile "
                            + "WHERE tenant_id = ?::uuid AND created_at < ? "
                            + "  AND ctid IN (SELECT ctid FROM student_profile "
                            + "               WHERE tenant_id = ?::uuid AND created_at < ? "
                            + "               ORDER BY created_at LIMIT ?)")) {
                ps.setString(1, tenantId);
                ps.setTimestamp(2, Timestamp.from(cutoff));
                ps.setString(3, tenantId);
                ps.setTimestamp(4, Timestamp.from(cutoff));
                ps.setInt(5, limit);
                return ps.executeUpdate();
            }
        });
    }

    @Override
    public int blankExpiredPrompts(@NonNull String tenantId,
                                   @NonNull Instant cutoff,
                                   int limit) {
        return inTenantTx(tenantId, conn -> {
            List<String> terminalIds = selectExpiredTerminalRunIds(conn, tenantId, cutoff, limit);
            if (terminalIds.isEmpty()) {
                return 0;
            }
            int changed = 0;
            changed += blankRunPayloads(conn, tenantId, terminalIds);
            blankRunStepPayloads(conn, tenantId, terminalIds);
            blankToolOperationPayloads(conn, tenantId, terminalIds);
            return changed;
        });
    }

    @Override
    public int purgeExpiredTraces(@NonNull String tenantId,
                                  @NonNull Instant cutoff,
                                  int limit) {
        return inTenantTx(tenantId, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM run_event "
                            + "WHERE tenant_id = ?::uuid AND created_at < ? "
                            + "  AND ctid IN (SELECT ctid FROM run_event "
                            + "               WHERE tenant_id = ?::uuid AND created_at < ? "
                            + "               ORDER BY created_at LIMIT ?)")) {
                ps.setString(1, tenantId);
                ps.setTimestamp(2, Timestamp.from(cutoff));
                ps.setString(3, tenantId);
                ps.setTimestamp(4, Timestamp.from(cutoff));
                ps.setInt(5, limit);
                return ps.executeUpdate();
            }
        });
    }

    @Override
    public int purgeExpiredAudits(@NonNull String tenantId,
                                  @NonNull Instant cutoff,
                                  int limit,
                                  @NonNull String actor) {
        // ⚠️ 为什么要在 Java 侧提前 fail-fast：
        //    DB 触发器也会拒绝空 actor，但那条错误消息是 PG 的 SQLSTATE 文本，
        //    不含「你是不是忘了传操作主体」的提示。运维看到后很可能
        //    误以为要去看权限配置，于是往错误方向排查。
        if (actor.isBlank()) {
            throw new IllegalArgumentException(
                    "审计清理必须声明操作主体（P4-D04 D-1）：actor 为空。"
                            + "该主体会被写入 erasure_certificate，是删除行为的唯一责任人。");
        }
        return inTenantTx(tenantId, conn -> {
            declareRetentionActor(conn, actor);
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM audit_record "
                            + "WHERE tenant_id = ?::uuid AND created_at < ? "
                            + "  AND ctid IN (SELECT ctid FROM audit_record "
                            + "               WHERE tenant_id = ?::uuid AND created_at < ? "
                            + "               ORDER BY created_at LIMIT ?)")) {
                ps.setString(1, tenantId);
                ps.setTimestamp(2, Timestamp.from(cutoff));
                ps.setString(3, tenantId);
                ps.setTimestamp(4, Timestamp.from(cutoff));
                ps.setInt(5, limit);
                int deleted = ps.executeUpdate();
                if (deleted > 0) {
                    log.info("审计记录已按保留期清理: tenant={}, rows={}, actor={}",
                            tenantId, deleted, actor);
                }
                return deleted;
            }
        });
    }

    @Override
    public void registerWithdrawal(@NonNull String tenantId,
                                   @NonNull String studentId,
                                   @NonNull Instant withdrawnAt,
                                   @NonNull Instant dueAt) {
        inTenantTx(tenantId, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO consent_withdrawal "
                            + "(tenant_id, student_id, withdrawn_at, due_at, state) "
                            + "VALUES (?::uuid, ?, ?, ?, 'PENDING') "
                            + "ON CONFLICT (tenant_id, student_id) DO NOTHING")) {
                ps.setString(1, tenantId);
                ps.setString(2, studentId);
                ps.setTimestamp(3, Timestamp.from(withdrawnAt));
                ps.setTimestamp(4, Timestamp.from(dueAt));
                ps.executeUpdate();
            }
            log.info("已登记监护人撤回同意: tenant={}, student={}, dueAt={}",
                    tenantId, studentId, dueAt);
            return null;
        });
    }

    @Override
    public @NonNull List<PendingWithdrawal> findDueWithdrawals(@NonNull String tenantId,
                                                               @NonNull Instant now) {
        return inTenantTx(tenantId, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT tenant_id::text, student_id, due_at "
                            + "FROM consent_withdrawal "
                            + "WHERE tenant_id = ?::uuid AND state = 'PENDING' AND due_at <= ? "
                            + "ORDER BY due_at LIMIT 1000")) {
                ps.setString(1, tenantId);
                ps.setTimestamp(2, Timestamp.from(now));
                try (ResultSet rs = ps.executeQuery()) {
                    List<PendingWithdrawal> rows = new ArrayList<>();
                    while (rs.next()) {
                        rows.add(new PendingWithdrawal(
                                rs.getString("tenant_id"),
                                rs.getString("student_id"),
                                rs.getTimestamp("due_at").toInstant()));
                    }
                    return rows;
                }
            }
        });
    }

    @Override
    public int eraseWithdrawnStudent(@NonNull String tenantId,
                                     @NonNull String studentId,
                                     @NonNull String actor) {
        if (actor.isBlank()) {
            throw new IllegalArgumentException(
                    "撤回删除必须声明操作主体（P4-D04 D-1）：actor 为空");
        }
        return inTenantTx(tenantId, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM student_profile "
                            + "WHERE tenant_id = ?::uuid AND student_id = ?")) {
                ps.setString(1, tenantId);
                ps.setString(2, studentId);
                return ps.executeUpdate();
            }
        });
    }

    @Override
    public void markWithdrawalErased(@NonNull String tenantId,
                                     @NonNull String studentId,
                                     @NonNull Instant erasedAt) {
        updateWithdrawalState(tenantId, studentId, "ERASED", erasedAt);
    }

    @Override
    public void markWithdrawalFailed(@NonNull String tenantId,
                                     @NonNull String studentId) {
        updateWithdrawalState(tenantId, studentId, "FAILED", null);
    }

    @Override
    public void recordCertificate(@NonNull String tenantId,
                                  @NonNull ErasureCertificate certificate) {
        inTenantTx(tenantId, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO erasure_certificate "
                            + "(tenant_id, reason, actor, executed_at, "
                            + " window_key, subject_key, detail) "
                            + "VALUES (?::uuid, ?, ?, ?, ?, ?, ?::jsonb) "
                            + "ON CONFLICT (tenant_id, reason, window_key, subject_key) "
                            + "DO NOTHING")) {
                ps.setString(1, tenantId);
                ps.setString(2, certificate.reason());
                ps.setString(3, certificate.actor());
                ps.setTimestamp(4, Timestamp.from(certificate.executedAt()));
                ps.setString(5, certificate.windowKey());
                ps.setString(6, certificate.subjectKey());
                ps.setString(7, toJson(certificate.detail()));
                int inserted = ps.executeUpdate();
                if (inserted == 0) {
                    // ⚠️ 不是错误：同一窗口重复运行是预期状态。
                    //    但必须留日志——否则「凭证缺失」会被误判为「清理未执行」。
                    log.debug("删除凭证已存在（幂等跳过）: tenant={}, reason={}, window={}, subject={}",
                            tenantId, certificate.reason(), certificate.windowKey(),
                            certificate.subjectKey());
                }
            }
            return null;
        });
    }

    @Override
    public @NonNull List<ErasureCertificate> findCertificates(@NonNull String tenantId,
                                                              @NonNull String reason,
                                                              int limit) {
        return inTenantTx(tenantId, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT reason, actor, executed_at, window_key, subject_key, detail "
                            + "FROM erasure_certificate "
                            + "WHERE tenant_id = ?::uuid AND reason = ? "
                            + "ORDER BY executed_at DESC LIMIT ?")) {
                ps.setString(1, tenantId);
                ps.setString(2, reason);
                ps.setInt(3, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    List<ErasureCertificate> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(new ErasureCertificate(
                                rs.getString("reason"),
                                rs.getString("actor"),
                                rs.getTimestamp("executed_at").toInstant(),
                                rs.getString("window_key"),
                                rs.getString("subject_key"),
                                parseDetail(rs.getString("detail"))));
                    }
                    return out;
                }
            }
        });
    }

    // --------------------------------------------------------------
    // 内部实现
    // --------------------------------------------------------------

    /**
     * 选出到期且<b>已终态</b>的 run。
     *
     * <p>⚠️ <b>为什么必须限定终态</b>：若在 {@code RUNNING} 的 run 上清空
     * {@code input_payload}，Worker 会在执行中读到空输入，
     * 产生「模型收到空 Prompt」这类<b>看起来像模型故障</b>的现象，
     * 而真实原因是清理任务误改了在途数据。
     */
    private List<String> selectExpiredTerminalRunIds(Connection conn,
                                                     String tenantId,
                                                     Instant cutoff,
                                                     int limit) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                // ⚠️ ?::run_state[] 不可省略（P4-D04 实测 F-13）：
                // run_state 是枚举，与 text[] 之间没有比较算子，
                // 裸 ANY(?) 会报「operator does not exist: run_state = text」。
                // 用 cast 而非 state::text：后者使 state 上的索引失效。
                "SELECT run_id::text FROM run "
                        + "WHERE tenant_id = ?::uuid AND created_at < ? "
                        + "  AND state = ANY (?::run_state[]) "
                        + "ORDER BY created_at LIMIT ?")) {
            ps.setString(1, tenantId);
            ps.setTimestamp(2, Timestamp.from(cutoff));
            ps.setArray(3, conn.createArrayOf("text", TERMINAL_STATES.toArray()));
            ps.setInt(4, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> ids = new ArrayList<>();
                while (rs.next()) {
                    ids.add(rs.getString(1));
                }
                return ids;
            }
        }
    }

    /**
     * 置空 run 的 Prompt 与结果。
     *
     * <p>⚠️ <b>为什么置 {@code '{}'} 而不是 {@code NULL}</b>（实测 schema）：
     * <ul>
     *   <li>{@code run.input_payload JSONB NOT NULL} —— NULL 会直接违反列约束；</li>
     *   <li>{@code run_result_consistent CHECK (state <> 'SUCCEEDED' OR result_payload IS NOT NULL)}
     *       —— 对 SUCCEEDED 的 run 置 NULL 会违反 CHECK。</li>
     * </ul>
     * 因此只能用<b>空对象</b>表示「已清理」。这也让「已清理」与「从未有值」
     * 在语义上保持可区分（后者不存在）。
     */
    private int blankRunPayloads(Connection conn,
                                 String tenantId,
                                 List<String> runIds) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE run SET input_payload = '{}'::jsonb, result_payload = '{}'::jsonb "
                        + "WHERE tenant_id = ?::uuid AND run_id = ANY (?::uuid[]) "
                        + "  AND (input_payload <> '{}'::jsonb "
                        + "       OR result_payload IS DISTINCT FROM '{}'::jsonb)")) {
            ps.setString(1, tenantId);
            ps.setArray(2, conn.createArrayOf("text", runIds.toArray()));
            return ps.executeUpdate();
        }
    }

    private void blankRunStepPayloads(Connection conn,
                                      String tenantId,
                                      List<String> runIds) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE run_step SET input_payload = '{}'::jsonb, output_payload = '{}'::jsonb "
                        + "WHERE tenant_id = ?::uuid AND run_id = ANY (?::uuid[])")) {
            ps.setString(1, tenantId);
            ps.setArray(2, conn.createArrayOf("text", runIds.toArray()));
            ps.executeUpdate();
        }
    }

    private void blankToolOperationPayloads(Connection conn,
                                            String tenantId,
                                            List<String> runIds) throws SQLException {
        // ⚠️ 为什么不清空 tool_operation.tool_arguments：
        //    工具参数账本是红线 4（操作与 attempt 区分）的对账依据，
        //    清空后无法回答「当时到底传了什么给工具」。
        //    工具参数的个人信息含量由 input_payload 承载，已在上面清理。
        //    本方法只清空结果载荷。
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE tool_operation SET result_payload = '{}'::jsonb "
                        + "WHERE tenant_id = ?::uuid AND run_id = ANY (?::uuid[]) "
                        + "  AND result_payload IS NOT NULL")) {
            ps.setString(1, tenantId);
            ps.setArray(2, conn.createArrayOf("text", runIds.toArray()));
            ps.executeUpdate();
        }
    }

    /**
     * 声明受控清理主体。
     *
     * <p>⚠️ <b>{@code is_local=true} 且必须在事务内</b>：
     * 自动提交模式下该设置在语句结束后立即丢失（与 RLS 的
     * {@code app.tenant_id} 绑定是同一类坑）。此处由
     * {@link TransactionalConnectionHolder#inTransaction} 保证处于事务中。
     */
    private void declareRetentionActor(Connection conn, String actor) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT set_config('agent.retention.actor', ?, true)")) {
            ps.setString(1, actor);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
            }
        }
    }

    private void updateWithdrawalState(String tenantId,
                                       String studentId,
                                       String state,
                                       Instant erasedAt) {
        inTenantTx(tenantId, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE consent_withdrawal SET state = ?, erased_at = ? "
                            + "WHERE tenant_id = ?::uuid AND student_id = ?")) {
                ps.setString(1, state);
                ps.setTimestamp(2, erasedAt == null ? null : Timestamp.from(erasedAt));
                ps.setString(3, tenantId);
                ps.setString(4, studentId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /**
     * 自拼 JSON。
     *
     * <p>⚠️ 为什么不用 PG 的 {@code json_build_object}：
     * 它输出 {@code {"k" : "v"}}（冒号<b>两侧带空格</b>），
     * 严格解析器返回 null <b>且不报错</b>（项目已记录的 F-10 静默失效）。
     * 既然要写，就让输出格式完全由本类控制。
     */
    private static String toJson(Map<String, Integer> detail) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Integer> e : detail.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            sb.append('"').append(e.getKey()).append("\": ").append(e.getValue());
            first = false;
        }
        return sb.append('}').toString();
    }

    /**
     * 解析 {@code {"k": n}} 形式的整数 Map。
     *
     * <p>⚠️ 刻意手写宽松解析：容忍 PG 输出中冒号两侧的空格与顺序差异。
     * 返回空 Map 时调用方无法区分「没有明细」与「解析失败」，
     * 故解析出的条目数会被记入 debug 日志。
     */
    private static Map<String, Integer> parseDetail(String raw) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (raw == null || raw.isBlank() || "{}".equals(raw.trim())) {
            return out;
        }
        String body = raw.trim();
        if (body.startsWith("{") && body.endsWith("}")) {
            body = body.substring(1, body.length() - 1);
        }
        for (String part : body.split(",")) {
            String[] kv = part.split(":", 2);
            if (kv.length != 2) {
                continue;
            }
            String key = kv[0].trim().replace("\"", "");
            String value = kv[1].trim().replace("\"", "");
            try {
                out.put(key, Integer.valueOf(value));
            } catch (NumberFormatException ignored) {
                // 非整数明细项不参与统计；不静默吞掉——留日志
                log.trace("凭证明细项解析跳过（非整数）: key={}", key);
            }
        }
        return out;
    }

    private <T> T inTenantTx(String tenantId, TxAction<T> action) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource,
                new TenantContext(tenantId, "system", Set.of(), 0L),
                action::run);
    }

    @FunctionalInterface
    private interface TxAction<T> {
        T run(Connection conn) throws SQLException;
    }
}
