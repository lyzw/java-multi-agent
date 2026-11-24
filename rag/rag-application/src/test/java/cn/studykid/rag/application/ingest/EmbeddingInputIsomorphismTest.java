package cn.studykid.rag.application.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.rag.core.chunk.Chunk;
import cn.studykid.rag.core.chunk.ChunkRole;
import cn.studykid.rag.core.embedding.EmbeddingInput;
import cn.studykid.rag.core.embedding.EmbeddingInputBuilder;
import cn.studykid.rag.core.embedding.EmbeddingInputTemplate;
import cn.studykid.rag.core.knowledge.KnowledgePoint;
import cn.studykid.rag.core.version.EditionCoordinate;

/**
 * 摄取路径与重索引路径的模板输入必须逐字同构（RAG-D28 P3-1 防向量漂移反证）。
 *
 * <p>⚠️ 为什么这是独立测试：摄取（内存 Chunk → {@link EmbeddingInputFactory}）与
 * 重索引（DB 行 → {@link EmbeddingInputBuilder}）是两条独立构造路径。
 * 任一处字段映射或排序漂移，同一片段会被嵌入成两个不同向量，
 * 而系统不会报错——评测只会表现为「召回莫名变化」。
 * 本测试钉死两者渲染结果逐字相等；撤掉任一侧的 canonicalName 排序即转红。
 */
class EmbeddingInputIsomorphismTest {

    private static EditionCoordinate edition() {
        return new EditionCoordinate(
                EditionCoordinate.Subject.MATH, EditionCoordinate.GradeBand.GRADE_7,
                EditionCoordinate.Term.FIRST, EditionCoordinate.Publisher.PEP, "2024");
    }

    private static List<KnowledgePoint> points() {
        // 故意乱序返回，验证工厂的确定化排序。
        return List.of(
                new KnowledgePoint("kp-2", "数学", "七年级", "抛物线对称轴",
                        List.of("轴对称性"), List.of(), List.of()),
                new KnowledgePoint("kp-1", "数学", "七年级", "二次函数顶点",
                        List.of("顶点式"), List.of(), List.of()));
    }

    @Test
    @DisplayName("摄取工厂与重索引 builder 渲染逐字相等（含知识点配对排序）")
    void ingestAndRescanRenderIdentically() {
        Chunk chunk = new Chunk("c-1", "d-1", edition(), ChunkRole.EXERCISE,
                "求抛物线的顶点坐标。",
                new Chunk.Locator(20, "第3章 > 3.2 二次函数", "Q003", 0, 30),
                // ⚠️ 故意让 kpIds 顺序与 canonicalName 排序**相反**（抛物线对称轴 > 二次函数顶点）：
                //    若工厂不排序，模板文本就与重索引侧 SQL 的 ORDER BY 结果不同 → 本测试转红。
                List.of("kp-2", "kp-1"), "Q003", "hash", null,
                null, Chunk.LEVEL_PARENT,
                "v = v_0 + at。匀变速直线运动速度时间公式，v 为末速度",
                "表格（2 行 2 列）；表头：知识点 难度");

        String viaIngest = EmbeddingInputTemplate.renderDocument(
                EmbeddingInputFactory.fromChunk(chunk, points()));

        // 重索引侧：模拟扫描 SQL 的扁平列（kp 已按 canonical_name 排序，别名同序）。
        // ⚠️ 两语义槽位必须逐字带过——任一侧漏传都会让本断言转红（防向量漂移）。
        String viaRescan = EmbeddingInputTemplate.renderDocument(
                EmbeddingInputBuilder.fromScanRow(
                        "人教版", "七年级", "上册", "2024", "第3章 > 3.2 二次函数",
                        20, "Q003", null, "EXERCISE", "求抛物线的顶点坐标。",
                        List.of("二次函数顶点", "抛物线对称轴"),
                        List.of("顶点式", "轴对称性"),
                        "v = v_0 + at。匀变速直线运动速度时间公式，v 为末速度",
                        "表格（2 行 2 列）；表头：知识点 难度"));

        assertEquals(viaIngest, viaRescan,
                "摄取与重索引必须产生同一 embedding 输入，否则重算会向量漂移");
    }

    @Test
    @DisplayName("撤点反证：重索引侧漏传语义槽位 → 文本必变（证明语义带过是承重的）")
    void droppedSemanticsChangesText() {
        Chunk chunk = new Chunk("c-1", "d-1", edition(), ChunkRole.EXERCISE,
                "求抛物线的顶点坐标。",
                new Chunk.Locator(20, "", "Q003", 0, 30),
                List.of(), "Q003", "hash", null, null, Chunk.LEVEL_PARENT,
                "F = ma。牛顿第二定律", null);

        String viaIngest = EmbeddingInputTemplate.renderDocument(
                EmbeddingInputFactory.fromChunk(chunk, points()));
        // 模拟重索引漏读 formula 列（传 null）——这是要防的漂移事故。
        String viaRescanDropped = EmbeddingInputTemplate.renderDocument(
                EmbeddingInputBuilder.fromScanRow(
                        "人教版", "七年级", "上册", "2024", "", 20, "Q003", null,
                        "EXERCISE", "求抛物线的顶点坐标。", List.of(), List.of(),
                        null, null));

        org.junit.jupiter.api.Assertions.assertNotEquals(viaIngest, viaRescanDropped,
                "若漏传语义槽位文本不变，说明槽位没真正进模板——反证失效");
        assertThat(viaIngest).contains("牛顿第二定律");
    }

    @Test
    @DisplayName("撤点反证：知识点顺序交换后文本必然改变（证明排序是承重的）")
    void swappedKnowledgeOrderChangesText() {
        EmbeddingInput a = EmbeddingInputBuilder.fromScanRow(
                "人教版", "七年级", "上册", "2024", "", 1, null, null, "EXPLANATION",
                "正文", List.of("A", "B"), List.of());
        EmbeddingInput b = EmbeddingInputBuilder.fromScanRow(
                "人教版", "七年级", "上册", "2024", "", 1, null, null, "EXPLANATION",
                "正文", List.of("B", "A"), List.of());
        org.junit.jupiter.api.Assertions.assertNotEquals(
                EmbeddingInputTemplate.renderDocument(a),
                EmbeddingInputTemplate.renderDocument(b),
                "若顺序不承重，排序代码就是假的——本断言保证排序真的改变输出");
    }
}
