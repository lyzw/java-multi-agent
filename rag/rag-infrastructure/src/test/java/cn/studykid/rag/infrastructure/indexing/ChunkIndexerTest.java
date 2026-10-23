package cn.studykid.rag.infrastructure.indexing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import cn.studykid.rag.infrastructure.embedding.LocalDeterministicEmbeddingPort;
import cn.studykid.rag.infrastructure.text.ChineseTokenizer;
import cn.studykid.rag.ports.CorpusRepository;

/**
 * ChunkIndexer 单元测试（无 DB）：验证单片段索引的「切词 + 嵌入 + 落库」链路，
 * 不触碰 JdbcTemplate / TransactionTemplate（与运行环境解耦）。
 *
 * <p><b>⚠️ 为什么「待索引口径」要在单测里断言</b>：
 * 「哪些片段算待索引」是一条 <b>fail-closed 口径</b>（未校验来源的片段绝不能进索引）。
 * 这条口径写在 SQL 里，单测可以直接断言 SQL 文本含「来源必须 VERIFIED」与
 * 「缺切词或缺向量」两个条件——比等 IT 才发现口径被改写要早得多。
 */
class ChunkIndexerTest {

    private final CorpusRepository corpusRepository = mock(CorpusRepository.class);
    private final LocalDeterministicEmbeddingPort embeddingPort = new LocalDeterministicEmbeddingPort();

    private ChunkIndexer newIndexer() {
        return new ChunkIndexer(
                mock(org.springframework.jdbc.core.JdbcTemplate.class),
                mock(org.springframework.transaction.support.TransactionTemplate.class),
                corpusRepository, embeddingPort, 200);
    }

    @Test
    void indexOne_writesJiebaAndEmbedding() {
        ChunkIndexer indexer = newIndexer();
        String tenantId = "tenant-1";
        String chunkId = "chunk-1";
        String text = "二次函数顶点坐标公式";

        indexer.indexOne(tenantId, chunkId, text);

        // 词法通道：落库的是 ChineseTokenizer 的切词结果
        verify(corpusRepository).updateJiebaTokens(
                eq(tenantId), eq(chunkId), eq(ChineseTokenizer.tokenize(text)));

        // 稠密通道：落库的是 1024 维向量，模型标识为本地占位版本
        ArgumentCaptor<float[]> vecCaptor = ArgumentCaptor.forClass(float[].class);
        verify(corpusRepository).upsertEmbedding(
                eq(tenantId), eq(chunkId), vecCaptor.capture(), eq(embeddingPort.modelVersion()));
        assertEquals(LocalDeterministicEmbeddingPort.DIM, vecCaptor.getValue().length);
        assertEquals("local-deterministic-v1", embeddingPort.modelVersion());
    }

    @Test
    void indexOne_doesNotTouchJdbcTemplate() {
        org.springframework.jdbc.core.JdbcTemplate jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        ChunkIndexer indexer = new ChunkIndexer(jdbc,
                mock(org.springframework.transaction.support.TransactionTemplate.class),
                corpusRepository, embeddingPort, 200);

        indexer.indexOne("t", "c", "示例文本");

        // indexOne 不应直接使用 JdbcTemplate（扫描/事务在 indexTenant 中）
        verify(jdbc, never()).update(anyString());
    }

    @Test
    void indexOne_keepsLatinTermsLowercased() {
        ChunkIndexer indexer = newIndexer();
        String text = "Quadratic function 顶点 Vertex";

        indexer.indexOne("t", "c", text);

        ArgumentCaptor<String> jiebaCaptor = ArgumentCaptor.forClass(String.class);
        verify(corpusRepository).updateJiebaTokens(eq("t"), eq("c"), jiebaCaptor.capture());
        // 拉丁整块小写保留：'quadratic' 'function' 'vertex' 应小写出现
        List<String> tokens = List.of(jiebaCaptor.getValue().split(" "));
        org.junit.jupiter.api.Assertions.assertTrue(tokens.contains("quadratic"));
        org.junit.jupiter.api.Assertions.assertTrue(tokens.contains("function"));
        org.junit.jupiter.api.Assertions.assertTrue(tokens.contains("vertex"));
    }

    // ---------------------------------------------------------------------
    // 限量重索引（管理面 HTTP 同步端点依赖的语义）
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("限量扫描：处理数不超过 limit（应用层硬上限，不依赖 DB 遵守 LIMIT）")
    void limitedReindexHonoursLimit() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TransactionTemplate tx = passthroughTransactionTemplate();
        // ⚠️ 这里让被 mock 的 DB **无视 LIMIT** 返回 5 条：
        //    若实现只把上限寄托在 SQL 的 LIMIT ? 上，就会处理 5 条——
        //    而管理面向调用方承诺的是「最多处理 N 条」。
        when(jdbc.query(eq(ChunkIndexer.PENDING_SQL),
                ArgumentMatchers.<RowMapper<ChunkIndexer.PendingChunk>>any(),
                ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(List.of(
                        new ChunkIndexer.PendingChunk("c1", "文本一"),
                        new ChunkIndexer.PendingChunk("c2", "文本二"),
                        new ChunkIndexer.PendingChunk("c3", "文本三"),
                        new ChunkIndexer.PendingChunk("c4", "文本四"),
                        new ChunkIndexer.PendingChunk("c5", "文本五")));

        ChunkIndexer indexer = new ChunkIndexer(jdbc, tx, corpusRepository, embeddingPort, 200);
        ChunkIndexer.IndexingSummary summary = indexer.indexTenant("t", 2);

        assertEquals(2, summary.indexedChunks(), "处理量必须受 limit 约束");
        assertEquals(2, summary.scannedChunks(),
                "scanned 是本次扫描并交给处理的条数，不是库里待索引的总数");
        verify(corpusRepository, times(2)).updateJiebaTokens(eq("t"), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
        verify(corpusRepository, times(2)).upsertEmbedding(eq("t"), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(float[].class), eq(embeddingPort.modelVersion()));
    }

    @Test
    @DisplayName("扫描到的条数少于 limit 时全部处理（不补齐、不截断）")
    void fewerPendingThanLimitAreAllIndexed() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TransactionTemplate tx = passthroughTransactionTemplate();
        when(jdbc.query(eq(ChunkIndexer.PENDING_SQL),
                ArgumentMatchers.<RowMapper<ChunkIndexer.PendingChunk>>any(),
                ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(List.of(new ChunkIndexer.PendingChunk("c1", "文本一")));

        ChunkIndexer indexer = new ChunkIndexer(jdbc, tx, corpusRepository, embeddingPort, 200);
        ChunkIndexer.IndexingSummary summary = indexer.indexTenant("t", 500);

        assertEquals(1, summary.indexedChunks());
        assertEquals(1, summary.scannedChunks());
    }

    @Test
    @DisplayName("limit 非正数直接拒绝（不静默当成 0 处理）")
    void nonPositiveLimitRejected() {
        ChunkIndexer indexer = newIndexer();
        assertThrows(IllegalArgumentException.class, () -> indexer.indexTenant("t", 0));
    }

    @Test
    @DisplayName("待索引口径：来源必须 VERIFIED，且缺切词或缺向量才算待索引")
    void pendingSqlKeepsFailClosedCriteria() {
        String sql = ChunkIndexer.PENDING_SQL;
        org.junit.jupiter.api.Assertions.assertTrue(sql.contains("src.ver_status = 'VERIFIED'"),
                "未校验来源的片段绝不能进索引（fail-closed）");
        org.junit.jupiter.api.Assertions.assertTrue(sql.contains("c.text_jieba = ''"),
                "缺词法切词的片段必须被补齐");
        org.junit.jupiter.api.Assertions.assertTrue(sql.contains("rag_chunk_embedding"),
                "缺向量的片段必须被补齐");
        org.junit.jupiter.api.Assertions.assertTrue(sql.contains("c.tenant_id = rag_current_tenant()"),
                "扫描必须按租户收口（RLS）");
        // ⚠️ 键集分页游标必须显式转 uuid：chunk_id 是 uuid 列，
        //    参数以字符串传入时 PG 无 uuid > varchar 运算符 → 运行期 500。
        //    （2026-10-23 实测踩中：这条 SQL 在被管理端点真正调用前从未跑过。）
        org.junit.jupiter.api.Assertions.assertTrue(sql.contains("c.chunk_id > ?::uuid"),
                "键集分页游标必须按 uuid 比较");
    }

    /**
     * 构造一个「直接执行回调」的 {@link TransactionTemplate}。
     *
     * <p>⚠️ 单测里不能 mock {@code execute()} 的返回值——那等于把
     * 「扫描结果」硬编码成期望值，测试就变成了自证。
     * 这里让模板真正跑回调，从而验证「扫描 SQL 被调用 + 参数正确」。
     */
    private TransactionTemplate passthroughTransactionTemplate() {
        TransactionTemplate tx = mock(TransactionTemplate.class);
        when(tx.execute(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> {
            TransactionCallback<?> callback = inv.getArgument(0);
            return callback.doInTransaction(null);
        });
        return tx;
    }
}
