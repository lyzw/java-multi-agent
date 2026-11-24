package cn.studykid.rag.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.rag.core.embedding.EmbeddingInputTemplate;

/**
 * Embedding 输入模板配置守卫（RAG-D28 §2.4 / P3-1）。
 */
class RagEmbeddingInputPropertiesTest {

    @Test
    @DisplayName("默认模板版本合法，validate 放行")
    void defaultVersionPasses() {
        RagEmbeddingInputProperties props = new RagEmbeddingInputProperties();
        assertEquals(EmbeddingInputTemplate.VERSION, props.getTemplateVersion());
        props.validate();
    }

    @Test
    @DisplayName("未知模板版本 fail-closed：装配期抛错，不回退 v1")
    void unknownVersionFailsClosed() {
        RagEmbeddingInputProperties props = new RagEmbeddingInputProperties();
        props.setTemplateVersion("embedding-input-v2-not-implemented");
        assertThrows(IllegalStateException.class, props::validate);
    }

    @Test
    @DisplayName("MRL 输出维度默认 1024（D29 DEC-P2），仅留痕不改列")
    void defaultDimensionIs1024() {
        assertEquals(1024, new RagEmbeddingInputProperties().getRealOutputDimension());
    }
}
