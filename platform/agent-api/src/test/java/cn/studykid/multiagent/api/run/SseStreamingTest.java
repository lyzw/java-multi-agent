package cn.studykid.multiagent.api.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.RateLimitPort;
import cn.studykid.multiagent.ports.persistence.RunEventQueryPort;
import cn.studykid.multiagent.ports.security.AccessDeniedException;
import cn.studykid.multiagent.ports.security.AuthorizationService;

/**
 * SSE 长连接推送测试（P3-D10 / FR-014 部分 / FR-017）。
 *
 * <p><b>核心命题</b>：
 * <ol>
 *   <li>长连接把轮询从<b>客户端</b>搬到<b>服务端</b>（延迟可预期）；</li>
 *   <li><b>终态必须关闭连接</b>——否则连接悬挂占资源，客户端等不到结束信号；</li>
 *   <li>建连必须过<b>限流</b>（D-4，收口 P2-D05 §6 L7）；</li>
 *   <li>长连接<b>不放松</b>红线 9 的授权与 404 存在性判定。</li>
 * </ol>
 */
class SseStreamingTest {

    private static final String TENANT = "6f5d33fc-723e-4f64-beda-9e4435c72691";
    private static final String RUN_ID = "run-1";

    // ── 测试替身 ────────────────────────────────────────────────────

    /** 事件查询替身：返回预置事件，并记录被调用的游标。 */
    private static final class FakeEventQuery implements RunEventQueryPort {
        final List<RunEvent> events = new ArrayList<>();
        final List<Long> cursorsSeen = new CopyOnWriteArrayList<>();
        final AtomicInteger calls = new AtomicInteger();
        volatile String state = "RUNNING";

        FakeEventQuery with(RunEvent... evs) {
            events.addAll(List.of(evs));
            return this;
        }

        @Override
        public String findState(String tenantId, String runId) {
            return state;
        }

        @Override
        public List<RunEvent> findAfter(String tenantId, String runId,
                                        long afterSequenceNo, int limit) {
            calls.incrementAndGet();
            cursorsSeen.add(afterSequenceNo);
            List<RunEvent> out = new ArrayList<>();
            for (RunEvent e : events) {
                if (e.sequenceNo() > afterSequenceNo) {
                    out.add(e);
                }
            }
            // ⚠️ 每次只返回一批，模拟「边读边推进游标」
            events.removeIf(e -> e.sequenceNo() <= afterSequenceNo);
            return out.size() > limit ? out.subList(0, limit) : out;
        }

    }

    private static RunEventQueryPort.RunEvent event(long seq, String type) {
        return new RunEventQueryPort.RunEvent(seq, type, Map.of("state", "RUNNING"),
                Instant.now());
    }

    private static RunController.CurrentPrincipal principal() {
        return new RunController.CurrentPrincipal() {
            @Override
            public TenantContext context() {
                return new TenantContext(TENANT, "user-1", Set.of(), 0L);
            }
            @Override public String principalId() { return "user-1"; }
            @Override public String providerId() { return "deepseek"; }
            @Override public String snapshotId() { return "snap-1"; }
            @Override public String correlationId() { return "corr-1"; }
        };
    }

    private static AuthorizationService allowAll() {
        return new AuthorizationService() {
            @Override public boolean isAuthorized(TenantContext c, String action, String resource) {
                return true;
            }
            @Override public long currentAuthzVersion(String tenantId, String principalId) {
                return 1L;
            }
            @Override public Set<String> rolesOf(TenantContext c) { return Set.of(); }
        };
    }

    private static AuthorizationService denyAll() {
        return new AuthorizationService() {
            @Override public boolean isAuthorized(TenantContext c, String action, String resource) {
                return false;
            }
            @Override public long currentAuthzVersion(String tenantId, String principalId) {
                return 1L;
            }
            @Override public Set<String> rolesOf(TenantContext c) { return Set.of(); }
        };
    }

    /** 记录型帧发送器（单测用；见 {@link RunEventStreamer.FrameSender}）。 */
    private static final class RecordingSender implements RunEventStreamer.FrameSender {
        final List<String> frames = new CopyOnWriteArrayList<>();
        final AtomicInteger completed = new AtomicInteger();
        final CountDownLatch completedLatch = new CountDownLatch(1);
        /** ⚠️ 至少收到一帧即放行——轮询是<b>异步</b>的，同步断言必然 flaky。 */
        final CountDownLatch firstFrame = new CountDownLatch(1);

        @Override
        public void send(String eventType, String sequenceNo, Object data) {
            frames.add(eventType + "#" + sequenceNo);
            firstFrame.countDown();
        }

        @Override
        public void complete() {
            completed.incrementAndGet();
            completedLatch.countDown();
        }

        boolean awaitComplete(long ms) throws InterruptedException {
            return completedLatch.await(ms, TimeUnit.MILLISECONDS);
        }

        boolean awaitFirstFrame(long ms) throws InterruptedException {
            return firstFrame.await(ms, TimeUnit.MILLISECONDS);
        }
    }

    /** 可脚本化限流替身。 */
    private static RateLimitPort limiter(RateLimitPort.Decision forced) {
        return new RateLimitPort() {
            @Override public Decision check(String tenantId, String bucketKey) {
                return forced;
            }
            @Override public long currentCount(String tenantId, String bucketKey) {
                return 0L;
            }
        };
    }

    // ── S-4：建连限流（D-4，收口 P2-D05 L7）───────────────────────

    @Nested
    @DisplayName("S-4 建连限流（P2-D05 §6 L7 收口）")
    class ConnectionRateLimit {

        @Test
        @DisplayName("⚠️⚠️ 超限 → 429，不返回流")
        void overLimitRejected() {
            var controller = new RunEventController(new FakeEventQuery(), allowAll(),
                    principal(), limiter(RateLimitPort.Decision.REJECTED));

            assertThatThrownBy(() -> controller.streamEvents(RUN_ID, null))
                    .as("⚠️⚠️ 长连接长期占用资源，必须限流")
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value())
                            .isEqualTo(429));
        }

        @Test
        @DisplayName("⚠️ 限流组件故障（FAIL_CLOSED）→ 同样 429")
        void failClosedAlsoRejected() {
            var controller = new RunEventController(new FakeEventQuery(), allowAll(),
                    principal(), limiter(RateLimitPort.Decision.FAIL_CLOSED));

            assertThatThrownBy(() -> controller.streamEvents(RUN_ID, null))
                    .isInstanceOf(ResponseStatusException.class);
        }
    }

    // ── 红线 9：长连接不放松授权 ───────────────────────────────────

    @Nested
    @DisplayName("S-5 授权与存在性判定不因长连接而放松")
    class AuthStillEnforced {

        @Test
        @DisplayName("⚠️⚠️ 授权失败 → 拒绝（不建连）")
        void deniedStillRejected() {
            var controller = new RunEventController(new FakeEventQuery(), denyAll(),
                    principal(), limiter(RateLimitPort.Decision.ALLOWED));

            assertThatThrownBy(() -> controller.streamEvents(RUN_ID, null))
                    .as("⚠️⚠️ 长连接不是绕过授权的后门")
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("⚠️ Run 不存在 → 404（不泄露存在性）")
        void missingRunReturns404() {
            var query = new FakeEventQuery();
            query.state = null;
            var controller = new RunEventController(query, allowAll(), principal(),
                    limiter(RateLimitPort.Decision.ALLOWED));

            assertThatThrownBy(() -> controller.streamEvents(RUN_ID, null))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value())
                            .isEqualTo(404));
        }
    }

    // ── S-2 / S-3：终态必须关闭连接 ────────────────────────────────

    @Nested
    @DisplayName("S-2/S-3 终态判定与连接关闭（D-3）")
    class TerminalClosesConnection {

        @Test
        @DisplayName("⚠️⚠️ 终态 Run → 发完成事件并 complete()（连接不得悬挂）")
        void terminalRunCompletesConnection() throws Exception {
            var query = new FakeEventQuery().with(event(1, "RUN_STARTED"));
            query.state = "SUCCEEDED";

            RecordingSender sender = new RecordingSender();
            var streamer = new RunEventStreamer(30L, 3000L);
            streamer.stream(null, query, TENANT, RUN_ID, 0L,
                    () -> query.state,
                    RunEventController.terminalStates(),
                    (type, meta) -> true,
                    sender);

            assertThat(sender.awaitComplete(5000))
                    .as("⚠️⚠️ 终态 Run 必须让连接结束（否则连接悬挂占资源）")
                    .isTrue();
            assertThat(sender.frames)
                    .as("⚠️ 必须先推事件、再推完成标记")
                    .contains("RUN_STARTED#1", "run-terminal#");
        }

        @Test
        @DisplayName("✅ 非终态 Run → 不发完成事件（连接保持）")
        void nonTerminalKeepsConnectionOpen() throws Exception {
            var query = new FakeEventQuery().with(event(1, "RUN_STARTED"));
            query.state = "RUNNING";

            RecordingSender sender = new RecordingSender();
            var streamer = new RunEventStreamer(30L, 5000L);
            streamer.stream(null, query, TENANT, RUN_ID, 0L,
                    () -> query.state,
                    RunEventController.terminalStates(),
                    (type, meta) -> true,
                    sender);

            assertThat(sender.awaitFirstFrame(3000))
                    .as("⚠️ 但事件仍应被推送")
                    .isTrue();
            assertThat(sender.completed.get())
                    .as("⚠️ 非终态 Run 不得被误关（否则客户端拿不到后续事件）")
                    .isZero();
            assertThat(sender.frames).contains("RUN_STARTED#1");
        }
    }

    // ── S-6：载荷白名单仍生效 ──────────────────────────────────────

    @Nested
    @DisplayName("S-6 载荷白名单（P2-D05 语义不得因重构回退）")
    class PayloadWhitelist {

        private final RunEventController controller = new RunEventController(
                new FakeEventQuery(), allowAll(), principal(),
                limiter(RateLimitPort.Decision.ALLOWED));

        @Test
        @DisplayName("⚠️⚠️ studentName 不在白名单 → 长连接推送中也不得出现")
        void sensitiveFieldNeverStreamed() {
            assertThat(controller.isPayloadFieldAllowed("studentName"))
                    .as("⚠️⚠️ 学生姓名绝不可出现在事件流中")
                    .isFalse();
            assertThat(controller.isPayloadFieldAllowed("idCard")).isFalse();
            assertThat(controller.isPayloadFieldAllowed("state")).isTrue();
            assertThat(controller.isPayloadFieldAllowed("toolName")).isTrue();
        }

        @Test
        @DisplayName("⚠️ 白名单是「未列入即丢弃」（fail-closed）")
        void whitelistIsFailClosed() {
            assertThat(controller.isPayloadFieldAllowed("someBrandNewField"))
                    .as("⚠️ 新增字段若忘记加白名单，必须默认丢弃而非默认放行")
                    .isFalse();
        }
    }
}