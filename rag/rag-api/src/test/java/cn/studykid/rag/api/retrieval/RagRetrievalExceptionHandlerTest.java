package cn.studykid.rag.api.retrieval;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import cn.studykid.rag.ports.EmbeddingUnavailableException;
import cn.studykid.rag.ports.TenantScopedGeneration;
import cn.studykid.rag.ports.TenantScopedRetrieval;

/**
 * 2026-11-10 E2E 缺陷 D-03 守卫（第二层）：检索面的 503 必须把<b>可执行原因</b>
 * 同时交给调用方与服务端日志，而不是被 Spring 默认 {@code /error} 吞掉。
 *
 * <p><b>⚠️ 反向验证</b>：
 * <ul>
 *   <li>撤掉 {@link RagRetrievalExceptionHandler} 的 {@code @ExceptionHandler} 方法
 *       → 第一个用例拿不到 {@code $.message}，变红；</li>
 *   <li>把 advice 的 {@code assignableTypes} 改成别的控制器
 *       → 第二个用例的 400 原因丢失，变红。</li>
 * </ul>
 */
class RagRetrievalExceptionHandlerTest {

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";

    private static final String VALID_BODY = "{\"query\":\"二次函数的顶点式怎么推导？\","
            + "\"mode\":\"EXPLAIN\","
            + "\"edition\":{\"subject\":\"数学\",\"grade\":\"八年级\",\"term\":\"上册\","
            + "\"publisher\":\"人教版\",\"revisionCode\":\"2024修订\"},"
            + "\"scopeMode\":\"STRICT\",\"topK\":8}";

    private final TenantScopedRetrieval retrieval = mock(TenantScopedRetrieval.class);
    private final TenantScopedGeneration generation = mock(TenantScopedGeneration.class);
    private final RagRetrievalController controller =
            new RagRetrievalController(retrieval, generation);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new RagRetrievalExceptionHandler())
            .build();

    @Test
    @DisplayName("嵌入服务不可达 → 503 响应体带配置键名，绝不静默降级")
    void embeddingUnavailableReturnsActionable503() throws Exception {
        when(retrieval.retrieve(any(), any())).thenThrow(new EmbeddingUnavailableException(
                "Embedding 请求失败：http://127.0.0.1:11434/v1/embeddings（ConnectException）"));

        mvc.perform(post("/api/v1/retrieval")
                        .header(RagRetrievalController.TENANT_HEADER, TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.message").value(containsString("嵌入模型服务不可用")))
                .andExpect(jsonPath("$.message").value(containsString("RAG_EMBEDDING_BASE_URL")))
                // 只回原因，不回堆栈/上游响应体（可能含凭据或语料片段）
                .andExpect(jsonPath("$.stackTrace").doesNotExist());

        // 拿不到稠密向量时生成侧绝不能被调用（不降级成词法检索还报成功）
        verifyNoInteractions(generation);
    }

    @Test
    @DisplayName("治理性 4xx 同样带回领域层原话，而不是笼统 Bad Request")
    void badRequestReasonIsPreserved() throws Exception {
        mvc.perform(post("/api/v1/retrieval")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(containsString("X-Tenant-Id")));
    }

    @Test
    @DisplayName("advice 只作用于检索控制器，不污染同容器里的管理面")
    void adviceIsScopedToRetrievalController() {
        RestControllerAdvice advice =
                RagRetrievalExceptionHandler.class.getAnnotation(RestControllerAdvice.class);

        org.assertj.core.api.Assertions.assertThat(advice).isNotNull();
        org.assertj.core.api.Assertions.assertThat(advice.assignableTypes())
                .containsExactly(RagRetrievalController.class);
    }
}
