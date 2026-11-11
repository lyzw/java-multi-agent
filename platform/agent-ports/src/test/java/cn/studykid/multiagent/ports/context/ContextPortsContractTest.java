package cn.studykid.multiagent.ports.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.ports.model.ModelMessage;
import cn.studykid.multiagent.ports.model.ModelRequest;

/**
 * M6 端口与值对象契约守卫（contract-only，零运行时接线）。
 *
 * <p><b>为什么必须有它</b>：{@code agent-ports} 若反向依赖 {@code agent-context}，
 * 会形成 Maven 循环并破坏 ADR-011 / C1；若 {@code ContextSpec} 自带渲染方法，
 * 就会与 {@code ContextLayout} 形成两套口径，出现「计量算 A、实际发 B」的静默失真。
 * 本测试把端口数量、签名中性、值对象 fail-closed 与纯数据载体边界一次性钉死。
 */
class ContextPortsContractTest {

    private static final Path CONTEXT_PORT_DIR =
            Path.of("src/main/java/cn/studykid/multiagent/ports/context");

    private static final Set<String> EXPECTED_PORTS = Set.of(
            "TokenCounter", "ContextCheckpointPort", "SummaryGeneratorPort", "ContextAssemblerPort");

    private static final Set<String> DISALLOWED_IMPORT_FRAGMENTS = Set.of(
            "cn.studykid.multiagent.context",
            "org.springframework",
            "java.sql",
            "javax.sql",
            "jakarta.persistence");

    @Test
    @DisplayName("ports/context 恰好四个端口，且源码不反向依赖算法层/框架/数据库")
    void exactlyFourPortsStayNeutral() throws IOException {
        Set<String> found = new HashSet<>();
        Pattern declaration = Pattern.compile("(?m)^public interface (\\w+)");
        for (String source : readContextSources()) {
            Matcher matcher = declaration.matcher(source);
            while (matcher.find()) {
                found.add(matcher.group(1));
            }
            List<String> imports = source.lines()
                    .filter(line -> line.startsWith("import "))
                    .toList();
            assertThat(imports)
                    .as("端口层不得引入算法层 / Spring / JDBC / JPA；Javadoc 提及不算，只看 import")
                    .noneMatch(line -> DISALLOWED_IMPORT_FRAGMENTS.stream()
                            .anyMatch(line::contains));
        }
        assertThat(found).containsExactlyInAnyOrderElementsOf(EXPECTED_PORTS);
    }

    @Test
    @DisplayName("四个端口的方法签名冻结，且泛型签名不泄漏算法层类型")
    void portMethodSignaturesAreFrozenAndNeutral() {
        assertThat(signatures(TokenCounter.class))
                .containsExactlyInAnyOrder("count(ContextBlocks)->int");
        assertThat(signatures(ContextCheckpointPort.class))
                .containsExactlyInAnyOrder(
                        "load(String,String,String)->Optional",
                        "advance(String,CheckpointDelta,long,long)->boolean");
        assertThat(signatures(SummaryGeneratorPort.class))
                .containsExactlyInAnyOrder("merge(String,List,EffectiveContextPolicy)->Optional");
        assertThat(signatures(ContextAssemblerPort.class))
                .containsExactlyInAnyOrder("assemble(ContextSpec)->AssembledContext");

        for (Class<?> port : List.of(TokenCounter.class, ContextCheckpointPort.class,
                SummaryGeneratorPort.class, ContextAssemblerPort.class)) {
            for (Method method : port.getDeclaredMethods()) {
                assertThat(genericSignature(method))
                        .as("%s#%s 不得把 agent-context 类型放进端口签名",
                                port.getSimpleName(), method.getName())
                        .doesNotContain("cn.studykid.multiagent.context");
            }
        }
    }

    @Test
    @DisplayName("ContextSpec 身份字段 fail-closed、可空文本归一化、且没有任何渲染便捷方法")
    void contextSpecIsPureDataAndFailsClosedOnIdentityGaps() {
        assertThatThrownBy(() -> spec(null, "scope:1", "m", policy(), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("tenantId");
        assertThatThrownBy(() -> spec("t", "  ", "m", policy(), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("scopeKey");
        assertThatThrownBy(() -> spec("t", "scope:1", " ", policy(), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("modelId");
        assertThatThrownBy(() -> new ContextSpec("t", "scope:1", "m", "sys", "{}", "first",
                "", List.of(), List.of(), "now", null, null, -1L, null, 0L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("policy");
        assertThatThrownBy(() -> new ContextSpec("t", "scope:1", "m", "sys", "{}", "first",
                "", List.of(), List.of(), "now", policy(), null, -2L, null, 0L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("upToSeq");
        assertThatThrownBy(() -> new ContextSpec("t", "scope:1", "m", "sys", "{}", "first",
                "", List.of(), List.of(), "now", policy(), null, -1L, null, -1L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("fencingToken");

        ContextSpec normalized = spec("t", "scope:1", "m", policy(), null);
        assertThat(normalized.capabilities().modelId()).isEqualTo("m");
        assertThat(normalized.capabilities().known()).isFalse();
        assertThat(normalized.memoryBlock()).isEmpty();
        assertThat(normalized.ragBlocks()).isEmpty();
        assertThat(normalized.messages()).isEmpty();
        assertThat(normalized.carryOverStateJson()).isNull();

        List<String> allowed = new ArrayList<>(
                Arrays.stream(ContextSpec.class.getRecordComponents())
                        .map(RecordComponent::getName).toList());
        allowed.addAll(List.of("equals", "hashCode", "toString"));
        assertThat(Arrays.stream(ContextSpec.class.getDeclaredMethods())
                .map(Method::getName).toList())
                .as("ContextSpec 只能是纯数据载体，禁止 toContextBlocks/toolSchemaMap 等第二套渲染口径")
                .containsExactlyInAnyOrderElementsOf(allowed);
    }

    @Test
    @DisplayName("EffectiveContextPolicy 保留全部已裁决字段且不在端口层提供默认值")
    void effectivePolicyKeepsAllResolvedFieldsWithoutDefaults() {
        assertThat(Arrays.stream(EffectiveContextPolicy.class.getRecordComponents())
                .map(RecordComponent::getName).toList())
                .containsExactly(
                        "enabled", "maxInputTokens", "triggerRatio", "targetRatio", "reserveRatio",
                        "recentTurns", "pinFirstUserTurn", "ragRatio", "memoryRatio", "summaryRatio",
                        "strategy", "summarizerModel", "summaryMaxTokens", "summaryTimeoutMs",
                        "maxCompactionsPerRun", "carryOver", "checkpointTtlSeconds", "policyVersion");

        Map<String, Class<?>> types = Arrays.stream(EffectiveContextPolicy.class.getRecordComponents())
                .collect(Collectors.toMap(RecordComponent::getName, RecordComponent::getType));
        assertThat(types)
                .containsEntry("strategy", String.class)
                .containsEntry("carryOver", String.class)
                .containsEntry("policyVersion", String.class);

        assertThat(Arrays.stream(EffectiveContextPolicy.class.getDeclaredMethods())
                .map(Method::getName).toList())
                .doesNotContain("defaults", "off", "withEnabled");

        EffectiveContextPolicy invalid = new EffectiveContextPolicy(
                true, 16_000, 0.70, 0.40, 0.10, 6, true, 0.30, 0.15, 0.25,
                null, null, 1200, 8000, 3, null, 1800, null);
        assertThat(invalid.strategy()).isEmpty();
        assertThat(invalid.carryOver()).isEmpty();
        assertThat(invalid.policyVersion()).isEmpty();
    }

    @Test
    @DisplayName("AssembledContext 对缺失请求/非法水位/负计数 fail-closed")
    void assembledContextFailsClosed() {
        assertThatThrownBy(() -> new AssembledContext(null, null, 0, false, 10, 10, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("request");
        assertThatThrownBy(() -> new AssembledContext(modelRequest(), null, 4, false, 10, 10, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("tier");
        assertThatThrownBy(() -> new AssembledContext(modelRequest(), null, 0, false, -1, 10, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("token");
        assertThatThrownBy(() -> new AssembledContext(modelRequest(), null, 0, false, 10, 10, -1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("compactionCount");

        AssembledContext valid = new AssembledContext(modelRequest(), null, 0, false, 10, 8, 0);
        assertThat(valid.request()).isNotNull();
        assertThat(valid.checkpointDelta()).isNull();
        assertThat(AssembledContext.class.getDeclaredConstructors()).hasSize(1);
    }

    @Test
    @DisplayName("Checkpoint 空对象恒过期，非空对象游标自洽；CheckpointDelta 绝不写空状态")
    void checkpointValueObjectsFailClosed() {
        Checkpoint empty = Checkpoint.empty();
        assertThat(empty.isEmpty()).isTrue();
        assertThat(empty.toSeq()).isEqualTo(-1L);
        assertThat(empty.expired(0L)).isTrue();
        assertThat(empty.expired(Long.MAX_VALUE)).isTrue();

        Checkpoint live = new Checkpoint("scope:1", 0L, 3L, 2, "m", "cp_v1",
                "{\"goal\":\"x\"}", "sha256:abc", null, 2, false, 100L);
        assertThat(live.expired(99L)).isFalse();
        assertThat(live.expired(100L)).isTrue();
        assertThat(live.toSeq()).isEqualTo(3L);
        assertThatThrownBy(() -> new Checkpoint("scope:1", 5L, 3L, 2, "m", "cp_v1",
                "{\"goal\":\"x\"}", "sha256:abc", null, 2, false, 100L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("fromSeq");

        CheckpointDelta delta = delta();
        assertThat(delta.sourceMessageIds()).isEmpty();
        assertThatThrownBy(() -> new CheckpointDelta(" ", 0L, 3L, 2, "m", "{}", "sha", null, 2, false, 1800, "cp_v1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("scopeKey");
        assertThatThrownBy(() -> new CheckpointDelta("scope:1", 0L, 3L, 2, "m", "", "sha", null, 2, false, 1800, "cp_v1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("stateJson");
        assertThatThrownBy(() -> new CheckpointDelta("scope:1", 0L, 3L, 2, "m", "{}", "", null, 2, false, 1800, "cp_v1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("digest");
        assertThatThrownBy(() -> new CheckpointDelta("scope:1", 4L, 3L, 2, "m", "{}", "sha", null, 2, false, 1800, "cp_v1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("fromSeq");
        assertThatThrownBy(() -> new CheckpointDelta("scope:1", 0L, 3L, 2, "m", "{}", "sha", null, 0, false, 1800, "cp_v1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("tier");
        assertThatThrownBy(() -> new CheckpointDelta("scope:1", 0L, 3L, 2, "m", "{}", "sha", null, 2, false, 0, "cp_v1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("checkpointTtlSeconds");
    }

    @Test
    @DisplayName("SummaryGeneratorPort 用不透明状态 JSON 隔离算法层，返回 Optional<String>")
    void summaryPortUsesOpaqueStateInsteadOfAlgorithmTypes() throws Exception {
        Method merge = SummaryGeneratorPort.class.getDeclaredMethod(
                "merge", String.class, List.class, EffectiveContextPolicy.class);
        assertThat(merge.getReturnType()).isEqualTo(Optional.class);
        assertThat(merge.getGenericReturnType()).isInstanceOf(ParameterizedType.class);
        assertThat(((ParameterizedType) merge.getGenericReturnType()).getActualTypeArguments()[0])
                .isEqualTo(String.class);
        assertThat(genericSignature(merge)).doesNotContain("cn.studykid.multiagent.context");
        assertThat(SummaryGeneratorPort.class.getDeclaredMethods()).hasSize(1);
    }

    @Test
    @DisplayName("ContextAssemblerPort 只有一个装配入口，且返回唯一送模型请求")
    void assemblerPortHasSingleEntry() throws Exception {
        Method assemble = ContextAssemblerPort.class.getDeclaredMethod("assemble", ContextSpec.class);
        assertThat(assemble.getReturnType()).isEqualTo(AssembledContext.class);
        assertThat(assemble.isDefault()).isFalse();
        assertThat(Arrays.stream(ContextAssemblerPort.class.getDeclaredMethods())
                .filter(m -> !m.isDefault()).count()).isEqualTo(1);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static List<String> readContextSources() throws IOException {
        try (Stream<Path> paths = Files.list(CONTEXT_PORT_DIR)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .map(path -> {
                        try {
                            return Files.readString(path);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .toList();
        }
    }

    private static Set<String> signatures(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .map(method -> method.getName() + "("
                        + Arrays.stream(method.getParameterTypes())
                                .map(Class::getSimpleName).collect(Collectors.joining(","))
                        + ")->" + method.getReturnType().getSimpleName())
                .collect(Collectors.toSet());
    }

    private static String genericSignature(Method method) {
        return Arrays.stream(method.getGenericParameterTypes())
                .map(java.lang.reflect.Type::getTypeName).collect(Collectors.joining(","))
                + "->" + method.getGenericReturnType().getTypeName();
    }

    private static EffectiveContextPolicy policy() {
        return new EffectiveContextPolicy(
                true, 16_000, 0.70, 0.40, 0.10, 6, true, 0.30, 0.15, 0.25,
                "TIERED", null, 1200, 8000, 3, "CLIENT", 1800, "cp_v1");
    }

    private static ContextSpec spec(String tenantId, String scopeKey, String modelId,
                                    EffectiveContextPolicy policy, ModelCapabilities capabilities) {
        return new ContextSpec(tenantId, scopeKey, modelId, "sys", "{}", "first",
                null, null, null, "now", policy, capabilities, -1L, null, 0L);
    }

    private static ModelRequest modelRequest() {
        return new ModelRequest("m", "sys", "user", 0.2, 100,
                Duration.ofSeconds(1), Map.of());
    }

    private static CheckpointDelta delta() {
        return new CheckpointDelta("scope:1", 0L, 3L, 2, "m", "{\"goal\":\"x\"}",
                "sha256:abc", null, 2, false, 1800, "cp_v1");
    }
}
