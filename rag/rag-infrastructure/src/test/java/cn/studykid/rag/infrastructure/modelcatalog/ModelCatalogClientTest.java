package cn.studykid.rag.infrastructure.modelcatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * {@link ModelCatalogClient#parse(String)} 解析逻辑测试（纯函数，无 HTTP）。
 */
class ModelCatalogClientTest {

    @Test
    void parsesEmbeddingModelWithDimension() {
        String json = """
                {
                  "scenario": "embedding",
                  "provider": "bge",
                  "modelName": "bge-m3",
                  "baseUrl": "https://embed.example/v1",
                  "dimension": 1024,
                  "supportsTools": false,
                  "supportsStreaming": false,
                  "supportsVision": false
                }
                """;

        ModelCatalogClient.ResolvedEmbeddingModel m = ModelCatalogClient.parse(json);
        assertThat(m.provider()).isEqualTo("bge");
        assertThat(m.modelName()).isEqualTo("bge-m3");
        assertThat(m.baseUrl()).isEqualTo("https://embed.example/v1");
        assertThat(m.dimension()).isEqualTo(1024);
    }

    @Test
    void parsesWithoutDimensionAsNull() {
        String json = """
                {"scenario":"embedding","provider":"openai","modelName":"text-embedding-3-small",
                 "baseUrl":"https://api.openai.com/v1"}""";
        ModelCatalogClient.ResolvedEmbeddingModel m = ModelCatalogClient.parse(json);
        assertThat(m.dimension()).isNull();
    }

    @Test
    void missingModelNameThrows() {
        String json = """
                {"scenario":"embedding","provider":"openai","baseUrl":"https://x/v1"}""";
        assertThatThrownBy(() -> ModelCatalogClient.parse(json))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("modelName");
    }
}
