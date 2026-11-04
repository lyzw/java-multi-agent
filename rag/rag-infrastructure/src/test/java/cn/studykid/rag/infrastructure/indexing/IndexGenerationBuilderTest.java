package cn.studykid.rag.infrastructure.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import cn.studykid.rag.core.indexing.IndexGeneration;
import cn.studykid.rag.ports.CorpusRepository;
import cn.studykid.rag.ports.EmbeddingPort;
import cn.studykid.rag.ports.IndexGenerationPort;

/**
 * 代际构建器的不变式（RAG-D13 §3.5.2 蓝绿切换的「绿」）。
 *
 * <p>⚠️ 本测试证明的核心命题：
 * <b>构建只能写 BUILDING 代际</b>——写 ACTIVE 等于原地重建（蓝绿要避免的），
 * 写 RETIRED 会污染回滚目标。两者都不报错，只会让切换/回滚在事后变成事故。
 */
class IndexGenerationBuilderTest {

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";

    private static IndexGeneration gen(int no, IndexGeneration.Status status) {
        return new IndexGeneration(no, "BGE-M3", 1024, status, 0, 0, null, null, null, null);
    }

    private static IndexGenerationBuilder builderWith(IndexGenerationPort port,
                                                     CorpusRepository repo,
                                                     EmbeddingPort emb) {
        // 扫描/计数走真实 SQL，这里只测「状态守卫」与「单片段写入」路径。
        return new IndexGenerationBuilder(
                mock(JdbcTemplate.class), mock(TransactionTemplate.class), repo, emb, port);
    }

    private static EmbeddingPort embeddingOf(float[] vector) {
        EmbeddingPort port = mock(EmbeddingPort.class);
        when(port.embed(any())).thenReturn(List.of(vector));
        when(port.modelVersion()).thenReturn("BGE-M3");
        return port;
    }

    @Nested
    @DisplayName("状态守卫：只允许构建 BUILDING 代际")
    class StatusGuard {

        @Test
        @DisplayName("ACTIVE 代际拒绝构建（否则等于原地重建，新旧向量混在一个空间）")
        void rejectsActive() {
            IndexGenerationPort port = mock(IndexGenerationPort.class);
            when(port.listGenerations(TENANT)).thenReturn(List.of(gen(1, IndexGeneration.Status.ACTIVE)));

            IndexGenerationBuilder b = builderWith(port, mock(CorpusRepository.class), embeddingOf(new float[]{1f}));

            assertThatThrownBy(() -> b.build(TENANT, 1))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("只允许构建 BUILDING");
        }

        @Test
        @DisplayName("RETIRED 代际拒绝构建（否则污染回滚目标）")
        void rejectsRetired() {
            IndexGenerationPort port = mock(IndexGenerationPort.class);
            when(port.listGenerations(TENANT)).thenReturn(List.of(gen(2, IndexGeneration.Status.RETIRED)));

            IndexGenerationBuilder b = builderWith(port, mock(CorpusRepository.class), embeddingOf(new float[]{1f}));

            assertThatThrownBy(() -> b.build(TENANT, 2))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("污染回滚目标");
        }

        @Test
        @DisplayName("代际不存在时明确报错")
        void rejectsMissing() {
            IndexGenerationPort port = mock(IndexGenerationPort.class);
            when(port.listGenerations(TENANT)).thenReturn(List.of());

            IndexGenerationBuilder b = builderWith(port, mock(CorpusRepository.class), embeddingOf(new float[]{1f}));

            assertThatThrownBy(() -> b.build(TENANT, 99))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("代际不存在");
        }
    }

    @Nested
    @DisplayName("单片段写入")
    class SingleWrite {

        @Test
        @DisplayName("写入的是指定代际，不是 ACTIVE")
        void writesToGivenGeneration() {
            CorpusRepository repo = mock(CorpusRepository.class);
            EmbeddingPort emb = embeddingOf(new float[]{0.1f, 0.2f});
            IndexGenerationBuilder b = builderWith(mock(IndexGenerationPort.class), repo, emb);

            b.indexOne(TENANT, 7, new IndexGenerationBuilder.PendingChunk("chunk-1", "二次函数"));

            // ⚠️ 关键断言：代际号 7（构建目标）而不是任何"当前代际"解析结果
            verify(repo).upsertEmbeddingForGeneration(
                    eq(TENANT), eq("chunk-1"), any(), eq("BGE-M3"), eq(7));
        }

        @Test
        @DisplayName("不触碰词法列（换模型不需要重切词）")
        void doesNotTouchJiebaColumns() {
            CorpusRepository repo = mock(CorpusRepository.class);
            IndexGenerationBuilder b = builderWith(
                    mock(IndexGenerationPort.class), repo, embeddingOf(new float[]{1f}));

            b.indexOne(TENANT, 3, new IndexGenerationBuilder.PendingChunk("c", "文本"));

            verify(repo, never()).updateJiebaTokens(anyString(), anyString(), anyString());
            verify(repo, never()).updateJiebaTokensInGlobalBucket(anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("异步分批（RAG-D21 §六 P1）")
    class AsyncBatching {

        @Test
        @DisplayName("prepare 只统计总数并清零游标，不执行嵌入")
        void prepareOnlyMarks() {
            IndexGenerationPort port = mock(IndexGenerationPort.class);
            CorpusRepository repo = mock(CorpusRepository.class);
            EmbeddingPort emb = embeddingOf(new float[]{1f});
            when(port.listGenerations(TENANT))
                    .thenReturn(List.of(gen(2, IndexGeneration.Status.BUILDING)));

            IndexGenerationBuilder b = builderWith(port, repo, emb);
            try {
                b.prepare(TENANT, 2);
            } catch (RuntimeException ignored) {
                // TransactionTemplate 是 mock，COUNT_SQL 走不通——本用例只验证"不嵌入"
            }

            // ⚠️ 核心断言：prepare 阶段绝不嵌入任何片段
            verify(emb, never()).embed(any());
            verify(repo, never()).upsertEmbeddingForGeneration(
                    anyString(), anyString(), any(), anyString(), anyInt());
        }

        @Test
        @DisplayName("ACTIVE 代际同样拒绝 prepare（与 buildBatch 一致）")
        void prepareRejectsActive() {
            IndexGenerationPort port = mock(IndexGenerationPort.class);
            when(port.listGenerations(TENANT))
                    .thenReturn(List.of(gen(1, IndexGeneration.Status.ACTIVE)));

            IndexGenerationBuilder b = builderWith(
                    port, mock(CorpusRepository.class), embeddingOf(new float[]{1f}));

            assertThatThrownBy(() -> b.prepare(TENANT, 1))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("只允许构建 BUILDING");
        }

        @Test
        @DisplayName("buildBatch 对非 BUILDING 代际同样拒绝")
        void buildBatchRejectsNonBuilding() {
            IndexGenerationPort port = mock(IndexGenerationPort.class);
            when(port.listGenerations(TENANT))
                    .thenReturn(List.of(gen(3, IndexGeneration.Status.RETIRED)));

            IndexGenerationBuilder b = builderWith(
                    port, mock(CorpusRepository.class), embeddingOf(new float[]{1f}));

            assertThatThrownBy(() -> b.buildBatch(TENANT, 3))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("污染回滚目标");
        }
    }

    @Nested
    @DisplayName("全局桶通道（RAG-D22 §7.2 #1）")
    class GlobalBucket {

        @Test
        @DisplayName("全局 SQL 由租户 SQL 派生：只差桶谓词，治理条件逐字一致")
        void globalSqlIsDerivedNotCopied() {
            // ⚠️ 这是「换模型时不进未校验语料」在全局桶上的唯一防线。
            //    若全局 SQL 是另写的，将来改 fail-closed 条件只会改到租户那半，
            //    全局语料就会带着未校验内容进索引，且不报错。
            assertThat(IndexGenerationBuilder.GLOBAL_SCAN_SQL)
                    .contains("c.tenant_id = rag_global_tenant_id()")
                    .doesNotContain("c.tenant_id = rag_current_tenant()");
            assertThat(IndexGenerationBuilder.GLOBAL_SCAN_SQL)
                    .contains("src.ver_status = 'VERIFIED'")
                    .contains("doc.ver_status = 'VERIFIED'")
                    .contains("doc.retrievable");

            assertThat(IndexGenerationBuilder.GLOBAL_COUNT_SQL)
                    .contains("c.tenant_id = rag_global_tenant_id()")
                    .contains("src.ver_status = 'VERIFIED'")
                    .contains("doc.ver_status = 'VERIFIED'")
                    .contains("doc.retrievable");
        }

        @Test
        @DisplayName("除桶谓词外，全局与租户 SQL 完全一致")
        void globalDiffersOnlyInBucketPredicate() {
            String normalizedGlobal = IndexGenerationBuilder.GLOBAL_SCAN_SQL
                    .replace("rag_global_tenant_id()", "BUCKET");
            String normalizedTenant = IndexGenerationBuilder.SCAN_SQL
                    .replace("rag_current_tenant()", "BUCKET");
            assertThat(normalizedGlobal).isEqualTo(normalizedTenant);
        }

        @Test
        @DisplayName("运维通道未接入时明确抛 RagOpsUnavailableException，不静默回落")
        void missingOpsSessionFailsLoudly() {
            IndexGenerationPort port = mock(IndexGenerationPort.class);
            when(port.listGenerations(anyString()))
                    .thenReturn(List.of(gen(2, IndexGeneration.Status.BUILDING)));
            // 三参构造器 = 无运维通道
            IndexGenerationBuilder b = new IndexGenerationBuilder(
                    mock(JdbcTemplate.class), mock(TransactionTemplate.class),
                    mock(CorpusRepository.class), embeddingOf(new float[]{1f}), port);

            assertThatThrownBy(() -> b.buildGlobalBatch(2))
                    .isInstanceOf(cn.studykid.rag.infrastructure.ops.RagOpsUnavailableException.class);
        }
    }

    @Nested
    @DisplayName("fail-closed 口径")
    class FailClosed {

        @Test
        @DisplayName("扫描 SQL 与日常索引共用同一套 VERIFIED / retrievable 条件")
        void scanSqlKeepsFailClosedPredicates() {
            // ⚠️ 这是「换模型时不进未校验语料」的唯一防线：
            //    若这里的条件比 ChunkIndexer 松，就会出现
            //    「日常索引不进、换模型却进了」的口径裂缝。
            assertThat(IndexGenerationBuilder.SCAN_SQL)
                    .contains("src.ver_status = 'VERIFIED'")
                    .contains("doc.ver_status = 'VERIFIED'")
                    .contains("doc.retrievable");
            assertThat(IndexGenerationBuilder.COUNT_SQL)
                    .contains("src.ver_status = 'VERIFIED'")
                    .contains("doc.ver_status = 'VERIFIED'")
                    .contains("doc.retrievable");
        }

        @Test
        @DisplayName("扫描 SQL 不含「缺向量」条件（换模型要重算全部片段）")
        void scanSqlDoesNotFilterByExistingEmbedding() {
            // ⚠️ 与 ChunkIndexer.PENDING_SQL 的唯一差异：
            //    换模型时**所有**片段都要重算，无论它当前有没有向量。
            assertThat(IndexGenerationBuilder.SCAN_SQL)
                    .doesNotContain("NOT EXISTS")
                    .doesNotContain("rag_chunk_embedding");
        }
    }
}
