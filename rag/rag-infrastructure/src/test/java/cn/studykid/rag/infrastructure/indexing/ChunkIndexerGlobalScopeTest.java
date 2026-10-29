package cn.studykid.rag.infrastructure.indexing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import cn.studykid.rag.infrastructure.ops.RagOpsSessionPort;
import cn.studykid.rag.infrastructure.ops.RagOpsUnavailableException;
import cn.studykid.rag.infrastructure.text.ChineseTokenizer;
import cn.studykid.rag.ports.CorpusRepository;

/**
 * 全局桶索引通道单元测试（第二刀 2.5，无 DB）。
 *
 * <p><b>⚠️ 为什么这条通道要有「结构性」断言而不是只测通不通</b>：
 * 运维角色带 BYPASSRLS，<b>库层不再限制它写到哪个桶</b>。
 * 于是「全局索引只写全局桶」这件事的保证完全落在两处代码上——
 * 扫描 SQL 的桶谓词、以及落库方法名（全局版没有租户参数）。
 * 这两处都能在本测试里直接钉住；一旦哪天有人把它们改回绑定参数，
 * 这里会先红，而不是等运维在租户桶里发现一批「不知道谁写的」行。
 */
class ChunkIndexerGlobalScopeTest {

    private final CorpusRepository corpusRepository = mock(CorpusRepository.class);
    private final LocalDeterministicEmbeddingPort embeddingPort = new LocalDeterministicEmbeddingPort();
    private final JdbcTemplate appJdbc = mock(JdbcTemplate.class);
    private final TransactionTemplate appTx = passthroughTransactionTemplate();
    private final JdbcTemplate opsJdbc = mock(JdbcTemplate.class);
    private final TransactionTemplate opsTx = passthroughTransactionTemplate();
    private final RecordingOpsSession opsSession =
            new RecordingOpsSession(opsJdbc, opsTx);

    @Test
    @DisplayName("全局扫描 SQL 与租户版只在桶谓词上不同，且不含 rag_current_tenant()")
    void globalScanSqlDiffersFromTenantScanOnlyInBucketPredicate() {
        String global = ChunkIndexer.GLOBAL_PENDING_SQL;
        assertTrue(global.contains("c.tenant_id = rag_global_tenant_id()"),
                "桶谓词必须是字面函数（写死在全局桶），而不是一个可以填别的桶的问号");
        assertFalse(global.contains("rag_current_tenant()"),
                "全局通道绝不能依赖会话租户：把哨兵写进 app.tenant_id 会让该函数 RAISE 42501");
        assertTrue(global.contains("src.ver_status = 'VERIFIED'"),
                "换通道不换口径——未校验来源仍不得进索引");
        assertTrue(global.contains("c.chunk_id > ?::uuid"), "键集分页游标仍按 uuid 比较");
        // 除桶谓词外两段 SQL 必须逐字相同（否则「同一份口径」只是注释）
        assertEquals(ChunkIndexer.PENDING_SQL
                        .replace("WHERE c.tenant_id = rag_current_tenant()",
                                 "WHERE c.tenant_id = rag_global_tenant_id()"),
                global);
    }

    @Test
    @DisplayName("通道未接入时抛 503 语义异常，且不落任何一行")
    void globalIndexWithoutOpsChannelFailsClosed() {
        ChunkIndexer indexer = newIndexer(null);

        RagOpsUnavailableException ex = assertThrows(RagOpsUnavailableException.class,
                () -> indexer.indexGlobalCorpus(10));
        assertEquals("rag.ops.datasource", ex.missingConfiguration());
        assertTrue(ex.getMessage().contains("不回落应用角色"), ex.getMessage());
        verifyNoInteractions(corpusRepository);
        verifyNoInteractions(opsJdbc);
    }

    @Test
    @DisplayName("limit 非正数在开会话之前就被拒绝（不留下「进过窗」的痕迹）")
    void nonPositiveLimitIsRefusedBeforeOpeningSession() {
        ChunkIndexer indexer = newIndexer(opsSession);

        assertThrows(IllegalArgumentException.class, () -> indexer.indexGlobalCorpus(0));
        assertEquals(0, opsSession.entries(), "校验顺序本身是契约：先卡参数再开窗口");
    }

    @Test
    @DisplayName("全局索引：扫描走运维连接 + 全局 SQL，落库走无租户参数的全局方法")
    void globalIndexScansOnOpsConnectionAndWritesThroughGlobalMethods() {
        when(opsJdbc.query(eq(ChunkIndexer.GLOBAL_PENDING_SQL),
                ArgumentMatchers.<RowMapper<ChunkIndexer.PendingChunk>>any(),
                any(), any()))
                .thenReturn(List.of(
                        new ChunkIndexer.PendingChunk("c1", "二次函数顶点坐标公式"),
                        new ChunkIndexer.PendingChunk("c2", "反二次函数")));

        ChunkIndexer indexer = newIndexer(opsSession);
        ChunkIndexer.IndexingSummary summary = indexer.indexGlobalCorpus(5);

        assertEquals(2, summary.indexedChunks());
        assertEquals(1, opsSession.entries(), "整段扫描+落库在同一次会话里完成");
        verify(corpusRepository).updateJiebaTokensInGlobalBucket(
                eq("c1"), eq(ChineseTokenizer.tokenize("二次函数顶点坐标公式")));
        ArgumentCaptor<float[]> vector = ArgumentCaptor.forClass(float[].class);
        verify(corpusRepository).upsertEmbeddingInGlobalBucket(
                eq("c1"), vector.capture(), eq(embeddingPort.modelVersion()));
        assertEquals(LocalDeterministicEmbeddingPort.DIM, vector.getValue().length);
        // ⚠️ 租户版方法一次都没被调用：桶的选择由方法名决定，而方法名没有参数可填
        verify(corpusRepository, never()).updateJiebaTokens(anyString(), anyString(), anyString());
        verify(corpusRepository, never()).upsertEmbedding(anyString(), anyString(),
                any(float[].class), anyString());
        // 全局通道不建会话租户（建了就是把哨兵写进 app.tenant_id）
        verify(opsJdbc, never()).queryForObject(anyString(), eq(String.class), any());
        verify(appJdbc, never()).queryForObject(anyString(), eq(String.class), any());
        verifyNoInteractions(appJdbc);
    }

    @Test
    @DisplayName("全局索引同样受 limit 硬约束（DB 多给也不处理）")
    void globalIndexHonoursLimit() {
        when(opsJdbc.query(eq(ChunkIndexer.GLOBAL_PENDING_SQL),
                ArgumentMatchers.<RowMapper<ChunkIndexer.PendingChunk>>any(),
                any(), any()))
                .thenReturn(List.of(
                        new ChunkIndexer.PendingChunk("c1", "文本一"),
                        new ChunkIndexer.PendingChunk("c2", "文本二"),
                        new ChunkIndexer.PendingChunk("c3", "文本三")));

        ChunkIndexer indexer = newIndexer(opsSession);
        ChunkIndexer.IndexingSummary summary = indexer.indexGlobalCorpus(2);

        assertEquals(2, summary.indexedChunks());
        assertEquals(2, summary.scannedChunks());
    }

    @Test
    @DisplayName("indexOne 是租户通道：它不碰运维会话")
    void indexOneStaysOnTenantChannel() {
        ChunkIndexer indexer = newIndexer(opsSession);

        indexer.indexOne("tenant-1", "c1", "示例文本");

        assertEquals(0, opsSession.entries());
        verify(corpusRepository).updateJiebaTokens(eq("tenant-1"), eq("c1"), anyString());
        verify(corpusRepository, never()).updateJiebaTokensInGlobalBucket(anyString(), anyString());
    }

    private ChunkIndexer newIndexer(RagOpsSessionPort session) {
        return new ChunkIndexer(appJdbc, appTx, corpusRepository, embeddingPort, 200, session);
    }

    /**
     * 记录「进过几次窗」的假运维通道。
     *
     * <p>⚠️ 它把调用方给的模板原样交回去，因此「窗口内用的是哪条连接」
     * 由被测代码决定，而不是测试替身替它决定。
     */
    private static final class RecordingOpsSession implements RagOpsSessionPort {

        private final JdbcTemplate jdbcTemplate;
        private final TransactionTemplate transactionTemplate;
        private int entries;

        RecordingOpsSession(JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate) {
            this.jdbcTemplate = jdbcTemplate;
            this.transactionTemplate = transactionTemplate;
        }

        int entries() {
            return entries;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public <T> T inGlobalCorpusSession(RagOpsAction<T> action) {
            entries++;
            return action.run(jdbcTemplate, transactionTemplate);
        }
    }

    /** 直接执行回调的事务模板（mock execute 的返回值等于自证，故让它真跑）。 */
    private static TransactionTemplate passthroughTransactionTemplate() {
        TransactionTemplate tx = mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> callback = inv.getArgument(0);
            return callback.doInTransaction(null);
        });
        return tx;
    }
}
