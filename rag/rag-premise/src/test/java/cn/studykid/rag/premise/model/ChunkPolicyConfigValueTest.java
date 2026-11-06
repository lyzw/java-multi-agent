package cn.studykid.rag.premise.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 配置解析（RAG-D26 阶段 2.4）：{@link ChunkPolicy#fromConfigValue}。
 *
 * <p>⚠️ 策略值要落库留痕，未知配置值必须 fail-closed
 * （静默回落会让实际策略与留痕不一致）。
 */
class ChunkPolicyConfigValueTest {

    @Test
    @DisplayName("合法配置值解析：全称与简写、大小写不敏感")
    void parsesKnownValues() {
        assertThat(ChunkPolicy.fromConfigValue("heading")).isEqualTo(ChunkPolicy.HEADING_HIERARCHY);
        assertThat(ChunkPolicy.fromConfigValue("HEADING_HIERARCHY"))
                .isEqualTo(ChunkPolicy.HEADING_HIERARCHY);
        assertThat(ChunkPolicy.fromConfigValue("Hybrid")).isEqualTo(ChunkPolicy.HYBRID_CHUNKER);
        assertThat(ChunkPolicy.fromConfigValue("hybrid_chunker"))
                .isEqualTo(ChunkPolicy.HYBRID_CHUNKER);
    }

    @Test
    @DisplayName("空值回落默认 HEADING_HIERARCHY（显式默认，非静默错配）")
    void blankFallsBackToDefault() {
        assertThat(ChunkPolicy.fromConfigValue(null)).isEqualTo(ChunkPolicy.HEADING_HIERARCHY);
        assertThat(ChunkPolicy.fromConfigValue("")).isEqualTo(ChunkPolicy.HEADING_HIERARCHY);
        assertThat(ChunkPolicy.fromConfigValue("  ")).isEqualTo(ChunkPolicy.HEADING_HIERARCHY);
    }

    @Test
    @DisplayName("未知值抛异常（fail-closed：不允许静默回落污染留痕）")
    void unknownValueFailsClosed() {
        assertThatThrownBy(() -> ChunkPolicy.fromConfigValue("docling-hybrid"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("docling-hybrid");
    }
}
