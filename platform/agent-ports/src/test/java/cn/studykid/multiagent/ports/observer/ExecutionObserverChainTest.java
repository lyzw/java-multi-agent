package cn.studykid.multiagent.ports.observer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.ports.model.ModelResponse;
import cn.studykid.multiagent.ports.observer.ExecutionObserver.LoopOutcome;
import cn.studykid.multiagent.ports.observer.ExecutionObserver.ToolCallSummary;

/**
 * M6-c Observer 契约守卫：新增事件默认空实现、链转发、异常隔离、旧事件零改动。
 *
 * <p>M6 是 contract-only，本测试<b>不</b>验证「谁在生产链路调用了新方法」——
 * 那属于 M7 接线与 M6-d 的零接线守卫。这里只保证：现有实现者不改一行也能编译，
 * 且链上真正覆写新方法的观测者能被可靠调用。
 */
class ExecutionObserverChainTest {

    @Test
    @DisplayName("onContextCompacted 是 default 空实现，既有观测者零改动即可编译")
    void defaultCompactionMethodIsNoOpForLegacyObservers() throws Exception {
        Method compact = ExecutionObserver.class.getDeclaredMethod(
                "onContextCompacted", String.class, String.class,
                int.class, int.class, int.class, int.class, boolean.class);
        assertThat(compact.isDefault()).isTrue();
        assertThat(compact.getReturnType()).isEqualTo(void.class);

        LegacyTrimObserver legacy = new LegacyTrimObserver();
        assertThat(Arrays.stream(LegacyTrimObserver.class.getDeclaredMethods())
                .map(Method::getName).toList())
                .doesNotContain("onContextCompacted");
        assertThatCode(() -> legacy.onContextCompacted("t-1", "r-1", 2, 100, 80, 20, false))
                .as("默认实现不得抛异常，也不得隐式改写旧 trim 事件")
                .doesNotThrowAnyException();
        assertThat(legacy.trimCalls).isZero();
    }

    @Test
    @DisplayName("链转发压缩事件到每个观测者，单个观测者异常不影响其他观测者")
    void chainForwardsCompactionAndIsolatesExceptions() {
        RecordingCompactionObserver first = new RecordingCompactionObserver("first", false);
        RecordingCompactionObserver failing = new RecordingCompactionObserver("failing", true);
        RecordingCompactionObserver last = new RecordingCompactionObserver("last", false);
        ExecutionObserverChain chain = new ExecutionObserverChain(List.of(first, failing, last));

        assertThatCode(() -> chain.onContextCompacted("t-1", "r-1", 2, 1024, 640, 384, true))
                .doesNotThrowAnyException();

        CompactionCall expected = new CompactionCall("t-1", "r-1", 2, 1024, 640, 384, true);
        assertThat(first.calls).containsExactly(expected);
        assertThat(failing.calls).containsExactly(expected);
        assertThat(last.calls).containsExactly(expected);
    }

    @Test
    @DisplayName("旧 onContextTrimmed 语义与链转发保持不变（CH-8 关闭态等价）")
    void legacyTrimStillForwardedUnchanged() throws Exception {
        LegacyTrimObserver a = new LegacyTrimObserver();
        LegacyTrimObserver b = new LegacyTrimObserver();
        new ExecutionObserverChain(List.of(a, b))
                .onContextTrimmed("t-1", "r-1", 3, 120, List.of("tool_a", "tool_b"));

        assertThat(a.trimCalls).isEqualTo(1);
        assertThat(b.trimCalls).isEqualTo(1);
        assertThat(a.lastDroppedTools).containsExactly("tool_a", "tool_b");

        Method trim = ExecutionObserver.class.getDeclaredMethod(
                "onContextTrimmed", String.class, String.class,
                int.class, int.class, List.class);
        assertThat(trim.isDefault()).isFalse();
        assertThat(ExecutionObserverChain.class.getDeclaredMethod(
                "onContextCompacted", String.class, String.class,
                int.class, int.class, int.class, int.class, boolean.class))
                .isNotNull();
    }

    @Test
    @DisplayName("压缩事件签名保持中性，不泄漏算法层或框架类型")
    void compactSignatureStaysNeutral() throws Exception {
        Method compact = ExecutionObserver.class.getDeclaredMethod(
                "onContextCompacted", String.class, String.class,
                int.class, int.class, int.class, int.class, boolean.class);
        List<String> typeNames = Arrays.stream(compact.getGenericParameterTypes())
                .map(Type::getTypeName).toList();
        assertThat(typeNames).noneMatch(name -> name.contains("cn.studykid.multiagent.context")
                || name.contains("org.springframework")
                || name.contains("java.sql"));
        assertThat(compact.getGenericReturnType().getTypeName()).isEqualTo("void");
    }

    @Test
    @DisplayName("Observer 契约源码不反向依赖算法层 / Spring / JDBC")
    void observerSourcesStayNeutral() throws IOException {
        Set<String> disallowed = Set.of(
                "cn.studykid.multiagent.context",
                "org.springframework",
                "java.sql",
                "javax.sql");
        for (String file : List.of("ExecutionObserver.java", "ExecutionObserverChain.java")) {
            Path source = Path.of("src/main/java/cn/studykid/multiagent/ports/observer", file);
            List<String> imports = Files.readString(source).lines()
                    .filter(line -> line.startsWith("import "))
                    .toList();
            assertThat(imports)
                    .as("%s 的 import 不得越界", file)
                    .noneMatch(line -> disallowed.stream().anyMatch(line::contains));
        }
    }

    // ── fakes ────────────────────────────────────────────────────────────

    private record CompactionCall(String tenantId, String runId, int tier,
                                  int beforeTokens, int afterTokens,
                                  int droppedTokens, boolean fallback) {
    }

    private abstract static class BaseObserver implements ExecutionObserver {
        @Override
        public void onLoopStart(String tenantId, String runId, int maxSteps) {
        }

        @Override
        public void onStepStart(String tenantId, String runId, int step) {
        }

        @Override
        public void onModelCallComplete(String tenantId, String runId, int step,
                                        ModelResponse response, Duration latency) {
        }

        @Override
        public void onToolCallComplete(String tenantId, String runId, int step,
                                       ToolCallSummary toolResult, Duration latency) {
        }

        @Override
        public void onContextTrimmed(String tenantId, String runId, int droppedRounds,
                                     int droppedTokens, List<String> droppedTools) {
        }

        @Override
        public void onLoopComplete(String tenantId, String runId,
                                   LoopOutcome outcome, int totalSteps) {
        }
    }

    private static final class LegacyTrimObserver extends BaseObserver {
        private int trimCalls;
        private List<String> lastDroppedTools = List.of();

        @Override
        public void onContextTrimmed(String tenantId, String runId, int droppedRounds,
                                     int droppedTokens, List<String> droppedTools) {
            trimCalls++;
            lastDroppedTools = List.copyOf(droppedTools);
        }
    }

    private static final class RecordingCompactionObserver extends BaseObserver {
        private final String name;
        private final boolean fail;
        private final List<CompactionCall> calls = new ArrayList<>();

        private RecordingCompactionObserver(String name, boolean fail) {
            this.name = name;
            this.fail = fail;
        }

        @Override
        public void onContextCompacted(String tenantId, String runId, int tier,
                                       int beforeTokens, int afterTokens,
                                       int droppedTokens, boolean fallback) {
            calls.add(new CompactionCall(tenantId, runId, tier, beforeTokens, afterTokens,
                    droppedTokens, fallback));
            if (fail) {
                throw new IllegalStateException("observer failed by design");
            }
        }
    }
}
