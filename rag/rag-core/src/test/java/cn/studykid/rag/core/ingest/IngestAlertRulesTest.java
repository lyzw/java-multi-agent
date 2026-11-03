package cn.studykid.rag.core.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 摄取链告警规则的测试（V1.0.2 S4-1，T-6 建议默认阈值）。
 *
 * <p>⚠️ 关键反证：<b>撤回取消不得计入失败率</b>——
 * 否则「法务撤回一批教材」会直接触发 CRITICAL 告警（N2）。
 * 该语义由调用方保证（只把 countsAsFailure() 为真的计入 failed），
 * 本测试锁住规则侧的边界行为。
 */
class IngestAlertRulesTest {

    @Test
    @DisplayName("失败率超 20% → CRITICAL")
    void failureRateCritical() {
        IngestAlertRules.Alert a = IngestAlertRules.evaluateFailureRate(5, 20);
        assertNotNull(a);
        assertEquals(IngestAlertRules.Level.CRITICAL, a.level());
        assertEquals(0.25, a.value(), 1e-9);
    }

    @Test
    @DisplayName("失败率恰好 20% → 不告警（阈值是「超过」而非「达到」）")
    void failureRateBoundary() {
        assertNull(IngestAlertRules.evaluateFailureRate(4, 20));
        assertNotNull(IngestAlertRules.evaluateFailureRate(5, 20));
    }

    @Test
    @DisplayName("无已结束 job → 不告警（避免刚上传时除零/误报）")
    void noFinishedNoAlert() {
        assertNull(IngestAlertRules.evaluateFailureRate(0, 0));
    }

    @Test
    @DisplayName("积压需同时满足条数与时长")
    void backlogNeedsBoth() {
        assertNull(IngestAlertRules.evaluateBacklog(100, 5), "条数够但时长不够");
        assertNull(IngestAlertRules.evaluateBacklog(10, 60), "时长够但条数不够");
        IngestAlertRules.Alert a = IngestAlertRules.evaluateBacklog(51, 16);
        assertNotNull(a);
        assertEquals(IngestAlertRules.Level.WARNING, a.level());
    }

    @Test
    @DisplayName("Sweeper 接管超 3 次 → WARNING")
    void sweeperTakeovers() {
        assertNull(IngestAlertRules.evaluateSweeperTakeovers(3));
        IngestAlertRules.Alert a = IngestAlertRules.evaluateSweeperTakeovers(4);
        assertNotNull(a);
        assertEquals(IngestAlertRules.Level.WARNING, a.level());
    }

    @Test
    @DisplayName("同来源连续失败 ≥3 → WARNING 且提示换文件")
    void repeatedFailure() {
        assertNull(IngestAlertRules.evaluateRepeatedFailure("s1", 2));
        IngestAlertRules.Alert a = IngestAlertRules.evaluateRepeatedFailure("s1", 3);
        assertNotNull(a);
        assertTrue(a.message().contains("换文件"),
                "连续失败几乎一定是文件问题，提示应指向换文件而非重试");
    }

    @Test
    @DisplayName("evaluateAll 汇总多条告警")
    void evaluateAllCollects() {
        List<IngestAlertRules.Alert> alerts =
                IngestAlertRules.evaluateAll(5, 20, 100, 30, 5);
        assertEquals(3, alerts.size(), "失败率 + 积压 + Sweeper 三条都应触发");
    }

    @Test
    @DisplayName("一切正常时无告警")
    void allClear() {
        assertTrue(IngestAlertRules.evaluateAll(0, 10, 2, 1, 0).isEmpty());
    }
}
