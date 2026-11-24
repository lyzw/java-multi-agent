package cn.studykid.rag.infrastructure.indexing;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * P3-1 语义槽位的 SQL 接线守卫：两条重算扫描 SQL 必须读两列，
 * 两个落库 INSERT 必须写两列。任一侧漏掉都会造成「摄取有语义、重算无语义」
 * 的静默向量漂移（运行时不报错，评测只表现为召回变化）。
 *
 * <p>⚠️ 源码级断言只能廉价挡住回退；行为级同构由
 * {@code EmbeddingInputIsomorphismTest} 与真库 IT 覆盖。
 */
class EmbeddingSemanticsSqlGuardTest {

    private static final Path SRC = Path.of("src/main/java/cn/studykid/rag/infrastructure");

    private static String read(String rel) throws Exception {
        return Files.readString(SRC.resolve(rel), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("两条重算扫描 SQL 都必须 SELECT 语义两列")
    void scanSqlReadsSemantics() throws Exception {
        String indexer = read("indexing/ChunkIndexer.java");
        String generation = read("indexing/IndexGenerationBuilder.java");
        for (String sql : new String[]{indexer, generation}) {
            assertTrue(sql.contains("c.formula_natural_language"),
                    "扫描 SQL 必须读 formula_natural_language");
            assertTrue(sql.contains("c.caption_or_table_summary"),
                    "扫描 SQL 必须读 caption_or_table_summary");
        }
    }

    @Test
    @DisplayName("落库必须写语义两列，且 ON CONFLICT 更新之（重灌不丢语义）")
    void insertWritesSemantics() throws Exception {
        String repo = Files.readString(
                Path.of("src/main/java/cn/studykid/rag/infrastructure/persistence/JdbcCorpusRepository.java"),
                StandardCharsets.UTF_8);
        // 两处 INSERT（租户 + 全局桶）都含列清单
        assertTrue(repo.contains("formula_natural_language, caption_or_table_summary"),
                "落库列清单必须含语义两列");
        assertTrue(repo.contains("formula_natural_language = EXCLUDED.formula_natural_language"),
                "ON CONFLICT 必须更新语义列，否则重灌后语义陈旧");
        assertTrue(repo.contains("caption_or_table_summary = EXCLUDED.caption_or_table_summary"),
                "ON CONFLICT 必须更新摘要列");
        // 两处写入点都取 Chunk 的槽位（至少出现两次：租户 + 全局）
        int writes = repo.split("chunk.formulaNaturalLanguage()", -1).length - 1;
        assertTrue(writes >= 2, "租户与全局桶两处 INSERT 都必须写语义槽位，实际=" + writes);
    }
}
