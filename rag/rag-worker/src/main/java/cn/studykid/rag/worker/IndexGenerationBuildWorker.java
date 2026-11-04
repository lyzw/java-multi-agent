package cn.studykid.rag.worker;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import cn.studykid.rag.core.indexing.IndexGeneration;
import cn.studykid.rag.core.scope.RagScope;
import cn.studykid.rag.infrastructure.indexing.IndexGenerationBuilder;
import cn.studykid.rag.ports.IndexGenerationPort;

/**
 * 索引代际构建器（异步，RAG-D13 §3.5.2 / RAG-D21 §六 P1）。
 *
 * <p><b>这一层解决什么</b>：改造前 `POST /index-generations/{g}/build` 在 HTTP 线程上
 * 一次性跑完全部片段——数万片段会阻塞数十分钟，且进程重启后进度丢失、无法续跑。
 * 现在改为「worker 轮询 + 分批推进」：
 * <pre>
 *   HTTP 端点：只创建 BUILDING 代际，立即返回
 *   worker  ：每 2 秒问一次「有没有待构建的代际」→ 处理一批 → 释放
 * </pre>
 *
 * <p><b>⚠️ 三条关键纪律</b>：
 * <ol>
 *   <li><b>{@code fixedDelay} 而非 {@code fixedRate}</b>：一批可能跑数秒到数分钟，
 *       用 fixedRate 会让下一轮触发追赶上一轮，堆积并发线程；</li>
 *   <li><b>异常绝不让线程死掉</b>：单批失败只记日志并跳过，下一轮重试——
 *       否则一次数据库抖动会永久停止所有代际构建（且表面无异常）；</li>
 *   <li><b>租户清单由配置显式给出</b>：worker 以非特权角色连接，
 *       {@code rag_index_generation} 受 RLS FORCE 约束，未设会话租户读不到任何行。
 *       跨租户扫描会「看起来在跑、实际永远领不到」（与 {@link IngestJobConsumer} 同口径）。</li>
 *   <li><b>全局桶走独立通道</b>：清单里出现全局哨兵时调 {@code buildGlobalBatch}，
 *       其写连接是 BYPASSRLS 的运维角色且不设会话租户——
 *       与租户通道共用同一套 fail-closed 口径，只换"在哪条连接上跑"。</li>
 * </ol>
 *
 * <p><b>⚠️ 为什么没有「分布式锁」</b>：多 worker 实例可能同时捡到同一个代际。
 * 目前**依赖幂等**——{@code upsertEmbeddingForGeneration} 是
 * {@code ON CONFLICT DO UPDATE}，重复嵌入同一片段只是浪费算力，不会产生错误数据。
 * 代价是算力浪费，收益是不引入额外中间件（与 RAG-D02「零新增中间件」一致）。
 * 若后续 worker 多副本且算力敏感，应改为「按代际号取模分片」而非加锁。
 */
@Component
public class IndexGenerationBuildWorker {

    private static final Logger log = LoggerFactory.getLogger(IndexGenerationBuildWorker.class);

    private final IndexGenerationPort generationPort;
    private final RagIndexerProperties properties;
    /**
     * 代际构建器。
     *
     * <p>⚠️ 可空：它需要 {@link cn.studykid.rag.ports.EmbeddingPort} 与
     * {@link cn.studykid.rag.ports.CorpusRepository}，某些部署（如只跑摄取、不跑索引的 worker）
     * 可能未装配。缺席时本组件静默待命，不影响摄取任务消费。
     */
    private final IndexGenerationBuilder generationBuilder;

    public IndexGenerationBuildWorker(IndexGenerationPort generationPort,
                                      RagIndexerProperties properties,
                                      @Autowired(required = false)
                                      IndexGenerationBuilder generationBuilder) {
        this.generationPort = generationPort;
        this.properties = properties;
        this.generationBuilder = generationBuilder;
    }

    /**
     * 每轮推进一个批次。
     *
     * <p>⚠️ {@code initialDelay} 比摄取消费更长（10 秒）：
     * 启动瞬间数据库可能还在预热，代际构建不是高优先级任务，不该与摄取抢连接。
     */
    @Scheduled(fixedDelayString = "${rag.generation.build.poll-interval-ms:2000}",
            initialDelayString = "10000")
    public void buildOnce() {
        if (generationBuilder == null) {
            return; // 未装配构建器：静默待命，不刷屏
        }
        for (String raw : safeTenants()) {
            String tenant = raw == null ? "" : raw.trim();
            if (tenant.isEmpty()) {
                continue;
            }
            if (RagScope.isGlobalTenant(tenant)) {
                // ⚠️ 全局桶走**独立通道**（buildGlobalBatch）：
                //    它的写连接必须是 BYPASSRLS 的运维角色，且**不设会话租户**——
                //    把哨兵填进 app.tenant_id 会让 rag_current_tenant() 直接抛错
                //    （V8 封堵「租户伪装成平台身份」，与 ChunkIndexer#indexGlobalCorpus 同款）。
                buildGlobalOnce();
                continue;
            }
            try {
                buildOneTenant(tenant);
            } catch (RuntimeException e) {
                // ⚠️ 单租户失败不能终止整个调度（否则一次抖动永久停掉所有构建）。
                log.error("[RAG] 代际构建异常 tenant={}（下一轮重试）", tenant, e);
            }
        }
    }

    /** 推进单个租户的一批。 */
    private void buildOneTenant(String tenant) {
        IndexGeneration pending = generationPort.findPendingBuild(tenant);
        if (pending == null) {
            return; // 无事可做是常态，不打日志（否则每 2 秒一条噪声）
        }
        IndexGenerationBuilder.BatchOutcome outcome =
                generationBuilder.buildBatch(tenant, pending.generation());
        if (outcome.processed() > 0) {
            log.info("[RAG] 代际构建推进 tenant={} generation={} batch={} finished={}",
                    tenant, pending.generation(), outcome.processed(), outcome.finished());
        }
    }

    /**
     * 推进全局桶的一个批次。
     *
     * <p>⚠️ 运维通道未接入时抛 {@code RagOpsUnavailableException}，
     * 由外层 catch 记日志并继续——全局桶构建缺失是**可控降级**，
     * 不是让整个 worker 停摆的理由（与 {@code ReindexRunner} 同一取舍）。
     */
    private void buildGlobalOnce() {
        String globalTenant = RagScope.GLOBAL_TENANT_ID;
        IndexGeneration pending = generationPort.findPendingBuild(globalTenant);
        if (pending == null) {
            return;
        }
        IndexGenerationBuilder.BatchOutcome outcome =
                generationBuilder.buildGlobalBatch(pending.generation());
        if (outcome.processed() > 0) {
            log.info("[RAG] 全局代际构建推进 generation={} batch={} finished={}",
                    pending.generation(), outcome.processed(), outcome.finished());
        }
    }

    private List<String> safeTenants() {
        List<String> tenants = properties.getTenants();
        return tenants == null ? List.of() : tenants;
    }
}
