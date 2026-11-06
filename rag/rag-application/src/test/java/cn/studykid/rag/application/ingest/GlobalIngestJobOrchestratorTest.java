package cn.studykid.rag.application.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import cn.studykid.rag.core.chunk.Chunk;
import cn.studykid.rag.core.chunk.ChunkRole;
import cn.studykid.rag.core.ingest.IngestJob;
import cn.studykid.rag.core.ingest.IngestJobStatus;
import cn.studykid.rag.core.version.EditionCoordinate;
import cn.studykid.rag.ports.CorpusRepository;
import cn.studykid.rag.ports.EmbeddingPort;
import cn.studykid.rag.ports.IngestJobPort;

/**
 * 平台档（全局桶）摄取编排器单元测试（G1，无 DB）。
 *
 * <p>验证 {@link GlobalIngestJobOrchestrator} 的不变式：
 * <ul>
 *   <li>全程只调用<b>全局</b>落库 / 回写方法（方法名即桶，无租户参数），</li>
 *   <li>绝不调用租户版方法（那是越权闸门本身，落到应用角色会静默 0 行），</li>
 *   <li>空产出 / 缺来源 / 管道异常都转成 job FAILED + 可读原因，不抛给消费循环。</li>
 * </ul>
 */
class GlobalIngestJobOrchestratorTest {

    private final IngestJobPort jobPort = mock(IngestJobPort.class);
    private final IngestionPipeline pipeline = mock(IngestionPipeline.class);
    private final CorpusRepository corpusRepository = mock(CorpusRepository.class);
    private final EmbeddingPort embeddingPort = mock(EmbeddingPort.class);
    private final GlobalIngestJobOrchestrator.GlobalIngestSource sourcePort =
            mock(GlobalIngestJobOrchestrator.GlobalIngestSource.class);

    private final String modelVersion = "test-model";
    private final java.util.function.Function<String, String> tokenizer = s -> s;

    private static final EditionCoordinate EDITION = new EditionCoordinate(
            EditionCoordinate.Subject.MATH, EditionCoordinate.GradeBand.GRADE_6,
            EditionCoordinate.Term.FIRST, EditionCoordinate.Publisher.HUAJIAO, "2024修订");

    private static IngestJob parsingJob() {
        return new IngestJob("job-1", "global-sentinel", "src-1", IngestJobStatus.PARSING,
                null, 0, null, null, null, null, null, null, null, "2026-01-01T00:00:00Z");
    }

    private static Chunk chunk(String id) {
        return new Chunk(id, "doc-1", EDITION, ChunkRole.PAPER_BODY, "知识点文本-" + id,
                new cn.studykid.rag.core.chunk.Chunk.Locator(1, "§1.1", null, 0, 10),
                List.of(), null, "hash-" + id);
    }

    private static IngestJobOrchestrator.IngestSourceView sourceView() {
        return new IngestJobOrchestrator.IngestSourceView(
                "doc-1", "全局教材", EDITION, "edition-1",
                Optional.of("bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                List.of(), "rag-ingest-v1");
    }

    private GlobalIngestJobOrchestrator orchestrator() {
        return new GlobalIngestJobOrchestrator(jobPort, pipeline, corpusRepository,
                embeddingPort, sourcePort, tokenizer);
    }

    @Test
    void happyPathWritesOnlyThroughGlobalMethodsAndMarksSucceeded() {
        when(sourcePort.load("src-1")).thenReturn(Optional.of(sourceView()));
        Chunk c1 = chunk("c1");
        Chunk c2 = chunk("c2");
        when(pipeline.ingest(any(), any(), any(), any(), any())).thenReturn(List.of(c1, c2));
        when(embeddingPort.embed(any())).thenReturn(List.of(new float[] {0.1f, 0.2f, 0.3f, 0.4f}));
        when(embeddingPort.modelVersion()).thenReturn(modelVersion);

        orchestrator().run(parsingJob());

        // 每个片段走全局落库方法（方法名钉死桶）
        verify(corpusRepository).saveChunkAndEmbeddingInGlobalBucket(
                eq(c1), eq("edition-1"), any(float[].class), eq(modelVersion));
        verify(corpusRepository).saveChunkAndEmbeddingInGlobalBucket(
                eq(c2), eq("edition-1"), any(float[].class), eq(modelVersion));
        verify(corpusRepository).updateJiebaTokensInGlobalBucket(eq("c1"), anyString());
        verify(corpusRepository).updateJiebaTokensInGlobalBucket(eq("c2"), anyString());
        verify(corpusRepository).markDocumentParseStatusInGlobalBucket(eq("doc-1"), eq("CHUNKED"));
        // RAG-D26 阶段 3.2：编排器固定走 7 参重载；mock 管道是旧模式 → 留痕为 null（未采集，
        // 不伪造策略值）。isNull() 需要 import org.mockito.ArgumentMatchers.isNull。
        verify(jobPort).markSucceededGlobal(eq("job-1"), eq(2), eq("rag-ingest-v1"), eq(modelVersion),
                isNull(), isNull());

        // ⚠️ 租户版方法一次都没被调用：桶由方法名决定，编译期就无形态填别的桶
        verify(corpusRepository, never()).saveChunkAndEmbedding(any(), any(), any(), any(), any());
        verify(corpusRepository, never()).updateJiebaTokens(any(), any(), anyString());
        verify(corpusRepository, never()).markDocumentParseStatus(any(), any(), any());
        verify(jobPort, never()).markSucceeded(any(), any(), anyInt(), any(), any());
        verify(jobPort, never()).markFailedGlobal(any(), any());
    }

    @Test
    void emptyChunksAreTreatedAsFailureNotSilentSuccess() {
        when(sourcePort.load("src-1")).thenReturn(Optional.of(sourceView()));
        when(pipeline.ingest(any(), any(), any(), any(), any())).thenReturn(List.of());

        orchestrator().run(parsingJob());

        verify(jobPort).markFailedGlobal(eq("job-1"),
                argThat(d -> d.contains("0 个片段")));
        verify(jobPort, never()).markSucceededGlobal(any(), anyInt(), any(), any());
    }

    @Test
    void missingSourceIsTreatedAsFailure() {
        when(sourcePort.load("src-1")).thenReturn(Optional.empty());

        orchestrator().run(parsingJob());

        verify(jobPort).markFailedGlobal(eq("job-1"),
                argThat(d -> d.contains("全局来源不存在或不可见")));
    }

    @Test
    void pipelineExceptionIsCapturedAsJobFailure() {
        when(sourcePort.load("src-1")).thenReturn(Optional.of(sourceView()));
        when(pipeline.ingest(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("docling 解析爆炸"));

        orchestrator().run(parsingJob());

        verify(jobPort).markFailedGlobal(eq("job-1"),
                argThat(d -> d.contains("docling 解析爆炸")));
    }

    @Test
    void failureAlsoMarksDocumentParseStatusFailed() {
        when(sourcePort.load("src-1")).thenReturn(Optional.of(sourceView()));
        when(pipeline.ingest(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("boom"));

        orchestrator().run(parsingJob());

        verify(corpusRepository).markDocumentParseStatusInGlobalBucket(eq("doc-1"), eq("FAILED"));
    }

    @Test
    void premiseModeWritesChunkPolicyTrace() {
        // RAG-D26 阶段 3.2：premise 模式下成功回写必须携带策略与参数快照
        // ——评测靠这两列把检索表现变化归因到切分策略。
        when(sourcePort.load("src-1")).thenReturn(Optional.of(sourceView()));
        when(pipeline.ingest(any(), any(), any(), any(), any())).thenReturn(List.of(chunk("c1")));
        when(pipeline.chunkPolicyTrace()).thenReturn("HEADING_HIERARCHY");
        when(pipeline.chunkPolicyConfigTrace()).thenReturn(
                new cn.studykid.rag.premise.model.ChunkPolicyConfig(512, 0.15, true, true)
                        .stableString());
        when(embeddingPort.embed(any())).thenReturn(List.of(new float[] {0.1f, 0.2f}));
        when(embeddingPort.modelVersion()).thenReturn(modelVersion);

        orchestrator().run(parsingJob());

        verify(jobPort).markSucceededGlobal(eq("job-1"), eq(1), eq("rag-ingest-v1"),
                eq(modelVersion), eq("HEADING_HIERARCHY"),
                eq("maxTokens=512,overlapRatio=0.15,mergePeers=true,repeatTableHeader=true"));
    }
}
