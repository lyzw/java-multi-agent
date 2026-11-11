package cn.studykid.multiagent.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.context.CompactionPipeline.Summarizer;
import cn.studykid.multiagent.context.ContextAssembler.AssemblyInput;
import cn.studykid.multiagent.context.ContextPolicy.CarryOver;
import cn.studykid.multiagent.context.ContextPolicy.Strategy;
import cn.studykid.multiagent.context.ContextPolicyRejectedException.Reason;
import cn.studykid.multiagent.context.WaterLevel.Thresholds;
import cn.studykid.multiagent.ports.context.AssembledContext;
import cn.studykid.multiagent.ports.context.ContextSpec;
import cn.studykid.multiagent.ports.context.EffectiveContextPolicy;
import cn.studykid.multiagent.ports.context.ModelCapabilities;
import cn.studykid.multiagent.ports.model.ModelMessage;
import cn.studykid.multiagent.ports.model.ModelMessage.Role;
import cn.studykid.multiagent.ports.model.ModelMessage.ToolCallEcho;
import cn.studykid.multiagent.ports.model.ModelRequest;

/**
 * M7-a 统一装配内核行为测试（ADR-013 D38 / 开发计划 §6.4-②）。
 *
 * <p>⚠️ <b>关闭等价桥是构造级保证，不是「再实现一遍 trim」</b>：本类用
 * {@code isSameAs(legacy)} 锁死「返回同一个 {@link ModelRequest} 实例」。
 * 一旦有人改为「复制字段后重建」，即使逐字段值暂时相同，也已埋下
 * 与 {@code ContextBudgetTrimmer} 漂移、悄悄改请求字节的隐患（[D11]）。
 *
 * <p>⚠️ <b>挤出压力而非弱断言</b>：W2/W3 用例用「先用超大窗口量出 before，
 * 再按目标 usage 反推 window」的办法精确把用量挤进目标水位带，
 * 避免硬编码窗口导致「水位没触发却断言通过」的空转。
 */
class ContextAssemblerTest {

    private static final String TENANT_ID = "11111111-1111-1111-1111-111111111111";
    private static final String SCOPE_KEY = "run:42";
    private static final String MODEL_ID = "minimax:MiniMax-M2.5";
    private static final String STABLE_SYSTEM = "你是错题归因助手，必须遵守以下约束";
    private static final String LEGACY_SYSTEM = STABLE_SYSTEM + "\n[裁剪说明] 早期轮次已移除";
    private static final String FIRST_USER_TURN = "原始任务：解方程 2x+3=7";
    private static final String CURRENT_INPUT = "请解释第二步";
    private static final String LEGACY_USER_CONTENT = "legacy 单轮冗余输入";
    private static final String MEMORY = "用户偏好：先讲思路再给答案";
    private static final String RAG = "命中：概念混淆的定义";
    private static final String SUMMARY = "{\"goal\":\"解方程\",\"status\":\"done\"}";
    private static final Map<String, Object> TOOL_SCHEMAS = Map.of(
            "grade_homework", Map.of("type", "object", "required", List.of("qid")));
    private static final Thresholds THRESHOLDS = Thresholds.defaults();

    private final HeuristicTokenCounter counter = new HeuristicTokenCounter();

    private ContextAssembler assembler() {
        return new ContextAssembler(counter, THRESHOLDS);
    }

    @Test
    @DisplayName("✅ enabled=false：返回 legacy 同一引用，零预算 / 零管线 / 零摘要 / 零事件")
    void disabledReturnsLegacyByIdentity() {
        ModelRequest legacy = legacyRequest();
        CountingSummarizer summarizer = new CountingSummarizer();
        ContextSpec spec = spec(policy(false, "TIERED", 16_000), rounds(2, 20),
                MEMORY, List.of(RAG));

        AssembledContext out = assembler().assemble(
                new AssemblyInput(spec, legacy, summarizer, 4));

        assertThat(out.request())
                .as("⛔ 关闭态重建了请求：逐位一致必须靠返回同一引用保证，不能靠复制字段")
                .isSameAs(legacy);
        assertThat(out.request().systemPrompt()).isEqualTo(LEGACY_SYSTEM);
        assertThat(out.request().messages()).isEqualTo(legacy.messages());
        assertThat(out.checkpointDelta()).isNull();
        assertThat(out.tier()).isZero();
        assertThat(out.fallback()).isFalse();
        assertThat(out.beforeTokens()).isZero();
        assertThat(out.afterTokens()).isZero();
        assertThat(out.compactionCount()).isEqualTo(4);
        assertThat(summarizer.calls).as("⛔ 关闭态调用了摘要器").isZero();
    }

    @Test
    @DisplayName("✅ enabled=true 但 strategy=OFF：同样走关闭等价桥（策略档与总开关一致）")
    void offStrategyAlsoReturnsLegacyByIdentity() {
        ModelRequest legacy = legacyRequest();
        CountingSummarizer summarizer = new CountingSummarizer();
        ContextSpec spec = spec(policy(true, "OFF", 16_000), List.of(),
                MEMORY, List.of(RAG));

        assertThat(ContextAssembler.isEnabled(spec.policy())).isFalse();
        AssembledContext out = assembler().assemble(
                new AssemblyInput(spec, legacy, summarizer, 0));

        assertThat(out.request()).isSameAs(legacy);
        assertThat(out.beforeTokens()).isZero();
        assertThat(out.afterTokens()).isZero();
        assertThat(out.checkpointDelta()).isNull();
        assertThat(summarizer.calls).isZero();
    }

    @Test
    @DisplayName("✅ 开启态 W0：只换 systemPrompt/messages，6 个非上下文字段逐字保留")
    void enabledW0SwapsOnlyContextBearingFields() {
        ModelRequest legacy = legacyRequest();
        CountingSummarizer summarizer = new CountingSummarizer();
        ContextSpec spec = spec(policy(true, "TIERED", 1_000_000), rounds(1, 40),
                MEMORY, List.of(RAG));

        AssembledContext out = assembler().assemble(
                new AssemblyInput(spec, legacy, summarizer, 2));
        ModelRequest req = out.request();

        assertThat(req).isNotSameAs(legacy);
        assertThat(req.modelId()).isEqualTo(legacy.modelId());
        assertThat(req.userContent()).isEqualTo(legacy.userContent());
        assertThat(req.temperature()).isEqualTo(legacy.temperature());
        assertThat(req.maxTokens()).isEqualTo(legacy.maxTokens());
        assertThat(req.timeout()).isEqualTo(legacy.timeout());
        assertThat(req.toolSchemas()).isEqualTo(legacy.toolSchemas());

        // [D6] 稳定前缀逐字等于 spec 基段，legacy 的裁剪说明不得残留。
        assertThat(req.systemPrompt()).isEqualTo(STABLE_SYSTEM)
                .doesNotContain("裁剪说明").doesNotContain(MEMORY).doesNotContain(RAG);
        // 首轮锚是独立 pinned 消息，⛔ 不得重复渲染 / 双算预算。
        assertThat(req.messages()).filteredOn(m -> m.content().contains(FIRST_USER_TURN))
                .hasSize(1);
        assertThat(req.messages()).filteredOn(m -> m.content().contains(CURRENT_INPUT))
                .hasSize(1);
        assertThat(req.messages()).filteredOn(m -> m.content().contains("历史用户"))
                .as("⛔ messages 未切换为装配结果").isEmpty();

        // 不可信块全部进围栏 USER 消息，绝不进 systemPrompt。
        assertThat(req.messages()).anySatisfy(m -> {
            assertThat(m.role()).isEqualTo(Role.USER);
            assertThat(m.content()).contains(ContextLayout.FENCE_OPEN)
                    .contains("[memory]").contains(MEMORY)
                    .contains(ContextLayout.FENCE_CLOSE);
        });
        assertThat(req.messages()).anySatisfy(m -> {
            assertThat(m.role()).isEqualTo(Role.USER);
            assertThat(m.content()).contains(ContextLayout.FENCE_OPEN)
                    .contains("[rag[0]]").contains(RAG)
                    .contains(ContextLayout.FENCE_CLOSE);
        });
        assertThat(req.messages())
                .filteredOn(m -> m.content().contains(ContextLayout.FENCE_OPEN))
                .allSatisfy(m -> assertThat(m.role()).isEqualTo(Role.USER));

        assertThat(out.tier()).isZero();
        assertThat(out.fallback()).isFalse();
        assertThat(out.beforeTokens()).isPositive();
        assertThat(out.afterTokens()).isPositive();
        assertThat(out.compactionCount()).isEqualTo(2);
        assertThat(out.checkpointDelta()).isNull();
        assertThat(summarizer.calls).as("⛔ W0 不该调用摘要器").isZero();
    }

    @Test
    @DisplayName("✅ 开启态 W2：整轮裁剪后 tier=2、压缩次数+1、零 LLM、围栏裁剪说明")
    void enabledW2CountsCompactionAndKeepsStablePrefix() {
        // ⚠️ 用无 TOOL 的普通回合：W1 没有可外置的工具结果，无法代偿 W2 的裁剪目标，
        //    否则 usage 虽到 0.80，W1 外置后已低于 target，最终档位会停在 W1（用例空转）。
        List<ModelMessage> msgs = plainRounds(24, 400);
        int before = fullBefore(msgs);
        int window = windowFor(before, 0.80);
        int hardLimit = window - (int) Math.ceil(window * 0.10);
        CountingSummarizer summarizer = new CountingSummarizer();

        AssembledContext out = assembler().assemble(new AssemblyInput(
                spec(policy(true, "TIERED", window, 2), msgs, "", List.of()),
                legacyRequest(), summarizer, 2));

        assertThat(out.tier()).as("⛔ 挤出压力未命中 W2，用例空转").isEqualTo(2);
        assertThat(out.compactionCount()).isEqualTo(3);
        assertThat(out.beforeTokens()).isGreaterThan(out.afterTokens());
        assertThat(out.afterTokens()).isLessThanOrEqualTo(hardLimit);
        assertThat(out.fallback()).isFalse();
        assertThat(summarizer.calls).as("⛔ W2 是零 LLM 档，不得调用摘要器").isZero();

        ModelRequest req = out.request();
        assertThat(req.systemPrompt()).isEqualTo(STABLE_SYSTEM);
        assertThat(req.messages()).filteredOn(m -> m.content().contains(FIRST_USER_TURN))
                .hasSize(1);
        assertThat(req.messages()).filteredOn(m -> m.content().contains(CURRENT_INPUT))
                .hasSize(1);
        assertThat(req.messages()).anySatisfy(m -> {
            assertThat(m.role()).isEqualTo(Role.USER);
            assertThat(m.content()).contains(ContextLayout.FENCE_OPEN)
                    .contains("[trim_notice]").contains("上下文预算裁剪")
                    .contains(ContextLayout.FENCE_CLOSE);
        });
    }

    @Test
    @DisplayName("✅ 开启态 W3：注入摘要器产出围栏摘要块，内核自身零模型调用")
    void enabledW3UsesInjectedSummarizer() {
        List<ModelMessage> msgs = rounds(6, 400);
        int before = fullBefore(msgs);
        int window = windowFor(before, 0.95);
        CountingSummarizer summarizer = new CountingSummarizer();

        AssembledContext out = assembler().assemble(new AssemblyInput(
                spec(policy(true, "TIERED", window), msgs, "", List.of()),
                legacyRequest(), summarizer, 1));

        assertThat(out.tier()).as("⛔ 挤出压力未命中 W3，用例空转").isEqualTo(3);
        assertThat(summarizer.calls).isEqualTo(1);
        assertThat(summarizer.lastDelta).isNotEmpty();
        assertThat(out.compactionCount()).isEqualTo(2);
        assertThat(out.fallback()).isFalse();

        ModelRequest req = out.request();
        assertThat(req.systemPrompt()).isEqualTo(STABLE_SYSTEM).doesNotContain("goal");
        assertThat(req.messages()).anySatisfy(m -> {
            assertThat(m.role()).isEqualTo(Role.USER);
            assertThat(m.content()).contains(ContextLayout.FENCE_OPEN)
                    .contains("[summary]").contains("goal")
                    .contains(ContextLayout.FENCE_CLOSE);
        });
    }

    @Test
    @DisplayName("⛔ 固定块超窗 → CONTEXT_PINNED_OVERFLOW，绝不静默夹紧 / 截断")
    void hardPinnedOverflowFailsClosed() {
        ContextSpec spec = new ContextSpec(
                TENANT_ID, SCOPE_KEY, MODEL_ID,
                "系统指令".repeat(200), RequestContextProjection.canonical(TOOL_SCHEMAS),
                FIRST_USER_TURN, "", List.of(), List.of(), CURRENT_INPUT,
                policy(true, "TIERED", 64), ModelCapabilities.unknown(MODEL_ID),
                -1, null, 1L);
        CountingSummarizer summarizer = new CountingSummarizer();

        assertThatThrownBy(() -> assembler().assemble(new AssemblyInput(
                spec, legacyRequest(), summarizer, 0)))
                .isInstanceOf(ContextBudgetException.class)
                .extracting(e -> ((ContextBudgetException) e).reason())
                .isEqualTo(ContextBudgetException.Reason.CONTEXT_PINNED_OVERFLOW);
        assertThat(summarizer.calls).isZero();
    }

    @Test
    @DisplayName("⛔ 非法策略档 → CONTEXT_POLICY_INVALID，不静默回退默认水位")
    void invalidStrategyFailsClosed() {
        ContextSpec spec = spec(policy(true, "BOGUS", 16_000), List.of(), "", List.of());
        CountingSummarizer summarizer = new CountingSummarizer();

        assertThatThrownBy(() -> assembler().assemble(new AssemblyInput(
                spec, legacyRequest(), summarizer, 0)))
                .isInstanceOf(ContextPolicyRejectedException.class)
                .extracting(e -> ((ContextPolicyRejectedException) e).reason())
                .isEqualTo(Reason.CONTEXT_POLICY_INVALID);
        assertThat(summarizer.calls).isZero();
    }

    @Test
    @DisplayName("⛔ AssemblyInput 缺关闭基线 / 负计数 → fail-closed")
    void assemblyInputFailsClosed() {
        ContextSpec spec = spec(policy(false, "TIERED", 16_000), List.of(), "", List.of());
        CountingSummarizer summarizer = new CountingSummarizer();

        assertThatThrownBy(() -> new AssemblyInput(spec, null, summarizer, 0))
                .as("⛔ 缺 legacyRequest 会在关闭态丢失逐位等价基线")
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AssemblyInput(spec, legacyRequest(), summarizer, -1))
                .as("⛔ 负 priorCompactions 会让压缩次数统计失真")
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── 夹具 ────────────────────────────────────────────────────────────

    private static ModelRequest legacyRequest() {
        return new ModelRequest(
                MODEL_ID, LEGACY_SYSTEM, LEGACY_USER_CONTENT, 0.27, 2_048,
                Duration.ofSeconds(31), TOOL_SCHEMAS,
                List.of(ModelMessage.user("历史用户"), ModelMessage.assistant("历史助手")));
    }

    private static EffectiveContextPolicy policy(boolean enabled, String strategy,
                                                  int window) {
        return policy(enabled, strategy, window, 6);
    }

    private static EffectiveContextPolicy policy(boolean enabled, String strategy,
                                                  int window, int recentTurns) {
        return new EffectiveContextPolicy(
                enabled, window, 0.70, 0.40, 0.10, recentTurns, true,
                0.30, 0.15, 0.25, strategy, "summary:model", 1_200, 8_000,
                3, "CLIENT", 1_800, "cp_m7a_v1");
    }

    private static ContextSpec spec(EffectiveContextPolicy policy,
                                    List<ModelMessage> messages,
                                    String memory,
                                    List<String> rag) {
        return new ContextSpec(
                TENANT_ID, SCOPE_KEY, MODEL_ID, STABLE_SYSTEM,
                RequestContextProjection.canonical(TOOL_SCHEMAS),
                FIRST_USER_TURN, memory, rag, messages, CURRENT_INPUT,
                policy, ModelCapabilities.unknown(MODEL_ID), -1, null, 77L);
    }

    /** 无 TOOL 的普通回合：每个 ASSISTANT 是独立可整轮丢弃单元，W1 无可外置对象。 */
    private static List<ModelMessage> plainRounds(int n, int chars) {
        List<ModelMessage> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(ModelMessage.assistant("助手回合 " + i + " " + "y".repeat(chars)));
        }
        return List.copyOf(out);
    }

    private static List<ModelMessage> rounds(int n, int toolChars) {
        List<ModelMessage> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(ModelMessage.assistantToolCalls("调用工具 " + i, List.of(
                    new ToolCallEcho("call-" + i, "grade_homework", "{\"qid\":" + i + "}"))));
            out.add(ModelMessage.toolResult("grade_homework",
                    "工具" + i + "结果" + "x".repeat(toolChars), "call-" + i));
        }
        return List.copyOf(out);
    }

    private static CompactionPipeline.Input pipeInput(List<ModelMessage> messages) {
        return new CompactionPipeline.Input(
                STABLE_SYSTEM, RequestContextProjection.canonical(TOOL_SCHEMAS),
                FIRST_USER_TURN, List.of(), "", messages, CURRENT_INPUT, -1, null);
    }

    private int fullBefore(List<ModelMessage> messages) {
        CompactionPipeline.Input input = pipeInput(messages);
        ContextPolicy wide = new ContextPolicy(
                true, 1_000_000, 0.70, 0.40, 0.10, 6, true,
                0.30, 0.15, 0.25, Strategy.TIERED, null, 1_200, 8_000,
                3, CarryOver.CLIENT, 1_800, "cp_wide");
        ContextBudget budget = BudgetAllocator.allocate(wide, counter,
                new BudgetAllocator.FixedBlocks(input.systemPrompt(), input.toolSchemas(),
                        input.firstUserTurn(), input.currentInput()));
        return new CompactionPipeline(counter)
                .compact(input, budget, wide, THRESHOLDS, new CountingSummarizer())
                .beforeTokens();
    }

    private static int windowFor(int before, double targetUsage) {
        return (int) Math.ceil(before / targetUsage);
    }

    /** 摘要器桩：记录调用次数与增量，绝不自己发请求（本模块零 LLM）。 */
    private static final class CountingSummarizer implements Summarizer {
        private int calls;
        private Optional<String> result = Optional.of(SUMMARY);
        private List<ModelMessage> lastDelta = List.of();

        @Override
        public Optional<String> summarize(List<ModelMessage> delta, String oldSummary) {
            calls++;
            lastDelta = delta;
            return result;
        }
    }
}
