package cn.studykid.rag.infrastructure.indexing;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
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

import cn.studykid.rag.core.embedding.EmbeddingInputTemplate;
import cn.studykid.rag.infrastructure.embedding.LocalDeterministicEmbeddingPort;
import cn.studykid.rag.ports.CorpusRepository;
import cn.studykid.rag.ports.EmbeddingPort;

/**
 * P3-1 接线反证：重索引路径必须把**模板文本**送进 embeddingPort，而不是裸正文。
 *
 * <p>撤掉 writeChunk 里的 renderDocument 调用，下面断言必红。
 */
class EmbeddingTemplateWiringTest {

    @Test
    @DisplayName("ChunkIndexer 重算：embeddingPort 收到的是模板文本（含教材前缀），非裸正文")
    void chunkIndexerEmbedsTemplateText() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TransactionTemplate tx = mock(TransactionTemplate.class);
        when(tx.execute(ArgumentMatchers.<TransactionCallback<List<ChunkIndexer.PendingChunk>>>any()))
                .thenAnswer(inv -> ((TransactionCallback<?>) inv.getArgument(0))
                        .doInTransaction(null));
        CorpusRepository repo = mock(CorpusRepository.class);
        EmbeddingPort port = spy(new LocalDeterministicEmbeddingPort());

        String bareText = "求抛物线的顶点坐标。";
        ChunkIndexer.PendingChunk row = new ChunkIndexer.PendingChunk(
                "c-1", bareText, "EXERCISE", 20, "第3章 > 二次函数", "Q003", null,
                "人教版", "七年级", "上册", "2024",
                List.of("二次函数顶点"), List.of("顶点式"));

        when(jdbc.query(eq(ChunkIndexer.PENDING_SQL.replace("{rag_tenant}", "'t'::uuid")),
                ArgumentMatchers.<RowMapper<ChunkIndexer.PendingChunk>>any(),
                ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(List.of(row));

        ChunkIndexer indexer = new ChunkIndexer(jdbc, tx, repo, port, 200);
        indexer.indexTenant("t", 1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(port).embed(captor.capture());
        String sent = captor.getValue().get(0);
        assertTrue(sent.contains("[教材: 人教版-七年级-上册-2024]"),
                "embedding 输入必须含模板教材前缀，实际=" + sent);
        assertTrue(sent.contains(bareText), "模板正文须保留原文");
        assertTrue(!sent.equals(bareText), "不能再把裸正文直接送 embedding");
    }

    @Test
    @DisplayName("P3-1 语义带过：扫描行的公式/摘要槽位必须进 embedding 文本（撤掉即红）")
    void semanticsFlowIntoEmbeddingText() {
        String text = cn.studykid.rag.core.embedding.EmbeddingInputTemplate.renderDocument(
                new ChunkIndexer.PendingChunk(
                        "c-2", "F = ma", "FORMULA", 5, "第3章", null, "FORMULA",
                        "F = ma。牛顿第二定律", "表格（2 行 2 列）；表头：知识点 难度",
                        "人教版", "八年级", "上册", "2024",
                        List.of(), List.of()).toEmbeddingInput());

        assertTrue(text.contains("牛顿第二定律"),
                "公式语义槽位必须进 embedding 文本，实际=" + text);
        assertTrue(text.contains("表头：知识点 难度"),
                "表格摘要槽位必须进 embedding 文本，实际=" + text);
    }
}
