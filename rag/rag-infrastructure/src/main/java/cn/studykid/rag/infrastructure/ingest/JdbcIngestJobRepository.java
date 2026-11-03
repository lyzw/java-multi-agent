package cn.studykid.rag.infrastructure.ingest;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import cn.studykid.rag.core.ingest.IngestJob;
import cn.studykid.rag.core.ingest.IngestJobStatus;
import cn.studykid.rag.ports.IngestHealth;
import cn.studykid.rag.ports.IngestJobPort;

/**
 * {@link IngestJobPort} 的 JDBC 实现（{@code rag_ingest_job}）。
 *
 * <p><b>⚠️ 本实现下沉到 {@code rag-infrastructure} 而非 {@code rag-worker}</b>：
 * 管理面（建单/列表/重试）与 worker（领取/回写/Sweeper）都要用它，
 * 而 {@code rag-worker} 被 {@code rag-management} 反向依赖是不允许的。
 * 口径只有一处，两侧只依赖契约。
 *
 * <p><b>⚠️ 多 worker 领取的正确性依赖 {@code FOR UPDATE SKIP LOCKED}</b>：
 * 两个 worker 同时轮询时，先到者锁行并领取，后到者跳过该行取下一行——
 * 不需要任何应用层分布式锁。用 {@code FOR UPDATE}（无 SKIP LOCKED）会让
 * 后到者<b>阻塞等待</b>而非跳过，多个 worker 退化为串行。
 *
 * <p><b>⚠️ 租户隔离</b>：本表策略为 {@code tenant_id = rag_current_tenant()}，
 * 应用连接是 {@code NOBYPASSRLS} 的非特权角色，故<b>每次读写前必须
 * 在事务内 {@code set_config('app.tenant_id', ?, true)}</b>，否则读不到、写不进
 * （表现为「扫到 0 行、报告成功」的静默失败）。领取与 Sweeper 是<b>跨租户</b>动作，
 * 走运维通道的连接（见 {@code RagOpsSessionPort}）或由调用方保证会话已就绪。
 */
public class JdbcIngestJobRepository implements IngestJobPort {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public JdbcIngestJobRepository(JdbcTemplate jdbcTemplate,
                                   TransactionTemplate transactionTemplate) {
        if (jdbcTemplate == null || transactionTemplate == null) {
            throw new IllegalArgumentException("jdbcTemplate / transactionTemplate 不能为空");
        }
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    /** 列清单（读取顺序与 {@link #mapJob} 严格对应）。 */
    private static final String COLUMNS =
            "job_id, tenant_id, source_id, status, stage, attempt, created_by,"
            + " parser_version, embed_model, chunk_count, error_detail,"
            + " started_at, finished_at, created_at";

    @Override
    public @NonNull IngestJob createJob(@NonNull String tenantId,
                                        @NonNull String sourceId,
                                        @NonNull String createdBy) {
        return createJobInternal(tenantId, sourceId, createdBy, 0);
    }

    @Override
    public @NonNull IngestJob createRetryJob(@NonNull String tenantId,
                                             @NonNull String sourceId,
                                             @NonNull String createdBy,
                                             @Nullable String previousJobId) {
        // ⚠️ 重试=新建行，attempt 在原 job 基础上 +1；旧行永久留痕、绝不 UPDATE。
        int nextAttempt = 1;
        if (previousJobId != null && !previousJobId.isBlank()) {
            Integer prev = jdbcTemplate.query(
                    "SELECT attempt FROM rag_ingest_job WHERE job_id = ?::uuid",
                    (rs, i) -> rs.getInt(1), previousJobId).stream().findFirst().orElse(0);
            nextAttempt = (prev == null ? 0 : prev) + 1;
        }
        return createJobInternal(tenantId, sourceId, createdBy, nextAttempt);
    }

    private IngestJob createJobInternal(String tenantId, String sourceId,
                                        String createdBy, int attempt) {
        return transactionTemplate.execute(status -> {
            // ⚠️ RLS：必须先声明租户会话，否则 INSERT 被 WITH CHECK 拒绝（或静默 0 行）。
            // ⚠️ 必须用 queryForObject 而非 update：set_config 是 SELECT、返回一行，
            //    update() 期望「无结果集」，否则抛 DataIntegrityViolation（实测 500）。
            jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                    String.class, tenantId);
            String jobId = jdbcTemplate.queryForObject(
                    "INSERT INTO rag_ingest_job"
                            + " (tenant_id, source_id, status, attempt, created_by)"
                            + " VALUES (?::uuid, ?::uuid, 'PENDING'::rag_ingest_status, ?, ?::uuid)"
                            + " RETURNING job_id::text",
                    String.class, tenantId, sourceId, attempt, createdBy);
            return load(jobId);
        });
    }

    @Override
    public @Nullable IngestJob claimNextJob() {
        return transactionTemplate.execute(status -> {
            // ⚠️⚠️ SKIP LOCKED 是本类的核心正确性来源：
            //    多 worker 并发轮询时各领一条、互不阻塞、绝不重复。
            //    ⚠️ 领取是跨租户动作：WHERE 不带租户谓词，故必须走**绕过 RLS 的运维通道**
            //    连接（由调用方在事务外已切换会话，见 IngestJobConsumer）。
            String jobId = jdbcTemplate.query("""
                            SELECT job_id::text FROM rag_ingest_job
                             WHERE status = 'PENDING'::rag_ingest_status
                             ORDER BY created_at
                             LIMIT 1
                             FOR UPDATE SKIP LOCKED
                            """,
                    (rs, i) -> rs.getString(1)).stream().findFirst().orElse(null);
            if (jobId == null) {
                return null;
            }
            jdbcTemplate.update("""
                            UPDATE rag_ingest_job
                               SET status = 'PARSING'::rag_ingest_status,
                                   started_at = COALESCE(started_at, now())
                             WHERE job_id = ?::uuid
                            """, jobId);
            return load(jobId);
        });
    }

    @Override
    public void updateStage(@NonNull String jobId, @NonNull String stage) {
        jdbcTemplate.update("UPDATE rag_ingest_job SET stage = ? WHERE job_id = ?::uuid",
                stage, jobId);
    }

    @Override
    public void advance(@NonNull String jobId, @NonNull IngestJobStatus status,
                        @Nullable String stage) {
        jdbcTemplate.update(
                "UPDATE rag_ingest_job SET status = ?::rag_ingest_status, stage = ?"
                        + " WHERE job_id = ?::uuid",
                status.name(), stage, jobId);
    }

    @Override
    public void markSucceeded(@NonNull String jobId, int chunkCount,
                              @NonNull String parserVersion, @NonNull String embedModel) {
        jdbcTemplate.update("""
                        UPDATE rag_ingest_job
                           SET status = 'SUCCEEDED'::rag_ingest_status,
                               chunk_count = ?,
                               parser_version = ?,
                               embed_model = ?,
                               finished_at = now(),
                               stage = NULL
                         WHERE job_id = ?::uuid
                        """, chunkCount, parserVersion, embedModel, jobId);
    }

    @Override
    public void markFailed(@NonNull String jobId, @NonNull String errorDetail) {
        // ⚠️ error_detail 必填：没有原因的 FAILED 在录入工作台上无法处置（见 IngestJob 不变式）。
        jdbcTemplate.update("""
                        UPDATE rag_ingest_job
                           SET status = 'FAILED'::rag_ingest_status,
                               error_detail = ?,
                               finished_at = now(),
                               stage = NULL
                         WHERE job_id = ?::uuid
                        """, errorDetail, jobId);
    }

    @Override
    public int cancelUnfinishedBySource(@NonNull String tenantId, @NonNull String sourceId) {
        return transactionTemplate.execute(status -> {
            // ⚠️ 必须用 queryForObject 而非 update：set_config 是 SELECT、返回一行，
            //    update() 期望「无结果集」，否则抛 DataIntegrityViolation（实测 500）。
            jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                    String.class, tenantId);
            // ⚠️ 只取消未完成（未进终态）的 job；已完成的不动。
            //    ⚠️ 置 REJECTED + SOURCE_REVOKED 而非新增 CANCELLED 枚举值（方案 P5/N2）。
            return jdbcTemplate.update("""
                            UPDATE rag_ingest_job
                               SET status = 'REJECTED'::rag_ingest_status,
                                   error_detail = ?,
                                   finished_at = now(),
                                   stage = NULL
                             WHERE tenant_id = ?::uuid
                               AND source_id = ?::uuid
                               AND status NOT IN ('SUCCEEDED'::rag_ingest_status,
                                                  'FAILED'::rag_ingest_status,
                                                  'REJECTED'::rag_ingest_status)
                            """, IngestJobStatus.REASON_SOURCE_REVOKED, tenantId, sourceId);
        });
    }

    @Override
    public int failTimedOutRunningJobs(int timeoutMinutes) {
        if (timeoutMinutes <= 0) {
            throw new IllegalArgumentException("timeoutMinutes 必须 > 0");
        }
        return jdbcTemplate.update("""
                        UPDATE rag_ingest_job
                           SET status = 'FAILED'::rag_ingest_status,
                               error_detail = 'SWEEPER_TIMEOUT: 超过 ' || ? || ' 分钟未完成，被 Sweeper 接管',
                               finished_at = now(),
                               stage = NULL
                         WHERE status IN ('PARSING'::rag_ingest_status,
                                          'CHUNKING'::rag_ingest_status,
                                          'EMBEDDING'::rag_ingest_status,
                                          'INDEXING'::rag_ingest_status)
                           AND started_at < now() - make_interval(mins => ?)
                        """, timeoutMinutes, timeoutMinutes);
    }

    @Override
    public @Nullable IngestJob findById(@NonNull String jobId) {
        List<IngestJob> rows = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM rag_ingest_job WHERE job_id = ?::uuid",
                this::mapJob, jobId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public @NonNull List<IngestJob> listBySource(@NonNull String sourceId, int limit) {
        int capped = Math.max(1, Math.min(limit, 200));
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM rag_ingest_job"
                        + " WHERE source_id = ?::uuid ORDER BY created_at DESC LIMIT ?",
                this::mapJob, sourceId, capped);
    }

    @Override
    public @NonNull List<IngestJob> listRecentByTenant(@NonNull String tenantId, int limit) {
        int capped = Math.max(1, Math.min(limit, 200));
        return transactionTemplate.execute(status -> {
            // ⚠️ RLS：先声明租户会话，否则读到 0 行（静默空列表）。
            // ⚠️ 必须用 queryForObject 而非 update：set_config 是 SELECT、返回一行，
            //    update() 期望「无结果集」，否则抛 DataIntegrityViolation（实测 500）。
            jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                    String.class, tenantId);
            return jdbcTemplate.query(
                    "SELECT " + COLUMNS + " FROM rag_ingest_job"
                            + " WHERE tenant_id = ?::uuid ORDER BY created_at DESC LIMIT ?",
                    this::mapJob, tenantId, capped);
        });
    }


    @Override
    public @NonNull IngestHealth health() {
        // ⚠️ 这是跨租户汇总查询；在应用角色下会被 RLS 过滤成 0，
        //    从而得到「一切正常」的假象——故必须在运维通道连接上执行。
        Integer failed = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM rag_ingest_job"
                        + " WHERE status = 'FAILED'::rag_ingest_status", Integer.class);
        Integer finished = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM rag_ingest_job"
                        + " WHERE status IN ('SUCCEEDED','FAILED','REJECTED')", Integer.class);
        Integer pending = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM rag_ingest_job"
                        + " WHERE status = 'PENDING'::rag_ingest_status", Integer.class);
        Long oldest = jdbcTemplate.queryForObject(
                "SELECT COALESCE(EXTRACT(EPOCH FROM (now() - MIN(created_at))) / 60, 0)::bigint"
                        + " FROM rag_ingest_job"
                        + " WHERE status = 'PENDING'::rag_ingest_status", Long.class);
        Integer takeovers = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM rag_ingest_job"
                        + " WHERE error_detail LIKE 'SWEEPER_TIMEOUT%'"
                        + " AND finished_at > now() - interval '1 hour'", Integer.class);
        return new IngestHealth(
                failed == null ? 0 : failed,
                finished == null ? 0 : finished,
                pending == null ? 0 : pending,
                oldest == null ? 0L : oldest,
                takeovers == null ? 0 : takeovers);
    }

    private IngestJob load(String jobId) {
        IngestJob job = findById(jobId);
        if (job == null) {
            throw new IllegalStateException("刚写入的 job 读不回来（RLS 会话未就绪？）：" + jobId);
        }
        return job;
    }

    private IngestJob mapJob(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new IngestJob(
                rs.getString("job_id"),
                rs.getString("tenant_id"),
                rs.getString("source_id"),
                IngestJobStatus.of(rs.getString("status")),
                rs.getString("stage"),
                rs.getInt("attempt"),
                rs.getString("created_by"),
                rs.getString("parser_version"),
                rs.getString("embed_model"),
                (Integer) rs.getObject("chunk_count"),
                rs.getString("error_detail"),
                ts(rs.getTimestamp("started_at")),
                ts(rs.getTimestamp("finished_at")),
                ts(rs.getTimestamp("created_at")));
    }

    private static String ts(Timestamp t) {
        if (t == null) {
            return null;
        }
        Instant i = t.toInstant();
        return java.time.OffsetDateTime.ofInstant(i, java.time.ZoneOffset.UTC).toString();
    }
}
