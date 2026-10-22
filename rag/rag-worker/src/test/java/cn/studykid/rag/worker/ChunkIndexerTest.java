package cn.studykid.rag.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import cn.studykid.rag.infrastructure.embedding.LocalDeterministicEmbeddingPort;
import cn.studykid.rag.infrastructure.text.ChineseTokenizer;
import cn.studykid.rag.ports.CorpusRepository;

/**
 * ChunkIndexer 单元测试（无 DB）：验证单片段索引的「切词 + 嵌入 + 落库」链路，
 * 不触碰 JdbcTemplate / TransactionTemplate（与运行环境解耦）。
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
}
