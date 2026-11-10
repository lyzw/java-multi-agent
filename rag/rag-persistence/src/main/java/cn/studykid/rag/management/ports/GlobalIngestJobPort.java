package cn.studykid.rag.management.ports;

import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.rag.core.ingest.IngestJob;

/**
 * 平台全局桶的摄取任务建单（rag_ops 运维通道）。
 *
 * <p><b>为什么不能复用 {@code IngestJobPort#createJob(tenantId, ...)}</b>：
 * {@code rag_ingest_job} 的策略是 {@code tenant_id = rag_current_tenant()}
 * （仅 USING/WITH CHECK，无 {@code scope='TENANT'} 限制），
 * 但平台档的<b>会话租户按设计置空</b>——把全局哨兵写进 {@code app.tenant_id}
 * 会触发 {@code rag_current_tenant()} 的越权封堵（SQLSTATE 42501）。
 * 于是「用应用连接建全局 job」必然被策略拒绝。
 *
 * <p>与 {@code RagOpsSessionPort} 同一条纪律：<b>方法签名里不存在租户参数</b>。
 * 全局行的租户列在 SQL 文本里写死为 {@code rag_global_tenant_id()}，
 * 调用方连「往某个租户桶里建单」这句话都拼不出来。
 *
 * <p>⚠️ 通道未接入时抛 {@code RagOpsUnavailableException}（呈现 503），
 * <b>绝不</b>回落到应用角色——回落的结果是「载体已落库、job 建不出、日志里一条 500」。
 */
public interface GlobalIngestJobPort {

    /**
     * 在全局桶内建一条 PENDING 摄取任务。
     *
     * @param sourceId    所属来源（必须已存在于全局桶）
     * @param createdBy   建单人主体 ID
     * @return 新建的 job
     */
    @NonNull
    IngestJob createGlobalJob(@NonNull String sourceId, @NonNull String createdBy);

    /**
     * 列出<b>全局桶</b>最近的摄取任务（管理面平台档「摄取任务」页用）。
     *
     * <p><b>⚠️ 为什么必须走运维通道</b>：{@code rag_ingest_job} 的 RLS 策略是
     * {@code tenant_id = rag_current_tenant()}，而平台档的会话租户按设计<b>置空</b>，
     * 应用角色 {@code rag_app} 因此对全局行恒读 0 行。用应用连接查只会得到
     * 「暂无任务」这种静默失真；拿全局哨兵去 {@code set_config} 更会被
     * {@code rag_current_tenant()} 的越权封堵（SQLSTATE 42501）打成 500。
     *
     * <p><b>⚠️ 方法名里没有租户参数</b>：桶由 SQL 谓词写死为
     * {@code tenant_id = rag_global_tenant_id()}，调用方拼不出「查某个租户的任务」。
     *
     * @param sourceId 可选：按来源过滤；为 {@code null}/空串时返回全局桶最近的任务
     * @param limit    条数上限（实现内部同样会夹到 1~200）
     * @return 任务列表（新→旧）；通道未接入时抛 {@code RagOpsUnavailableException}
     *         由管理面呈现为 503，<b>不</b>回落成空列表
     */
    @NonNull
    List<IngestJob> listGlobalJobs(@Nullable String sourceId, int limit);

    /**
     * 在全局桶内按 ID 读取一条摄取任务（管理面平台档「重试原任务」用）。
     *
     * <p><b>⚠️ 必须走运维会话</b>：{@code rag_ingest_job} 的 RLS 策略是
     * {@code tenant_id = rag_current_tenant()}，平台档会话租户按设计置空，
     * 应用角色对全局行恒读 0 行。复用 {@code IngestJobPort#findById} 会静默失真
     * （对真实存在的全局 job 报「不存在」→ 404），把哨兵写进 {@code app.tenant_id}
     * 又会被 42501 拒绝。故与全局建单/列表同源，谓词写死 {@code rag_global_tenant_id()}。
     *
     * <p>⚠️ 签名里没有租户参数：桶由 SQL 钉死，调用方拼不出「读别的桶」这句话。
     *
     * @param jobId 任务 ID
     * @return 全局 job；不存在时返回 {@code null}（跨桶探测语义等价 404）
     */
    @Nullable
    IngestJob findByIdGlobal(@NonNull String jobId);

    /**
     * 全局桶的人工重试：为同一来源<b>新建一行</b> job（{@code attempt}+1），
     * 旧行永久留痕，绝不原地重置状态。
     *
     * <p><b>⚠️ 与租户侧 {@code createRetryJob} 完全镜像</b>，但整段读 attempt +
     * 写新行在<b>同一运维事务</b>内完成（BYPASSRLS，谓词写死全局哨兵）——
     * 既不回落应用角色（那会对全局行静默 0 行），也不把哨兵塞进会话变量（42501）。
     *
     * <p>⚠️ 通道未接入时抛 {@code RagOpsUnavailableException}，由管理面呈现为 503，
     * <b>绝不</b>返回 null 假装重试成功。
     *
     * @param sourceId      所属来源（全局桶内）
     * @param createdBy     重试操作人主体 ID
     * @param previousJobId 被重试的原 job（用于 attempt 递增）；可为空
     * @return 新建的 PENDING 全局 job
     */
    @NonNull
    IngestJob createRetryGlobalJob(@NonNull String sourceId,
                                   @NonNull String createdBy,
                                   @Nullable String previousJobId);
}
