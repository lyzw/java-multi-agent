package cn.studykid.rag.api.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import cn.studykid.rag.core.retrieval.RetrievalRequest.RetrievalMode;
import cn.studykid.rag.ports.EmbeddingUnavailableException;
import cn.studykid.rag.ports.TenantScopedGeneration;
import cn.studykid.rag.ports.TenantScopedRetrieval;

/**
 * 2026-11-10 E2E 缺陷 D-03 守卫：嵌入模型服务不可达时，检索端点必须给 503 + 可执行提示。
 *
 * <p>修复前本机 Ollama 未启动 → {@code OpenAiCompatibleEmbeddingPort} 抛泛化
 * {@link IllegalStateException} → Spring 默认 {@code HTTP 500 Internal Server Error}
 * （响应体里没有任何原因），运维只能靠翻日志猜。
 *
 * <p><b>⚠️ 反向验证</b>：把控制器里的 {@code catch (EmbeddingUnavailableException)} 撤掉，
 * 第一个用例会直接抛出 {@link EmbeddingUnavailableException}（不是
 * {@link ResponseStatusException}）而变红。
 */
class RagRetrievalControllerEmbeddingUnavailableTest {

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";

    private final TenantScopedRetrieval retrieval = mock(TenantScopedRetrieval.class);
    private final TenantScopedGeneration generation = mock(TenantScopedGeneration.class);
    private final RagRetrievalController controller =
            new RagRetrievalController(retrieval, generation);

    private static RetrievalRequestDto request() {
        return new RetrievalRequestDto(
                "二次函数的顶点式怎么推导？", RetrievalMode.EXPLAIN,
                new EditionDto("数学", "八年级", "上册", "人教版", "2024修订"),
                null, null, null, null, 8);
    }

    @Test
    @DisplayName("嵌入服务不可达 → 503 并点名要检查的部署配置（不是无名 500）")
    void embeddingUnavailableMapsToServiceUnavailable() {
        when(retrieval.retrieve(any(), any())).thenThrow(new EmbeddingUnavailableException(
                "Embedding 请求失败：http://127.0.0.1:11434/v1/embeddings（ConnectException）"));

        assertThatThrownBy(() -> controller.retrieve(TENANT, request()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> {
                    ResponseStatusException ex = (ResponseStatusException) e;
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(ex.getReason()).contains("嵌入模型服务不可用");
                    assertThat(ex.getReason()).contains("RAG_EMBEDDING_BASE_URL");
                });
        verify(retrieval).retrieve(any(), any());
        // ⚠️ 绝不静默降级（例如改走纯词法检索却报成功）：生成侧根本不该被调用。
        verifyNoInteractions(generation);
    }

    @Test
    @DisplayName("契约/配置错误仍原样抛出，不被伪装成「稍后重试」的 503")
    void contractErrorIsNotDisguisedAsUnavailable() {
        when(retrieval.retrieve(any(), any()))
                .thenThrow(new IllegalStateException("Embedding 维度不匹配"));

        assertThatThrownBy(() -> controller.retrieve(TENANT, request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("维度不匹配");
    }
}
