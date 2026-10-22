package cn.studykid.rag.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动即触发重索引（RAG-D09 R2-1）。
 *
 * <p>⚠️ 仅当 {@code rag.indexer.auto-start=true} 时生效——
 *   检索服务（rag-bootstrap）不托管本 bean（其配置默认关闭），
 *   故不会出现「检索服务自启索引」的误用。
 *
 * <p><b>⚠️ 租户枚举约束</b>：受 RLS 约束，Worker 以非特权应用角色连接，
 *   无法跨租户枚举 {@code rag_tenant}。因此<b>待索引租户必须由配置显式给出</b>
 *   （{@code rag.indexer.tenants}）。生产两种更优做法：
 *   <ul>
 *     <li><b>事件驱动</b>：主平台在创建/校验来源后下发索引任务（按租户），
 *         而非 Worker 自行遍历；</li>
 *     <li><b>专用扫描角色</b>：额外配置一个具备 {@code BYPASSRLS} 的只读角色
 *         仅用于「列出租户 + 扫描待索引片段」，<b>写入仍走应用角色</b>
 *         （{@code CorpusRepository} 内部 set_config 保持隔离）。
 *         这是多租户批量处理的标准模式，可在 {@code WorkerInfraConfig} 中扩展。</li>
 *   </ul>
 *
 * <p><b>⚠️ 启动前自检（fail-closed 探针）</b>：在<b>未设置租户</b>的会话下
 *   查询 {@code rag_chunk} 计数，期望为 0。若返回 &gt; 0，说明 RLS FORCE 未生效，
 *   立即中止索引并报错（与 rag-bootstrap 的 {@code RagStartupSelfCheck} 同源纪律）。
 */
@Component
@ConditionalOnProperty(name = "rag.indexer.auto-start", havingValue = "true")
public class ReindexRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ReindexRunner.class);

    private final ChunkIndexer chunkIndexer;
    private final RagIndexerProperties properties;
    private final JdbcTemplate jdbcTemplate;

    public ReindexRunner(ChunkIndexer chunkIndexer, RagIndexerProperties properties,
                         JdbcTemplate jdbcTemplate) {
        this.chunkIndexer = chunkIndexer;
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!failClosedPreflight()) {
            log.error("[reindex] fail-closed 探针失败：未设租户仍可读取 rag_chunk，"
                    + " RLS FORCE 疑似未生效。中止启动重索引以避免跨租户泄漏。");
            return;
        }

        List<String> tenants = properties.getTenants();
        if (tenants == null || tenants.isEmpty()) {
            log.warn("[reindex] rag.indexer.tenants 为空：受 RLS 约束 Worker 不能跨租户枚举，"
                    + " 须在配置中显式列出租户 UUID，跳过启动重索引。"
                    + " 生产建议由主平台下发索引任务（见 ReindexRunner 注释）。");
            return;
        }

        int grandTotal = 0;
        for (String tenant : tenants) {
            String t = tenant.trim();
            if (t.isEmpty()) {
                continue;
            }
            try {
                grandTotal += chunkIndexer.indexTenant(t);
            } catch (RuntimeException ex) {
                log.error("[reindex] 租户 {} 索引失败：{}", t, ex.getMessage(), ex);
            }
        }
        log.info("[reindex] 启动重索引结束，累计处理 {} 个片段", grandTotal);
    }

    /**
     * fail-closed 探针：在<b>未设置租户</b>的会话中查询 {@code rag_chunk} 计数，
     * 期望 0（RLS FORCE 使无租户会话看不到任何行）。
     *
     * <p>⚠️ 本方法由 {@link #run(ApplicationArguments)} 直接调用（self-invocation），
     * 故 {@code @Transactional} 不会生效——这恰是预期：探针本就应在「无租户上下文」
     * 下运行，以验证 RLS FORCE 是否真正拦截了越权读取。
     */
    boolean failClosedPreflight() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM rag_chunk", Integer.class);
        int c = count == null ? 0 : count;
        if (c > 0) {
            log.error("[reindex] fail-closed 探针异常：未设租户仍读到 {} 行 rag_chunk", c);
            return false;
        }
        return true;
    }
}
