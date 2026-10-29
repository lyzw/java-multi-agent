package cn.studykid.rag.worker;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import cn.studykid.rag.application.observability.LogContext;
import cn.studykid.rag.core.scope.RagScope;
import cn.studykid.rag.infrastructure.indexing.ChunkIndexer;

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
 *   查询 {@code rag_chunk}，期望<b>读不到任何租户私有行</b>
 *   （{@code scope <> 'PLATFORM'} 计数为 0）。若 &gt; 0，说明 RLS FORCE 未生效，
 *   立即中止索引并报错（与 rag-bootstrap 的 {@code RagStartupSelfCheck} 同源纪律）。
 *   V8 前这里的表述是「期望 0 行」；全局语料上线后那个判据会把正常库判死，
 *   理由见 {@link #failClosedPreflight()}。
 *
 * <p><b>⚠️ 全局桶是这份列表里可选的一项，但不是租户通道能做的事</b>：
 * 列表里出现全局哨兵租户时，本类走 {@link ChunkIndexer#indexGlobalCorpus}
 * ——一条包在运维数据源会话窗里、桶谓词写死 {@code rag_global_tenant_id()} 的通道。
 * <b>不能</b>把它当普通租户喂给 {@code indexTenant}：{@code set_config} 填哨兵后
 * {@code rag_current_tenant()} 直接抛错（V8 封堵「租户伪装成平台身份」的承重设计），
 * 那会以「数据库异常」的面目出现在启动日志里，看不出是该走哪条通道。
 * 运维通道未接入时该条目得到 503 语义的失败记录并<b>继续</b>处理其余租户——
 * 全局语料索引缺失是可控的降级，不是让整个 worker 起不来的理由。
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
        // 启动重索引跑在 main 线程上（没有 HTTP 过滤器给它写上下文）：
        // 这里补一条系统链路，否则本次启动的全部日志都是 [- - -]，
        // 出问题时既查不到「哪一次启动」也看不出是谁在做。
        try (LogContext.Scope startup = LogContext.push(new LogContext.Snapshot(
                LogContext.newTraceId(), null, LogContext.SYSTEM))) {
            doRun();
        }
    }

    private void doRun() {
        if (!failClosedPreflight()) {
            log.error("[reindex] fail-closed 探针失败：未设租户仍可读取租户私有 rag_chunk，"
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
            try (LogContext.Scope ignored = LogContext.push(new LogContext.Snapshot(
                    LogContext.newTraceId(), t, LogContext.SYSTEM))) {
                // ⚠️ catch 在作用域内部：作用域先于 catch 关闭，
                //    写在外的话「某租户索引失败」这条日志会丢掉租户字段。
                try {
                    // 全局桶换通道，其余照旧：两条通道共用同一份「待索引」口径，
                    // 差别只在连接与桶谓词（见 ChunkIndexer#GLOBAL_PENDING_SQL）。
                    grandTotal += RagScope.isGlobalTenant(t)
                            ? chunkIndexer.indexGlobalCorpus(Integer.MAX_VALUE).indexedChunks()
                            : chunkIndexer.indexTenant(t);
                } catch (RuntimeException ex) {
                    log.error("[reindex] {} 索引失败：{}", t, ex.getMessage(), ex);
                }
            }
        }
        log.info("[reindex] 启动重索引结束，累计处理 {} 个片段", grandTotal);
    }

    /**
     * fail-closed 探针：在<b>未设置租户</b>的会话中确认读不到任何<b>租户私有</b>片段。
     *
     * <p>⚠️ V8 双作用域后判定条件从「可见 0 行」改成「可见行全是全局行」：
     * 无租户会话现在能看到平台全局语料（这是全局共享的定义本身），
     * 继续断言 0 行会在第一次导入全局语料时把 worker 判死。
     * 真正的泄漏判据是「{@code scope <> 'PLATFORM'} 的行可见」。
     * 与 {@code RagStartupSelfCheck} 及
     * {@code scripts/rag-corpus-scope-counterproof.sh} 的 P3-2 同构。
     *
     * <p>⚠️ 本方法由 {@link #run(ApplicationArguments)} 直接调用（self-invocation），
     * 故 {@code @Transactional} 不会生效——这恰是预期：探针本就应在「无租户上下文」
     * 下运行，以验证 RLS FORCE 是否真正拦截了越权读取。
     */
    boolean failClosedPreflight() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM rag_chunk WHERE scope <> 'PLATFORM'", Integer.class);
        int c = count == null ? 0 : count;
        if (c > 0) {
            log.error("[reindex] fail-closed 探针异常：未设租户仍读到 {} 行租户私有 rag_chunk", c);
            return false;
        }
        return true;
    }
}
