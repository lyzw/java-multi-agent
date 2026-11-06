package cn.studykid.multiagent.memory.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;
import cn.studykid.multiagent.infrastructure.persistence.TenantConnectionLease;
import cn.studykid.multiagent.memory.domain.MemoryEntry;
import cn.studykid.multiagent.memory.domain.MemoryStrength;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.memory.port.MemoryPort;

/**
 * 跨 Run 记忆的 JDBC 实现（P1 · {@code docs/Agent记忆机制分析与设计方案.md}）。
 *
 * <p><b>六条不变量</b>：
 * <ol>
 *   <li><b>全部走租户连接</b> —— 经 {@link TransactionalConnectionHolder}
 *       在事务内先绑定租户再执行业务 SQL。{@code student_memory} 是
 *       {@code FORCE ROW LEVEL SECURITY} 表，绑定顺序颠倒或漏绑的后果是
 *       <b>静默 0 行</b>（既不报错也不返回数据），而症状与
 *       "这个学生没有记忆"完全一致。</li>
 *   <li><b>强度读时重算</b> —— 库里的 {@code strength} 是<b>写入时</b>的值。
 *       召回路径必须按"当前时刻"重算（{@link MemoryStrength#compute}），
 *       否则白天读到的是昨夜的值，衰减就变成了"夜里跑批的事"。</li>
 *   <li><b>整合幂等</b> —— 只有 {@code last_run_id} 与本次 Run <b>不同</b>
 *       才允许 {@code evidence_count + 1}。MQ 重投与 {@code resume}
 *       会让同一 Run 的整合跑多次；若不判重，一个知识点会从"错 1 次"
 *       刷成"错 3 次"，<b>直接跨过召回门槛</b>（{@code base(1)=0.295} 不可召回，
 *       {@code base(3)=0.650} 可召回）—— 凭空制造一条并不存在的弱点。</li>
 *   <li><b>证据只来自 {@code weakness_record}</b> —— 不从模型输出派生。
 *       {@code weakness_record} 的行是经 {@code ToolInvocationGate}
 *       全链路闸门写入的；模型自述的"我记住了"不构成证据（R-2）。</li>
 *   <li><b>不抛异常穿透</b> —— 记忆是可选增强。所有失败降级为
 *       "这次没有记忆" + WARN，绝不让 Run 因记忆失败而失败。</li>
 *   <li><b>排序单调性</b> —— 召回前按 {@code evidence_count DESC, last_seen_at DESC}
 *       取候选集，再在 Java 侧按实时强度排序。理由见 {@link #SQL_RECALL}。</li>
 * </ol>
 *
 * <p>⚠️ <b>为什么按 {@code evidence_count DESC, last_seen_at DESC} 预排序</b>：
 * 真实强度 {@code base(n) * e^(-λ·days)} 对两个分量都单调递增 ——
 * {@code n} 更大且 {@code last_seen_at} 更近的行，强度<b>必然</b>更高。
 * 故该排序不会把"应当入选"的行挤到候选集之外（它是强度的支配序，
 * 只是不是全序）。反向不成立：单看 {@code strength} 列是<b>写入时</b>的值，
 * 用它排序会把一条三个月没再出现的强记忆排到前面。
 */
public class JdbcMemoryRepository implements MemoryPort {

    private static final Logger log = LoggerFactory.getLogger(JdbcMemoryRepository.class);

    /**
     * 召回候选集上限（取回后按实时强度再筛）。
     *
     * <p>⚠️ 设上限而不是"全表取回"：一个学生长期使用后记忆条目可达数百条，
     * 而真正能进上下文的最多 {@code recall-max-items}（默认 5）条。
     * 取回全表等于每轮 Run 都为 99% 用不上的数据付网络与解析成本。
     */
    private static final int MAX_RECALL_SCAN = 50;

    /**
     * 召回候选查询。
     *
     * <p>⚠️ {@code tenant_id} 条件<b>不可删除</b>（即便 RLS 已隔离，
     * 应用侧条件仍是第一道防线 —— 同 {@code JdbcProfileQueryRepository}）。
     *
     * <p>⚠️ 这里<b>不</b>在 SQL 里按 {@code strength >= 0.40} 过滤：
     * 该列是写入时的值。正确做法是取回候选集后在 Java 侧重算再筛，
     * 见 {@link #recall}。
     */
    private static final String SQL_RECALL = """
            SELECT student_id, knowledge_point, error_type, strength, evidence_count,
                   first_seen_at, last_seen_at, last_run_id
            FROM student_memory
            WHERE tenant_id = ?::uuid AND student_id = ?
            ORDER BY evidence_count DESC, last_seen_at DESC
            LIMIT %d
            """.formatted(MAX_RECALL_SCAN);

    /**
     * 本 Run 的证据（每个知识点只取一条）。
     *
     * <p>⚠️ 用 {@code DISTINCT ON} 而不是逐行处理：同一 Run 里一个知识点
     * 可能因不同错因被 {@code record_weakness} 写多条（幂等键含
     * {@code run_id + knowledge_point}，故同错因会去重、不同错因不会）。
     * 若逐行 upsert，一次 Run 会把 {@code evidence_count} 加多次 ——
     * 而 {@code evidence_count} 的语义是"<b>不同 Run</b>中出现的次数"。
     *
     * <p>取 {@code created_at DESC} 的第一条，即"该知识点本次最后给出的错因"。
     */
    private static final String SQL_EVIDENCE_OF_RUN = """
            SELECT DISTINCT ON (knowledge_point) knowledge_point, error_type, created_at
            FROM weakness_record
            WHERE tenant_id = ?::uuid AND student_id = ? AND run_id = ?::uuid
            ORDER BY knowledge_point, created_at DESC
            """;

    /** 读取既有记忆行（用于判定幂等与累加）。 */
    private static final String SQL_LOAD_ONE = """
            SELECT evidence_count, last_run_id, first_seen_at
            FROM student_memory
            WHERE tenant_id = ?::uuid AND student_id = ? AND knowledge_point = ?
            """;

    /** 新建记忆行。 */
    private static final String SQL_INSERT = """
            INSERT INTO student_memory
                (tenant_id, student_id, knowledge_point, error_type, strength,
                 evidence_count, first_seen_at, last_seen_at, last_run_id)
            VALUES (?::uuid, ?, ?, ?, ?, 1, ?, ?, ?::uuid)
            """;

    /**
     * 更新既有记忆行（增强）。
     *
     * <p>⚠️ {@code first_seen_at} <b>不更新</b>：它记录"这个知识点第一次
     * 被发现的时间"，是判断"长期弱点 vs 近期弱点"的唯一依据。
     * 跟着 {@code last_seen_at} 一起刷会让所有记忆都变成"最近才出现"。
     */
    private static final String SQL_UPDATE = """
            UPDATE student_memory
            SET error_type = ?, strength = ?, evidence_count = ?,
                last_seen_at = ?, last_run_id = ?::uuid, updated_at = now()
            WHERE tenant_id = ?::uuid AND student_id = ? AND knowledge_point = ?
            """;

    private static final String SQL_FORGET = """
            DELETE FROM student_memory
            WHERE tenant_id = ?::uuid AND student_id = ?
            """;

    private static final String SQL_COUNT = """
            SELECT count(*) FROM student_memory
            WHERE tenant_id = ?::uuid AND student_id = ?
            """;

    private final DataSource dataSource;
    private final Clock clock;

    public JdbcMemoryRepository(@NonNull DataSource dataSource) {
        this(dataSource, Clock.systemUTC());
    }

    public JdbcMemoryRepository(@NonNull DataSource dataSource, @NonNull Clock clock) {
        this.dataSource = dataSource;
        this.clock = clock;
    }

    @Override
    public @NonNull List<MemoryEntry> recall(@NonNull String tenantId,
                                             @NonNull String studentId,
                                             int k,
                                             int maxTokens) {
        if (k <= 0) {
            // ⚠️ 不是异常：k <= 0 的语义是"不要记忆"（配置为 0 时的合法意图）。
            throw new IllegalArgumentException("召回条数上限必须为正数，收到: " + k);
        }
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("召回 token 上限必须为正数，收到: " + maxTokens);
        }

        Instant now = clock.instant();
        List<MemoryEntry> candidates;
        try (TenantConnectionLease lease = tenantConnection(tenantId)) {
            try {
                candidates = loadCandidates(lease.connection(), tenantId, studentId);
            } finally {
                // ⚠️ 只读查询：回滚以释放读事务（同 JdbcProfileQueryRepository）。
                lease.rollbackQuietly();
            }
        } catch (SQLException e) {
            // ⚠️ 降级而非抛出：记忆不可用不应让 Run 失败（见类注释第 5 条）。
            log.warn("记忆召回失败，本次 Run 无历史记忆: tenant={}, student={}",
                    tenantId, studentId, e);
            return List.of();
        }

        // ⚠️ 按**实时**强度重排并筛选，而不是信任库里的 strength 列。
        //    库里的值是写入时的；三个月没再出现的强记忆若按它排序，
        //    会排在一条上周刚出现的弱记忆前面 —— 那正是"该忘的没忘"。
        List<MemoryEntry> sorted = candidates.stream()
                .sorted(Comparator.comparingDouble((MemoryEntry e) -> e.liveStrength(now)).reversed())
                .toList();

        List<MemoryEntry> result = new ArrayList<>();
        int usedTokens = 0;
        for (MemoryEntry entry : sorted) {
            if (result.size() >= k) {
                break;
            }
            if (!entry.recallable(now)) {
                continue;
            }
            int tokens = entry.estimatedTokens(now);
            if (usedTokens + tokens > maxTokens) {
                // ⚠️ 不截断、不硬塞：超出预算就少给几条。
                //    截断一条记忆的中段会把它变成无法解读的碎片，
                //    而模型仍会把它当成完整事实。
                continue;
            }
            result.add(entry);
            usedTokens += tokens;
        }

        if (result.size() < candidates.size()) {
            log.debug("记忆召回: tenant={}, student={}, 候选={}, 入选={}, tokens≈{}",
                    tenantId, studentId, candidates.size(), result.size(), usedTokens);
        }
        return List.copyOf(result);
    }

    @Override
    public int consolidate(@NonNull String tenantId,
                           @NonNull String studentId,
                           @NonNull String runId) {
        Instant now = clock.instant();
        try {
            return TransactionalConnectionHolder.inTransaction(
                    dataSource, systemContext(tenantId),
                    conn -> consolidateInTx(conn, tenantId, studentId, runId, now));
        } catch (RuntimeException e) {
            // ⚠️ 降级而非抛出：整合失败只意味着"这次没记住"，
            //    而 weakness_record 里的证据仍然在 —— 下一次 Run 会重新派生。
            //    若这里抛出去，等于让"记忆不可用"变成"Run 失败"。
            log.warn("记忆整合失败（本次不落记忆，证据仍在 weakness_record）: "
                    + "tenant={}, student={}, run={}", tenantId, studentId, runId, e);
            return 0;
        }
    }

    @Override
    public int forget(@NonNull String tenantId, @NonNull String studentId) {
        try {
            return TransactionalConnectionHolder.inTransaction(
                    dataSource, systemContext(tenantId),
                    conn -> {
                        try (PreparedStatement ps = conn.prepareStatement(SQL_FORGET)) {
                            ps.setString(1, tenantId);
                            ps.setString(2, studentId);
                            int deleted = ps.executeUpdate();
                            if (deleted > 0) {
                                log.info("记忆已删除（撤回同意）: tenant={}, student={}, rows={}",
                                        tenantId, studentId, deleted);
                            }
                            return deleted;
                        }
                    });
        } catch (RuntimeException e) {
            // ⚠️ 这里**必须**抛：这是合规删除路径，静默失败等于
            //    "以为删了、其实还在"。与 recall/consolidate 的降级纪律相反。
            log.error("记忆删除失败（合规风险）: tenant={}, student={}",
                    tenantId, studentId, e);
            throw e;
        }
    }

    @Override
    public int countMemories(@NonNull String tenantId, @NonNull String studentId) {
        try (TenantConnectionLease lease = tenantConnection(tenantId)) {
            try (PreparedStatement ps = lease.connection().prepareStatement(SQL_COUNT)) {
                ps.setString(1, tenantId);
                ps.setString(2, studentId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            } finally {
                lease.rollbackQuietly();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("统计记忆条数失败", e);
        }
    }

    // ── 内部 ────────────────────────────────────────────────────────

    private List<MemoryEntry> loadCandidates(Connection conn, String tenantId, String studentId)
            throws SQLException {
        List<MemoryEntry> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(SQL_RECALL)) {
            ps.setString(1, tenantId);
            ps.setString(2, studentId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    MemoryEntry entry = toEntry(rs);
                    if (entry != null) {
                        result.add(entry);
                    }
                }
            }
        }
        return result;
    }

    /**
     * 行 → 领域对象。
     *
     * <p>⚠️ 单行不合法时返回 {@code null} 并 WARN，而不是让整个召回失败：
     * 一条脏行（历史迁移遗留、外部直连写入）不应让这个学生的
     * <b>其余全部记忆</b>都不可用。
     */
    private @Nullable MemoryEntry toEntry(ResultSet rs) throws SQLException {
        try {
            Timestamp lastSeen = rs.getTimestamp("last_seen_at");
            Timestamp firstSeen = rs.getTimestamp("first_seen_at");
            String lastRunId = rs.getString("last_run_id");
            return new MemoryEntry(
                    rs.getString("student_id"),
                    rs.getString("knowledge_point"),
                    rs.getString("error_type"),
                    rs.getDouble("strength"),
                    rs.getInt("evidence_count"),
                    firstSeen == null ? Instant.EPOCH : firstSeen.toInstant(),
                    lastSeen == null ? Instant.EPOCH : lastSeen.toInstant(),
                    lastRunId);
        } catch (IllegalArgumentException e) {
            log.warn("跳过不合法的记忆行: kp={}, 原因={}",
                    rs.getString("knowledge_point"), e.getMessage());
            return null;
        }
    }

    private int consolidateInTx(Connection conn, String tenantId, String studentId,
                                String runId, Instant now) throws SQLException {
        List<Evidence> evidences = loadEvidenceOfRun(conn, tenantId, studentId, runId);
        if (evidences.isEmpty()) {
            // ⚠️ 不是错误：本次 Run 可能根本没有调用 record_weakness
            //    （比如学生答对了，或模型没判断出错因）。这是预期状态。
            log.debug("本 Run 无可整合证据: tenant={}, run={}", tenantId, runId);
            return 0;
        }

        int affected = 0;
        for (Evidence evidence : evidences) {
            affected += upsert(conn, tenantId, studentId, runId, evidence, now);
        }
        log.info("记忆整合完成: tenant={}, student={}, run={}, 证据={}, 变更={}",
                tenantId, studentId, runId, evidences.size(), affected);
        return affected;
    }

    private List<Evidence> loadEvidenceOfRun(Connection conn, String tenantId,
                                             String studentId, String runId)
            throws SQLException {
        List<Evidence> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(SQL_EVIDENCE_OF_RUN)) {
            ps.setString(1, tenantId);
            ps.setString(2, studentId);
            ps.setString(3, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp seen = rs.getTimestamp("created_at");
                    result.add(new Evidence(
                            rs.getString("knowledge_point"),
                            rs.getString("error_type"),
                            seen == null ? now() : seen.toInstant()));
                }
            }
        }
        return result;
    }

    /**
     * 单条证据的 upsert，返回 1（新建/增强）或 0（同 Run 重放，跳过）。
     *
     * <p>⚠️ <b>幂等判定必须发生在 {@code UPDATE} 之前</b>：
     * 先查再改。若写成"先无条件 UPDATE 再判断"，重放时
     * {@code evidence_count} 已经被加了，无法回退。
     */
    private int upsert(Connection conn, String tenantId, String studentId, String runId,
                       Evidence evidence, Instant now) throws SQLException {
        Existing existing = loadExisting(conn, tenantId, studentId, evidence.knowledgePoint());

        if (existing == null) {
            try (PreparedStatement ps = conn.prepareStatement(SQL_INSERT)) {
                ps.setString(1, tenantId);
                ps.setString(2, studentId);
                ps.setString(3, evidence.knowledgePoint());
                ps.setString(4, evidence.errorType());
                // 新建即 n = 1 → strength = base(1) ≈ 0.30（与 DDL 默认值同源）
                ps.setDouble(5, MemoryStrength.computeRounded(1, evidence.seenAt(), now));
                ps.setTimestamp(6, Timestamp.from(evidence.seenAt()));
                ps.setTimestamp(7, Timestamp.from(evidence.seenAt()));
                ps.setString(8, runId);
                return ps.executeUpdate();
            }
        }

        // ⚠️ 同一 Run 重复整合：原样保留，不增计数、不刷时间。
        //    这同时覆盖了 MQ 重投、resume 续跑、以及人工重放三种情形。
        if (runId.equals(existing.lastRunId())) {
            return 0;
        }

        int newCount = existing.evidenceCount() + 1;
        try (PreparedStatement ps = conn.prepareStatement(SQL_UPDATE)) {
            ps.setString(1, evidence.errorType());
            ps.setDouble(2, MemoryStrength.computeRounded(
                    newCount, evidence.seenAt(), now));
            ps.setInt(3, newCount);
            ps.setTimestamp(4, Timestamp.from(evidence.seenAt()));
            ps.setString(5, runId);
            ps.setString(6, tenantId);
            ps.setString(7, studentId);
            ps.setString(8, evidence.knowledgePoint());
            return ps.executeUpdate();
        }
    }

    private @Nullable Existing loadExisting(Connection conn, String tenantId,
                                            String studentId, String knowledgePoint)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SQL_LOAD_ONE)) {
            ps.setString(1, tenantId);
            ps.setString(2, studentId);
            ps.setString(3, knowledgePoint);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new Existing(rs.getInt("evidence_count"), rs.getString("last_run_id"));
            }
        }
    }

    private Instant now() {
        return clock.instant();
    }

    /**
     * 取得已绑定租户的连接。
     *
     * <p>⚠️ 与 {@code JdbcProfileQueryRepository} 同源铁律：
     * 外层有事务则复用，否则自开并绑定租户。
     */
    private TenantConnectionLease tenantConnection(String tenantId) throws SQLException {
        Connection outer = TransactionalConnectionHolder.current();
        if (outer != null) {
            return TenantConnectionLease.borrowed(outer);
        }
        return TransactionalConnectionHolder.openTenantConnection(
                dataSource, systemContext(tenantId));
    }

    private static TenantContext systemContext(String tenantId) {
        return new TenantContext(tenantId, "system", java.util.Set.of(), 0L);
    }

    /** 本 Run 的一条证据。 */
    private record Evidence(String knowledgePoint, String errorType, Instant seenAt) {
    }

    /** 既有记忆行的最小读取面。 */
    private record Existing(int evidenceCount, @Nullable String lastRunId) {
    }
}
