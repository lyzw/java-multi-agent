package cn.studykid.rag.core.ingest;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 摄取任务（{@code rag_ingest_job} 的领域视图）。
 *
 * <p><b>⚠️ job 是「一次处理尝试」的事实记录，不是「一个载体」的状态</b>：
 * 重试会<b>新建一行</b>（{@code attempt} + 1），旧行永久留痕、绝不覆盖。
 * 因此「某载体当前处理到哪」应看<b>该载体最新一条 job</b>，
 * 「历史上重试过几次」应看 job 行数——两个问题不能混答。
 *
 * <p><b>⚠️ job 状态与来源校验状态正交</b>（方案 §3.2）：
 * job FAILED 不改变来源的 {@code PENDING/IN_REVIEW}；自动化永远不写
 * {@code VERIFIED}。两层各自演进，不得互相牵连。
 *
 * @param jobId         任务 ID
 * @param tenantId      租户 ID
 * @param sourceId      所属来源 ID
 * @param status        当前状态
 * @param stage         阶段内进度（如 {@code "PARSING 60"}），可为 null
 * @param attempt       第几次尝试（从 0 起）
 * @param createdBy     触发人（去哨兵后为真实主体），可为 null（历史数据）
 * @param parserVersion 解析器版本（可复现性留痕），可为 null
 * @param embedModel    嵌入模型标识，可为 null
 * @param chunkCount    产出的片段数，未完成时可为 null
 * @param errorDetail   失败/取消原因，{@code FAILED} 时必填
 * @param startedAt     开始时间（未被领取时为 null）
 * @param finishedAt    结束时间（终态时必填）
 * @param createdAt     建单时间
 */
public record IngestJob(
        @NonNull String jobId,
        @NonNull String tenantId,
        @NonNull String sourceId,
        @NonNull IngestJobStatus status,
        @Nullable String stage,
        int attempt,
        @Nullable String createdBy,
        @Nullable String parserVersion,
        @Nullable String embedModel,
        @Nullable Integer chunkCount,
        @Nullable String errorDetail,
        @Nullable String startedAt,
        @Nullable String finishedAt,
        @NonNull String createdAt
) {

    /**
     * 紧凑构造器：锁住「失败必有原因」这条不变式（方案 §2.2 / §7.1）。
     *
     * <p>⚠️ 这不是形式校验：没有 {@code error_detail} 的 FAILED job
     * 在录入工作台上是一块无法处置的死数据，运维只能猜。
     */
    public IngestJob {
        if (jobId == null || jobId.isBlank()) {
            throw new IllegalArgumentException("jobId 不能为空");
        }
        if (sourceId == null || sourceId.isBlank()) {
            throw new IllegalArgumentException("sourceId 不能为空");
        }
        if (status == null) {
            throw new IllegalArgumentException("status 不能为空");
        }
        if (status == IngestJobStatus.FAILED
                && (errorDetail == null || errorDetail.isBlank())) {
            throw new IllegalArgumentException("FAILED 的 job 必须带 errorDetail");
        }
        if (status.isTerminal() && (finishedAt == null || finishedAt.isBlank())) {
            throw new IllegalArgumentException("终态 job 必须带 finishedAt");
        }
    }

    /**
     * 是否为「来源撤回导致的取消」（统计失败率时须剔除）。
     *
     * @return 是撤回取消返回 true
     */
    public boolean isSourceRevokedCancel() {
        return status == IngestJobStatus.REJECTED
                && IngestJobStatus.REASON_SOURCE_REVOKED.equals(errorDetail);
    }

    /**
     * 是否计入失败率分母之外的「真实故障」。
     *
     * <p>⚠️ 撤回取消（{@link #isSourceRevokedCancel()}）不计入：它是治理动作的正常结果，
     * 计入会让「法务撤回一批教材」直接触发失败率告警（方案 §7.1 / N2）。
     *
     * @return 计入失败统计返回 true
     */
    public boolean countsAsFailure() {
        return status == IngestJobStatus.FAILED;
    }
}
