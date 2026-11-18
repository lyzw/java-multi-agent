package cn.studykid.multiagent.infrastructure.agentchataudit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 确定性采样器的行为验证。
 *
 * <p><b>为什么要测分布</b>：采样率是写进评估报告的口径。若哈希分布有偏，
 * 「按 5% 抽样」实际抽到 15%，报告里的结论就建立在一个错的前提上，
 * 而<b>没有任何报错</b>。反向验证：换回 {@code String.hashCode()} 后
 * 同前缀 traceId 的桶位会明显聚集，本测试的分布断言会变红。
 */
class ChatAuditSamplingTest {

    @Test
    @DisplayName("边界：rate=1 全量、rate=0 全不采")
    void boundaries() {
        for (int i = 0; i < 100; i++) {
            assertThat(SamplingDecider.shouldRecord(1.0d, "trace-" + i)).isTrue();
            assertThat(SamplingDecider.shouldRecord(0.0d, "trace-" + i)).isFalse();
        }
    }

    @Test
    @DisplayName("确定性：同一 auditId 永远同一结论")
    void deterministic() {
        String auditId = "3f9a4c1e-0000-4000-8000-000000000001";
        double first = SamplingDecider.bucket(auditId);
        for (int i = 0; i < 5; i++) {
            assertThat(SamplingDecider.bucket(auditId)).isEqualTo(first);
        }
    }

    @Test
    @DisplayName("分布：同前缀 traceId 在 10% 采样下命中数落在合理区间")
    void distributionIsNotClustered() {
        int hits = 0;
        int total = 5_000;
        for (int i = 0; i < total; i++) {
            // 刻意用同前缀 + 递增序号：这是 String.hashCode() 最容易聚集的形态
            if (SamplingDecider.shouldRecord(0.10d, "trace-same-prefix-" + i)) {
                hits++;
            }
        }
        // 期望 500；允许 ±40% 的宽松区间，但绝不能是 0 或 2000+
        assertThat(hits).isBetween(300, 700);
    }

    @Test
    @DisplayName("不同 auditId 的桶位不重复堆叠")
    void bucketsSpread() {
        Set<Long> buckets = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            buckets.add(Math.round(SamplingDecider.bucket("id-" + i) * 1_000_000));
        }
        assertThat(buckets).hasSizeGreaterThan(150);
    }
}
