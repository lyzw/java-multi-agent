package cn.studykid.multiagent.infra.retrieval.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.ResponseEntity;

/**
 * G3 检索引擎端到端接线测试（P3-D10）。
 *
 * <p>⚠️ 守门：真实向量库为外部依赖（A-008 阻断），本测试不依赖 PG，
 * 仅验证「端点 → 服务 → Authority Triple Gate」的接线与响应形态，
 * 用 {@code IT_PG_URL} 守门保持与项目 IT 约定一致（无环境时跳过）。
 * 核心门控逻辑由 {@link RetrievalServiceTest}（必跑单测）覆盖，避免假绿。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
public class RetrievalEngineIT {

    private RetrievalEndpoint endpoint(double vectorBase) {
        VectorSearchEngine v = new VectorSearchEngine();
        v.setBaseScoreForTest(vectorBase);
        RetrievalService svc = new RetrievalService("tenant-x", v, new TfIdfRanker(10, 0.5, 0.5));
        return new RetrievalEndpoint(svc);
    }

    @Test
    void endpoint_returns_success_when_gate_passes() {
        RetrievalEndpoint.RetrievalRequest req = new RetrievalEndpoint.RetrievalRequest(
                "tenant-x", "ws-1", Instant.now().minusSeconds(60), "math");
        ResponseEntity<RetrievalEndpoint.RetrievalResponse> resp = endpoint(0.9).workspace(req);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resp.getBody().passed()).isTrue();
    }

    @Test
    void endpoint_returns_failed_when_rls_mismatch() {
        RetrievalEndpoint.RetrievalRequest req = new RetrievalEndpoint.RetrievalRequest(
                "other", "ws-1", Instant.now(), "math");
        ResponseEntity<RetrievalEndpoint.RetrievalResponse> resp = endpoint(0.9).workspace(req);
        assertThat(resp.getBody().passed()).isFalse();
        assertThat(resp.getBody().failureReason()).isEqualTo("RLS_MISMATCH");
    }

    @Test
    void endpoint_returns_bad_request_when_tenant_blank() {
        RetrievalEndpoint.RetrievalRequest req = new RetrievalEndpoint.RetrievalRequest(
                null, "ws-1", Instant.now(), "math");
        ResponseEntity<RetrievalEndpoint.RetrievalResponse> resp = endpoint(0.9).workspace(req);
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
    }
}
