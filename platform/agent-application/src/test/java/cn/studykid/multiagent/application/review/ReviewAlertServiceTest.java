package cn.studykid.multiagent.application.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.application.review.ReviewAlertService.ReviewableFacts;
import cn.studykid.multiagent.application.review.ReviewAlertService.Verdict;
import cn.studykid.multiagent.ports.persistence.ReviewTaskPort;
import cn.studykid.multiagent.ports.persistence.ReviewTaskPort.Priority;
import cn.studykid.multiagent.ports.persistence.ReviewTaskPort.ReviewTaskSpec;
import cn.studykid.multiagent.ports.persistence.ReviewTaskPort.ReviewTaskView;
import cn.studykid.multiagent.ports.persistence.ReviewTaskPort.Source;

/**
 * 告警联动单测（P2）。
 *
 * <p>核心断言：<b>规则判定是纯函数</b>（不依赖 DB），
 * 且「未命中就不建任务」——否则每次采集都会往队列塞正常 Trace，
 * reviewer 打开队列全是无关项，队列就没人看了。
 */
class ReviewAlertServiceTest {

    private static final String TENANT = "t-1";
    private static final String TRACE = "trace-1";
    private static final String RUN = "run-1";

    /** 替身队列：记录建的任务；可模拟「已有未终结任务」。 */
    private static final class FakeReviewTasks implements ReviewTaskPort {
        final List<ReviewTaskSpec> created = new ArrayList<>();
        boolean openExists = false;

        @Override
        public String create(ReviewTaskSpec spec) {
            created.add(spec);
            return "task-" + created.size();
        }

        @Override
        public void assign(String taskId, String assignee) {
        }

        @Override
        public void resolve(String taskId, String rootCause, String note) {
        }

        @Override
        public void dismiss(String taskId, String note) {
        }

        @Override
        public boolean hasOpenTask(String traceId) {
            return openExists;
        }

        @Override
        public List<ReviewTaskView> list(String status, String tenantId, int limit) {
            return List.of();
        }
    }

    private static ReviewableFacts facts(int loop, boolean maxRounds, boolean safety,
                                        Long in, Long out) {
        return new ReviewableFacts(RUN, loop, maxRounds, safety, in, out);
    }

    @Test
    @DisplayName("✅ P2-A1 正常 Trace 不建任务（未命中不建是队列信噪比的前提）")
    void normalTraceNoTask() {
        FakeReviewTasks port = new FakeReviewTasks();
        ReviewAlertService svc = new ReviewAlertService(port);

        Optional<String> r = svc.evaluate(TENANT, TRACE, facts(0, false, false, 100L, 50L));

        assertThat(r).isEmpty();
        assertThat(port.created).isEmpty();
    }

    @Test
    @DisplayName("✅ P2-A2 疑似循环 → HIGH / ANOMALY")
    void loopSuspected() {
        FakeReviewTasks port = new FakeReviewTasks();
        ReviewAlertService svc = new ReviewAlertService(port);

        Optional<String> r = svc.evaluate(TENANT, TRACE, facts(2, false, false, 100L, 50L));

        assertThat(r).isPresent();
        assertThat(port.created).hasSize(1);
        ReviewTaskSpec spec = port.created.get(0);
        assertThat(spec.source()).isEqualTo(Source.ANOMALY);
        assertThat(spec.priority()).isEqualTo(Priority.HIGH);
        assertThat(spec.note()).contains("循环");
    }

    @Test
    @DisplayName("✅ P2-A3 触达最大轮次 → HIGH / ANOMALY")
    void maxRounds() {
        FakeReviewTasks port = new FakeReviewTasks();
        ReviewAlertService svc = new ReviewAlertService(port);

        svc.evaluate(TENANT, TRACE, facts(0, true, false, 100L, 50L));

        assertThat(port.created).hasSize(1);
        assertThat(port.created.get(0).source()).isEqualTo(Source.ANOMALY);
        assertThat(port.created.get(0).priority()).isEqualTo(Priority.HIGH);
    }

    @Test
    @DisplayName("✅ P2-A4 安全拦截 → HIGH / ALERT（优先级高于循环）")
    void safetyBlockWins() {
        FakeReviewTasks port = new FakeReviewTasks();
        ReviewAlertService svc = new ReviewAlertService(port);

        svc.evaluate(TENANT, TRACE, facts(3, true, true, 100L, 50L));

        assertThat(port.created).hasSize(1);
        assertThat(port.created.get(0).source()).isEqualTo(Source.ALERT);
        assertThat(port.created.get(0).priority()).isEqualTo(Priority.HIGH);
        assertThat(port.created.get(0).note()).contains("安全");
    }

    @Test
    @DisplayName("✅ P2-A5 超大 token → NORMAL / ANOMALY（20000 阈值）")
    void oversizedToken() {
        FakeReviewTasks port = new FakeReviewTasks();
        ReviewAlertService svc = new ReviewAlertService(port);

        // 恰好等于阈值不触发（边界不误伤）
        svc.evaluate(TENANT, TRACE, facts(0, false, false, 15_000L, 5_000L));
        assertThat(port.created).isEmpty();

        // 超过阈值触发
        svc.evaluate(TENANT, TRACE, facts(0, false, false, 15_000L, 5_001L));
        assertThat(port.created).hasSize(1);
        assertThat(port.created.get(0).source()).isEqualTo(Source.ANOMALY);
        assertThat(port.created.get(0).priority()).isEqualTo(Priority.NORMAL);
    }

    @Test
    @DisplayName("✅ P2-A6 token 未采集（null）不判「超大」")
    void nullTokensNotOversized() {
        FakeReviewTasks port = new FakeReviewTasks();
        ReviewAlertService svc = new ReviewAlertService(port);

        svc.evaluate(TENANT, TRACE, facts(0, false, false, null, null));

        assertThat(port.created).isEmpty();
    }

    @Test
    @DisplayName("✅ P2-A7 已有未终结任务 → 不重复建（去重闸门）")
    void dedupe() {
        FakeReviewTasks port = new FakeReviewTasks();
        port.openExists = true;
        ReviewAlertService svc = new ReviewAlertService(port);

        Optional<String> r = svc.evaluate(TENANT, TRACE, facts(5, false, false, 100L, 50L));

        assertThat(r).isEmpty();
        assertThat(port.created).isEmpty();
    }

    @Test
    @DisplayName("✅ P2-A8 端口抛异常时告警联动 fail-soft（不冒泡）")
    void failSoftOnPortError() {
        ReviewTaskPort broken = new ReviewTaskPort() {
            @Override
            public String create(ReviewTaskSpec spec) {
                throw new IllegalStateException("DB 抖动");
            }

            @Override
            public void assign(String taskId, String assignee) {
            }

            @Override
            public void resolve(String taskId, String rootCause, String note) {
            }

            @Override
            public void dismiss(String taskId, String note) {
            }

            @Override
            public boolean hasOpenTask(String traceId) {
                return false;
            }

            @Override
            public List<ReviewTaskView> list(String status, String tenantId, int limit) {
                return List.of();
            }
        };
        ReviewAlertService svc = new ReviewAlertService(broken);

        Optional<String> r = svc.evaluate(TENANT, TRACE, facts(3, false, false, 100L, 50L));

        assertThat(r).isEmpty(); // 吞掉异常，不影响采集链路
    }

    @Test
    @DisplayName("✅ P2-A9 judge 是纯函数：同一输入恒等结论")
    void judgeIsPure() {
        ReviewAlertService svc = new ReviewAlertService(new FakeReviewTasks());
        ReviewableFacts f = facts(1, false, false, 100L, 50L);

        Optional<Verdict> a = svc.judge(f);
        Optional<Verdict> b = svc.judge(f);

        assertThat(a).isPresent();
        assertThat(a.get().source()).isEqualTo(b.get().source());
        assertThat(a.get().priority()).isEqualTo(b.get().priority());
    }
}
