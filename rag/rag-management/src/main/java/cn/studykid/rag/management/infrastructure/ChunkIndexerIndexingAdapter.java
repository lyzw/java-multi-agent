package cn.studykid.rag.management.infrastructure;

import java.util.concurrent.TimeUnit;

import org.jspecify.annotations.NonNull;

import cn.studykid.rag.infrastructure.indexing.ChunkIndexer;
import cn.studykid.rag.management.ports.CorpusIndexingPort;

/**
 * 管理面「重跑索引」端口的基础设施适配器。
 *
 * <p><b>⚠️ 为什么只是薄薄一层委托</b>：
 * 索引逻辑（待索引口径、切词、嵌入、落库）全部在
 * {@link ChunkIndexer} 里，而它位于 {@code rag-infrastructure}——
 * 管理面与 worker <b>共用同一份实现</b>。
 * 若这里再复制一份 SQL，「哪些片段算待索引」就会出现两个口径，
 * 而这条口径直接决定「未校验语料会不会进索引」（fail-closed 的底线）。
 *
 * <p>本适配器只负责两件管理面特有的事：
 * <ol>
 *   <li><b>耗时统计</b>：HTTP 同步端点必须告诉调用方「跑了多久」，
 *       否则前端只能转圈等待，无法判断是「确实慢」还是「卡住了」；</li>
 *   <li><b>端口语义转换</b>：把基础设施的结果翻译成管理面端口的返回类型。</li>
 * </ol>
 *
 * <p><b>⚠️ 租户隔离不在本层兜底</b>：{@link ChunkIndexer} 内部在事务内
 * {@code set_config('app.tenant_id', ...)}，RLS 由它保证。
 * 本层刻意<b>不</b>自己开事务包裹——那会与内层事务模板嵌套，
 * 反而使「扫描用一条连接、写入用另一条连接」这件事更难看清。
 */
public class ChunkIndexerIndexingAdapter implements CorpusIndexingPort {

    private final ChunkIndexer chunkIndexer;

    public ChunkIndexerIndexingAdapter(@NonNull ChunkIndexer chunkIndexer) {
        if (chunkIndexer == null) {
            throw new IllegalArgumentException("chunkIndexer 不能为空");
        }
        this.chunkIndexer = chunkIndexer;
    }

    @Override
    @NonNull
    public ReindexOutcome reindex(@NonNull String tenantId, int limit) {
        long startedAt = System.nanoTime();
        ChunkIndexer.IndexingSummary summary = chunkIndexer.indexTenant(tenantId, limit);
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        return new ReindexOutcome(summary.indexedChunks(), summary.scannedChunks(), durationMs);
    }
}
