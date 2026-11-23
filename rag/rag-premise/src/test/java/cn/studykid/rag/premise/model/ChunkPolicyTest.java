package cn.studykid.rag.premise.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link ChunkPolicy} 的策略解析与留痕语义测试（RAG-D28 P0-7 补齐）。
 *
 * <p>⚠️ 该枚举值落库到 {@code rag_document.chunk_policy} / {@code rag_ingest_job.chunk_policy}，
 * 是「这批语料按哪套规则切」的权威留痕。测试锁定两条契约：
 * <ol>
 *   <li><b>未知配置值 fail-closed</b>：拼错时抛异常，绝不静默回退 heading，
 *       否则实际策略与留痕不一致、重灌判断失真；</li>
 *   <li><b>桥依赖语义自包含</b>：只有 HYBRID_CHUNKER 需要外部分块桥，
 *       装配方据此决定是否校验桥端点。</li>
 * </ol>
 */
class ChunkPolicyTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "\t"})
    @DisplayName("空配置值回退 HEADING_HIERARCHY 默认（无留痕歧义）")
    void blankValueFallsBackToDefault(String value) {
        assertThat(ChunkPolicy.fromConfigValue(value)).isEqualTo(ChunkPolicy.HEADING_HIERARCHY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"heading", "HEADING", "Heading_Hierarchy", " heading "})
    @DisplayName("heading 家族大小写/简写不敏感，统一解析为 HEADING_HIERARCHY")
    void headingFamily(String value) {
        assertThat(ChunkPolicy.fromConfigValue(value)).isEqualTo(ChunkPolicy.HEADING_HIERARCHY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"hybrid", "HYBRID", "Hybrid_Chunker", " hybrid "})
    @DisplayName("hybrid 家族大小写/简写不敏感，统一解析为 HYBRID_CHUNKER")
    void hybridFamily(String value) {
        assertThat(ChunkPolicy.fromConfigValue(value)).isEqualTo(ChunkPolicy.HYBRID_CHUNKER);
    }

    @ParameterizedTest
    @ValueSource(strings = {"hybrd", "HybridChunker", "docling-hybrid", "H", "1"})
    @DisplayName("反证：未知策略值一律抛异常，绝不静默回退（留痕一致性守卫）")
    void unknownValueFailsClosed(String value) {
        assertThatThrownBy(() -> ChunkPolicy.fromConfigValue(value))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知切分策略配置值")
                .hasMessageContaining(value);
    }

    @Test
    @DisplayName("只有 HYBRID_CHUNKER 依赖分块桥；HEADING_HIERARCHY 纯 Java")
    void bridgeDependencyIsSelfDescribing() {
        assertThat(ChunkPolicy.HEADING_HIERARCHY.requiresChunkBridge()).isFalse();
        assertThat(ChunkPolicy.HEADING_HIERARCHY.isHeadingHierarchy()).isTrue();
        assertThat(ChunkPolicy.HYBRID_CHUNKER.requiresChunkBridge()).isTrue();
        assertThat(ChunkPolicy.HYBRID_CHUNKER.isHeadingHierarchy()).isFalse();
    }
}
