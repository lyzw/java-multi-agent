package cn.studykid.rag.management.ports;

import org.jspecify.annotations.NonNull;

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
}
