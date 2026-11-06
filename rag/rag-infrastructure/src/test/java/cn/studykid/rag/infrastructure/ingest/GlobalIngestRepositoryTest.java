package cn.studykid.rag.infrastructure.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import cn.studykid.rag.core.chunk.Chunk;
import cn.studykid.rag.core.chunk.ChunkRole;
import cn.studykid.rag.core.ingest.IngestJob;
import cn.studykid.rag.core.ingest.IngestJobStatus;
import cn.studykid.rag.core.version.EditionCoordinate;
import cn.studykid.rag.infrastructure.ops.RagOpsSessionPort;
import cn.studykid.rag.infrastructure.ops.RagOpsUnavailableException;
import cn.studykid.rag.infrastructure.persistence.JdbcCorpusRepository;
import cn.studykid.rag.infrastructure.persistence.VectorColumn;

/**
 * 平台档（全局桶）摄取仓储单元测试（G1，无 DB）。
 *
 * <p><b>为什么是「结构性」断言而非只测通不通</b>：运维角色带 BYPASSRLS，
 * 库层不再限制它写到哪个桶。于是「全局摄入只写全局桶」这件事的保证完全落在
 * 两段代码上——落库 SQL 的桶谓词（字面 {@code rag_global_tenant_id()}）与方法名
 * （全局版没有租户参数）。本测试把这两处直接钉死；一旦有人把它们改回绑定参数，
 * 这里先红，而不是等运维在租户桶里发现一批「不知道谁写的」行。
 */
class GlobalIngestRepositoryTest {

    private static final EditionCoordinate EDITION = new EditionCoordinate(
            EditionCoordinate.Subject.MATH, EditionCoordinate.GradeBand.GRADE_6,
            EditionCoordinate.Term.FIRST, EditionCoordinate.Publisher.HUAJIAO, "2024修订");

    /** 记录 SQL 的假 JdbcTemplate：update 落库 SQL 进 sink；query 按内容答样例。 */
    private static JdbcTemplate recordingJdbc(List<String> sink) {
        return mock(JdbcTemplate.class, invocation -> {
            String name = invocation.getMethod().getName();
            Object[] args = invocation.getArguments();
            if ("update".equals(name) && args.length >= 1 && args[0] instanceof String sql) {
                sink.add(sql);
                return 1;
            }
            if ("query".equals(name) && args.length >= 1 && args[0] instanceof String sql) {
                sink.add(sql);
                if (sql.contains("FOR UPDATE SKIP LOCKED")) {
                    return List.of("job-1");
                }
                if (sql.contains("FROM rag_ingest_job")) {
                    return List.of(sampleJob());
                }
                return List.of();
            }
            return null;
        });
    }

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

    private static TransactionTemplate passthroughTx() {
        TransactionTemplate tx = mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(null);
        });
        return tx;
    }

    private static IngestJob sampleJob() {
        return new IngestJob("job-1", "global-sentinel", "src-1", IngestJobStatus.PARSING,
                null, 0, null, null, null, null, null, null, null, "2026-01-01T00:00:00Z");
    }

    private static Chunk chunk(String id) {
        return new Chunk(id, "doc-1", EDITION, ChunkRole.PAPER_BODY, "文本-" + id,
                new Chunk.Locator(1, "§1.1", null, 0, 5), List.of(), null, "hash-" + id);
    }

    private final JdbcTemplate appJdbc = mock(JdbcTemplate.class);

    @Test
    void saveChunkAndEmbeddingInGlobalBucketWritesThroughOpsOnGlobalBucket() {
        List<String> sink = new java.util.ArrayList<>();
        JdbcTemplate opsJdbc = recordingJdbc(sink);
        RecordingOpsSession opsSession = new RecordingOpsSession(opsJdbc, passthroughTx());
        JdbcCorpusRepository repo = new JdbcCorpusRepository(appJdbc,
                new VectorColumn("vector", 4), opsSession);

        repo.saveChunkAndEmbeddingInGlobalBucket(chunk("c1"), "edition-1",
                new float[] {1, 2, 3, 4}, "m");

        assertEquals(1, opsSession.entries(), "整段落库在同一次运维会话窗内");
        boolean chunkInsert = sink.stream().anyMatch(s ->
                s.contains("INSERT INTO rag_chunk") && s.contains("rag_global_tenant_id()")
                        && s.contains("'PLATFORM'"));
        boolean embedInsert = sink.stream().anyMatch(s ->
                s.contains("INSERT INTO rag_chunk_embedding") && s.contains("rag_global_tenant_id()")
                        && s.contains("'PLATFORM'"));
        assertTrue(chunkInsert, "rag_chunk 必须写全局桶（tenant_id=rag_global_tenant_id() 且 scope='PLATFORM'）");
        assertTrue(embedInsert, "rag_chunk_embedding 必须写全局桶（tenant_id=rag_global_tenant_id() 且 scope='PLATFORM'）");
        // ⚠️ 应用连接一次都没被用：全局写绝不回落应用角色
        verifyNoInteractions(appJdbc);
    }

    @Test
    void markDocumentParseStatusInGlobalBucketUsesGlobalPredicate() {
        List<String> sink = new java.util.ArrayList<>();
        RecordingOpsSession opsSession = new RecordingOpsSession(recordingJdbc(sink), passthroughTx());
        JdbcCorpusRepository repo = new JdbcCorpusRepository(appJdbc,
                new VectorColumn("vector", 4), opsSession);

        repo.markDocumentParseStatusInGlobalBucket("doc-1", "CHUNKED");

        assertTrue(sink.stream().anyMatch(s ->
                s.contains("UPDATE rag_document") && s.contains("tenant_id = rag_global_tenant_id()")),
                "rag_document 回写必须收口在全局桶");
    }

    @Test
    void corpusGlobalFailsClosedWithoutOpsSession() {
        List<String> sink = new java.util.ArrayList<>();
        JdbcCorpusRepository repo = new JdbcCorpusRepository(appJdbc,
                new VectorColumn("vector", 4), null);

        RagOpsUnavailableException ex = assertThrows(RagOpsUnavailableException.class,
                () -> repo.saveChunkAndEmbeddingInGlobalBucket(chunk("c1"), "e",
                        new float[] {1, 2, 3, 4}, "m"));
        assertEquals("rag.ops.datasource", ex.missingConfiguration());
        assertTrue(ex.getMessage().contains("不回落应用角色"), ex.getMessage());
        assertTrue(sink.isEmpty(), "未接入时一行都不该写");
    }

    @Test
    void claimNextGlobalJobUsesGlobalSentinelAndSkipLocked() {
        List<String> sink = new java.util.ArrayList<>();
        RecordingOpsSession opsSession = new RecordingOpsSession(recordingJdbc(sink), passthroughTx());
        JdbcIngestJobRepository repo = new JdbcIngestJobRepository(appJdbc, passthroughTx(), opsSession);

        IngestJob job = repo.claimNextGlobalJob();

        assertEquals("job-1", job.jobId());
        assertEquals(1, opsSession.entries());
        assertTrue(sink.stream().anyMatch(s ->
                s.contains("FOR UPDATE SKIP LOCKED") && s.contains("rag_global_tenant_id()")),
                "领取谓词必须写死全局哨兵且用 SKIP LOCKED");
    }

    @Test
    void markSucceededGlobalUsesGlobalPredicate() {
        List<String> sink = new java.util.ArrayList<>();
        RecordingOpsSession opsSession = new RecordingOpsSession(recordingJdbc(sink), passthroughTx());
        JdbcIngestJobRepository repo = new JdbcIngestJobRepository(appJdbc, passthroughTx(), opsSession);

        repo.markSucceededGlobal("job-1", 2, "v1", "m");

        assertTrue(sink.stream().anyMatch(s ->
                s.contains("UPDATE rag_ingest_job") && s.contains("'SUCCEEDED'")
                        && s.contains("tenant_id = rag_global_tenant_id()")),
                "成功回写必须收口在全局桶");
    }

    @Test
    void markFailedGlobalUsesGlobalPredicate() {
        List<String> sink = new java.util.ArrayList<>();
        RecordingOpsSession opsSession = new RecordingOpsSession(recordingJdbc(sink), passthroughTx());
        JdbcIngestJobRepository repo = new JdbcIngestJobRepository(appJdbc, passthroughTx(), opsSession);

        repo.markFailedGlobal("job-1", "解析器崩溃");

        assertTrue(sink.stream().anyMatch(s ->
                s.contains("UPDATE rag_ingest_job") && s.contains("'FAILED'")
                        && s.contains("tenant_id = rag_global_tenant_id()")),
                "失败回写必须收口在全局桶");
    }

    @Test
    void advanceGlobalUsesGlobalPredicate() {
        List<String> sink = new java.util.ArrayList<>();
        RecordingOpsSession opsSession = new RecordingOpsSession(recordingJdbc(sink), passthroughTx());
        JdbcIngestJobRepository repo = new JdbcIngestJobRepository(appJdbc, passthroughTx(), opsSession);

        repo.advanceGlobal("job-1", IngestJobStatus.CHUNKING, "CHUNKING 60");

        // ⚠️ 状态是绑定参数（?::rag_ingest_status）而非内联字面量，故校验 cast 与桶谓词
        assertTrue(sink.stream().anyMatch(s ->
                s.contains("UPDATE rag_ingest_job") && s.contains("rag_ingest_status")
                        && s.contains("tenant_id = rag_global_tenant_id()")));
    }

    @Test
    void failTimedOutRunningGlobalJobsUsesGlobalPredicate() {
        List<String> sink = new java.util.ArrayList<>();
        RecordingOpsSession opsSession = new RecordingOpsSession(recordingJdbc(sink), passthroughTx());
        JdbcIngestJobRepository repo = new JdbcIngestJobRepository(appJdbc, passthroughTx(), opsSession);

        int n = repo.failTimedOutRunningGlobalJobs(30);

        assertEquals(1, n, "记录型假 JdbcTemplate 对每条 update 返回 1");
        assertTrue(sink.stream().anyMatch(s ->
                s.contains("UPDATE rag_ingest_job") && s.contains("SWEEPER_TIMEOUT")
                        && s.contains("tenant_id = rag_global_tenant_id()")));
    }

    @Test
    void ingestJobGlobalMethodsFailClosedWithoutOpsSession() {
        JdbcIngestJobRepository repo = new JdbcIngestJobRepository(appJdbc, passthroughTx(), null);

        RagOpsUnavailableException ex = assertThrows(RagOpsUnavailableException.class,
                repo::claimNextGlobalJob);
        assertEquals("rag.ops.datasource", ex.missingConfiguration());
    }
}
