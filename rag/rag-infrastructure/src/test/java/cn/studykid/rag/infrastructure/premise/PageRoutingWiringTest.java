package cn.studykid.rag.infrastructure.premise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.rag.infrastructure.LocalRagPortsConfig;
import cn.studykid.rag.infrastructure.parsing.LocalDeterministicDocumentParserPort;
import cn.studykid.rag.premise.model.ChunkPolicy;

/**
 * 页级路由装配测试（D28 P1-1）：灰度开关默认关闭；启用时 fail-closed。
 */
class PageRoutingWiringTest {

    private final LocalRagPortsConfig config = new LocalRagPortsConfig(false);
    private final LocalDeterministicDocumentParserPort parser =
            new LocalDeterministicDocumentParserPort();

    @Test
    @DisplayName("默认关闭：既有装配行为完全不变（兼容重载）")
    void routingDisabledByDefault() {
        var pre = config.premisePreprocessor(parser, "docling", "", "standard", 600);
        assertThat(pre.parseBackendId()).isEqualTo("docling-serve");
        assertThat(pre.availablePolicies())
                .contains(ChunkPolicy.HEADING_HIERARCHY, ChunkPolicy.HYBRID_CHUNKER);
    }

    @Test
    @DisplayName("六参重载显式关闭：同样不包装路由")
    void routingExplicitlyDisabled() {
        var pre = config.premisePreprocessor(parser, "docling", "", "standard", 600, false);
        assertThat(pre.parseBackendId()).isEqualTo("docling-serve");
    }

    @Test
    @DisplayName("启用且配了 MinerU 端点：装配为路由后端，切分策略齐全")
    void routingEnabledWithEscalation() {
        var pre = config.premisePreprocessor(parser, "docling",
                "http://mineru.internal:8000", "standard", 600, true);
        assertThat(pre.parseBackendId()).isEqualTo("routing(docling-serve+mineru)");
        assertThat(pre.availablePolicies())
                .contains(ChunkPolicy.HEADING_HIERARCHY, ChunkPolicy.HYBRID_CHUNKER);
    }

    @Test
    @DisplayName("fail-closed：启用路由却没配 MinerU 端点 → 装配期拒绝启动")
    void routingEnabledWithoutEscalationEndpointFails() {
        assertThatThrownBy(() -> config.premisePreprocessor(
                parser, "docling", "", "standard", 600, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未配置");
    }

    @Test
    @DisplayName("fail-closed：primary 已是 MinerU → 无可升级档位，拒绝装配")
    void routingEnabledOnMineruPrimaryFails() {
        assertThatThrownBy(() -> config.premisePreprocessor(
                parser, "mineru", "http://mineru.internal:8000", "standard", 600, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已是最贵档位");
    }
}
