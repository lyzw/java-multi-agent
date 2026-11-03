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
     * 领取下一条待处理 job（多 worker 安全）。
     *
     * <p>⚠️ 实现必须用 {@code SELECT ... FOR UPDATE SKIP LOCKED LIMIT 1}：
     * 两个 worker 同时轮询时各领一条、互不阻塞，也不会领到同一条。
     *
     * @return 领取到的 job（已置为 {@link IngestJobStatus#PARSING} 且写入 started_at），
     *         无待处理任务返回 {@code null}
     */
    @Nullable IngestJob claimNextJob();

    /**
     * 回写阶段进度。
     *
     * @param jobId 任务 ID
     * @param stage 阶段内进度描述（如 {@code "PARSING 60"}）
     */
    void updateStage(@NonNull String jobId, @NonNull String stage);

    /**
     * 推进状态（不改变 started_at）。
     *
     * @param jobId   任务 ID
     * @param status  目标状态
     * @param stage   阶段内进度，可为 null
     */
    void advance(@NonNull String jobId, @NonNull IngestJobStatus status, @Nullable String stage);

    /**
     * 标记成功。
     *
     * @param jobId      任务 ID
     * @param chunkCount 产出片段数
     * @param parserVersion 解析器版本
     * @param embedModel 嵌入模型标识
     */
    void markSucceeded(@NonNull String jobId, int chunkCount,
                       @NonNull String parserVersion, @NonNull String embedModel);

    /**
     * 标记失败（{@code error_detail} 必填）。
     *
     * @param jobId       任务 ID
     * @param errorDetail 失败原因
     */
    void markFailed(@NonNull String jobId, @NonNull String errorDetail);

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
     * @param timeoutMinutes 超时阈值（分钟）
     * @return 被接管的 job 数量
     */
    int failTimedOutRunningJobs(int timeoutMinutes);

    /**
     * 查询单个 job。
     *
     * @param jobId 任务 ID
     * @return job；不存在返回 {@code null}
     */
    @Nullable IngestJob findById(@NonNull String jobId);

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
