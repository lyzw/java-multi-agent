package cn.studykid.multiagent.infra.retrieval.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * G3 检索引擎核心单测（P3-D10）。
 *
 * <p>不依赖外部资源，确定性验证 Authority Triple Gate 三门逻辑
 * （RLS / TTL / 语义分），符合 P3-D1「可证伪」精神。
 */
class RetrievalServiceTest {

    private static final String TENANT = "tenant-x";

    private RetrievalService service(double vectorBase) {
        VectorSearchEngine v = new VectorSearchEngine();
        v.setBaseScoreForTest(vectorBase);
        TfIdfRanker t = new TfIdfRanker(10, 0.5, 0.5);
        return new RetrievalService(TENANT, v, t);
    }

    private RetrievalService.ContextRecord okRecord() {
        return new RetrievalService.ContextRecord(
                "id-1", TENANT, Instant.now().minusSeconds(60), "math algebra");
    }

    @Test
    void gate_passes_when_all_three_ok() {
        RetrievalService.RetrievalResult r = service(0.9).retrieve(okRecord());
        assertThat(r.passed()).isTrue();
        assertThat(r.hybridScore()).isGreaterThan(0.0);
    }

    @Test
    void rls_mismatch_when_tenant_blank() {
        RetrievalService.ContextRecord rec = new RetrievalService.ContextRecord(
                "id-1", " ", Instant.now(), "t");
        RetrievalService.RetrievalResult r = service(0.9).retrieve(rec);
        assertThat(r.passed()).isFalse();
        assertThat(r.failureReason()).isEqualTo("RLS_MISMATCH");
    }

    @Test
    void rls_mismatch_when_tenant_not_expected() {
        RetrievalService.ContextRecord rec = new RetrievalService.ContextRecord(
                "id-1", "other-tenant", Instant.now(), "t");
        RetrievalService.RetrievalResult r = service(0.9).retrieve(rec);
        assertThat(r.passed()).isFalse();
        assertThat(r.failureReason()).isEqualTo("RLS_MISMATCH");
    }

    @Test
    void ttl_unverifiable_when_last_updated_null() {
        RetrievalService.ContextRecord rec = new RetrievalService.ContextRecord(
                "id-1", TENANT, null, "t");
        RetrievalService.RetrievalResult r = service(0.9).retrieve(rec);
        assertThat(r.passed()).isFalse();
        assertThat(r.failureReason()).isEqualTo("TTL_UNVERIFIABLE");
    }

    @Test
    void ttl_expired_when_older_than_90_days() {
        RetrievalService.ContextRecord rec = new RetrievalService.ContextRecord(
                "id-1", TENANT, Instant.now().minus(Duration.ofDays(100)), "t");
        RetrievalService.RetrievalResult r = service(0.9).retrieve(rec);
        assertThat(r.passed()).isFalse();
        assertThat(r.failureReason()).isEqualTo("TTL_EXPIRED");
    }

    @Test
    void vector_score_below_threshold_rejected() {
        RetrievalService.RetrievalResult r = service(0.1).retrieve(okRecord());
        assertThat(r.passed()).isFalse();
        assertThat(r.failureReason()).isEqualTo("VECTOR_SCORE_BELOW_THRESHOLD");
    }
}
