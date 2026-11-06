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
import cn.studykid.rag.infrastructure.ops.RagOpsSessionPort;
import cn.studykid.rag.infrastructure.ops.RagOpsUnavailableException;
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

    /**
     * 平台全局桶的建单通道（BYPASSRLS 的 rag_ops）；为 null 时平台档建单直接抛
     * {@link RagOpsUnavailableException}（呈现 503），绝不回落应用角色。
     */
    private final RagOpsSessionPort opsSession;

    public JdbcIngestJobRepository(JdbcTemplate jdbcTemplate,
                                   TransactionTemplate transactionTemplate) {
        this(jdbcTemplate, transactionTemplate, null);
    }

    public JdbcIngestJobRepository(JdbcTemplate jdbcTemplate,
                                   TransactionTemplate transactionTemplate,
                                   @Nullable RagOpsSessionPort opsSession) {
        if (jdbcTemplate == null || transactionTemplate == null) {
            throw new IllegalArgumentException("jdbcTemplate / transactionTemplate 不能为空");
        }
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.opsSession = opsSession;
    }

    /**
     * 平台全局桶建单（rag_ops 运维会话）。
     *
     * <p><b>⚠️ 为什么不是「给 createJob 传全局哨兵」</b>：应用通道的
     * {@code set_config('app.tenant_id', 哨兵)} 会撞 {@code rag_current_tenant()}
     * 的越权封堵（SQLSTATE 42501，V8 承重设计）——那句话在库里就不成立。
     * 全局行的租户列在 SQL 文本里直接写 {@code rag_global_tenant_id()}，
     * 方法签名里也没有租户参数可填。
     *
     * @param sourceId  所属来源（全局桶内）
     * @param createdBy 建单人主体 ID
     * @return 新建的 job
     */
    @NonNull
    public IngestJob createGlobalJob(@NonNull String sourceId, @NonNull String createdBy) {
        if (opsSession == null) {
            throw new RagOpsUnavailableException("rag.ops.datasource",
                    "平台全局语料写通道未接入：建摄取任务需要运维数据源（rag_ops），不回落应用角色");
        }
        return opsSession.inGlobalCorpusSession((opsTemplate, opsTx) ->
                opsTx.execute(status -> {
                    String jobId = opsTemplate.queryForObject(
                            "INSERT INTO rag_ingest_job"
                                    + " (tenant_id, source_id, status, attempt, created_by)"
                                    + " VALUES (rag_global_tenant_id(), ?::uuid,"
                                    + "         'PENDING'::rag_ingest_status, 0, ?::uuid)"
                                    + " RETURNING job_id::text",
                            String.class, sourceId, createdBy);
                    List<IngestJob> rows = opsTemplate.query(
                            "SELECT " + COLUMNS + " FROM rag_ingest_job WHERE job_id = ?::uuid",
                            this::mapJob, jobId);
                    if (rows.isEmpty()) {
                        throw new IllegalStateException("刚写入的全局 job 读不回来：" + jobId);
                    }
                    return rows.get(0);
                }));
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
        // ⚠️ attempt 查询必须声明租户会话：裸查在 RLS 下恒 0 行 → attempt 恒为 1
        //    （同一根因：rag_ingest_job 未设租户会话读不到任何行）。
        int nextAttempt = transactionTemplate.execute(status -> {
            jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                    String.class, tenantId);
            if (previousJobId == null || previousJobId.isBlank()) {
                return 1;
            }
            Integer prev = jdbcTemplate.query(
                    "SELECT attempt FROM rag_ingest_job WHERE job_id = ?::uuid",
                    (rs, i) -> rs.getInt(1), previousJobId).stream().findFirst().orElse(null);
            return (prev == null ? 0 : prev) + 1;
        });
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
    public @Nullable IngestJob claimNextJobForTenant(@NonNull String tenantId) {
        return transactionTemplate.execute(status -> {
            // ⚠️ RLS：必须先声明租户会话，否则 SELECT 被策略过滤成 0 行
            //    （2026-11-03 实测：无租户会话 visible_jobs=0，任务静默积压）。
            //    必须用 queryForObject 而非 update：set_config 是 SELECT、返回一行，
            //    update() 期望「无结果集」，否则抛 DataIntegrityViolation（实测 500）。
            jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                    String.class, tenantId);
            // ⚠️⚠️ SKIP LOCKED 是本类的核心正确性来源：
            //    多 worker 并发轮询时各领一条、互不阻塞、绝不重复。
            //    ⚠️ tenant_id 谓词与 RLS 策略同向（双保险）：隔离由 RLS 保证，
            //    应用层谓词只是把「领哪一桶」写死，不承担隔离职责。
            String jobId = jdbcTemplate.query("""
                            SELECT job_id::text FROM rag_ingest_job
                             WHERE status = 'PENDING'::rag_ingest_status
                               AND tenant_id = ?::uuid
                             ORDER BY created_at
                             LIMIT 1
                             FOR UPDATE SKIP LOCKED
                            """,
                    (rs, i) -> rs.getString(1), tenantId).stream().findFirst().orElse(null);
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
    public void updateStage(@NonNull String tenantId, @NonNull String jobId, @NonNull String stage) {
        inTenant(tenantId, () -> jdbcTemplate.update(
                "UPDATE rag_ingest_job SET stage = ? WHERE job_id = ?::uuid",
                stage, jobId));
    }

    @Override
    public void advance(@NonNull String tenantId, @NonNull String jobId,
                        @NonNull IngestJobStatus status, @Nullable String stage) {
        inTenant(tenantId, () -> jdbcTemplate.update(
                "UPDATE rag_ingest_job SET status = ?::rag_ingest_status, stage = ?"
                        + " WHERE job_id = ?::uuid",
                status.name(), stage, jobId));
    }

    @Override
    public void markSucceeded(@NonNull String tenantId, @NonNull String jobId, int chunkCount,
                              @NonNull String parserVersion, @NonNull String embedModel) {
        // 旧签名 = 未采集切分留痕（RAG-D26 阶段 3.2 前的行为）
        markSucceeded(tenantId, jobId, chunkCount, parserVersion, embedModel, null, null);
    }

    @Override
    public void markSucceeded(@NonNull String tenantId, @NonNull String jobId, int chunkCount,
                              @NonNull String parserVersion, @NonNull String embedModel,
                              String chunkPolicy, String chunkPolicyConfig) {
        // ⚠️ 留痕列逐项判断：null 不写（保持 NULL）——「未采集」与「空串」是两件事
        //    （13_v1_0_4_chunk_policy_alter.sql 的口径注释）。
        //    SQL 用 StringBuilder 显式拼 SET，参数列表与之一一对应（可审计）。
        StringBuilder sql = new StringBuilder("""
                UPDATE rag_ingest_job
                   SET status = 'SUCCEEDED'::rag_ingest_status,
                       chunk_count = ?,
                       parser_version = ?,
                       embed_model = ?,
                       finished_at = now(),
                       stage = NULL
                 WHERE job_id = ?::uuid
                """);
        java.util.List<Object> setArgs = new java.util.ArrayList<>();
        setArgs.add(chunkCount);
        setArgs.add(parserVersion);
        setArgs.add(embedModel);
        // WHERE 前的动态 SET 必须拼在 WHERE 之前——用占位替换而不是字符串尾插
        String base = sql.toString();
        String setPrefix = base.substring(0, base.indexOf(" WHERE"));
        String wherePart = base.substring(base.indexOf(" WHERE"));
        StringBuilder setSql = new StringBuilder(setPrefix);
        if (chunkPolicy != null) {
            setSql.append(",\n                       chunk_policy = ?");
            setArgs.add(chunkPolicy);
        }
        if (chunkPolicyConfig != null) {
            setSql.append(",\n                       chunk_policy_config = ?");
            setArgs.add(chunkPolicyConfig);
        }
        String finalSql = setSql + wherePart;
        inTenant(tenantId, () -> jdbcTemplate.update(finalSql, setArgs.toArray()));
    }

    @Override
    public void markFailed(@NonNull String tenantId, @NonNull String jobId,
                           @NonNull String errorDetail) {
        // ⚠️ error_detail 必填：没有原因的 FAILED 在录入工作台上无法处置（见 IngestJob 不变式）。
        inTenant(tenantId, () -> jdbcTemplate.update("""
                        UPDATE rag_ingest_job
                           SET status = 'FAILED'::rag_ingest_status,
                               error_detail = ?,
                               finished_at = now(),
                               stage = NULL
                         WHERE job_id = ?::uuid
                        """, errorDetail, jobId));
    }

    // ---------------------------------------------------------------------
    // 平台档摄入：全局桶 job 领取 / 回写（G1 / RAG-D11 §1.3）
    // 全部走运维会话（rag_ops，BYPASSRLS），谓词写死全局哨兵租户。
    // ---------------------------------------------------------------------

    @Nullable
    @Override
    public IngestJob claimNextGlobalJob() {
        if (opsSession == null) {
            throw new RagOpsUnavailableException("rag.ops.datasource",
                    "平台全局摄取消费需要运维数据源（rag_ops），不回落应用角色");
        }
        return opsSession.inGlobalCorpusSession((opsTemplate, opsTx) -> opsTx.execute(status -> {
            // ⚠️ 同租户侧 claim：FOR UPDATE SKIP LOCKED 保多 worker 不重复领。
            //    tenant_id 谓词写死全局哨兵（方法不接收租户参数，杜绝越权领桶）。
            String jobId = opsTemplate.query("""
                            SELECT job_id::text FROM rag_ingest_job
                             WHERE status = 'PENDING'::rag_ingest_status
                               AND tenant_id = rag_global_tenant_id()
                             ORDER BY created_at
                             LIMIT 1
                             FOR UPDATE SKIP LOCKED
                            """, (rs, i) -> rs.getString(1)).stream().findFirst().orElse(null);
            if (jobId == null) {
                return null;
            }
            // ⚠️ 置 PARSING + 首次 started_at（与租户侧语义一致）。
            opsTemplate.update("""
                    UPDATE rag_ingest_job
                       SET status = 'PARSING'::rag_ingest_status,
                           started_at = COALESCE(started_at, now())
                     WHERE job_id = ?::uuid
                    """, jobId);
            // ⚠️ 读回必须用同一运维会话（不能用主连接 jdbcTemplate：应用角色读不到全局行）。
            List<IngestJob> rows = opsTemplate.query(
                    "SELECT " + COLUMNS + " FROM rag_ingest_job WHERE job_id = ?::uuid",
                    this::mapJob, jobId);
            return rows.isEmpty() ? null : rows.get(0);
        }));
    }

    @Override
    public void markSucceededGlobal(@NonNull String jobId, int chunkCount,
                                    @NonNull String parserVersion, @NonNull String embedModel) {
        // 旧签名 = 未采集切分留痕（RAG-D26 阶段 3.2 前的行为）
        markSucceededGlobal(jobId, chunkCount, parserVersion, embedModel, null, null);
    }

    @Override
    public void markSucceededGlobal(@NonNull String jobId, int chunkCount,
                                    @NonNull String parserVersion, @NonNull String embedModel,
                                    String chunkPolicy, String chunkPolicyConfig) {
        if (opsSession == null) {
            throw new RagOpsUnavailableException("rag.ops.datasource",
                    "平台全局摄取回写需要运维数据源（rag_ops），不回落应用角色");
        }
        // ⚠️ 留痕列逐项判断（同租户侧 markSucceeded 的口径与注释）。
        String base = """
                        UPDATE rag_ingest_job
                           SET status = 'SUCCEEDED'::rag_ingest_status,
                               chunk_count = ?,
                               parser_version = ?,
                               embed_model = ?,
                               finished_at = now(),
                               stage = NULL
                         WHERE job_id = ?::uuid
                           AND tenant_id = rag_global_tenant_id()
                        """;
        String setPrefix = base.substring(0, base.indexOf(" WHERE"));
        String wherePart = base.substring(base.indexOf(" WHERE"));
        StringBuilder setSql = new StringBuilder(setPrefix);
        java.util.List<Object> setArgs = new java.util.ArrayList<>();
        setArgs.add(chunkCount);
        setArgs.add(parserVersion);
        setArgs.add(embedModel);
        if (chunkPolicy != null) {
            setSql.append(",\n                               chunk_policy = ?");
            setArgs.add(chunkPolicy);
        }
        if (chunkPolicyConfig != null) {
            setSql.append(",\n                               chunk_policy_config = ?");
            setArgs.add(chunkPolicyConfig);
        }
        String finalSql = setSql + wherePart;
        opsSession.inGlobalCorpusSession((opsTemplate, opsTx) -> opsTx.execute(status -> {
            // ⚠️ AND tenant_id = rag_global_tenant_id() 承重（非冗余）：BYPASSRLS 下无 RLS 兜底，
            //    去掉它一个传错的 job_id 会改到别的租户的 job。
            opsTemplate.update(finalSql, setArgs.toArray());
            return null;
        }));
    }

    @Override
    public void updateStageGlobal(@NonNull String jobId, @NonNull String stage) {
        if (opsSession == null) {
            throw new RagOpsUnavailableException("rag.ops.datasource",
                    "平台全局摄取回写需要运维数据源（rag_ops），不回落应用角色");
        }
        opsSession.inGlobalCorpusSession((opsTemplate, opsTx) -> opsTx.execute(status -> {
            opsTemplate.update("""
                            UPDATE rag_ingest_job SET stage = ?
                             WHERE job_id = ?::uuid
                               AND tenant_id = rag_global_tenant_id()
                            """, stage, jobId);
            return null;
        }));
    }

    @Override
    public void advanceGlobal(@NonNull String jobId,
                              @NonNull IngestJobStatus status, @Nullable String stage) {
        if (opsSession == null) {
            throw new RagOpsUnavailableException("rag.ops.datasource",
                    "平台全局摄取回写需要运维数据源（rag_ops），不回落应用角色");
        }
        // ⚠️ lambda 的事务状态参数改名 txStatus，避免与上面的 IngestJobStatus status 重名遮蔽。
        opsSession.inGlobalCorpusSession((opsTemplate, opsTx) -> opsTx.execute(txStatus -> {
            opsTemplate.update("""
                            UPDATE rag_ingest_job
                               SET status = ?::rag_ingest_status, stage = ?
                             WHERE job_id = ?::uuid
                               AND tenant_id = rag_global_tenant_id()
                            """, status.name(), stage, jobId);
            return null;
        }));
    }

    @Override
    public void markFailedGlobal(@NonNull String jobId, @NonNull String errorDetail) {
        if (opsSession == null) {
            throw new RagOpsUnavailableException("rag.ops.datasource",
                    "平台全局摄取回写需要运维数据源（rag_ops），不回落应用角色");
        }
        opsSession.inGlobalCorpusSession((opsTemplate, opsTx) -> opsTx.execute(status -> {
            // ⚠️ error_detail 必填（同租户侧 markFailed）：故障现场不可消失。
            // ⚠️ AND tenant_id = rag_global_tenant_id() 承重（见 markSucceededGlobal）。
            opsTemplate.update("""
                            UPDATE rag_ingest_job
                               SET status = 'FAILED'::rag_ingest_status,
                                   error_detail = ?,
                                   finished_at = now(),
                                   stage = NULL
                             WHERE job_id = ?::uuid
                               AND tenant_id = rag_global_tenant_id()
                            """, errorDetail, jobId);
            return null;
        }));
    }

    @Override
    public int failTimedOutRunningGlobalJobs(int timeoutMinutes) {
        if (timeoutMinutes <= 0) {
            throw new IllegalArgumentException("timeoutMinutes 必须 > 0");
        }
        if (opsSession == null) {
            throw new RagOpsUnavailableException("rag.ops.datasource",
                    "平台全局摄取 Sweeper 需要运维数据源（rag_ops），不回落应用角色");
        }
        return opsSession.inGlobalCorpusSession((opsTemplate, opsTx) -> opsTx.execute(status ->
                opsTemplate.update("""
                        UPDATE rag_ingest_job
                           SET status = 'FAILED'::rag_ingest_status,
                               error_detail = 'SWEEPER_TIMEOUT: 超过 ' || ? || ' 分钟未完成，被 Sweeper 接管',
                               finished_at = now(),
                               stage = NULL
                         WHERE tenant_id = rag_global_tenant_id()
                           AND status IN ('PARSING'::rag_ingest_status,
                                          'CHUNKING'::rag_ingest_status,
                                          'EMBEDDING'::rag_ingest_status,
                                          'INDEXING'::rag_ingest_status)
                           AND started_at < now() - make_interval(mins => ?)
                        """, timeoutMinutes, timeoutMinutes)));
    }

    /**
     * 在租户会话内执行一条写操作。
     *
     * <p>⚠️⚠️ 为什么每个 mutator 都必须包一层：
     * {@code rag_ingest_job} 的 RLS 策略是 {@code tenant_id = rag_current_tenant()}，
     * <b>未设租户会话的 UPDATE 会被静默变成 0 行</b>（2026-11-03 实测：
     * 无租户 UPDATE 0 / 设租户 UPDATE 1）——不报错、不留痕，
     * 于是「进度回写」与「失败原因」双双消失，任务永远停在中间态。
     *
     * <p>⚠️ 必须用 {@code queryForObject} 执行 set_config：它是 SELECT、返回一行，
     * {@code update()} 期望「无结果集」，否则抛 DataIntegrityViolation（实测 500）。
     * ⚠️ {@code set_config(..., true)} 是<b>事务局部</b>，故必须在事务内执行。
     */
    private void inTenant(String tenantId, Runnable action) {
        transactionTemplate.execute(status -> {
            jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                    String.class, tenantId);
            action.run();
            return null;
        });
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
    public int failTimedOutRunningJobs(@NonNull String tenantId, int timeoutMinutes) {
        if (timeoutMinutes <= 0) {
            throw new IllegalArgumentException("timeoutMinutes 必须 > 0");
        }
        // ⚠️⚠️ Sweeper 是跨租户动作，但**不能**用无租户会话的全表 UPDATE：
        //    RLS 会把它静默变成 0 行（实测），僵尸任务永远不被接管。
        //    故按租户逐个执行，隔离仍由 RLS 策略保证。
        return transactionTemplate.execute(status -> {
            jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                    String.class, tenantId);
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
        });
    }

    @Override
    public @Nullable IngestJob findById(@NonNull String jobId) {
        List<IngestJob> rows = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM rag_ingest_job WHERE job_id = ?::uuid",
                this::mapJob, jobId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public @Nullable IngestJob findByIdForTenant(@NonNull String tenantId, @NonNull String jobId) {
        return transactionTemplate.execute(status -> {
            // ⚠️ RLS：裸查（findById）不声明租户会话，在应用角色下恒 0 行 →
            //    重试端点对真实存在的 job 报 404。必须先声明租户会话再查。
            // ⚠️ 必须用 queryForObject 而非 update：set_config 是 SELECT、返回一行。
            jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                    String.class, tenantId);
            // ⚠️ tenant_id 同时入 WHERE：就算会话被污染也不跨租户读行（纵深防御）。
            List<IngestJob> rows = jdbcTemplate.query(
                    "SELECT " + COLUMNS + " FROM rag_ingest_job"
                            + " WHERE job_id = ?::uuid AND tenant_id = ?::uuid",
                    this::mapJob, jobId, tenantId);
            return rows.isEmpty() ? null : rows.get(0);
        });
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
