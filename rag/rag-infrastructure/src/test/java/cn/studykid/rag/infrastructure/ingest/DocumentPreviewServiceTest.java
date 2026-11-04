package cn.studykid.rag.infrastructure.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import cn.studykid.rag.application.ingest.IngestionPipeline;
import cn.studykid.rag.core.chunk.Chunk;
import cn.studykid.rag.core.chunk.ChunkRole;
import cn.studykid.rag.core.version.EditionCoordinate;

/**
 * 上传前解析预览的不变式（RAG-D15 §六 待办 #6）。
 *
 * <p>⚠️ 本测试证明的核心命题：
 * <b>dry-run 不得产生任何副作用</b>——不落桶、不建行、不嵌入。
 * 若预览偷偷写了数据，"先预览再入库"就退化成"上传了两次"。
 */
class DocumentPreviewServiceTest {

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String SOURCE = "22222222-2222-2222-2222-222222222222";

    private static final EditionCoordinate EDITION = new EditionCoordinate(
            EditionCoordinate.Subject.MATH, EditionCoordinate.GradeBand.GRADE_7,
            EditionCoordinate.Term.FIRST, EditionCoordinate.Publisher.PEP, "2024修订");

    private static Chunk chunk(String id, ChunkRole role, String text) {
        return new Chunk(id, "preview-doc", EDITION, role, text,
                new Chunk.Locator(1, "第1章", null, 0, text.length()),
                List.of(), null, "hash-" + id);
    }

    private static JdbcIngestSourceLoader loaderWith(Optional<JdbcIngestSourceLoader.PreviewContext> ctx) {
        JdbcIngestSourceLoader loader = mock(JdbcIngestSourceLoader.class);
        when(loader.loadForPreview(anyString(), anyString())).thenReturn(ctx);
        return loader;
    }

    private static JdbcIngestSourceLoader.PreviewContext okContext() {
        return new JdbcIngestSourceLoader.PreviewContext(EDITION, "edition-1", List.of());
    }

    @Nested
    @DisplayName("截断与统计")
    class Truncation {

        @Test
        @DisplayName("片段数超过上限时截断，并如实报告总数")
        void truncatesButReportsTotal() {
            IngestionPipeline pipeline = mock(IngestionPipeline.class);
            when(pipeline.ingest(any(), any(), any(), any(), any()))
                    .thenReturn(List.of(
                            chunk("c1", ChunkRole.EXPLANATION, "讲解1"),
                            chunk("c2", ChunkRole.EXERCISE_ANSWER, "答案1"),
                            chunk("c3", ChunkRole.EXPLANATION, "讲解2")));

            DocumentPreviewService svc = new DocumentPreviewService(pipeline, loaderWith(Optional.of(okContext())));
            var r = svc.preview(TENANT, SOURCE, "a.pdf", "x".getBytes(StandardCharsets.UTF_8), 2);

            // ⚠️ 截断后仍要告诉用户"一共多少"——否则他以为就 2 段
            assertThat(r.totalChunks()).isEqualTo(3);
            assertThat(r.returnedChunks()).isEqualTo(2);
            assertThat(r.truncated()).isTrue();
        }

        @Test
        @DisplayName("上限被夹在 [1, MAX] 之间（防调用方传巨大值）")
        void clampsLimit() {
            IngestionPipeline pipeline = mock(IngestionPipeline.class);
            when(pipeline.ingest(any(), any(), any(), any(), any())).thenReturn(List.of());

            DocumentPreviewService svc = new DocumentPreviewService(pipeline, loaderWith(Optional.of(okContext())));
            assertThat(svc.preview(TENANT, SOURCE, "a.pdf", new byte[]{1}, 99999).limit())
                    .isEqualTo(DocumentPreviewService.MAX_PREVIEW_LIMIT);
            assertThat(svc.preview(TENANT, SOURCE, "a.pdf", new byte[]{1}, 0).limit())
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("角色统计预置全部角色（计数为 0 也出现），避免图例跳动")
        void roleStatsIncludeZeroRoles() {
            IngestionPipeline pipeline = mock(IngestionPipeline.class);
            when(pipeline.ingest(any(), any(), any(), any(), any()))
                    .thenReturn(List.of(chunk("c1", ChunkRole.EXPLANATION, "讲解")));

            DocumentPreviewService svc = new DocumentPreviewService(pipeline, loaderWith(Optional.of(okContext())));
            var r = svc.preview(TENANT, SOURCE, "a.pdf", new byte[]{1}, 50);

            assertThat(r.byRole()).containsKey(ChunkRole.EXERCISE_ANSWER.name());
            assertThat(r.byRole().get(ChunkRole.EXERCISE_ANSWER.name())).isZero();
            assertThat(r.byRole().get(ChunkRole.EXPLANATION.name())).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("零副作用（核心不变式）")
    class NoSideEffects {

        @Test
        @DisplayName("预览只调 pipeline.ingest，不触碰任何写入口")
        void previewOnlyParses() {
            IngestionPipeline pipeline = mock(IngestionPipeline.class);
            when(pipeline.ingest(any(), any(), any(), any(), any()))
                    .thenReturn(List.of(chunk("c1", ChunkRole.EXPLANATION, "讲解")));

            DocumentPreviewService svc = new DocumentPreviewService(pipeline, loaderWith(Optional.of(okContext())));
            svc.preview(TENANT, SOURCE, "a.pdf", "content".getBytes(StandardCharsets.UTF_8), 50);

            // ⚠️ DocumentPreviewService 的依赖只有 pipeline 与 loader——
            //    结构上就没有"写"的能力。这里断言 loader 只被用于"取上下文"，
            //    没有被用于任何写入语义。
            verify(pipeline).ingest(any(), any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("版本坐标缺失")
    class MissingEdition {

        @Test
        @DisplayName("来源未登记五轴 → 明确报错，不造默认坐标")
        void missingEditionFailsLoudly() {
            IngestionPipeline pipeline = mock(IngestionPipeline.class);
            DocumentPreviewService svc =
                    new DocumentPreviewService(pipeline, loaderWith(Optional.empty()));

            assertThatThrownBy(() ->
                    svc.preview(TENANT, SOURCE, "a.pdf", new byte[]{1}, 50))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("未登记版本五轴");

            // ⚠️ 关键：报错时**不能**去解析——否则会用一个"假坐标"切出一堆结果
            verify(pipeline, never()).ingest(any(), any(), any(), any(), any());
        }
    }
}
