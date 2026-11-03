package cn.studykid.rag.ports;

import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.rag.core.ingest.IngestJob;
import cn.studykid.rag.core.ingest.IngestJobStatus;

/**
 * 摄取任务存储端口（{@code rag_ingest_job}）。
 *
 * <p><b>⚠️ 为什么放在 {@code rag-ports} 而不是 {@code rag-management}</b>：
 * 本端口被<b>管理面</b>（建单 / 列表 / 重试）与 <b>worker</b>（领取 / 回写 / Sweeper）
 * 两侧共同消费，而 {@code rag-worker} <b>不依赖</b> {@code rag-management}。
 * 契约下沉到 ports 层，实现（Jdbc）放在 {@code rag-infrastructure}，
 * 两侧只依赖契约——与 {@code ChunkIndexer} 下沉的理由同源（口径只有一处）。
 *
 * <p><b>⚠️ 并发契约</b>：{@link #claimNextJob} 必须以
 * {@code FOR UPDATE SKIP LOCKED} 实现，保证多 worker 不重复领同一 job。
 * 该语义无法用应用层锁替代（跨进程）。
 */
public interface IngestJobPort {

    /**
     * 建单（上传成功后调用）。
     *
     * @param tenantId  租户 ID
     * @param sourceId  来源 ID
     * @param createdBy 触发人主体 ID
     * @return 新建的 job
     */
    @NonNull IngestJob createJob(@NonNull String tenantId,
                                 @NonNull String sourceId,
                                 @NonNull String createdBy);

    /**
     * 领取下一条待处理 job（多 worker 安全，<b>按租户</b>领取）。
     *
     * <p>⚠️ 实现必须用 {@code SELECT ... FOR UPDATE SKIP LOCKED LIMIT 1}：
     * 两个 worker 同时轮询时各领一条、互不阻塞，也不会领到同一条。
     *
     * <p>⚠️⚠️ 领取<b>必须逐租户做</b>，不可跨租户扫全表：
     * Worker 以「非特权 + 无 BYPASSRLS」的应用角色连接，
     * {@code rag_ingest_job} 的 RLS 策略是 {@code tenant_id = rag_current_tenant()}，
     * <b>未设租户的会话读不到任何行</b>（2026-11-03 实测：同一张表
     * 「无租户会话 visible_jobs=0 / 运维通道 visible_jobs=3」，
     * 于是 worker 永远领不到任务、任务静默积压）。
     * 跨租户领取须走运维通道（BYPASSRLS），那是另一条评审通道；
     * 故此处由调用方按 {@code rag.indexer.tenants} 显式给出租户，
     * 领取在事务内 {@code set_config('app.tenant_id', ...)} 后执行——
     * <b>由 RLS 策略本身做隔离</b>，不依赖应用层过滤兜底。
     *
     * @param tenantId 租户 ID（须为该 worker 被授权处理的租户）
     * @return 领取到的 job（已置为 {@link IngestJobStatus#PARSING} 且写入 started_at），
     *         无待处理任务返回 {@code null}
     */
    @Nullable IngestJob claimNextJobForTenant(@NonNull String tenantId);

    /**
     * 回写阶段进度。
     *
     * <p>⚠️⚠️ 必须带 {@code tenantId}：{@code rag_ingest_job} 的 RLS 策略是
     * {@code tenant_id = rag_current_tenant()}，**未设租户会话的 UPDATE 会被静默
     * 变成 0 行**（2026-11-03 实测：无租户 UPDATE 0 / 设租户 UPDATE 1）——
     * 失败与进度都会「写不进去且不报错」，任务永远停在中间态。
     *
     * @param tenantId 租户 ID
     * @param jobId 任务 ID
     * @param stage 阶段内进度描述（如 {@code "PARSING 60"}）
     */
    void updateStage(@NonNull String tenantId, @NonNull String jobId, @NonNull String stage);

    /**
     * 推进状态（不改变 started_at）。
     *
     * <p>⚠️ 同 {@link #updateStage}：必须带租户会话，否则状态推进被 RLS 静默吞掉。
     *
     * @param tenantId 租户 ID
     * @param jobId   任务 ID
     * @param status  目标状态
     * @param stage   阶段内进度，可为 null
     */
    void advance(@NonNull String tenantId, @NonNull String jobId,
                 @NonNull IngestJobStatus status, @Nullable String stage);

    /**
     * 标记成功。
     *
     * <p>⚠️ 同 {@link #updateStage}：必须带租户会话，否则成功态写不进去。
     *
     * @param tenantId 租户 ID
     * @param jobId      任务 ID
     * @param chunkCount 产出片段数
     * @param parserVersion 解析器版本
     * @param embedModel 嵌入模型标识
     */
    void markSucceeded(@NonNull String tenantId, @NonNull String jobId, int chunkCount,
                       @NonNull String parserVersion, @NonNull String embedModel);

    /**
     * 标记失败（{@code error_detail} 必填）。
     *
     * <p>⚠️ 同 {@link #updateStage}：必须带租户会话，否则**失败原因写不进去**
     * ——故障现场直接消失（2026-11-03 实测：解析失败的任务停在 PARSING、stage 为空、
     * error_detail 为空，表面看像「还在跑」）。
     *
     * @param tenantId 租户 ID
     * @param jobId       任务 ID
     * @param errorDetail 失败原因
     */
    void markFailed(@NonNull String tenantId, @NonNull String jobId, @NonNull String errorDetail);

    /**
     * 按来源级联取消未完成的 job（来源 REVOKED 时调用）。
     *
     * <p>⚠️ 置为 {@link IngestJobStatus#REJECTED} +
     * {@link IngestJobStatus#REASON_SOURCE_REVOKED}，<b>不新增枚举值</b>（方案 P5/N2）。
     *
     * @param tenantId 租户 ID
     * @param sourceId 来源 ID
     * @return 被取消的 job 数量
     */
    int cancelUnfinishedBySource(@NonNull String tenantId, @NonNull String sourceId);

    /**
     * Sweeper：把超时仍在「进行中」的 job 置为 FAILED（防僵尸）。
     *
     * <p>⚠️ 必须带 {@code tenantId}：Sweeper 是跨租户动作，但**不得**用无租户会话的
     * 全表 UPDATE（RLS 会让它静默 0 行，僵尸任务永远不被接管）；
     * 故按租户逐个执行，隔离仍由 RLS 策略保证。
     *
     * @param tenantId 租户 ID
     * @param timeoutMinutes 超时阈值（分钟）
     * @return 被接管的 job 数量
     */
    int failTimedOutRunningJobs(@NonNull String tenantId, int timeoutMinutes);

    /**
     * 查询单个 job。
     *
     * @param jobId 任务 ID
     * @return job；不存在返回 {@code null}
     */
    @Nullable IngestJob findById(@NonNull String jobId);

    /**
     * 查询单个 job（<b>租户会话版</b>）。
     *
     * <p>⚠️⚠️ 管理面（租户档）<b>必须</b>用本方法而不是 {@link #findById}：
     * {@code rag_ingest_job} 的 RLS 策略是 {@code tenant_id = rag_current_tenant()}，
     * {@link #findById} 是裸查询、不声明租户会话，在应用角色（无 BYPASSRLS）下
     * <b>恒读 0 行</b>——重试端点据此 404「摄取任务不存在」（2026-11-03 实测：
     * 列表端点能看到 3 条 SUCCEEDED job，同一请求的重试端点却 404）。
     * 实现必须在事务内先 {@code set_config('app.tenant_id', ?, true)} 再查。
     *
     * @param tenantId 租户 ID
     * @param jobId 任务 ID
     * @return job；不存在或<b>不属于该租户</b>时返回 {@code null}（跨租户探测即 404，语义等价）
     */
    @Nullable IngestJob findByIdForTenant(@NonNull String tenantId, @NonNull String jobId);

    /**
     * 列出某来源下的 job（新→旧）。
     *
     * @param sourceId 来源 ID
     * @param limit    条数上限
     * @return job 列表
     */
    @NonNull List<IngestJob> listBySource(@NonNull String sourceId, int limit);

    /**
     * 列出某租户最近的 job（录入工作台「我的任务列表」用）。
     *
     * @param tenantId 租户 ID
     * @param limit    条数上限
     * @return job 列表（新→旧）
     */
    @NonNull List<IngestJob> listRecentByTenant(@NonNull String tenantId, int limit);

    /**
     * 读取摄取链健康快照（跨租户汇总，供 worker 侧的告警评估使用）。
     *
     * @return 健康快照
     */
    IngestHealth health();

    /**
     * 人工重试：为同一来源新建一条 job（旧行留痕不覆盖）。
     *
     * @param tenantId  租户 ID
     * @param sourceId  来源 ID
     * @param createdBy 触发人
     * @param previousJobId 被重试的原 job（用于 attempt 递增），可为 null
     * @return 新建的 job
     */
    @NonNull IngestJob createRetryJob(@NonNull String tenantId,
                                      @NonNull String sourceId,
                                      @NonNull String createdBy,
                                      @Nullable String previousJobId);
}
