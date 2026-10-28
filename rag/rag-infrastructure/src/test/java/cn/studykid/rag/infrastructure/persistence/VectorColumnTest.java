package cn.studykid.rag.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import cn.studykid.rag.infrastructure.embedding.LocalDeterministicEmbeddingPort;

/**
 * {@link VectorColumn} 的契约测试：cast 白名单、维度校验与 ANN 索引上限。
 *
 * <p>这里守的是「换模型/迁列时最容易出错的那一步」，所以断言集中在<b>拒绝</b>行为上。
 */
class VectorColumnTest {

    @Test
    void exposesCastPlaceholderForBothSupportedTypes() {
        assertEquals("?::vector", new VectorColumn("vector", 1024).castPlaceholder());
        assertEquals("?::halfvec", new VectorColumn("halfvec", 4096).castPlaceholder());
    }

    @Test
    void rejectsTypesOutsideTheWhitelist() {
        // cast 要拼进 SQL：任何非白名单类型名都必须在装配期被拒
        for (String bad : new String[] {"vec**; DROP TABLE", "float4[]", "VECTOR", ""}) {
            assertThrows(IllegalArgumentException.class, () -> new VectorColumn(bad, 1024), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> new VectorColumn(null, 1024));
        assertThrows(IllegalArgumentException.class, () -> new VectorColumn("vector", 0));
    }

    @Test
    void requireDimensionFailsBeforeHittingTheDatabase() {
        VectorColumn column = new VectorColumn("halfvec", 4096);
        column.requireDimension(new float[4096]);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> column.requireDimension(new float[1024]));
        assertTrue(e.getMessage().contains("halfvec(4096)"), e.getMessage());
        assertTrue(e.getMessage().contains("实际 1024 维"), e.getMessage());
    }

    @Test
    void annIndexLimitsMatchPgvectorCapabilities() {
        // pgvector 0.8.x：HNSW 最多 vector 2000 维 / halfvec 4000 维
        assertFalse(new VectorColumn("vector", 4096).indexable());
        assertFalse(new VectorColumn("halfvec", 4096).indexable());
        assertTrue(new VectorColumn("halfvec", 4000).indexable());
        assertTrue(new VectorColumn("vector", 2000).indexable());
        // 占位端口（1024 维）在现有 DDL 下始终可建索引
        assertTrue(new VectorColumn("vector",
                LocalDeterministicEmbeddingPort.DIM).indexable());
    }
}
